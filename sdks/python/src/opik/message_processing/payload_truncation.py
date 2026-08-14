"""针对每个 span/trace 的负载大小限制。

当 span 或 trace 的 ``input``/``output`` 字段非常大时——例如把整个检索结果集内联记录——
会在后端膨胀成数 GB 的结构，并可能破坏数据摄取。本模块通过一个简单、可预测的两轮规则
来强制实施**每个对象**的大小限制：

1. 任何单个字段超过限制时，都会被替换为截断标记（常见情况：一个超大字段，其余小字段保留）；
2. 如果对象整体仍然超过限制，则其余可截断字段也会被替换——从而保证对象最终不超过限制。

不对字段排序，也没有逐步重新测量的循环（最多做一次整体测量）。该逻辑在后台消息处理器中、
即将发送 create/update 请求之前执行——此时附件已被单独提取/上传，因此只会测量并截断
将要内联发送的内容。只要发生任何截断，就会记录一条警告。

``metadata`` 有意地永不被截断（它保存着后端依赖的小型结构化字段，如 ``thread_id``/``model``），
并且被排除在整体测量之外，因此较大的 ``metadata`` 不会触发对 ``input``/``output`` 的截断。
"""

import logging
from typing import Any, Callable, Dict, List, Optional, TypeVar

from .batching import sequence_splitter
from ..rest_api.types import span_write, trace_write

LOGGER = logging.getLogger(__name__)

# span 或 trace 的写入模型——两者都携带相同的可截断 input/output 字段。
WriteT = TypeVar("WriteT", span_write.SpanWrite, trace_write.TraceWrite)

# span/trace 上可能携带大型用户负载的字段。有意地仅包含 `input`/`output`——
# `metadata` 保持不变（参见模块文档字符串）。
_TRUNCATABLE_FIELDS = ("input", "output")


def _truncation_marker(size_mb: float) -> Dict[str, Any]:
    # 用于替换超大字段的紧凑标记。`opik_truncated` 是机器可检测的标志；
    # `reason` 是带有原始大小以及该字段原本会触发的服务器错误码（413/400）的简短标记。
    return {
        "opik_truncated": True,
        "reason": f"<omitted_due_to_size_{round(size_mb)}MB_error_code_413_400>",
    }


def _log_truncation(
    kind: str, obj_id: Optional[str], max_size_mb: float, truncated_fields: List[str]
) -> None:
    LOGGER.warning(
        "%s '%s' 超过了每个 %s 的 %s MB 大小限制；被截断的字段：%s。 "
        "请将大型负载作为附件记录以避免截断。",
        kind.capitalize(),
        obj_id,
        kind,
        max_size_mb,
        ", ".join(truncated_fields),
    )


def _field_sizes_mb(get_field: Callable[[str], Any]) -> Dict[str, float]:
    sizes: Dict[str, float] = {}
    for name in _TRUNCATABLE_FIELDS:
        value = get_field(name)
        if value is not None:
            sizes[name] = sequence_splitter.get_payload_size_MB(value)
    return sizes


def _plan_truncation(
    field_sizes_mb: Dict[str, float],
    max_size_mb: float,
    measure_whole: Callable[[Dict[str, Any]], float],
) -> Dict[str, Any]:
    """决定将哪些字段替换为标记，以使对象最终 <= 限制。

    第一轮截断任何单个就超过限制的字段。第二轮（硬性的每个对象上限）仅在对象整体仍超限时
    截断其余字段。返回 ``{field_name: marker}``（若无需截断则为空）。
    """
    updates: Dict[str, Any] = {}

    # 第一轮——单个字段超过限制（常见的“一个大字段”情况）。
    for name, size_mb in field_sizes_mb.items():
        if size_mb > max_size_mb:
            updates[name] = _truncation_marker(size_mb)

    # 第二轮——硬性的每个对象上限：若整体仍超限，则同时截断其余可截断字段。
    # 只测量一次，不循环。
    if measure_whole(updates) > max_size_mb:
        for name, size_mb in field_sizes_mb.items():
            if name not in updates:
                updates[name] = _truncation_marker(size_mb)

    return updates


def truncate_write_if_needed(
    obj: WriteT, max_size_mb: float, kind: str = "span"
) -> WriteT:
    """返回 ``obj``（span 或 trace 的写入对象）的副本，其中超大的字段已被截断。"""
    # 非正数限制会禁用检查（与 TS SDK 保持一致）。在此处做防护还可以避免
    # 0 或负数限制会把每个字段都标记为超大的退化情况。
    if max_size_mb <= 0:
        return obj
    field_sizes = _field_sizes_mb(lambda n: getattr(obj, n, None))
    if not field_sizes:
        return obj

    def measure_whole(overrides: Dict[str, Any]) -> float:
        # 排除 metadata：它永不被截断，因此不能让它把对象“拖到”上限之上，
        # 从而触发对小 input/output 的无谓截断。
        candidate = obj.model_copy(update={"metadata": None, **overrides})
        return sequence_splitter.get_payload_size_MB(candidate)

    updates = _plan_truncation(field_sizes, max_size_mb, measure_whole)
    if not updates:
        return obj

    # 写入模型是冻结的；model_copy(update=...) 会构建一个新实例而不重新校验，
    # 因此标记字典可以被接受并赋值到 JSON 字段上。
    result = obj.model_copy(update=updates)
    _log_truncation(kind, getattr(obj, "id", None), max_size_mb, list(updates))
    return result


def truncate_writes(
    objs: List[WriteT], max_size_mb: float, kind: str = "span"
) -> List[WriteT]:
    """截断批次中每个超过每个对象限制的 span/trace。"""
    return [truncate_write_if_needed(obj, max_size_mb, kind) for obj in objs]


def truncate_kwargs_if_needed(
    kwargs: Dict[str, Any], max_size_mb: float, kind: str = "span"
) -> None:
    """就地截断单个 create/update 负载字典中的超大字段。

    用于非批处理的 create 路径（``use_batching=False``）和 update 路径，
    这样通过 update 发送的超大 ``output``/``input``（例如在 create 已刷新之后调用
    ``span.end(output=...)``）会以与 create 相同的方式被限制。同时适用于 span 和 trace（``kind``）。
    """
    # 非正数限制会禁用检查（与 TS SDK 保持一致）。
    if max_size_mb <= 0:
        return
    field_sizes = _field_sizes_mb(kwargs.get)
    if not field_sizes:
        return

    def measure_whole(overrides: Dict[str, Any]) -> float:
        # 从整体测量中排除 metadata（永不被截断）。
        return sequence_splitter.get_payload_size_MB(
            {**kwargs, **overrides, "metadata": None}
        )

    updates = _plan_truncation(field_sizes, max_size_mb, measure_whole)
    if not updates:
        return

    kwargs.update(updates)
    _log_truncation(kind, kwargs.get("id"), max_size_mb, list(updates))
