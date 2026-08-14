import logging
import datetime
from typing import (
    Any,
    Dict,
    List,
    Optional,
    TYPE_CHECKING,
    Callable,
    NamedTuple,
    Union,
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
from opik.types import DistributedTraceHeadersDict, ErrorInfoDict, LLMProvider
from opik.validation import parameters_validator
from . import (
    base_llm_patcher,
    run_parse_helpers,
    opik_encoder_extension,
    provider_usage_extractors,
    response_cost_extractors,
    run_state,
)

from ...api_objects import helpers

if TYPE_CHECKING:
    from langchain_core.runnables.graph import Graph
    from langchain_core.messages import BaseMessage

LOGGER = logging.getLogger(__name__)

opik_encoder_extension.register()

language_models.BaseLLM.dict = base_llm_patcher.base_llm_dict_patched()

# 接收错误字符串并返回True表示应跳过该错误，否则返回False的回调函数类型。
SkipErrorCallback = Callable[[str], bool]

# 要记录在 LLM span 上的固定提供商：一个纯字符串或一个 LLMProvider。
ProviderOverride = Union[str, LLMProvider]


class ProviderResolverContext(NamedTuple):
    """提供商解析回调针对单次 LLM 运行所接收的内容。

    ``model`` 是从运行中解析出的模型名称，通常是路由键
    （例如，当它包含 "anthropic" 时返回 "bedrock"）。``run`` 是原始的
    LangChain 运行字典，是仅凭 ``model`` 无法表达的内容进行路由的逃生口。
    """

    model: Optional[str]
    run: Dict[str, Any]


# 接收 ProviderResolverContext 并返回要记录在该特定运行上的提供商的可调用对象。
# 返回 None 会回退到从运行中自动检测的提供商。
ProviderResolver = Callable[[ProviderResolverContext], Optional[ProviderOverride]]

# 当错误通过skip_error_callback被有意跳过时使用的占位输出字典。
# 这表示由于执行过程中处理/忽略的错误而未产生输出。
ERROR_SKIPPED_OUTPUTS = {"warning": "Error output skipped by skip_error_callback."}

# LangGraph 中断/恢复功能的常量
LANGGRAPH_INTERRUPT_OUTPUT_KEY = "__interrupt__"
LANGGRAPH_RESUME_INPUT_KEY = "__resume__"
LANGGRAPH_INTERRUPT_METADATA_KEY = "_langgraph_interrupt"

# LangGraph ParentCommand 常量（多智能体控制流路由）
LANGGRAPH_PARENT_COMMAND_METADATA_KEY = "_langgraph_parent_command"


class TrackRootRunResult(NamedTuple):
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
        provider: Optional[Union[ProviderOverride, ProviderResolver]] = None,
        **kwargs: Any,
    ) -> None:
        """
        使用各种参数初始化类的实例，用于追踪、元数据和项目配置。

        Args:
            tags: 与记录的 trace 关联的标签列表。
            metadata: 包含记录 trace 的元数据信息的字典。
            graph: 用于表示依赖关系或流程的 LangGraph Graph 对象，
                以在 Opik 中追踪图定义。
            project_name: 与 trace 关联的项目名称。
            distributed_headers: 分布式追踪上下文的头部信息。
            thread_id: 要与 trace 关联的对话线程的唯一标识符。
            skip_error_callback : 用于处理跳过错误逻辑的回调函数。
                允许为有意跳过的错误定义自定义处理逻辑。
                请注意，在有意跳过错误的 trace/span 中，
                输出将被替换为 `ERROR_SKIPPED_OUTPUTS`。您可以
                使用 `opik_context.get_current_span_data().update(output=...)` 手动提供输出。
            opik_context_read_only_mode: 是否向/从上下文存储中添加/弹出 span/trace。
                * 如果为 False（默认值），OpikTracer 会将创建的 span/trace 添加到 opik 上下文中，
                  因此如果在 LangChain runnable 内部调用 @track 装饰的函数，
                  它会自动附加到来自 LangChain 的父 span。
                * 如果为 True，OpikTracer 不会修改上下文存储，仅从 LangChain 的 Run 对象创建 span/trace。
                  当环境不支持并发操作的适当上下文隔离，且您希望避免
                  由于不安全而修改 Opik 上下文栈时，这可能很有用。
            provider: 要在 LLM span 上记录的提供商，后端用于成本计算。
                当调用通过 OpenAI 兼容代理（例如 LiteLLM 网关）路由时很有用，
                否则提供商会自动检测为代理的主机名，无法计算成本。接受：
                * 一个字符串或 ``opik.LLMProvider``，应用于每个 LLM span
                  （常见的单一提供商情况），或
                * 一个接收 ``ProviderResolverContext``（``.model`` 是解析出的模型名称，
                  ``.run`` 是原始运行字典）并返回该特定运行提供商的可调用对象
                  （用于混合多个提供商的链/图）。从可调用对象返回 None 会回退到
                  该运行自动检测的提供商。
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

        self._run_state = run_state.RunStateStore()

        self._created_traces: List[trace.Trace] = []

        self._project_name = project_name

        self._distributed_headers = distributed_headers

        self._thread_id = thread_id

        self._opik_context_storage = context_storage.get_current_context_instance()

        self._root_run_external_parent_span_id: contextvars.ContextVar[
            Optional[str]
        ] = contextvars.ContextVar("root_run_external_parent_span_id", default=None)

        self._skip_error_callback = skip_error_callback

        self._opik_context_read_only_mode = opik_context_read_only_mode

        self._provider = provider

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

    def _persist_run(self, run: Run) -> None:
        run_dict: Dict[str, Any] = run.dict()

        error_info: Optional[ErrorInfoDict]
        trace_additional_metadata: Dict[str, Any] = {}

        error_str = run_dict.get("error")
        outputs: Optional[Dict[str, Any]] = None
        error_info = None

        if error_str is not None:
            # GraphInterrupt不是错误 - 它是LangGraph的正常控制流
            if interrupt_value := run_parse_helpers.parse_graph_interrupt_value(
                error_str
            ):
                outputs = {LANGGRAPH_INTERRUPT_OUTPUT_KEY: interrupt_value}
                trace_additional_metadata[LANGGRAPH_INTERRUPT_METADATA_KEY] = True
                # 不设置error_info - 这不是错误
            # ParentCommand不是错误 - 它是LangGraph中的多智能体路由
            elif run_parse_helpers.is_langgraph_parent_command(error_str):
                trace_additional_metadata[LANGGRAPH_PARENT_COMMAND_METADATA_KEY] = True
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

        # LangChain 每棵树只对根运行调用一次 _persist_run，因此这正好为每个 trace 最终化一次。
        # 我们只最终化自己拥有的 trace：`span_data is None` 表示仅 trace 的根
        # （被跳过的 LangGraph 根，无 span），而 owns_trace() 覆盖正常情况。
        # 在外部 trace（@track 函数或分布式头部）下运行的根运行留给其真正所有者最终化——
        # 我们只向它贡献了 span。
        span_data = self._run_state.get_span_data(run.id)
        if span_data is None or self._run_state.owns_trace(span_data.trace_id):
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
        trace_data = self._run_state.get_trace_data(run_id)
        if trace_data is None:
            LOGGER.warning(
                f"在 trace 数据映射中未找到运行 '{run_id}' 的 trace 数据。跳过 _finalize_trace 的处理。"
            )
            return

        # 针对 `.astream()` 方法使用的解决方案
        if trace_data.input == {"input": ""}:
            trace_data.input = run_dict["inputs"]
        elif isinstance(trace_data.input, dict) and "input" in trace_data.input:
            input_value = trace_data.input.get("input")
            if resume_value := run_parse_helpers.extract_resume_value_from_command(
                input_value
            ):
                trace_data.input = {LANGGRAPH_RESUME_INPUT_KEY: resume_value}

        # 检查是否有子span具有GraphInterrupt输出，并将其用于追踪输出
        for span_data in self._run_state.spans_for_trace(trace_data.id):
            if (
                span_data.metadata is not None
                and span_data.metadata.get(LANGGRAPH_INTERRUPT_METADATA_KEY) is True
            ):
                # 使用子span的中断输出
                outputs = span_data.output
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

        # 记录父 span ID 以供后续 LangGraph 清理使用
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
            LOGGER.error("_process_start_span 执行失败：%s", e, exc_info=True)

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

        # 检查父运行是否为被跳过的 LangGraph/LangChain 根运行。
        # 如果是，则将子项直接附加到 trace。
        # 否则，附加到父 span。
        if self._run_state.is_skipped_langgraph_root(run.parent_run_id):
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
        为给定运行创建根 trace 和 span，并将 trace 和
        span 数据记录在追踪器的运行状态中供后续查找。

        仅当未创建新 trace 时才会创建新 span，即当附加到现有 span
        或分布式头部时。如果创建了新 trace，则跳过 span，仅记录
        trace 数据供将来参考。
        """
        # 这是链的第一次运行。
        root_run_result = self._track_root_run(run_dict, allow_duplicating_root_span)
        if root_run_result.new_trace_data is not None:
            if not self._opik_context_read_only_mode:
                self._opik_context_storage.set_trace_data(
                    root_run_result.new_trace_data
                )
            self._emit_start_trace(root_run_result.new_trace_data)

        # 如果这是新追踪下的LangGraph/LangChain根运行，则跳过创建span
        if root_run_result.new_span_data is None:
            # 将此运行标记为已跳过，并记录其 trace 数据供子运行使用
            self._run_state.mark_skipped_langgraph_root(run_id)

            if root_run_result.new_trace_data is not None:
                self._run_state.save_trace_data(run_id, root_run_result.new_trace_data)
        else:
            # 记录新的 span（以及 trace，如果有），以便子运行可以查找它们
            self._run_state.save_span_data(run_id, root_run_result.new_span_data)
            if root_run_result.new_trace_data is not None:
                self._run_state.save_trace_data(run_id, root_run_result.new_trace_data)

            if not self._opik_context_read_only_mode:
                self._opik_context_storage.add_span_data(root_run_result.new_span_data)

            self._emit_start_span(root_run_result.new_span_data)

    def _should_log_start_events(self) -> bool:
        return (
            self._opik_client.config.log_start_trace_span
            and tracing_runtime_config.is_tracing_active()
        )

    def _emit_start_trace(self, trace_data: trace.TraceData) -> None:
        if self._should_log_start_events():
            self._opik_client.__internal_api__trace__(**trace_data.as_start_parameters)

    def _emit_start_span(self, span_data: span.SpanData) -> None:
        if self._should_log_start_events():
            self._opik_client.__internal_api__span__(**span_data.as_start_parameters)

    def _attach_span_to_parent_span(
        self, run_id: UUID, parent_run_id: UUID, run_dict: Dict[str, Any]
    ) -> None:
        """
        将子span附加到父span并更新相关上下文存储。

        此方法负责创建与某个运行关联的新 span 数据对象，将其链接到父 span 数据，
        并记录在追踪器的运行状态中。此外，它更新上下文存储，并在追踪处于活动状态时记录该 span。
        """
        parent_span_data = self._run_state.get_span_data(parent_run_id)
        assert parent_span_data is not None

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

        self._run_state.save_span_data(run_id, new_span_data)

        if self._run_state.owns_trace(new_span_data.trace_id):
            # 父运行可能是一个仅以 span 形式存在的流重启根运行
            # （而非被跳过的 LangGraph 根）；存储回退到 trace_id 查找，
            # 使子运行仍能继承 trace 数据。
            self._run_state.link_child_run_to_parent_trace(
                child_run_id=run_id,
                parent_run_id=parent_run_id,
                trace_id=new_span_data.trace_id,
            )

        if not self._opik_context_read_only_mode:
            self._opik_context_storage.add_span_data(new_span_data)

        self._emit_start_span(new_span_data)

    def _attach_span_to_local_or_distributed_trace(
        self, run_id: UUID, parent_run_id: UUID, run_dict: Dict[str, Any]
    ) -> None:
        """
        通过检查追踪数据或分布式头部，将子span直接附加到追踪，
        并根据提供的运行信息创建新的span数据。
        """
        # 检查我们是否有追踪数据（新追踪）或分布式头部
        parent_trace_data = self._run_state.get_trace_data(parent_run_id)
        if parent_trace_data is not None:
            # LangGraph创建了新追踪 - 将子项直接附加到追踪
            trace_data = parent_trace_data
            project_name = helpers.resolve_child_span_project_name(
                trace_data.project_name,
                context_storage.resolve_project_name(self._project_name, "OpikTracer"),
            )

            new_span_data = span.SpanData(
                trace_id=trace_data.id,
                parent_span_id=None,  # 追踪的直接子项
                input=run_dict["inputs"],
                metadata=run_parse_helpers.get_run_metadata(run_dict),
                name=run_dict["name"],
                type=run_parse_helpers.get_span_type(run_dict),
                project_name=project_name,
            )
            if self._run_state.owns_trace(new_span_data.trace_id):
                self._run_state.save_trace_data(run_id, trace_data)

        elif self._distributed_headers:
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

        elif (
            current_trace_data := self._opik_context_storage.get_trace_data()
        ) is not None:
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
        else:
            LOGGER.warning(
                f"未找到 LangGraph 子运行 '{run_id}' 的 trace 数据或分布式头部"
            )
            return

        new_span_data.update(metadata={"created_from": "langchain"})
        self._run_state.save_span_data(run_id, new_span_data)

        if not self._opik_context_read_only_mode:
            self._opik_context_storage.add_span_data(new_span_data)

        self._emit_start_span(new_span_data)

    def _process_end_span(self, run: Run) -> None:
        span_data = None
        try:
            # 如果这是跳过的LangGraph根运行，则跳过处理
            if self._run_state.is_skipped_langgraph_root(run.id):
                return

            span_data = self._run_state.get_span_data(run.id)
            if span_data is None:
                LOGGER.warning(
                    f"在 span 数据映射中未找到运行 '{run.id}' 的 span 数据。跳过结束 span 的处理。"
                )
                return
            run_dict: Dict[str, Any] = run.dict()

            usage_info = provider_usage_extractors.try_extract_provider_usage_data(
                run_dict
            )
            if usage_info is None:
                usage_info = llm_usage.LLMUsageInfo()

            provider_override = self._resolve_provider(run_dict, usage_info.model)
            if provider_override is not None:
                usage_info.provider = provider_override

            total_cost = response_cost_extractors.try_extract_response_cost(run_dict)

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
                total_cost=total_cost,
            )

            if tracing_runtime_config.is_tracing_active():
                self._opik_client.__internal_api__span__(**span_data.as_parameters)
        except Exception as e:
            LOGGER.error(f"_process_end_span 执行失败：{e}", exc_info=True)
        finally:
            self._release_ended_span_state(run, span_data)

    def _release_ended_span_state(
        self, run: Run, span_data: Optional[span.SpanData]
    ) -> None:
        """在运行的 span 结束后进行清理。

        将已结束的 span 从上下文栈中弹出，并且当该运行是其树的根时，
        释放整个子树的记账状态。根运行的结束处理程序是 LangChain 为其子树
        触发的最后一个回调（在 ``_persist_run`` 之后），因此此时可以安全地
        丢弃该状态。
        """
        if span_data is not None and not self._opik_context_read_only_mode:
            self._opik_context_storage.trim_span_data_stack_to_certain_span(
                span_id=span_data.id
            )
            self._opik_context_storage.pop_span_data(ensure_id=span_data.id)

        if run.parent_run_id is None:
            self._run_state.release_run_tree(run)

    def _resolve_provider(
        self, run_dict: Dict[str, Any], model: Optional[str]
    ) -> Optional[str]:
        if self._provider is None:
            return None

        if callable(self._provider):
            context = ProviderResolverContext(model=model, run=run_dict)
            try:
                resolved: Optional[ProviderOverride] = self._provider(context)
            except Exception:
                # 用户回调绝不能中断 trace 日志记录：发出警告并回退到该运行自动检测的提供商。
                LOGGER.warning(
                    "提供商解析回调引发了异常；回退到自动检测的提供商。",
                    exc_info=True,
                )
                return None
        else:
            resolved = self._provider

        if isinstance(resolved, LLMProvider):
            # 标准化为纯字符串值，避免裸枚举成员以 "LLMProvider.OPENAI" 的形式泄漏到日志/span 中。
            return resolved.value

        return resolved

    def _should_skip_error(self, error_str: str) -> bool:
        if self._skip_error_callback is None:
            return False

        return self._skip_error_callback(error_str)

    def _process_end_span_with_error(self, run: Run) -> None:
        span_data = None
        try:
            # 如果这是跳过的LangGraph根运行，则跳过处理
            if self._run_state.is_skipped_langgraph_root(run.id):
                return

            span_data = self._run_state.get_span_data(run.id)
            if span_data is None:
                LOGGER.warning(
                    f"在 span 数据映射中未找到运行 '{run.id}' 的 span 数据。跳过 _process_end_span_with_error 的处理。"
                )
                return

            run_dict: Dict[str, Any] = run.dict()
            error_str = run_dict["error"]

            # GraphInterrupt不是错误 - 它是LangGraph的正常控制流
            if interrupt_value := run_parse_helpers.parse_graph_interrupt_value(
                error_str
            ):
                span_data.init_end_time().update(
                    metadata={LANGGRAPH_INTERRUPT_METADATA_KEY: True},
                    output={LANGGRAPH_INTERRUPT_OUTPUT_KEY: interrupt_value},
                )
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
            LOGGER.debug(f"_process_end_span_with_error 执行失败：{e}")
        finally:
            self._release_ended_span_state(run, span_data)

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
        return self._run_state.get_span_data(run_id)

    def _skip_tracking(self) -> bool:
        return not tracing_runtime_config.is_tracing_active()

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
