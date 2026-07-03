import contextlib
import logging
import os
from typing import Any, Dict, Iterator, List, Optional, Union

from haystack import tracing

import opik
from opik import context_storage, tracing_runtime_config, url_helpers
from opik.decorator import arguments_helpers, span_creation_handler
from opik.api_objects import span as opik_span
from opik.api_objects import trace as opik_trace
from opik.types import SpanType

from . import opik_span_bridge
from . import constants

LOGGER = logging.getLogger(__name__)


class OpikTracer(tracing.Tracer):
    """Haystack 追踪器与 Opik 之间的桥接器，用于追踪管道操作。"""

    def __init__(
        self,
        opik_client: Optional["opik.Opik"] = None,
        name: str = "Haystack",
        project_name: Optional[str] = None,
    ) -> None:
        """
        初始化 OpikTracer。

        Args:
            opik_client: 已弃用。客户端现在通过 ``opik.get_global_client()`` 延迟解析。
                此参数为向后兼容而保留，但会被忽略。
            name: 管道或组件的名称。
            project_name: 用于追踪的项目名称（可选）。
        """
        if not tracing.tracer.is_content_tracing_enabled:
            LOGGER.warning(
                "Traces will not be logged to Opik because Haystack tracing is disabled. "
                "To enable, set the HAYSTACK_CONTENT_TRACING_ENABLED environment variable to true "
                "before importing Haystack."
            )
        self._context: List[opik_span_bridge.OpikSpanBridge] = []
        self._name = name
        self._project_name = project_name
        self.enforce_flush = (
            os.getenv(constants.HAYSTACK_OPIK_ENFORCE_FLUSH_ENV_VAR, "false").lower()
            == "true"
        )

    @property
    def _opik_client(self) -> opik.Opik:
        """获取 Opik 全局客户端实例。"""
        return opik.get_global_client()

    @contextlib.contextmanager
    def trace(
        self,
        operation_name: str,
        tags: Optional[Dict[str, Any]] = None,
        parent_span: Optional[opik_span_bridge.OpikSpanBridge] = None,
    ) -> Iterator[tracing.Span]:
        """
        创建追踪上下文管理器。

        Args:
            operation_name: 操作名称。
            tags: 可选的标签字典。
            parent_span: 可选的父 Span。

        Yields:
            OpikSpanBridge 实例。
        """
        tags = tags or {}
        span_name = tags.get(constants.COMPONENT_NAME_KEY, operation_name)

        if parent_span:
            span = self._create_child_span(parent_span, span_name, operation_name, tags)
        else:
            span = self._create_span_or_trace(operation_name, span_name)

        self._context.append(span)

        if tags:
            span.set_tags(tags)

        try:
            yield span
        finally:
            self._finalize_span(span)

    def _create_span_or_trace(
        self, operation_name: str, span_name: str
    ) -> opik_span_bridge.OpikSpanBridge:
        """
        根据现有上下文创建 Span 或 Trace。

        使用 span_creation_handler 处理上下文，对于管道操作使用管道名称，
        否则使用组件名称。
        """
        # 对于管道操作使用管道名称，否则使用组件名称
        final_name = self._name if "pipeline.run" in operation_name else span_name
        metadata = {"created_from": "haystack", "operation": operation_name}

        # 始终使用 span_creation_handler - 它能正确处理现有上下文
        start_span_parameters = arguments_helpers.StartSpanParameters(
            name=final_name,
            type="general",
            metadata=metadata,
            project_name=context_storage.resolve_project_name(
                self._project_name, "OpikTracer"
            ),
        )

        result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=start_span_parameters,
            distributed_trace_headers=None,
        )
        final_span_or_trace_data: Union[opik_span.SpanData, opik_trace.TraceData] = (
            result.trace_data if result.trace_data is not None else result.span_data
        )

        return opik_span_bridge.OpikSpanBridge(final_span_or_trace_data)

    def _create_child_span(
        self,
        parent_span: opik_span_bridge.OpikSpanBridge,
        span_name: str,
        operation_name: str,
        tags: Dict[str, Any],
    ) -> opik_span_bridge.OpikSpanBridge:
        """
        从父 Span 创建子 Span。

        Args:
            parent_span: 父 Span 桥接器。
            span_name: Span 名称。
            operation_name: 操作名称。
            tags: 标签字典。

        Returns:
            新创建的子 Span 桥接器。
        """
        parent_data = parent_span.get_opik_span_or_trace_data()
        span_type: SpanType = (
            "llm"
            if tags.get(constants.COMPONENT_TYPE_KEY)
            in constants.ALL_SUPPORTED_GENERATORS
            else "general"
        )

        span_data = parent_data.create_child_span_data(
            name=span_name,
            type=span_type,
            metadata={"created_from": "haystack", "operation": operation_name},
        )
        return opik_span_bridge.OpikSpanBridge(span_data)

    def _finalize_span(self, span: opik_span_bridge.OpikSpanBridge) -> None:
        """
        完成 Span：更新元数据并结束 Span。

        将数据发送到后端（如果追踪处于活动状态），并从上下文中移除 Span。
        """
        try:
            span_or_trace_data = span.get_opik_span_or_trace_data()
            span.apply_component_metadata()
            span_or_trace_data.init_end_time()

            # 如果追踪处于活动状态，将数据发送到后端
            if tracing_runtime_config.is_tracing_active():
                if isinstance(span_or_trace_data, opik_trace.TraceData):
                    self._opik_client.__internal_api__trace__(
                        **span_or_trace_data.as_parameters
                    )
                else:
                    self._opik_client.__internal_api__span__(
                        **span_or_trace_data.as_parameters
                    )

            self._context.pop()
            if self.enforce_flush:
                self.flush()
        except Exception as e:
            LOGGER.error("Failed to finalize span: %s", e, exc_info=True)
            if self._context:
                self._context.pop()

    def flush(self) -> None:
        """刷新 Opik 客户端以发送待处理的数据。"""
        self._opik_client.flush()

    def current_span(self) -> Optional[opik_span_bridge.OpikSpanBridge]:
        """返回当前活动的 Span。"""
        return self._context[-1] if self._context else None

    def get_project_url(self) -> Optional[str]:
        """返回追踪数据的项目 URL。"""
        span = self.current_span()
        if not span:
            return None

        span_data = span.get_opik_span_or_trace_data()
        trace_id = (
            span_data.id
            if isinstance(span_data, opik_trace.TraceData)
            else span_data.trace_id
        )
        return url_helpers.get_project_url_by_trace_id(
            trace_id=trace_id, url_override=self._opik_client.config.url_override
        )

    def get_trace_id(self) -> Optional[str]:
        """返回当前追踪的 Trace ID。"""
        span = self.current_span()
        if not span:
            return None

        span_data = span.get_opik_span_or_trace_data()
        return (
            span_data.id
            if isinstance(span_data, opik_trace.TraceData)
            else span_data.trace_id
        )
