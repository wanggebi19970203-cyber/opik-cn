import logging
from typing import Any, Callable, Dict, List, Optional, Type

import httpx
import pydantic
import tenacity

from opik import dict_utils, exceptions, logging_messages
from opik.file_upload import base_upload_manager, types as upload_types
from opik.file_upload.s3_multipart_upload import s3_upload_error
from opik.rate_limit import rate_limit
from opik.rest_api import client as rest_api_client, core as rest_api_core
from opik.rest_api.types import (
    feedback_score_batch_item,
    feedback_score_batch_item_thread,
    guardrail,
    experiment_item,
    span_write,
    trace_write,
)

from . import assertion_results_processor, message_processors
from .. import data_loss, encoder_helpers, messages, payload_truncation, permissions
from ..replay import replay_manager, db_manager


LOGGER = logging.getLogger(__name__)


MessageProcessingHandler = Callable[[messages.BaseMessage], None]


class OpikMessageProcessor(message_processors.BaseMessageProcessor):
    def __init__(
        self,
        rest_client: rest_api_client.OpikApi,
        file_upload_manager: base_upload_manager.BaseFileUploadManager,
        fallback_replay_manager: replay_manager.ReplayManager,
        unauthorized_message_types_registry: permissions.UnauthorizedMessageTypeRegistry,
        data_loss_tracker: data_loss.DataLossTracker,
        batch_memory_limit_mb: int = 50,
        max_payload_size_mb: Optional[float] = None,
        active: bool = True,
    ):
        self._rest_client = rest_client
        self._file_uploader = file_upload_manager
        self._batch_memory_limit_mb = batch_memory_limit_mb
        # 每个 span 的大小限制（MB）。超大的 span 会在发送前被截断。
        # None 会禁用检查。
        self._max_payload_size_mb = max_payload_size_mb
        self._is_active = active
        self._replay_manager = fallback_replay_manager
        self._unauthorized_message_types_registry = unauthorized_message_types_registry
        self._data_loss_tracker = data_loss_tracker

        self._assertion_results_processor = (
            assertion_results_processor.AssertionResultsMessageProcessor(
                rest_client=rest_client,
            )
        )

        self._handlers: Dict[Type, MessageProcessingHandler] = {
            messages.CreateSpanMessage: self._process_create_span_message,  # type: ignore
            messages.CreateTraceMessage: self._process_create_trace_message,  # type: ignore
            messages.UpdateSpanMessage: self._process_update_span_message,  # type: ignore
            messages.UpdateTraceMessage: self._process_update_trace_message,  # type: ignore
            messages.AddTraceFeedbackScoresBatchMessage: self._process_add_trace_feedback_scores_batch_message,  # type: ignore
            messages.AddSpanFeedbackScoresBatchMessage: self._process_add_span_feedback_scores_batch_message,  # type: ignore
            messages.AddThreadsFeedbackScoresBatchMessage: self._process_add_threads_feedback_scores_batch_message,  # type: ignore
            messages.CreateSpansBatchMessage: self._process_create_spans_batch_message,  # type: ignore
            messages.CreateTraceBatchMessage: self._process_create_traces_batch_message,  # type: ignore
            messages.GuardrailBatchMessage: self._process_guardrail_batch_message,  # type: ignore
            messages.AddAssertionResultsBatchMessage: self._process_add_assertion_results_batch_message,  # type: ignore
            messages.CreateExperimentItemsBatchMessage: self._process_create_experiment_items_batch_message,  # type: ignore
            messages.CreateAttachmentMessage: self._process_create_attachment,  # type: ignore
            messages.AttachmentSupportingMessage: self._noop_handler,  # type: ignore
        }
        # 需要在重放时忽略的消息列表，因为它们不用于与服务器通信；
        # AttachmentSupportingMessage 只是一个标记性的容器消息，后续用于提取
        # CreateAttachmentMessage 和经过预处理后的原始消息
        self._ignored_message_types_for_replay = [messages.AttachmentSupportingMessage]

    def is_active(self) -> bool:
        return self._is_active

    def register_message_handler(
        self, message_type: Type, handler: MessageProcessingHandler
    ) -> None:
        """为特定消息类型注册处理程序。"""
        self._handlers[message_type] = handler

    def process(self, message: messages.BaseMessage) -> None:
        if not self.is_active():
            return

        # 检查该消息类型是否被授权处理
        if not self._unauthorized_message_types_registry.is_authorized(
            message.message_type
        ):
            LOGGER.debug(
                "未授权的消息类型：'%s' —— 已从处理中忽略。",
                message.message_type,
            )
            self._record_data_loss(
                message, data_loss.FailureReason.UNAUTHORIZED, status_code=401
            )
            return

        message_type = type(message)
        handler = self._handlers.get(message_type)
        if handler is None:
            LOGGER.debug("未知的消息类型 - %s", message_type.__name__)
            return

        should_register_message = not self._should_ignore_replay_for_message_type(
            message
        )
        should_unregister_message = (
            self._replay_manager.has_server_connection and should_register_message
        )
        if isinstance(message, messages.CreateAttachmentMessage):
            # 上传完成后会由回调将其注销
            should_unregister_message = False

        try:
            if should_register_message:
                # 处理可重放的消息
                if self._replay_manager.has_server_connection:
                    # 将消息注册到重放管理器并处理它
                    self._replay_manager.register_message(message)

                    handler(message)
                else:
                    # 将消息注册为失败，并跳过发送到后端
                    self._replay_manager.register_message(
                        message, status=db_manager.MessageStatus.failed
                    )
            else:
                # 处理不可重放的消息（被忽略的类型完全绕过重放）
                handler(message)

        except rest_api_core.ApiError as exception:
            if exception.status_code == 409:
                # 有时重试机制会以发送两次相同请求的方式工作。
                # 如果后端拒绝了第二个请求，我们不希望用户看到错误。
                if should_unregister_message:
                    self._replay_manager.unregister_message(message.message_id)  # type: ignore
                return
            elif exception.status_code == 429:
                if exception.headers is not None:
                    rate_limiter = rate_limit.parse_rate_limit(exception.headers)
                    if rate_limiter is not None:
                        raise exceptions.OpikCloudRequestsRateLimited(
                            headers=exception.headers,
                            retry_after=rate_limiter.retry_after(),
                        )
            elif exception.status_code == 401:
                LOGGER.error(
                    "未授权的消息类型 '%s' 处理请求：%s",
                    message.message_type,
                    exception.body,
                )
                # 将该消息类型注册为未授权，避免再次发送到后端
                self._unauthorized_message_types_registry.add(message.message_type)
                self._record_data_loss(
                    message,
                    data_loss.FailureReason.UNAUTHORIZED,
                    status_code=401,
                    detail=str(exception.body),
                )
            else:
                error_tracking_extra = _generate_error_tracking_extra(
                    exception, message
                )
                LOGGER.error(
                    logging_messages.FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER,
                    message_type.__name__,
                    str(exception),
                    extra={"error_tracking_extra": error_tracking_extra},
                )
                self._record_data_loss(
                    message,
                    data_loss.FailureReason.from_status_code(exception.status_code),
                    status_code=exception.status_code,
                    detail=str(exception),
                )
        except tenacity.RetryError as retry_error:
            cause = retry_error.last_attempt.exception()
            error_tracking_extra = _generate_error_tracking_extra(cause, message)
            LOGGER.error(
                logging_messages.FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER,
                message_type.__name__,
                f"{cause.__class__.__name__} - {cause}",
                extra={"error_tracking_extra": error_tracking_extra},
            )
            LOGGER.warning(logging_messages.MAKE_SURE_OPIK_IS_CONFIGURED_CORRECTLY)
            self._record_data_loss(
                message,
                data_loss.FailureReason.from_status_code(
                    error_tracking_extra.get("status_code")
                ),
                status_code=error_tracking_extra.get("status_code"),
                detail=f"{cause.__class__.__name__} - {cause}",
            )
        except pydantic.ValidationError as validation_error:
            error_tracking_extra = _generate_error_tracking_extra(
                validation_error, message
            )
            LOGGER.error(
                "处理消息失败：'%s'，原因是输入数据校验错误：\n%s\n",
                message_type.__name__,
                validation_error,
                exc_info=True,
                extra={"error_tracking_extra": error_tracking_extra},
            )
            self._record_data_loss(
                message,
                data_loss.FailureReason.SERIALIZATION,
                detail=str(validation_error),
            )
        except (httpx.ConnectError, httpx.TimeoutException) as ex:
            should_unregister_message = False
            LOGGER.warning(
                "处理消息失败：'%s'，原因是连接错误。将在连接恢复后重试。",
                message_type.__name__,
            )
            # 因连接错误，通过重放管理器将消息标记为失败
            self._replay_manager.message_sent_failed(
                message.message_id,  # type: ignore
                failure_reason=str(ex),
            )
        except Exception as exception:
            error_tracking_extra = _generate_error_tracking_extra(exception, message)
            LOGGER.error(
                logging_messages.FAILED_TO_PROCESS_MESSAGE_IN_BACKGROUND_STREAMER,
                message_type.__name__,
                str(exception),
                exc_info=True,
                extra={"error_tracking_extra": error_tracking_extra},
            )
            LOGGER.warning(logging_messages.MAKE_SURE_OPIK_IS_CONFIGURED_CORRECTLY)
            self._record_data_loss(
                message, data_loss.FailureReason.UNKNOWN, detail=str(exception)
            )

        # 由于消息已送达或发生其他错误，从重放管理器中注销该消息
        if should_unregister_message:
            self._replay_manager.unregister_message(message.message_id)  # type: ignore

    def _record_data_loss(
        self,
        message: messages.BaseMessage,
        reason: data_loss.FailureReason,
        status_code: Optional[int] = None,
        detail: Optional[str] = None,
    ) -> None:
        self._data_loss_tracker.record(
            data_loss.FailedMessageInfo(
                message_type=type(message).__name__,
                reason=reason,
                item_count=message.item_count,
                status_code=status_code,
                detail=detail,
            )
        )

    def _process_create_span_message(
        self,
        message: messages.CreateSpanMessage,
    ) -> None:
        create_span_kwargs = message.as_payload_dict()
        cleaned_create_span_kwargs = dict_utils.remove_none_from_dict(
            create_span_kwargs
        )
        cleaned_create_span_kwargs = encoder_helpers.encode_and_anonymize(
            cleaned_create_span_kwargs,
            fields_to_anonymize=message.fields_to_anonymize(),
            object_type="span",
        )

        # 在发送前立即强制执行每个对象的大小限制，此时附件提取已剥离/上传了大附件。
        if self._max_payload_size_mb is not None:
            payload_truncation.truncate_kwargs_if_needed(
                cleaned_create_span_kwargs, self._max_payload_size_mb, kind="span"
            )

        LOGGER.debug("创建 span 请求：%s", cleaned_create_span_kwargs)
        self._rest_client.spans.create_span(**cleaned_create_span_kwargs)

    def _process_create_trace_message(
        self,
        message: messages.CreateTraceMessage,
    ) -> None:
        create_trace_kwargs = message.as_payload_dict()
        cleaned_create_trace_kwargs = dict_utils.remove_none_from_dict(
            create_trace_kwargs
        )
        cleaned_create_trace_kwargs = encoder_helpers.encode_and_anonymize(
            cleaned_create_trace_kwargs,
            fields_to_anonymize=message.fields_to_anonymize(),
            object_type="trace",
        )

        # 与 span 相同的每个对象大小上限：@track / 手动 trace 记录会把负载镜像到 trace 上，
        # 因此超大的 trace input/output 也必须被限制。
        if self._max_payload_size_mb is not None:
            payload_truncation.truncate_kwargs_if_needed(
                cleaned_create_trace_kwargs, self._max_payload_size_mb, kind="trace"
            )

        LOGGER.debug("创建 trace 请求：%s", cleaned_create_trace_kwargs)
        self._rest_client.traces.create_trace(**cleaned_create_trace_kwargs)

    def _process_update_span_message(
        self,
        message: messages.UpdateSpanMessage,
    ) -> None:
        update_span_kwargs = message.as_payload_dict()

        cleaned_update_span_kwargs = dict_utils.remove_none_from_dict(
            update_span_kwargs
        )
        cleaned_update_span_kwargs = encoder_helpers.encode_and_anonymize(
            cleaned_update_span_kwargs,
            fields_to_anonymize=message.fields_to_anonymize(),
            object_type="span",
        )

        # 对 update 也强制执行每个对象的大小限制：通过 update_span 附加的超大
        # output/input（例如在 create 已刷新后调用 span.end(output=...)）
        # 否则会绕过上限。
        if self._max_payload_size_mb is not None:
            payload_truncation.truncate_kwargs_if_needed(
                cleaned_update_span_kwargs, self._max_payload_size_mb, kind="span"
            )

        LOGGER.debug("更新 span 请求：%s", cleaned_update_span_kwargs)
        self._rest_client.spans.update_span(**cleaned_update_span_kwargs)

    def _process_update_trace_message(
        self,
        message: messages.UpdateTraceMessage,
    ) -> None:
        update_trace_kwargs = message.as_payload_dict()

        cleaned_update_trace_kwargs = dict_utils.remove_none_from_dict(
            update_trace_kwargs
        )
        cleaned_update_trace_kwargs = encoder_helpers.encode_and_anonymize(
            cleaned_update_trace_kwargs,
            fields_to_anonymize=message.fields_to_anonymize(),
            object_type="trace",
        )

        # 限制通过 update 附加的超大 trace output/input（例如 @track 装饰器
        # 在根函数返回时设置 trace 输出）。
        if self._max_payload_size_mb is not None:
            payload_truncation.truncate_kwargs_if_needed(
                cleaned_update_trace_kwargs, self._max_payload_size_mb, kind="trace"
            )

        LOGGER.debug("更新 trace 请求：%s", cleaned_update_trace_kwargs)
        self._rest_client.traces.update_trace(**cleaned_update_trace_kwargs)
        LOGGER.debug("已发送 trace %s", message.trace_id)

    def _process_add_span_feedback_scores_batch_message(
        self,
        message: messages.AddSpanFeedbackScoresBatchMessage,
    ) -> None:
        scores = [
            feedback_score_batch_item.FeedbackScoreBatchItem(**score_message.__dict__)
            for score_message in message.batch
        ]

        LOGGER.debug("添加 span 反馈评分的请求，数量：%d", len(scores))

        self._rest_client.spans.score_batch_of_spans(
            scores=scores,
        )
        LOGGER.debug("已发送 span 反馈评分批次 %d", len(scores))

    def _process_add_trace_feedback_scores_batch_message(
        self,
        message: messages.AddTraceFeedbackScoresBatchMessage,
    ) -> None:
        scores = [
            feedback_score_batch_item.FeedbackScoreBatchItem(**score_message.__dict__)
            for score_message in message.batch
        ]

        LOGGER.debug("添加 trace 反馈评分请求：%d", len(scores))

        self._rest_client.traces.score_batch_of_traces(
            scores=scores,
        )
        LOGGER.debug("已发送 trace 反馈评分批次，数量 %d", len(scores))

    def _process_add_threads_feedback_scores_batch_message(
        self,
        message: messages.AddThreadsFeedbackScoresBatchMessage,
    ) -> None:
        scores = [
            feedback_score_batch_item_thread.FeedbackScoreBatchItemThread(
                **score_message.as_payload_dict()
            )
            for score_message in message.batch
        ]

        try:
            LOGGER.debug("添加线程反馈评分请求，数量 %d", len(scores))
            self._rest_client.traces.score_batch_of_threads(
                scores=scores,
            )
            LOGGER.debug(
                "已发送线程反馈评分批次，数量 %d", len(scores)
            )
        except rest_api_core.ApiError as exception:
            # 对于 AddThreadsFeedbackScoresBatchMessage，如果线程未关闭，后端会拒绝该请求，
            # 而用户可能并未意识到这一点。因此，我们显示警告消息。
            if exception.status_code == 409:
                LOGGER.warning(
                    "线程反馈评分批次被后端拒绝，原因：'%s'",
                    exception.body,
                )
            # 继续向上传播，交由统一的错误处理器处理
            raise exception

    def _process_create_spans_batch_message(
        self, message: messages.CreateSpansBatchMessage
    ) -> None:
        LOGGER.debug("创建 span 批次请求，数量 %d", len(message.batch))
        # 在发送前立即强制执行每个对象的大小限制，此时附件提取已剥离/上传了大附件。
        batch: List[span_write.SpanWrite] = message.batch
        if self._max_payload_size_mb is not None:
            batch = payload_truncation.truncate_writes(
                batch, self._max_payload_size_mb, kind="span"
            )
        self._rest_client.spans.create_spans(spans=batch)
        LOGGER.debug("已发送 span 批次，数量 %d", len(batch))

    def _process_create_traces_batch_message(
        self, message: messages.CreateTraceBatchMessage
    ) -> None:
        LOGGER.debug("创建 trace 批次请求，数量 %d", len(message.batch))
        batch: List[trace_write.TraceWrite] = message.batch
        if self._max_payload_size_mb is not None:
            batch = payload_truncation.truncate_writes(
                batch, self._max_payload_size_mb, kind="trace"
            )
        self._rest_client.traces.create_traces(traces=batch)
        LOGGER.debug("已发送 trace 批次，数量 %d", len(batch))

    def _process_guardrail_batch_message(
        self,
        message: messages.GuardrailBatchMessage,
    ) -> None:
        batch = []

        for message_item in message.batch:
            guardrail_batch_item_message = guardrail.Guardrail(**message_item.__dict__)
            batch.append(guardrail_batch_item_message)

        self._rest_client.guardrails.create_guardrails(guardrails=batch)

    def _process_add_assertion_results_batch_message(
        self,
        message: messages.AddAssertionResultsBatchMessage,
    ) -> None:
        self._assertion_results_processor.process(message)

    def _process_create_experiment_items_batch_message(
        self,
        message: messages.CreateExperimentItemsBatchMessage,
    ) -> None:
        experiment_items_batch = [
            experiment_item.ExperimentItem(
                id=item.id,
                experiment_id=item.experiment_id,
                dataset_item_id=item.dataset_item_id,
                trace_id=item.trace_id,
                project_name=item.project_name,
                execution_policy=item.execution_policy,
            )
            for item in message.batch
        ]

        LOGGER.debug(
            "创建实验条目批次请求，数量 %d",
            len(experiment_items_batch),
        )
        self._rest_client.experiments.create_experiment_items(
            experiment_items=experiment_items_batch
        )
        LOGGER.debug(
            "已发送实验条目批次，数量 %d", len(experiment_items_batch)
        )

    def _process_create_attachment(
        self, message: messages.CreateAttachmentMessage
    ) -> None:
        LOGGER.debug("正在处理创建附件消息")
        self._file_uploader.upload(
            message,
            on_upload_failed=self._on_upload_failed_callback(message.message_id),  # type: ignore
            on_upload_success=self._on_upload_success_callback(message.message_id),  # type: ignore
        )
        LOGGER.debug("已上传附件 %s", message.file_name)

    def _on_upload_failed_callback(
        self, message_id: int
    ) -> upload_types.OnUploadFailureCallback:
        def _callback(error: Exception) -> None:
            if (
                isinstance(error, httpx.ConnectError)
                or isinstance(error, httpx.TimeoutException)
                or (
                    isinstance(error, s3_upload_error.S3UploadError)
                    and error.connection_error
                )
            ):
                self._replay_manager.message_sent_failed(
                    message_id, failure_reason=str(error)
                )
            else:
                # 这是不可恢复的错误——注销该消息
                self._replay_manager.unregister_message(message_id)

        return _callback

    def _on_upload_success_callback(
        self, message_id: int
    ) -> upload_types.OnUploadSuccessCallback:
        def _callback() -> None:
            self._replay_manager.unregister_message(message_id)

        return _callback

    def _noop_handler(self, message: messages.BaseMessage) -> None:
        # 直接忽略该消息
        pass

    def _should_ignore_replay_for_message_type(
        self, message: messages.BaseMessage
    ) -> bool:
        message_type = type(message)
        if message_type in self._ignored_message_types_for_replay:
            LOGGER.debug(
                "消息类型 %s 在忽略列表中，重放管理将跳过它。",
                message_type.__name__,
            )
            return True
        return False


def _generate_error_tracking_extra(
    exception: Exception, message: messages.BaseMessage
) -> Dict[str, Any]:
    result: Dict[str, Any] = {"exception": exception}

    if isinstance(exception, rest_api_core.ApiError):
        fingerprint = [
            type(message).__name__,
            type(exception).__name__,
            str(exception.status_code),
        ]
        result["fingerprint"] = fingerprint
        result["status_code"] = exception.status_code

    return result
