from typing import Any, Dict, List, Optional

from opik import exceptions, id_helpers
from opik.rest_api import types as rest_api_types
from opik.types import FeedbackScoreDict
from . import bulk_item
from .. import constants

_JSON_LIKE_FIELDS = ("input", "output", "metadata")


def _validate_json_like_fields(
    source: Any,
    failure_reasons: List[str],
    location: str,
) -> None:
    """在后端期望 JSON 对象的位置拒绝 str/list。

    线缆类型既接受 ``str`` 和 ``List[Dict]``，也接受 ``Dict``，但字符串
    落入 ClickHouse 时会成为不透明 blob，UI 无法将其渲染为结构化的
    输入/输出。直接调用原始 Fern 客户端的调用者只有在数据已存储之后才会
    发现这一点，因此我们在此前置拒绝。
    """
    for field_name in _JSON_LIKE_FIELDS:
        value = getattr(source, field_name, None)
        if value is None or isinstance(value, dict):
            continue
        failure_reasons.append(
            f"{location}.{field_name} 必须是 dict，实际为 {type(value).__name__}"
        )


def _validate_feedback_score(
    score: Any,
    failure_reasons: List[str],
    location: str,
) -> None:
    """检查转换所读取的键，否则会引发 KeyError。"""
    if not isinstance(score, dict):
        failure_reasons.append(f"{location} 必须是 dict，实际为 {type(score).__name__}")
        return

    if not score.get("name"):
        failure_reasons.append(f"{location}.name 为必填项且不能为空")

    value = score.get("value")
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        failure_reasons.append(f"{location}.value 为必填项且必须是数字")


def _validate_error_info(
    error_info: Any,
    failure_reasons: List[str],
    location: str,
) -> None:
    """检查线缆模型所需的字段，避免出现原始的 pydantic 错误。"""
    if error_info is None:
        return

    if not isinstance(error_info, dict):
        failure_reasons.append(
            f"{location}.error_info 必须是 dict，实际为 {type(error_info).__name__}"
        )
        return

    for required_key in ("exception_type", "traceback"):
        if not error_info.get(required_key):
            failure_reasons.append(
                f"{location}.error_info.{required_key} 为必填项且不能为空"
            )


def _validate_record(
    record: bulk_item.ExperimentItemBulkRecord,
    index: int,
    failure_reasons: List[str],
) -> None:
    location = f"items[{index}]"

    if not record.dataset_item_id:
        failure_reasons.append(f"{location}.dataset_item_id 必须是非空字符串")

    if record.evaluate_task_result is not None and record.trace is not None:
        failure_reasons.append(
            f"{location} 必须提供 evaluate_task_result 或 trace 其中之一，但不能同时提供"
        )

    # 如果两个字段都没有，后端会静默创建一个输出为 null 的隐藏 trace，
    # 因此项目被存储了但对用户不可见。
    if record.evaluate_task_result is None and record.trace is None:
        failure_reasons.append(
            f"{location} 必须提供 evaluate_task_result 或 trace 其中之一"
        )

    if record.evaluate_task_result is not None and not isinstance(
        record.evaluate_task_result, dict
    ):
        failure_reasons.append(
            f"{location}.evaluate_task_result 必须是 dict，"
            f"实际为 {type(record.evaluate_task_result).__name__}"
        )

    if record.trace is not None:
        _validate_json_like_fields(record.trace, failure_reasons, f"{location}.trace")
        _validate_error_info(
            record.trace.error_info, failure_reasons, f"{location}.trace"
        )

    for span_index, span in enumerate(record.spans or []):
        span_location = f"{location}.spans[{span_index}]"
        _validate_json_like_fields(span, failure_reasons, span_location)
        _validate_error_info(span.error_info, failure_reasons, span_location)

    for score_index, score in enumerate(record.feedback_scores or []):
        _validate_feedback_score(
            score, failure_reasons, f"{location}.feedback_scores[{score_index}]"
        )


def _validate_project_name_consistency(
    records: List[bulk_item.ExperimentItemBulkRecord],
    project_name: Optional[str],
    failure_reasons: List[str],
) -> None:
    """镜像 ExperimentItemBulkUploadValidator。

    当设置了请求级 project_name 时，如果任何项目级 trace 指定了不同的项目，
    后端会拒绝整个上传。
    """
    if project_name is None or not project_name.strip():
        return

    for index, record in enumerate(records):
        trace = record.trace
        if (
            trace is None
            or trace.project_name is None
            or not trace.project_name.strip()
        ):
            continue
        if trace.project_name.casefold() != project_name.casefold():
            failure_reasons.append(
                f"items[{index}].trace.project_name ({trace.project_name!r}) 与 "
                f"上传的 project_name ({project_name!r}) 不匹配"
            )


def validate_records(
    records: List[bulk_item.ExperimentItemBulkRecord],
    project_name: Optional[str],
) -> None:
    """如果有任何记录无效，则引发 :class:`opik.exceptions.ValidationError`。"""
    failure_reasons: List[str] = []

    for index, record in enumerate(records):
        _validate_record(record, index, failure_reasons)

    _validate_project_name_consistency(records, project_name, failure_reasons)

    if failure_reasons:
        raise exceptions.ValidationError(
            prefix="batch_upload_items", failure_reasons=failure_reasons
        )


def _to_rest_trace(
    trace: bulk_item.ExperimentItemBulkTrace,
) -> rest_api_types.TraceExperimentItemBulkWriteView:
    return rest_api_types.TraceExperimentItemBulkWriteView(
        id=trace.id if trace.id is not None else id_helpers.generate_id(),
        project_name=trace.project_name,
        name=trace.name,
        start_time=trace.start_time,
        end_time=trace.end_time,
        input=trace.input,
        output=trace.output,
        metadata=trace.metadata,
        tags=trace.tags,
        error_info=(
            rest_api_types.ErrorInfoExperimentItemBulkWriteView(**trace.error_info)
            if trace.error_info is not None
            else None
        ),
        thread_id=trace.thread_id,
    )


def _to_rest_span(
    span: bulk_item.ExperimentItemBulkSpan,
) -> rest_api_types.SpanExperimentItemBulkWriteView:
    return rest_api_types.SpanExperimentItemBulkWriteView(
        id=span.id if span.id is not None else id_helpers.generate_id(),
        parent_span_id=span.parent_span_id,
        name=span.name,
        type=span.type,
        start_time=span.start_time,
        end_time=span.end_time,
        input=span.input,
        output=span.output,
        metadata=span.metadata,
        model=span.model,
        provider=span.provider,
        tags=span.tags,
        usage=span.usage,
        error_info=(
            rest_api_types.ErrorInfoExperimentItemBulkWriteView(**span.error_info)
            if span.error_info is not None
            else None
        ),
        total_estimated_cost=span.total_estimated_cost,
    )


def _to_rest_feedback_score(
    score: FeedbackScoreDict,
) -> rest_api_types.FeedbackScoreExperimentItemBulkWriteView:
    return rest_api_types.FeedbackScoreExperimentItemBulkWriteView(
        name=score["name"],
        value=score["value"],
        category_name=score.get("category_name"),
        reason=score.get("reason"),
        source=constants.FEEDBACK_SCORE_SOURCE_SDK,
    )


def to_rest_record(
    record: bulk_item.ExperimentItemBulkRecord,
) -> rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView:
    # 仅设置调用者实际提供的字段。后端将 evaluate_task_result 映射为
    # Jackson JsonNode，因此显式的 JSON null 会反序列化为 NullNode 而非
    # Java null——在 trace 旁发送 "evaluate_task_result": null 会触发
    # “不能同时提供两者”的校验器。未设置的字段会从请求体中省略，
    # 这正是后端所期望的。
    optional_fields: Dict[str, Any] = {}

    if record.evaluate_task_result is not None:
        optional_fields["evaluate_task_result"] = record.evaluate_task_result

    if record.trace is not None:
        optional_fields["trace"] = _to_rest_trace(record.trace)

    if record.spans is not None:
        optional_fields["spans"] = [_to_rest_span(span) for span in record.spans]

    if record.feedback_scores is not None:
        optional_fields["feedback_scores"] = [
            _to_rest_feedback_score(score) for score in record.feedback_scores
        ]

    return rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView(
        dataset_item_id=record.dataset_item_id,
        **optional_fields,
    )
