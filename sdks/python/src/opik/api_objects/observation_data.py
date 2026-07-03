import dataclasses
import datetime
import logging
from typing import Any, Dict, List, Optional, TypeVar

import opik.api_objects.attachment as attachment
import opik.datetime_helpers as datetime_helpers
from opik.types import ErrorInfoDict, FeedbackScoreDict, TraceSource
from . import data_helpers

LOGGER = logging.getLogger(__name__)

ObservationDataT = TypeVar("ObservationDataT", bound="ObservationData")


@dataclasses.dataclass(kw_only=True)
class ObservationData:
    """
    TraceData 和 SpanData 的基类，包含通用属性和方法。

    此类使用 Python 3.10 的 kw_only=True 特性，允许在父类中定义可选参数，
    而子类可以有必需参数。
    """

    name: Optional[str] = None
    start_time: Optional[datetime.datetime] = dataclasses.field(
        default_factory=datetime_helpers.local_timestamp
    )
    end_time: Optional[datetime.datetime] = None
    metadata: Optional[Dict[str, Any]] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    feedback_scores: Optional[List[FeedbackScoreDict]] = None
    project_name: Optional[str] = None
    error_info: Optional[ErrorInfoDict] = None
    attachments: Optional[List[attachment.Attachment]] = None
    source: TraceSource = "sdk"
    environment: Optional[str] = None

    def update(self: ObservationDataT, **new_data: Any) -> ObservationDataT:
        """
        使用提供的键值对更新对象的属性。此方法在更新前检查属性是否存在，
        并为特定关键字（如 metadata、output、input、attachments 和 tags）适当地合并数据。
        如果键不对应于对象的属性或提供的值为 None，则跳过更新。

        Args:
            **new_data: 要更新的属性键值对。键应与对象上的现有属性匹配，
                值为 None 的将不会更新。

        Returns:
            更新后的对象实例（保留实际的子类类型）。
        """
        for key, value in new_data.items():
            if value is None:
                continue

            if key not in self.__dict__ and key != "prompts":
                LOGGER.debug(
                    "An attempt to update observation with parameter name it doesn't have: %s",
                    key,
                )
                continue

            if key == "metadata":
                self.metadata = data_helpers.merge_metadata(
                    self.metadata, new_metadata=value
                )
                continue
            elif key == "output":
                self.output = data_helpers.merge_outputs(self.output, new_outputs=value)
                continue
            elif key == "input":
                self.input = data_helpers.merge_inputs(self.input, new_inputs=value)
                continue
            elif key == "attachments":
                self._update_attachments(value)
                continue
            elif key == "tags":
                self.tags = data_helpers.merge_tags(self.tags, new_tags=value)
                continue
            elif key == "prompts":
                self.metadata = data_helpers.merge_metadata(
                    self.metadata, new_metadata=new_data.get("metadata"), prompts=value
                )
                continue

            self.__dict__[key] = value

        return self

    def init_end_time(self: ObservationDataT) -> ObservationDataT:
        """将 end_time 初始化为当前时间戳。"""
        self.end_time = datetime_helpers.local_timestamp()
        return self

    def _update_attachments(self, attachments: List[attachment.Attachment]) -> None:
        """将新附件与现有附件合并。"""
        if self.attachments is None:
            self.attachments = attachments
        else:
            self.attachments.extend(attachments)
