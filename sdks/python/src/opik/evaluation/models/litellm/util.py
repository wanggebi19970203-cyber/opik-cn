"""LiteLLM 模型共享的工具函数。"""

from __future__ import annotations

from typing import Any, Callable, Dict, Optional, Set


def coerce_temperature_to_float(value: Any) -> Optional[float]:
    """将 `temperature` 值规范化为浮点数。

    LiteLLM 接受 `temperature` 为 int、float 或数字字符串，并在 API 调用前
    内部强制转换。冲突解决代码（例如"当 temperature 非 1 时丢弃 X"）需要相同的
    转换以避免 ``"1"`` / ``"1.0"`` 等字符串形式的误判。
    当值无法转换时返回 ``None`` —— 调用方应将其视为"类型不匹配，回退保留参数"，
    以免错误输入 *同时* 触发不相关参数的静默丢弃。
    """
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def normalise_choice(choice: Any) -> Dict[str, Any]:
    """无论响应类型如何，生成 LiteLLM choice 的字典视图。

    LiteLLM 可能返回原始字典、Pydantic 模型或数据类。在此规范化为字典
    可保持下游解析逻辑一致，并与旧版客户端向后兼容。
    """

    if isinstance(choice, dict):
        return choice
    if hasattr(choice, "model_dump") and callable(choice.model_dump):
        try:
            return choice.model_dump()
        except TypeError:
            pass
    normalised: Dict[str, Any] = {}
    message = getattr(choice, "message", None)
    if message is not None:
        normalised["message"] = message
    logprobs = getattr(choice, "logprobs", None)
    if logprobs is not None:
        normalised["logprobs"] = logprobs
    return normalised


def apply_model_specific_filters(
    model_name: str,
    params: Dict[str, Any],
    already_warned: Set[str],
    warn: Callable[[str, Any], None],
) -> None:
    """在调用 LiteLLM 前为特定模型系列调整/丢弃参数。

    当前处理：
    - GPT-5：仅接受 temperature=1 且不返回对数概率。
    - DashScope Qwen：强制 logprobs / top_logprobs 的约束。
    """
    normalized_model_name = _normalize_model_name(model_name)

    if normalized_model_name.startswith("gpt-5"):
        _apply_gpt5_filters(params, already_warned, warn)
        return

    if normalized_model_name.startswith("dashscope/"):
        _apply_qwen_dashscope_filters(params, already_warned, warn)
        return


def _normalize_model_name(model_name: str) -> str:
    """为能力检查规范化带提供商前缀的模型名称。"""
    if "/" not in model_name:
        return model_name

    provider, model_without_provider = model_name.split("/", 1)
    if provider in ("openai", "anthropic"):
        return model_without_provider

    return model_name


def _apply_gpt5_filters(
    params: Dict[str, Any],
    already_warned: Set[str],
    warn: Callable[[str, Any], None],
) -> None:
    """应用 GPT-5 特定的参数过滤器。

    仅接受 temperature=1 且不返回对数概率。
    提前移除这些参数可避免提供商错误，同时回调向调用方发出一次性警告。
    """

    unsupported: list[tuple[str, Any]] = []

    if "temperature" in params:
        value = params["temperature"]
        numeric_value = coerce_temperature_to_float(value)
        if numeric_value is None or abs(numeric_value - 1.0) > 1e-6:
            unsupported.append(("temperature", value))

    for param in ("logprobs", "top_logprobs"):
        if param in params:
            unsupported.append((param, params[param]))

    _drop_unsupported_params_with_warning(
        params,
        unsupported,
        already_warned,
        warn,
    )


def _apply_qwen_dashscope_filters(
    params: Dict[str, Any],
    already_warned: Set[str],
    warn: Callable[[str, Any], None],
) -> None:
    """应用 Qwen/DashScope 特定的参数过滤器。

    不返回对数概率。
    """

    unsupported: list[tuple[str, Any]] = []

    for param in ("logprobs", "top_logprobs"):
        if param in params:
            unsupported.append((param, params[param]))

    _drop_unsupported_params_with_warning(
        params,
        unsupported,
        already_warned,
        warn,
    )


def _drop_unsupported_params_with_warning(
    params: Dict[str, Any],
    unsupported_params: list[tuple[str, Any]],
    already_warned: Set[str],
    warn: Callable[[str, Any], None],
) -> None:
    """移除不支持的参数，每个参数名称仅发出一次警告。"""
    for param, value in unsupported_params:
        params.pop(param, None)
        if param in already_warned:
            continue
        warn(param, value)
        already_warned.add(param)
