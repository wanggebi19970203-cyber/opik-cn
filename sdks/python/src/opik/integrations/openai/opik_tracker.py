from typing import Optional, TypeVar, Union

import openai
import opik

from . import (
    chat_completion_chunks_aggregator,
    openai_chat_completions_decorator,
)
import opik.semantic_version as semantic_version
from opik.types import LLMProvider

OpenAIClient = TypeVar("OpenAIClient", openai.OpenAI, openai.AsyncOpenAI)


def _get_provider(openai_client: OpenAIClient) -> str:
    """Get the provider name from the OpenAI client's base URL.

    从 OpenAI 客户端的 base URL 中获取提供商名称。
    """
    if openai_client.base_url.host != "api.openai.com":
        return openai_client.base_url.host
    return "openai"


def track_openai(
    openai_client: OpenAIClient,
    project_name: Optional[str] = None,
    provider: Optional[Union[str, LLMProvider]] = None,
) -> OpenAIClient:
    """Adds Opik tracking wrappers to an OpenAI client.

    为 OpenAI 客户端添加 Opik 追踪包装器。

    客户端始终会被修补；但每个被包装的调用在发送遥测数据之前会检查
    `opik.is_tracing_active()`。如果在调用时追踪处于禁用状态，
    被包装的函数会正常执行，但不会发送 span/trace。

    追踪以下调用：
    * `openai_client.chat.completions.create()`，包括支持 stream=True 模式。
    * `openai_client.beta.chat.completions.parse()`
    * `openai_client.beta.chat.completions.stream()`
    * `openai_client.responses.create()`
    * `openai_client.videos.create()`、`videos.create_and_poll()`、`videos.poll()`、
      `videos.list()`、`videos.delete()`、`videos.remix()`、`videos.download_content()`，
      以及下载内容的 `write_to_file()`
    * `openai_client.audio.speech.create()` 和
      `openai_client.audio.speech.with_streaming_response.create()`

    可在其他 Opik 追踪函数内部使用。

    Args:
        openai_client: OpenAI 或 AsyncOpenAI 客户端实例。
        project_name: 用于记录数据的项目名称。
        provider: 在集成创建的每个 LLM span 上记录的提供商名称。
            OpenAI SDK 通常被用作其他 OpenAI 兼容 API（如 Together、
            OpenRouter、vLLM、DeepSeek 等）的客户端，因此该参数允许您
            记录实际的模型提供商，而不是 base URL 主机。接受任意字符串，
            或通过 `opik.LLMProvider` 枚举指定 Opik 识别的用于成本追踪的
            提供商之一："openai"、"anthropic"、"google_vertexai"、"google_ai"、
            "groq"、"bedrock"、"anthropic_vertexai"。未提供时，将从客户端的
            base URL 推断提供商（api.openai.com 对应 "openai"，否则为 base URL 主机）。

    Returns:
        已启用 Opik 追踪的修改后的 OpenAI 客户端。
    """
    if hasattr(openai_client, "opik_tracked"):
        return openai_client

    openai_client.opik_tracked = True

    if provider is None:
        resolved_provider = _get_provider(openai_client)
    elif isinstance(provider, LLMProvider):
        # 标准化为纯字符串值，避免裸枚举成员以 "LLMProvider.OPENAI" 的形式泄漏到日志/span 中。
        resolved_provider = provider.value
    else:
        resolved_provider = provider

    _patch_openai_chat_completions(openai_client, resolved_provider, project_name)

    if hasattr(openai_client, "responses"):
        _patch_openai_responses(openai_client, resolved_provider, project_name)

    if hasattr(openai_client, "videos"):
        _patch_openai_videos(openai_client, resolved_provider, project_name)

    if hasattr(openai_client, "audio"):
        _patch_openai_audio(openai_client, resolved_provider, project_name)

    return openai_client


def _patch_openai_chat_completions(
    openai_client: OpenAIClient,
    provider: str,
    project_name: Optional[str] = None,
) -> None:
    chat_completions_decorator_factory = (
        openai_chat_completions_decorator.OpenaiChatCompletionsTrackDecorator()
    )
    chat_completions_decorator_factory.provider = provider

    completions_create_decorator = chat_completions_decorator_factory.track(
        type="llm",
        name="chat_completion_create",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )
    completions_parse_decorator = chat_completions_decorator_factory.track(
        type="llm",
        name="chat_completion_parse",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )
    completions_stream_decorator = chat_completions_decorator_factory.track(
        type="llm",
        name="chat_completion_stream",
        generations_aggregator=chat_completion_chunks_aggregator.aggregate,
        project_name=project_name,
    )

    openai_client.chat.completions.create = completions_create_decorator(
        openai_client.chat.completions.create
    )
    if semantic_version.SemanticVersion.parse(openai.__version__) < "1.92.0":  # type: ignore
        # beta.chat.completions.stream() 底层调用 chat.completions.create(stream=True)。
        # 因此装饰 `create` 会自动追踪 `stream`。
        openai_client.beta.chat.completions.parse = completions_parse_decorator(
            openai_client.beta.chat.completions.parse
        )
    else:
        # OpenAI 重构了 beta API。
        # * chat.completion.stream 底层调用 chat.completion.create，因此无需再次装饰。
        # * 但 beta.chat.completion.stream 不会调用 chat.completion.create，因此需要单独装饰！
        openai_client.beta.chat.completions.stream = completions_stream_decorator(
            openai_client.beta.chat.completions.stream
        )

        openai_client.chat.completions.parse = completions_parse_decorator(
            openai_client.chat.completions.parse
        )
        openai_client.beta.chat.completions.parse = completions_parse_decorator(
            openai_client.beta.chat.completions.parse
        )


def _patch_openai_responses(
    openai_client: OpenAIClient,
    provider: str,
    project_name: Optional[str] = None,
) -> None:
    from . import (
        response_events_aggregator,
        openai_responses_decorator,
    )

    responses_decorator_factory = (
        openai_responses_decorator.OpenaiResponsesTrackDecorator()
    )
    responses_decorator_factory.provider = provider

    if hasattr(openai_client.responses, "create"):
        responses_create_decorator = responses_decorator_factory.track(
            type="llm",
            name="responses_create",
            generations_aggregator=response_events_aggregator.aggregate,
            project_name=project_name,
        )
        openai_client.responses.create = responses_create_decorator(
            openai_client.responses.create
        )

    if hasattr(openai_client.responses, "parse"):
        responses_parse_decorator = responses_decorator_factory.track(
            type="llm",
            name="responses_parse",
            generations_aggregator=response_events_aggregator.aggregate,
            project_name=project_name,
        )
        openai_client.responses.parse = responses_parse_decorator(
            openai_client.responses.parse
        )


def _patch_openai_videos(
    openai_client: OpenAIClient,
    provider: str,
    project_name: Optional[str] = None,
) -> None:
    from . import videos

    create_decorator_factory = videos.VideosCreateTrackDecorator(provider=provider)
    download_decorator_factory = videos.VideosDownloadTrackDecorator()

    video_metadata = {"created_from": "openai", "type": "openai_videos"}
    video_tags = ["openai"]

    if hasattr(openai_client.videos, "create"):
        decorator = create_decorator_factory.track(
            type="llm",
            name="videos.create",
            project_name=project_name,
        )
        openai_client.videos.create = decorator(openai_client.videos.create)

    if hasattr(openai_client.videos, "create_and_poll"):
        decorator = opik.track(
            name="videos.create_and_poll",
            tags=video_tags,
            metadata=video_metadata,
            project_name=project_name,
        )
        openai_client.videos.create_and_poll = decorator(
            openai_client.videos.create_and_poll
        )

    if hasattr(openai_client.videos, "remix"):
        decorator = create_decorator_factory.track(
            type="llm",
            name="videos.remix",
            project_name=project_name,
        )
        openai_client.videos.remix = decorator(openai_client.videos.remix)

    # 注意：videos.retrieve 故意不做修补，以避免产生过多 span，
    # 因为它在轮询操作期间会被频繁调用。

    if hasattr(openai_client.videos, "poll"):
        decorator = opik.track(
            name="videos.poll",
            tags=video_tags,
            metadata=video_metadata,
            project_name=project_name,
        )
        openai_client.videos.poll = decorator(openai_client.videos.poll)

    if hasattr(openai_client.videos, "delete"):
        decorator = opik.track(
            name="videos.delete",
            tags=video_tags,
            metadata=video_metadata,
            project_name=project_name,
        )
        openai_client.videos.delete = decorator(openai_client.videos.delete)

    # 修补 download_content - 同时修补返回实例上的 write_to_file
    # download_content 返回一个惰性响应对象，write_to_file 执行实际下载
    if hasattr(openai_client.videos, "download_content"):
        decorator = download_decorator_factory.track(
            type="general",
            name="videos.download_content",
            project_name=project_name,
        )
        openai_client.videos.download_content = decorator(
            openai_client.videos.download_content
        )

    if hasattr(openai_client.videos, "list"):
        decorator = opik.track(
            name="videos.list",
            tags=video_tags,
            metadata=video_metadata,
            project_name=project_name,
        )
        openai_client.videos.list = decorator(openai_client.videos.list)


def _patch_openai_audio(
    openai_client: OpenAIClient,
    provider: str,
    project_name: Optional[str] = None,
) -> None:
    from . import audio

    tts_create_decorator_factory = audio.TTSCreateTrackDecorator(provider=provider)
    tts_streaming_decorator_factory = audio.TTSStreamingResponseCreateTrackDecorator(
        provider=provider,
    )

    if not hasattr(openai_client.audio, "speech"):
        return

    # 在修补 speech.create 之前先修补 with_streaming_response.create。
    # with_streaming_response 是一个 cached_property，它在初始化时通过 functools.wraps
    # 捕获 speech.create。如果先修补 speech.create，functools.wraps 会将
    # opik_tracked=True 复制到流式包装器上，导致幂等性检查跳过它。
    if hasattr(openai_client.audio.speech, "with_streaming_response") and hasattr(
        openai_client.audio.speech.with_streaming_response, "create"
    ):
        decorator = tts_streaming_decorator_factory.track(
            type="llm",
            name="audio.speech.with_streaming_response.create",
            project_name=project_name,
        )
        openai_client.audio.speech.with_streaming_response.create = decorator(
            openai_client.audio.speech.with_streaming_response.create
        )

    # 修补 audio.speech.create（同步，返回 HttpxBinaryResponseContent）
    if hasattr(openai_client.audio.speech, "create"):
        decorator = tts_create_decorator_factory.track(
            type="llm",
            name="audio.speech.create",
            project_name=project_name,
        )
        openai_client.audio.speech.create = decorator(openai_client.audio.speech.create)
