"""组装 :class:`~opik.message_processing.data_loss.FlushResult` 值。

数据丢失报告的读取侧。它持有描述一次 flush 所需的两个协作者——队列（经由
streamer）和 :class:`DataLossTracker`——这样任何调用方都无需自行收集它们。
每个连接捆绑（connection bundle）拥有一个实例，并由其上的每个客户端共享。
"""

import logging
import time
from typing import TYPE_CHECKING

from . import data_loss

if TYPE_CHECKING:
    from . import streamer as streamer_module


LOGGER = logging.getLogger(__name__)


class FlushReporter:
    def __init__(
        self,
        streamer: "streamer_module.Streamer",
        data_loss_tracker: data_loss.DataLossTracker,
    ) -> None:
        self._streamer = streamer
        self._data_loss_tracker = data_loss_tracker

    def marker(self) -> "data_loss.DropMarker":
        """标识丢弃历史中当前位置的不透明令牌。

        在 flush 之前获取一个；之后将其传给 :meth:`build_result`，
        即可只把期间观察到的丢弃归因于该次 flush。
        """
        return self._data_loss_tracker.marker()

    def build_result(
        self, marker: "data_loss.DropMarker", *, flushed: bool
    ) -> "data_loss.FlushResult":
        dropped_messages, dropped_items, failures = self._data_loss_tracker.drops_since(
            marker
        )
        result = data_loss.FlushResult(
            flushed=flushed,
            remaining_queue_size=self._streamer.queue_size(),
            dropped_messages=dropped_messages,
            dropped_items=dropped_items,
            failures=tuple(failures),
        )
        if not result.success:
            LOGGER.error(
                "Opik flush 完成时发生数据丢失：%d 条消息 / %d 个条目被丢弃，"
                "仍有 %d 条在队列中。详情请查看 Opik.last_flush_result。",
                result.dropped_messages,
                result.dropped_items,
                result.remaining_queue_size,
            )
        return result

    def build_errors_report(self) -> "data_loss.ErrorsReport":
        """面向整个发送器的数据丢失快照，与任何单次 flush 无关。"""
        total_messages, total_items, failures = self._data_loss_tracker.total_drops()
        return data_loss.ErrorsReport(
            total_dropped_messages=total_messages,
            total_dropped_items=total_items,
            failures=tuple(failures),
            generated_at=time.time(),
        )
