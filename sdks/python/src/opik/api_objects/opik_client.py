import contextvars
import copy
import datetime
import functools
import json
import logging
import threading
import weakref
from typing import (
    Any,
    Dict,
    List,
    Optional,
    TypeVar,
    Union,
    Literal,
    cast,
    overload,
)

from . import (
    connection_resources,
    constants,
    dashboard,
    dataset,
    experiment,
    optimization,
    helpers,
    opik_query_language,
    rest_helpers,
    rest_stream_parser,
    search_helpers,
    span as span_module,
    trace as trace_module,
)
from .dashboard import rest_operations as dashboard_rest_operations
from .dashboard import types as dashboard_types
from .dashboard import validation as dashboard_validation
from .annotation_queue import (
    TracesAnnotationQueue,
    ThreadsAnnotationQueue,
)
from .annotation_queue import rest_operations as annotation_queue_rest_operations
from .attachment import Attachment
from .attachment import client as attachment_client
from .attachment import converters as attachment_converters
from .dataset import test_suite
from .dataset import execution_policy as dataset_execution_policy
from .dataset import rest_operations as dataset_rest_operations
from .experiment import experiments_client
from .experiment import helpers as experiment_helpers
from .experiment import rest_operations as experiment_rest_operations
from . import prompt as prompt_module
from .prompt import client as prompt_client
from .prompt import prompt_cache
from .prompt.text import prompt as text_prompt_module
from .prompt.chat import chat_prompt as chat_prompt_module
from ..validation.chat_prompt_messages import ChatPromptMessagesValidator
from .agent_config.base import Config
from .agent_config.config import ConfigManager
from .threads import threads_client
from .trace import migration as trace_migration, trace_client
from .. import config as opik_config
from .. import (
    datetime_helpers,
    exceptions,
    httpx_client,
    id_helpers,
    llm_usage,
    url_helpers,
)
from ..message_processing import (
    messages,
)
from ..message_processing.batching import sequence_splitter
from ..rest_api import client as rest_api_client
from ..rest_api import errors as rest_api_errors
from ..rest_api.core.api_error import ApiError
from ..rest_api.types import (
    environment_public,
    project_public,
    span_public,
    trace_public,
    trace_thread,
    span_filter_public,
    trace_filter_public,
)
from ..types import (
    BatchAssertionResultDict,
    BatchFeedbackScoreDict,
    ErrorInfoDict,
    FeedbackScoreDict,
    LLMProvider,
    SpanType,
    TraceSource,
)
from .. import context_storage

LOGGER = logging.getLogger(__name__)

T = TypeVar("T")
_ConfigT = TypeVar("_ConfigT", bound=Config)
QueueT = TypeVar("QueueT", TracesAnnotationQueue, ThreadsAnnotationQueue)


class Opik:
    def __init__(
        self,
        project_name: Optional[str] = None,
        workspace: Optional[str] = None,
        host: Optional[str] = None,
        api_key: Optional[str] = None,
        batching: bool = True,
        _use_batching: bool = False,
        _show_misconfiguration_message: bool = True,
    ) -> None:
        """
        初始化一个Opik对象，用于手动向Opik服务器记录traces和spans。

        Args:
            project_name: 项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用`Default Project`。
            workspace: 工作区名称。如果未提供，将使用`default`。
            host: Opik服务器的主机URL。如果未提供，默认为`https://www.comet.com/opik/api`。
            api_key: Opik的API密钥。对于本地安装，此参数会被忽略。
            batching: 如果为True（默认），则启用请求批处理以提高吞吐量。
                启用后，更新操作（``update_span``、``update_trace``、
                ``Span.update``、``Trace.update``）可能会导致数据丢失，
                如果更新在批处理的创建请求刷新之前到达服务器。
            _use_batching: 已弃用。请使用``batching``代替。
            _show_misconfiguration_message: 仅用于特定条件下的内部使用。
                如果Opik服务器配置不当，打印警告消息。
        Returns:
            None
        """

        config_ = opik_config.get_from_user_inputs(
            project_name=project_name,
            workspace=workspace,
            url_override=host,
            api_key=api_key,
        )

        config_.check_for_known_misconfigurations(
            show_misconfiguration_message=_show_misconfiguration_message,
        )
        self._config = config_

        self._workspace: str = config_.workspace
        self._project_name: str = config_.project_name
        self._flush_timeout: Optional[int] = config_.default_flush_timeout
        self._project_name_most_recent_trace: Optional[str] = None
        self._use_batching = batching or _use_batching

        self._acquire_shared_resources()

    @property
    def config(self) -> opik_config.OpikConfig:
        """
        Returns:
            OpikConfig: Opik客户端配置的只读副本。
        """
        return self._config.model_copy()

    @property
    def rest_client(self) -> rest_api_client.OpikApi:
        """
        提供对底层REST API客户端的直接访问。

        警告：此客户端不保证与未来SDK版本向后兼容。
        虽然它提供了使用当前Opik REST API的便捷方式，
        但不建议过度依赖其API，因为Opik的REST API合约可能会更改。

        Returns:
            OpikApi: Opik客户端使用的REST客户端。
        """
        return self._rest_client

    @property
    def project_name(self) -> str:
        """
        此属性获取与实例关联的项目名称。
        这是一个只读属性。

        Returns:
            str: 项目名称。
        """
        return self._project_name

    def _acquire_shared_resources(self) -> None:
        self._lease = connection_resources.MANAGER.acquire(
            self._config,
            use_batching=self._use_batching,
        )
        self._resources = self._lease.resources
        self._bind_resources()

        # ``self._lease.release`` is a bound method of the lease, so the finalizer
        # captures the lease (and through it the manager), never ``self``. A
        # dropped handle therefore releases its reference on GC without the
        # atexit strong-ref pin that caused OPIK-7127.
        #
        # ``close_on_zero=False``: a finalizer must do nothing risky, so the GC
        # path only decrements the bundle's refcount. It never closes (thread
        # joins, network flush) — that is left to an explicit ``end()`` or to the
        # atexit ``close_all``.
        self._finalizer = weakref.finalize(
            self, self._lease.release, self._flush_timeout, close_on_zero=False
        )

    def _bind_resources(self) -> None:
        # Expose the bundle's objects as attributes so the rest of the client
        # (and external callers of ``__internal_api__message_processor__``)
        # delegate to the shared connection resources unchanged.
        self._httpx_client = self._resources.httpx_client
        self._rest_client = self._resources.rest_client
        self.__internal_api__message_processor__ = self._resources.message_processor
        self._streamer = self._resources.streamer

    def _display_trace_url(self, trace_id: str, project_name: str) -> None:
        project_url = url_helpers.get_project_url_by_trace_id(
            trace_id=trace_id,
            url_override=self._config.url_override,
        )
        if (
            self._project_name_most_recent_trace is None
            or self._project_name_most_recent_trace != project_name
        ):
            LOGGER.info(
                f'Started logging traces to the "{project_name}" project at {project_url}.'
            )
            self._project_name_most_recent_trace = project_name

    def _display_created_dataset_url(self, dataset_name: str, dataset_id: str) -> None:
        dataset_url = url_helpers.get_dataset_url_by_id(
            dataset_id, self._config.url_override
        )

        LOGGER.info(f'Created a "{dataset_name}" dataset at {dataset_url}.')

    def auth_check(self) -> None:
        """
        检查当前API密钥用户是否有权访问配置的工作区及其内容。
        """
        self._rest_client.check.access(
            request={}  # 空请求体，为未来向后兼容性保留
        )

    def trace(
        self,
        id: Optional[str] = None,
        name: Optional[str] = None,
        start_time: Optional[datetime.datetime] = None,
        end_time: Optional[datetime.datetime] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        feedback_scores: Optional[List[FeedbackScoreDict]] = None,
        project_name: Optional[str] = None,
        error_info: Optional[ErrorInfoDict] = None,
        thread_id: Optional[str] = None,
        attachments: Optional[List[Attachment]] = None,
        environment: Optional[str] = None,
        **ignored_kwargs: Any,
    ) -> trace_module.Trace:
        """
        创建并记录一个新的trace。

        Args:
            id: trace的唯一标识符，如果未提供，将生成新的ID。必须是有效的[UUIDv7](https://uuid7.com/) ID。
            name: trace的名称。
            start_time: trace的开始时间。如果未提供，将使用当前本地时间。
            end_time: trace的结束时间。
            input: trace的输入数据。可以是任何有效的JSON可序列化对象。
            output: trace的输出数据。可以是任何有效的JSON可序列化对象。
            metadata: trace的附加元数据。可以是任何有效的JSON可序列化对象。
            tags: 与trace关联的标签。
            feedback_scores: 与trace关联的反馈分数字典列表。字典不需要包含`id`值。
            project_name: 项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            error_info: 包含错误信息的字典（通常在trace函数失败时使用）。
            thread_id: 用于将多个trace分组到一个线程中。
                标识符由用户定义，在每个项目中必须唯一。
            attachments: 要上传到trace的附件列表。

        Returns:
            trace.Trace: 创建的trace对象。
        """
        return self.__internal_api__trace__(
            id=id,
            name=name,
            start_time=start_time,
            end_time=end_time,
            input=input,
            output=output,
            metadata=metadata,
            tags=tags,
            feedback_scores=feedback_scores,
            project_name=project_name,
            error_info=error_info,
            thread_id=thread_id,
            attachments=attachments,
            source="sdk",
            environment=environment,
        )

    def __internal_api__trace__(
        self,
        id: Optional[str] = None,
        name: Optional[str] = None,
        start_time: Optional[datetime.datetime] = None,
        end_time: Optional[datetime.datetime] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        metadata: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        feedback_scores: Optional[List[FeedbackScoreDict]] = None,
        project_name: Optional[str] = None,
        error_info: Optional[ErrorInfoDict] = None,
        thread_id: Optional[str] = None,
        attachments: Optional[List[Attachment]] = None,
        source: TraceSource = "sdk",
        environment: Optional[str] = None,
        **ignored_kwargs: Any,
    ) -> trace_module.Trace:
        id = id if id is not None else id_helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )
        last_updated_at = datetime_helpers.local_timestamp()

        project_name = self._resolve_project_name(project_name)
        if environment is None:
            environment = self._config.environment

        create_trace_message = messages.CreateTraceMessage(
            trace_id=id,
            project_name=project_name,
            name=name,
            start_time=start_time,
            end_time=end_time,
            input=input,
            output=output,
            metadata=metadata,
            tags=tags,
            error_info=error_info,
            thread_id=thread_id,
            last_updated_at=last_updated_at,
            source=source,
            environment=environment,
        )
        self._streamer.put(create_trace_message)
        self._display_trace_url(trace_id=id, project_name=project_name)

        if feedback_scores is not None:
            for feedback_score in feedback_scores:
                feedback_score["id"] = id

            self.log_traces_feedback_scores(
                cast(List[BatchFeedbackScoreDict], feedback_scores), project_name
            )

        if attachments is not None:
            for attachment_data in attachments:
                self._streamer.put(
                    attachment_converters.attachment_to_message(
                        attachment_data=attachment_data,
                        entity_type="trace",
                        entity_id=id,
                        project_name=project_name,
                        url_override=self._config.url_override,
                    )
                )

        return trace_module.Trace(
            id=id,
            message_streamer=self._streamer,
            project_name=project_name,
            url_override=self._config.url_override,
            source=source,
            config=self._config,
            environment=environment,
        )

    def copy_traces(
        self,
        project_name: str,
        destination_project_name: str,
        delete_original_project: bool = False,
    ) -> None:
        """
        将traces从一个项目复制到另一个项目。此方法将源项目中的所有traces
        复制到目标项目。可选地，您也可以从源项目中删除这些traces。

        在复制traces时，traces和spans的ID都将在复制过程中更新。

        注意：此方法未针对大型项目进行优化，如果遇到任何问题，请在GitHub上
        提交issue。此外，请注意删除链接到实验的traces将导致UI中的不一致。

        Args:
            project_name: 要复制traces的源项目名称。
            destination_project_name: 要复制traces的目标项目名称。
            delete_original_project: 是否删除原始项目。默认为False。

        Returns:
            None
        """

        if not self._use_batching:
            raise exceptions.OpikException(
                "In order to use this method, you must enable batching using opik.Opik(batching=True)."
            )

        traces_public = self.search_traces(project_name=project_name)
        spans_public = self.search_spans(project_name=project_name)

        trace_data = [
            trace_module.trace_public_to_trace_data(
                project_name=project_name, trace_public=trace_public_
            )
            for trace_public_ in traces_public
        ]
        span_data = [
            span_module.span_public_to_span_data(
                project_name=project_name, span_public_=span_public_
            )
            for span_public_ in spans_public
        ]

        new_trace_data, new_span_data = (
            trace_migration.prepare_traces_and_spans_for_copy(
                destination_project_name, trace_data, span_data
            )
        )

        for trace_data_ in new_trace_data:
            self.__internal_api__trace__(**trace_data_.as_parameters)

        for span_data_ in new_span_data:
            self.__internal_api__span__(**span_data_.as_parameters)

        if delete_original_project:
            trace_ids = [trace_.id for trace_ in trace_data]
            for batch in sequence_splitter.split_into_batches(
                trace_ids,
                max_length=constants.DELETE_TRACE_BATCH_SIZE,
            ):
                self._rest_client.traces.delete_traces(ids=batch)

    def span(
        self,
        trace_id: Optional[str] = None,
        id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        name: Optional[str] = None,
        type: SpanType = "general",
        start_time: Optional[datetime.datetime] = None,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None,
        feedback_scores: Optional[List[FeedbackScoreDict]] = None,
        project_name: Optional[str] = None,
        model: Optional[str] = None,
        provider: Optional[Union[str, LLMProvider]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
        attachments: Optional[List[Attachment]] = None,
    ) -> span_module.Span:
        """
        创建并记录一个新的span。

        Args:
            trace_id: trace的唯一标识符。如果未提供，将生成新的ID。必须是有效的[UUIDv7](https://uuid7.com/) ID。
            id: span的唯一标识符。如果未提供，将生成新的ID。必须是有效的[UUIDv7](https://uuid.ramsey.dev/en/stable/rfc4122/version8.html) ID。
            parent_span_id: 父span的唯一标识符。
            name: span的名称。
            type: span的类型。默认为"general"。
            start_time: span的开始时间。如果未提供，将使用当前本地时间。
            end_time: span的结束时间。
            metadata: span的附加元数据。可以是任何有效的JSON可序列化对象。
            input: span的输入数据。可以是任何有效的JSON可序列化对象。
            output: span的输出数据。可以是任何有效的JSON可序列化对象。
            tags: 与span关联的标签。
            feedback_scores: 与span关联的反馈分数字典列表。字典不需要包含`id`值。
            project_name: 项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            usage: span的使用数据。为了在UI中显示输入、输出和总token数，
                usage必须包含OpenAI格式的键（可以在字典顶层额外传递）：
                prompt_tokens、completion_tokens和total_tokens。
                如果未找到OpenAI格式的键，Opik将尝试自动计算（如果识别出使用格式），
                但不保证成功。可以在opik.LLMProvider枚举中查看支持的提供商格式。
            model: LLM的名称（在这种情况下`type`参数应为== `llm`）
            provider: LLM的提供商。可以在`opik.LLMProvider`枚举中找到Opik官方支持的成本跟踪提供商。
                如果您的提供商不在其中，请在我们的GitHub上提交issue - https://github.com/comet-ml/opik。
                如果您的提供商不在列表中，仍然可以指定它，但成本跟踪将不可用。
            error_info: 包含错误信息的字典（通常在span函数失败时使用）。
            total_cost: span的成本（以美元为单位）。此值优先于Opik根据使用情况计算的成本。
            attachments: 要上传到span的附件列表。

        Returns:
            span.Span: 创建的span对象。
        """
        return self.__internal_api__span__(
            trace_id=trace_id,
            id=id,
            parent_span_id=parent_span_id,
            name=name,
            type=type,
            start_time=start_time,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            usage=usage,
            feedback_scores=feedback_scores,
            project_name=project_name,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            attachments=attachments,
            source="sdk",
        )

    def __internal_api__span__(
        self,
        trace_id: Optional[str] = None,
        id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        name: Optional[str] = None,
        type: SpanType = "general",
        start_time: Optional[datetime.datetime] = None,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None,
        feedback_scores: Optional[List[FeedbackScoreDict]] = None,
        project_name: Optional[str] = None,
        model: Optional[str] = None,
        provider: Optional[Union[str, LLMProvider]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
        attachments: Optional[List[Attachment]] = None,
        source: TraceSource = "sdk",
        environment: Optional[str] = None,
    ) -> span_module.Span:
        id = id if id is not None else id_helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )

        project_name = self._resolve_project_name(project_name)
        if environment is None:
            environment = self._config.environment

        if trace_id is None:
            trace_id = id_helpers.generate_id()
            # TODO: 决定需要传递什么给CreateTraceMessage。
            # 此版本可能不是最终版本。
            create_trace_message = messages.CreateTraceMessage(
                trace_id=trace_id,
                project_name=project_name,
                name=name,
                start_time=start_time,
                end_time=end_time,
                input=input,
                output=output,
                metadata=metadata,
                tags=tags,
                error_info=error_info,
                thread_id=None,
                last_updated_at=datetime_helpers.local_timestamp(),
                source=source,
                environment=environment,
            )
            self._streamer.put(create_trace_message)

        if feedback_scores is not None:
            for feedback_score in feedback_scores:
                feedback_score["id"] = id

            self.log_spans_feedback_scores(
                cast(List[BatchFeedbackScoreDict], feedback_scores), project_name
            )

        return span_module.span_client.create_span(
            trace_id=trace_id,
            project_name=project_name,
            url_override=self._config.url_override,
            message_streamer=self._streamer,
            span_id=id,
            parent_span_id=parent_span_id,
            name=name,
            type=type,
            start_time=start_time,
            end_time=end_time,
            input=input,
            output=output,
            metadata=metadata,
            tags=tags,
            usage=usage,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            attachments=attachments,
            source=source,
            config=self._config,
            environment=environment,
        )

    def update_span(
        self,
        id: str,
        trace_id: str,
        parent_span_id: Optional[str],
        project_name: str,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None,
        model: Optional[str] = None,
        provider: Optional[Union[LLMProvider, str]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
        attachments: Optional[List[Attachment]] = None,
    ) -> None:
        """
        更新现有span的属性。

        此方法应在span完全创建和存储后使用。
        如果在span创建之前或之后立即调用，更新可能会静默失败或导致数据不正确。

        此方法使用四个参数来标识span：
            - `id`
            - `trace_id`
            - `parent_span_id`
            - `project_name`

        这些参数**必须完全匹配**创建span时使用的值。
        如果其中任何一个不正确，更新可能不会应用且不会引发错误。

        所有其他参数都是可选的，将更新span中的相应字段。
        如果未提供参数，现有值将保持不变。

        Args:
            id: 要更新的span的唯一标识符。
            trace_id: span所属trace的唯一标识符。
            parent_span_id: 父span的唯一标识符。
            project_name: span所属的项目名称。
            end_time: span的新结束时间。
            metadata: 与span关联的新元数据。
            input: span的新输入数据。
            output: span的新输出数据。
            tags: 与span关联的新标签列表。
            usage: span的新使用数据。为了在UI中显示输入、输出和总token数，
                usage必须包含OpenAI格式的键（可以在字典顶层额外传递）：
                prompt_tokens、completion_tokens和total_tokens。
                如果未找到OpenAI格式的键，Opik将尝试自动计算（如果识别出使用格式），
                但不保证成功。可以在opik.LLMProvider枚举中查看支持的提供商格式。
            model: LLM的新名称。
            provider: LLM的新提供商。可以在`opik.LLMProvider`枚举中找到Opik官方支持的成本跟踪提供商。
                如果您的提供商不在其中，请在我们的GitHub上提交issue - https://github.com/comet-ml/opik。
                如果您的提供商不在列表中，仍然可以指定它，但成本跟踪将不可用。
            error_info: 包含错误信息的新字典（通常在span函数失败时使用）。
            total_cost: span的新成本（以美元为单位）。此值优先于Opik根据使用情况计算的成本。
            attachments: 要上传到span的新附件列表。

        Returns:
            None
        """
        helpers.warn_if_batching_update(
            use_batching=self._use_batching,
            suppress_warning=self._config.suppress_batching_update_warning,
            method_name="Opik.update_span()",
        )

        span_module.span_client.update_span(
            id=id,
            trace_id=trace_id,
            parent_span_id=parent_span_id,
            url_override=self._config.url_override,
            message_streamer=self._streamer,
            project_name=project_name,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            usage=usage,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            attachments=attachments,
            source="sdk",
        )

    def update_trace(
        self,
        trace_id: str,
        project_name: str,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[Any]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        thread_id: Optional[str] = None,
    ) -> None:
        """
        更新trace属性。

        此方法应在trace完全创建和存储后使用。
        如果在trace创建之前或之后立即调用，更新可能会静默失败或导致数据不正确。

        此方法使用两个参数来标识trace：
            - `trace_id`
            - `project_name`

        这些参数**必须完全匹配**创建trace时使用的值。
        如果其中任何一个不正确，更新可能不会应用且不会引发错误。

        所有其他参数都是可选的，将更新trace中的相应字段。
        如果未提供参数，现有值将保持不变。

        Args:
            trace_id: trace的唯一标识符。
            project_name: trace所属的项目名称。
            end_time: trace的结束时间。
            metadata: 与trace关联的附加元数据。
            input: trace的输入数据。
            output: trace的输出数据。
            tags: 与trace关联的标签列表。
            error_info: 包含错误信息的字典（通常在trace函数失败时使用）。
            thread_id: 用于将多个trace分组到一个线程中。
                标识符由用户定义，在每个项目中必须唯一。

        Returns:
            None
        """
        helpers.warn_if_batching_update(
            use_batching=self._use_batching,
            suppress_warning=self._config.suppress_batching_update_warning,
            method_name="Opik.update_trace()",
        )

        if not trace_id or not project_name:
            raise ValueError(
                "trace_id and project_name must be provided and can not be None or empty, "
                f"trace_id: {trace_id}, project_name: {project_name}"
            )

        trace_client.update_trace(
            trace_id=trace_id,
            project_name=project_name,
            message_streamer=self._streamer,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            error_info=error_info,
            thread_id=thread_id,
            source="sdk",
        )

    def log_spans_feedback_scores(
        self, scores: List[BatchFeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        """
        记录spans的反馈分数。

        Args:
            scores (List[BatchFeedbackScoreDict]): 反馈分数字典列表。
                必须通过`id`键为每个分数指定span id。
            project_name: 记录spans的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
                已弃用：请在`scores`参数中列出的反馈分数字典中使用`project_name`。

        Returns:
            None

        Example:
            >>> from opik import Opik
            >>> client = Opik()
            >>> # 跨多个项目批量记录
            >>> scores = [
            >>>     {"id": span1_id, "name": "accuracy", "value": 0.95, "project_name": "project-A"},
            >>>     {"id": span2_id, "name": "accuracy", "value": 0.88, "project_name": "project-B"},
            >>> ]
            >>> client.log_spans_feedback_scores(scores=scores)
        """
        score_messages = helpers.parse_feedback_score_messages(
            scores=scores,
            project_name=self._resolve_project_name(project_name),
            parsed_item_class=messages.FeedbackScoreMessage,
            logger=LOGGER,
        )
        if score_messages is None:
            LOGGER.error(
                f"No valid spans feedback scores to log from provided ones: {scores}"
            )
            return

        for batch in sequence_splitter.split_into_batches(
            score_messages,
            max_payload_size_MB=opik_config.MAX_BATCH_SIZE_MB,
            max_length=constants.FEEDBACK_SCORES_MAX_BATCH_SIZE,
        ):
            add_span_feedback_scores_batch_message = (
                messages.AddSpanFeedbackScoresBatchMessage(batch=batch)
            )

            self._streamer.put(add_span_feedback_scores_batch_message)

    def log_traces_feedback_scores(
        self, scores: List[BatchFeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        """
        记录traces的反馈分数。

        Args:
            scores (List[BatchFeedbackScoreDict]): 反馈分数字典列表。
                必须通过`id`键为每个分数指定trace id。
            project_name: 记录traces的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
                已弃用：请在`scores`参数中列出的反馈分数字典中使用`project_name`。

        Returns:
            None

        Example:
            >>> from opik import Opik
            >>> client = Opik()
            >>> # 跨多个项目批量记录
            >>> scores = [
            >>>     {"id": trace1_id, "name": "accuracy", "value": 0.95, "project_name": "project-A"},
            >>>     {"id": trace2_id, "name": "accuracy", "value": 0.88, "project_name": "project-B"},
            >>> ]
            >>> client.log_traces_feedback_scores(scores=scores)
        """
        score_messages = helpers.parse_feedback_score_messages(
            scores=scores,
            project_name=self._resolve_project_name(project_name),
            parsed_item_class=messages.FeedbackScoreMessage,
            logger=LOGGER,
        )

        if score_messages is None:
            LOGGER.error(
                f"No valid traces feedback scores to log from provided ones: {scores}"
            )
            return

        for batch in sequence_splitter.split_into_batches(
            score_messages,
            max_payload_size_MB=opik_config.MAX_BATCH_SIZE_MB,
            max_length=constants.FEEDBACK_SCORES_MAX_BATCH_SIZE,
        ):
            add_trace_feedback_scores_batch_message = (
                messages.AddTraceFeedbackScoresBatchMessage(batch=batch)
            )

            self._streamer.put(add_trace_feedback_scores_batch_message)

    def log_assertion_results(
        self,
        assertion_results: List[BatchAssertionResultDict],
        project_name: Optional[str] = None,
    ) -> None:
        """
        通过专用的断言结果摄取端点记录traces的断言结果。

        Args:
            assertion_results: 断言结果字典列表。每个条目需要`id`（trace id）、
                `name`和`status`（"passed"或"failed"）。
            project_name: traces所属的项目。如果未提供，则回退到活动项目上下文，
                然后使用客户端默认值。
        """
        resolved_project_name = self._resolve_project_name(project_name)

        valid_items = []
        for item in assertion_results:
            if not (item.get("id") and item.get("name")):
                continue
            if item.get("status") not in ("passed", "failed"):
                LOGGER.error(
                    "Skipping assertion result with invalid status %r — "
                    "must be 'passed' or 'failed': %s",
                    item.get("status"),
                    item,
                )
                continue
            valid_items.append(item)

        if len(valid_items) == 0:
            LOGGER.error(
                f"No valid assertion results to log from provided ones: {assertion_results}"
            )
            return

        assertion_messages = [
            messages.AssertionResultMessage(
                entity_id=item["id"],
                project_name=item.get("project_name") or resolved_project_name,
                name=item["name"],
                status=item["status"],
                reason=item.get("reason"),
                source="sdk",
            )
            for item in valid_items
        ]

        for batch in sequence_splitter.split_into_batches(
            assertion_messages,
            max_payload_size_MB=opik_config.MAX_BATCH_SIZE_MB,
            max_length=constants.FEEDBACK_SCORES_MAX_BATCH_SIZE,
        ):
            self._streamer.put(messages.AddAssertionResultsBatchMessage(batch=batch))

    def log_threads_feedback_scores(
        self, scores: List[BatchFeedbackScoreDict], project_name: Optional[str] = None
    ) -> None:
        """
        记录线程的反馈分数。

        Args:
            scores (List[BatchFeedbackScoreDict]): 反馈分数字典列表。
                必须通过`id`键为每个分数指定线程id。
            project_name: 记录线程的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
                已弃用：请在`scores`参数中列出的反馈分数字典中使用`project_name`。

        Returns:
            None

        Example:
            >>> from opik import Opik
            >>> client = Opik()
            >>> # 跨多个项目批量记录
            >>> scores = [
            >>>     {"id": "thread_123", "name": "user_satisfaction", "value": 0.85, "project_name": "project-A"},
            >>>     {"id": "thread_456", "name": "user_satisfaction", "value": 0.92, "project_name": "project-B"},
            >>> ]
            >>> client.log_threads_feedback_scores(scores=scores)
        """
        self.get_threads_client().log_threads_feedback_scores(
            scores=scores, project_name=project_name
        )

    def search_threads(
        self,
        project_name: Optional[str] = None,
        filter_string: Optional[str] = None,
        max_results: int = 1000,
        truncate: bool = True,
    ) -> List[trace_thread.TraceThread]:
        """
        根据特定条件在给定项目中搜索线程。

        Args:
            project_name: 要搜索线程的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            filter_string: 使用Opik查询语言（OQL）缩小搜索范围的过滤器字符串。
                格式为：`"<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"`

                支持的列：
                - `id`: 字符串 (=, !=, contains, not_contains, starts_with, ends_with, >, <)
                - `first_message`, `last_message`: 字符串 (=, !=, contains, not_contains, starts_with, ends_with, >, <)
                - `environment`: 生命周期阶段枚举 (=, !=, in, not_in)
                - `status`: 枚举 (=, !=)
                - `start_time`, `end_time`, `created_at`, `last_updated_at`: 日期时间 (=, !=, >, >=, <, <=)
                - `feedback_scores`: 数值，使用点表示法 (=, !=, >, >=, <, <=, is_empty, is_not_empty)
                - `tags`, `annotation_queue_ids`: 列表 (=, !=, contains, not_contains, is_empty, is_not_empty)
                - `duration`, `number_of_messages`: 数值 (=, !=, >, >=, <, <=)

                示例：
                - `status = "active"` - 按线程状态过滤
                - `id = "thread_123"` - 按特定线程ID过滤
                - `number_of_messages >= 5` - 按消息数量过滤
                - `first_message contains "hello"` - 按首条消息内容过滤
                - `feedback_scores.user_frustration > 0.5` - 按反馈分数过滤
                - `tags contains "important"` - 按标签过滤
                - `environment = "production"` - 按环境过滤
                - `environment in ("production", "staging")` - 按多个环境过滤

                如果未提供，将返回项目中的所有线程，最多返回限制数量。
            max_results: 要检索的最大线程数。默认值为1000。
            truncate: 是否截取存储在输入、输出或元数据中的图像数据。

        Returns:
            与搜索条件匹配的TraceThread对象列表。

        Example:
            >>> from opik import Opik
            >>> client = Opik()
            >>> threads = client.search_threads(
            >>>     project_name="Demo Project",
            >>>     filter_string='id = "thread_123"',
            >>>     max_results=10,
            >>> )
        """
        return self.get_threads_client().search_threads(
            project_name=project_name,
            filter_string=filter_string,
            max_results=max_results,
            truncate=truncate,
        )

    def delete_trace_feedback_score(self, trace_id: str, name: str) -> None:
        """
        删除与特定trace关联的反馈分数。

        Args:
            trace_id:
                需要删除反馈分数的trace的唯一标识符。
            name: str
                与要删除的反馈分数关联的名称。

        Returns:
            None
        """
        self._rest_client.traces.delete_trace_feedback_score(
            id=trace_id,
            name=name,
        )

    def delete_span_feedback_score(self, span_id: str, name: str) -> None:
        """
        删除与特定span关联的反馈分数。

        Args:
            span_id:
                需要删除反馈分数的span的唯一标识符。
            name: str
                与要删除的反馈分数关联的名称。

        Returns:
            None
        """
        self._rest_client.spans.delete_span_feedback_score(
            id=span_id,
            name=name,
        )

    def create_environment(
        self,
        name: str,
        description: Optional[str] = None,
        color: Optional[str] = None,
    ) -> environment_public.EnvironmentPublic:
        """
        在当前工作区中创建新环境。

        Args:
            name: 人类可读的环境名称（例如``production``）。
            description: 可选描述。
            color: 可选的颜色十六进制代码，用于UI显示。

        Returns:
            创建的环境。
        """
        new_id = id_helpers.generate_id()
        try:
            self._rest_client.environments.create_environment(
                id=new_id,
                name=name,
                description=description,
                color=color,
            )
        except rest_api_errors.ConflictError:
            raise exceptions.EnvironmentAlreadyExists(
                f"Environment {name!r} already exists in this workspace."
            )
        return self._rest_client.environments.get_environment_by_id(new_id)

    def get_environments(self) -> List[environment_public.EnvironmentPublic]:
        """
        列出当前工作区中的环境。

        后端将响应限制在工作区限制内（默认20个）。
        """
        page = self._rest_client.environments.find_environments()
        return list(page.content or [])

    _BUILTIN_ENVIRONMENT_NAMES = frozenset({"production", "staging", "development"})

    def update_environment(
        self,
        name: str,
        description: Optional[str] = None,
        color: Optional[str] = None,
    ) -> environment_public.EnvironmentPublic:
        """
        通过名称标识环境，更新其描述和/或颜色。

        返回更新后的环境。
        """
        if color is not None and name in self._BUILTIN_ENVIRONMENT_NAMES:
            raise exceptions.EnvironmentConfigurationError(
                f"Cannot change the colour of the built-in environment {name!r}. "
                "Colour updates are not allowed for 'production', 'staging', or 'development'."
            )
        existing = self._find_environment_by_name(name)
        if existing is None:
            raise exceptions.OpikException(f"No environment found with name {name!r}.")
        self._rest_client.environments.update_environment(
            existing.id,
            description=description,
            color=color,
        )
        return self._rest_client.environments.get_environment_by_id(existing.id)

    def delete_environment(self, name: str) -> None:
        """
        按名称删除环境。如果不存在匹配的环境，则不执行任何操作。
        """
        existing = self._find_environment_by_name(name)
        if existing is None:
            return
        self._rest_client.environments.delete_environments_batch(ids=[existing.id])

    def _find_environment_by_name(
        self, name: str
    ) -> Optional[environment_public.EnvironmentPublic]:
        for env in self.get_environments():
            if env.name == name:
                return env
        return None

    def get_dataset(
        self, name: str, project_name: Optional[str] = None
    ) -> dataset.Dataset:
        """
        按名称获取数据集。

        Args:
            name: 数据集的名称。
            project_name: 数据集所属的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。

        Returns:
            dataset.Dataset: 与传入名称关联的数据集对象。
        """
        project_name = self._resolve_project_name(project_name)
        dataset_fern = self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=name, project_name=project_name
        )

        return dataset.Dataset.from_public(
            dataset_fern=dataset_fern,
            project_name=project_name,
            rest_client=self._rest_client,
            client=self,
        )

    def get_datasets(
        self,
        max_results: int = 100,
        sync_items: bool = False,
        project_name: Optional[str] = None,
    ) -> List[dataset.Dataset]:
        """
        返回所有数据集，最多返回指定限制数量。

        Args:
            project_name: 数据集所属的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            max_results: 要返回的最大数据集数量。
            sync_items: 如果为True，则为每个返回的数据集预先加载项目哈希——
                每个数据集一次REST往返。默认为False：哈希在首次``dataset.insert(...)``
                调用时懒加载，因此仅检查元数据的调用者无需支付任何代价，
                而插入数据的调用者仍然可以正确获得内容哈希去重。

        Returns:
            List[dataset.Dataset]: 与过滤器字符串匹配的数据集对象列表。
        """
        datasets = dataset_rest_operations.get_datasets(
            project_name=self._resolve_project_name(project_name),
            rest_client=self._rest_client,
            max_results=max_results,
            sync_items=sync_items,
        )

        return datasets

    def get_dataset_experiments(
        self,
        dataset_name: str,
        max_results: int = 100,
        project_name: Optional[str] = None,
    ) -> List[experiment.Experiment]:
        """
        返回所有实验，最多返回指定限制数量。

        Args:
            dataset_name: 数据集的名称。
            max_results: 要返回的最大实验数量。
            project_name: 数据集所属的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。

        Returns:
            List[experiment.Experiment]: 实验对象列表。
        """
        project_name = self._resolve_project_name(project_name)
        dataset_id = dataset_rest_operations.get_dataset_id(
            self._rest_client, dataset_name=dataset_name, project_name=project_name
        )

        experiments_client = self.get_experiments_client()
        experiments = dataset_rest_operations.get_dataset_experiments(
            rest_client=self._rest_client,
            dataset_id=dataset_id,
            max_results=max_results,
            streamer=self._streamer,
            experiments_client=experiments_client,
        )

        return experiments

    def delete_dataset(self, name: str, project_name: Optional[str] = None) -> None:
        """
        按名称删除数据集。

        Args:
            name: 数据集的名称。
            project_name: 数据集所属的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
        """
        project_name = self._resolve_project_name(project_name)
        self._rest_client.datasets.delete_dataset_by_name(
            dataset_name=name, project_name=project_name
        )

    def create_dataset(
        self,
        name: str,
        description: Optional[str] = None,
        project_name: Optional[str] = None,
    ) -> dataset.Dataset:
        """
        创建新数据集。

        Args:
            name: 数据集的名称。
            description: 数据集的可选描述。
            project_name: 数据集所属的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。

        Returns:
            dataset.Dataset: 创建的数据集对象。
        """
        project_name = self._resolve_project_name(project_name)
        self._rest_client.datasets.create_dataset(
            name=name,
            description=description,
            project_name=project_name,
        )

        result = dataset.Dataset(
            name=name,
            description=description,
            project_name=project_name,
            rest_client=self._rest_client,
            dataset_items_count=0,
            client=self,
        )

        self._display_created_dataset_url(dataset_name=name, dataset_id=result.id)

        return result

    def get_or_create_dataset(
        self,
        name: str,
        description: Optional[str] = None,
        project_name: Optional[str] = None,
    ) -> dataset.Dataset:
        """
        按名称获取现有数据集，如果不存在则创建新数据集。

        Args:
            name: 数据集的名称。
            description: 数据集的可选描述。
            project_name: 数据集所属的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。

        Returns:
            dataset.Dataset: 数据集对象。
        """
        try:
            return self.get_dataset(name, project_name=project_name)
        except ApiError as e:
            if e.status_code == 404:
                return self.create_dataset(
                    name, description=description, project_name=project_name
                )
            raise

    def create_dashboard(
        self,
        name: str,
        type: Optional[Union[dashboard_types.DashboardType, str]] = None,
        description: Optional[str] = None,
        project_name: Optional[str] = None,
        project_id: Optional[str] = None,
        sections: Optional[
            List[Union[dashboard_types.DashboardSection, Dict[str, Any]]]
        ] = None,
    ) -> dashboard.Dashboard:
        """
        创建新的仪表板。

        Args:
            name: 仪表板的名称。
            type: 仪表板类型，可以是``"multi_project"``或``"experiments"``。
                决定允许哪些小部件类型。
            description: 仪表板的可选描述。
            project_name: 对于项目范围的仪表板，项目名称。如果不存在将被创建。
                当提供``project_id``时被忽略。
            project_id: 对于项目范围的仪表板，项目id。优先于``project_name``。
                如果两者都未提供，将创建工作区级别的仪表板。
            sections: 可选的初始部分（``DashboardSection``对象或字典）。
                如果省略，仪表板将从一个空的"Overview"部分开始。

        Returns:
            dashboard.Dashboard: 创建的仪表板对象。
        """
        if sections is None:
            section_dicts: List[Dict[str, Any]] = [
                dashboard_types.DashboardSection(title="Overview").to_jsonable()
            ]
        else:
            section_dicts = copy.deepcopy(
                dashboard_validation.as_section_dicts(sections)
            )

        dashboard_type = getattr(type, "value", type)
        for section in section_dicts:
            for widget in section.get("widgets", []):
                dashboard_validation.validate_widget_for_dashboard(
                    widget, dashboard_type
                )
                if project_id is not None:
                    dashboard_validation.inject_project_id(widget, project_id)
                elif project_name is None:
                    # 完全没有项目——将错误委托给inject_project_id
                    dashboard_validation.inject_project_id(widget, None)

        config = {
            "version": dashboard_types.DASHBOARD_VERSION,
            "sections": section_dicts,
            "lastModified": dashboard_types.now_ms(),
        }
        dashboard_validation.validate_structure(config)

        response = self._rest_client.dashboards.create_dashboard(
            name=name,
            config=config,
            type=dashboard_type,
            description=description,
            project_id=project_id,
            project_name=project_name,
        )

        return dashboard.Dashboard.from_public(
            dashboard_public=response,
            rest_client=self._rest_client,
            client=self,
        )

    def get_dashboard(self, dashboard_id: str) -> dashboard.Dashboard:
        """
        按id获取仪表板。

        Args:
            dashboard_id: 仪表板的id。

        Returns:
            dashboard.Dashboard: 仪表板对象。
        """
        response = self._rest_client.dashboards.get_dashboard_by_id(dashboard_id)
        return dashboard.Dashboard.from_public(
            dashboard_public=response,
            rest_client=self._rest_client,
            client=self,
        )

    def get_dashboards(
        self,
        name: Optional[str] = None,
        project_id: Optional[str] = None,
        max_results: int = 100,
        sorting: Optional[str] = None,
        filters: Optional[str] = None,
    ) -> List[dashboard.Dashboard]:
        """
        获取工作区中的仪表板。

        Args:
            name: 可选的仪表板名称过滤器。
            project_id: 可选的项目id过滤器。
            max_results: 要返回的最大仪表板数量。
            sorting: 可选的序列化排序规范。
            filters: 可选的序列化过滤器规范。

        Returns:
            List[dashboard.Dashboard]: 匹配的仪表板列表。
        """
        return dashboard_rest_operations.find_dashboards(
            rest_client=self._rest_client,
            client=self,
            name=name,
            project_id=project_id,
            max_results=max_results,
            sorting=sorting,
            filters=filters,
        )

    def delete_dashboard(self, dashboard_id: str) -> None:
        """
        按id删除仪表板。

        Args:
            dashboard_id: 仪表板的id。
        """
        self._rest_client.dashboards.delete_dashboard(dashboard_id)

    def create_test_suite(
        self,
        name: str,
        description: Optional[str] = None,
        global_assertions: Optional[List[str]] = None,
        global_execution_policy: Optional[
            dataset_execution_policy.ExecutionPolicy
        ] = None,
        tags: Optional[List[str]] = None,
        project_name: Optional[str] = None,
    ) -> test_suite.TestSuite:
        """
        创建用于回归测试的新测试套件。

        测试套件是预配置的测试套件，可让您验证提示词更改、模型更新或
        代码修改不会破坏现有功能。

        Args:
            name: 测试套件的名称。
            description: 此套件测试内容的可选描述。
            global_assertions: 应用于所有项目的套件级断言。
                每个字符串描述将由LLM检查的预期行为。
            global_execution_policy: 套件级执行策略。
                示例：{"runs_per_item": 3, "pass_threshold": 2}
            tags: 套件的可选标签列表。
            project_name: 与套件关联的项目可选名称。

        Returns:
            TestSuite: 创建的测试套件对象。

        Example:
            >>> suite = client.create_test_suite(
            ...     name="Refund Policy Tests",
            ...     description="Regression tests for refund scenarios",
            ...     project_name="custom-project",
            ...     global_assertions=[
            ...         "No hallucinated information",
            ...         "Response is helpful",
            ...     ],
            ... )
            >>>
            >>> suite.insert([
            ...     {"data": {"user_input": "How do I get a refund?", "user_tier": "premium"}},
            ... ])
            >>>
            >>> results = suite.run(task=my_llm_function)
        """
        from .dataset import validators, rest_operations

        if global_execution_policy is not None:
            validators.validate_execution_policy(global_execution_policy)

        evaluators = validators.resolve_evaluators(
            global_assertions, None, "suite-level assertions"
        )

        project_name = self._resolve_project_name(project_name)
        rest_operations.create_test_suite_dataset(
            rest_client=self._rest_client,
            dataset_name=name,
            project_name=project_name,
            description=description,
            evaluators=evaluators,
            exec_policy=global_execution_policy,
            tags=tags,
        )
        suite_dataset = dataset.Dataset(
            name=name,
            description=description,
            project_name=project_name,
            rest_client=self._rest_client,
            dataset_items_count=0,
            client=self,
        )

        return test_suite.TestSuite(
            name=name,
            dataset_=suite_dataset,
            client=self,
        )

    def get_test_suite(
        self, name: str, project_name: Optional[str] = None
    ) -> test_suite.TestSuite:
        """
        按名称获取现有测试套件。

        从后端检索数据集及其版本级断言和执行策略，返回完全配置的TestSuite。

        Args:
            name: 测试套件的名称。
            project_name: 套件关联的项目可选名称。

        Returns:
            TestSuite: 测试套件对象。

        Raises:
            ApiError: 如果不存在具有给定名称的数据集（404）。
        """
        project_name = self._resolve_project_name(project_name)
        dataset_fern = self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=name,
            project_name=project_name,
        )

        suite_dataset = dataset.Dataset.from_public(
            dataset_fern=dataset_fern,
            project_name=project_name,
            rest_client=self._rest_client,
            client=self,
        )

        return test_suite.TestSuite(
            name=name,
            dataset_=suite_dataset,
            client=self,
        )

    def get_or_create_test_suite(
        self,
        name: str,
        description: Optional[str] = None,
        global_assertions: Optional[List[str]] = None,
        global_execution_policy: Optional[
            dataset_execution_policy.ExecutionPolicy
        ] = None,
        tags: Optional[List[str]] = None,
        project_name: Optional[str] = None,
    ) -> test_suite.TestSuite:
        """
        按名称获取现有测试套件，如果不存在则创建新的。

        如果套件已存在，则按原样返回——``global_assertions``、
        ``global_execution_policy``、``description``和``tags``参数
        仅在创建新套件时使用。
        要修改现有套件，请使用:meth:`TestSuite.update`。

        Args:
            name: 测试套件的名称。
            description: 可选描述（仅在创建时使用）。
            global_assertions: 套件级断言（仅在创建时使用）。
            global_execution_policy: 执行策略（仅在创建时使用）。
            tags: 可选标签列表（仅在创建时使用）。
            project_name: 套件关联的项目可选名称。

        Returns:
            TestSuite: 测试套件对象。
        """
        try:
            return self.get_test_suite(name, project_name=project_name)
        except ApiError as e:
            if e.status_code == 404:
                return self.create_test_suite(
                    name=name,
                    description=description,
                    global_execution_policy=global_execution_policy,
                    global_assertions=global_assertions,
                    tags=tags,
                    project_name=project_name,
                )
            raise

    def delete_test_suite(self, name: str, project_name: Optional[str] = None) -> None:
        """
        按名称删除测试套件。

        Args:
            name: 测试套件的名称。
            project_name: 套件所属的项目名称。
        """
        project_name = self._resolve_project_name(project_name)
        self._rest_client.datasets.delete_dataset_by_name(
            dataset_name=name, project_name=project_name
        )

    def get_test_suites(
        self,
        max_results: int = 100,
        project_name: Optional[str] = None,
    ) -> List[test_suite.TestSuite]:
        """
        返回所有测试套件，最多返回指定限制数量。

        仅返回测试套件，不返回常规数据集。

        Args:
            max_results: 要返回的最大测试套件数量。
            project_name: 套件所属的项目名称。

        Returns:
            List[TestSuite]: 测试套件对象列表。
        """
        from .dataset import rest_operations

        return rest_operations.get_test_suites(
            project_name=self._resolve_project_name(project_name),
            rest_client=self._rest_client,
            max_results=max_results,
            client=self,
        )

    def get_test_suite_experiments(
        self,
        name: str,
        max_results: int = 100,
        project_name: Optional[str] = None,
    ) -> List[experiment.Experiment]:
        """
        返回测试套件的所有实验。

        Args:
            name: 测试套件的名称。
            max_results: 要返回的最大实验数量。
            project_name: 套件所属的项目名称。

        Returns:
            List[Experiment]: 实验对象列表。
        """
        from .dataset import rest_operations as dataset_rest_operations

        project_name = self._resolve_project_name(project_name)
        dataset_id = dataset_rest_operations.get_dataset_id(
            self._rest_client, dataset_name=name, project_name=project_name
        )

        experiments_client = self.get_experiments_client()
        return dataset_rest_operations.get_dataset_experiments(
            rest_client=self._rest_client,
            dataset_id=dataset_id,
            max_results=max_results,
            streamer=self._streamer,
            experiments_client=experiments_client,
        )

    def create_experiment(
        self,
        dataset_name: str,
        name: Optional[str] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
        prompt: Optional[prompt_module.base_prompt.BasePrompt] = None,
        prompts: Optional[List[prompt_module.base_prompt.BasePrompt]] = None,
        type: Literal["regular", "trial", "mini-batch"] = "regular",
        evaluation_method: Literal["dataset", "evaluation_suite"] = "dataset",
        optimization_id: Optional[str] = None,
        tags: Optional[List[str]] = None,
        dataset_version_id: Optional[str] = None,
        project_name: Optional[str] = None,
        experiment_id: Optional[str] = None,
    ) -> experiment.Experiment:
        """
        使用给定的数据集名称和可选参数创建新实验。

        Args:
            dataset_name: The name of the dataset to associate with the experiment.
            name: The optional name for the experiment. If None, a generated name will be used.
            experiment_config: Optional experiment configuration parameters. Must be a dictionary if provided.
            prompt: Prompt object to associate with the experiment. Deprecated, use `prompts` argument instead.
            prompts: List of Prompt objects to associate with the experiment.
            type: The type of the experiment. Can be "regular", "trial", or "mini-batch".
                Defaults to "regular". "trial" and "mini-batch" are only relevant for prompt optimization experiments.
            optimization_id: Optional ID of the optimization associated with the experiment.
            tags: Optional list of tags to associate with the experiment.
            dataset_version_id: Optional ID of the dataset version to associate with the experiment.
            project_name: Optional name of the project to associate the experiment with.
            experiment_id: Optional explicit id for the experiment. When None a fresh id is
                generated. Callers that must know the id before creation (e.g. the migrate
                cascade, which records it for crash-safe cleanup) can supply their own.

        Returns:
            experiment.Experiment: 新创建的实验对象。
        """
        id = experiment_id or id_helpers.generate_id()

        checked_prompts = experiment_helpers.handle_prompt_args(
            prompt=prompt,
            prompts=prompts,
        )

        metadata, prompt_versions = experiment.build_metadata_and_prompt_versions(
            experiment_config=experiment_config,
            prompts=checked_prompts,
        )

        project_name = self._resolve_project_name(project_name)

        self._rest_client.experiments.create_experiment(
            name=name,
            dataset_name=dataset_name,
            id=id,
            metadata=metadata,
            prompt_versions=prompt_versions,
            type=type,
            evaluation_method=evaluation_method,
            optimization_id=optimization_id,
            tags=tags,
            dataset_version_id=dataset_version_id,
            project_name=project_name,
        )

        experiment_ = experiment.Experiment(
            id=id,
            name=name,
            dataset_name=dataset_name,
            rest_client=self._rest_client,
            streamer=self._streamer,
            experiments_client=self.get_experiments_client(),
            prompts=checked_prompts,
            tags=tags,
            project_name=project_name,
        )

        return experiment_

    def update_experiment(
        self,
        id: str,
        name: Optional[str] = None,
        experiment_config: Optional[Dict[str, Any]] = None,
    ) -> None:
        """
        更新实验的名称和/或配置。

        Args:
            id: 实验ID。
            name: 实验的新名称。如果为None，名称将不会更新。
            experiment_config: 实验的新配置。如果为None，配置将不会更新。

        Raises:
            ValueError: 如果id为None或空，或者name和experiment_config都为None。
        """
        if not id:
            raise ValueError(
                f"id must be provided and can not be None or empty, id: {id}"
            )

        if name is None and experiment_config is None:
            raise ValueError(
                "At least one of 'name' or 'experiment_config' must be provided"
            )

        # 仅包含提供的参数以避免清除字段
        request_params: Dict[str, Any] = {}
        if name is not None:
            request_params["name"] = name
        if experiment_config is not None:
            request_params["metadata"] = experiment_config

        self._rest_client.experiments.update_experiment(id, **request_params)

    def get_experiment_by_name(
        self, name: str, project_name: Optional[str] = None
    ) -> experiment.Experiment:
        """
        按名称返回现有实验。

        Args:
            name: 实验的名称。
            project_name: 实验所属的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。

        Returns:
            experiment.Experiment: 现有实验的API对象。
        """
        LOGGER.warning(
            "已弃用，请改用`get_experiments_by_name`或`get_experiment_by_id`。"
        )
        project_name = self._resolve_project_name(project_name)
        experiment_public = experiment_rest_operations.get_experiment_data_by_name(
            rest_client=self._rest_client, name=name, project_name=project_name
        )

        return experiment.Experiment(
            id=experiment_public.id,
            name=experiment_public.name,
            dataset_name=experiment_public.dataset_name,
            rest_client=self._rest_client,
            streamer=self._streamer,
            experiments_client=self.get_experiments_client(),
            tags=experiment_public.tags,
            project_name=experiment_public.project_name,
        )

    def get_experiments_by_name(
        self, name: str, project_name: Optional[str] = None
    ) -> List[experiment.Experiment]:
        """
        返回名称中包含给定字符串的现有实验列表。
        搜索不区分大小写。

        Args:
            name: 要在实验名称中搜索的字符串。
            project_name: 要搜索的项目名称。如果为None，使用默认项目。

        Returns:
            List[experiment.Experiment]: 现有实验列表。
        """
        project_name = self._resolve_project_name(project_name)
        experiments_public = experiment_rest_operations.get_experiments_data_by_name(
            rest_client=self._rest_client, name=name, project_name=project_name
        )
        result = []

        for public_experiment in experiments_public:
            experiment_ = experiment.Experiment(
                id=public_experiment.id,
                name=public_experiment.name,
                dataset_name=public_experiment.dataset_name,
                rest_client=self._rest_client,
                streamer=self._streamer,
                experiments_client=self.get_experiments_client(),
                tags=public_experiment.tags,
                project_name=public_experiment.project_name,
            )
            result.append(experiment_)

        return result

    def get_experiment_by_id(self, id: str) -> experiment.Experiment:
        """
        按id返回现有实验。

        Args:
            id: 实验的id。

        Returns:
            experiment.Experiment: 现有实验的API对象。
        """
        try:
            experiment_public = self._rest_client.experiments.get_experiment_by_id(
                id=id
            )
        except ApiError as exception:
            if exception.status_code == 404:
                raise exceptions.ExperimentNotFound(
                    f"Experiment with the id {id} not found."
                ) from exception
            raise

        return experiment.Experiment(
            id=experiment_public.id,
            name=experiment_public.name,
            dataset_name=experiment_public.dataset_name,
            rest_client=self._rest_client,
            streamer=self._streamer,
            experiments_client=self.get_experiments_client(),
            tags=experiment_public.tags,
            project_name=experiment_public.project_name,
        )

    def end(self, timeout: Optional[int] = None, *, flush: bool = True) -> None:
        """
        结束Opik会话并提交所有待处理消息。

        Connection resources are shared and ref-counted across clients with a
        matching configuration: this releases the current client's reference.
        The underlying streamer/threads are torn down only when the last client
        sharing them is ended (or garbage-collected). When ``flush`` is True the
        flush drains the *shared* queue, so pending data from other clients on
        the same connection is delivered too.

        Args:
            timeout (Optional[int]): 关闭流处理器的超时时间。一旦达到超时，
                流处理器将被关闭，无论所有消息是否已发送。如果未设置超时，
                将使用Opik配置中的默认值。当``flush``为False时忽略。
            flush (bool): 如果为True（默认），则在关闭前等待排队的消息和
                文件上传到达后端——这是生产环境和atexit关闭的安全选择。
                如果为False，则在发送停止信号后立即返回，丢弃仍在传输中的
                任何内容——适用于测试内断言已在测试期间轮询后端的测试拆卸。

        After ``end()`` the client must not be used again. Calling ``trace()``,
        ``span()``, ``flush()``, etc. on an ended client is unsupported and its
        behavior is undefined: it may silently no-op, or — because the transport
        is shared — it may still succeed by riding another live client's
        resources. Do not rely on either outcome; create a new client instead.

        Returns:
            None
        """
        timeout = timeout if timeout is not None else self._flush_timeout
        # Explicit teardown on a user thread, so close on the last reference
        # (close_on_zero=True). Releasing is idempotent, so the detached GC
        # finalizer cannot double-decrement.
        self._lease.release(timeout, flush=flush, close_on_zero=True)
        self._finalizer.detach()

    def flush(self, timeout: Optional[int] = None) -> bool:
        """
        刷新流处理器以确保所有消息已发送。

        Args:
            timeout (Optional[int]): 刷新流处理器的超时时间。一旦达到超时，
                刷新方法将返回，无论所有消息是否已发送。

        Returns:
            如果在指定超时内所有消息已发送则返回True，否则返回False。
        """
        timeout = timeout if timeout is not None else self._flush_timeout
        return self._streamer.flush(timeout)

    def __internal_api__drain_to_processors__(
        self, timeout: Optional[float] = None
    ) -> bool:
        """
        排空待处理消息，以便进程内链式处理器（特别是本地模拟器）
        已应用迄今为止提交的每条消息。

        比`flush(...)`更轻量：跳过文件上传和重放刷新，因为调用者
        只关心本地处理器状态，而不是后端传递。在调用代理式LLM判断器
        之前由评估引擎使用——参见`EvaluationEngine._build_trace_tool_context`
        了解原因。
        """
        return self._streamer.drain_to_processors(timeout)

    def __internal_api__failed_uploads__(self, timeout: Optional[float] = None) -> int:
        """
        返回刷新后失败的文件上传数量。阻塞式 - 等待所有上传完成。
        """
        return self._streamer.__internal_api__failed_uploads__(timeout=timeout)

    def search_traces(
        self,
        project_name: Optional[str] = None,
        filter_string: Optional[str] = None,
        max_results: int = 1000,
        truncate: bool = True,
        exclude: Optional[List[str]] = None,
        wait_for_at_least: Optional[int] = None,
        wait_for_timeout: int = httpx_client.READ_TIMEOUT_SECONDS,
        max_batch_size: int = rest_stream_parser.MAX_ENDPOINT_BATCH_SIZE,
    ) -> List[trace_public.TracePublic]:
        """
        在给定项目中搜索traces。可选地，您可以等待至少找到一定数量的traces后再返回，
        但需在指定超时内。如果在指定超时内未找到wait_for_at_least数量的traces，
        将引发异常。

        Args:
            project_name: 要搜索traces的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            filter_string: 使用Opik查询语言（OQL）缩小搜索范围的过滤器字符串。
                格式为："<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"

                支持的列包括：
                - `id`, `name`, `created_by`, `thread_id`, `type`, `model`, `provider`: 字符串字段，完全支持运算符
                - `environment`: 生命周期阶段枚举字段 (=, !=, in, not_in)
                - `status`: 字符串字段 (=, contains, not_contains)
                - `start_time`, `end_time`: 日期时间字段（使用ISO 8601格式，例如"2024-01-01T00:00:00Z"）
                - `input`, `output`: 内容字符串字段 (=, contains, not_contains)
                - `metadata`: 字典字段（使用点表示法，例如"metadata.model"）
                - `feedback_scores`: 数值字段（使用点表示法，例如"feedback_scores.accuracy"）
                - `tags`: 列表字段（仅使用"contains"运算符）
                - `usage.total_tokens`, `usage.prompt_tokens`, `usage.completion_tokens`: 数值使用字段
                - `duration`, `number_of_messages`, `total_estimated_cost`: 数值字段

                按列支持的运算符：
                - `id`, `name`, `created_by`, `thread_id`, `type`, `model`, `provider`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `environment`: =, !=, in, not_in
                - `status`: =, contains, not_contains
                - `start_time`, `end_time`: =, >, <, >=, <=
                - `input`, `output`: =, contains, not_contains
                - `metadata`: =, contains, >, <
                - `feedback_scores`: =, >, <, >=, <=, is_empty, is_not_empty
                - `tags`: contains（仅）
                - `usage.total_tokens`, `usage.prompt_tokens`, `usage.completion_tokens`, `duration`, `number_of_messages`, `total_estimated_cost`: =, !=, >, <, >=, <=

                Examples:
                - `start_time >= "2024-01-01T00:00:00Z"` - Filter by start date
                - `start_time > "2024-01-01T00:00:00Z" AND start_time < "2024-02-01T00:00:00Z"` - Date range
                - `input contains "question"` - Filter by input content
                - `usage.total_tokens > 1000` - Filter by token usage
                - `feedback_scores.accuracy > 0.8` - Filter by feedback score
                - `feedback_scores.my_metric is_empty` - Filter traces with empty feedback score
                - `feedback_scores.my_metric is_not_empty` - Filter traces with non-empty feedback score
                - `tags contains "production"` - Filter by tag
                - `metadata.model = "gpt-4"` - Filter by metadata field
                - `thread_id = "thread_123"` - Filter by thread ID
                - `environment = "production"` - Filter by environment
                - `environment in ("production", "staging")` - Filter by multiple environments

                If not provided, all traces in the project will be returned up to the limit.
            max_results: The maximum number of traces to return.
            truncate: Whether to truncate image data stored in input, output, or metadata
            exclude: Fields to exclude from the response. For example, ["feedback_scores"]
            wait_for_at_least: The minimum number of traces to wait for before returning.
            wait_for_timeout: The timeout for waiting for traces.
            max_batch_size: The maximum number of traces requested per page from the backend
                (default 2000). The backend buffers a page in memory before streaming it, so a
                large page of heavy traces (e.g. with inline attachments) can spike server memory;
                lower this to bound per-request memory. On a connection/timeout error the page size
                is automatically halved and the page retried.

        Raises:
            如果在指定超时内未找到wait_for_at_least数量的traces，引发exceptions.SearchTimeoutError。
        """
        filters_ = helpers.parse_filter_expressions(
            filter_string,
            parsed_item_class=trace_filter_public.TraceFilterPublic,
            entity_type="traces",
        )

        project_name = self._resolve_project_name(project_name)

        search_functor = functools.partial(
            search_helpers.search_traces_with_filters,
            rest_client=self._rest_client,
            project_name=project_name,
            filters=filters_,
            max_results=max_results,
            truncate=truncate,
            exclude=exclude,
            max_batch_size=max_batch_size,
        )

        if wait_for_at_least is None:
            return search_functor()

        # 如果提供了 wait_for_at_least，则与后端同步直到找到指定数量的 traces
        result = search_helpers.search_and_wait_for_done(
            search_functor=search_functor,
            wait_for_at_least=wait_for_at_least,
            wait_for_timeout=wait_for_timeout,
            sleep_time=5,
        )
        if len(result) < wait_for_at_least:
            raise exceptions.SearchTimeoutError(
                f"Timeout after {wait_for_timeout} seconds: expected {wait_for_at_least} traces, but only {len(result)} were found."
            )

        return result

    def search_spans(
        self,
        project_name: Optional[str] = None,
        trace_id: Optional[str] = None,
        filter_string: Optional[str] = None,
        max_results: int = 1000,
        truncate: bool = True,
        exclude: Optional[List[str]] = None,
        wait_for_at_least: Optional[int] = None,
        wait_for_timeout: int = httpx_client.READ_TIMEOUT_SECONDS,
        max_batch_size: int = rest_stream_parser.MAX_ENDPOINT_BATCH_SIZE,
    ) -> List[span_public.SpanPublic]:
        """
        在给定trace中搜索spans。这允许您根据span的输入、输出、元数据、标签等或
        根据trace ID搜索spans。此外，您可以等待至少找到一定数量的spans后再返回，
        但需在指定超时内。如果在指定超时内未找到wait_for_at_least数量的spans，
        将引发异常。

        Args:
            project_name: 要搜索spans的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            trace_id: 要搜索spans的trace ID。如果提供，搜索将仅限于给定trace中的spans。
            filter_string: 使用Opik查询语言（OQL）缩小搜索范围的过滤器字符串。
                格式为："<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"

                支持的列包括：
                - `id`, `name`, `created_by`, `thread_id`, `type`, `model`, `provider`: 字符串字段，完全支持运算符
                - `environment`: 生命周期阶段枚举字段 (=, !=, in, not_in)
                - `status`: 字符串字段 (=, contains, not_contains)
                - `start_time`, `end_time`: 日期时间字段（使用ISO 8601格式，例如"2024-01-01T00:00:00Z"）
                - `input`, `output`: 内容字符串字段 (=, contains, not_contains)
                - `metadata`: 字典字段（使用点表示法，例如"metadata.model"）
                - `feedback_scores`: 数值字段（使用点表示法，例如"feedback_scores.accuracy"）
                - `tags`: 列表字段（仅使用"contains"运算符）
                - `usage.total_tokens`, `usage.prompt_tokens`, `usage.completion_tokens`: 数值使用字段
                - `duration`, `number_of_messages`, `total_estimated_cost`: 数值字段

                按列支持的运算符：
                - `id`, `name`, `created_by`, `thread_id`, `type`, `model`, `provider`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `environment`: =, !=, in, not_in
                - `status`: =, contains, not_contains
                - `start_time`, `end_time`: =, >, <, >=, <=
                - `input`, `output`: =, contains, not_contains
                - `metadata`: =, contains, >, <
                - `feedback_scores`: =, >, <, >=, <=, is_empty, is_not_empty
                - `tags`: contains（仅）
                - `usage.total_tokens`, `usage.prompt_tokens`, `usage.completion_tokens`, `duration`, `number_of_messages`, `total_estimated_cost`: =, !=, >, <, >=, <=

                Examples:
                - `start_time >= "2024-01-01T00:00:00Z"` - Filter by start date
                - `start_time > "2024-01-01T00:00:00Z" AND start_time < "2024-02-01T00:00:00Z"` - Date range
                - `input contains "question"` - Filter by input content
                - `usage.total_tokens > 1000` - Filter by token usage
                - `feedback_scores.accuracy > 0.8` - Filter by feedback score
                - `feedback_scores.my_metric is_empty` - Filter spans with empty feedback score
                - `feedback_scores.my_metric is_not_empty` - Filter spans with non-empty feedback score
                - `tags contains "production"` - Filter by tag
                - `metadata.model = "gpt-4"` - Filter by metadata field
                - `thread_id = "thread_123"` - Filter by thread ID
                - `environment = "production"` - Filter by environment
                - `environment in ("production", "staging")` - Filter by multiple environments

                If not provided, all spans in the project/trace will be returned up to the limit.
            max_results: The maximum number of spans to return.
            truncate: Whether to truncate image data stored in input, output, or metadata
            exclude: List of fields to exclude from the response (e.g., ["feedback_scores", "input", "output"])
            wait_for_at_least: The minimum number of spans to wait for before returning.
            wait_for_timeout: The timeout for waiting for spans.
            max_batch_size: The maximum number of spans requested per page from the backend
                (default 2000). The backend buffers a page in memory before streaming it, so a
                large page of heavy spans (e.g. with inline attachments) can spike server memory;
                lower this to bound per-request memory. On a connection/timeout error the page size
                is automatically halved and the page retried.

        Raises:
            如果在指定超时内未找到wait_for_at_least数量的spans，引发exceptions.SearchTimeoutError。
        """
        filters = helpers.parse_filter_expressions(
            filter_string,
            parsed_item_class=span_filter_public.SpanFilterPublic,
            entity_type="spans",
        )

        project_name = self._resolve_project_name(project_name)
        search_functor = functools.partial(
            search_helpers.search_spans_with_filters,
            rest_client=self._rest_client,
            project_name=project_name,
            trace_id=trace_id,
            filters=filters,
            max_results=max_results,
            truncate=truncate,
            exclude=exclude,
            max_batch_size=max_batch_size,
        )

        if wait_for_at_least is None:
            return search_functor()

        # 如果提供了 wait_for_at_least，则与后端同步直到找到指定数量的 spans
        result = search_helpers.search_and_wait_for_done(
            search_functor=search_functor,
            wait_for_at_least=wait_for_at_least,
            wait_for_timeout=wait_for_timeout,
            sleep_time=5,
        )
        if len(result) < wait_for_at_least:
            raise exceptions.SearchTimeoutError(
                f"Timeout after {wait_for_timeout} seconds: expected {wait_for_at_least} spans, but only {len(result)} were found."
            )

        return result

    def get_trace_content(self, id: str) -> trace_public.TracePublic:
        """
        Args:
            id (str): trace id
        Returns:
            trace_public.TracePublic: 包含找到的trace所有数据的pydantic模型对象。
            如果未找到trace则引发错误。
        """
        return self._rest_client.traces.get_trace_by_id(id)

    def get_span_content(self, id: str) -> span_public.SpanPublic:
        """
        Args:
            id (str): span id
        Returns:
            span_public.SpanPublic: 包含找到的span所有数据的pydantic模型对象。
            如果未找到span则引发错误。
        """
        return self._rest_client.spans.get_span_by_id(id)

    def get_project(self, id: str) -> project_public.ProjectPublic:
        """
        通过唯一标识符获取项目。

        Parameters:
            id (str): 项目id（uuid）。

        Returns:
            project_public.ProjectPublic: 包含找到的项目所有数据的pydantic模型对象。
            如果未找到项目则引发错误。
        """
        return self._rest_client.projects.get_project_by_id(id)

    def get_project_url(self, project_name: Optional[str] = None) -> str:
        """
        返回当前工作区中项目的URL。
        此方法不发出任何请求或执行任何检查（例如项目是否存在）。
        它仅根据提供的数据构建URL字符串。

        Parameters:
            project_name (str): 要返回URL的项目名称。
                如果未提供，将使用当前Opik实例的默认项目名称。

        Returns:
            str: URL
        """

        dereferenced_workspace = self._workspace
        if dereferenced_workspace == opik_config.OPIK_WORKSPACE_DEFAULT_NAME:
            dereferenced_workspace = (
                self._rest_client.check.get_workspace_name().workspace_name
            )

        project_name = self._resolve_project_name(project_name)

        return url_helpers.get_project_url_by_workspace(
            workspace=dereferenced_workspace, project_name=project_name
        )

    def get_threads_client(self) -> threads_client.ThreadsClient:
        """
        创建并提供绑定到当前上下文的``ThreadsClient``实例。

        ``ThreadsClient``可用于与线程API交互，以管理和交互对话线程。

        Returns:
            ThreadsClient: 使用当前上下文初始化的``threads_client.ThreadsClient``实例。
        """
        return threads_client.ThreadsClient(self)

    def get_attachment_client(self) -> attachment_client.AttachmentClient:
        """
        创建并提供绑定到当前上下文的``AttachmentClient``实例。

        ``AttachmentClient``可用于与附件API交互，以检索附件列表、
        下载附件以及为traces和spans上传附件。

        Returns:
            AttachmentClient: ``attachment.client.AttachmentClient``的实例。
        """
        return attachment_client.AttachmentClient(
            rest_client=self._rest_client,
            url_override=self._config.url_override,
            workspace_name=self._workspace,
            rest_httpx_client=self._httpx_client,
        )

    def queue_attachment_upload(
        self,
        entity_type: Literal["trace", "span"],
        entity_id: str,
        project_name: str,
        file_path: str,
        file_name: Optional[str] = None,
        mime_type: Optional[str] = None,
    ) -> None:
        """
        将本地文件排队通过流处理器后台上传为附件。

        此方法是非阻塞的：上传由后台流处理器处理，提供并行化、自动重试和监控。
        调用:meth:`flush`等待所有排队的上传完成。

        Parameters:
            entity_type: 要附加文件的实体类型（``"trace"``或``"span"``）。
            entity_id: 要附加文件的trace或span的ID。
            project_name: 包含实体的项目名称。
            file_path: 要上传的本地文件路径。
            file_name: 要分配给附件的名称。默认为文件的基本名称。
            mime_type: 文件的MIME类型。如果未提供，将从文件名自动检测。
        """
        attachment_data = Attachment(
            data=file_path,
            file_name=file_name,
            content_type=mime_type,
            create_temp_copy=False,
        )
        self._streamer.put(
            attachment_converters.attachment_to_message(
                attachment_data=attachment_data,
                entity_type=entity_type,
                entity_id=entity_id,
                project_name=project_name,
                url_override=self._config.url_override,
            )
        )

    def create_prompt(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]] = None,
        type: prompt_module.PromptType = prompt_module.PromptType.MUSTACHE,
        id: Optional[str] = None,
        description: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
        project_name: Optional[str] = None,
    ) -> prompt_module.Prompt:
        """
        使用给定名称和模板创建新的文本提示词。
        如果已存在同名的文本提示词，且模板不同，将创建现有提示词的新版本。

        Parameters:
            name: 提示词的名称。
            prompt: 提示词的模板内容。
            metadata: 要包含在提示词中的可选元数据。
            type: 模板类型（MUSTACHE或JINJA2）。
            id: 提示词的可选唯一标识符（UUID）。
            description: 提示词的可选描述（最多255个字符）。
            change_description: 此版本更改的可选描述。
            tags: 要与提示词关联的可选标签列表。
            project_name: 要与提示词关联的可选项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。

        Returns:
            包含创建或检索的提示词详细信息的Prompt对象。

        Raises:
            PromptTemplateStructureMismatch: 如果已存在同名的聊天提示词（模板结构不可变）。
            ApiError: 如果在创建提示词期间出现错误。
        """
        prompt_client_ = prompt_client.PromptClient(self._rest_client)
        project_name = self._resolve_project_name(project_name)
        prompt_version = prompt_client_.create_prompt(
            name=name,
            prompt=prompt,
            metadata=metadata,
            type=type,
            id=id,
            description=description,
            change_description=change_description,
            tags=tags,
            project_name=project_name,
        )
        return prompt_module.Prompt.from_fern_prompt_version(
            name, prompt_version, project_name=project_name
        )

    def create_chat_prompt(
        self,
        name: str,
        messages: List[Dict[str, Any]],
        metadata: Optional[Dict[str, Any]] = None,
        type: prompt_module.PromptType = prompt_module.PromptType.MUSTACHE,
        id: Optional[str] = None,
        description: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
        project_name: Optional[str] = None,
    ) -> prompt_module.ChatPrompt:
        """
        使用给定名称和消息模板创建新的聊天提示词。
        如果已存在同名的聊天提示词，且消息不同，将创建新版本。

        Parameters:
            name: 聊天提示词的名称。
            messages: 包含'role'和'content'字段的消息字典列表。
            metadata: 要包含在提示词中的可选元数据。
            type: 模板类型（MUSTACHE或JINJA2）。
            id: 提示词的可选唯一标识符（UUID）。
            description: 提示词的可选描述（最多255个字符）。
            change_description: 此版本更改的可选描述。
            tags: 要与提示词关联的可选标签列表。
            project_name: 提示词的可选项目名称。

        Returns:
            包含创建或检索的聊天提示词详细信息的ChatPrompt对象。

        Raises:
            PromptTemplateStructureMismatch: 如果已存在同名的文本提示词（模板结构不可变）。
            ApiError: 如果在创建提示词期间出现错误。
        """
        validator = ChatPromptMessagesValidator(messages)
        validator.validate()
        validator.raise_if_validation_failed()

        prompt_client_ = prompt_client.PromptClient(self._rest_client)
        project_name = self._resolve_project_name(project_name)
        messages_str = json.dumps(messages)
        prompt_version = prompt_client_.create_prompt(
            name=name,
            prompt=messages_str,
            metadata=metadata,
            type=type,
            template_structure="chat",
            id=id,
            description=description,
            change_description=change_description,
            tags=tags,
            project_name=project_name,
        )
        return prompt_module.ChatPrompt.from_fern_prompt_version(
            name, prompt_version, project_name=project_name
        )

    def get_prompt(
        self,
        name: str,
        commit: Optional[str] = None,
        project_name: Optional[str] = None,
        no_cache: bool = False,
        version: Optional[str] = None,
        environment: Optional[str] = None,
    ) -> Optional[prompt_module.Prompt]:
        """
        按名称检索文本提示词，可选择性地针对特定``version``。

        此方法仅返回文本提示词。结果在客户端缓存
        （TTL可通过OPIK_PROMPT_CACHE_TTL_SECONDS配置，默认300秒）。
        在@track上下文中调用时，提示词引用将注入到活动trace/span元数据中。

        Parameters:
            name: 提示词的名称。
            commit: 已弃用，推荐使用``version``。与``version``互斥。
            project_name: 要从中检索提示词的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            no_cache: 如果为True，则跳过本地缓存并直接从后端获取，保证获取新值。
            version: 可选的顺序版本选择器，格式为``"v<N>"``（例如``"v3"``）。
                如果未提供，将检索最新版本。
            environment: 可选的环境名称。提供时，返回给定环境当前指向的版本。
                与``version``互斥。

        Returns:
            Prompt: 指定文本提示词的详细信息，如果未找到则返回None。

        Raises:
            PromptTemplateStructureMismatch: 如果提示词存在但是聊天提示词（模板结构不匹配）。
            ValueError: 如果同时提供了``version``和``environment``。
        """
        return prompt_client.PromptClient(self._rest_client).get_prompt_with_cache(
            name=name,
            commit=commit,
            project_name=self._resolve_project_name(project_name),
            template_structure="text",
            prompt_cls=text_prompt_module.Prompt,
            no_cache=no_cache,
            version=version,
            environment=environment,
        )

    def get_chat_prompt(
        self,
        name: str,
        commit: Optional[str] = None,
        project_name: Optional[str] = None,
        no_cache: bool = False,
        version: Optional[str] = None,
        environment: Optional[str] = None,
    ) -> Optional[prompt_module.ChatPrompt]:
        """
        按名称检索聊天提示词，可选择性地针对特定``version``。

        此方法仅返回聊天提示词。结果在客户端缓存
        （TTL可通过OPIK_PROMPT_CACHE_TTL_SECONDS配置，默认300秒）。
        在@track上下文中调用时，提示词引用将注入到活动trace/span元数据中。

        Parameters:
            name: 提示词的名称。
            commit: 已弃用，推荐使用``version``。与``version``互斥。
            project_name: 要从中检索提示词的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            no_cache: 如果为True，则跳过本地缓存并直接从后端获取，保证获取新值。
            version: 可选的顺序版本选择器，格式为``"v<N>"``（例如``"v3"``）。
                如果未提供，将检索最新版本。
            environment: 可选的环境名称。提供时，返回给定环境当前指向的版本。
                与``version``互斥。

        Returns:
            ChatPrompt: 指定聊天提示词的详细信息，如果未找到则返回None。

        Raises:
            PromptTemplateStructureMismatch: 如果提示词存在但是文本提示词（模板结构不匹配）。
            ValueError: 如果同时提供了``version``和``environment``。
        """
        return prompt_client.PromptClient(self._rest_client).get_prompt_with_cache(
            name=name,
            commit=commit,
            project_name=self._resolve_project_name(project_name),
            template_structure="chat",
            prompt_cls=chat_prompt_module.ChatPrompt,
            no_cache=no_cache,
            version=version,
            environment=environment,
        )

    def set_prompt_environments(
        self,
        prompt_name: str,
        environments: List[str],
        *,
        version: Optional[str] = None,
        project_name: Optional[str] = None,
    ) -> None:
        """
        替换提示词版本拥有的完整环境集。

        提供的列表成为解析版本的完整环境集。
        传入空列表可清除版本的所有环境。列表中任何环境的所有权都转移到此版本：
        之前拥有这些环境的同一提示词的任何其他版本都将被清除。
        内存中现有的``Prompt``对象不会被修改——使用``client.get_prompt(...)``重新获取以查看更改。

        Parameters:
            prompt_name: 提示词的名称。
            environments: 要分配的环境。每个环境必须已在工作区中注册。传入``[]``可清除。
            version: 可选的顺序版本选择器，格式为``"v<N>"``（例如``"v3"``）。
                默认为最新版本。
            project_name: 提示词所属的项目。默认为活动项目上下文，然后使用客户端默认值。

        Raises:
            PromptNotFoundError: 提示词名称（或提供的``version``）在解析的项目中不存在。
            EnvironmentNotFoundError: ``environments``中的一个未在工作区中注册。
        """
        resolved_project_name = self._resolve_project_name(project_name)
        try:
            resolved_version = self._rest_client.prompts.retrieve_prompt_version(
                name=prompt_name,
                version_number=version,
                project_name=resolved_project_name,
            )
        except ApiError as e:
            if e.status_code == 404:
                if version is not None:
                    raise exceptions.PromptNotFoundError(
                        f"No version {version!r} found for prompt {prompt_name!r}."
                    ) from e
                raise exceptions.PromptNotFoundError(
                    f"No prompt found with name {prompt_name!r}."
                ) from e
            raise

        target = list(dict.fromkeys(environments))
        try:
            self._rest_client.prompts.set_prompt_version_environment(
                version_id=resolved_version.id,
                environments=target,
            )
        except ApiError as e:
            # 后端将未知环境报告为404（未找到）或409
            # （冲突，当名称与工作区注册表检查冲突时）。
            if e.status_code in (404, 409):
                raise exceptions.EnvironmentNotFoundError(
                    f"One or more environments in {target!r} are not registered in this workspace."
                ) from e
            raise

        prompt_cache.invalidate_for_prompt(
            name=prompt_name, project_name=resolved_project_name
        )

    def get_prompt_history(
        self,
        name: str,
        search: Optional[str] = None,
        filter_string: Optional[str] = None,
        project_name: Optional[str] = None,
    ) -> List[prompt_module.Prompt]:
        """
        检索给定提示词名称的所有文本提示词版本历史。

        Parameters:
            name: 提示词的名称。
            search: 要在模板或更改描述字段中查找的可选搜索文本。
            project_name: 要从中检索提示词历史的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            filter_string: 使用Opik查询语言（OQL）缩小搜索范围的过滤器字符串。
                格式为："<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"

                支持的列包括：
                - `id`, `commit`, `template`, `change_description`, `created_by`: 字符串字段，完全支持运算符
                - `metadata`: 字典字段（使用点表示法，例如"metadata.environment"）
                - `type`: 枚举字段（仅=, !=）
                - `tags`: 列表字段（仅使用"contains"运算符）
                - `created_at`: 日期时间字段（使用ISO 8601格式，例如"2024-01-01T00:00:00Z"）

                示例：
                - `tags contains "production"` - 按标签过滤
                - `tags contains "v1" AND tags contains "production"` - 按多个标签过滤
                - `template contains "customer"` - 按模板内容过滤
                - `created_by = "user@example.com"` - 按创建者过滤
                - `created_at >= "2024-01-01T00:00:00Z"` - 按创建日期过滤
                - `metadata.environment = "prod"` - 按元数据字段过滤

        Returns:
            List[Prompt]: 给定名称的文本Prompt实例列表，如果未找到则返回空列表。

        Raises:
            PromptTemplateStructureMismatch: 如果提示词存在但是聊天提示词（模板结构不匹配）。

        Example:
            # 获取提示词的所有版本
            versions = client.get_prompt_history(name="my-prompt", project_name="my-project")

            # 按标签过滤（包含"production"标签的版本）
            versions = client.get_prompt_history(
                name="my-prompt",
                project_name="my-project",
                filter_string='tags contains "production"'
            )

            # 在模板或更改描述字段中搜索特定文本
            versions = client.get_prompt_history(
                name="my-prompt",
                project_name="my-project",
                search="customer"
            )

            # 组合搜索和过滤
            versions = client.get_prompt_history(
                name="my-prompt",
                project_name="my-project",
                search="customer",
                filter_string='tags contains "production"'
            )
        """
        prompt_client_ = prompt_client.PromptClient(self._rest_client)
        project_name = self._resolve_project_name(project_name)

        # 首先，通过尝试获取最新版本来验证这是文本提示词
        # 让PromptTemplateStructureMismatch异常传播——这是一个硬错误
        latest_version = prompt_client_.get_prompt(
            name=name, raise_if_not_template_structure="text", project_name=project_name
        )

        if latest_version is None:
            return []

        # 现在获取所有版本（我们知道这是文本提示词）
        fern_prompt_versions = prompt_client_.get_all_prompt_versions(
            name=name,
            search=search,
            filter_string=filter_string,
            project_name=project_name,
        )

        result = [
            prompt_module.Prompt.from_fern_prompt_version(
                name, version, project_name=project_name
            )
            for version in fern_prompt_versions
        ]
        return result

    def get_chat_prompt_history(
        self,
        name: str,
        search: Optional[str] = None,
        filter_string: Optional[str] = None,
        project_name: Optional[str] = None,
    ) -> List[prompt_module.ChatPrompt]:
        """
        检索给定提示词名称的所有聊天提示词版本历史。

        Parameters:
            name: 提示词的名称。
            search: 要在模板或更改描述字段中查找的可选搜索文本。
            project_name: 要从中检索提示词历史的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            filter_string: 使用Opik查询语言（OQL）缩小搜索范围的过滤器字符串。
                格式为："<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"

                支持的列包括：
                - `id`, `commit`, `template`, `change_description`, `created_by`: 字符串字段，完全支持运算符
                - `metadata`: 字典字段（使用点表示法，例如"metadata.environment"）
                - `type`: 枚举字段（仅=, !=）
                - `tags`: 列表字段（仅使用"contains"运算符）
                - `created_at`: 日期时间字段（使用ISO 8601格式，例如"2024-01-01T00:00:00Z"）

                示例：
                - `tags contains "production"` - 按标签过滤
                - `tags contains "v1" AND tags contains "production"` - 按多个标签过滤
                - `template contains "helpful assistant"` - 按模板内容过滤
                - `created_by = "user@example.com"` - 按创建者过滤
                - `created_at >= "2024-01-01T00:00:00Z"` - 按创建日期过滤
                - `metadata.environment = "prod"` - 按元数据字段过滤

        Returns:
            List[ChatPrompt]: 给定名称的ChatPrompt实例列表，如果未找到则返回空列表。

        Raises:
            PromptTemplateStructureMismatch: 如果提示词存在但是文本提示词（模板结构不匹配）。

        Example:
            # 获取聊天提示词的所有版本
            versions = client.get_chat_prompt_history(name="my-chat-prompt", project_name="my-project")

            # 按标签过滤（包含"production"标签的版本）
            versions = client.get_chat_prompt_history(
                name="my-chat-prompt",
                project_name="my-project",
                filter_string='tags contains "production"'
            )

            # 在模板或更改描述字段中搜索特定文本
            versions = client.get_chat_prompt_history(
                name="my-chat-prompt",
                project_name="my-project",
                search="helpful assistant"
            )

            # 组合搜索和过滤
            versions = client.get_chat_prompt_history(
                name="my-chat-prompt",
                project_name="my-project",
                search="helpful assistant",
                filter_string='tags contains "production"'
            )
        """
        prompt_client_ = prompt_client.PromptClient(self._rest_client)
        project_name = self._resolve_project_name(project_name)

        # 首先，通过尝试获取最新版本来验证这是聊天提示词
        # 让PromptTemplateStructureMismatch异常传播——这是一个硬错误
        latest_version = prompt_client_.get_prompt(
            name=name, raise_if_not_template_structure="chat", project_name=project_name
        )

        if latest_version is None:
            return []

        # 现在获取所有版本（我们知道这是聊天提示词）
        fern_prompt_versions = prompt_client_.get_all_prompt_versions(
            name=name,
            search=search,
            filter_string=filter_string,
            project_name=project_name,
        )

        result = [
            prompt_module.ChatPrompt.from_fern_prompt_version(
                name, version, project_name=project_name
            )
            for version in fern_prompt_versions
        ]
        return result

    def get_all_prompts(
        self, name: str, project_name: Optional[str] = None
    ) -> List[prompt_module.Prompt]:
        """
        已弃用：请改用Opik.get_prompt_history()。
        检索给定提示词名称的所有提示词版本历史。

        Parameters:
            name: 提示词的名称。
            project_name: 要从中检索提示词历史的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。

        Returns:
            List[prompt_module.Prompt]: 给定名称的Prompt实例列表。
        """
        LOGGER.warning(
            "Opik.get_all_prompts()已弃用。请改用Opik.get_prompt_history()。"
        )
        return self.get_prompt_history(name, project_name=project_name)

    def search_prompts(
        self, filter_string: Optional[str] = None, project_name: Optional[str] = None
    ) -> List[Union[prompt_module.Prompt, prompt_module.ChatPrompt]]:
        """
        检索给定搜索参数的最新提示词版本（包括文本和聊天提示词）。

        Parameters:
            project_name: 要搜索的项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            filter_string: 使用Opik查询语言（OQL）缩小搜索范围的过滤器字符串。
                格式为："<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"

                支持的列包括：
                - `id`, `name`: 字符串字段
                - `tags`: 列表字段（仅使用"contains"运算符）
                - `created_by`: 字符串字段
                - `template_structure`: 字符串字段（"string"或"chat"）

                按列支持的运算符：
                - `id`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `name`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `created_by`: =, !=, contains, not_contains, starts_with, ends_with, >, <
                - `template_structure`: =, !=
                - `tags`: contains（仅）

                示例：
                - `tags contains "alpha"` - 按标签过滤
                - `tags contains "alpha" AND tags contains "beta"` - 按多个标签过滤
                - `name contains "summary"` - 按名称子字符串过滤
                - `created_by = "user@example.com"` - 按创建者过滤
                - `id starts_with "prompt_"` - 按ID前缀过滤
                - `template_structure = "text"` - 仅文本提示词
                - `template_structure = "chat"` - 仅聊天提示词

                如果未提供，将返回所有提示词（包括文本和聊天）。

        Returns:
            List[Union[Prompt, ChatPrompt]]: 找到的Prompt和/或ChatPrompt实例列表。
        """
        oql = opik_query_language.OpikQueryLanguage.for_traces(filter_string or "")
        parsed_filters = oql.get_filter_expressions()

        project_name = self._resolve_project_name(project_name)

        prompt_client_ = prompt_client.PromptClient(self._rest_client)
        search_results = prompt_client_.search_prompts(
            parsed_filters=parsed_filters, project_name=project_name
        )

        # 根据template_structure转换为Prompt或ChatPrompt对象
        prompts: List[Union[prompt_module.Prompt, prompt_module.ChatPrompt]] = []
        for result in search_results:
            if result.template_structure == "chat":
                prompts.append(
                    prompt_module.ChatPrompt.from_fern_prompt_version(
                        result.name,
                        result.prompt_version_detail,
                        project_name=project_name,
                    )
                )
            else:
                prompts.append(
                    prompt_module.Prompt.from_fern_prompt_version(
                        result.name,
                        result.prompt_version_detail,
                        project_name=project_name,
                    )
                )

        return prompts

    def create_optimization(
        self,
        dataset_name: str,
        objective_name: str,
        name: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
        optimization_id: Optional[str] = None,
        project_name: Optional[str] = None,
    ) -> optimization.Optimization:
        id = optimization_id or id_helpers.generate_id()

        project_name = self._resolve_project_name(project_name)

        self._rest_client.optimizations.create_optimization(
            id=id,
            name=name,
            dataset_name=dataset_name,
            objective_name=objective_name,
            status="running",
            metadata=metadata,
            project_name=project_name,
        )

        optimization_client = optimization.Optimization(
            id=id, rest_client=self._rest_client, project_name=project_name
        )
        return optimization_client

    def delete_optimizations(self, ids: List[str]) -> None:
        self._rest_client.optimizations.delete_optimizations_by_id(ids=ids)

    def get_optimization_by_id(self, id: str) -> optimization.Optimization:
        result = self._rest_client.optimizations.get_optimization_by_id(id)
        try:
            project = self.get_project(result.project_id)
            return optimization.Optimization(
                id=result.id,
                rest_client=self._rest_client,
                project_name=project.name,
            )
        except Exception as e:
            LOGGER.warning(
                f"Failed to get project for optimization with ID: {id}, reason: {e}"
            )

        return optimization.Optimization(id=id, rest_client=self._rest_client)

    def get_experiments_client(self) -> experiments_client.ExperimentsClient:
        """
        检索`ExperimentsClient`的实例。

        Returns:
            使用缓存的REST客户端初始化的ExperimentsClient实例。
        """
        return experiments_client.ExperimentsClient(self._rest_client)

    def get_prompts_client(self) -> prompt_client.PromptClient:
        """
        检索用于批量提示词操作的`PromptClient`实例。

        使用此客户端进行批量更新提示词版本标签等操作。

        Returns:
            使用缓存的REST客户端初始化的PromptClient实例。

        Example:
            prompts_client = client.get_prompts_client()
            prompts_client.batch_update_prompt_version_tags(
                version_ids=["version-id-1", "version-id-2"],
                tags=["production", "v2"]
            )
        """
        return prompt_client.PromptClient(self._rest_client)

    def _create_annotation_queue(
        self,
        name: str,
        queue_class: type[QueueT],
        project_name: Optional[str],
        description: Optional[str],
        instructions: Optional[str],
        comments_enabled: Optional[bool],
        feedback_definition_names: Optional[List[str]],
    ) -> QueueT:
        """创建具有指定范围的标注队列的辅助方法。"""
        project_name = self._resolve_project_name(project_name)

        project_id = rest_helpers.resolve_project_id_by_name(
            self._rest_client, project_name
        )
        queue_id = id_helpers.generate_id()

        self._rest_client.annotation_queues.create_annotation_queue(
            id=queue_id,
            project_id=project_id,
            name=name,
            scope=queue_class.SCOPE,
            description=description,
            instructions=instructions,
            comments_enabled=comments_enabled,
            feedback_definition_names=feedback_definition_names,
        )

        common_kwargs = {
            "id": queue_id,
            "name": name,
            "project_id": project_id,
            "rest_client": self._rest_client,
            "description": description,
            "instructions": instructions,
            "comments_enabled": comments_enabled,
            "feedback_definition_names": list(feedback_definition_names)
            if feedback_definition_names
            else None,
            "items_count": 0,
        }

        return queue_class(**common_kwargs)

    def create_traces_annotation_queue(
        self,
        name: str,
        project_name: Optional[str] = None,
        description: Optional[str] = None,
        instructions: Optional[str] = None,
        comments_enabled: Optional[bool] = None,
        feedback_definition_names: Optional[List[str]] = None,
    ) -> TracesAnnotationQueue:
        """
        创建用于traces的新标注队列。

        Args:
            name: 标注队列的名称。
            project_name: 项目的名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            description: 队列的可选描述。
            instructions: 审阅者的可选说明。
            comments_enabled: 是否启用项目评论。
            feedback_definition_names: 反馈定义名称的可选列表。

        Returns:
            TracesAnnotationQueue: 创建的traces标注队列对象。
        """
        return self._create_annotation_queue(
            name=name,
            queue_class=TracesAnnotationQueue,
            project_name=project_name,
            description=description,
            instructions=instructions,
            comments_enabled=comments_enabled,
            feedback_definition_names=feedback_definition_names,
        )

    def create_threads_annotation_queue(
        self,
        name: str,
        project_name: Optional[str] = None,
        description: Optional[str] = None,
        instructions: Optional[str] = None,
        comments_enabled: Optional[bool] = None,
        feedback_definition_names: Optional[List[str]] = None,
    ) -> ThreadsAnnotationQueue:
        """
        创建用于线程的新标注队列。

        Args:
            name: 标注队列的名称。
            project_name: 项目的名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            description: 队列的可选描述。
            instructions: 审阅者的可选说明。
            comments_enabled: 是否启用项目评论。
            feedback_definition_names: 反馈定义名称的可选列表。

        Returns:
            ThreadsAnnotationQueue: 创建的线程标注队列对象。
        """
        return self._create_annotation_queue(
            name=name,
            queue_class=ThreadsAnnotationQueue,
            project_name=project_name,
            description=description,
            instructions=instructions,
            comments_enabled=comments_enabled,
            feedback_definition_names=feedback_definition_names,
        )

    def get_traces_annotation_queue(self, queue_id: str) -> TracesAnnotationQueue:
        """
        按ID获取traces标注队列。

        Args:
            queue_id: 标注队列的ID。

        Returns:
            TracesAnnotationQueue: traces标注队列对象。

        Raises:
            OpikException: 如果未找到队列或不是traces队列。
        """
        return annotation_queue_rest_operations.get_traces_annotation_queue_by_id(
            rest_client=self._rest_client,
            queue_id=queue_id,
        )

    def get_threads_annotation_queue(self, queue_id: str) -> ThreadsAnnotationQueue:
        """
        按ID获取线程标注队列。

        Args:
            queue_id: 标注队列的ID。

        Returns:
            ThreadsAnnotationQueue: 线程标注队列对象。

        Raises:
            OpikException: 如果未找到队列或不是线程队列。
        """
        return annotation_queue_rest_operations.get_threads_annotation_queue_by_id(
            rest_client=self._rest_client,
            queue_id=queue_id,
        )

    def get_traces_annotation_queues(
        self,
        project_name: Optional[str] = None,
        max_results: int = 1000,
    ) -> List[TracesAnnotationQueue]:
        """
        获取项目的所有traces标注队列。

        Args:
            project_name: 项目的名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            max_results: 要返回的最大队列数。默认为1000。

        Returns:
            List[TracesAnnotationQueue]: traces标注队列对象列表。
        """
        project_id = rest_helpers.resolve_project_id_by_name(
            self._rest_client, self._resolve_project_name(project_name)
        )

        return annotation_queue_rest_operations.get_traces_annotation_queues(
            rest_client=self._rest_client,
            project_id=project_id,
            max_results=max_results,
        )

    def get_threads_annotation_queues(
        self,
        project_name: Optional[str] = None,
        max_results: int = 1000,
    ) -> List[ThreadsAnnotationQueue]:
        """
        获取项目的所有线程标注队列。

        Args:
            project_name: 项目的名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            max_results: 要返回的最大队列数。默认为1000。

        Returns:
            List[ThreadsAnnotationQueue]: 线程标注队列对象列表。
        """
        project_id = rest_helpers.resolve_project_id_by_name(
            self._rest_client, self._resolve_project_name(project_name)
        )

        return annotation_queue_rest_operations.get_threads_annotation_queues(
            rest_client=self._rest_client,
            project_id=project_id,
            max_results=max_results,
        )

    def delete_annotation_queue(self, queue_id: str) -> None:
        """
        按ID删除标注队列。

        Args:
            queue_id: 要删除的标注队列的ID。
        """
        self._rest_client.annotation_queues.delete_annotation_queue_batch(
            ids=[queue_id]
        )

    @overload
    def get_or_create_config(
        self,
        *,
        fallback: _ConfigT,
        project_name: Optional[str] = ...,
        env: Optional[str] = ...,
        version: Optional[str] = ...,
        timeout_in_seconds: Optional[int] = ...,
    ) -> _ConfigT: ...

    @overload
    def get_or_create_config(
        self,
        *,
        fallback: None = ...,
        project_name: Optional[str] = ...,
        env: Optional[str] = ...,
        version: Optional[str] = ...,
        timeout_in_seconds: Optional[int] = ...,
    ) -> Config: ...

    def get_or_create_config(
        self,
        *,
        fallback: Optional[Config] = None,
        project_name: Optional[str] = None,
        env: Optional[str] = None,
        version: Optional[str] = None,
        timeout_in_seconds: Optional[int] = 5,
    ) -> Config:
        """
        从后端获取配置，可选择性地从回退值自动创建。

        必须在用``@opik.track``装饰的函数内部调用。

        最多只能提供``env``或``version``中的一个。

        * ``env`` — 获取部署到环境的版本（例如``"staging"``）。
        * ``version`` — 按名称获取特定版本。特殊值``"latest"``获取项目中的最新版本；
          当配置完全不存在且提供了``fallback``时，从中自动创建。
        * 两者都不提供 — 等同于``env="prod"``。如果项目中配置完全不存在且提供了
          ``fallback``，则从中自动创建（后端将第一个版本标记为``"prod"``）。

        失败模式取决于是否提供了``fallback``：

        * **有回退值**：后端错误（超时、网络故障）返回``is_fallback=True``的回退实例。
          如果请求了明确的``env``/``version``但缺失，引发:class:`~opik.exceptions.ConfigNotFound`。
          如果配置完全不存在，从回退值自动创建。返回值是``type(fallback)``的实例。
        * **无回退值**：后端错误会重新引发。如果配置完全不存在，
          引发:class:`~opik.exceptions.ConfigNotFound`而不是自动创建。
          返回值是通用的``Config``实例——只有当回退值提供子类时才能进行类型化字段访问。

        如果后端蓝图缺少回退值类上声明的任何字段，引发:class:`~opik.exceptions.ConfigMismatch`。

        Args:
            fallback: 用户定义的``Config``子类的实例。提供时，如果后端不可达，
                用作返回值；自动创建时用作初始值。
            project_name: Opik项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            env: 要获取的环境标签（例如``"prod"``、``"staging"``）。
            version: 按名称获取特定版本。使用``"latest"``获取最新版本。
            timeout_in_seconds: 等待后端响应的最大秒数。有回退值时，超时返回回退值，
                缓存在后台继续刷新；无回退值时，引发超时异常。传入``None``无限期等待。
        """
        if fallback is not None and (
            not isinstance(fallback, Config) or type(fallback) is Config
        ):
            raise TypeError(
                "fallback must be an instance of a Config subclass, "
                f"got {type(fallback).__name__}"
            )

        if env is not None and version is not None:
            raise ValueError(
                "Specify at most one of 'env' (fetch by environment tag) "
                "or 'version' (fetch by version name)."
            )

        # 解析选择器：
        # - version="latest" → 获取最新蓝图；如果为空则自动创建。
        # - 明确的env或命名版本 → 按选择器获取；不自动创建。
        # - 两者都不提供 → 获取env="prod"；如果配置完全不存在则自动创建。
        if version == "latest":
            env = None
            version = None
            auto_create_if_empty = True
        elif env is None and version is None:
            env = "prod"
            auto_create_if_empty = True
        else:
            auto_create_if_empty = False

        resolved_project = self._resolve_project_name(project_name)
        manager = ConfigManager(
            project_name=resolved_project,
            rest_client_=self._rest_client,
        )
        resolved_cls = type(fallback) if fallback is not None else Config
        return resolved_cls._get_or_create_from_backend(
            manager,
            resolved_project,
            fallback=fallback,
            env=env,
            version=version,
            auto_create_if_empty=auto_create_if_empty,
            timeout_in_seconds=timeout_in_seconds,
        )

    def create_config(
        self,
        config: Config,
        project_name: Optional[str] = None,
        description: Optional[str] = None,
    ) -> str:
        """
        无条件地将配置版本写入后端。

        与:meth:`get_or_create_config`不同，此方法不需要``@opik.track``上下文，
        且始终执行写入——新版本的值会覆盖最新蓝图的值。

        Args:
            config: 用户定义的``Config``子类的实例。
            project_name: Opik项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            description: 与版本一起存储的可选描述。

        Returns:
            新写入蓝图的版本名称。
        """
        if not isinstance(config, Config) or type(config) is Config:
            raise TypeError(
                "config must be an instance of a Config subclass, "
                f"got {type(config).__name__}"
            )

        manager = ConfigManager(
            project_name=self._resolve_project_name(project_name),
            rest_client_=self._rest_client,
        )
        return config._create_from_instance(manager, description)

    def set_config_env(
        self,
        *,
        project_name: Optional[str] = None,
        version: str,
        env: str,
    ) -> None:
        """
        为特定配置版本标记环境名称。

        标记后，项目的``get_or_create_config(env=env)``将返回此版本。

        Args:
            project_name: Opik项目名称。如果未提供，则回退到活动项目上下文
                （来自@track或opik.project_context），然后使用客户端默认值。
            version: 要标记的蓝图版本名称。
            env: 环境名称（例如``"prod"``、``"staging"``）。
        """
        resolved_project = self._resolve_project_name(project_name)
        manager = ConfigManager(
            project_name=resolved_project,
            rest_client_=self._rest_client,
        )
        manager.set_env(version=version, env=env)

    def _resolve_project_name(self, explicitly_passed_value: Optional[str]) -> str:
        return helpers.resolve_project_name(
            explicitly_passed_value=explicitly_passed_value,
            value_from_config=self._project_name,
            value_from_context=context_storage.get_context_project_name(),
        )


_context_client_var: contextvars.ContextVar[Optional[Opik]] = contextvars.ContextVar(
    "_context_client_var", default=None
)
_global_singleton: Optional[Opik] = None
# Serializes lazy creation of the global singleton so concurrent cold-start
# callers share one client instead of racing to build several.
_global_singleton_lock = threading.Lock()


def get_current_client_raw() -> Optional[Opik]:
    """
    返回活动的Opik客户端，不自动创建。

    解析顺序：
    1. 上下文本地客户端（通过``set_global_client(client, context_wise=True)``设置）
    2. 全局单例（通过``set_global_client(client)``设置）
    3. 如果未设置客户端，返回``None``
    """
    client = _context_client_var.get()
    if client is not None:
        return client

    return _global_singleton


def get_global_client() -> Opik:
    """
    获取活动的Opik客户端，如果需要则创建一个。

    解析顺序：
    1. 上下文本地客户端（通过``set_global_client(client, context_wise=True)``设置）
    2. 全局单例（通过``set_global_client(client)``设置）
    3. 自动创建的默认客户端（首次调用时创建）
    """
    client = get_current_client_raw()
    if client is not None:
        return client

    global _global_singleton
    # Re-check under the lock: without it, concurrent cold-start callers (e.g. one
    # tracer shared by parallel pipelines) each build a client and its full
    # transport stack, and all but one are immediately discarded — wasteful, and
    # it races the shared connection-resource manager's build path.
    with _global_singleton_lock:
        client = get_current_client_raw()
        if client is not None:
            return client
        _global_singleton = Opik()
        return _global_singleton


def set_global_client(client: Opik, context_wise: bool = False) -> None:
    """
    设置活动的Opik客户端。

    Args:
        client: 要使用的Opik客户端实例。
        context_wise: 如果为True，则仅为当前上下文设置客户端（线程安全、异步安全）。
            如果为False，则替换全局单例。
    """
    if context_wise:
        _context_client_var.set(client)
    else:
        global _global_singleton
        _global_singleton = client


def reset_global_client(end_client: bool = True) -> None:
    """
    清除活动的Opik客户端。

    Args:
        end_client: 如果为True（默认），在清除前对全局单例调用``.end()``。
            当调用者独立管理客户端生命周期时，设置为False。
    """
    global _global_singleton
    if _global_singleton is not None:
        if end_client:
            _global_singleton.end()
        _global_singleton = None
    _context_client_var.set(None)


def get_client_cached() -> Opik:
    return get_global_client()
