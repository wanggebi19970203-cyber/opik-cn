from __future__ import annotations
import dataclasses
import datetime
from dataclasses import field
from typing import Optional, Any, Dict, List, Union, Literal, Set, Type, TypeVar

from . import arguments_utils
from .preprocessing import constants
from ..rest_api.core import pydantic_utilities
from ..rest_api.types import span_write, trace_write
from ..types import (
    SpanType,
    ErrorInfoDict,
    LLMProvider,
    AttachmentEntityType,
    TraceSource,
)

T = TypeVar("T", bound="BaseMessage")


def from_db_message_dict(message_class: Type[T], data: Dict[str, Any]) -> T:
    """从字典反序列化消息。

    通过从构造函数参数中过滤掉 init=False 的字段，并在对象创建后恢复这些字段来处理它们。
    """
    # 获取消息类的字段信息
    fields_info = {f.name: f for f in dataclasses.fields(message_class)}
    init_fields = {name for name, f in fields_info.items() if f.init}

    # 将数据拆分为 init 字段和非 init 字段
    init_data = {k: v for k, v in data.items() if k in init_fields}
    non_init_data = {
        k: v for k, v in data.items() if k in fields_info and k not in init_fields
    }

    # 使用 init 字段创建对象
    obj = message_class(**init_data)

    # 恢复非 init 字段
    for key, value in non_init_data.items():
        setattr(obj, key, value)

    return obj


@dataclasses.dataclass
class BaseMessage:
    delivery_time: float = field(init=False, default=0.0)
    delivery_attempts: int = field(init=False, default=1)

    message_id: Optional[int] = field(init=False, default=None)
    message_type: str = field(init=False, default="BaseMessage")

    def as_payload_dict(self) -> Dict[str, Any]:
        # 这里不使用 dataclasses.as_dict()，
        # 因为它会尝试深拷贝所有对象，遇到不可序列化的对象时会失败
        data = {**self.__dict__}
        attributes_to_remove = [
            "delivery_time",
            "delivery_attempts",
            constants.MARKER_ATTRIBUTE_NAME,
            "message_id",
            "message_type",
        ]
        for attribute in attributes_to_remove:
            data.pop(attribute, None)
        return data

    def as_db_message_dict(self) -> Dict[str, Any]:
        return {**self.__dict__}

    @property
    def item_count(self) -> int:
        """此消息携带的数据项（traces/spans/...）数量。

        批量消息可携带多个；普通消息计为一个。用于报告消息被丢弃时丢失了多少数据。
        """
        batch = getattr(self, "batch", None)
        return len(batch) if batch is not None else 1


@dataclasses.dataclass
class CreateTraceMessage(BaseMessage):
    trace_id: str
    project_name: str
    name: Optional[str]
    start_time: datetime.datetime
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]
    error_info: Optional[ErrorInfoDict]
    thread_id: Optional[str]
    last_updated_at: Optional[datetime.datetime]
    source: TraceSource
    environment: Optional[str] = None

    message_type = "CreateTraceMessage"

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("trace_id")
        return data

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class UpdateTraceMessage(BaseMessage):
    """
    "不建议使用。仅为公共 API 中的底层更新操作而保留"
    """

    trace_id: str
    project_name: str
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]
    error_info: Optional[ErrorInfoDict]
    thread_id: Optional[str]
    source: str
    environment: Optional[str] = None

    message_type = "UpdateTraceMessage"

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("trace_id")
        return data

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class CreateSpanMessage(BaseMessage):
    span_id: str
    trace_id: str
    project_name: str
    parent_span_id: Optional[str]
    name: Optional[str]
    start_time: datetime.datetime
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]
    type: SpanType
    usage: Optional[Dict[str, int]]
    model: Optional[str]
    provider: Optional[Union[LLMProvider, str]]
    error_info: Optional[ErrorInfoDict]
    total_cost: Optional[float]
    last_updated_at: Optional[datetime.datetime]
    source: TraceSource
    environment: Optional[str] = None

    message_type = "CreateSpanMessage"

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("span_id")
        data["total_estimated_cost"] = data.pop("total_cost")
        return data

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class UpdateSpanMessage(BaseMessage):
    """不建议使用。仅为公共 API 中的底层更新操作而保留"""

    span_id: str
    parent_span_id: Optional[str]
    trace_id: str
    project_name: str
    end_time: Optional[datetime.datetime]
    input: Optional[Dict[str, Any]]
    output: Optional[Dict[str, Any]]
    metadata: Optional[Dict[str, Any]]
    tags: Optional[List[str]]
    usage: Optional[Dict[str, int]]
    model: Optional[str]
    provider: Optional[Union[LLMProvider, str]]
    error_info: Optional[ErrorInfoDict]
    total_cost: Optional[float]
    source: str
    environment: Optional[str] = None

    message_type = "UpdateSpanMessage"

    def __post_init__(self) -> None:
        if self.input is not None:
            self.input = arguments_utils.recursive_shallow_copy(self.input)
        if self.output is not None:
            self.output = arguments_utils.recursive_shallow_copy(self.output)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["id"] = data.pop("span_id")
        data["total_estimated_cost"] = data.pop("total_cost")
        return data

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class FeedbackScoreMessage(BaseMessage):
    """
    消息处理器中没有对应的处理程序，它仅作为 BatchMessage 的一个条目存在
    """

    id: str
    project_name: str
    name: str
    value: float
    source: str
    reason: Optional[str] = None
    category_name: Optional[str] = None

    message_type = "FeedbackScoreMessage"


@dataclasses.dataclass
class AddFeedbackScoresBatchMessage(BaseMessage):
    batch: List[FeedbackScoreMessage]
    supports_batching: bool = True

    message_type = "AddFeedbackScoresBatchMessage"

    def __post_init__(self) -> None:
        self.batch = _deserialize_base_message_batch(self.batch, FeedbackScoreMessage)

    def as_db_message_dict(self) -> Dict[str, Any]:
        return _serialize_base_message_batch_to_dict(self.__dict__, self.batch)


@dataclasses.dataclass
class AddTraceFeedbackScoresBatchMessage(AddFeedbackScoresBatchMessage):
    message_type = "AddTraceFeedbackScoresBatchMessage"


@dataclasses.dataclass
class AddSpanFeedbackScoresBatchMessage(AddFeedbackScoresBatchMessage):
    message_type = "AddSpanFeedbackScoresBatchMessage"


@dataclasses.dataclass
class ThreadsFeedbackScoreMessage(FeedbackScoreMessage):
    """
    消息处理器中没有对应的处理程序，它仅作为 AddThreadsFeedbackScoresBatchMessage 的一个条目存在
    """

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data["thread_id"] = data.pop("id")
        return data


@dataclasses.dataclass
class AddThreadsFeedbackScoresBatchMessage(BaseMessage):
    batch: List[ThreadsFeedbackScoreMessage]
    supports_batching: bool = True

    message_type = "AddThreadsFeedbackScoresBatchMessage"

    def __post_init__(self) -> None:
        self.batch = _deserialize_base_message_batch(
            self.batch, ThreadsFeedbackScoreMessage
        )

    def as_db_message_dict(self) -> Dict[str, Any]:
        return _serialize_base_message_batch_to_dict(self.__dict__, self.batch)


@dataclasses.dataclass
class CreateSpansBatchMessage(BaseMessage):
    batch: List[span_write.SpanWrite]

    message_type = "CreateSpansBatchMessage"

    def __post_init__(self) -> None:
        self.batch = _deserialize_pydantic_batch(self.batch, span_write.SpanWrite)

    def as_db_message_dict(self) -> Dict[str, Any]:
        return _serialize_pydantic_batch_to_dict(self.__dict__, self.batch)

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class CreateTraceBatchMessage(BaseMessage):
    batch: List[trace_write.TraceWrite]

    message_type = "CreateTraceBatchMessage"

    def __post_init__(self) -> None:
        self.batch = _deserialize_pydantic_batch(self.batch, trace_write.TraceWrite)

    def as_db_message_dict(self) -> Dict[str, Any]:
        return _serialize_pydantic_batch_to_dict(self.__dict__, self.batch)

    @staticmethod
    def fields_to_anonymize() -> Set[str]:
        return {"input", "output", "metadata"}


@dataclasses.dataclass
class GuardrailBatchItemMessage(BaseMessage):
    """
    消息处理器中没有对应的处理程序，它仅作为 BatchMessage 的一个条目存在
    """

    project_name: Optional[str]
    entity_id: str
    secondary_id: str
    name: str
    result: Union[Literal["passed", "failed"], Any]
    config: Dict[str, Any]
    details: Dict[str, Any]

    message_type = "GuardrailBatchItemMessage"


@dataclasses.dataclass
class GuardrailBatchMessage(BaseMessage):
    batch: List[GuardrailBatchItemMessage]
    supports_batching: bool = True

    message_type = "GuardrailBatchMessage"

    def __post_init__(self) -> None:
        self.batch = _deserialize_base_message_batch(
            self.batch, GuardrailBatchItemMessage
        )

    def as_db_message_dict(self) -> Dict[str, Any]:
        return _serialize_base_message_batch_to_dict(self.__dict__, self.batch)

    def as_payload_dict(self) -> Dict[str, Any]:
        data = super().as_payload_dict()
        data.pop("supports_batching")
        return data


@dataclasses.dataclass
class AssertionResultMessage(BaseMessage):
    """
    消息处理器中没有对应的处理程序，它仅作为 AddAssertionResultsBatchMessage 的一个条目存在。
    """

    entity_id: str
    project_name: Optional[str]
    name: str
    status: Literal["passed", "failed"]
    source: Literal["sdk", "ui", "online_scoring"]
    reason: Optional[str] = None

    message_type = "AssertionResultMessage"


@dataclasses.dataclass
class AddAssertionResultsBatchMessage(BaseMessage):
    batch: List[AssertionResultMessage]
    entity_type: Literal["TRACE", "SPAN", "THREAD"] = "TRACE"
    # 生产者（Opik.log_assertion_results）已通过 sequence_splitter 进行拆分；
    # 绕过 BatchManager，使 streamer 不会尝试重新批处理（未为此消息类型注册批处理映射）。
    supports_batching: bool = False

    message_type = "AddAssertionResultsBatchMessage"

    def __post_init__(self) -> None:
        self.batch = _deserialize_base_message_batch(self.batch, AssertionResultMessage)

    def as_db_message_dict(self) -> Dict[str, Any]:
        return _serialize_base_message_batch_to_dict(self.__dict__, self.batch)


@dataclasses.dataclass
class ExperimentItemMessage(BaseMessage):
    """
    消息处理器中没有对应的处理程序，它仅作为 CreateExperimentItemsBatchMessage 的一个条目存在
    """

    id: str
    experiment_id: str
    trace_id: str
    dataset_item_id: str
    project_name: Optional[str] = None
    execution_policy: Optional[Dict[str, Any]] = None

    message_type = "ExperimentItemMessage"


@dataclasses.dataclass
class CreateExperimentItemsBatchMessage(BaseMessage):
    batch: List[ExperimentItemMessage]
    supports_batching: bool = True

    message_type = "CreateExperimentItemsBatchMessage"

    def __post_init__(self) -> None:
        self.batch = _deserialize_base_message_batch(self.batch, ExperimentItemMessage)

    def as_db_message_dict(self) -> Dict[str, Any]:
        return _serialize_base_message_batch_to_dict(self.__dict__, self.batch)


@dataclasses.dataclass
class CreateAttachmentMessage(BaseMessage):
    file_path: str
    file_name: str
    mime_type: Optional[str]
    entity_type: AttachmentEntityType
    entity_id: str
    project_name: str
    encoded_url_override: str
    delete_after_upload: bool = False

    message_type = "CreateAttachmentMessage"


@dataclasses.dataclass
class AttachmentSupportingMessage(BaseMessage):
    original_message: BaseMessage

    message_type = "AttachmentSupportingMessage"


def _deserialize_base_message_batch(
    batch: List[Any],
    item_class: Type[T],
) -> List[T]:
    """将批次中的字典项转换为 BaseMessage 派生对象。"""
    return [
        from_db_message_dict(item_class, item) if isinstance(item, dict) else item
        for item in batch
    ]


def _deserialize_pydantic_batch(
    batch: List[Any],
    item_class: Type[pydantic_utilities.T],
) -> List[Any]:
    """将批次中的字典项转换为 Pydantic 模型对象。"""
    return [
        pydantic_utilities.parse_obj_as(item_class, item)
        if isinstance(item, dict)
        else item
        for item in batch
    ]


def _serialize_base_message_batch_to_dict(
    instance_dict: Dict[str, Any],
    batch: List[T],
) -> Dict[str, Any]:
    """将 BaseMessage 批次序列化为字典。"""
    batch_items = [item.as_db_message_dict() for item in batch]
    return {**instance_dict, "batch": batch_items}


def _serialize_pydantic_batch_to_dict(
    instance_dict: Dict[str, Any],
    batch: List[Any],
) -> Dict[str, Any]:
    """将 Pydantic 模型批次序列化为字典。"""
    batch_items = [item.dict() for item in batch]
    return {**instance_dict, "batch": batch_items}
