from typing import Optional, TYPE_CHECKING

from . import invoke_agent_decorator
from .converse import chunks_aggregator as converse_chunks_aggregator
from .converse import converse_decorator

from .invoke_model import invoke_model_decorator
from .invoke_model import chunks_aggregator as invoke_model_chunks_aggregator


if TYPE_CHECKING:
    from mypy_boto3_bedrock_runtime.client import BedrockRuntimeClient


def track_bedrock(
    client: "BedrockRuntimeClient",
    project_name: Optional[str] = None,
) -> "BedrockRuntimeClient":
    """为 AWS Bedrock 客户端添加 Opik 追踪功能。

    追踪对 `converse()`、`converse_stream()`、`invoke_model()` 和
    `invoke_model_with_response_stream()` 方法的调用。
    可在其他已启用 Opik 追踪的函数中使用。

    InvokeModel API 支持的模型子提供商（流式和非流式）：
    - **Anthropic** (Claude)
    - **Amazon** (Nova)
    - **Meta** (Llama)
    - **Mistral** (Pixtral)

    Args:
        client: AWS Bedrock 客户端实例
            （botocore.client.BedrockRuntime 或 botocore.client.AgentsforBedrockRuntime）。
        project_name: 用于记录数据的项目名称。

    Returns:
        已启用 Opik 追踪的 Bedrock 客户端实例。
    """

    decorator_for_converse = converse_decorator.BedrockConverseDecorator()
    decorator_for_invoke_agent = invoke_agent_decorator.BedrockInvokeAgentDecorator()
    decorator_for_invoke_model = invoke_model_decorator.BedrockInvokeModelDecorator()

    if hasattr(client, "invoke_agent") and not hasattr(
        client.invoke_agent, "opik_tracked"
    ):
        wrapper = decorator_for_invoke_agent.track(
            type="llm",
            name="bedrock_invoke_agent",
            project_name=project_name,
            generations_aggregator=converse_chunks_aggregator.aggregate_invoke_agent_chunks,
        )
        tracked_invoke_agent = wrapper(client.invoke_agent)
        client.invoke_agent = tracked_invoke_agent

    if hasattr(client, "converse") and not hasattr(client.converse, "opik_tracked"):
        wrapper = decorator_for_converse.track(
            type="llm",
            name="bedrock_converse",
            project_name=project_name,
        )
        tracked_converse = wrapper(client.converse)
        client.converse = tracked_converse

    if hasattr(client, "converse_stream") and not hasattr(
        client.converse_stream, "opik_tracked"
    ):
        stream_wrapper = decorator_for_converse.track(
            type="llm",
            name="bedrock_converse_stream",
            project_name=project_name,
            generations_aggregator=converse_chunks_aggregator.aggregate_converse_stream_chunks,
        )
        tracked_converse_stream = stream_wrapper(client.converse_stream)
        client.converse_stream = tracked_converse_stream

    if hasattr(client, "invoke_model") and not hasattr(
        client.invoke_model, "opik_tracked"
    ):
        wrapper = decorator_for_invoke_model.track(
            type="llm",
            name="bedrock_invoke_model",
            project_name=project_name,
        )
        tracked_invoke_model = wrapper(client.invoke_model)
        client.invoke_model = tracked_invoke_model

    if hasattr(client, "invoke_model_with_response_stream") and not hasattr(
        client.invoke_model_with_response_stream, "opik_tracked"
    ):
        stream_wrapper = decorator_for_invoke_model.track(
            type="llm",
            name="bedrock_invoke_model_stream",
            project_name=project_name,
            generations_aggregator=invoke_model_chunks_aggregator.aggregate_chunks_to_dataclass,
        )
        tracked_invoke_model_stream = stream_wrapper(
            client.invoke_model_with_response_stream
        )
        client.invoke_model_with_response_stream = tracked_invoke_model_stream

    return client
