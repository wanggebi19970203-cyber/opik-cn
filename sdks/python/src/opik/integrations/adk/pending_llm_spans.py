from typing import Any, Optional, Tuple

from opik.api_objects.span.span_data import SpanData

from .bounded_cache import DEFAULT_MAX_SIZE, BoundedCache

# ADK 在 ``before_model_callback`` 中创建 Opik LLM span，并在
# ``after_model_callback`` 中最终化它，通常通过 Opik 的 contextvar
# span 栈（``OpikContextStorage``）进行交接。当 ADK 的 ``ContextCacheConfig``
# 处于活动状态时，其 ``handle_context_caching`` / ``create_cache`` OpenTelemetry span
# 会增加额外的 ``context.attach()/detach()`` 循环，这些循环在一次 SSE 流式
# 异步生成器挂起期间将 contextvar 回退到该 push 之前的快照——
# 因此 ``top_span_data()`` 在 ``after_model_callback`` 中返回 ``None``，
# span 永远不会被最终化（comet-ml/opik#5524）。
#
# 此注册表是一种与 contextvar 无关的交接方式：``before_model_callback``
# 将 span 注册到 ADK 每次模型调用构建一次并传递给两个回调的
# ``EventActions`` 对象上（在流式分片之间保持不变，不受 contextvar
# 变更影响），``after_model_callback`` 则通过同一对象恢复它。
#
# ``EventActions`` 无法可靠地进行哈希，因此条目以 ``id(actions)`` 为键，
# 但同时持有对 ``actions`` 对象的强引用，并在查找时校验身份。这消除了
# ``id()`` 复用带来的隐患：一个比其回调存续更久的条目（其
# ``after_model_callback`` 从未运行的调用——例如提前短路的前置回调或模型错误）
# 会使其 ``actions`` 保持存活，因此 CPython 无法将该 id 复用于后续调用的
# ``EventActions``；而即使某个 id 真的发生了碰撞，身份校验也会拒绝过期的
# span，而不是将其返回给错误的调用。
#
# 它做了大小限制（见 ``BoundedCache``），因为这些未被认领的条目绝不能
# 在长期存活的共享追踪器上不断累积。被逐出的条目会被丢弃，且
# 不会变更其 span：逐出只会在超过 ``max_size`` 个并发在途模型调用时发生，
# 此时最旧的调用可能仍然存活，强制最终化它会把一个原本可正常追踪的调用
# 替换为空 span，并阻止其真正的 ``after_model_callback`` 将其最终化。
# 丢弃条目反而会让 span 留在上下文栈上，由正常的栈回退路径将其最终化
# （此注册表新增的分离上下文恢复在超出上限后只会退回到注册表出现之前的行为）。


class PendingLlmSpanRegistry:
    """在途 LLM span 的有界注册表，以每次模型调用的
    ``EventActions`` 对象为键（按 id，并进行身份校验）。
    """

    def __init__(self, max_size: int = DEFAULT_MAX_SIZE) -> None:
        self._cache: BoundedCache[int, Tuple[Any, SpanData]] = BoundedCache(max_size)

    def register(self, actions: Any, span_data: SpanData) -> None:
        self._cache.set(id(actions), (actions, span_data))

    def get(self, actions: Any) -> Optional[SpanData]:
        entry = self._cache.get(id(actions))
        if entry is not None and entry[0] is actions:
            return entry[1]
        return None

    def pop(self, actions: Any) -> Optional[SpanData]:
        entry = self._cache.pop(id(actions))
        if entry is not None and entry[0] is actions:
            return entry[1]
        return None
