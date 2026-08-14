"""
面向评估器的粘合代码，使 :mod:`opik.evaluation.evaluator` 保持精简。

两项职责：

1. 为每个评估入口构建恢复状态，并将其嵌入 ``experiment_config``。
   各入口流入状态的 kwargs 各不相同，因此这里放置一个小型的逐入口辅助
   函数，而不是放在 :mod:`state`（它保持通用）中。

2. 在项解析完成后，当仅凭配置无法重建迭代时（评估时使用了采样器或显式的
   ``dataset_item_ids``），将这些 id 写入本地检查点。

检查点写入器通过注入提供，因此本模块不硬性依赖本地文件持久化：可以为
测试或未来的后端接入其他存储。
"""

import logging
from typing import Any, Callable, Dict, List, Optional, Union

from ...api_objects.dataset import dataset
from ..samplers import base_dataset_sampler
from ..types import ErrorTolerance
from . import checkpoint as checkpoint_module
from . import state as state_module

LOGGER = logging.getLogger(__name__)


CheckpointWriter = Callable[[str, List[str]], None]


_NO_DATASET_VERSION_REASON = (
    "评估针对的是未启用版本管理的数据集；恢复需要固定的数据集版本，"
    "以便迭代能够对原始运行所见的同一组项可重现"
)


def resume_state_for_evaluate(
    *,
    experiment_config: Optional[Dict[str, Any]],
    dataset_: Union[dataset.Dataset, dataset.DatasetVersion],
    trial_count: int,
    dataset_filter_string: Optional[str],
    nb_samples: Optional[int],
    dataset_sampler: Optional[base_dataset_sampler.BaseDatasetSampler],
    dataset_item_ids: Optional[List[str]],
    error_tolerance: ErrorTolerance,
) -> Dict[str, Any]:
    """为 ``evaluate`` / ``evaluate_prompt`` / ``evaluate_optimization_trial`` 构建恢复 blob。"""
    dataset_version_name = _dataset_version_name_or_none(dataset_)
    if dataset_version_name is None:
        return state_module.embed_non_resumable_state(
            experiment_config,
            state_module.NonResumableState(reason=_NO_DATASET_VERSION_REASON),
        )

    return state_module.embed_resumable_state(
        experiment_config,
        state_module.ResumableState(
            default_runs_per_item=trial_count,
            dataset_filter_string=dataset_filter_string,
            dataset_version_name=dataset_version_name,
            nb_samples=nb_samples,
            requires_local_checkpoint=(
                dataset_sampler is not None or dataset_item_ids is not None
            ),
            error_tolerance=error_tolerance,
        ),
    )


def write_checkpoint_if_needed(
    *,
    experiment_id: str,
    resolved_ids: Optional[List[str]],
    checkpoint_writer: Optional[CheckpointWriter] = None,
) -> None:
    """
    当仅凭配置无法重建迭代时（使用了采样器或显式的 ``dataset_item_ids``），
    对已解析的项 id 进行快照。

    调用方直接传入 ``resolved_ids``：显式的 ``dataset_item_ids`` 在前期即
    已知（无需消费迭代器），而采样器路径已经物化了用于采样的列表。流式
    场景传入 ``None`` 且为无操作——仅靠恢复状态即可重现。

    ``checkpoint_writer`` 默认为 ``checkpoint.write_checkpoint``，在调用时
    解析，因此对 checkpoint 模块的模块级补丁在测试中会生效。
    """
    if resolved_ids is None:
        return

    writer = checkpoint_writer or checkpoint_module.write_checkpoint
    writer(experiment_id, list(resolved_ids))


def _dataset_version_name_or_none(
    dataset_: Union[dataset.Dataset, dataset.DatasetVersion],
) -> Optional[str]:
    """
    返回用于恢复时要固定的版本名称。

    对于 ``DatasetVersion``，我们可直接获得版本名称。对于 ``Dataset``，我们
    使用评估时刻的最新版本名称——也就是 ``create_experiment`` 写入实验记录
    的同一版本 id。当数据集没有任何版本时返回 None。
    """
    version_info = dataset_.get_version_info()
    if version_info is None:
        return None
    return version_info.version_name
