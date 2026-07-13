import logging
import time
from typing import Any, Dict, List, Optional, Tuple, Union

import google.adk.agents
from google.adk.agents import callback_context
from google.adk import models
from google.adk.tools import base_tool
from google.adk.tools import tool_context

import opik
from opik import context_storage
from opik.api_objects import span, trace
from opik.types import DistributedTraceHeadersDict
from opik.decorator import span_creation_handler, arguments_helpers

from . import (
    helpers as adk_helpers,
    callback_context_info_extractors,
    output_cache,
    patchers,
)
from .patchers import (
    litellm_wrappers,
    llm_response_wrapper,
)
from .patchers.adk_otel_tracer import llm_span_helpers
from .graph import mermaid_graph_builder

LOGGER = logging.getLogger(__name__)

SpanOrTraceData = Union[span.SpanData, trace.TraceData]


class OpikTracer:
    """
    用于 google-adk 的 Opik 追踪器。
    """

    def __init__(
        self,
        name: Optional[str] = None,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        project_name: Optional[str] = None,
        distributed_headers: Optional[DistributedTraceHeadersDict] = None,
    ):
        """
        初始化 OpikTracer。

        Args:
            name: 追踪器创建的根 span 或 trace 的默认名称。
            tags: 追踪器创建的所有 trace 和 span 的默认标签。
            metadata: 追踪器创建的所有 trace 和 span 的默认元数据。
            project_name: 用于追踪的项目名称。
            distributed_headers: 分布式追踪头信息。
        """
        self.name = name
        self.tags = tags
        self.metadata = metadata or {}
        self.metadata["created_from"] = "google-adk"
        self.project_name = project_name
        self._distributed_headers = distributed_headers

        self._init_internal_attributes()

    @property
    def _opik_client(self) -> opik.Opik:
        return opik.get_global_client()

    def _init_internal_attributes(self) -> None:
        # 按 ADK invocation_id 缓存最后一次模型输出。单个追踪器实例会在
        # 并发调用间共享（track_adk_agent_recursive 模式），因此按 invocation_id
        # 隔离各自的输出；缓存有上限，不会无限增长。
        self._last_model_output = output_cache.LastModelOutputCache()
        # 追踪首 token 时间（TTFT）：映射 span_id -> (请求开始时间, 首 token 时间)
        self._ttft_tracking: Dict[str, Tuple[float, Optional[float]]] = {}

        patchers.patch_adk(
            distributed_headers=self._distributed_headers,
        )

    def _has_response_content(self, llm_response: models.LlmResponse) -> bool:
        """
        检查 LlmResponse 是否包含实际内容（文本或函数调用）。

        Args:
            llm_response: 待检查的 LLM 响应。

        Returns:
            如果响应包含文本内容或函数调用则返回 True，否则返回 False。
        """
        try:
            # 直接检查 LlmResponse 对象的内容结构
            if llm_response.content is not None and llm_response.content.parts:
                for part in llm_response.content.parts:
                    # 检查文本内容
                    if part.text and part.text.strip():
                        return True
                    # 检查函数调用内容（工具调用）
                    if part.function_call:
                        return True
            return False
        except Exception as e:
            LOGGER.debug(
                f"Error checking LlmResponse.content.parts for TTFT: {e}",
                exc_info=True,
            )
            return False

    def _safe_ttft_tracking(
        self, span_id: Optional[str], pop: bool = False
    ) -> Tuple[Optional[float], Optional[float]]:
        """
        安全地获取某个 span 的首 token 时间（TTFT）追踪数据。

        Args:
            span_id: 待查询的 span ID。
            pop: 若为 True，获取后移除该条目；若为 False，保留该条目。

        Returns:
            (请求开始时间, 首 token 时间) 的元组。如果 span_id 为 None 或未找到，
            返回 (None, None)。
        """
        if span_id is None or span_id not in self._ttft_tracking:
            return (None, None)
        if pop:
            return self._ttft_tracking.pop(span_id)
        return self._ttft_tracking[span_id]

    def flush(self) -> None:
        self._opik_client.flush()

    def before_agent_callback(
        self,
        callback_context: callback_context.CallbackContext,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            current_trace = context_storage.get_trace_data()
            current_span = context_storage.top_span_data()

            thread_id, session_metadata = (
                callback_context_info_extractors.try_get_session_info(callback_context)
            )

            agent_metadata = self.metadata.copy()
            agent_metadata["adk_invocation_id"] = callback_context.invocation_id
            agent_metadata.update(session_metadata)

            _try_add_agent_graph_to_metadata(agent_metadata, callback_context)

            if callback_context.user_content is not None:
                user_input = adk_helpers.convert_adk_base_model_to_dict(
                    callback_context.user_content
                )
            else:
                user_input = None

            name = self.name or callback_context.agent_name

            if current_span is not None:
                current_span.update(
                    name=name,
                    metadata={**agent_metadata},
                    input=user_input,
                    tags=self.tags,
                    project_name=self.project_name,
                )
            elif current_trace is not None:
                current_trace.update(
                    name=name,
                    metadata={**agent_metadata},
                    input=user_input,
                    tags=self.tags,
                    thread_id=thread_id,
                    project_name=self.project_name,
                )
            else:
                LOGGER.warning(
                    f"No current span or trace found in context for agent: {callback_context.agent_name}"
                )

        except Exception as e:
            LOGGER.error(f"Failed during before_agent_callback(): {e}", exc_info=True)

    def after_agent_callback(
        self,
        callback_context: callback_context.CallbackContext,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            output = self._last_model_output.get(callback_context.invocation_id)
            current_span = context_storage.top_span_data()
            current_trace = context_storage.get_trace_data()
            if current_span is not None:
                current_span.update(
                    output=output,
                    project_name=self.project_name,
                )
            elif current_trace is not None:
                current_trace.update(
                    output=output,
                    project_name=self.project_name,
                )
            else:
                LOGGER.warning(
                    "No current span or trace found in context for agent output update"
                )
        except Exception as e:
            LOGGER.error(f"Failed during after_agent_callback(): {e}", exc_info=True)

    def before_model_callback(
        self,
        callback_context: callback_context.CallbackContext,
        llm_request: models.LlmRequest,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            input = adk_helpers.convert_adk_base_model_to_dict(llm_request)

            provider, model = litellm_wrappers.parse_provider_and_model(
                llm_request.model
            )
            if provider is None:
                provider = adk_helpers.get_adk_provider()

            # ADK 在执行 LLM 调用的 `start_as_current_span` 之前就运行 `before_model_callback`，
            # 因此无法在此方法中更新 Opik span。
            # 所以我们在这里手动创建 span，该流程由 ADKTracerWrapper 内部处理。
            result = span_creation_handler.create_span_respecting_context(
                start_span_arguments=arguments_helpers.StartSpanParameters(
                    name=model,
                    project_name=self.project_name,
                    metadata={
                        **self.metadata,
                        llm_span_helpers.SPAN_STATUS: llm_span_helpers.LLMSpanStatus.STARTED,
                    },
                    type="llm",
                    model=model,
                    provider=provider,
                    input=input,
                ),
                distributed_trace_headers=None,
            )

            context_storage.add_span_data(result.span_data)

            # 记录请求开始时间，用于计算首 token 时间
            request_start_time = time.time()
            self._ttft_tracking[result.span_data.id] = (request_start_time, None)
        except Exception as e:
            LOGGER.error(f"Failed during before_model_callback(): {e}", exc_info=True)

    def after_model_callback(
        self,
        callback_context: callback_context.CallbackContext,
        llm_response: models.LlmResponse,
        *args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            is_partial = llm_response.partial is True
        except Exception:
            LOGGER.debug("Error checking for partial chunks", exc_info=True)
            is_partial = False

        span_id: Optional[str] = None
        exception_occurred = False
        try:
            model = None
            usage = None
            output = None

            if adk_helpers.has_empty_text_part_content(llm_response):
                # 提前返回前清理已有的 TTFT 追踪数据
                current_span = context_storage.top_span_data()
                if current_span is not None and current_span.id is not None:
                    self._safe_ttft_tracking(current_span.id, pop=True)
                return

            current_span = context_storage.top_span_data()
            if current_span is None:
                # The span pushed in before_model_callback is invisible here: ADK's
                # ContextCacheConfig wraps the LLM call in its own OTel span, which
                # forks the async context under SSE streaming, so the ContextVar
                # mutation isn't visible and top_span_data() returns None (#5524).
                #
                # We can't finalize the per-LLM span, but we recover the model
                # OUTPUT into the per-invocation, bounded _last_model_output cache
                # (added in #7266) so after_agent_callback still stamps the trace
                # output instead of dropping the whole answer. Keying by
                # invocation_id keeps this safe across concurrent SSE sessions.
                #
                # Discard up front (mirroring the non-detached path below) so a
                # failed conversion leaves no stale value; partial chunks never
                # cache — we wait for the final response. The recovered output
                # keeps its usage metadata (the non-detached path pops that onto the
                # span, but there is no span here): harmless on the display-only
                # trace output, and it preserves usage info that's otherwise lost.
                self._last_model_output.discard(callback_context.invocation_id)
                if not is_partial:
                    try:
                        self._last_model_output.set(
                            callback_context.invocation_id,
                            adk_helpers.convert_adk_base_model_to_dict(llm_response),
                        )
                    except Exception:
                        LOGGER.debug(
                            "Failed to recover model output without a current span",
                            exc_info=True,
                        )
                LOGGER.debug(
                    "No current span in context (detached async context, e.g. "
                    "ContextCacheConfig); recovered model output via the cache"
                )
                return

            # 提前保存 span_id，确保所有退出路径都能正确清理
            span_id = current_span.id

            # 追踪首 token 时间：检测首个 token 的到达
            # 在每次回调（包括部分分片）中检查首 token，
            # 以便捕获内容首次出现的时刻
            request_start_time, first_token_time = self._safe_ttft_tracking(
                span_id, pop=False
            )
            if (
                first_token_time is None
                and request_start_time is not None
                and span_id is not None
            ):
                # 检查此响应是否包含实际内容（首 token）
                # 内容可以是文本或函数调用（工具调用）
                if self._has_response_content(llm_response):
                    # 检测到首 token - 记录时间
                    first_token_time = time.time()
                    self._ttft_tracking[span_id] = (
                        request_start_time,
                        first_token_time,
                    )

            # 忽略部分分片的最终处理，ADK 会在最后用完整响应调用此方法
            # 注意：我们故意保留部分分片的 TTFT 追踪条目，因为 ADK 会用最终的
            # 非部分响应再次调用此方法，届时会正确清理
            if is_partial:
                return

            # 本次调用的最终（非部分）响应：预先清除该 invocation 缓存的输出，
            # 这样即使下方转换失败（或后续出错），after_agent_callback 也不会
            # 写入过期数据。仅在转换成功时重新设置。
            self._last_model_output.discard(callback_context.invocation_id)

            try:
                output = adk_helpers.convert_adk_base_model_to_dict(llm_response)
                usage_data = llm_response_wrapper.pop_llm_usage_data(
                    output, current_span.provider
                )
                if usage_data is not None:
                    model = usage_data.model
                    usage = usage_data.opik_usage
            except Exception as e:
                LOGGER.debug(
                    f"Error converting LlmResponse to dict or extracting usage data, reason: {e}",
                    exc_info=True,
                )

            # 计算首 token 时间并添加到元数据
            metadata_update = {}
            request_start_time, first_token_time = self._safe_ttft_tracking(
                span_id, pop=True
            )
            if first_token_time is not None and request_start_time is not None:
                time_to_first_token = first_token_time - request_start_time
                metadata_update["time_to_first_token"] = time_to_first_token

            # 与已有元数据合并
            if current_span.metadata is None:
                current_span.metadata = {}
            current_span.metadata.update(metadata_update)
            current_span.metadata[llm_span_helpers.SPAN_STATUS] = (
                llm_span_helpers.LLMSpanStatus.READY_FOR_FINALIZATION.value
            )

            current_span.update(
                output=output,
                name=model or current_span.model,
                type="llm",
                model=model,
                usage=usage,
                metadata=current_span.metadata,
                project_name=self.project_name,
            )

            context_storage.pop_span_data(ensure_id=current_span.id)
            current_span.init_end_time()
            # 手动关闭此 span，因为 ADK 关闭得太晚，
            # 而且还会在其中添加工具 span，这是我们希望避免的。
            if opik.is_tracing_active():
                self._opik_client.__internal_api__span__(**current_span.as_parameters)
            if output is not None:
                self._last_model_output.set(callback_context.invocation_id, output)

        except Exception as e:
            exception_occurred = True
            LOGGER.error(f"Failed during after_model_callback(): {e}", exc_info=True)
        finally:
            # 在所有退出路径上清理 TTFT 追踪条目，防止内存泄漏
            # 跳过部分分片的清理（正常返回），因为 ADK 会用最终响应再次调用
            # 对于最终响应，条目已在前面被弹出，此处为空操作
            # 对于错误（exception_occurred=True）或提前返回，此处确保清理发生
            if span_id is not None and (exception_occurred or not is_partial):
                self._ttft_tracking.pop(span_id, None)

    def before_tool_callback(
        self,
        tool: base_tool.BaseTool,
        args: Dict[str, Any],
        tool_context: tool_context.ToolContext,
        *other_args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            current_span = context_storage.top_span_data()

            tool_metadata = {
                "function_call_id": tool_context.function_call_id,
                **self.metadata,
            }

            # 用工具信息更新现有 span
            if current_span is not None:
                current_span.update(
                    name=tool.name,
                    type="tool",
                    input=args,
                    metadata={**tool_metadata},
                    project_name=self.project_name,
                )
            else:
                LOGGER.warning(
                    f"No current span found in context for tool: {tool.name}"
                )
                _log_tool_context_warning(context=tool_context)

        except Exception as e:
            LOGGER.error(f"Failed during before_tool_callback(): {e}", exc_info=True)

    def after_tool_callback(
        self,
        tool: base_tool.BaseTool,
        args: Dict[str, Any],
        tool_context: tool_context.ToolContext,
        tool_response: Any,
        *other_args: Any,
        **kwargs: Any,
    ) -> None:
        try:
            # 回调调用的调试日志
            current_span = context_storage.top_span_data()

            output = (
                tool_response
                if isinstance(tool_response, dict)
                else {"output": tool_response}
            )

            # 用工具输出更新现有 span
            if current_span is not None:
                current_span.update(
                    output=output,
                    project_name=self.project_name,
                )
            else:
                LOGGER.warning(
                    f"No current span found in context for tool output update: {tool.name}"
                )
                _log_tool_context_warning(context=tool_context)
        except Exception as e:
            LOGGER.error(f"Failed during after_tool_callback(): {e}", exc_info=True)

    def __getstate__(self) -> Dict[str, Any]:
        state = self.__dict__.copy()
        state.pop("_opik_client", None)
        # TTFT 追踪是运行时状态，不进行序列化
        state.pop("_ttft_tracking", None)
        # 输出缓存包含 threading.Lock（不可 pickle）且为进程级运行时状态；
        # __setstate__ 会重新创建一个全新的实例。
        state.pop("_last_model_output", None)
        return state

    def __setstate__(self, state: Dict[str, Any]) -> None:
        self.__dict__.update(state)
        self._init_internal_attributes()


def _try_add_agent_graph_to_metadata(
    metadata: Dict[str, Any], callback_context: callback_context.CallbackContext
) -> None:
    current_agent: Optional[google.adk.agents.BaseAgent] = (
        callback_context_info_extractors.try_get_current_agent_instance(
            callback_context
        )
    )

    if current_agent is None:
        return

    try:
        metadata["_opik_graph_definition"] = {
            "format": "mermaid",
            "data": mermaid_graph_builder.build_mermaid_graph_definition(
                current_agent.root_agent
            ),
        }
    except Exception:
        LOGGER.error("Failed to build mermaid graph for agent.", exc_info=True)


def _log_tool_context_warning(context: tool_context.ToolContext) -> None:
    if context is not None:
        warning = f"Function call id: {context.function_call_id}, agent name: {context.agent_name}"
        if context.actions is not None:
            warning += f", is escalate: {context.actions.escalate}, transfer to: {context.actions.transfer_to_agent}"

        LOGGER.warning(warning)
