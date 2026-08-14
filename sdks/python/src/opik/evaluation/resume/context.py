"""
将已存储的实验转换为实时 ``ResumeContext`` 的编排器。

读取持久化状态（:mod:`opik.evaluation.resume.state`），可选地加载已解析
项 id 的检查点（默认读取器来自 :mod:`opik.evaluation.resume.checkpoint`，
可注入），在**其固定版本**上解析数据集，并根据实验现有的项统计每个
数据集项的已完成运行次数。

恢复始终绑定到特定的 :class:`DatasetVersion`。如果实验记录没有固定的
``dataset_version_name``（因为它是在恢复支持之前创建的、由外部客户端
创建的，或是针对禁用版本管理的数据集创建的），本模块会拒绝构建上下文，
并抛出 :class:`ExperimentNotResumable`。针对不断变动的 ``Dataset`` HEAD
进行迭代会静默地包含/排除自原始运行以来新增或删除的项，从而破坏恢复契约。

检查点读取器通过注入提供，因此本模块不硬性依赖本地文件实现：未来的
服务端工件存储可以接入而无需改动此编排器。
"""

import dataclasses
import logging
from typing import Callable, Dict, List, Mapping, Optional

from ... import exceptions as opik_exceptions
from ...api_objects import opik_client
from ...api_objects.dataset import dataset
from ...api_objects.experiment import experiment as experiment_module
from ...api_objects.experiment import experiment_item
from .. import types
from . import checkpoint as checkpoint_module
from . import state as state_module

LOGGER = logging.getLogger(__name__)


CheckpointReader = Callable[[str], Optional[List[str]]]


@dataclasses.dataclass(frozen=True)
class ResumeContext:
    """继续一个被中断的实验所需的全部内容。

    ``dataset`` 始终是固定到原始 ``evaluate()`` 调用所运行版本的
    :class:`DatasetVersion`。这里的类型收窄是有意为之——针对不断变动的数据集
    HEAD 的恢复行为是未定义的。
    """

    experiment: experiment_module.Experiment
    dataset: dataset.DatasetVersion
    completed_runs_by_item_id: Mapping[str, int]
    default_runs_per_item: int
    dataset_filter_string: Optional[str]
    nb_samples: Optional[int]
    candidate_dataset_item_ids: Optional[List[str]]
    error_tolerance: types.ErrorTolerance
    """原始评估调用运行时使用的容错设置，以便恢复后的运行不会静默回退到默认值。"""


def prepare_resume_context(
    client: opik_client.Opik,
    experiment_id: str,
    *,
    checkpoint_reader: Optional[CheckpointReader] = None,
) -> ResumeContext:
    """
    根据已存储的实验构建 :class:`ResumeContext`。

    ``checkpoint_reader`` 默认为本地文件读取器，但可以被替换（用于测试或
    其他存储）。当实验需要检查点而读取器返回 ``None`` 时，会抛出
    :class:`LocalCheckpointMissing`。

    Raises:
        opik.exceptions.ExperimentNotFound: 实验不存在时。
        ExperimentNotResumable: 实验被标记为不可恢复时。
        LocalCheckpointMissing: 必需的检查点无法获取时。
    """
    reader = checkpoint_reader or checkpoint_module.read_checkpoint

    experiment = client.get_experiment_by_id(experiment_id)
    persisted = _require_resumable_state(
        experiment_id=experiment_id,
        persisted=state_module.read_resume_state(experiment),
    )

    candidate_ids: Optional[List[str]] = None
    if persisted.requires_local_checkpoint:
        candidate_ids = reader(experiment_id)
        if candidate_ids is None:
            raise opik_exceptions.LocalCheckpointMissing(
                f"实验 {experiment_id} 需要已解析数据集项 id 的检查点，"
                "但无法读取。请在写入检查点的机器上恢复，或显式重新提供"
                "原始的 dataset_item_ids。"
            )

    dataset_version = _resolve_dataset_version(
        client=client,
        dataset_name=experiment.dataset_name,
        project_name=experiment.project_name,
        dataset_version_name=persisted.dataset_version_name,
    )

    return ResumeContext(
        experiment=experiment,
        dataset=dataset_version,
        completed_runs_by_item_id=_count_completed_runs_by_item_id(
            experiment.get_items()
        ),
        default_runs_per_item=persisted.default_runs_per_item,
        dataset_filter_string=persisted.dataset_filter_string,
        nb_samples=persisted.nb_samples,
        candidate_dataset_item_ids=candidate_ids,
        error_tolerance=persisted.error_tolerance,
    )


def _require_resumable_state(
    *,
    experiment_id: str,
    persisted: Optional[state_module.PersistedResumeState],
) -> state_module.ResumableState:
    """
    根据 :func:`read_resume_state` 返回的和类型进行分发。

    存在时返回 :class:`ResumableState` 载荷；对所有不可恢复的路径（缺少
    blob、显式的不可恢复标记）抛出 :class:`ExperimentNotResumable`。
    """
    if persisted is None:
        raise opik_exceptions.ExperimentNotResumable(
            f"实验 {experiment_id} 的配置中没有恢复状态 "
            "（由较旧版本的 SDK 或外部客户端创建）。"
            "恢复需要固定版本的数据集版本，但该信息未被记录。"
        )
    if isinstance(persisted, state_module.NonResumableState):
        raise opik_exceptions.ExperimentNotResumable(
            f"实验 {experiment_id} 无法恢复：{persisted.reason}"
        )
    return persisted


def _resolve_dataset_version(
    *,
    client: opik_client.Opik,
    dataset_name: str,
    project_name: Optional[str],
    dataset_version_name: str,
) -> dataset.DatasetVersion:
    """
    始终返回固定到原始运行版本的 :class:`DatasetVersion`。调用方已通过
    :func:`_ensure_resumable` 验证 ``dataset_version_name`` 非空。
    """
    dataset_ = client.get_dataset(name=dataset_name, project_name=project_name)
    return dataset_.get_version_view(dataset_version_name)


def _count_completed_runs_by_item_id(
    experiment_items: List[experiment_item.ExperimentItemContent],
) -> Mapping[str, int]:
    """统计每个数据集项完全完成的试验次数。"""
    counts: Dict[str, int] = {}
    for item in experiment_items:
        if not is_trial_fully_completed(item):
            continue
        counts[item.dataset_item_id] = counts.get(item.dataset_item_id, 0) + 1
    return counts


def is_trial_fully_completed(
    item: experiment_item.ExperimentItemContent,
) -> bool:
    """当且仅当该试验到达引擎的仅正常路径代码行时返回 True。

    引擎仅在任务 + 评分 + 评分日志全部返回后才设置 ``trace.output``（参见
    :func:`opik.evaluation.engine.helpers.evaluate_llm_task_context`）。
    因此，持久化 trace 的 output 是否存在，正是恢复所需的完成信号。
    """
    return item.evaluation_task_output is not None
