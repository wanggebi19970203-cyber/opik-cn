import datetime
import logging
from typing import Any, Dict, List, Optional, Union

import opik.datetime_helpers as datetime_helpers
import opik.id_helpers as id_helpers
import opik.llm_usage as llm_usage
import opik.api_objects.attachment as attachment
from opik.message_processing import messages, streamer

from ..attachment import converters as attachment_converters

from opik.types import (
    DistributedTraceHeadersDict,
    ErrorInfoDict,
    LLMProvider,
    SpanType,
    TraceSource,
)
from opik import config as opik_config
from .. import constants, validation_helpers, helpers

LOGGER = logging.getLogger(__name__)


class Span:
    def __init__(
        self,
        id: str,
        trace_id: str,
        project_name: str,
        message_streamer: streamer.Streamer,
        url_override: str,
        source: TraceSource,
        parent_span_id: Optional[str] = None,
        config: Optional[opik_config.OpikConfig] = None,
        environment: Optional[str] = None,
    ):
        """
        Span 对象。不应直接创建此对象，而应使用 Trace 的 `span` 方法
        (:func:`opik.Opik.span`) 或另一个 Span 的 `span` 方法
        (:meth:`opik.Span.span`) 来创建。
        """
        self.id = id
        self.trace_id = trace_id
        self.parent_span_id = parent_span_id
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
        tags: Optional[List[str]] = None,
        usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None,
        model: Optional[str] = None,
        provider: Optional[Union[LLMProvider, str]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
    ) -> None:
        """
        结束 span 并更新其属性。

        此方法类似于 `update` 方法，但它会在未提供结束时间时自动计算结束时间。

        注意：启用批处理后，在 span 创建后不久调用此方法可能会导致数据丢失。
        替代方案是通过 ``client.span()`` 使用相同 ID 重新发送完整载荷——后端会
        覆盖先前的值。参见 https://www.comet.com/docs/opik/reference/python-sdk/troubleshooting/batching-and-updates

        Args:
            end_time: span 的结束时间。如果未提供，将使用当前时间。
            metadata: 与 span 关联的额外元数据。
            input: span 的输入数据。
            output: span 的输出数据。
            tags: 与 span 关联的标签列表。
            usage: span 的用量数据。为了在 UI 中显示输入、输出和总 token 数，
                用量必须包含 OpenAI 格式的键（可以在字典顶层附加传递，与原始用量并列）：
                prompt_tokens、completion_tokens 和 total_tokens。
                如果未找到 OpenAI 格式的键，Opik 会在用量格式可识别时尝试自动计算
                （可在 opik.LLMProvider 枚举中查看支持的提供商格式），但不保证成功。
            model: LLM 的名称。
            provider: LLM 的提供商。可在 `opik.LLMProvider` 枚举中找到 Opik 官方支持
                成本追踪的提供商。如果您的提供商不在列表中，请在我们的 GitHub 上提交 issue
                - https://github.com/comet-ml/opik。
                如果您的提供商不在列表中，仍可指定，但成本追踪功能将不可用。
            error_info: 包含错误信息的字典（通常在 span 函数执行失败时使用）。
            total_cost: span 的成本（单位：美元）。此值优先于 Opik 根据用量计算的成本。

        Returns:
            None
        """
        end_time = (
            end_time if end_time is not None else datetime_helpers.local_timestamp()
        )

        helpers.warn_if_batching_update(
            use_batching=self._streamer.use_batching,
            suppress_warning=bool(
                self._config and self._config.suppress_batching_update_warning
            ),
            method_name="Span.end()",
        )

        self._update(
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
        )

    def update(
        self,
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
    ) -> None:
        """
        更新 span 的属性。

        注意：启用批处理后，在 span 创建后不久调用此方法可能会导致数据丢失。
        替代方案是通过 ``client.span()`` 使用相同 ID 重新发送完整载荷——后端会
        覆盖先前的值。参见 https://www.comet.com/docs/opik/reference/python-sdk/troubleshooting/batching-and-updates

        Args:
            end_time: span 的结束时间。
            metadata: 与 span 关联的额外元数据。
            input: span 的输入数据。
            output: span 的输出数据。
            tags: 与 span 关联的标签列表。
            usage: span 的用量数据。为了在 UI 中显示输入、输出和总 token 数，
                用量必须包含 OpenAI 格式的键（可以在字典顶层附加传递，与原始用量并列）：
                prompt_tokens、completion_tokens 和 total_tokens。
                如果未找到 OpenAI 格式的键，Opik 会在用量格式可识别时尝试自动计算
                （可在 opik.LLMProvider 枚举中查看支持的提供商格式），但不保证成功。
            model: LLM 的名称。
            provider: LLM 的提供商。可在 `opik.LLMProvider` 枚举中找到 Opik 官方支持
                成本追踪的提供商。如果您的提供商不在列表中，请在我们的 GitHub 上提交 issue
                - https://github.com/comet-ml/opik。
                如果您的提供商不在列表中，仍可指定，但成本追踪功能将不可用。
            error_info: 包含错误信息的字典（通常在 span 函数执行失败时使用）。
            total_cost: span 的成本（单位：美元）。此值优先于 Opik 根据用量计算的成本。

        Returns:
            None
        """
        helpers.warn_if_batching_update(
            use_batching=self._streamer.use_batching,
            suppress_warning=bool(
                self._config and self._config.suppress_batching_update_warning
            ),
            method_name="Span.update()",
        )

        self._update(
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
        )

    def _update(
        self,
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
    ) -> None:
        update_span(
            id=self.id,
            trace_id=self.trace_id,
            parent_span_id=self.parent_span_id,
            url_override=self._url_override,
            message_streamer=self._streamer,
            project_name=self._project_name,
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
            source=self.source,
            environment=self._environment,
        )

    def span(
        self,
        id: Optional[str] = None,
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
        provider: LLMProvider = LLMProvider.OPENAI,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
        attachments: Optional[List[attachment.Attachment]] = None,
    ) -> "Span":
        """
        在当前 span 内创建一个新的子 span。

        Args:
            id: span 的 ID，应为 UUIDv7 格式。如果未提供，将自动生成新的 ID。
            name: span 的名称。
            type: span 的类型。默认为 "general"。
            start_time: span 的开始时间。如果未提供，将使用当前时间。
            end_time: span 的结束时间。
            metadata: 与 span 关联的额外元数据。
            input: span 的输入数据。
            output: span 的输出数据。
            tags: 与 span 关联的标签列表。
            usage: span 的用量数据。为了在 UI 中显示输入、输出和总 token 数，
                用量必须包含 OpenAI 格式的键（可以在字典顶层附加传递，与原始用量并列）：
                prompt_tokens、completion_tokens 和 total_tokens。
                如果未找到 OpenAI 格式的键，Opik 会在用量格式可识别时尝试自动计算
                （可在 opik.LLMProvider 枚举中查看支持的提供商格式），但不保证成功。
            model: LLM 的名称（此时 `type` 参数应设置为 `llm`）。
            provider: LLM 的提供商。可在 `opik.LLMProvider` 枚举中找到 Opik 官方支持
                成本追踪的提供商。如果您的提供商不在列表中，请在我们的 GitHub 上提交 issue
                - https://github.com/comet-ml/opik。
                如果您的提供商不在列表中，仍可指定，但成本追踪功能将不可用。
            error_info: 包含错误信息的字典（通常在 span 函数执行失败时使用）。
            total_cost: span 的成本（单位：美元）。此值优先于 Opik 根据用量计算的成本。
            attachments: 要上传到 span 的附件列表。

        Returns:
            Span: 创建的子 span 对象。
        """
        return create_span(
            trace_id=self.trace_id,
            project_name=self._project_name,
            url_override=self._url_override,
            message_streamer=self._streamer,
            span_id=id,
            parent_span_id=self.id,
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
        为 span 记录反馈分数。

        Args:
            name: 反馈分数的名称。
            value: 反馈分数的值。
            category_name: 反馈分数的类别名称。
            reason: 反馈分数的原因。

        Returns:
            None
        """
        add_span_feedback_batch_message = messages.AddSpanFeedbackScoresBatchMessage(
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

        self._streamer.put(add_span_feedback_batch_message)

    def get_distributed_trace_headers(self) -> DistributedTraceHeadersDict:
        """
        返回用于传递到远程节点上被追踪函数的请求头字典。
        """
        return {"opik_parent_span_id": self.id, "opik_trace_id": self.trace_id}


def create_span(
    trace_id: str,
    project_name: str,
    url_override: str,
    message_streamer: streamer.Streamer,
    span_id: Optional[str] = None,
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
    source: TraceSource = "sdk",
    config: Optional[opik_config.OpikConfig] = None,
    environment: Optional[str] = None,
) -> Span:
    """创建一个新的 span。"""
    # 如果未提供 span_id，则生成一个新的 UUID
    span_id = span_id if span_id is not None else id_helpers.generate_id()
    # 如果未提供开始时间，使用当前本地时间
    start_time = (
        start_time if start_time is not None else datetime_helpers.local_timestamp()
    )

    # 验证并解析用量数据，使其与后端兼容
    backend_compatible_usage = validation_helpers.validate_and_parse_usage(
        usage=usage,
        logger=LOGGER,
        provider=provider,
    )

    # 如果存在有效的用量数据，将其添加到元数据中
    if backend_compatible_usage is not None:
        metadata = helpers.add_usage_to_metadata(usage=usage, metadata=metadata)

    # 创建 span 消息并发送到消息流
    create_span_message = messages.CreateSpanMessage(
        span_id=span_id,
        trace_id=trace_id,
        project_name=project_name,
        parent_span_id=parent_span_id,
        name=name,
        type=type,
        start_time=start_time,
        end_time=end_time,
        input=input,
        output=output,
        metadata=metadata,
        tags=tags,
        usage=backend_compatible_usage,
        model=model,
        provider=provider,
        error_info=error_info,
        total_cost=total_cost,
        last_updated_at=datetime_helpers.local_timestamp(),
        source=source,
        environment=environment,
    )
    message_streamer.put(create_span_message)

    # 如果存在附件，逐个创建附件消息并发送
    if attachments is not None:
        for attachment_data in attachments:
            create_attachment_message = attachment_converters.attachment_to_message(
                attachment_data=attachment_data,
                entity_type="span",
                entity_id=span_id,
                project_name=project_name,
                url_override=url_override,
            )
            message_streamer.put(create_attachment_message)

    # 返回创建的 Span 对象
    return Span(
        id=span_id,
        parent_span_id=parent_span_id,
        trace_id=trace_id,
        message_streamer=message_streamer,
        project_name=project_name,
        url_override=url_override,
        source=source,
        config=config,
        environment=environment,
    )


def update_span(
    id: str,
    trace_id: str,
    parent_span_id: Optional[str],
    project_name: str,
    url_override: str,
    message_streamer: streamer.Streamer,
    source: TraceSource,
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
    environment: Optional[str] = None,
) -> None:
    """更新一个已存在的 span。"""
    # 验证并解析用量数据，使其与后端兼容
    backend_compatible_usage = validation_helpers.validate_and_parse_usage(
        usage=usage,
        logger=LOGGER,
        provider=provider,
    )

    # 如果存在有效的用量数据，将其添加到元数据中
    if backend_compatible_usage is not None:
        metadata = helpers.add_usage_to_metadata(usage=usage, metadata=metadata)

    # 创建更新 span 消息
    update_span_message = messages.UpdateSpanMessage(
        span_id=id,
        trace_id=trace_id,
        parent_span_id=parent_span_id,
        project_name=project_name,
        end_time=end_time,
        metadata=metadata,
        input=input,
        output=output,
        tags=tags,
        usage=backend_compatible_usage,
        model=model,
        provider=provider,
        error_info=error_info,
        total_cost=total_cost,
        source=source,
        environment=environment,
    )

    # 如果存在附件，逐个创建附件消息并发送
    if attachments is not None:
        for attachment_data in attachments:
            create_attachment_message = attachment_converters.attachment_to_message(
                attachment_data=attachment_data,
                entity_type="span",
                entity_id=id,
                project_name=project_name,
                url_override=url_override,
            )
            message_streamer.put(create_attachment_message)

    # 发送更新消息到消息流
    message_streamer.put(update_span_message)
