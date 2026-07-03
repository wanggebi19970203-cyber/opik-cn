import contextvars
import logging
from typing import Optional, Dict, Any, List, Union
import uuid

from llama_index.core.callbacks import schema as llama_index_schema
from llama_index.core.callbacks import base_handler

import opik
from opik import context_storage, tracing_runtime_config
from opik.decorator import arguments_helpers, span_creation_handler
from opik.api_objects import span, trace

from . import event_parsing_utils

LOGGER = logging.getLogger(__name__)

INDEX_CONSTRUCTION_TRACE_NAME = "index_construction"
LLAMA_INDEX_METADATA = {"created_from": "llama_index"}

# Context variable for root trace/span created by LlamaIndex
# LlamaIndex创建的根trace/span的上下文变量
_llama_root: contextvars.ContextVar[Optional[Union[span.SpanData, trace.TraceData]]] = (
    contextvars.ContextVar("_llama_root", default=None)
)


def _get_last_event(trace_map: Dict[str, List[str]]) -> str:
    def dfs(key: str) -> str:
        if key not in trace_map or not trace_map[key]:
            return key
        return dfs(trace_map[key][-1])

    start_key = next(iter(trace_map))
    return dfs(start_key)


class LlamaIndexCallbackHandler(base_handler.BaseCallbackHandler):
    def __init__(
        self,
        event_starts_to_ignore: Optional[List[llama_index_schema.CBEventType]] = None,
        event_ends_to_ignore: Optional[List[llama_index_schema.CBEventType]] = None,
        project_name: Optional[str] = None,
        skip_index_construction_trace: bool = False,
    ):
        """Initialize LlamaIndex callback handler for Opik tracing.
        初始化LlamaIndex回调处理器，用于Opik追踪。

        Args:
            event_starts_to_ignore: Event start types to be ignored during processing.
                处理过程中需要忽略的事件开始类型。
            event_ends_to_ignore: Event end types to be ignored during processing.
                处理过程中需要忽略的事件结束类型。
            project_name: Project name for trace/span context.
                trace/span上下文的项目名称。
            skip_index_construction_trace: Whether to skip index construction traces.
                是否跳过索引构建追踪。
        """
        event_starts_to_ignore = (
            event_starts_to_ignore if event_starts_to_ignore else []
        )
        event_ends_to_ignore = event_ends_to_ignore if event_ends_to_ignore else []
        super().__init__(
            event_starts_to_ignore=event_starts_to_ignore,
            event_ends_to_ignore=event_ends_to_ignore,
        )

        self._skip_index_construction_trace = skip_index_construction_trace
        self._project_name = project_name
        self._opik_context_storage = context_storage.get_current_context_instance()

        # Event tracking - shared across contexts, but events have unique IDs
        # 事件跟踪 - 跨上下文共享，但事件具有唯一ID
        self._map_event_id_to_span_data: Dict[str, span.SpanData] = {}
        self._map_event_id_to_output: Dict[str, Any] = {}

        # For streaming: end_trace may be called before event_end, so we need to
        # defer the trace output update until the event output is available
        # 对于流式处理：end_trace可能在event_end之前被调用，因此需要
        # 延迟trace输出更新，直到事件输出可用
        self._pending_root_output_updates: Dict[
            str, Union[span.SpanData, trace.TraceData]
        ] = {}

    @property
    def _opik_client(self) -> opik.Opik:
        return opik.get_global_client()

    def _send_root_to_backend(
        self, root: Union[span.SpanData, trace.TraceData]
    ) -> None:
        """Send root trace or span data to the backend.
        将根trace或span数据发送到后端。"""
        if isinstance(root, span.SpanData):
            self._opik_client.__internal_api__span__(**root.as_parameters)
        elif isinstance(root, trace.TraceData):
            self._opik_client.__internal_api__trace__(**root.as_parameters)

    def start_trace(self, trace_id: Optional[str] = None) -> None:
        if (
            self._skip_index_construction_trace
            and trace_id == INDEX_CONSTRUCTION_TRACE_NAME
        ):
            return

        trace_name = trace_id if trace_id else "llama_index_operation"

        span_creation_result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=arguments_helpers.StartSpanParameters(
                name=trace_name,
                type="general",
                project_name=context_storage.resolve_project_name(
                    self._project_name, "LlamaIndexCallbackHandler"
                ),
                metadata=LLAMA_INDEX_METADATA,
            ),
            distributed_trace_headers=None,
            opik_context_storage=self._opik_context_storage,
        )

        if span_creation_result.trace_data is not None:
            self._opik_context_storage.set_trace_data(span_creation_result.trace_data)
            self._opik_client.__internal_api__trace__(
                **span_creation_result.trace_data.as_start_parameters
            )
            _llama_root.set(span_creation_result.trace_data)
        else:
            self._opik_context_storage.add_span_data(span_creation_result.span_data)
            self._opik_client.__internal_api__span__(
                **span_creation_result.span_data.as_start_parameters
            )
            _llama_root.set(span_creation_result.span_data)

    def end_trace(
        self,
        trace_id: Optional[str] = None,
        trace_map: Optional[Dict[str, List[str]]] = None,
    ) -> None:
        if not trace_map:
            return

        root = _llama_root.get()
        if root is None:
            return

        last_event = _get_last_event(trace_map)

        # Check if the output for the last event is already available.
        # For streaming calls, LlamaIndex calls end_trace() BEFORE event_end(),
        # so the output won't be stored yet.
        # 检查最后一个事件的输出是否已经可用。
        # 对于流式调用，LlamaIndex会在event_end()之前调用end_trace()，
        # 因此输出可能尚未存储。
        if last_event in self._map_event_id_to_output:
            last_event_output = self._map_event_id_to_output.get(last_event)
            root.init_end_time().update(output=last_event_output)

            # Send the trace/span with output
            # 发送带有输出的trace/span
            self._send_root_to_backend(root)
        else:
            # Output not available yet (streaming scenario).
            # Store the root so we can update it when event_end is called.
            # Don't send the trace/span yet - it will be sent in on_event_end
            # with the output and correct end_time to avoid race conditions.
            # Note: We don't set end_time here because the actual end is when
            # the last event ends, not when LlamaIndex calls end_trace().
            # 输出尚未可用（流式场景）。
            # 存储根节点，以便在event_end被调用时更新它。
            # 暂不发送trace/span - 它将在on_event_end中发送，
            # 带有输出和正确的结束时间，以避免竞态条件。
            # 注意：这里不设置end_time，因为实际结束时间是最后一个
            # 事件结束时，而不是LlamaIndex调用end_trace()时。
            self._pending_root_output_updates[last_event] = root

        # Clean up context storage
        # 清理上下文存储
        if isinstance(root, span.SpanData):
            self._opik_context_storage.pop_span_data(ensure_id=root.id)
        elif isinstance(root, trace.TraceData):
            self._opik_context_storage.pop_trace_data(ensure_id=root.id)

        # Clean up
        # 清理
        _llama_root.set(None)

    def on_event_start(
        self,
        event_type: llama_index_schema.CBEventType,
        payload: Optional[Dict[str, Any]] = None,
        event_id: Optional[str] = None,
        parent_id: Optional[str] = None,
        **kwargs: Any,
    ) -> str:
        if not event_id:
            event_id = str(uuid.uuid4())

        root_span_or_trace = _llama_root.get()

        if root_span_or_trace is None:
            if not self._skip_index_construction_trace:
                LOGGER.warning(
                    "No active LlamaIndex trace/span found in context. "
                    "parent_id=%s, event_type=%s, event_id=%s",
                    parent_id,
                    event_type,
                    event_id,
                )
            return event_id

        span_input = event_parsing_utils.get_span_input_from_events(event_type, payload)

        # Skip creating span if event duplicates root operation name
        # 如果事件重复了根操作名称，则跳过创建span
        root_name = root_span_or_trace.name if root_span_or_trace else None
        event_duplicates_root = (
            parent_id == llama_index_schema.BASE_TRACE_EVENT
            and event_type.value == root_name
        )
        if event_duplicates_root:
            if span_input:
                root_span_or_trace.update(input=span_input)
            return event_id

        span_creation_result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=arguments_helpers.StartSpanParameters(
                name=event_type.value,
                input=span_input,
                type=(
                    "llm"
                    if event_type == llama_index_schema.CBEventType.LLM
                    else "general"
                ),
                project_name=context_storage.resolve_project_name(
                    self._project_name, "LlamaIndexCallbackHandler"
                ),
                metadata=LLAMA_INDEX_METADATA,
            ),
            distributed_trace_headers=None,
            opik_context_storage=self._opik_context_storage,
        )
        span_data = span_creation_result.span_data
        self._map_event_id_to_span_data[event_id] = span_data
        self._opik_context_storage.add_span_data(span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.__internal_api__span__(**span_data.as_start_parameters)

        # Update root input from first child event
        # 从第一个子事件更新根输入
        if parent_id == llama_index_schema.BASE_TRACE_EVENT and span_input is not None:
            root_span_or_trace.update(input=span_input)

        return event_id

    def on_event_end(
        self,
        event_type: llama_index_schema.CBEventType,
        payload: Optional[Dict[str, Any]] = None,
        event_id: Optional[str] = None,
        **kwargs: Any,
    ) -> None:
        span_output = event_parsing_utils.get_span_output_from_event(
            event_type, payload
        )
        error_info = event_parsing_utils.get_span_error_info(payload)

        if not event_id:
            return

        # Store output for end_trace
        # 存储输出用于end_trace
        self._map_event_id_to_output[event_id] = span_output

        # Check if there's a pending root trace/span output update for this event.
        # This happens when end_trace() was called before event_end() (streaming scenario).
        # 检查是否有待处理的根trace/span输出更新。
        # 这发生在end_trace()在event_end()之前被调用的情况（流式场景）。
        if event_id in self._pending_root_output_updates:
            root = self._pending_root_output_updates.pop(event_id)
            # Set end_time now (the actual end) and update with output
            # 设置end_time（实际结束时间）并更新输出
            root.init_end_time().update(output=span_output)

            # Send the trace/span to the backend with correct end_time and output
            # 将trace/span发送到后端，带有正确的end_time和输出
            self._send_root_to_backend(root)

        # Finalize span if it exists
        # 如果span存在则完成它
        if event_id in self._map_event_id_to_span_data:
            span_data = self._map_event_id_to_span_data[event_id]

            llm_usage_info = event_parsing_utils.get_usage_data(payload)
            span_data.update(**llm_usage_info.__dict__)
            span_data.update(output=span_output, error_info=error_info).init_end_time()

            if tracing_runtime_config.is_tracing_active():
                self._opik_client.__internal_api__span__(**span_data.as_parameters)

            self._opik_context_storage.pop_span_data(ensure_id=span_data.id)
            del self._map_event_id_to_span_data[event_id]

    def flush(self) -> None:
        """Flush pending Opik data to backend.
        将待处理的Opik数据刷新到后端。"""
        self._opik_client.flush()
