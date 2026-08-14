"""由 :class:`opik.Opik` 句柄共享的连接作用域传输资源。

这里构建的对象（httpx 连接池、REST 客户端、消息处理链、文件上传管理器、
重放管理器 + 连接监视器，以及带有消费线程的流处理器）是*连接*
``(url, workspace, api_key, ...)`` 的属性，而不是单个客户端的属性。

职责被拆分，使每种类型只做一件事：

- :class:`SharedConnectionResourcesBundle` —— 值对象：持有存活的传输对象，
  并知道如何销毁它们（``close``）。
- :class:`ConnectionResourceManager` —— 生命周期权威：派生连接身份，
  构建或复用 bundle，对其进行引用计数，并决定何时拆除它（包括进程退出时）。
- :class:`Lease` —— 每个句柄一个、仅释放一次的令牌，将所有生命周期决策
  委托回管理器。
"""

import atexit
import hashlib
import json
import logging
import threading
from typing import Callable, Dict, Optional, Tuple

import httpx

from .. import config as opik_config
from .. import httpx_client, rest_client_configurator
from ..file_upload import upload_manager
from ..healthcheck import connection_monitor, connection_probe
from ..message_processing import (
    data_loss,
    flush_reporter,
    message_queue,
    permissions,
    streamer,
    streamer_constructors,
)
from ..message_processing.processors import message_processors, message_processors_chain
from ..message_processing.replay import replay_manager
from ..rest_api import client as rest_api_client

LOGGER = logging.getLogger(__name__)


class SharedConnectionResourcesBundle:
    """持有一个连接身份的昂贵传输对象。

    连接作用域：它不携带 ``project_name`` 或每次调用的状态，因此可以支撑
    多个 :class:`opik.Opik` 句柄。``close`` 会销毁 bundle 所拥有的内容——
    流处理器的线程和文件上传工作线程池，以及在持久化（``flush=True``）关闭时
    的 httpx 连接池——因此驱逐一个 bundle 永远不会泄漏线程。``flush_timeout``
    是连接的已配置排空预算，在进程退出钩子关闭 bundle 时使用。
    """

    def __init__(
        self,
        httpx_client: httpx.Client,
        rest_client: rest_api_client.OpikApi,
        message_processor: message_processors.ChainedMessageProcessor,
        file_upload_manager: upload_manager.FileUploadManager,
        replay_manager: replay_manager.ReplayManager,
        streamer: streamer.Streamer,
        data_loss_tracker: data_loss.DataLossTracker,
        flush_timeout: Optional[int],
    ) -> None:
        self.httpx_client = httpx_client
        self.rest_client = rest_client
        self.message_processor = message_processor
        self.file_upload_manager = file_upload_manager
        self.replay_manager = replay_manager
        self.streamer = streamer
        self.flush_reporter = flush_reporter.FlushReporter(
            streamer=streamer,
            data_loss_tracker=data_loss_tracker,
        )
        self.flush_timeout = flush_timeout

    def close(self, timeout: Optional[int], *, flush: bool) -> bool:
        # 排空/停止流处理器（消费线程、重放、批量预处理器）；
        # 当 flush=True 时，还会刷新待处理的文件上传。
        # 关闭流处理器也会停止并 join 重放管理器（它自己的守护线程），
        # 因此这里无需单独的重放拆除操作。
        flushed = self.streamer.close(timeout, flush=flush)
        # 同时停止上传工作线程池，这样驱逐就不会让它的线程继续运行。
        # wait=flush 与流处理器保持一致：持久化关闭时阻塞等待进行中的上传，
        # 即发即弃的拆除时立即返回。
        self.file_upload_manager.close(wait=flush)
        if flush:
            # 仅在持久化关闭时、且最后才关闭 httpx 连接池——在流处理器
            # 已 join 重放线程且上传已排空之后，这样就不会有请求仍在进行中。
            # 每个 bundle 拥有一个专用客户端（按连接身份构建），因此这
            # 绝不会影响其他 bundle。
            # flush=False 是即发即弃：流处理器故意让守护线程继续完成
            # 进行中的请求，因此在这里关闭连接池会与它们发生竞态——
            # 留给 GC / 进程退出的 close_all 处理。
            self.httpx_client.close()
        return flushed

    def flush(self, timeout: Optional[int]) -> bool:
        """排空共享消息队列，而不拆除 bundle。

        当一个句柄以 ``flush=True`` 释放、而其他句柄仍共享该 bundle 时使用：
        已排队的数据现在被持久化，但传输层仍为剩余的句柄保持存活。

        返回队列是否在 ``timeout`` 内完全排空。
        """
        return self.streamer.flush(timeout)


def _create_replay_manager(
    config: opik_config.OpikConfig,
    httpx_client: httpx.Client,
) -> replay_manager.ReplayManager:
    probe = connection_probe.ConnectionProbe(
        base_url=config.url_override,
        client=httpx_client,
    )
    monitor = connection_monitor.OpikConnectionMonitor(
        ping_interval=config.connection_monitor_ping_interval,
        check_timeout=config.connection_monitor_check_timeout,
        probe=probe,
    )

    return replay_manager.ReplayManager(
        monitor=monitor,
        batch_size=config.replay_batch_size,
        batch_replay_delay=config.replay_batch_replay_delay,
        tick_interval_seconds=config.replay_tick_interval,
    )


def create_connection_resources(
    config: opik_config.OpikConfig, *, use_batching: bool
) -> SharedConnectionResourcesBundle:
    """为 ``config`` 构建完整的传输栈。

    纯粹构建、无缓存感知——这是 :class:`ConnectionResourceManager`
    在缓存未命中时调用的默认构建器。
    """
    httpx_client_ = httpx_client.get(
        workspace=config.workspace,
        api_key=config.api_key,
        check_tls_certificate=config.check_tls_certificate,
        compress_json_requests=config.enable_json_request_compression,
    )
    rest_client = rest_api_client.OpikApi(
        base_url=config.url_override,
        httpx_client=httpx_client_,
    )
    rest_client._client_wrapper._timeout = (
        httpx.USE_CLIENT_DEFAULT
    )  # 参见 https://github.com/fern-api/fern/issues/5321
    rest_client_configurator.configure(rest_client)

    max_queue_size = message_queue.calculate_max_queue_size(
        maximal_queue_size=config.maximal_queue_size,
        batch_factor=config.maximal_queue_size_batch_factor,
    )

    file_uploader = upload_manager.FileUploadManager(
        rest_client=rest_client,
        httpx_client=httpx_client_,
        worker_count=config.file_upload_background_workers,
    )

    data_loss_tracker = data_loss.DataLossTracker()

    fallback_replay = _create_replay_manager(config, httpx_client_)

    message_processor = message_processors_chain.create_message_processors_chain(
        rest_client=rest_client,
        file_upload_manager=file_uploader,
        fallback_replay_manager=fallback_replay,
        unauthorized_message_types_registry=permissions.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=config.unauthorized_message_type_retry_interval,
            max_retry_count=config.unauthorized_message_type_max_retry_count,
        ),
        data_loss_tracker=data_loss_tracker,
        max_payload_size_mb=config.max_payload_size_mb,
    )
    streamer_ = streamer_constructors.construct_online_streamer(
        file_uploader=file_uploader,
        n_consumers=config.background_workers,
        use_batching=use_batching,
        use_attachment_extraction=config.is_attachment_extraction_active,
        min_base64_embedded_attachment_size=config.min_base64_embedded_attachment_size,
        max_queue_size=max_queue_size,
        message_processor=message_processor,
        url_override=config.url_override,
        fallback_replay_manager=fallback_replay,
    )

    return SharedConnectionResourcesBundle(
        httpx_client=httpx_client_,
        rest_client=rest_client,
        message_processor=message_processor,
        file_upload_manager=file_uploader,
        replay_manager=fallback_replay,
        streamer=streamer_,
        data_loss_tracker=data_loss_tracker,
        flush_timeout=config.default_flush_timeout,
    )


# 由 ``_connection_key`` 生成的不透明、可哈希的连接身份。
ConnectionKey = Tuple[str, bool]


def _connection_key(
    config: opik_config.OpikConfig, *, use_batching: bool
) -> ConnectionKey:
    # 整个配置定义了一个连接的身份：任何不同的字段都会产生不同的 bundle。
    # 对序列化后的配置进行哈希可保持键紧凑，并且按构造方式，永远不以明文
    # 持有 api_key（或任何字段）。
    #
    # 注意，这意味着仅因每个句柄的设置（例如不同的默认 ``project_name``
    # 或 ``default_flush_timeout``）而不同的客户端会获得独立的 bundle。
    # 这是安全的——项目是按 trace 携带的，flush 超时是每个 ``end()`` 的
    # 参数——但要在项目之间共享一个连接，应使用单个客户端并在每次调用时
    # 传入 ``project_name``。
    fingerprint = json.dumps(config.model_dump(mode="json"), sort_keys=True)
    digest = hashlib.sha256(fingerprint.encode("utf-8")).hexdigest()
    return (digest, use_batching)


class Lease:
    """每个句柄持有、仅释放一次的 bundle 令牌。

    每个 :class:`opik.Opik` 句柄都持有自己的租约。它携带 bundle，使句柄
    无需重新查找即可委托，并守护单次 ``release``，这样显式 ``end()`` 之后
    再跟 GC 终结器就不会释放两次。所有生命周期*决策*（引用计数、拆除）
    都在管理器上——租约只负责转发。
    """

    def __init__(
        self,
        manager: "ConnectionResourceManager",
        key: ConnectionKey,
        resources: SharedConnectionResourcesBundle,
    ) -> None:
        self._manager = manager
        self._key = key
        self.resources = resources
        self._released = False
        self._once_lock = threading.Lock()

    def release(
        self, timeout: Optional[int], *, flush: bool = True, close_on_zero: bool
    ) -> Optional[bool]:
        """释放此句柄的引用。当本次释放执行了排空（显式的 ``flush=True``
        释放）时，返回权威的 flush 结果；否则返回 ``None``（已释放，或是
        不做网络 I/O 的 GC 终结器）。"""
        with self._once_lock:
            if self._released:
                return None
            self._released = True
        return self._manager.release(
            self._key, timeout, flush=flush, close_on_zero=close_on_zero
        )


class _Entry:
    def __init__(
        self, resources: SharedConnectionResourcesBundle, refcount: int
    ) -> None:
        self.resources = resources
        self.refcount = refcount


class ConnectionResourceManager:
    """共享连接资源生命周期的唯一所有者。

    从配置派生连接身份，构建或复用按该身份进行引用计数的 bundle，
    并且仅当其最后一个租约被*显式*释放（``Opik.end()``）时才拆除 bundle——
    总是在锁内将其驱逐之后，这样并发的 ``acquire`` 永远不会收到正在关闭
    的 bundle。由 GC 终结器丢弃的引用（``close_on_zero=False``）仅递减计数
    并让 bundle 保持缓存；关闭绝不在垃圾回收中执行。任何存活到进程退出的
    内容都由 ``close_all`` 销毁。销毁机制委托给 bundle 的 ``close``；
    本类负责*何时*发生。
    """

    def __init__(
        self,
        builder: Callable[
            ..., SharedConnectionResourcesBundle
        ] = create_connection_resources,
    ) -> None:
        self._builder = builder
        self._lock = threading.Lock()
        self._entries: Dict[ConnectionKey, _Entry] = {}

    def acquire(
        self,
        config: opik_config.OpikConfig,
        *,
        use_batching: bool,
    ) -> Lease:
        key = _connection_key(config, use_batching=use_batching)

        # 快速路径：在锁内复用现有 bundle。
        with self._lock:
            entry = self._entries.get(key)
            if entry is not None:
                entry.refcount += 1
                return Lease(manager=self, key=key, resources=entry.resources)

        # 还没有 bundle——在锁外构建，这样缓慢的传输栈构建
        # 不会串行化不相关的获取操作。
        bundle = self._builder(config, use_batching=use_batching)

        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                self._entries[key] = _Entry(resources=bundle, refcount=1)
                return Lease(manager=self, key=key, resources=bundle)
            # 在构建竞态中落败：保留胜出的 bundle，在其上取一个引用，
            # 并在下方（锁外）丢弃我们自己的那个。
            entry.refcount += 1
            lease = Lease(manager=self, key=key, resources=entry.resources)

        # 丢弃我们在竞态中落败的那个 bundle。这里的拆除失败不得拒绝调用者——
        # 胜出的租约已经有效——因此记录日志并继续。
        try:
            bundle.close(timeout=0, flush=False)
        except Exception:
            LOGGER.debug(
                "关闭在获取竞态后被丢弃的连接资源失败",
                exc_info=True,
            )
        return lease

    def release(
        self,
        key: ConnectionKey,
        timeout: Optional[int],
        *,
        flush: bool = True,
        close_on_zero: bool,
    ) -> Optional[bool]:
        # 共享下的持久性：在仍然共享其 bundle 的句柄上执行显式的
        # ``end(flush=True)`` 必须在此句柄放弃其引用*之前*排空共享队列。
        # 在我们的引用仍被计数时刷新可保持 refcount >= 1，因此并发的
        # 最后一次释放无法驱逐并 ``close(flush=False)`` 该 bundle——那会
        # 从这次 flush 底下清空消息队列，丢失 ``flush=True`` 调用者所被
        # 承诺的数据。仅当另一个句柄也共享该 bundle 时才预刷新；唯一持有者的
        # ``close(flush=True)``（见下方）已经持久地排空。GC 终结器
        # （``close_on_zero=False``）从不做网络 I/O，因此它从不预刷新。
        # 调用者的权威 flush 结果：由下方实际执行排空的分支设置——共享的
        # 预刷新或最后一次引用的关闭。当本次释放未执行排空（GC 终结器，或
        # bundle 已在别处释放）时保持 None，这样调用者就能区分“未确认”与
        # “确认未刷新”。
        flushed: Optional[bool] = None
        if flush and close_on_zero:
            with self._lock:
                entry = self._entries.get(key)
                shared_bundle = (
                    entry.resources
                    if entry is not None and entry.refcount > 1
                    else None
                )
            if shared_bundle is not None:
                flushed = shared_bundle.flush(timeout)

        # 现在丢弃我们的引用。因为我们只在这里——在上方任何预刷新完成之后——
        # 递减，所以在另一个句柄预刷新进行中时，关闭绝不会运行：该句柄
        # 仍持有其引用，因此在其 flush 返回之前计数不可能达到零。
        with self._lock:
            entry = self._entries.get(key)
            if entry is None:
                return flushed
            entry.refcount -= 1
            if entry.refcount > 0:
                return flushed
            if not close_on_zero:
                # 最后一个引用由 GC 终结器丢弃（参见
                # ``Opik._acquire_shared_resources``）。在那里只能安全地执行
                # 上方的引用计数递减；关闭——流处理器线程 join、文件上传池
                # 关闭、网络 flush——绝不能发生在垃圾回收内部。让 bundle
                # 保持缓存，以便之后相同身份的 ``acquire`` 复用它，或由
                # ``close_all`` 在进程退出时销毁它。
                return flushed
            # 在锁内、关闭之前驱逐，这样并发的 acquire 永远不会收到
            # 正在被拆除的 bundle。
            del self._entries[key]
            bundle = entry.resources

        closed_flushed = bundle.close(timeout, flush=flush)
        # 非排空式的拆除没有 flush 结果可报告——返回 None（而非 close()
        # 的布尔值），使结果保持为“这里未发生排空”。
        return closed_flushed if flush else None

    def close_all(self, *, flush: bool = True) -> None:
        """关闭并驱逐每个缓存的 bundle。注册为进程的 ``atexit`` 钩子
        （``flush=True``），此时每个 bundle 都在其自身连接配置的
        ``flush_timeout`` 内排空，而非无界地排空；``flush=False`` 则
        在不做网络 I/O 的情况下重置注册表。"""
        with self._lock:
            entries = list(self._entries.values())
            self._entries.clear()

        for entry in entries:
            try:
                entry.resources.close(entry.resources.flush_timeout, flush=flush)
            except Exception:
                LOGGER.debug(
                    "关闭共享连接资源失败",
                    exc_info=True,
                )

    def active_connection_count(self) -> int:
        """存活的缓存 bundle 数量。用于测试和调试。"""
        with self._lock:
            return len(self._entries)

    def reference_count(
        self, config: opik_config.OpikConfig, *, use_batching: bool
    ) -> int:
        """当前共享 ``config`` 的 bundle 的句柄数量（若没有则为 0）。"""
        key = _connection_key(config, use_batching=use_batching)
        with self._lock:
            entry = self._entries.get(key)
            return 0 if entry is None else entry.refcount


MANAGER = ConnectionResourceManager()
atexit.register(MANAGER.close_all)
