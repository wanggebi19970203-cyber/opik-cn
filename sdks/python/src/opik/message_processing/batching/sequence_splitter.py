import logging
from typing import List, Optional, TypeVar, Sequence, Any
import opik.jsonable_encoder as jsonable_encoder

T = TypeVar("T")

LOGGER = logging.getLogger(__name__)


def _get_expected_payload_size_MB(item: T) -> float:
    encoded_for_json = jsonable_encoder.encode(item)
    size = _get_json_size(encoded_for_json)
    return size / (1024 * 1024)


def get_payload_size_MB(item: T) -> float:
    """估算 ``item`` 序列化为 JSON 后的大小（以 MB 为单位）。

    内部大小估算器的公开包装，供 span 截断复用，从而使截断时测量的大小与
    批处理的大小估算保持一致。
    """
    return _get_expected_payload_size_MB(item)


def _get_json_size(obj: Any) -> Any:
    """
    在不实际执行 JSON 编码（这很耗费 CPU 和内存）的情况下计算最终 JSON 的大小。
    这里假设我们只会收到基本的 Python 对象、字符串、布尔值、数字、列表和字典，
    并且对象不包含任何循环引用。
    """
    try:
        if isinstance(obj, str):
            return len(obj.encode("utf-8")) + 2  # 字符串两侧的引号
        elif isinstance(obj, (int, float)):
            return len(str(obj))
        elif isinstance(obj, type(None)):
            # null 关键字
            return 4
        elif isinstance(obj, dict):
            size = 2  # 花括号 {}
            allowed_keys = set(obj.keys())
            for key, value in obj.items():
                if key in allowed_keys:
                    encoded_key = _get_json_size(key)
                    encoded_value = _get_json_size(value)
                    size += encoded_key + encoded_value + 1 + 1  # key:value 中的冒号与逗号
            return size - 1  # 去掉末尾多余的逗号
        elif isinstance(obj, list):
            size = 2  # 方括号 []
            for item in obj:
                size += _get_json_size(item) + 1  # 逗号
            return size - 1  # 去掉末尾多余的逗号
        elif isinstance(obj, bool):
            return len(str(obj))
        else:
            LOGGER.debug("JSON 大小估算过程中遇到意外对象：%r", type(obj))
            return len(str(obj))

    except Exception:
        LOGGER.debug("无法计算对象大小。", exc_info=True)
        # 为保险起见，返回一个会让该 span 独立成批的值
        return float("inf")


def split_into_batches(
    items: Sequence[T],
    max_payload_size_MB: Optional[float] = None,
    max_length: Optional[int] = None,
) -> List[List[T]]:
    assert (max_payload_size_MB is not None) or (max_length is not None), (
        "至少需要设置一种拆分限制"
    )

    if max_length is None:
        max_length = len(items)

    if max_payload_size_MB is None:
        max_payload_size_MB = float("inf")

    batches: List[List[T]] = []
    current_batch: List[T] = []
    current_batch_size_MB: float = 0.0

    for item in items:
        item_size_MB = (
            0.0 if max_payload_size_MB is None else _get_expected_payload_size_MB(item)
        )

        if item_size_MB >= max_payload_size_MB:
            batches.append([item])
            continue

        batch_is_already_full = len(current_batch) == max_length
        batch_will_exceed_memory_limit_after_adding = (
            current_batch_size_MB + item_size_MB > max_payload_size_MB
        )

        if batch_is_already_full or batch_will_exceed_memory_limit_after_adding:
            batches.append(current_batch)
            current_batch = [item]
            current_batch_size_MB = item_size_MB
        else:
            current_batch.append(item)
            current_batch_size_MB += item_size_MB

    if len(current_batch) > 0:
        batches.append(current_batch)

    return batches
