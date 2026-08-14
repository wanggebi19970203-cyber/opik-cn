"""
持久化在 ``experiment_config[_opik_resume]`` 中的恢复状态。

该模式被建模为和类型：

- :class:`ResumableState` — 实验**可以**被恢复；携带确定性重建迭代所需的
  全部配置。
- :class:`NonResumableState` — 实验**无法**被恢复；携带面向用户的原因。

本模块负责编码（:func:`embed_resumable_state` /
:func:`embed_non_resumable_state`）与解码（:func:`read_resume_state`）。
该模式刻意只存储小型的、可重现的配置——绝不存储已解析的数据列表。当
仅凭配置无法重建迭代时（自定义采样器或显式的 ``dataset_item_ids``），
``requires_local_checkpoint`` 标志会将恢复流程指向配套的检查点文件（参见
:mod:`opik.evaluation.resume.checkpoint`）。

数据类和编码函数都不携带默认值——调用方必须显式提供。默认值应归属于
面向用户的 API（例如 ``evaluate_resume``），而非内部持久化边界，因为遗漏
字段会在那里静默产生损坏的 blob。
"""

import dataclasses
import json
import logging
from typing import Any, Dict, Optional, Union

from ...api_objects.experiment import experiment as experiment_module
from ..types import ErrorTolerance


LOGGER = logging.getLogger(__name__)

RESUME_METADATA_KEY = "_opik_resume"
RESUME_SCHEMA_VERSION = 1


@dataclasses.dataclass(frozen=True)
class ResumableState:
    """
    恢复时重建迭代所需的完整配置。

    ``dataset_version_name`` 是必填项：恢复仅针对固定的
    :class:`DatasetVersion` 操作，而绝不会针对不断变动的 ``Dataset`` HEAD。
    """

    default_runs_per_item: int
    dataset_filter_string: Optional[str]
    dataset_version_name: str
    nb_samples: Optional[int]
    requires_local_checkpoint: bool
    error_tolerance: ErrorTolerance


@dataclasses.dataclass(frozen=True)
class NonResumableState:
    """标记该实验无法被安全地恢复。"""

    reason: str


PersistedResumeState = Union[ResumableState, NonResumableState]


def embed_resumable_state(
    experiment_config: Optional[Dict[str, Any]],
    state: ResumableState,
) -> Dict[str, Any]:
    """
    将 :class:`ResumableState` blob 嵌入 ``experiment_config``。

    该 blob 以单个 JSON 字符串值的形式存储在 ``RESUME_METADATA_KEY`` 下，
    以便实验的 Configuration UI 只显示一行，而不是每个嵌套字段各占一行。
    """
    new_config = dict(experiment_config) if experiment_config else {}
    new_config[RESUME_METADATA_KEY] = json.dumps(
        {
            "schema_version": RESUME_SCHEMA_VERSION,
            "resumable": True,
            "default_runs_per_item": state.default_runs_per_item,
            "dataset_filter_string": state.dataset_filter_string,
            "dataset_version_name": state.dataset_version_name,
            "nb_samples": state.nb_samples,
            "requires_local_checkpoint": state.requires_local_checkpoint,
            "error_tolerance": int(state.error_tolerance),
        }
    )
    return new_config


def embed_non_resumable_state(
    experiment_config: Optional[Dict[str, Any]],
    state: NonResumableState,
) -> Dict[str, Any]:
    """
    将不可恢复标记嵌入 ``experiment_config``。

    仅存储标记 + 原因；不会泄漏任何迭代配置。出于与
    :func:`embed_resumable_state` 相同的 UI 展示原因，以 JSON 字符串序列化。
    """
    new_config = dict(experiment_config) if experiment_config else {}
    new_config[RESUME_METADATA_KEY] = json.dumps(
        {
            "schema_version": RESUME_SCHEMA_VERSION,
            "resumable": False,
            "non_resumable_reason": state.reason,
        }
    )
    return new_config


def read_resume_state(
    experiment: experiment_module.Experiment,
) -> Optional[PersistedResumeState]:
    """
    解码附加到实验上的恢复 blob。

    Returns:
        * 当 blob 将实验标记为可恢复且携带固定数据集版本时，返回
          :class:`ResumableState`。
        * 当 blob 将实验标记为不可恢复时，返回
          :class:`NonResumableState`。
        * 当不存在 blob 时返回 ``None``（例如由较旧版本的 SDK 或外部客户端
          创建）——调用方必须将其视为不可恢复，并给出明确的错误。

    没有固定 ``dataset_version_name`` 的可恢复 blob 会被降级为
    :class:`NonResumableState`。针对不断变动的数据集 HEAD 进行迭代会破坏
    恢复契约；与其静默地允许，此函数在解码边界即予以拒绝。
    """
    raw_state = _read_raw_resume_state(experiment)
    if raw_state is None:
        return None

    if not raw_state.get("resumable", False):
        return NonResumableState(
            reason=_coerce_optional_str(raw_state.get("non_resumable_reason"))
            or "未指定"
        )

    dataset_version_name = _coerce_optional_str(raw_state.get("dataset_version_name"))
    if dataset_version_name is None:
        return NonResumableState(
            reason=(
                "恢复 blob 缺少固定的 dataset_version_name；"
                "该实验无法被安全地恢复"
            )
        )

    return ResumableState(
        default_runs_per_item=_coerce_positive_int(
            raw_state.get("default_runs_per_item"), fallback=1
        ),
        dataset_filter_string=_coerce_optional_str(
            raw_state.get("dataset_filter_string")
        ),
        dataset_version_name=dataset_version_name,
        nb_samples=_coerce_optional_positive_int(raw_state.get("nb_samples")),
        requires_local_checkpoint=bool(
            raw_state.get("requires_local_checkpoint", False)
        ),
        error_tolerance=_coerce_error_tolerance(raw_state.get("error_tolerance")),
    )


def _read_raw_resume_state(
    experiment: experiment_module.Experiment,
) -> Optional[Dict[str, Any]]:
    """
    从实验元数据解码原始恢复 blob。

    该 blob 始终是 ``RESUME_METADATA_KEY`` 下的 JSON 编码字符串。
    任何其他形态（缺失、非字符串、JSON 格式错误）都被视为“无恢复状态”，
    以便调用方抛出相应的错误。
    """
    experiment_data = experiment.get_experiment_data()
    metadata = getattr(experiment_data, "metadata", None) or {}
    if not isinstance(metadata, dict):
        return None

    raw = metadata.get(RESUME_METADATA_KEY)
    if not isinstance(raw, str):
        return None

    try:
        decoded = json.loads(raw)
    except json.JSONDecodeError:
        LOGGER.warning(
            "无法解码实验元数据上的 JSON 恢复状态；"
            "将该实验视为没有恢复状态。",
            exc_info=True,
        )
        return None

    return decoded if isinstance(decoded, dict) else None


def _coerce_error_tolerance(value: Any) -> ErrorTolerance:
    """解码持久化的容错设置，失败时回退到默认值。

    在该字段存在之前写入的 blob 会省略它，而本 SDK 无法识别的值（由较新
    版本创建的实验）不应导致恢复失败——这两种情况都在默认值下恢复。
    """
    try:
        return ErrorTolerance(value)
    except (ValueError, TypeError):
        if value is not None:
            # 仅包含类型和有界摘录：该 blob 是外部输入，可能携带任意大的值。
            LOGGER.warning(
                "恢复状态中存在无法识别的 error_tolerance（%s：%.40s）；将在 %s 下恢复。",
                type(value).__name__,
                value,
                ErrorTolerance.METRIC_ERRORS.name,
            )
        return ErrorTolerance.METRIC_ERRORS


def _coerce_positive_int(value: Any, *, fallback: int) -> int:
    if isinstance(value, int) and value >= 1:
        return value
    return fallback


def _coerce_optional_positive_int(value: Any) -> Optional[int]:
    if isinstance(value, int) and value >= 1:
        return value
    return None


def _coerce_optional_str(value: Any) -> Optional[str]:
    if isinstance(value, str):
        return value
    return None
