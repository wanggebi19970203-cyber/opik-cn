import importlib.util
import logging
import sys
from typing import Optional, Any, Dict

from opik import config, _logging

from .litellm import litellm_chat_model
from . import base_model, model_name_helper

LOGGER = logging.getLogger(__name__)

_MODEL_CACHE: Dict[Any, base_model.OpikBaseModel] = {}


def _freeze(value: Any) -> Any:
    if isinstance(value, dict):
        return frozenset((k, _freeze(v)) for k, v in value.items())
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(v) for v in value)
    if isinstance(value, set):
        return frozenset(_freeze(v) for v in value)
    return value


def _make_cache_key(model_name: str, track: bool, model_kwargs: Dict[str, Any]) -> Any:
    frozen_kwargs = frozenset((k, _freeze(v)) for k, v in model_kwargs.items())
    return (model_name, track, frozen_kwargs)


def get_default_model_name() -> str:
    """从 Opik 配置中解析默认模型名称。"""
    return config.OpikConfig().default_llm


def get(
    model_name: Optional[str], track: bool = True, **model_kwargs: Any
) -> base_model.OpikBaseModel:
    """
    获取或创建缓存的 LiteLLM 聊天模型实例。

    Args:
        model_name: 要使用的模型名称。为 None 时默认使用 OpikConfig.default_llm。
        track: 是否追踪模型调用。为 False 时禁用此模型实例的追踪。
            默认为 True。
        **model_kwargs: 传递给模型构造函数的额外关键字参数。

    Returns:
        缓存的或新创建的 OpikBaseModel 实例。
    """
    if model_name is None:
        model_name = get_default_model_name()

    cache_key = _make_cache_key(model_name, track, model_kwargs)
    if cache_key not in _MODEL_CACHE:
        _MODEL_CACHE[cache_key] = _create_model(model_name, track, model_kwargs)
    return _MODEL_CACHE[cache_key]


def _should_use_anthropic_native(model_name: str) -> bool:
    if not model_name_helper.is_anthropic_model(model_name):
        return False
    if "anthropic" in sys.modules:
        return True
    if importlib.util.find_spec("anthropic") is not None:
        return True
    _logging.log_once_at_level(
        logging.WARNING,
        "Anthropic SDK 未安装。模型 '%s' 将回退使用 LiteLLM。"
        "请通过 `pip install anthropic` 安装以获得更稳定的体验。",
        LOGGER,
        model_name,
    )
    return False


def _create_model(
    model_name: str, track: bool, model_kwargs: Dict[str, Any]
) -> base_model.OpikBaseModel:
    if _should_use_anthropic_native(model_name):
        LOGGER.debug(
            "使用原生 Anthropic SDK 处理模型 %s",
            model_name,
        )
        from .anthropic.anthropic_chat_model import AnthropicChatModel

        return AnthropicChatModel(model_name=model_name, track=track, **model_kwargs)

    return litellm_chat_model.LiteLLMChatModel(
        model_name=model_name, track=track, **model_kwargs
    )
