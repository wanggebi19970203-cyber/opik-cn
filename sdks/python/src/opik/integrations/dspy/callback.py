from typing import Any, Dict, Optional, Tuple, Union
import logging

import dspy
from dspy.utils import callback as dspy_callback

import opik
from opik import context_storage, opik_context, tracing_runtime_config
from opik import llm_usage
from opik.api_objects import helpers, span, trace
from opik.decorator import error_info_collector

from .graph import build_mermaid_graph_from_module
from .parsers import LMHistoryInfo, extract_lm_info_from_history, get_span_type

LOGGER = logging.getLogger(__name__)

SpanOrTraceData = Union[span.SpanData, trace.TraceData]


class OpikCallback(dspy_callback.BaseCallback):
    """
    DSPy Opik 日志记录的回调类。

    用于将 DSPy 模块的执行过程记录到 Opik 平台，支持追踪模块调用、
    LM 请求和工具调用的完整执行链路。

    Args:
        project_name: 用于记录数据的 Opik 项目名称。
        log_graph: 如果为 True，将为每个模块记录 Mermaid 图表。
    """

    def __init__(
        self,
        project_name: Optional[str] = None,
        log_graph: bool = False,
    ):
        self._map_call_id_to_span_data: Dict[str, span.SpanData] = {}
        self._map_call_id_to_trace_data: Dict[str, trace.TraceData] = {}
        # 存储 (lm_instance, expected_messages) 用于提取使用量并验证正确的历史记录条目
        self._map_call_id_to_lm_info: Dict[str, Tuple[Any, Optional[Any]]] = {}

        self._origins_metadata: Dict[str, Any] = {"created_from": "dspy"}

        self._context_storage = context_storage.get_current_context_instance()

        self._project_name = project_name
        self.log_graph = log_graph

    @property
    def _opik_client(self) -> opik.Opik:
        return opik.get_global_client()

    def _skip_tracking(self) -> bool:
        """检查是否应跳过追踪。"""
        return not tracing_runtime_config.is_tracing_active()

    def on_module_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        if self._skip_tracking():
            return

        # 首先检查回调的上下文
        if (current_span_data := self._context_storage.top_span_data()) is not None:
            self._attach_span_to_existing_span(
                call_id=call_id,
                current_span_data=current_span_data,
                instance=instance,
                inputs=inputs,
            )
        elif (current_trace_data := self._context_storage.get_trace_data()) is not None:
            self._attach_span_to_existing_trace(
                call_id=call_id,
                current_trace_data=current_trace_data,
                instance=instance,
                inputs=inputs,
            )
        # 回调上下文为空，检查 Opik 的上下文
        elif (current_span_data := opik_context.get_current_span_data()) is not None:
            self._attach_span_to_existing_span(
                call_id=call_id,
                current_span_data=current_span_data,
                instance=instance,
                inputs=inputs,
            )
        elif (current_trace_data := opik_context.get_current_trace_data()) is not None:
            self._attach_span_to_existing_trace(
                call_id=call_id,
                current_trace_data=current_trace_data,
                instance=instance,
                inputs=inputs,
            )
        else:
            # 回调上下文和 Opik 上下文均为空，启动新的追踪
            self._start_trace(
                call_id=call_id,
                instance=instance,
                inputs=inputs,
            )

    def _attach_span_to_existing_span(
        self,
        call_id: str,
        current_span_data: span.SpanData,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        """将新的 span 附加到现有的 span 下。"""
        project_name = helpers.resolve_child_span_project_name(
            parent_project_name=current_span_data.project_name,
            child_project_name=context_storage.resolve_project_name(
                self._project_name, "OpikCallback"
            ),
        )
        span_type = get_span_type(instance)

        span_data = span.SpanData(
            trace_id=current_span_data.trace_id,
            parent_span_id=current_span_data.id,
            name=instance.__class__.__name__,
            input=inputs,
            type=span_type,
            project_name=project_name,
            metadata=self._get_opik_metadata(instance),
        )
        self._start_span(call_id=call_id, span_data=span_data)

    def _attach_span_to_existing_trace(
        self,
        call_id: str,
        current_trace_data: trace.TraceData,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        """将新的 span 附加到现有的 trace 下。"""
        project_name = helpers.resolve_child_span_project_name(
            current_trace_data.project_name,
            context_storage.resolve_project_name(self._project_name, "OpikCallback"),
        )
        span_type = get_span_type(instance)

        span_data = span.SpanData(
            trace_id=current_trace_data.id,
            parent_span_id=None,
            name=instance.__class__.__name__,
            input=inputs,
            type=span_type,
            project_name=project_name,
            metadata=self._get_opik_metadata(instance),
        )
        self._start_span(call_id=call_id, span_data=span_data)

    def _start_span(self, call_id: str, span_data: span.SpanData) -> None:
        """启动一个新的 span。"""
        self._map_call_id_to_span_data[call_id] = span_data
        self._set_current_context_data(span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.__internal_api__span__(**span_data.as_start_parameters)

    def _start_trace(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        """启动一个新的 trace。"""
        trace_data = trace.TraceData(
            name=instance.__class__.__name__,
            input=inputs,
            metadata=self._get_opik_metadata(instance),
            project_name=context_storage.resolve_project_name(
                self._project_name, "OpikCallback"
            ),
        )
        self._map_call_id_to_trace_data[call_id] = trace_data
        self._set_current_context_data(trace_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.__internal_api__trace__(**trace_data.as_start_parameters)

    def on_module_end(
        self,
        call_id: str,
        outputs: Optional[Any],
        exception: Optional[Exception] = None,
    ) -> None:
        """模块执行结束时的回调。"""
        self._end_span(
            call_id=call_id,
            exception=exception,
            outputs=outputs,
        )
        self._end_trace(call_id=call_id)

    def _end_trace(self, call_id: str) -> None:
        """结束追踪。"""
        if trace_data := self._map_call_id_to_trace_data.pop(call_id, None):
            if tracing_runtime_config.is_tracing_active():
                trace_data.init_end_time()
                self._opik_client.__internal_api__trace__(**trace_data.as_parameters)

            self._context_storage.pop_trace_data(ensure_id=trace_data.id)

    def _end_span(
        self,
        call_id: str,
        outputs: Optional[Any],
        exception: Optional[Exception] = None,
        usage: Optional[llm_usage.OpikUsage] = None,
        extra_metadata: Optional[Dict[str, Any]] = None,
        actual_provider: Optional[str] = None,
        actual_model: Optional[str] = None,
        total_cost: Optional[float] = None,
    ) -> None:
        """结束 span 并记录输出、异常和使用量信息。"""
        if span_data := self._map_call_id_to_span_data.pop(call_id, None):
            if exception:
                error_info = error_info_collector.collect(exception)
                span_data.update(error_info=error_info)

            # 准备更新字典
            update_kwargs: Dict[str, Any] = {
                "output": {"output": outputs},
                "usage": usage,
                "total_cost": total_cost,
            }

            # 处理返回实际服务提供商/模型的 LLM 路由器（如 OpenRouter）
            if extra_metadata is None:
                extra_metadata = {}

            # 当实际提供商不同时更新提供商（例如 OpenRouter -> Hyperbolic）
            if (
                actual_provider is not None
                and span_data.provider is not None
                and span_data.provider.lower() != actual_provider.lower()
            ):
                # 将原始提供商（如 "openrouter"）存储在元数据中
                extra_metadata["llm_router"] = span_data.provider
                # 更新为实际提供商以实现准确的成本追踪
                update_kwargs["provider"] = actual_provider.lower()

            if (
                actual_model is not None
                and span_data.model is not None
                and span_data.model != actual_model
            ):
                # 将原始模型（如 "@preset/qwen"）存储在元数据中
                extra_metadata["original_model"] = span_data.model
                # 更新为实际模型以实现准确的成本追踪
                update_kwargs["model"] = actual_model

            # 仅在有内容需要添加时设置元数据
            if extra_metadata:
                update_kwargs["metadata"] = extra_metadata

            span_data.update(**update_kwargs).init_end_time()
            if tracing_runtime_config.is_tracing_active():
                self._opik_client.__internal_api__span__(**span_data.as_parameters)

            # 从上下文中移除 span 数据
            self._context_storage.pop_span_data(ensure_id=span_data.id)

    def _collect_common_span_data(
        self, instance: Any, inputs: Dict[str, Any]
    ) -> span.SpanData:
        """收集通用的 span 数据。"""
        current_callback_context_data = self._get_current_context_data()
        assert current_callback_context_data is not None

        project_name = helpers.resolve_child_span_project_name(
            current_callback_context_data.project_name,
            context_storage.resolve_project_name(self._project_name, "OpikCallback"),
        )

        if isinstance(current_callback_context_data, span.SpanData):
            trace_id = current_callback_context_data.trace_id
            parent_span_id = current_callback_context_data.id
        else:
            trace_id = current_callback_context_data.id
            parent_span_id = None

        span_type = get_span_type(instance)

        return span.SpanData(
            trace_id=trace_id,
            parent_span_id=parent_span_id,
            name=(
                instance.name
                if hasattr(instance, "name")
                else instance.__class__.__name__
            ),
            input=inputs,
            type=span_type,
            project_name=project_name,
            metadata=self._get_opik_metadata(instance),
        )

    def on_lm_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        """语言模型调用开始时的回调。"""
        span_data = self._collect_common_span_data(instance, inputs)

        provider, model = instance.model.split(r"/", 1)

        span_data.update(
            provider=provider,
            model=model,
            name=f"{span_data.name}: {provider} - {model}",
        )
        self._map_call_id_to_span_data[call_id] = span_data

        # 存储 LM 实例和预期消息用于提取使用量
        self._map_call_id_to_lm_info[call_id] = (
            instance,
            inputs.get("messages"),
        )

        self._set_current_context_data(span_data)

    def on_lm_end(
        self,
        call_id: str,
        outputs: Optional[Dict[str, Any]],
        exception: Optional[Exception] = None,
    ) -> None:
        """语言模型调用结束时的回调。"""
        lm_info = self._extract_lm_info_from_history(call_id)

        # 仅在有确定值时将 cache_hit 添加到 span 元数据
        extra_metadata = (
            {"cache_hit": lm_info.cache_hit} if lm_info.cache_hit is not None else None
        )

        self._end_span(
            call_id=call_id,
            exception=exception,
            outputs=outputs,
            usage=lm_info.usage,
            extra_metadata=extra_metadata,
            actual_provider=lm_info.actual_provider,
            actual_model=lm_info.actual_model,
            total_cost=lm_info.total_cost,
        )

    def on_tool_start(
        self,
        call_id: str,
        instance: Any,
        inputs: Dict[str, Any],
    ) -> None:
        """工具调用开始时的回调。"""
        span_data = self._collect_common_span_data(instance, inputs)
        self._map_call_id_to_span_data[call_id] = span_data
        self._set_current_context_data(span_data)

    def on_tool_end(
        self,
        call_id: str,
        outputs: Optional[Dict[str, Any]],
        exception: Optional[Exception] = None,
    ) -> None:
        """工具调用结束时的回调。"""
        self._end_span(
            call_id=call_id,
            exception=exception,
            outputs=outputs,
        )

    def flush(self) -> None:
        """将待处理的 Opik 数据发送到后端。"""
        self._opik_client.flush()

    def _set_current_context_data(self, value: SpanOrTraceData) -> None:
        """设置当前上下文数据。"""
        if isinstance(value, span.SpanData):
            self._context_storage.add_span_data(value)
        elif isinstance(value, trace.TraceData):
            self._context_storage.set_trace_data(value)
        else:
            raise ValueError(f"Invalid context type: {type(value)}")

    def _get_current_context_data(self) -> Optional[SpanOrTraceData]:
        """获取当前上下文数据。"""
        if span_data := self._context_storage.top_span_data():
            return span_data
        return self._context_storage.get_trace_data()

    def _extract_lm_info_from_history(self, call_id: str) -> LMHistoryInfo:
        """
        从 LM 的历史记录中提取 token 使用量、缓存状态、实际提供商和成本。

        DSPy 在每次调用后将使用信息存储在 LM 的历史记录中。
        我们验证历史记录条目与预期消息是否匹配，以处理并发 LM 调用
        可能产生的竞态条件。

        对于 OpenRouter 等路由器，响应包含实际提供服务的提供商
        （如 "Novita"、"Together"），这与模型字符串中使用的路由器名称
        （如 "openrouter"）不同。

        成本字段由 OpenRouter 等提供商提供，包含所有 token 类型
        （推理、缓存、多模态）的准确定价。

        Returns:
            LMHistoryInfo: 包含 usage、cache_hit、actual_provider 和 total_cost。
        """
        lm_info = self._map_call_id_to_lm_info.pop(call_id, None)
        if lm_info is None:
            return LMHistoryInfo(
                usage=None,
                cache_hit=None,
                actual_provider=None,
                actual_model=None,
                total_cost=None,
            )

        lm_instance, expected_messages = lm_info
        return extract_lm_info_from_history(lm_instance, expected_messages)

    def _get_opik_metadata(self, instance: Any) -> Dict[str, Any]:
        """获取 Opik 元数据，可选包含模块的 Mermaid 图表。"""
        graph = None
        if self.log_graph and isinstance(instance, dspy.Module):
            try:
                graph = build_mermaid_graph_from_module(instance)
            except Exception:
                LOGGER.warning("无法从 DSPy 模块生成图表")

        if graph:
            return {
                **self._origins_metadata,
                **{
                    "_opik_graph_definition": {
                        "format": "mermaid",
                        "data": graph,
                    }
                },
            }
        else:
            return self._origins_metadata
