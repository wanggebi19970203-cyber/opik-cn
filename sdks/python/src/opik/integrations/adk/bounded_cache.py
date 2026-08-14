import collections
import threading
from typing import Generic, Optional, TypeVar

_K = TypeVar("_K")
_V = TypeVar("_V")

DEFAULT_MAX_SIZE = 1000


class BoundedCache(Generic[_K, _V]):
    """一个线程安全、大小受限的映射，当大小超过 ``max_size`` 时会逐出最旧的条目。

    供 ADK 追踪器的运行时缓存（按 invocation 区分的模型输出缓存和
    待处理 LLM span 注册表）共享，这些缓存保存在一个长期存在、
    并发使用的追踪器上，因此必须保持有界并在同一处加锁。
    """

    def __init__(self, max_size: int = DEFAULT_MAX_SIZE) -> None:
        self._lock = threading.Lock()
        self._entries: "collections.OrderedDict[_K, _V]" = collections.OrderedDict()
        self._max_size = max_size

    def set(self, key: _K, value: _V) -> None:
        with self._lock:
            self._entries[key] = value
            self._entries.move_to_end(key)
            while len(self._entries) > self._max_size:
                self._entries.popitem(last=False)

    def get(self, key: _K) -> Optional[_V]:
        with self._lock:
            return self._entries.get(key)

    def pop(self, key: _K) -> Optional[_V]:
        with self._lock:
            return self._entries.pop(key, None)
