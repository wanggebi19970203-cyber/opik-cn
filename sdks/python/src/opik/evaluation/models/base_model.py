import abc
import logging
import sys
from contextlib import contextmanager, asynccontextmanager
from typing import Any, List, Dict, Literal, Optional, Type
import pydantic
from typing_extensions import TypedDict

if sys.version_info < (3, 11):
    from typing_extensions import Required
else:
    from typing import Required

from opik import exceptions


LOGGER = logging.getLogger(__name__)


Role = Literal["system", "user", "assistant", "tool"]


class ToolCallFunction(TypedDict):
    name: str
    arguments: str


class ToolCall(TypedDict):
    id: str
    type: Literal["function"]
    function: ToolCallFunction


class ConversationDict(TypedDict, total=False):
    """OpenAI 格式的聊天消息，用于在调用方和 LLM 封装层之间传递对话轮次。

    ``role`` 始终会被设置。其余字段按条件必填：

    - ``content`` 在 system/user/tool 消息以及生成文本的 assistant 消息中设置。
      对于仅发出 ``tool_calls`` 的 assistant 消息，该字段会被*省略*（键不存在，
      而非 ``None``），因此始终期望文本输出的调用方（即所有现有的评判器）可以通过
      ``message["content"]`` 索引获取 ``str`` 类型。
    - ``tool_calls`` 出现在调用工具的 assistant 消息中。
    - ``tool_call_id``（以及可选的 ``name``）出现在工具结果消息中。
    """

    role: Required[Role]
    content: str
    tool_calls: List[ToolCall]
    tool_call_id: str
    name: str


class OpikBaseModel(abc.ABC):
    """
    LLM 接口基类。

    如果需要在评估指标中实现自定义 LLM 提供商，应继承此类。
    """

    def __init__(self, model_name: str):
        """
        使用给定的模型名称初始化基础模型。

        Args:
            model_name: 要使用的 LLM 模型名称。
        """
        self.model_name = model_name

    @abc.abstractmethod
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
            kwargs: 模型生成字符串时可能使用的额外参数。

        Returns:
            生成的字符串输出。
        """
        pass

    @abc.abstractmethod
    def generate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> Any:
        """
        请勿直接调用此方法。此方法仅供 `get_provider_response()` 内部使用。

        生成提供商特定的响应。可用于与底层模型提供商（如 OpenAI、Anthropic）交互并获取原始输出。

        Args:
            messages: 发送给模型的消息列表，应为字典列表。
            kwargs: 提供商生成响应所需的参数。

        Returns:
            模型提供商返回的响应，类型取决于具体用例和 LLM。
        """
        pass

    # 不标记为 abstractmethod，以避免破坏现有用户实现
    def generate_chat_completion(
        self,
        messages: List[ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> ConversationDict:
        """
        从带角色标记的聊天消息列表生成 assistant 轮次。

        实现应将消息原样转发给底层 chat-completions API。保留调用方的
        ``system``/``user`` 分离结构，使得提供商能够在多次调用间缓存
        稳定的系统前缀——这正是使用此方法而非 :meth:`generate_string` 的核心意义。

        Args:
            messages: 遵循 OpenAI chat-completions 格式的 ``{"role": ..., "content": ...}`` 字典列表。
            response_format: 可选的 Pydantic 模型，指定期望的输出格式。
            kwargs: 转发给底层提供商调用的额外参数。

        Returns:
            ``{"role": "assistant", "content": ...}`` 字典，调用方可将其追加回输入 ``messages`` 以进行后续轮次对话。
        """
        raise NotImplementedError(
            "Chat completion generation not implemented for this provider"
        )

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
            kwargs: 模型生成字符串时可能使用的额外参数。

        Returns:
            生成的字符串输出。
        """
        raise NotImplementedError("Async generation not implemented for this provider")

    async def agenerate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> Any:
        """
        请勿直接调用此方法。此方法仅供 `aget_provider_response()` 内部使用。

        生成提供商特定的响应（异步版本）。可用于与底层模型提供商（如 OpenAI、Anthropic）交互并获取原始输出。

        Args:
            messages: 发送给模型的消息列表，应为包含 "content" 和 "role" 键的字典列表。
            kwargs: 提供商生成响应所需的参数。

        Returns:
            模型提供商返回的响应，类型取决于具体用例和 LLM。
        """
        raise NotImplementedError("Async generation not implemented for this provider")

    async def agenerate_chat_completion(
        self,
        messages: List[ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> ConversationDict:
        """:meth:`generate_chat_completion` 的异步版本。"""
        raise NotImplementedError("Async generation not implemented for this provider")


@contextmanager
def get_provider_response(
    model_provider: OpikBaseModel, messages: List[Dict[str, Any]], **kwargs: Any
) -> Any:
    """
    获取和管理模型提供商响应的上下文管理器。
    确保与模型提供商交互过程中的错误被正确处理并记录日志。

    Args:
        model_provider: 派生自 `OpikBaseModel` 的类实例，负责与模型交互。
        messages: 包含要传递给模型的消息或输入的字典列表。
        **kwargs: 自定义模型响应生成的额外关键字参数。

    Yields:
        模型提供商生成的响应。

    Raises:
        exceptions.BaseLLMError: 当模型提供商生成响应失败时抛出。
    """
    try:
        yield model_provider.generate_provider_response(messages, **kwargs)
    except Exception as e:
        LOGGER.error("Failed to call LLM provider, reason: %s", e)
        raise exceptions.BaseLLMError(str(e))


@asynccontextmanager
async def aget_provider_response(
    model_provider: OpikBaseModel, messages: List[Dict[str, Any]], **kwargs: Any
) -> Any:
    """
    从模型提供商获取响应的异步上下文管理器。

    此函数异步与指定的 `model_provider` 交互，基于给定的 `messages` 列表和额外的关键字参数生成响应。
    如果在此过程中发生错误，将记录日志并抛出自定义异常。

    Args:
        model_provider: 请求响应的模型提供商。
        messages: 包含待处理消息的字典列表。
        **kwargs: 传递给模型提供商响应生成方法的额外关键字参数。

    Yields:
        模型提供商异步生成的响应。

    Raises:
        exceptions.BaseLLMError: 异步与模型提供商交互时发生错误时抛出。
    """
    try:
        response = await model_provider.agenerate_provider_response(
            messages=messages, **kwargs
        )
        yield response
    except Exception as e:
        LOGGER.error("Failed to call LLM provider asynchronously, reason: %s", e)
        raise exceptions.BaseLLMError(str(e))


def check_model_output_string(output: Optional[str]) -> str:
    """
    检查模型输出并验证其非 None。

    此函数确保语言模型（LLM）返回的输出具有有效的非空值。
    如果输出为 None，将抛出带有详细信息的错误，有助于调试环境配置不正确或 API 密钥缺失等问题。

    Args:
        output: 语言模型生成的待验证输出字符串。

    Returns:
        经过验证的语言模型输出。

    Raises:
        exceptions.BaseLLMError: 当输出为 None 时抛出。错误信息包含验证环境配置和检查模型 API 密钥可用性的建议。
    """
    if output is None:
        raise exceptions.BaseLLMError(
            "Received None as the output from the LLM. Please verify your environment configuration "
            "and ensure that the API keys for the models in use (e.g., OPENAI_API_KEY) are set correctly."
        )

    return output
