"""Anthropic 批量消息方法的警告装饰器模块。

提供一个通用的警告装饰器工厂，用于在调用暂不支持追踪的方法时发出警告日志。
"""

import logging
import functools

from typing import Callable, Any


def warning_decorator(message: str, logger: logging.Logger) -> Callable:
    """创建一个在函数调用时发出警告日志的装饰器。

    用于标记当前 Opik 集成尚不支持追踪的方法，
    在用户调用这些方法时通过日志提示。

    Args:
        message: 警告日志的消息内容。
        logger: 用于输出警告的日志记录器。

    Returns:
        装饰器函数，包装原始函数使其在调用时记录警告。
    """
    def decorator(func: Callable) -> Callable:
        @functools.wraps(func)
        def wrapper(*args: Any, **kwargs: Any) -> Any:
            logger.warning(message)
            return func(*args, **kwargs)

        return wrapper

    return decorator
