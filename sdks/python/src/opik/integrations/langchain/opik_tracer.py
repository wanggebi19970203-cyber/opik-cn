import logging
import datetime
from typing import (
    Any,
    Dict,
    List,
    Optional,
    Set,
    TYPE_CHECKING,
    Callable,
    NamedTuple,
)
import contextvars
from uuid import UUID

from langchain_core import language_models
from langchain_core.tracers import BaseTracer
from langchain_core.tracers.schemas import Run

import opik
from opik import context_storage, dict_utils, llm_usage, tracing_runtime_config
from opik.api_objects import span, trace
from opik.decorator import arguments_helpers, span_creation_handler
from opik.types import DistributedTraceHeadersDict, ErrorInfoDict
from opik.validation import parameters_validator
from . import (
    base_llm_patcher,
    run_parse_helpers,
    opik_encoder_extension,
    provider_usage_extractors,
)

from ...api_objects import helpers

if TYPE_CHECKING:
    from langchain_core.runnables.graph import Graph
    from langchain_core.messages import BaseMessage

LOGGER = logging.getLogger(__name__)

opik_encoder_extension.register()

language_models.BaseLLM.dict = base_llm_patcher.base_llm_dict_patched()

# A callable that receives an error string and returns True if the error should be skipped,
# or False otherwise.
# 接收错误字符串并返回True表示应跳过该错误，否则返回False的回调函数类型。
SkipErrorCallback = Callable[[str], bool]

# Placeholder output dictionary used when errors are intentionally skipped
# via the skip_error_callback. This signals that the output was not produced
# due to a handled/ignored error during execution.
# 当错误通过skip_error_callback被有意跳过时使用的占位输出字典。
# 这表示由于执行过程中处理/忽略的错误而未产生输出。
ERROR_SKIPPED_OUTPUTS = {"warning": "Error output skipped by skip_error_callback."}

# Constants for LangGraph interrupt/resume functionality
# LangGraph 中断/恢复功能的常量
LANGGRAPH_INTERRUPT_OUTPUT_KEY = "__interrupt__"
LANGGRAPH_RESUME_INPUT_KEY = "__resume__"
LANGGRAPH_INTERRUPT_METADATA_KEY = "_langgraph_interrupt"

# Constant for LangGraph ParentCommand (multi-agent control flow routing)
# LangGraph ParentCommand 常量（多智能体控制流路由）
LANGGRAPH_PARENT_COMMAND_METADATA_KEY = "_langgraph_parent_command"


class TrackRootRunResult(NamedTuple):
    """跟踪根运行结果的命名元组。"""
    new_trace_data: Optional[trace.TraceData]
    new_span_data: Optional[span.SpanData]


class OpikTracer(BaseTracer):
    """Langchain Opik 追踪器。"""

    def __init__(
        self,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        graph: Optional["Graph"] = None,
        project_name: Optional[str] = None,
        distributed_headers: Optional[DistributedTraceHeadersDict] = None,
        thread_id: Optional[str] = None,
        skip_error_callback: Optional[SkipErrorCallback] = None,
        opik_context_read_only_mode: bool = False,
        **kwargs: Any,
    ) -> None:
        """
        使用各种参数初始化类的实例，用于追踪、元数据和项目配置。

        Args:
            tags: 与记录的追踪关联的标签列表。
            metadata: 包含记录追踪元数据信息的字典。
            graph: 用于表示依赖关系或流程的LangGraph Graph对象，
                用于在Opik中跟踪图定义。
            project_name: 与追踪关联的项目名称。
            distributed_headers: 分布式追踪上下文的头部信息。
            thread_id: 与追踪关联的对话线程唯一标识符。
            skip_error_callback: 处理跳过错误逻辑的回调函数。
                允许定义处理有意跳过的错误的自定义逻辑。
                请注意，在有意跳过错误的追踪/span中，
                输出将被替换为 `ERROR_SKIPPED_OUTPUTS`。您可以使用
                `opik_context.get_current_span_data().update(output=...)` 手动提供输出。
            opik_context_read_only_mode: 是否向上下文存储添加/弹出span/trace。
                * 如果为False（默认），OpikTracer会将创建的span/trace添加到opik上下文中，
                  因此如果在LangChain可运行对象内部调用了@track装饰的函数，
                  它将自动附加到LangChain的父span中。
                * 如果为True，OpikTracer不会修改上下文存储，只会从LangChain的Run对象创建span/trace。
                  当环境不支持并发操作的适当上下文隔离，并且您希望避免因不安全性而修改Opik上下文栈时，这可能很有用。
            **kwargs: 传递给父类构造函数的其他参数。
        """
        validator = parameters_validator.create_validator(
            method_name="__init__", class_name=self.__class__.__name__
        )
        validator.add_str_parameter(thread_id, name="thread_id")
        validator.add_str_parameter(project_name, name="project_name")
        validator.add_dict_parameter(metadata, name="metadata")
        validator.add_list_parameter(tags, name="tags")
        if not validator.validate():
            validator.raise_validation_error()

        super().__init__(**kwargs)
        self._trace_default_metadata = metadata if metadata is not None else {}
        self._trace_default_metadata["created_from"] = "langchain"

        if graph:
            self.set_graph(graph)

        self._trace_default_tags = tags

        self._span_data_map: Dict[UUID, span.SpanData] = {}
        """从运行ID到span数据的映射。"""

        self._created_traces_data_map: Dict[UUID, trace.TraceData] = {}
        """从运行ID到追踪数据的映射。"""

        self._created_traces: List[trace.Trace] = []

        self._externally_created_traces_ids: Set[str] = set()

        self._skipped_langgraph_root_run_ids: Set[UUID] = set()
        """跳过创建span的LangGraph根运行ID集合。"""

        self._langgraph_parent_span_ids: Dict[UUID, Optional[str]] = {}
        """从LangGraph根运行ID到父span ID的映射（如果附加到追踪则为None）。"""

        self._project_name = project_name

        self._distributed_headers = distributed_headers

        self._thread_id = thread_id

        self._opik_context_storage = context_storage.get_current_context_instance()

        self._root_run_external_parent_span_id: contextvars.ContextVar[
            Optional[str]
        ] = contextvars.ContextVar("root_run_external_parent_span_id", default=None)

        self._skip_error_callback = skip_error_callback

        self._opik_context_read_only_mode = opik_context_read_only_mode

    @property
    def _opik_client(self) -> opik.Opik:
        return opik.get_global_client()

    def set_graph(self, graph: "Graph") -> None:
        """
        设置LangGraph图结构以在Opik追踪中进行可视化。

        此方法提取图结构并将其存储在追踪元数据中，
        使图可以在Opik UI中进行可视化。

        Args:
            graph: LangGraph Graph对象（通常通过 graph.get_graph(xray=True) 获取）。
        """
        self._trace_default_metadata["_opik_graph_definition"] = {
            "format": "mermaid",
            "data": graph.draw_mermaid(),
        }

    def _is_opik_span_created_by_this_tracer(self, span_id: str) -> bool:
        return any(span_.id == span_id for span_ in self._span_data_map.values())

    def _is_opik_trace_created_by_this_tracer(self, trace_id: str) -> bool:
        return any(
            trace_.id == trace_id for trace_ in self._created_traces_data_map.values()
        )

    def _persist_run(self, run: Run) -> None:
        """持久化运行数据。"""
        run_dict: Dict[str, Any] = run.dict()

        error_info: Optional[ErrorInfoDict]
        trace_additional_metadata: Dict[str, Any] = {}

        error_str = run_dict.get("error")
        outputs: Optional[Dict[str, Any]] = None
        error_info = None

        if error_str is not None:
            # GraphInterrupt is not an error - it's a normal control flow for LangGraph
            # GraphInterrupt不是错误 - 它是LangGraph的正常控制流
            if interrupt_value := run_parse_helpers.parse_graph_interrupt_value(
                error_str
            ):
                outputs = {LANGGRAPH_INTERRUPT_OUTPUT_KEY: interrupt_value}
                trace_additional_metadata[LANGGRAPH_INTERRUPT_METADATA_KEY] = True
                # Don't set error_info - this is not an error
                # 不设置error_info - 这不是错误
            # ParentCommand is not an error - it's multi-agent routing in LangGraph
            # ParentCommand不是错误 - 它是LangGraph中的多智能体路由
            elif run_parse_helpers.is_langgraph_parent_command(error_str):
                trace_additional_metadata[LANGGRAPH_PARENT_COMMAND_METADATA_KEY] = True
                # Don't set error_info - this is not an error
                # 不设置error_info - 这不是错误
            elif not self._should_skip_error(error_str):
                error_info = ErrorInfoDict(
                    exception_type="Exception",
                    traceback=error_str,
                )
            else:
                outputs = ERROR_SKIPPED_OUTPUTS
        elif (outputs := run_dict.get("outputs")) is not None:
            if isinstance(outputs, dict):
                outputs = run_parse_helpers.extract_command_update(outputs)

        if not self._opik_context_read_only_mode:
            self._ensure_no_hanging_opik_tracer_spans()

        span_data = self._span_data_map.get(run.id)
        if (
            span_data is None
            or span_data.trace_id not in self._externally_created_traces_ids
        ):
            self._finalize_trace(
                run_id=run.id,
                run_dict=run_dict,
                trace_additional_metadata=trace_additional_metadata,
                outputs=outputs,
                error_info=error_info,
            )

    def _finalize_trace(
        self,
        run_id: UUID,
        run_dict: Dict[str, Any],
        trace_additional_metadata: Optional[Dict[str, Any]],
        outputs: Optional[Dict[str, Any]],
        error_info: Optional[ErrorInfoDict],
    ) -> None:
        """完成追踪处理。"""
        trace_data = self._created_traces_data_map.get(run_id)
        if trace_data is None:
            LOGGER.warning(
                f"Trace data for run '{run_id}' not found in the traces data map. Skipping processing of _finalize_trace."
            )
            return

        # workaround for `.astream()` method usage
        # 针对 `.astream()` 方法使用的解决方案
        if trace_data.input == {"input": ""}:
            trace_data.input = run_dict["inputs"]
        elif isinstance(trace_data.input, dict) and "input" in trace_data.input:
            input_value = trace_data.input.get("input")
            if resume_value := run_parse_helpers.extract_resume_value_from_command(
                input_value
            ):
                trace_data.input = {LANGGRAPH_RESUME_INPUT_KEY: resume_value}

        # Check if any child span has a GraphInterrupt output and use it for trace output
        # 检查是否有子span具有GraphInterrupt输出，并将其用于追踪输出
        for _, span_data in self._span_data_map.items():
            if (
                span_data.trace_id == trace_data.id
                and span_data.metadata is not None
                and span_data.metadata.get(LANGGRAPH_INTERRUPT_METADATA_KEY) is True
            ):
                # Use the interrupt output from the child span
                # 使用子span的中断输出
                outputs = span_data.output
                # Also propagate the interrupt metadata to trace
                # 同时将中断元数据传播到追踪
                if trace_additional_metadata is None:
                    trace_additional_metadata = {}
                trace_additional_metadata[LANGGRAPH_INTERRUPT_METADATA_KEY] = True
                break

        if trace_additional_metadata:
            trace_data.update(metadata=trace_additional_metadata)

        trace_data.init_end_time().update(output=outputs, error_info=error_info)
        trace_ = self._opik_client.__internal_api__trace__(**trace_data.as_parameters)

        assert trace_ is not None
        self._created_traces.append(trace_)
        if not self._opik_context_read_only_mode:
            self._opik_context_storage.pop_trace_data(ensure_id=trace_data.id)

    def _ensure_no_hanging_opik_tracer_spans(self) -> None:
        root_run_external_parent_span_id = self._root_run_external_parent_span_id.get()
        there_were_no_external_spans_before_chain_invocation = (
            root_run_external_parent_span_id is None
        )

        if there_were_no_external_spans_before_chain_invocation:
            self._opik_context_storage.clear_spans()
        else:
            assert root_run_external_parent_span_id is not None
            self._opik_context_storage.trim_span_data_stack_to_certain_span(
                root_run_external_parent_span_id
            )

    def _track_root_run(
        self, run_dict: Dict[str, Any], allow_duplicating_root_span: bool
    ) -> TrackRootRunResult:
        run_metadata = run_parse_helpers.get_run_metadata(run_dict)
        root_metadata = dict_utils.deepmerge(self._trace_default_metadata, run_metadata)

        # Track the parent span ID for LangGraph cleanup later
        current_span_data = self._opik_context_storage.top_span_data()
        parent_span_id_when_langgraph_started = (
            current_span_data.id if current_span_data is not None else None
        )
        self._root_run_external_parent_span_id.set(
            parent_span_id_when_langgraph_started
        )
        detected_thread_id = run_metadata.get("thread_id")
        thread_id = self._thread_id or detected_thread_id

        start_span_arguments = arguments_helpers.StartSpanParameters(
            name=run_dict["name"],
            input=run_dict["inputs"],
            type=run_parse_helpers.get_span_type(run_dict),
            tags=self._trace_default_tags,
            metadata=root_metadata,
            project_name=context_storage.resolve_project_name(
                self._project_name, "OpikTracer"
            ),
            thread_id=thread_id,
        )

        span_creation_result = span_creation_handler.create_span_respecting_context(
            start_span_arguments=start_span_arguments,
            distributed_trace_headers=self._distributed_headers,
            opik_context_storage=self._opik_context_storage,
        )

        trace_created_externally = (
            span_creation_result.trace_data is None
            and not self._is_opik_trace_created_by_this_tracer(
                span_creation_result.span_data.trace_id
            )
        )
        if trace_created_externally:
            self._externally_created_traces_ids.add(
                span_creation_result.span_data.trace_id
            )

        should_skip_root_span_creation = (
            span_creation_result.trace_data is not None
            and run_parse_helpers.is_root_run(run_dict)
            and not allow_duplicating_root_span
        )
        if should_skip_root_span_creation:
            return TrackRootRunResult(
                new_trace_data=span_creation_result.trace_data,
                new_span_data=None,
            )

        return TrackRootRunResult(
            new_trace_data=span_creation_result.trace_data,
            new_span_data=span_creation_result.span_data,
        )

    def _process_start_span(self, run: Run, allow_duplicating_root_span: bool) -> None:
        try:
            self._process_start_span_unsafe(run, allow_duplicating_root_span)
        except Exception as e:
            LOGGER.error("Failed during _process_start_span: %s", e, exc_info=True)

    def _process_start_span_unsafe(
        self, run: Run, allow_duplicating_root_span: bool
    ) -> None:
        run_dict: Dict[str, Any] = run.dict()

        if not run.parent_run_id:
            self._create_root_trace_and_span(
                run_id=run.id,
                run_dict=run_dict,
                allow_duplicating_root_span=allow_duplicating_root_span,
            )
            return

        # Check if the parent is a skipped LangGraph/LangChain root run.
        # If so, attach children directly to trace.
        # Otherwise, attach to the parent span.
        if run.parent_run_id in self._skipped_langgraph_root_run_ids:
            self._attach_span_to_local_or_distributed_trace(
                run_id=run.id,
                parent_run_id=run.parent_run_id,
                run_dict=run_dict,
            )
        else:
            self._attach_span_to_parent_span(
                run_id=run.id, parent_run_id=run.parent_run_id, run_dict=run_dict
            )

    def _create_root_trace_and_span(
        self, run_id: UUID, run_dict: Dict[str, Any], allow_duplicating_root_span: bool
    ) -> None:
        """
        为给定运行创建根追踪和span，并将相关的追踪和span数据存储在本地存储中以供将来参考。

        仅在未创建新追踪时才创建新span，即当附加到现有span或分布式头部时。
        如果创建了新追踪，则跳过span，仅将追踪数据存储在本地存储中以供将来参考。
        """
        # This is the first run for the chain.
        # 这是链的第一次运行。
        root_run_result = self._track_root_run(run_dict, allow_duplicating_root_span)
        if root_run_result.new_trace_data is not None:
            if not self._opik_context_read_only_mode:
                self._opik_context_storage.set_trace_data(
                    root_run_result.new_trace_data
                )

            if (
                self._opik_client.config.log_start_trace_span
                and tracing_runtime_config.is_tracing_active()
            ):
                self._opik_client.__internal_api__trace__(
                    **root_run_result.new_trace_data.as_start_parameters
                )

        # If this is a LangGraph/LangChain root run under fresh trace, skip creating the span
        # 如果这是新追踪下的LangGraph/LangChain根运行，则跳过创建span
        if root_run_result.new_span_data is None:
            # Mark this run as skipped and store trace data for child runs
            # 标记此运行为已跳过，并为子运行存储追踪数据
            self._skipped_langgraph_root_run_ids.add(run_id)

            # Store parent span ID if LangGraph was attached to the existing span
            # 如果LangGraph附加到现有span，则存储父span ID
            parent_span_id = self._root_run_external_parent_span_id.get()
            self._langgraph_parent_span_ids[run_id] = parent_span_id

            # Store trace data if we created a new trace but skip span data
            # 如果我们创建了新追踪但跳过span数据，则存储追踪数据
            if root_run_result.new_trace_data is not None:
                self._save_span_trace_data_to_local_maps(
                    run_id=run_id,
                    span_data=None,
                    trace_data=root_run_result.new_trace_data,
                )
        else:
            # save new span and trace data to local maps to be able to retrieve them later
            # 将新的span和追踪数据保存到本地映射中，以便稍后检索
            self._save_span_trace_data_to_local_maps(
                run_id=run_id,
                span_data=root_run_result.new_span_data,
                trace_data=root_run_result.new_trace_data,
            )

            if not self._opik_context_read_only_mode:
                self._opik_context_storage.add_span_data(root_run_result.new_span_data)

            if (
                self._opik_client.config.log_start_trace_span
                and tracing_runtime_config.is_tracing_active()
            ):
                self._opik_client.__internal_api__span__(
                    **root_run_result.new_span_data.as_start_parameters
                )

    def _attach_span_to_parent_span(
        self, run_id: UUID, parent_run_id: UUID, run_dict: Dict[str, Any]
    ) -> None:
        """
        将子span附加到父span并更新相关上下文存储。

        此方法负责创建与运行关联的新span数据对象，
        将其链接到父span数据，并保存到本地和外部映射中。
        此外，它更新上下文存储，并在追踪活动时记录span。
        """
        parent_span_data = self._span_data_map[parent_run_id]

        project_name = helpers.resolve_child_span_project_name(
            parent_span_data.project_name,
            context_storage.resolve_project_name(self._project_name, "OpikTracer"),
        )

        new_span_data = span.SpanData(
            trace_id=parent_span_data.trace_id,
            parent_span_id=parent_span_data.id,
            input=run_dict["inputs"],
            metadata=run_parse_helpers.get_run_metadata(run_dict),
            name=run_dict["name"],
            type=run_parse_helpers.get_span_type(run_dict),
            project_name=project_name,
        )
        new_span_data.update(metadata={"created_from": "langchain"})

        self._save_span_trace_data_to_local_maps(
            run_id=run_id,
            span_data=new_span_data,
            trace_data=None,
        )

        if new_span_data.trace_id not in self._externally_created_traces_ids:
            if parent_run_id in self._created_traces_data_map:
                self._created_traces_data_map[run_id] = self._created_traces_data_map[
                    parent_run_id
                ]
            else:
                # Parent may be a stream-restart root run that was created as a regular
                # span (not a skipped LangGraph root). Find the trace data by trace_id.
                # 父级可能是作为常规span（不是跳过的LangGraph根）创建的流重启根运行。
                # 通过trace_id查找追踪数据。
                for td in self._created_traces_data_map.values():
                    if td.id == new_span_data.trace_id:
                        self._created_traces_data_map[run_id] = td
                        break

        if not self._opik_context_read_only_mode:
            self._opik_context_storage.add_span_data(new_span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.__internal_api__span__(
                **new_span_data.as_start_parameters
            )

    def _attach_span_to_local_or_distributed_trace(
        self, run_id: UUID, parent_run_id: UUID, run_dict: Dict[str, Any]
    ) -> None:
        """
        通过检查追踪数据或分布式头部，将子span直接附加到追踪，
        并根据提供的运行信息创建新的span数据。
        """
        # Check if we have trace data (new trace) or distributed headers
        # 检查我们是否有追踪数据（新追踪）或分布式头部
        if parent_run_id in self._created_traces_data_map:
            # LangGraph created a new trace - attach children directly to trace
            # LangGraph创建了新追踪 - 将子项直接附加到追踪
            trace_data = self._created_traces_data_map[parent_run_id]
            project_name = helpers.resolve_child_span_project_name(
                trace_data.project_name,
                context_storage.resolve_project_name(self._project_name, "OpikTracer"),
            )

            new_span_data = span.SpanData(
                trace_id=trace_data.id,
                parent_span_id=None,  # Direct child of trace  # 追踪的直接子项
                input=run_dict["inputs"],
                metadata=run_parse_helpers.get_run_metadata(run_dict),
                name=run_dict["name"],
                type=run_parse_helpers.get_span_type(run_dict),
                project_name=project_name,
            )
            if new_span_data.trace_id not in self._externally_created_traces_ids:
                self._created_traces_data_map[run_id] = trace_data

        elif self._distributed_headers:
            # LangGraph with distributed headers - attach to distributed trace
            # 带有分布式头部的LangGraph - 附加到分布式追踪
            new_span_data = span.SpanData(
                trace_id=self._distributed_headers["opik_trace_id"],
                parent_span_id=self._distributed_headers["opik_parent_span_id"],
                name=run_dict["name"],
                input=run_dict["inputs"],
                metadata=run_parse_helpers.get_run_metadata(run_dict),
                tags=self._trace_default_tags,
                project_name=context_storage.resolve_project_name(
                    self._project_name, "OpikTracer"
                ),
                type=run_parse_helpers.get_span_type(run_dict),
            )
            self._externally_created_traces_ids.add(new_span_data.trace_id)

        elif (
            current_trace_data := self._opik_context_storage.get_trace_data()
        ) is not None:
            # LangGraph attached to existing trace - attach children directly to trace
            # LangGraph附加到现有追踪 - 将子项直接附加到追踪
            project_name = helpers.resolve_child_span_project_name(
                current_trace_data.project_name,
                context_storage.resolve_project_name(self._project_name, "OpikTracer"),
            )

            new_span_data = span.SpanData(
                trace_id=current_trace_data.id,
                parent_span_id=None,
                name=run_dict["name"],
                input=run_dict["inputs"],
                metadata=run_parse_helpers.get_run_metadata(run_dict),
                tags=self._trace_default_tags,
                project_name=project_name,
                type=run_parse_helpers.get_span_type(run_dict),
            )

            if not self._is_opik_trace_created_by_this_tracer(current_trace_data.id):
                self._externally_created_traces_ids.add(current_trace_data.id)
        else:
            LOGGER.warning(
                f"Cannot find trace data or distributed headers for LangGraph child run '{run_id}'"
            )
            return

        new_span_data.update(metadata={"created_from": "langchain"})
        self._save_span_trace_data_to_local_maps(
            run_id=run_id,
            span_data=new_span_data,
            trace_data=None,
        )

        if not self._opik_context_read_only_mode:
            self._opik_context_storage.add_span_data(new_span_data)

        if (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        ):
            self._opik_client.__internal_api__span__(
                **new_span_data.as_start_parameters
            )

    def _process_end_span(self, run: Run) -> None:
        """处理span结束事件。"""
        span_data = None
        try:
            # Skip processing if this is a skipped LangGraph root run
            # 如果这是跳过的LangGraph根运行，则跳过处理
            if run.id in self._skipped_langgraph_root_run_ids:
                return

            if run.id not in self._span_data_map:
                LOGGER.warning(
                    f"Span data for run '{run.id}' not found in the span data map. Skipping processing of end span."
                )
                return
            span_data = self._span_data_map[run.id]
            run_dict: Dict[str, Any] = run.dict()

            usage_info = provider_usage_extractors.try_extract_provider_usage_data(
                run_dict
            )
            if usage_info is None:
                usage_info = llm_usage.LLMUsageInfo()

            # workaround for `.astream()` method usage
            # 针对 `.astream()` 方法使用的解决方案
            if span_data.input == {"input": ""} or span_data.input == {"input": {}}:
                span_data.input = run_dict["inputs"]
            elif isinstance(span_data.input, dict):
                input_value = span_data.input.get("input")
                if resume_value := run_parse_helpers.extract_resume_value_from_command(
                    input_value
                ):
                    span_data.input = {LANGGRAPH_RESUME_INPUT_KEY: resume_value}

            run_dict_outputs = run_dict.get("outputs")
            span_output = (
                run_parse_helpers.extract_command_update(run_dict_outputs)
                if isinstance(run_dict_outputs, dict)
                else {"output": run_dict_outputs}
            )

            span_data.init_end_time().update(
                output=span_output,
                usage=(
                    usage_info.usage.provider_usage.model_dump()
                    if isinstance(usage_info.usage, llm_usage.OpikUsage)
                    else usage_info.usage
                ),
                provider=usage_info.provider,
                model=usage_info.model,
            )

            if tracing_runtime_config.is_tracing_active():
                self._opik_client.__internal_api__span__(**span_data.as_parameters)
        except Exception as e:
            LOGGER.error(f"Failed during _process_end_span: {e}", exc_info=True)
        finally:
            if span_data is not None and not self._opik_context_read_only_mode:
                self._opik_context_storage.trim_span_data_stack_to_certain_span(
                    span_id=span_data.id
                )
                self._opik_context_storage.pop_span_data(ensure_id=span_data.id)

    def _should_skip_error(self, error_str: str) -> bool:
        if self._skip_error_callback is None:
            return False

        return self._skip_error_callback(error_str)

    def _process_end_span_with_error(self, run: Run) -> None:
        """处理带错误的span结束事件。"""
        # Skip processing if this is a skipped LangGraph root run
        # 如果这是跳过的LangGraph根运行，则跳过处理
        if run.id in self._skipped_langgraph_root_run_ids:
            return

        if run.id not in self._span_data_map:
            LOGGER.warning(
                f"Span data for run '{run.id}' not found in the span data map. Skipping processing of _process_end_span_with_error."
            )
            return

        span_data = None
        try:
            run_dict: Dict[str, Any] = run.dict()
            span_data = self._span_data_map[run.id]
            error_str = run_dict["error"]

            # GraphInterrupt is not an error - it's a normal control flow for LangGraph
            # GraphInterrupt不是错误 - 它是LangGraph的正常控制流
            if interrupt_value := run_parse_helpers.parse_graph_interrupt_value(
                error_str
            ):
                span_data.init_end_time().update(
                    metadata={LANGGRAPH_INTERRUPT_METADATA_KEY: True},
                    output={LANGGRAPH_INTERRUPT_OUTPUT_KEY: interrupt_value},
                )
            # ParentCommand is not an error - it's multi-agent routing in LangGraph
            # ParentCommand不是错误 - 它是LangGraph中的多智能体路由
            elif run_parse_helpers.is_langgraph_parent_command(error_str):
                span_data.init_end_time().update(
                    metadata={LANGGRAPH_PARENT_COMMAND_METADATA_KEY: True},
                )
            elif self._should_skip_error(error_str):
                span_data.init_end_time().update(output=ERROR_SKIPPED_OUTPUTS)
            else:
                error_info = ErrorInfoDict(
                    exception_type="Exception",
                    traceback=error_str,
                )
                span_data.init_end_time().update(
                    output=None,
                    error_info=error_info,
                )

            if tracing_runtime_config.is_tracing_active():
                self._opik_client.__internal_api__span__(**span_data.as_parameters)
        except Exception as e:
            LOGGER.debug(f"Failed during _process_end_span_with_error: {e}")
        finally:
            if span_data is not None and not self._opik_context_read_only_mode:
                self._opik_context_storage.trim_span_data_stack_to_certain_span(
                    span_id=span_data.id
                )
                self._opik_context_storage.pop_span_data(ensure_id=span_data.id)

    def _save_span_trace_data_to_local_maps(
        self,
        run_id: UUID,
        span_data: Optional[span.SpanData],
        trace_data: Optional[trace.TraceData],
    ) -> None:
        if span_data is not None:
            self._span_data_map[run_id] = span_data

        if trace_data is not None:
            self._created_traces_data_map[run_id] = trace_data

    def flush(self) -> None:
        """
        刷新以确保所有数据发送到Opik服务器。
        """
        self._opik_client.flush()

    def created_traces(self) -> List[trace.Trace]:
        """
        获取OpikTracer创建的追踪列表。

        Returns:
            List[Trace]: 追踪列表。
        """
        return self._created_traces

    def get_current_span_data_for_run(self, run_id: UUID) -> Optional[span.SpanData]:
        return self._span_data_map.get(run_id)

    def _skip_tracking(self) -> bool:
        if not tracing_runtime_config.is_tracing_active():
            return True

        return False

    def _on_llm_start(self, run: Run) -> None:
        """处理LLM运行开始事件。"""
        if self._skip_tracking():
            return

        self._process_start_span(run, allow_duplicating_root_span=True)

    def on_chat_model_start(
        self,
        serialized: Dict[str, Any],
        messages: List[List["BaseMessage"]],
        *,
        run_id: UUID,
        tags: Optional[List[str]] = None,
        parent_run_id: Optional[UUID] = None,
        metadata: Optional[Dict[str, Any]] = None,
        name: Optional[str] = None,
        **kwargs: Any,
    ) -> Run:
        """开始LLM运行的追踪。

        从Langchain追踪器复制而来，默认在所有追踪器中禁用，
        参见 https://github.com/langchain-ai/langchain/blob/fdda1aaea14b257845a19023e8af5e20140ec9fe/libs/core/langchain_core/callbacks/manager.py#L270-L289
        和 https://github.com/langchain-ai/langchain/blob/fdda1aaea14b257845a19023e8af5e20140ec9fe/libs/core/langchain_core/tracers/core.py#L168-L180

        Args:
            serialized: 序列化的模型。
            messages: 消息列表。
            run_id: 运行ID。
            tags: 标签。默认为None。
            parent_run_id: 父运行ID。默认为None。
            metadata: 元数据。默认为None。
            name: 名称。默认为None。
            kwargs: 其他关键字参数。

        Returns:
            Run: 运行对象。
        """
        start_time = datetime.datetime.now(datetime.timezone.utc)
        if metadata:
            kwargs.update({"metadata": metadata})

        # We switched from langchain dumpd to model_dump() as we don't need all the langchain stuff
        # 我们从langchain dumpd切换到model_dump()，因为我们不需要所有的langchain内容
        chat_model_run = Run(
            id=run_id,
            parent_run_id=parent_run_id,
            serialized=serialized,
            inputs={
                "messages": [[msg.model_dump() for msg in batch] for batch in messages]
            },
            extra=kwargs,
            events=[{"name": "start", "time": start_time}],
            start_time=start_time,
            run_type="llm",
            tags=tags,
            name=name,  # type: ignore[arg-type]
        )

        self._start_trace(chat_model_run)
        self._on_chat_model_start(chat_model_run)
        return chat_model_run

    def _on_chat_model_start(self, run: Run) -> None:
        """处理聊天模型运行开始事件。"""
        if self._skip_tracking():
            return

        self._process_start_span(run, allow_duplicating_root_span=True)

    def _on_llm_end(self, run: Run) -> None:
        """处理LLM运行结束事件。"""
        if self._skip_tracking():
            return

        self._process_end_span(run)

    def _on_llm_error(self, run: Run) -> None:
        """处理LLM运行错误事件。"""
        if self._skip_tracking():
            return

        self._process_end_span_with_error(run)

    def _on_chain_start(self, run: Run) -> None:
        """处理链运行开始事件。"""
        if self._skip_tracking():
            return

        self._process_start_span(run, allow_duplicating_root_span=False)

    def _on_chain_end(self, run: Run) -> None:
        """处理链运行结束事件。"""
        if self._skip_tracking():
            return

        self._process_end_span(run)

    def _on_chain_error(self, run: Run) -> None:
        """处理链运行错误事件。"""
        if self._skip_tracking():
            return

        self._process_end_span_with_error(run)

    def _on_tool_start(self, run: Run) -> None:
        """处理工具运行开始事件。"""
        if self._skip_tracking():
            return

        self._process_start_span(run, allow_duplicating_root_span=True)

    def _on_tool_end(self, run: Run) -> None:
        """处理工具运行结束事件。"""
        if self._skip_tracking():
            return

        self._process_end_span(run)

    def _on_tool_error(self, run: Run) -> None:
        """处理工具运行错误事件。"""
        if self._skip_tracking():
            return

        self._process_end_span_with_error(run)
