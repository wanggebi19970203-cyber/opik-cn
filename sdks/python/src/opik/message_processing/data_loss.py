"""对被最终丢弃（从未送达）的消息进行跟踪。

后台发送器从不会向用户代码抛出异常，也不会阻塞应用的关键路径。这留下了一个
缺口：当一批 trace/span 在耗尽所有重试与恢复手段后被丢弃时，调用方无法通过
带内（in-band）方式得知此事。:class:`DataLossTracker` 会记录这些最终丢弃事件，
以便调用方在刷新（flush）或结束客户端时通过 :class:`FlushResult` 将其暴露出来。

这里只记录*最终*丢弃——即永远不会再次发送的消息。那些仍预期会被送达的
临时状态（限流重入队、为稍后重放而暂存的连接错误）会被有意排除在外。
"""

import collections
import dataclasses
import enum
import time
from typing import Deque, List, Optional, Tuple

# ``DataLossTracker.marker`` 返回并回传给 ``drops_since`` 的不透明令牌：
# 某个时间点上的（消息数、条目数）累计总量。
DropMarker = Tuple[int, int]


class FailureReason(str, enum.Enum):
    HTTP_CLIENT_ERROR = "http_client_error"
    HTTP_SERVER_ERROR = "http_server_error"
    UNAUTHORIZED = "unauthorized"
    SERIALIZATION = "serialization"
    UNKNOWN = "unknown"

    @staticmethod
    def from_status_code(status_code: Optional[int]) -> "FailureReason":
        if status_code is not None and 400 <= status_code < 500:
            return FailureReason.HTTP_CLIENT_ERROR
        if status_code is not None and 500 <= status_code < 600:
            return FailureReason.HTTP_SERVER_ERROR
        return FailureReason.UNKNOWN


@dataclasses.dataclass(frozen=True)
class FailedMessageInfo:
    """单次最终丢弃：SDK 放弃送达的一条消息。"""

    message_type: str
    reason: FailureReason
    item_count: int
    status_code: Optional[int] = None
    detail: Optional[str] = None
    timestamp: float = dataclasses.field(default_factory=time.time)


@dataclasses.dataclass(frozen=True)
class FlushResult:
    """一次 ``flush()``/``end()`` 调用的结果。

    Attributes:
        flushed: 队列是否在超时时间内排空。
        remaining_queue_size: 调用返回时仍在队列中的消息数。
        dropped_messages: 本次 flush 期间观察到的最终丢弃的消息数。
        dropped_items: 这些被丢弃消息所损失的 trace/span 数量。
        failures: 本次 flush 期间观察到的丢弃详情（尽力而为；
            受跟踪器容量的限制）。
    """

    flushed: bool
    remaining_queue_size: int
    dropped_messages: int
    dropped_items: int
    failures: Tuple[FailedMessageInfo, ...]

    @property
    def success(self) -> bool:
        """仅当本次 flush 排空队列且无数据丢失时才为 True。"""
        return self.flushed and self.dropped_messages == 0


@dataclasses.dataclass(frozen=True)
class ErrorsReport:
    """后台发送器记录的最终数据丢失快照。

    面向整个发送器，而不与某一次 flush 绑定。

    .. note::
        该报告是**有上限的**。``total_dropped_messages`` /
        ``total_dropped_items`` 始终精确（以累计总量方式维护），但 ``failures``
        仅保存每次丢弃详情的有界、最近窗口。一旦达到该上限，最早的详情就会被
        丢弃，因此 ``failures`` 中的条目数可能少于 ``total_dropped_messages``，
        且 ``first_failure_at`` 反映的是最早*保留*详情的时刻，而不一定是
        有史以来的第一次丢弃。

    Attributes:
        total_dropped_messages: 发送器启动以来最终丢弃的消息数（精确）。
        total_dropped_items: 这些消息所损失的 trace/span 数量（精确）。
        failures: 每次丢弃的最近详情，有上限（见说明）；每条都带有各自的
            ``timestamp``。
        generated_at: 生成该报告的 Unix 时间。
    """

    total_dropped_messages: int
    total_dropped_items: int
    failures: Tuple[FailedMessageInfo, ...]
    generated_at: float

    @property
    def first_failure_at(self) -> Optional[float]:
        """最早被保留失败的（有界窗口内的）时间戳；若没有则为 None。"""
        return min((failure.timestamp for failure in self.failures), default=None)

    @property
    def last_failure_at(self) -> Optional[float]:
        """最新被保留失败的时间戳；若没有则为 None。"""
        return max((failure.timestamp for failure in self.failures), default=None)


class DataLossTracker:
    """对最终丢弃消息的有界记录。

    在同一个连接身份的所有 :class:`opik.Opik` 句柄之间共享（后台发送器是共享的）。
    每次 flush 的归因通过一个不透明的单调标记完成：flush 之前调用 :meth:`marker`，
    flush 之后调用 :meth:`drops_since`。

    无锁：写入来自发送器的后台线程，并依赖 ``deque`` 的线程安全性。运行中的
    计数器是普通整数，因此在并发丢弃场景下，计数可能会短暂滞后或被舍入——
    这种不精确是被有意接受的（数据丢失统计无需精确到每条消息，而加锁会给
    热发送路径带来竞争）。
    """

    def __init__(self, max_entries: int = 1000):
        self._entries: Deque[FailedMessageInfo] = collections.deque(maxlen=max_entries)
        # 累计总量独立于有界的 ``_entries`` 窗口维护，
        # 这样即使最早的详情被逐出，计数也仍然存在。
        self._recorded_count = 0
        self._recorded_items = 0

    def record(self, failure: FailedMessageInfo) -> None:
        self._entries.append(failure)
        self._recorded_count += 1
        self._recorded_items += failure.item_count

    def marker(self) -> DropMarker:
        """标记丢弃历史中当前位置的不透明令牌。

        携带运行中的（消息数、条目数）累计总量；将其传给 :meth:`drops_since`
        即可获得自该时刻以来观察到的增量。
        """
        return self._recorded_count, self._recorded_items

    def drops_since(
        self, marker: DropMarker
    ) -> Tuple[int, int, List[FailedMessageInfo]]:
        """自 ``marker`` 以来记录的丢弃情况。

        返回 ``(message_count, item_count, failures)`` —— 前两者来自累计总量的
        计数，最后一项是保留的每次丢弃详情（限于最近的 ``max_entries`` 条，
        一旦超出容量，最早的条目将被逐出）。
        """
        marker_count, marker_items = marker
        count = self._recorded_count - marker_count
        items = self._recorded_items - marker_items
        window = list(self._entries)
        window_size = min(count, len(window))
        failures = window[-window_size:] if window_size > 0 else []
        return count, items, failures

    def total_drops(self) -> Tuple[int, int, List[FailedMessageInfo]]:
        """历史累计丢弃总量以及保留的详情。

        返回 ``(message_count, item_count, failures)``，与任何 flush 边界无关——
        用于回答在整个发送器生命周期内“是否有任何数据丢失过”。``failures``
        限于最近的 ``max_entries`` 条，更早的详情会被逐出。
        """
        return self._recorded_count, self._recorded_items, list(self._entries)
