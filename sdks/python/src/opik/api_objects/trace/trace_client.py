import datetime
import logging
from typing import Any, Dict, List, Optional, Union

import opik.datetime_helpers as datetime_helpers
import opik.llm_usage as llm_usage
import opik.api_objects.attachment as attachment
from opik.message_processing import messages, streamer
from opik import config as opik_config
from opik.types import ErrorInfoDict, SpanType, LLMProvider, TraceSource
from .. import constants, helpers, span

LOGGER = logging.getLogger(__name__)


class Trace:
    def __init__(
        self,
        id: str,
        message_streamer: streamer.Streamer,
        project_name: str,
        url_override: str,
        source: TraceSource,
        config: opik_config.OpikConfig,
        environment: Optional[str] = None,
    ):
        """
        Trace 对象。不应直接创建此对象，请使用 :meth:`opik.Opik.trace` 来创建新的 trace。
        """
        self.id = id
        self._streamer = message_streamer
        self._project_name = project_name
        self._url_override = url_override
        self.source = source
        self._config = config
        self._environment = environment

    def end(
        self,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[Any]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        thread_id: Optional[str] = None,
    ) -> None:
        """
        结束 trace 并更新其属性。

        此方法与 `update` 方法类似，但它会自动计算结束时间（如果未提供）。

        注意：启用批处理后，在 trace 创建后不久调用此方法可能会导致数据丢失。
        替代方案是通过 ``client.trace()`` 使用相同的 ID 重新发送完整的数据负载，
        后端会覆盖之前的值。参见 https://www.comet.com/docs/opik/reference/python-sdk/troubleshooting/batching-and-updates

        Args:
            end_time: trace 的结束时间。如果未提供，将使用当前时间。
            metadata: 与 trace 关联的额外元数据。
            input: trace 的输入数据。
            output: trace 的输出数据。
            tags: 与 trace 关联的标签列表。
            error_info: 包含错误信息的字典（通常在 trace 函数失败时使用）。
            thread_id: 用于将多个 trace 分组到一个线程中。
                该标识符由用户定义，且在每个项目中必须唯一。

        Returns:
            None
        """
        end_time = (
            end_time if end_time is not None else datetime_helpers.local_timestamp()
        )

        helpers.warn_if_batching_update(
            use_batching=self._streamer.use_batching,
            suppress_warning=self._config.suppress_batching_update_warning,
            method_name="Trace.end()",
        )

        self._update(
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            error_info=error_info,
            thread_id=thread_id,
        )

    def update(
        self,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[Any]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        thread_id: Optional[str] = None,
    ) -> None:
        """
        更新 trace 的属性。

        注意：启用批处理后，在 trace 创建后不久调用此方法可能会导致数据丢失。
        替代方案是通过 ``client.trace()`` 使用相同的 ID 重新发送完整的数据负载，
        后端会覆盖之前的值。参见 https://www.comet.com/docs/opik/reference/python-sdk/troubleshooting/batching-and-updates

        Args:
            end_time: trace 的结束时间。
            metadata: 与 trace 关联的额外元数据。
            input: trace 的输入数据。
            output: trace 的输出数据。
            tags: 与 trace 关联的标签列表。
            error_info: 包含错误信息的字典（通常在 trace 函数失败时使用）。
            thread_id: 用于将多个 trace 分组到一个线程中。
                该标识符由用户定义，且在每个项目中必须唯一。

        Returns:
            None
        """
        helpers.warn_if_batching_update(
            use_batching=self._streamer.use_batching,
            suppress_warning=self._config.suppress_batching_update_warning,
            method_name="Trace.update()",
        )

        self._update(
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            error_info=error_info,
            thread_id=thread_id,
        )

    def _update(
        self,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[Any]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        thread_id: Optional[str] = None,
    ) -> None:
        update_trace(
            trace_id=self.id,
            project_name=self._project_name,
            message_streamer=self._streamer,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            error_info=error_info,
            thread_id=thread_id,
            source=self.source,
            environment=self._environment,
        )

    def span(
        self,
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
        model: Optional[str] = None,
        provider: Optional[Union[LLMProvider, str]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
        attachments: Optional[List[attachment.Attachment]] = None,
    ) -> span.Span:
        """
        在 trace 中创建一个新的 span。

        Args:
            id: span 的 ID 应为 UUIDv7 格式。如果未提供，将自动生成新的 ID。
            parent_span_id: 父 span 的 ID（如果存在）。
            name: span 的名称。
            type: span 的类型。默认为 "general"。
            start_time: span 的开始时间。如果未提供，将使用当前时间。
            end_time: span 的结束时间。
            metadata: 与 span 关联的额外元数据。
            input: span 的输入数据。
            output: span 的输出数据。
            tags: 与 span 关联的标签列表。
            usage: span 的用量数据。为了使输入、输出和总 token 数在 UI 中可见，
                usage 必须包含 OpenAI 格式的键（可以在字典顶层额外传递）：
                prompt_tokens、completion_tokens 和 total_tokens。
                如果未找到 OpenAI 格式的键，Opik 会尝试在识别用量格式时自动计算
                （可在 opik.LLMProvider 枚举中查看支持哪些提供商的格式），但不保证成功。
            model: LLM 的名称（此时 `type` 参数应为 == `llm`）。
            provider: LLM 的提供商。可在 `opik.LLMProvider` 枚举中找到 Opik 官方支持成本跟踪的提供商。
                如果您的提供商不在列表中，请在我们的 GitHub 上提交 issue - https://github.com/comet-ml/opik。
                如果您的提供商不在列表中，仍然可以指定它，但成本跟踪功能将不可用。
            error_info: 包含错误信息的字典（通常在 span 函数失败时使用）。
            total_cost: span 的成本（以美元计）。此值优先于 Opik 根据用量计算的成本。
            attachments: 要上传到 span 的附件列表。

        Returns:
            span.Span: 创建的 span 对象。
        """
        return span.span_client.create_span(
            trace_id=self.id,
            project_name=self._project_name,
            url_override=self._url_override,
            message_streamer=self._streamer,
            span_id=id,
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
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            attachments=attachments,
            source=self.source,
            config=self._config,
            environment=self._environment,
        )

    def log_feedback_score(
        self,
        name: str,
        value: float,
        category_name: Optional[str] = None,
        reason: Optional[str] = None,
    ) -> None:
        """
        为 trace 记录反馈分数。

        Args:
            name: 反馈分数的名称。
            value: 反馈分数的值。
            category_name: 反馈分数的类别名称。
            reason: 反馈分数的原因。

        Returns:
            None
        """
        add_trace_feedback_batch_message = messages.AddTraceFeedbackScoresBatchMessage(
            batch=[
                messages.FeedbackScoreMessage(
                    id=self.id,
                    name=name,
                    value=value,
                    category_name=category_name,
                    reason=reason,
                    source=constants.FEEDBACK_SCORE_SOURCE_SDK,
                    project_name=self._project_name,
                )
            ],
        )

        self._streamer.put(add_trace_feedback_batch_message)


def update_trace(
    trace_id: str,
    project_name: str,
    message_streamer: streamer.Streamer,
    source: TraceSource,
    end_time: Optional[datetime.datetime] = None,
    metadata: Optional[Dict[str, Any]] = None,
    input: Optional[Dict[str, Any]] = None,
    output: Optional[Dict[str, Any]] = None,
    tags: Optional[List[Any]] = None,
    error_info: Optional[ErrorInfoDict] = None,
    thread_id: Optional[str] = None,
    environment: Optional[str] = None,
) -> None:
    """
    使用新信息更新现有的 trace。
    此函数向提供的 message_streamer 发送 UpdateTraceMessage，
    允许更新 trace 的各种字段，如结束时间、元数据、输入、输出、标签、错误信息和线程关联。

    Args:
        trace_id: 要更新的 trace 的唯一标识符。
        project_name: 与 trace 关联的项目名称。
        message_streamer: 用于发送更新消息的消息流处理器。
        end_time: trace 的结束时间。默认为 None。
        metadata: trace 的额外元数据。默认为 None。
        input: 与 trace 关联的输入数据。默认为 None。
        output: 与 trace 关联的输出数据。默认为 None。
        tags: 与 trace 关联的标签列表。默认为 None。
        error_info: 与 trace 相关的错误信息。默认为 None。
        thread_id: 与 trace 关联的线程 ID。默认为 None。
        source: 更新的来源。可以是 "sdk"、"experiment" 或 "optimization"。

    Returns:
        None

    使用说明：
        - 此函数不返回值；它向消息流处理器发送更新消息。
        - 除 trace_id、project_name 和 message_streamer 外，所有参数都是可选的。
        - 只有提供的字段才会在 trace 中更新。
    """
    update_trace_message = messages.UpdateTraceMessage(
        trace_id=trace_id,
        project_name=project_name,
        end_time=end_time,
        metadata=metadata,
        input=input,
        output=output,
        tags=tags,
        error_info=error_info,
        thread_id=thread_id,
        source=source,
        environment=environment,
    )
    message_streamer.put(update_trace_message)
