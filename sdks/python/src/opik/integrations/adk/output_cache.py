from typing import Any, Dict, Optional

from .bounded_cache import DEFAULT_MAX_SIZE, BoundedCache

# 单个 OpikTracer 实例在所有并发 ADK 调用之间共享
# （即文档化的 ``track_adk_agent_recursive`` 模式），因此按 ``invocation_id``
# 缓存最近的模型输出，以避免并发调用相互覆盖各自的输出。
#
# 该缓存有意做大小限制（见 ``BoundedCache``）：ADK 没有保证的
# 调用清理钩子——在代理出错、提前升级/``end_invocation`` 以及流取消时，
# ``after_agent_callback`` 会被跳过——因此依赖回调来释放条目会让映射
# 在长期存活的共享追踪器上无限增长。逐出策略无论如何都会限制内存。


class LastModelOutputCache:
    """按 invocation_id 缓存最后一次模型输出的有界缓存。

    ADK 在 ``after_model_callback`` 中交付模型响应，但代理的输出
    稍后在 ``after_agent_callback`` 中打上标记；此缓存在这两者之间传递该值，
    以 ``invocation_id`` 为键，并在共享同一追踪器的并发调用之间相互隔离。
    """

    def __init__(self, max_size: int = DEFAULT_MAX_SIZE) -> None:
        self._cache: BoundedCache[str, Dict[str, Any]] = BoundedCache(max_size)

    def set(self, invocation_id: str, output: Dict[str, Any]) -> None:
        self._cache.set(invocation_id, output)

    def get(self, invocation_id: str) -> Optional[Dict[str, Any]]:
        return self._cache.get(invocation_id)

    def discard(self, invocation_id: str) -> None:
        """如果存在，则丢弃 ``invocation_id`` 对应的缓存输出。

        用于模型调用未产生可用输出的情况，以避免同一调用中较早调用的
        过期值之后被打到 span 或 trace 上。
        """
        self._cache.pop(invocation_id)
