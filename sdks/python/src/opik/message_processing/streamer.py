import threading
import logging
import time
from typing import List, Optional

from opik.file_upload import base_upload_manager
from . import messages, message_queue, queue_consumer
from .. import _logging
from .. import synchronization
from .preprocessing import (
    attachments_preprocessor,
    batching_preprocessor,
)
from .replay import replay_manager


LOGGER = logging.getLogger(__name__)


class Streamer:
    def __init__(
        self,
        queue: message_queue.MessageQueue[messages.BaseMessage],
        queue_consumers: List[queue_consumer.QueueConsumer],
        attachments_preprocessor: attachments_preprocessor.AttachmentsPreprocessor,
        batch_preprocessor: batching_preprocessor.BatchingPreprocessor,
        file_uploader: base_upload_manager.BaseFileUploadManager,
        fallback_replay_manager: replay_manager.ReplayManager,
    ) -> None:
        self._lock = threading.RLock()
        self._message_queue = queue
        self._queue_consumers = queue_consumers
        self._attachments_preprocessor = attachments_preprocessor
        self._batch_preprocessor = batch_preprocessor
        self._file_upload_manager = file_uploader
        self._fallback_replay_manager = fallback_replay_manager

        self._drain = False

        self._idle = True

        self._start_queue_consumers()
        self._batch_preprocessor.start()

        self._fallback_replay_manager.set_replay_callback(self.put)
        self._fallback_replay_manager.start()

    @property
    def use_batching(self) -> bool:
        return self._batch_preprocessor._batch_manager is not None

    def put(self, message: messages.BaseMessage, force: bool = False) -> None:
        with self._lock:
            if self._drain and not force:
                return

            self._idle = False
            try:
                # 首先进行内嵌附件的预处理（必须始终最先执行）
                preprocessed_message = self._attachments_preprocessor.preprocess(
                    message
                )

                # 第三步进行批处理预处理
                preprocessed_message = self._batch_preprocessor.preprocess(
                    preprocessed_message
                )

                # 如果消息未被预处理器完全消费，则处理所得消息
                if preprocessed_message is not None:
                    if self._message_queue.accept_put_without_discarding() is False:
                        _logging.log_once_at_level(
                            logging.WARNING,
                            "已达到消息队列大小限制。新消息已加入队列，最早的消息已被丢弃。",
                            logger=LOGGER,
                        )
                    self._message_queue.put(preprocessed_message)
            except Exception as ex:
                LOGGER.error(
                    "streamer 处理消息失败：%s", ex, exc_info=ex
                )
            self._idle = True

    def close(self, timeout: Optional[int] = None, *, flush: bool = True) -> bool:
        """
        停止数据处理线程。

        Args:
            timeout: 用于排空管道的预算。仅在 ``flush`` 为 True 时有意义；否则被忽略。
            flush: 若为 True（默认），在关闭前等待排队的消息和文件上传到达后端——
                这是历史悠久的、对生产安全的做法。设为 False 则用于即发即弃（fire-and-forget）的
                拆除场景，其中待处理的数据可以被丢弃（例如 e2e 测试中的每个测试清理，
                断言已在测试主体中轮询过后端）。

        Returns:
            所有数据是否已刷新到后端。在 ``flush=True`` 关闭时该值具有权威性
            （即内部 ``flush(timeout)`` 的结果）；在 ``flush=False`` 时为 ``False``
            （待处理数据被有意丢弃）。
        """
        with self._lock:
            if self._drain:
                # 已关闭——使调用幂等，以便在显式关闭后 atexit 能安全触发
                # （测试清理中常见）。
                return self._message_queue.empty()
            if flush:
                synchronization.wait_for_done(
                    check_function=lambda: self._idle,
                    timeout=timeout,
                    sleep_time=0.1,
                )
            self._drain = True

        self._batch_preprocessor.stop(flush=flush)
        self._fallback_replay_manager.close()

        if flush:
            # 在释放调用方之前，等待重放线程、消费者队列和文件上传真正排空。
            # 队列排空期间消费者必须继续运行，因此在最后才关闭它们。
            self._fallback_replay_manager.join(timeout)
            flushed = self.flush(timeout)
            self._close_queue_consumers()
            return flushed
        else:
            # 即发即弃：丢弃待处理消息，使收到停止信号的消费者看到空队列并自行退出。
            # 不做 join——守护线程可以在后台完成进行中的 HTTP 请求，而不会阻塞拆除。
            pending = self._message_queue.size()
            if pending > 0:
                LOGGER.warning(
                    "Streamer.close(flush=False) 正在丢弃 %d 条排队的消息，且不进行刷新。"
                    "尚未到达后端的数据将会丢失。如需持久性请使用 flush=True（默认值）——"
                    "flush=False 仅适用于短生命周期的测试/拆除场景，不适用于生产关闭。",
                    pending,
                )
            self._message_queue.clear()
            self._close_queue_consumers()
            return False

    def drain_to_processors(self, timeout: Optional[float] = None) -> bool:
        """轻量级排空：确保到目前为止提交的每条消息都已应用到进程内的链式处理器
        （尤其是 `LocalEmulatorMessageProcessor`）。

        它与 `flush(...)` 的区别在于跳过文件上传管理器和回退重放管理器——这两者
        关注的都是后端交付，而非本地处理器状态。它被设计为在调用智能体 LLM 评判器
        之前由评估引擎频繁调用，该评判器读取模拟器对最近运行任务的 spans/error_info 视图。
        如果没有这次排空，评分开始时队列消费者可能仍在处理该批次，评判器就会看到陈旧数据。

        如果所有内容都在 `timeout` 内排空则返回 True；如果超时触发时仍有消息待处理则返回 False。
        智能体路径将 False 视为“已尽力应用”，并继续使用模拟器中当前的任何状态。
        """
        self._batch_preprocessor.flush()
        synchronization.wait_for_done(
            check_function=lambda: self._all_done(),
            timeout=timeout,
            sleep_time=0.05,
            progress_callback=self._batch_preprocessor.flush,
        )
        return self._all_done()

    def flush(self, timeout: Optional[float], upload_sleep_time: int = 5) -> bool:
        # 等待当前待处理消息的处理完成
        # 这应在刷新批处理预处理器之前完成，因为处理过程中
        # 可能会有批处理消息被加入队列
        with self._lock:
            synchronization.wait_for_done(
                check_function=lambda: self._idle,
                timeout=timeout,
                sleep_time=0.1,
            )

        self._batch_preprocessor.flush()

        if self._fallback_replay_manager.has_server_connection:
            # 仅当与服务器有连接时才进行重放
            self._fallback_replay_manager.flush()

        start_time = time.time()

        synchronization.wait_for_done(
            check_function=lambda: self._all_done(),
            timeout=timeout,
            sleep_time=0.1,
            progress_callback=self._batch_preprocessor.flush,
        )

        elapsed_time = time.time() - start_time
        if timeout is not None:
            timeout = timeout - elapsed_time
            if timeout < 0.0:
                timeout = 1.0

        # 刷新上传管理器是阻塞操作
        upload_flushed = self._file_upload_manager.flush(
            timeout=timeout, sleep_time=upload_sleep_time
        )

        flushed = upload_flushed and self._all_done()
        LOGGER.debug(f"Streamer 已完全刷新：{flushed}")

        return flushed

    def _all_done(self) -> bool:
        # 仅当 `put()` 接受的每条消息都已被消费者最终处理（其
        # `message_processor.process(...)` 已返回或抛出了非限流错误）时，
        # `all_tasks_done()` 才为 True。这消除了消息已从队列弹出但尚未处理的竞态。
        return (
            self._message_queue.all_tasks_done() and self._batch_preprocessor.is_empty()
        )

    def __internal_api__failed_uploads__(self, timeout: Optional[float]) -> int:
        """返回失败文件上传的数量。阻塞——等待所有上传完成。"""
        return self._file_upload_manager.failed_uploads(timeout=timeout)

    def queue_size(self) -> int:
        return self._message_queue.size()

    def _start_queue_consumers(self) -> None:
        for consumer in self._queue_consumers:
            consumer.start()

    def _close_queue_consumers(self) -> None:
        for consumer in self._queue_consumers:
            consumer.close()
