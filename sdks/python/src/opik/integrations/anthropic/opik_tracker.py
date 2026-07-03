"""Anthropic 客户端 Opik 追踪集成模块。

为 Anthropic SDK 客户端添加 Opik 调用追踪能力，支持消息创建、流式输出、
解析等多种方法的自动监控。
"""

from typing import Optional
import logging

import anthropic
from . import messages_create_decorator
from . import messages_batch_decorator
from typing import TypeVar, Dict, Any
from opik.types import LLMProvider

# AnthropicClient 类型变量，涵盖所有支持的 Anthropic 客户端变体
AnthropicClient = TypeVar(
    "AnthropicClient",
    anthropic.AsyncAnthropic,
    anthropic.Anthropic,
    anthropic.AsyncAnthropicBedrock,
    anthropic.AnthropicBedrock,
    anthropic.AsyncAnthropicVertex,
    anthropic.AnthropicVertex,
)

LOGGER = logging.getLogger(__name__)


def track_anthropic(
    anthropic_client: AnthropicClient,
    project_name: Optional[str] = None,
) -> AnthropicClient:
    """为 Anthropic 客户端添加 Opik 追踪功能。

    集成以下 Anthropic 库对象：
        * AsyncAnthropic,
        * Anthropic,
        * AsyncAnthropicBedrock,
        * AnthropicBedrock,
        * AsyncAnthropicVertex,
        * AnthropicVertex,

    支持的方法（适用于上述所有类）：
        * `client.messages.create()`
        * `client.messages.parse()`
        * `client.messages.stream()`
        * `client.beta.messages.create()`
        * `client.beta.messages.parse()`
        * `client.beta.messages.stream()`

    可在其他 Opik 追踪函数内部使用。

    Args:
        anthropic_client: Anthropic 客户端实例。
        project_name: 用于记录数据的项目名称。

    Returns:
        集成了 Opik 追踪逻辑的 Anthropic 客户端。
    """
    # 防止重复追踪：若已标记则直接返回
    if hasattr(anthropic_client, "opik_tracked"):
        return anthropic_client

    anthropic_client.opik_tracked = True
    provider = (
        LLMProvider.ANTHROPIC
    )  # TODO: 实现对 Vertex 和 Bedrock 的正式支持
    decorator_factory = messages_create_decorator.AnthropicMessagesCreateDecorator(
        provider=provider
    )

    # 从客户端实例中提取元数据（base_url、区域等）
    metadata = _extract_metadata_from_client(anthropic_client)

    # 为各方法创建追踪装饰器
    create_decorator = decorator_factory.track(
        type="llm",
        name="anthropic_messages_create",
        project_name=project_name,
        metadata=metadata,
    )
    stream_decorator = decorator_factory.track(
        type="llm",
        name="anthropic_messages_stream",
        project_name=project_name,
        metadata=metadata,
    )
    # 暂不支持批量创建的追踪，使用警告装饰器替代
    batch_create_decorator = messages_batch_decorator.warning_decorator(
        "At the moment Opik Anthropic integration does not support tracking for `client.beta.messages.batches.create` calls",
        LOGGER,
    )
    completions_create_decorator = messages_batch_decorator.warning_decorator(
        "Opik Anthropic integration does not support tracking for `client.completions.create` calls",
        LOGGER,
    )

    parse_decorator = decorator_factory.track(
        type="llm",
        name="anthropic_messages_parse",
        project_name=project_name,
        metadata=metadata,
    )
    beta_create_decorator = decorator_factory.track(
        type="llm",
        name="anthropic_beta_messages_create",
        project_name=project_name,
        metadata=metadata,
    )
    beta_stream_decorator = decorator_factory.track(
        type="llm",
        name="anthropic_beta_messages_stream",
        project_name=project_name,
        metadata=metadata,
    )
    beta_parse_decorator = decorator_factory.track(
        type="llm",
        name="anthropic_beta_messages_parse",
        project_name=project_name,
        metadata=metadata,
    )

    # 用装饰器替换客户端的原始方法
    anthropic_client.messages.create = create_decorator(
        anthropic_client.messages.create
    )
    anthropic_client.messages.stream = stream_decorator(
        anthropic_client.messages.stream
    )
    # parse 方法可能在旧版 SDK 中不存在，捕获异常并记录调试日志
    try:
        anthropic_client.messages.parse = parse_decorator(
            anthropic_client.messages.parse
        )
    except Exception:
        LOGGER.debug(
            "Failed to patch `client.messages.parse` method. "
            "It is likely because the anthropic SDK version does not support it",
            exc_info=True,
        )
    # beta.messages 方法可能未在当前客户端中实现
    try:
        anthropic_client.beta.messages.create = beta_create_decorator(
            anthropic_client.beta.messages.create
        )
        anthropic_client.beta.messages.stream = beta_stream_decorator(
            anthropic_client.beta.messages.stream
        )
        anthropic_client.beta.messages.parse = beta_parse_decorator(
            anthropic_client.beta.messages.parse
        )
    except AttributeError:
        LOGGER.debug(
            "Failed to patch `client.beta.messages` methods. It is likely because they were not implemented in the provided anthropic client",
            exc_info=True,
        )
    # 批量创建方法可能未在当前客户端中实现
    try:
        anthropic_client.beta.messages.batches.create = batch_create_decorator(
            anthropic_client.beta.messages.batches.create
        )
    except Exception:
        LOGGER.debug(
            "Failed to patch `client.messages.batches.create` method. It is likely because it was not implemented in the provided anthropic client",
            exc_info=True,
        )

    # completions.create 方法可能未在当前客户端中实现
    try:
        anthropic_client.completions.create = completions_create_decorator(
            anthropic_client.completions.create
        )
    except Exception:
        LOGGER.debug(
            "Failed to patch `client.completions.create` method. It is likely because it was not implemented in the provided anthropic client",
            exc_info=True,
        )

    return anthropic_client


def _extract_metadata_from_client(client: AnthropicClient) -> Dict[str, Any]:
    """从 Anthropic 客户端实例中提取元数据。

    根据客户端类型提取不同的元数据信息：
    - 所有客户端：base_url
    - Bedrock 客户端：aws_region
    - Vertex 客户端：region、project_id

    Args:
        client: Anthropic 客户端实例。

    Returns:
        包含客户端元数据的字典。
    """
    metadata = {"base_url": client.base_url}
    if isinstance(
        client, (anthropic.AnthropicBedrock, anthropic.AsyncAnthropicBedrock)
    ):
        metadata["aws_region"] = client.aws_region
    elif isinstance(
        client, (anthropic.AnthropicVertex, anthropic.AsyncAnthropicVertex)
    ):
        metadata["region"] = client.region
        metadata["project_id"] = client.project_id

    return metadata
