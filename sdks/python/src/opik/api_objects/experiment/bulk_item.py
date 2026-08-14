import dataclasses
import datetime
from typing import Any, Dict, List, Optional

from opik.types import ErrorInfoDict, FeedbackScoreDict, SpanType

JsonLike = Dict[str, Any]


@dataclasses.dataclass
class ExperimentItemBulkTrace:
    """在批量上传中与实验项目一起创建的 trace。"""

    start_time: datetime.datetime
    id: Optional[str] = None
    name: Optional[str] = None
    project_name: Optional[str] = None
    end_time: Optional[datetime.datetime] = None
    input: Optional[JsonLike] = None
    output: Optional[JsonLike] = None
    metadata: Optional[JsonLike] = None
    tags: Optional[List[str]] = None
    error_info: Optional[ErrorInfoDict] = None
    thread_id: Optional[str] = None


@dataclasses.dataclass
class ExperimentItemBulkSpan:
    """在批量上传中与实验项目一起创建的 span。"""

    start_time: datetime.datetime
    id: Optional[str] = None
    parent_span_id: Optional[str] = None
    name: Optional[str] = None
    type: Optional[SpanType] = None
    end_time: Optional[datetime.datetime] = None
    input: Optional[JsonLike] = None
    output: Optional[JsonLike] = None
    metadata: Optional[JsonLike] = None
    model: Optional[str] = None
    provider: Optional[str] = None
    tags: Optional[List[str]] = None
    usage: Optional[Dict[str, int]] = None
    error_info: Optional[ErrorInfoDict] = None
    total_estimated_cost: Optional[float] = None


@dataclasses.dataclass
class ExperimentItemBulkRecord:
    """
    通过 :meth:`Experiment.batch_upload_items` 上传的单个实验项目。

    提供 ``evaluate_task_result``（由后端创建 trace）或 ``trace``
    （由你提供），但绝不能同时提供两者。
    """

    dataset_item_id: str
    evaluate_task_result: Optional[JsonLike] = None
    trace: Optional[ExperimentItemBulkTrace] = None
    spans: Optional[List[ExperimentItemBulkSpan]] = None
    feedback_scores: Optional[List[FeedbackScoreDict]] = None
