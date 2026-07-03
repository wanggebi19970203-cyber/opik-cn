from typing import Callable, Optional

import google.genai as genai
from google.genai import types as genai_types

from . import (
    generate_content_decorator,
    generations_aggregators,
    stream_wrappers,
    encoder_extension,
)


def _get_provider(client: genai.Client) -> str:
    """从 GenAI 客户端获取提供商名称。

    根据客户端配置判断使用的是 Vertex AI 还是 Google AI 平台。

    Args:
        client: GenAI 客户端实例。

    Returns:
        提供商名称字符串，"google_vertexai" 或 "google_ai"。
    """
    return "google_vertexai" if client.vertexai else "google_ai"


def track_genai(
    client: genai.Client,
    project_name: Optional[str] = None,
    upload_videos: bool = True,
    cost_callback: Optional[
        Callable[[genai_types.GenerateContentResponse], Optional[float]]
    ] = None,
) -> genai.Client:
    """为 genai.Client 添加 Opik 追踪功能。

    追踪以下方法的调用：
    * client.models.generate_content - 同步内容生成
    * client.models.generate_content_stream - 同步流式内容生成
    * client.aio.models.generate_content - 异步内容生成
    * client.aio.models.generate_content_stream - 异步流式内容生成
    * client.models.generate_videos - Veo 视频生成
    * client.aio.models.generate_videos - 异步 Veo 视频生成
    * client.operations.get - 轮询视频生成状态
    * video.save - 保存生成的视频

    可在其他已启用 Opik 追踪的函数中使用。

    Args:
        client: genai.Client 实例。
        project_name: 用于记录数据的项目名称。
        upload_videos: 是否在调用 video.save 时将生成的视频作为附件上传。
            默认为 True。
        cost_callback: 可选的回调函数，接收 GenerateContentResponse 并返回以美元
            为单位的成本（浮点数），返回 None 则使用自动成本计算。适用于定价模型
            复杂的场景（例如思考 token 有不同费率），此时自动成本追踪可能不够准确。

    Returns:
        启用了 Opik 追踪的 genai.Client 实例。
    """
    if hasattr(client, "opik_tracked"):
        return client
    encoder_extension.register()

    client.opik_tracked = True

    provider = _get_provider(client)

    _patch_generate_content(client, provider, project_name, cost_callback)
    _patch_generate_videos(client, provider, project_name, upload_videos)

    return client


def _patch_generate_content(
    client: genai.Client,
    provider: str,
    project_name: Optional[str],
    cost_callback: Optional[
        Callable[[genai_types.GenerateContentResponse], Optional[float]]
    ] = None,
) -> None:
    """为 generate_content 方法添加 Opik 追踪。

    装饰同步和异步版本的 generate_content 及 generate_content_stream 方法。

    Args:
        client: GenAI 客户端实例。
        provider: 提供商名称。
        project_name: 项目名称。
        cost_callback: 可选的成本回调函数。
    """
    decorator_factory = generate_content_decorator.GenerateContentTrackDecorator(
        provider=provider,
        cost_callback=cost_callback,
    )

    client.models.generate_content = decorator_factory.track(
        name="generate_content",
        type="llm",
        project_name=project_name,
    )(client.models.generate_content)

    # 需要进行此转换，使 @track 装饰器不再将 generate_content_stream 视为生成器函数，
    # 因为生成器函数的追踪方式与此处需求不同。用户仍然会获得 Iterator 对象作为返回值。
    client.models.generate_content_stream = (
        stream_wrappers.generator_function_to_normal_function(
            client.models.generate_content_stream
        )
    )
    client.models.generate_content_stream = decorator_factory.track(
        name="generate_content_stream",
        type="llm",
        project_name=project_name,
        generations_aggregator=generations_aggregators.aggregate_response_content_items,
    )(client.models.generate_content_stream)

    client.aio.models.generate_content = decorator_factory.track(
        name="async_generate_content",
        type="llm",
        project_name=project_name,
    )(client.aio.models.generate_content)

    # 无需像同步方法那样进行类似的转换，因为异步版本的 generate_content_stream
    # 已经是对生成器函数的封装，其工作方式与辅助函数类似
    client.aio.models.generate_content_stream = decorator_factory.track(
        name="async_generate_content_stream",
        type="llm",
        project_name=project_name,
        generations_aggregator=generations_aggregators.aggregate_response_content_items,
    )(client.aio.models.generate_content_stream)


GENAI_VIDEOS_TAGS = ["genai"]
GENAI_VIDEOS_METADATA = {"created_from": "genai", "type": "genai_videos"}


def _patch_generate_videos(
    client: genai.Client,
    provider: str,
    project_name: Optional[str],
    upload_videos: bool,
) -> None:
    """为 generate_videos 和 operations.get 方法添加 Opik 追踪。

    Args:
        client: GenAI 客户端实例。
        provider: 提供商名称。
        project_name: 项目名称。
        upload_videos: 是否上传生成的视频。
    """
    from . import videos

    if not hasattr(client.models, "generate_videos"):
        return

    video_decorator_factory = videos.GenerateVideosTrackDecorator(provider=provider)

    # 为同步 generate_videos 方法添加追踪
    client.models.generate_videos = video_decorator_factory.track(
        name="models.generate_videos",
        type="llm",
        project_name=project_name,
        tags=GENAI_VIDEOS_TAGS,
        metadata=GENAI_VIDEOS_METADATA,
    )(client.models.generate_videos)

    # 为异步 generate_videos 方法添加追踪
    if hasattr(client.aio.models, "generate_videos"):
        client.aio.models.generate_videos = video_decorator_factory.track(
            name="models.generate_videos",
            type="llm",
            project_name=project_name,
            tags=GENAI_VIDEOS_TAGS,
            metadata=GENAI_VIDEOS_METADATA,
        )(client.aio.models.generate_videos)

    # 为 operations.get 添加追踪以监控轮询，并为已完成的视频补丁 Video.save
    _patch_operations_get(client, project_name, upload_videos)


def _patch_operations_get(
    client: genai.Client, project_name: Optional[str], upload_videos: bool
) -> None:
    """为 operations.get 方法添加追踪，以监控轮询并为已完成的视频补丁 Video.save。

    Args:
        client: GenAI 客户端实例。
        project_name: 项目名称。
        upload_videos: 是否上传生成的视频。
    """
    from . import videos

    if not hasattr(client, "operations") or not hasattr(client.operations, "get"):
        return

    operations_decorator = videos.OperationsGetTrackDecorator(
        project_name=project_name, upload_videos=upload_videos
    )
    client.operations.get = operations_decorator.track(
        name="operations.get",
        type="general",
        project_name=project_name,
        tags=GENAI_VIDEOS_TAGS,
        metadata=GENAI_VIDEOS_METADATA,
    )(client.operations.get)
