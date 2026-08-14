package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.domain.CommentDAO;
import com.comet.opik.domain.CommentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.attachment.EntityType.TRACE;

@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TraceDeletedListener {

    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull CommentService commentService;
    private final @NonNull AttachmentService attachmentService;
    private final @NonNull SpanService spanService;

    /**
     * 通过异步删除相关实体来处理 TracesDeleted 事件。
     * 这包括与被删除追踪关联的反馈评分、评论、附件和跨度。
     *
     * @param event 包含被删除追踪 ID 的 TracesDeleted 事件
     */
    @Subscribe
    public void onTracesDeleted(@NonNull TracesDeleted event) {
        Set<UUID> traceIds = event.traceIds();
        String workspaceId = event.workspaceId();
        String userName = event.userName();
        UUID projectId = event.projectId();

        log.info(
                "收到工作区 '{}' 的 TracesDeleted 事件，追踪数量：'{}'。正在处理相关实体删除",
                workspaceId, traceIds.size());

        processTraceDeletion(traceIds, projectId)
                .doOnError(error -> {
                    log.error(
                            "处理工作区 '{}' 的 TracesDeleted 事件失败，追踪数量：'{}'，错误：'{}'",
                            workspaceId, traceIds.size(), error.getMessage());
                    log.error("处理追踪相关实体删除时出错", error);
                })
                .doOnSuccess(__ -> log.info(
                        "成功处理工作区 '{}' 的 TracesDeleted 事件，追踪数量：'{}'",
                        workspaceId, traceIds.size()))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .subscribe();
    }

    /**
     * 处理与追踪相关的所有实体的删除。
     * 此方法按正确顺序处理删除，以维护引用完整性。
     *
     * @param traceIds 应删除其相关实体的追踪 ID 集合
     * @return 当所有相关实体都已删除时完成的 Mono
     */
    private Mono<Void> processTraceDeletion(Set<UUID> traceIds, UUID projectId) {
        log.info("开始删除追踪的相关实体，数量 '{}'", traceIds.size());

        return feedbackScoreService.deleteByTraceIds(traceIds, projectId)
                .then(Mono.defer(() -> commentService.deleteByEntityIds(CommentDAO.EntityType.TRACE, traceIds,
                        projectId)))
                .then(Mono.defer(() -> attachmentService.deleteByEntityIds(TRACE, traceIds, projectId)))
                .then(Mono.defer(() -> spanService.deleteByTraceIds(traceIds, projectId)));
    }
}
