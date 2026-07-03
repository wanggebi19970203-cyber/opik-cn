"""Anthropic 消息创建方法的追踪装饰器模块。

专门用于追踪 Anthropic 客户端 `messages.create` 方法调用的装饰器实现，
处理输入预处理、输出预处理以及流式响应的追踪逻辑。
"""

import logging
import warnings
from typing import Any, Callable, Dict, List, Optional, Tuple, Union

import anthropic
from anthropic.types import Message as AnthropicMessage
from typing_extensions import override

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator

from . import stream_patchers

LOGGER = logging.getLogger(__name__)

# 需要作为输入记录的关键字参数
KWARGS_KEYS_TO_LOG_AS_INPUTS = ["messages", "system", "tools", "output_format"]
# 需要作为输出记录的响应字段
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["content"]


class AnthropicMessagesCreateDecorator(base_track_decorator.BaseTrackDecorator):
    """BaseTrackDecorator 的 Anthropic 专用实现。

    专门用于追踪 `[Anthropic.AsyncAnthropic].messages.create` 方法的调用。
    """

    def __init__(self, provider: str) -> None:
        self.provider: str = provider

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        """Span 开始前的输入预处理器。

        从 kwargs 中提取需要记录的输入参数和元数据，
        构建 StartSpanParameters 对象用于创建新的追踪 Span。
        """
        assert kwargs is not None, (
            "Expected kwargs to be not None in Anthropic.messages.create(**kwargs)"
        )
        metadata = track_options.metadata if track_options.metadata is not None else {}
        name = track_options.name if track_options.name is not None else func.__name__

        # 将 kwargs 拆分为输入部分和元数据部分
        input, metadata_from_kwargs = dict_utils.split_dict_by_keys(
            kwargs, KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata.update(metadata_from_kwargs)
        metadata["created_from"] = "anthropic"
        tags = ["anthropic"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            provider=self.provider,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Union[str, AnthropicMessage],
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        """Span 结束前的输出预处理器。

        处理 Anthropic 响应消息，提取 token 用量、输出内容和模型信息。
        当输出为字符串时（表示错误），将其包装为错误字典。
        """
        if isinstance(output, str):
            output = {"error": output}
            result = arguments_helpers.EndSpanParameters(output=output)

            return result

        # 提取并构建 token 用量信息
        usage_dict = output.usage.model_dump()
        opik_usage = llm_usage.try_build_opik_usage_or_log_error(
            provider=self.provider,
            usage=usage_dict,
            logger=LOGGER,
            error_message="Failed to log token usage from anthropic call",
        )
        # Anthropic 的 messages.parse() 返回包含 ParsedTextBlock 的 ParsedMessage，
        # 但 ParsedTextBlock 不在 Message.content 的联合类型定义中，
        # Pydantic 序列化时会对每个无法匹配的联合变体发出警告。
        # 数据本身可以正确序列化，这些警告属于噪声信息。
        with warnings.catch_warnings():
            warnings.filterwarnings(
                "ignore",
                message="Pydantic serializer warnings",
            )
            output_dict = output.model_dump()
        # 将响应拆分为输出内容和元数据
        span_output, metadata = dict_utils.split_dict_by_keys(
            output_dict, RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )
        model = metadata.get("model")

        # 如果存在迭代次数信息（如 agentic 工具调用），记录到元数据中
        if usage_dict.get("iterations"):
            metadata["usage.iterations"] = usage_dict["iterations"]

        result = arguments_helpers.EndSpanParameters(
            output=span_output, usage=opik_usage, metadata=metadata, model=model
        )

        return result

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Union[
        None, anthropic.MessageStreamManager, anthropic.AsyncMessageStreamManager
    ]:
        """流式响应处理器。

        根据输出类型判断是否为流式对象，若是则对其进行打补丁以支持追踪。
        支持同步/异步的 Stream、MessageStreamManager 以及 Beta 版本的流式管理器。
        非流式输出返回 None。
        """
        # 处理同步 MessageStreamManager
        if isinstance(output, anthropic.MessageStreamManager):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_sync_message_stream_manager(
                output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                finally_callback=self._after_call,
            )

        # 处理异步 MessageStreamManager
        if isinstance(output, anthropic.AsyncMessageStreamManager):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_async_message_stream_manager(
                output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                finally_callback=self._after_call,
            )

        # 处理同步 Stream
        if isinstance(output, anthropic.Stream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_sync_stream(
                output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                finally_callback=self._after_call,
            )

        # 处理异步 AsyncStream
        if isinstance(output, anthropic.AsyncStream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_async_stream(
                output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                finally_callback=self._after_call,
            )

        # 处理 Beta 版本的流式管理器（可能在旧版 SDK 中不可用）
        try:
            from anthropic.lib.streaming._beta_messages import (
                BetaAsyncMessageStreamManager,
                BetaMessageStreamManager,
            )

            if isinstance(output, BetaMessageStreamManager):
                span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
                return stream_patchers.patch_sync_beta_message_stream_manager(
                    output,
                    span_to_end=span_to_end,
                    trace_to_end=trace_to_end,
                    finally_callback=self._after_call,
                )

            if isinstance(output, BetaAsyncMessageStreamManager):
                span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
                return stream_patchers.patch_async_beta_message_stream_manager(
                    output,
                    span_to_end=span_to_end,
                    trace_to_end=trace_to_end,
                    finally_callback=self._after_call,
                )
        except ImportError:
            pass

        # 非流式输出，返回 None
        NOT_A_STREAM = None

        return NOT_A_STREAM
