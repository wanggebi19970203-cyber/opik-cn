import abc
import functools
import inspect
import logging
from typing import (
    Any,
    Callable,
    Dict,
    List,
    Optional,
    Set,
    Tuple,
    Union,
    NamedTuple,
)

from .. import context_storage, logging_messages, tracing_runtime_config
from ..api_objects import opik_client, span, trace
from ..runner import registry
from ..types import DistributedTraceHeadersDict, ErrorInfoDict, SpanType, TraceSource
from . import (
    arguments_helpers,
    error_info_collector,
    generator_wrappers,
    inspect_helpers,
    opik_args,
    span_creation_handler,
)

LOGGER = logging.getLogger(__name__)

TRACES_CREATED_BY_DECORATOR: Set[str] = set()


class TrackingStartOptions(NamedTuple):
    """跟踪启动选项，包含创建span所需的参数。"""

    start_span_parameters: arguments_helpers.StartSpanParameters
    opik_args: Optional[opik_args.OpikArgs]
    opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict]


class BaseTrackDecorator(abc.ABC):
    """
    内部使用基类。

    所有 TrackDecorator 实例共享相同的上下文，可以同时使用。

    子类必须实现以下方法:
        * _start_span_inputs_preprocessor - 预处理span创建输入参数
        * _end_span_inputs_preprocessor - 预处理span结束输入参数
        * _generators_handler - 生成器处理器（提供了默认实现，但仍需通过 `super()` 调用）

    不建议重写此类的其他方法。
    """

    def __init__(self) -> None:
        self.provider: Optional[str] = None
        """ LLM 提供商名称。在集成跟踪装饰器的子类中使用。 """

    def track(
        self,
        name: Optional[Union[Callable, str]] = None,
        type: SpanType = "general",
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        capture_input: bool = True,
        ignore_arguments: Optional[List[str]] = None,
        capture_output: bool = True,
        generations_aggregator: Optional[Callable[[List[Any]], Any]] = None,
        flush: bool = False,
        project_name: Optional[str] = None,
        create_duplicate_root_span: bool = True,
        entrypoint: bool = False,
        source: Optional[TraceSource] = None,
        environment: Optional[str] = None,
    ) -> Union[Callable, Callable[[Callable], Callable]]:
        """
        跟踪函数执行的装饰器。

        可以用作 @track 或 @track() 形式。

        Args:
            name: span 的名称。
            type: span 的类型。
            tags: 与 span 关联的标签。
            metadata: 与 span 关联的元数据。
            capture_input: 是否捕获输入参数。
            ignore_arguments: 不应包含在 span/trace 输入中的参数列表。
            capture_output: 是否捕获输出结果。
            generations_aggregator: 用于聚合生成结果的函数。
            flush: 日志记录后是否刷新客户端。
            project_name: 记录数据的项目名称。
            create_duplicate_root_span: 是否创建复制根 trace 数据的根 span。
            source: trace 的来源。

        Returns:
            Callable: 被装饰的函数（无括号使用时）
                或装饰器函数（有括号使用时）。

        Note:
            可以使用此装饰器跟踪嵌套函数，Opik 会自动创建 trace
            并正确处理嵌套函数调用的 span。

            此装饰器可用于跟踪同步和异步函数，
            以及同步和异步生成器。
            它会自动检测函数类型并应用相应的跟踪逻辑。

            跟踪状态仅在调用开始时检查一次；在跟踪启用期间开始的调用
            即使在返回前跟踪被禁用，仍会被记录。
        """
        track_options = arguments_helpers.TrackOptions(
            name=None,
            type=type,
            tags=tags,
            metadata=metadata,
            capture_input=capture_input,
            ignore_arguments=ignore_arguments,
            capture_output=capture_output,
            generations_aggregator=generations_aggregator,
            flush=flush,
            project_name=project_name,
            create_duplicate_root_span=create_duplicate_root_span,
            source=source,
            environment=environment,
        )

        if callable(name):
            # 装饰器未使用 '()' 调用。这意味着被装饰的函数
            # 自动作为 'track' 函数的第一个参数 - name 传入
            func = name
            return self._decorate(
                func=func,
                track_options=track_options,
            )

        track_options.name = name

        def decorator(func: Callable) -> Callable:
            wrapped = self._decorate(
                func=func,
                track_options=track_options,
            )
            if entrypoint:
                _apply_entrypoint(func, wrapped, track_options)
            return wrapped

        return decorator

    def _decorate(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
    ) -> Callable:
        """
        跟踪策略:

            * 常规同步和异步函数/方法: 在函数调用时开始 span，
        函数结束时结束 span。在函数执行期间，span 保存在 opik 上下文中，
        因此可以作为嵌套跟踪函数创建的 span 的父级。

            * 生成器和异步生成器: 在生成器开始产出值时开始 span，
        生成器完成产出值时结束 trace。span 仅在 __next__ 或 __anext__ 方法
        执行期间保存在 opik 上下文中。这意味着 span 只能作为在 __next__
        或 __anext__ 内部调用的跟踪函数创建的 span 的父级。

            * 返回可被 `_streams_handler` 识别的流或流管理器对象的同步和异步函数:
        span 在函数调用时开始，在流数据块耗尽时结束。span 不保存在 opik 上下文中，
        因此这些 span 不能作为其他 span 的父级。这通常是 LLM API 调用中
        使用 `stream=True` 的情况。
        """
        # 幂等性: 如果已经跟踪过则跳过重复装饰
        if hasattr(func, "opik_tracked") and func.opik_tracked:  # type: ignore
            return func

        if inspect.isgeneratorfunction(func):
            return self._tracked_sync_generator(func=func, track_options=track_options)

        if inspect.isasyncgenfunction(func):
            return self._tracked_async_generator(
                func=func,
                track_options=track_options,
            )

        if inspect_helpers.is_async(func):
            return self._tracked_async(
                func=func,
                track_options=track_options,
            )

        return self._tracked_sync(
            func=func,
            track_options=track_options,
        )

    def _prepare_tracking_start_options(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> TrackingStartOptions:
        """
        准备跟踪启动选项，提取分布式跟踪头和 opik 参数。

        Args:
            func: 被跟踪的函数。
            track_options: 跟踪配置选项。
            args: 函数的位置参数。
            kwargs: 函数的关键字参数。

        Returns:
            TrackingStartOptions: 包含启动参数的选项对象。
        """
        # 提取分布式跟踪头信息
        opik_distributed_trace_headers = (
            arguments_helpers.extract_distributed_trace_headers(kwargs)
        )

        opik_args_ = None
        try:
            # 从关键字参数中提取 opik 特定参数
            opik_args_ = opik_args.extract_opik_args(kwargs, func)

            # 使用子类实现的预处理器生成 span 启动参数
            start_span_arguments = self._start_span_inputs_preprocessor(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            # 将 opik 参数应用到 span 启动参数
            start_span_arguments = opik_args.apply_opik_args_to_start_span_params(
                params=start_span_arguments,
                opik_args=opik_args_,
            )
        except Exception as exception:
            LOGGER.error(
                logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_CREATION_FOR_TRACKED_FUNCTION,
                inspect_helpers.get_function_name(func),
                (args, kwargs),
                str(exception),
                exc_info=True,
            )

            start_span_arguments = arguments_helpers.StartSpanParameters(
                name=inspect_helpers.get_function_name(func),
                type=track_options.type,
                tags=track_options.tags,
                metadata=track_options.metadata,
                project_name=track_options.project_name,
            )

        return TrackingStartOptions(
            start_span_arguments, opik_args_, opik_distributed_trace_headers
        )

    def _tracked_sync_generator(
        self, func: Callable, track_options: arguments_helpers.TrackOptions
    ) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            if not tracing_runtime_config.is_tracing_active():
                return func(*args, **kwargs)

            track_start_options = self._prepare_tracking_start_options(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            try:
                result = generator_wrappers.SyncTrackedGenerator(
                    func(*args, **kwargs),
                    start_span_arguments=track_start_options.start_span_parameters,
                    opik_distributed_trace_headers=track_start_options.opik_distributed_trace_headers,
                    track_options=track_options,
                    finally_callback=self._after_call,
                )
                return result
            except Exception as exception:
                LOGGER.debug(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    inspect_helpers.get_function_name(func),
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                raise exception

        wrapper.opik_tracked = True  # type: ignore

        return wrapper

    def _tracked_async_generator(
        self, func: Callable, track_options: arguments_helpers.TrackOptions
    ) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            if not tracing_runtime_config.is_tracing_active():
                return func(*args, **kwargs)

            track_start_options = self._prepare_tracking_start_options(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            try:
                result = generator_wrappers.AsyncTrackedGenerator(
                    func(*args, **kwargs),
                    start_span_arguments=track_start_options.start_span_parameters,
                    opik_distributed_trace_headers=track_start_options.opik_distributed_trace_headers,
                    track_options=track_options,
                    finally_callback=self._after_call,
                )
                return result
            except Exception as exception:
                LOGGER.debug(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    inspect_helpers.get_function_name(func),
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                raise exception

        wrapper.opik_tracked = True  # type: ignore

        return wrapper

    def _tracked_sync(
        self, func: Callable, track_options: arguments_helpers.TrackOptions
    ) -> Callable:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> Any:  # type: ignore
            if not tracing_runtime_config.is_tracing_active():
                return func(*args, **kwargs)
            should_process_span_data = self._before_call(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )

            result = None
            error_info: Optional[ErrorInfoDict] = None
            func_exception = None
            try:
                result = func(*args, **kwargs)
            except Exception as exception:
                LOGGER.debug(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    inspect_helpers.get_function_name(func),
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                func_exception = exception

            # 检查结果是否为流或流管理器对象
            stream_or_stream_manager = self._streams_handler(
                result,
                track_options.capture_output,
                track_options.generations_aggregator,
            )
            if stream_or_stream_manager is not None:
                return stream_or_stream_manager

            # 处理函数调用后的数据保存
            self._after_call(
                output=result,
                error_info=error_info,
                capture_output=track_options.capture_output,
                flush=track_options.flush,
                should_process_span_data=should_process_span_data,
            )
            if func_exception is not None:
                raise func_exception
            return result

        # 标记函数已被跟踪
        wrapper.opik_tracked = True  # type: ignore

        return wrapper

    def _tracked_async(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
    ) -> Callable:
        @functools.wraps(func)
        async def wrapper(*args, **kwargs) -> Any:  # type: ignore
            if not tracing_runtime_config.is_tracing_active():
                return await func(*args, **kwargs)
            should_process_span_data = self._before_call(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            )
            result = None
            error_info: Optional[ErrorInfoDict] = None
            func_exception = None
            try:
                result = await func(*args, **kwargs)
            except Exception as exception:
                LOGGER.debug(
                    logging_messages.EXCEPTION_RAISED_FROM_TRACKED_FUNCTION,
                    inspect_helpers.get_function_name(func),
                    (args, kwargs),
                    str(exception),
                    exc_info=True,
                )
                error_info = error_info_collector.collect(exception)
                func_exception = exception

            stream_or_stream_manager = self._streams_handler(
                result,
                track_options.capture_output,
                track_options.generations_aggregator,
            )
            if stream_or_stream_manager is not None:
                return stream_or_stream_manager

            self._after_call(
                output=result,
                error_info=error_info,
                capture_output=track_options.capture_output,
                flush=track_options.flush,
                should_process_span_data=should_process_span_data,
            )
            if func_exception is not None:
                raise func_exception
            return result

        wrapper.opik_tracked = True  # type: ignore
        return wrapper

    def _before_call(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> bool:
        """
        在函数调用前执行的准备工作。

        Returns:
            bool: 是否应处理 span 数据。
        """
        try:
            return self.__before_call_unsafe(
                func=func,
                track_options=track_options,
                args=args,
                kwargs=kwargs,
            ).should_process_span_data
        except Exception as exception:
            LOGGER.error(
                logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_CREATION_FOR_TRACKED_FUNCTION,
                inspect_helpers.get_function_name(func),
                (args, kwargs),
                str(exception),
                exc_info=True,
            )
        return False

    def __before_call_unsafe(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> span_creation_handler.SpanCreationResult:
        track_start_options = self._prepare_tracking_start_options(
            func=func,
            track_options=track_options,
            args=args,
            kwargs=kwargs,
        )

        return add_start_candidates(
            start_span_parameters=track_start_options.start_span_parameters,
            opik_distributed_trace_headers=track_start_options.opik_distributed_trace_headers,
            opik_args_data=track_start_options.opik_args,
            tracing_active=tracing_runtime_config.is_tracing_active(),
            create_duplicate_root_span=track_options.create_duplicate_root_span,
            source=track_options.source,
        )

    def _after_call(
        self,
        output: Optional[Any],
        error_info: Optional[ErrorInfoDict],
        capture_output: bool,
        generators_span_to_end: Optional[span.SpanData] = None,
        generators_trace_to_end: Optional[trace.TraceData] = None,
        flush: bool = False,
        should_process_span_data: bool = True,
    ) -> None:
        """
        在函数调用后执行的清理和数据保存工作。

        Args:
            output: 函数的输出结果。
            error_info: 错误信息（如果发生异常）。
            capture_output: 是否捕获输出。
            generators_span_to_end: 生成器的 span 数据（如果适用）。
            generators_trace_to_end: 生成器的 trace 数据（如果适用）。
            flush: 是否刷新客户端。
            should_process_span_data: 是否应处理 span 数据。
        """
        try:
            self.__after_call_unsafe(
                output=output,
                error_info=error_info,
                capture_output=capture_output,
                generators_span_to_end=generators_span_to_end,
                generators_trace_to_end=generators_trace_to_end,
                flush=flush,
                should_process_span_data=should_process_span_data,
            )
        except Exception as exception:
            LOGGER.error(
                logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_FINALIZATION_FOR_TRACKED_FUNCTION,
                output,
                str(exception),
                exc_info=True,
            )

    def __after_call_unsafe(
        self,
        output: Optional[Any],
        error_info: Optional[ErrorInfoDict],
        capture_output: bool,
        generators_span_to_end: Optional[span.SpanData],
        generators_trace_to_end: Optional[trace.TraceData],
        flush: bool,
        should_process_span_data: bool,
    ) -> None:
        span_data_to_end: Optional[span.SpanData] = None
        if generators_span_to_end is None:
            if should_process_span_data:
                # span 数据必须存在于上下文栈中，否则表示出现问题
                span_data_to_end, trace_data_to_end = pop_end_candidates()
            else:
                # span 数据不在上下文中，只有根 trace 数据存在
                trace_data_to_end = pop_end_candidate_trace_data()
        else:
            span_data_to_end, trace_data_to_end = (
                generators_span_to_end,
                generators_trace_to_end,
            )

        if output is not None:
            if should_process_span_data and span_data_to_end is not None:
                # 仅在适当时从当前 span 数据创建结束参数
                try:
                    end_arguments = self._end_span_inputs_preprocessor(
                        output=output,
                        capture_output=capture_output,
                        current_span_data=span_data_to_end,
                    )
                except Exception as e:
                    LOGGER.error(
                        logging_messages.UNEXPECTED_EXCEPTION_ON_SPAN_FINALIZATION_FOR_TRACKED_FUNCTION,
                        output,
                        str(e),
                        exc_info=True,
                    )

                    end_arguments = arguments_helpers.EndSpanParameters(
                        output={"output": output}
                    )
            else:
                # 直接使用输出作为结束参数
                end_arguments = arguments_helpers.EndSpanParameters(
                    output={"output": output}
                )
        else:
            end_arguments = arguments_helpers.EndSpanParameters(error_info=error_info)

        # 获取全局 Opik 客户端实例
        client = opik_client.get_global_client()

        if should_process_span_data and span_data_to_end is not None:
            # 仅在适当时保存 span 数据
            span_data_to_end.init_end_time().update(
                **end_arguments.to_kwargs(),
            )
            client.__internal_api__span__(**span_data_to_end.as_parameters)

        if trace_data_to_end is not None:
            trace_data_to_end.init_end_time().update(
                **end_arguments.to_kwargs(ignore_keys=["usage", "model", "provider"]),
            )

            client.__internal_api__trace__(**trace_data_to_end.as_parameters)

        if flush:
            client.flush()

    @abc.abstractmethod
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Any]:
        """
        子类必须重写此方法以自定义流式对象的处理。
        流对象通常是 LLM 提供商在使用 `stream=True` 选项调用其 API 时返回的对象。

        Opik 对此类流对象的处理方式是在 API 调用时开始 span，
        在流数据块耗尽时结束 span。
        """

        NO_STREAM_DETECTED = None

        return NO_STREAM_DETECTED

    @abc.abstractmethod
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        """
        子类必须重写此方法以自定义从函数输入参数
        生成 span/trace 参数的逻辑。
        """
        pass

    @abc.abstractmethod
    def _end_span_inputs_preprocessor(
        self,
        output: Optional[Any],
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        """
        子类必须重写此方法以自定义从函数返回值
        生成 span/trace 参数的逻辑。
        """
        pass


def _apply_entrypoint(
    original_func: Callable,
    wrapped_func: Callable,
    track_options: "arguments_helpers.TrackOptions",
) -> None:
    """应用入口点配置，将函数注册到运行器注册表中。"""
    agent_name = track_options.name or original_func.__name__
    agent_project = track_options.project_name or "default"
    params = registry.extract_params(original_func)
    docstring = inspect.getdoc(original_func) or ""

    registry.register(
        name=agent_name,
        func=wrapped_func,
        project=agent_project,
        params=params,
        docstring=docstring,
    )

    from ..runner.activate import activate_runner

    activate_runner()


def pop_end_candidates() -> Tuple[span.SpanData, Optional[trace.TraceData]]:
    """
    从当前上下文中弹出由 @track 装饰器创建的 span 和 trace（如果存在）数据，
    返回弹出的对象。

    装饰器无法向已弹出的对象附加任何子对象，因为它们已不在上下文栈中。
    """
    span_data_to_end = context_storage.pop_span_data()
    assert span_data_to_end is not None, (
        "When pop_end_candidates is called, top span data must not be None. Otherwise something is wrong."
    )

    context_storage.release_context_project_name_if_owner(span_data_to_end.id)

    trace_data_to_end = pop_end_candidate_trace_data()
    return span_data_to_end, trace_data_to_end


def pop_end_candidate_trace_data() -> Optional[trace.TraceData]:
    """
    如果满足特定条件，从栈中弹出最近创建的 trace 数据。

    此函数检查上下文存储的 span 数据栈是否为空，如果是，则尝试
    弹出并返回与上下文关联的最近创建的 trace 数据。只有当 trace 数据的 ID
    是使用装饰器创建的预定义 trace ID 集合的一部分时，才会被移除。
    如果不满足条件，则返回 None。

    注意: 装饰器无法向已弹出的对象附加任何子对象，因为它们已不在上下文栈中。

    Returns:
        如果满足条件，从栈中弹出的 trace 数据；否则返回 None。
    """
    possible_trace_data_to_end = context_storage.get_trace_data()
    if (
        context_storage.span_data_stack_empty()
        and possible_trace_data_to_end is not None
        and possible_trace_data_to_end.id in TRACES_CREATED_BY_DECORATOR
    ):
        trace_data_to_end = context_storage.pop_trace_data(
            ensure_id=possible_trace_data_to_end.id
        )
        TRACES_CREATED_BY_DECORATOR.discard(possible_trace_data_to_end.id)
        context_storage.release_context_project_name_if_owner(
            possible_trace_data_to_end.id
        )
        return trace_data_to_end

    return None


def _try_acquire_project_name(
    span_creation_result: span_creation_handler.SpanCreationResult,
) -> None:
    """尝试获取项目名称，将 span 或 trace 的项目名称注册到上下文中。"""
    if span_creation_result.should_process_span_data:
        span_data = span_creation_result.span_data
        if span_data.project_name is not None:
            context_storage.try_acquire_context_project_name(
                span_data.project_name, span_data.id
            )
    elif span_creation_result.trace_data is not None:
        trace_data = span_creation_result.trace_data
        if trace_data.project_name is not None:
            context_storage.try_acquire_context_project_name(
                trace_data.project_name, trace_data.id
            )


def add_start_candidates(
    start_span_parameters: arguments_helpers.StartSpanParameters,
    opik_distributed_trace_headers: Optional[DistributedTraceHeadersDict],
    opik_args_data: Optional[opik_args.OpikArgs],
    tracing_active: bool,
    create_duplicate_root_span: bool,
    source: Optional[TraceSource],
) -> span_creation_handler.SpanCreationResult:
    """
    处理新开始 span 和 trace 的创建与注册，同时根据给定参数
    遵循跟踪上下文。如果创建了 trace，还会应用相关参数，
    并在跟踪活动时处理客户端日志记录。

    Args:
        start_span_parameters: 用于启动 span 的参数，包括 span 名称和其他配置。
        opik_distributed_trace_headers: 可选的分布式跟踪头信息，传递给 span 创建过程。
        opik_args_data: 可选的附加参数，可在 span 创建后应用于 trace 数据。
        tracing_active: 布尔值，指示跟踪是否处于活动状态。
        create_duplicate_root_span: 布尔值，指示是否创建根 span 及其关联的根 trace 并复制其数据。
        source: trace 的来源，决定如何创建 trace。

    Returns:
        span 创建的结果，包含 span 和 trace 数据。
    """
    # 检查是否有预设的 trace ID
    preset_trace_id = None
    if opik_args_data and opik_args_data.trace_args and opik_args_data.trace_args.id:
        preset_trace_id = opik_args_data.trace_args.id

    # 根据上下文创建 span
    span_creation_result = span_creation_handler.create_span_respecting_context(
        start_span_arguments=start_span_parameters,
        distributed_trace_headers=opik_distributed_trace_headers,
        should_create_duplicate_root_span=create_duplicate_root_span,
        preset_trace_id=preset_trace_id,
        source=source,
    )
    if span_creation_result.should_process_span_data:
        # 将 span 数据添加到上下文存储
        context_storage.add_span_data(span_creation_result.span_data)

        if tracing_active:
            # 获取全局客户端并记录 span 开始事件
            client = opik_client.get_global_client()

            if client.config.log_start_trace_span:
                client.__internal_api__span__(
                    **span_creation_result.span_data.as_start_parameters
                )
    else:
        # 如果不应处理 span 数据，显示警告（如果需要）
        _show_root_span_not_created_warning_if_needed(
            start_span_parameters=start_span_parameters,
            tracing_active=tracing_active,
            should_process_span_data=span_creation_result.should_process_span_data,
        )

    if span_creation_result.trace_data is not None:
        add_start_trace_candidate(
            trace_data=span_creation_result.trace_data,
            opik_args_data=opik_args_data,
            tracing_active=tracing_active,
        )

    _try_acquire_project_name(span_creation_result)

    return span_creation_result


def add_start_trace_candidate(
    trace_data: trace.TraceData,
    opik_args_data: Optional[opik_args.OpikArgs],
    tracing_active: bool,
) -> None:
    """
    将开始 trace 候选项添加到当前上下文存储中，
    并在适用时使用给定的 Opik 参数进行更新。

    此函数在当前上下文中初始化 trace 数据并跟踪其创建。
    它还会将提供的 Opik 参数修改应用于 trace，
    并在跟踪活动且日志记录启用时在客户端记录开始 trace span。

    Args:
        trace_data: 要添加和初始化到当前上下文存储的 trace 数据对象，
            包含 trace 的详细信息。
        opik_args_data: 可选的 OpikArgs 对象，包含要应用于 trace 的附加数据。
            可能包括修改或丰富 trace 数据的配置。
        tracing_active: 布尔值，指示跟踪是否处于活动状态。
    """
    context_storage.set_trace_data(trace_data)
    TRACES_CREATED_BY_DECORATOR.add(trace_data.id)

    # 在 span/trace 创建后处理 thread_id 和 trace 更新
    opik_args.apply_opik_args_to_trace(opik_args=opik_args_data, trace_data=trace_data)

    if not tracing_active:
        return

    client = opik_client.get_global_client()
    if client.config.log_start_trace_span:
        client.__internal_api__trace__(**trace_data.as_start_parameters)


def _show_root_span_not_created_warning_if_needed(
    start_span_parameters: arguments_helpers.StartSpanParameters,
    tracing_active: bool,
    should_process_span_data: bool,
) -> None:
    """
    如果需要，显示根 span 未被创建的警告。

    当用户指定的 span 类型（如 'llm' 或 'tool'）因禁用根 trace 而丢失时，
    记录警告日志。

    Args:
        start_span_parameters: span 的启动参数。
        tracing_active: 跟踪是否处于活动状态。
        should_process_span_data: 是否应处理 span 数据。
    """
    if not tracing_active:
        return

    user_provided_span_type_will_be_lost = (
        not should_process_span_data and start_span_parameters.type in ["llm", "tool"]
    )
    if user_provided_span_type_will_be_lost:
        LOGGER.warning(
            "The root span '%s' of type '%s' will not be created because "
            "its creation was explicitly disabled along with the root trace.",
            start_span_parameters.name,
            start_span_parameters.type,
        )
