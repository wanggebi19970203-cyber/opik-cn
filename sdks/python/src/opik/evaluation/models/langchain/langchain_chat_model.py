import logging
from typing import Any, cast, Dict, List, Optional, Type, TYPE_CHECKING
import pydantic

from . import opik_monitoring, message_converters, response_parser
from ...models import base_model

if TYPE_CHECKING:
    import langchain_core.language_models
    import langchain_core.messages

LOGGER = logging.getLogger(__name__)


class LangchainChatModel(base_model.OpikBaseModel):
    def __init__(
        self,
        chat_model: "langchain_core.language_models.BaseChatModel",
        track: bool = True,
    ) -> None:
        """
        使用给定的 Langchain 聊天模型实例初始化模型。

        Args:
            chat_model: 要包装的 Langchain 聊天模型实例。
                假设 BaseChatModel 已配置完毕且所有必需依赖已安装。
            track: 是否追踪模型调用。
        """
        model_name = _extract_model_name(chat_model)
        super().__init__(model_name=model_name)

        self._engine = chat_model
        self._track = track

    def generate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        """
        从模型生成字符串输出的简化接口。

        Args:
            input: 模型基于此输入字符串生成输出。
            response_format: 指定期望输出字符串格式的 pydantic 模型。
            kwargs: 模型生成字符串时可能使用的额外参数。

        Returns:
            str: 生成的字符串输出。
        """
        message = self.generate_chat_completion(
            messages=[{"role": "user", "content": input}],
            response_format=response_format,
            **kwargs,
        )
        return message["content"]

    def generate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        """
        从聊天消息列表生成 assistant 轮次。

        Args:
            messages: ``{"role": ..., "content": ...}`` 字典列表。
            response_format: 可选的 Pydantic 模型，指定期望的输出格式。
            kwargs: 转发给 langchain 聊天模型 invoke 调用的额外参数。

        Returns:
            ``{"role": "assistant", "content": ...}``。
        """
        if response_format is not None:
            kwargs["response_format"] = response_format

        with base_model.get_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **kwargs,
        ) as response:
            return response_parser.parse_assistant_message(response)

    def generate_provider_response(
        self,
        messages: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> "langchain_core.messages.AIMessage":
        """
        请勿直接调用此方法。此方法仅供 `base_model.get_provider_response()` 内部使用。

        使用 Langchain 模型生成提供商特定的响应。

        Args:
            messages: 发送给模型的消息列表，应为包含 "content" 和 "role" 键的字典列表。
            kwargs: 提供商生成响应所需的参数。

        Returns:
            ModelResponse: 模型提供商返回的响应。
        """
        langchain_messages = message_converters.convert_to_langchain_messages(messages)

        opik_monitoring.add_opik_tracer_to_params(kwargs)
        response = self._engine.invoke(langchain_messages, **kwargs)

        return response

    async def agenerate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        """
        从模型生成字符串输出的简化接口（异步版本）。

        Args:
            input: 模型基于此输入字符串生成输出。
            response_format: 指定期望输出字符串格式的 pydantic 模型。
            kwargs: 模型生成字符串时可能使用的额外参数。

        Returns:
            str: 生成的字符串输出。
        """
        message = await self.agenerate_chat_completion(
            messages=[{"role": "user", "content": input}],
            response_format=response_format,
            **kwargs,
        )
        return message["content"]

    async def agenerate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        """:meth:`generate_chat_completion` 的异步版本。"""
        if response_format is not None:
            kwargs["response_format"] = response_format

        async with base_model.aget_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **kwargs,
        ) as response:
            return response_parser.parse_assistant_message(response)

    async def agenerate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> "langchain_core.messages.AIMessage":
        """
        请勿直接调用此方法。此方法仅供 `base_model.aget_provider_response()` 内部使用。

        使用 Langchain 模型生成提供商特定的响应（异步版本）。

        Args:
            messages: 发送给模型的消息列表，应为包含 "content" 和 "role" 键的字典列表。
            kwargs: 提供商生成响应所需的参数。

        Returns:
            ModelResponse: 模型提供商返回的响应。
        """
        langchain_messages = message_converters.convert_to_langchain_messages(messages)

        opik_monitoring.add_opik_tracer_to_params(kwargs)
        response = await self._engine.ainvoke(langchain_messages, **kwargs)

        return response


def _extract_model_name(
    langchain_chat_model: "langchain_core.language_models.BaseChatModel",
) -> str:
    if hasattr(langchain_chat_model, "model") and isinstance(
        langchain_chat_model.model, str
    ):
        return langchain_chat_model.model

    if hasattr(langchain_chat_model, "model_name") and isinstance(
        langchain_chat_model.model_name, str
    ):
        return langchain_chat_model.model_name

    if hasattr(langchain_chat_model, "model_id") and isinstance(
        langchain_chat_model.model_id, str
    ):
        return langchain_chat_model.model_id

    return "unknown-model"
