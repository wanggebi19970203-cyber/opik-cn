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
    pending_llm_spans,
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
        # 在途 LLM span 以 id(callback_context.actions) 为键，使
        # after_model_callback 能够恢复在 before_model_callback 中创建的 span，
        # 即使 ContextCacheConfig 在 SSE 流式传输下分离了
        # contextvar span 栈（comet-ml/opik#5524）。
        self._pending_llm_spans = pending_llm_spans.PendingLlmSpanRegistry()
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
                f"检查 LlmResponse.content.parts 以计算 TTFT 时出错：{e}",
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
                    f"上下文中未找到代理 {callback_context.agent_name} 的当前 span 或 trace"
                )

        except Exception as e:
            LOGGER.error(f"before_agent_callback() 执行失败：{e}", exc_info=True)

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
                    "上下文中未找到用于更新代理输出的当前 span 或 trace"
                )
        except Exception as e:
            LOGGER.error(f"after_agent_callback() 执行失败：{e}", exc_info=True)

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
            # 同时将 span 注册到一个与 contextvar 无关的、按模型调用区分的键下，
            # 以便在 ContextCacheConfig 分离上下文栈时 after_model_callback 能够恢复它
            # （comet-ml/opik#5524）。该键在整个受支持的 ADK 版本范围内解析共享的
            # EventActions（>= 1.29 上为公开的 ``.actions``，之前为私有的 ``_event_actions``）。
            actions = _resolve_event_actions(callback_context)
            if actions is not None:
                self._pending_llm_spans.register(actions, result.span_data)

            # 记录请求开始时间，用于计算首 token 时间
            request_start_time = time.time()
            self._ttft_tracking[result.span_data.id] = (request_start_time, None)
        except Exception as e:
            LOGGER.error(f"before_model_callback() 执行失败：{e}", exc_info=True)

    def _force_close_llm_span(self, span_data: span.SpanData, reason: str) -> None:
        """关闭一个到达终止状态但无可记录内容的已恢复 LLM span——
        即终止的空 SSE 响应（参见 after_model_callback）。
        没有此处理，span 将停留在 ``started`` 状态而没有结束时间
        （comet-ml/opik#5524）。

        尽力而为且幂等：它会跳过已经最终化的 span（正常的
        ``after_model_callback`` 赢得了竞态），并且从不引发异常，因为它
        从提前返回路径运行。当 span 位于栈顶时，将其从上下文栈弹出，
        使已关闭的 span 不会残留并错误地成为后续 span 的父级。
        ``reason`` 记录在元数据中，使不完整的 span（无 output/usage）
        在检查 trace 时能与正常最终化的 span 区分开。
        """
        try:
            if span_data.end_time is not None:
                return
            if span_data.metadata is None:
                span_data.metadata = {}
            span_data.metadata[llm_span_helpers.SPAN_STATUS] = (
                llm_span_helpers.LLMSpanStatus.READY_FOR_FINALIZATION.value
            )
            # 前导下划线的内部元数据约定（参见 _OPIK_SPAN_STATUS）。
            span_data.metadata["_opik_llm_span_force_closed_reason"] = reason
            stack_top = context_storage.top_span_data()
            if stack_top is not None and stack_top.id == span_data.id:
                context_storage.pop_span_data(ensure_id=span_data.id)
            span_data.init_end_time()
            # 同时丢弃对应的 TTFT 条目，以免泄漏。
            self._ttft_tracking.pop(span_data.id, None)
            if opik.is_tracing_active():
                self._opik_client.__internal_api__span__(**span_data.as_parameters)
        except Exception:
            LOGGER.debug(
                "强制关闭 LLM span 失败（原因=%s）", reason, exc_info=True
            )

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
            LOGGER.debug("检查部分分片时出错", exc_info=True)
            is_partial = False

        span_id: Optional[str] = None
        actions = _resolve_event_actions(callback_context)
        exception_occurred = False
        try:
            model = None
            usage = None
            output = None

            # 预先解析在 before_model_callback 中创建的 LLM span，使
            # 即使在下方空内容提前返回的情况下，``finally`` 也能清理其 TTFT 和
            # 待处理注册表条目。优先使用按模型调用区分的注册表条目
            # （以 id(callback_context.actions) 为键），该条目在 ContextCacheConfig + SSE
            # 流式传输下的上下文分离中得以保留（comet-ml/opik#5524）。
            # 当栈顶是我们尚未最终化的 LLM span 时回退到上下文栈顶——
            # 在没有条目被注册（没有 ``actions`` 的回调上下文）或在极端并发下
            # 条目被逐出的情况下保持正常路径工作。由分离的上下文留在栈顶的
            # 父 span 不属于我们，因此被忽略。
            stack_top = context_storage.top_span_data()
            current_span = (
                self._pending_llm_spans.get(actions) if actions is not None else None
            )
            if (
                current_span is None
                and stack_top is not None
                and llm_span_helpers.is_externally_created_llm_span_that_just_started(
                    stack_top
                )
            ):
                current_span = stack_top
            if current_span is not None:
                # 提前记录，使 finally 能够在任何退出路径上清理 TTFT。
                span_id = current_span.id

            if adk_helpers.has_empty_text_part_content(llm_response):
                # 空内容。部分分片之后可能还有更多内容（ADK 会用最终响应
                # 再次调用），因此保留 span、其 TTFT 条目及其注册表条目并等待。
                # 但终止的（非部分）空响应是本次调用的最后一次回调：
                # 没有可记录的内容，而 finally 会丢弃注册表条目，并且——
                # 在分离的 ContextCacheConfig 上下文下——span 也不在栈上，
                # 因此直接返回会使恢复的 span 滞留在 ``started`` 状态。
                # 改为强制关闭它（comet-ml/opik#5524）；_force_close_llm_span
                # 在它位于栈顶时也会将其弹出。
                if not is_partial and current_span is not None:
                    self._force_close_llm_span(
                        current_span, reason="empty_terminal_response"
                    )
                return

            if current_span is None:
                # 本次调用未注册 LLM span：before_model_callback 未运行，
                # 或条目已被消费。分离上下文的情况（#5524）已通过上方的
                # _pending_llm_spans 处理，因此这里我们仅将模型 OUTPUT
                # 恢复到按 invocation 区分、有界的 _last_model_output 缓存中（#7266），
                # 使 after_agent_callback 仍能标记 trace 输出。预先丢弃，
                # 使失败的转换不会留下过期值；部分分片永不缓存。
                self._last_model_output.discard(callback_context.invocation_id)
                if not is_partial:
                    try:
                        self._last_model_output.set(
                            callback_context.invocation_id,
                            adk_helpers.convert_adk_base_model_to_dict(llm_response),
                        )
                    except Exception:
                        LOGGER.debug(
                            "在没有当前 span 的情况下恢复模型输出失败",
                            exc_info=True,
                        )
                LOGGER.debug(
                    "上下文中没有当前 span（分离的异步上下文，例如 "
                    "ContextCacheConfig）；通过缓存恢复了模型输出"
                )
                return

            # 仅当上下文栈实际持有我们的 span 时，才在最终化时弹出栈；
            # 当上下文被分离时，span 不在栈上，栈顶（如果有）是
            # 我们不能触碰的父 span。
            span_on_stack = stack_top is not None and stack_top.id == current_span.id

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
                    f"将 LlmResponse 转换为字典或提取使用数据时出错，原因：{e}",
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

            if span_on_stack:
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
            LOGGER.error(f"after_model_callback() 执行失败：{e}", exc_info=True)
        finally:
            # 在任何最终响应或错误退出时清理 TTFT 条目（部分分片保留它，
            # 因为 ADK 会用最终响应再次调用）。在主路径上它已在上方弹出，
            # 因此这里是空操作；在空内容提前返回时，清理在此处发生。
            if span_id is not None and (exception_occurred or not is_partial):
                self._ttft_tracking.pop(span_id, None)
            # 一旦本次调用完成（任何最终响应退出，成功或错误），
            # 就从注册表中丢弃恢复的 span，使上方失败的最终化
            # 不会留下过期条目，被后续 id() 复用所映射到。
            # 部分分片会保留它以供最终响应使用。
            if actions is not None and not is_partial:
                self._pending_llm_spans.pop(actions)

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
                    f"上下文中未找到工具 {tool.name} 的当前 span"
                )
                _log_tool_context_warning(context=tool_context)

        except Exception as e:
            LOGGER.error(f"before_tool_callback() 执行失败：{e}", exc_info=True)

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
                    f"上下文中未找到用于更新工具输出的当前 span：{tool.name}"
                )
                _log_tool_context_warning(context=tool_context)
        except Exception as e:
            LOGGER.error(f"after_tool_callback() 执行失败：{e}", exc_info=True)

    def __getstate__(self) -> Dict[str, Any]:
        state = self.__dict__.copy()
        state.pop("_opik_client", None)
        # TTFT 追踪是运行时状态，不进行序列化
        state.pop("_ttft_tracking", None)
        # 输出缓存和待处理 span 注册表持有 threading.Lock
        # （不可 pickle），且为进程级运行时状态；__setstate__ 会重新创建新的实例。
        state.pop("_last_model_output", None)
        state.pop("_pending_llm_spans", None)
        return state

    def __setstate__(self, state: Dict[str, Any]) -> None:
        self.__dict__.update(state)
        self._init_internal_attributes()


def _resolve_event_actions(
    callback_context: callback_context.CallbackContext,
) -> Optional[Any]:
    """返回 ADK 传递给 before/after_model_callback 的按模型调用区分的
    ``EventActions`` 对象——即待处理 span 注册表用于 comet-ml/opik#5524
    恢复的无 contextvar 键。

    ADK >= 1.29 将其暴露为公开的 ``.actions`` 属性；更早的受支持版本——
    其中 ContextCacheConfig + SSE 仍可能使 span 滞留——仅将其私有地存储为
    ``_event_actions``。优先使用公开属性，并回退到私有属性，使该键在整个
    版本范围内都能被填充；两者解析为同一个对象，因此 before/after_model_callback
    在该键上保持一致。对于两者都不暴露的回调上下文返回 ``None``。
    """
    actions = getattr(callback_context, "actions", None)
    if actions is None:
        actions = getattr(callback_context, "_event_actions", None)
    return actions


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
        LOGGER.error("为代理构建 mermaid 图失败。", exc_info=True)


def _log_tool_context_warning(context: tool_context.ToolContext) -> None:
    if context is not None:
        warning = f"函数调用 id：{context.function_call_id}，代理名称：{context.agent_name}"
        if context.actions is not None:
            warning += f"，是否升级：{context.actions.escalate}，转移至：{context.actions.transfer_to_agent}"

        LOGGER.warning(warning)
