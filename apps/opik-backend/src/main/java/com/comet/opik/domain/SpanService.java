package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanBatchUpdate;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.UsageByWorkspaceProjectUserResponse;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.api.events.SpansDeleted;
import com.comet.opik.api.events.SpansUpdated;
import com.comet.opik.domain.attachment.AttachmentReinjectorService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentStripperService;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.BinaryOperatorUtils;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.attachment.EntityType.SPAN;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
public class SpanService {

    public static final String PARENT_SPAN_IS_MISMATCH = "parent_span_id does not match the existing span";
    public static final String TRACE_ID_MISMATCH = "trace_id does not match the existing span";
    public static final String SPAN_KEY = "Span";
    public static final String SPAN_TRACE_KEY = "Span trace";
    public static final String SPAN_PARENT_KEY = "Span parent";
    public static final String PROJECT_AND_WORKSPACE_NAME_MISMATCH = "Project name and workspace name do not match the existing span";

    private final @NonNull SpanDAO spanDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull LockService lockService;
    private final @NonNull CommentService commentService;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull AttachmentService attachmentService;
    private final @NonNull AttachmentStripperService attachmentStripperService;
    private final @NonNull AttachmentReinjectorService attachmentReinjectorService;
    private final @NonNull EventBus eventBus;
    private final @NonNull DeletionEventDAO deletionEventDAO;
    private final @NonNull @Config OpikConfiguration config;

    @WithSpan
    public Mono<Span.SpanPage> find(int page, int size, @NonNull SpanSearchCriteria searchCriteria) {
        log.info("按 '{}' 查找 span", searchCriteria);

        return findProjectAndVerifyVisibility(searchCriteria)
                .flatMap(resolvedCriteria -> spanDAO.find(page, size, resolvedCriteria)
                        .flatMap(spanPage -> {
                            // 如果 stripAttachments=false，则将附件重新注入所有 span
                            if (!resolvedCriteria.stripAttachments()) {
                                return Flux.fromIterable(spanPage.content())
                                        .concatMap(span -> attachmentReinjectorService.reinjectAttachments(span,
                                                !resolvedCriteria.stripAttachments()))
                                        .collectList()
                                        .map(reinjectedSpans -> spanPage.toBuilder()
                                                .content(reinjectedSpans)
                                                .build());
                            }
                            return Mono.just(spanPage);
                        }));
    }

    @WithSpan
    public Mono<Boolean> existsByProjectId(@NonNull SpanSearchCriteria searchCriteria) {
        return findProjectAndVerifyVisibility(searchCriteria)
                .flatMap(spanDAO::existsByProjectId)
                .switchIfEmpty(Mono.just(false));
    }

    private Mono<SpanSearchCriteria> findProjectAndVerifyVisibility(SpanSearchCriteria searchCriteria) {
        return projectService
                .resolveProjectIdAndVerifyVisibility(searchCriteria.projectId(), searchCriteria.projectName())
                .map(projectId -> searchCriteria.toBuilder().projectId(projectId).build());
    }

    @WithSpan
    public Mono<Span> getById(@NonNull UUID id) {
        return getById(id, false);
    }

    @WithSpan
    public Mono<Span> getById(@NonNull UUID id, boolean stripAttachments) {
        return Mono.deferContextual(ctx -> spanDAO.getById(id)
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Span", id))))
                .flatMap(span -> {
                    Project project = projectService.get(span.projectId(), ctx.get(RequestContext.WORKSPACE_ID));
                    return Mono.just(span.toBuilder()
                            .projectName(project.name())
                            .build());
                }))
                .flatMap(span -> attachmentReinjectorService.reinjectAttachments(span, !stripAttachments));
    }

    @WithSpan
    public Flux<Span> getByTraceIds(@NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Flux.empty();
        }

        log.info("获取 '{}' 条 trace 的 span", traceIds.size());

        return spanDAO.getByTraceIds(traceIds)
                .flatMap(span -> attachmentReinjectorService.reinjectAttachments(span, true));
    }

    /**
     * 跨给定 trace ID 的所有 span 的近似序列化大小（字节）。供 trace 线程在线评分器使用，
     * 以在不将 span 加载到堆内存的情况下，确定内联与 agentic 工具路由决策的规模
     * （OPIK-7454）。不做附件重新注入——它是纯聚合，因此也跳过了完整获取所支付的
     * 每个 span 的附件解析开销。输入为空时返回 0。
     */
    @WithSpan
    public Mono<Long> getSpansSizeByTraceIds(Set<UUID> traceIds) {
        if (CollectionUtils.isEmpty(traceIds)) {
            return Mono.just(0L);
        }

        log.info("估算 '{}' 条 trace 的 span 大小", traceIds.size());

        return spanDAO.getSpansSizeByTraceIds(traceIds);
    }

    @WithSpan
    public Flux<Span> getByIds(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Flux.empty();
        }

        log.info("按ID获取 '{}' 个 span", ids.size());

        return spanDAO.getByIds(ids)
                .flatMap(span -> attachmentReinjectorService.reinjectAttachments(span, true));
    }

    @WithSpan
    public Mono<UUID> create(@NonNull Span span) {
        var id = span.id() == null ? idGenerator.generateId() : span.id();
        var projectName = WorkspaceUtils.getProjectName(span.projectName());
        return idGenerator
                .validateIdAsync(id, SPAN_KEY)
                .then(Mono.fromRunnable(() -> validateSpanReferences(span.traceId(), span.parentSpanId())))
                .then(projectService.getOrCreate(projectName))
                .flatMap(project -> lockService.executeWithLock(
                        new LockService.Lock(id, SPAN_KEY),
                        Mono.defer(() -> insertSpan(span, project, id))));
    }

    private Mono<UUID> insertSpan(Span span, Project project, UUID id) {
        return spanDAO.getPartialById(id)
                .flatMap(partialExistingSpan -> insertSpan(span, project, id, partialExistingSpan))
                .switchIfEmpty(Mono.defer(() -> create(span, project, id)))
                .onErrorResume(this::handleSpanDBError);
    }

    private Mono<UUID> insertSpan(Span span, Project project, UUID id, Span partialExistingSpan) {
        return Mono.defer(() -> {
            // 检查是否存在由补丁请求创建的部分跨度，如果是则继续插入
            if (Instant.EPOCH.equals(partialExistingSpan.startTime())) {
                return create(span, project, id);
            }
            // 否则，非部分跨度已存在，忽略插入操作直接返回ID
            return Mono.just(id);
        });
    }

    private Mono<UUID> create(Span span, Project project, UUID id) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME, "");
            String userName = ctx.get(RequestContext.USER_NAME);
            String projectName = project.name();

            // 从跨度中剥离附件，使用生成的ID和项目ID
            Span spanWithId = span.toBuilder().id(id).projectId(project.id()).build();
            return attachmentStripperService.stripAttachments(spanWithId, workspaceId, userName, projectName)
                    .flatMap(processedSpan -> {
                        log.info("插入 span，ID '{}'，项目ID '{}'，traceID '{}'，父spanID '{}'",
                                processedSpan.id(), processedSpan.projectId(), processedSpan.traceId(),
                                processedSpan.parentSpanId());
                        var savedSpan = processedSpan.toBuilder()
                                .projectId(project.id())
                                .projectName(projectName)
                                .build();
                        return spanDAO.insert(processedSpan)
                                .doOnSuccess(__ -> eventBus.post(
                                        new SpansCreated(List.of(savedSpan), workspaceId, userName, workspaceName)))
                                .thenReturn(processedSpan.id());
                    });
        });
    }

    @WithSpan
    public Mono<Void> update(@NonNull UUID id, @NonNull SpanUpdate spanUpdate) {
        log.info("更新 span，ID '{}'", id);

        String projectName = WorkspaceUtils.getProjectName(spanUpdate.projectName());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return idGenerator
                    .validateIdNotInFutureAsync(id, SPAN_KEY)
                    .then(Mono.fromRunnable(
                            () -> validateSpanReferences(spanUpdate.traceId(), spanUpdate.parentSpanId())))
                    .then(Mono.defer(() -> getProjectById(spanUpdate)
                            .switchIfEmpty(Mono.defer(() -> projectService.getOrCreate(projectName)))
                            .subscribeOn(Schedulers.boundedElastic()))
                            //TODO: 重构以实现正确的冲突解决
                            .flatMap(project -> lockService.executeWithLock(
                                    new LockService.Lock(id, SPAN_KEY),
                                    Mono.defer(() -> spanDAO.getOnlySpanDataById(id, project.id())
                                            .flatMap(span -> updateOrFail(spanUpdate, id, span, project))
                                            .switchIfEmpty(
                                                    Mono.defer(() -> insertUpdate(project, spanUpdate, id)))
                                            .onErrorResume(this::handleSpanDBError)
                                            .then()))))
                    .doOnSuccess(__ -> eventBus.post(
                            new SpansUpdated(Set.of(spanUpdate.traceId()), workspaceId, userName)));
        });
    }

    @WithSpan
    public Mono<Void> batchUpdate(@NonNull SpanBatchUpdate batchUpdate) {
        log.info("批量更新 '{}' 个 span", batchUpdate.ids().size());

        boolean mergeTags = Boolean.TRUE.equals(batchUpdate.mergeTags());
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Mono
                    .fromRunnable(() -> validateSpanReferences(batchUpdate.update().traceId(),
                            batchUpdate.update().parentSpanId()))
                    .then(spanDAO.bulkUpdate(batchUpdate.ids(), batchUpdate.update(), mergeTags))
                    .onErrorResume(TagOperations::mapTagLimitError)
                    .doOnSuccess(__ -> {
                        log.info("完成 '{}' 个 span 的批量更新", batchUpdate.ids().size());
                        eventBus.post(new SpansUpdated(Set.of(batchUpdate.update().traceId()), workspaceId, userName));
                    });
        });
    }

    private Mono<Long> insertUpdate(Project project, SpanUpdate spanUpdate, UUID id) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            String projectName = project.name();

            // 在数据库事务外剥离附件
            return attachmentStripperService.stripAttachments(
                    spanUpdate, id, workspaceId, userName, projectName)
                    .flatMap(processedUpdate -> spanDAO.partialInsert(id, project.id(), processedUpdate));
        });
    }

    private Mono<Project> getProjectById(SpanUpdate spanUpdate) {
        return makeMonoContextAware((userName, workspaceId) -> {

            if (spanUpdate.projectId() != null) {
                return Mono.fromCallable(() -> projectService.get(spanUpdate.projectId(), workspaceId));
            }

            return Mono.empty();
        });
    }

    private <T> Mono<T> handleSpanDBError(Throwable ex) {
        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && ex.getMessage().contains("String too long for type FixedString")
                && (ex.getMessage().contains("project_id") || ex.getMessage().contains("workspace_id"))) {
            return failWithConflict(PROJECT_AND_WORKSPACE_NAME_MISMATCH);
        }
        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && (ex.getMessage().contains("CAST(leftPad(") && ex.getMessage().contains(".parent_span_id, 40_UInt8")
                        && ex.getMessage().contains("FixedString(19)"))) {

            return failWithConflict(PARENT_SPAN_IS_MISMATCH);
        }
        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && ex.getMessage().contains("_CAST(trace_id, FixedString(36))")) {

            return failWithConflict(TRACE_ID_MISMATCH);
        }
        return TagOperations.mapTagLimitError(ex);
    }

    private Mono<Long> updateOrFail(SpanUpdate spanUpdate, UUID id, Span existingSpan, Project project) {
        if (!project.id().equals(existingSpan.projectId())) {
            return failWithConflict(PROJECT_AND_WORKSPACE_NAME_MISMATCH);
        }

        if (!Objects.equals(existingSpan.parentSpanId(), spanUpdate.parentSpanId())) {
            return failWithConflict(PARENT_SPAN_IS_MISMATCH);
        }

        if (!existingSpan.traceId().equals(spanUpdate.traceId())) {
            return failWithConflict(TRACE_ID_MISMATCH);
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            String projectName = project.name();

            // 步骤1：在数据库事务外获取已存在的附件
            return attachmentService.getAttachmentInfoByEntity(id, SPAN, existingSpan.projectId())
                    .flatMap(existingAttachments ->
            // 步骤2：在数据库事务外剥离附件
            attachmentStripperService.stripAttachments(
                    spanUpdate, id, workspaceId, userName, projectName)
                    .flatMap(processedUpdate ->
            // 步骤3：在数据库事务中更新跨度
            spanDAO.update(id, processedUpdate, existingSpan)
                    .flatMap(updateResult -> {
                        // 步骤4：仅删除旧数据中的自动剥离附件
                        // 用户上传的附件将被保留，除非用户明确移除
                        List<AttachmentInfo> autoStrippedAttachments = AttachmentUtils
                                .filterAutoStrippedAttachments(existingAttachments);

                        if (!autoStrippedAttachments.isEmpty()) {
                            return attachmentService.deleteSpecificAttachments(autoStrippedAttachments,
                                    id, SPAN, existingSpan.projectId())
                                    .thenReturn(updateResult);
                        }
                        return Mono.just(updateResult);
                    })));
        });
    }

    private <T> Mono<T> failWithConflict(String error) {
        log.info(error);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(error))));
    }

    public Mono<Boolean> validateSpanWorkspace(@NonNull String workspaceId, @NonNull Set<UUID> spanIds) {
        if (spanIds.isEmpty()) {
            return Mono.just(true);
        }

        return spanDAO.getSpanWorkspace(spanIds)
                .map(spanWorkspace -> spanWorkspace.stream().allMatch(span -> workspaceId.equals(span.workspaceId())));
    }

    @WithSpan
    public Mono<Long> create(@NonNull SpanBatch batch) {

        Preconditions.checkArgument(!batch.spans().isEmpty(), "Batch spans must not be empty");

        List<Span> dedupedSpans = dedupSpans(batch.spans());

        List<String> projectNames = dedupedSpans
                .stream()
                .map(Span::projectName)
                .map(WorkspaceUtils::getProjectName)
                .distinct()
                .toList();

        log.info("为项目 '{}' 创建 span 批次", projectNames);

        // 在处理前删除批次中所有跨度的自动剥离附件
        // 这可以防止SDK多次发送相同跨度数据时产生重复的自动剥离附件
        // 同时保留用户上传的附件
        Set<UUID> spanIds = dedupedSpans.stream()
                .map(Span::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 在任何副作用（自动剥离附件删除、项目创建）之前快速失败于无效ID，
        // 这样被拒绝的批次永远不会改变状态。运行在 deferContextual 内部，以便审计
        // 指标能将批次自身的ID归属到请求的工作区。
        return Mono.deferContextual(validationCtx -> {
            String validationWorkspaceId = validationCtx.get(RequestContext.WORKSPACE_ID);
            dedupedSpans.forEach(span -> {
                if (span.id() != null) {
                    idGenerator.validateId(span.id(), SPAN_KEY, validationWorkspaceId);
                }
                validateSpanReferences(span.traceId(), span.parentSpanId());
            });
            return attachmentService.deleteAutoStrippedAttachments(SPAN, spanIds);
        })
                .then(Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME, "");
                    String userName = ctx.get(RequestContext.USER_NAME);

                    Mono<List<Span>> resolveProjects = Flux.fromIterable(projectNames)
                            .flatMap(projectService::getOrCreate)
                            .collectList()
                            .map(projects -> bindSpanToProjectAndId(dedupedSpans, projects));

                    return resolveProjects
                            .flatMap(this::stripAttachmentsFromSpanBatch)
                            .flatMap(spans -> spanDAO.batchInsert(spans)
                                    .doOnSuccess(__ -> eventBus.post(
                                            new SpansCreated(spans, workspaceId, userName, workspaceName))));
                }));
    }

    private Mono<List<Span>> stripAttachmentsFromSpanBatch(List<Span> spans) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Flux.fromIterable(spans)
                    .flatMap(span -> {
                        String projectName = WorkspaceUtils.getProjectName(span.projectName());
                        return attachmentStripperService.stripAttachments(span, workspaceId, userName,
                                projectName);
                    })
                    .collectList();
        });
    }

    private List<Span> dedupSpans(List<Span> initialSpans) {

        Map<Boolean, List<Span>> shouldBeDeduped = initialSpans.stream()
                .collect(Collectors.partitioningBy(span -> span.id() != null && span.lastUpdatedAt() != null));

        List<Span> result = new ArrayList<>(shouldBeDeduped.get(false));

        Collection<Span> dedupedSpans = shouldBeDeduped.get(true)
                .stream()
                .collect(Collectors.toMap(
                        Span::id,
                        Function.identity(),
                        (span1, span2) -> span1.lastUpdatedAt().isAfter(span2.lastUpdatedAt()) ? span1 : span2))
                .values();

        result.addAll(dedupedSpans);

        return result;
    }

    // 共享的 span 引用ID策略：trace（必需）和 parent（可选）必须是时间有序的
    // UUIDv7，允许是过去的时间。被每个 span 写入路径使用，以便规则不会在它们之间漂移。
    private void validateSpanReferences(UUID traceId, UUID parentSpanId) {
        idGenerator.validateIdNotInFuture(traceId, SPAN_TRACE_KEY);
        idGenerator.validateIdNotInFutureIfPresent(parentSpanId, SPAN_PARENT_KEY);
    }

    private List<Span> bindSpanToProjectAndId(List<Span> spans, List<Project> projects) {
        Map<String, Project> projectPerName = projects.stream()
                .collect(Collectors.toMap(
                        WorkspaceUtils::stripProjectName,
                        Function.identity(),
                        BinaryOperatorUtils.last(),
                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));

        return spans
                .stream()
                .map(span -> {
                    String projectName = WorkspaceUtils.getProjectName(span.projectName());
                    Project project = projectPerName.get(projectName);

                    if (project == null) {
                        log.warn("未找到 span 项目 '{}' 和默认 '{}' 对应的项目", span.projectName(),
                                projectName);
                        throw new IllegalStateException("Project not found: %s".formatted(span.projectName()));
                    }

                    // ID 已在 create(SpanBatch) 中提前验证；生成的 ID 本身就是有效的。
                    UUID id = span.id() == null ? idGenerator.generateId() : span.id();

                    return span.toBuilder().id(id).projectId(project.id()).build();
                })
                .toList();
    }

    public Mono<ProjectStats> getStats(@NonNull SpanSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(spanDAO::getStats)
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    @WithSpan
    public Flux<Span> search(int limit, @NonNull SpanSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMapMany(resolvedCriteria -> spanDAO.search(limit, resolvedCriteria)
                        .concatMap(span ->
                        // 如果stripAttachments=false，重新注入附件
                        attachmentReinjectorService.reinjectAttachments(span,
                                !resolvedCriteria.stripAttachments())));
    }

    @WithSpan
    public Mono<Void> deleteByTraceIds(@NonNull Set<UUID> traceIds, UUID projectId) {
        if (traceIds.isEmpty()) {
            return Mono.empty();
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return spanDAO.getSpanIdsForTraces(traceIds, projectId)
                    .flatMap(spanIds -> {
                        if (spanIds.isEmpty()) {
                            return Mono.empty();
                        }
                        return commentService.deleteByEntityIds(CommentDAO.EntityType.SPAN, spanIds, projectId)
                                .then(Mono.defer(() -> feedbackScoreService.deleteBySpanIds(spanIds, projectId)))
                                .then(Mono.defer(() -> attachmentService.deleteByEntityIds(SPAN, spanIds, projectId)))
                                .then(spanDAO.deleteByIds(spanIds, projectId)
                                        .doOnSuccess(__ -> log.info(
                                                "Deleted '{}' spans for workspace '{}', project '{}'",
                                                spanIds.size(), workspaceId, projectId)))
                                .then(captureDeletions(spanIds, projectId, workspaceId, userName))
                                .thenReturn(spanIds);
                    })
                    .doOnSuccess(spanIds -> {
                        if (spanIds != null) {
                            eventBus.post(new SpansDeleted(spanIds, traceIds, workspaceId, userName, projectId));
                        }
                    })
                    .then();
        });
    }

    /**
     * 将被 trace 删除级联移除的 span ID 记录到 {@code deletion_events_local} 桥接表中，以便它们
     * 在 Slice 3 迁移窗口期间的 {@code spans} 表复制中得以保留。尽力而为且延迟执行：受
     * {@code spanDeletionEventsCaptureEnabled} 控制，仅在删除成功后运行，并且任何捕获失败都会被
     * 记录并吞掉，因此永远不会干扰删除。span 没有独立的删除入口，因此此级联是唯一的捕获路径。
     * 与 {@code TraceService.captureDeletions} 对称。
     */
    private Mono<Void> captureDeletions(Set<UUID> ids, UUID projectId, String workspaceId, String userName) {
        return Mono.defer(() -> {
            if (!config.getDatabaseAnalyticsDataModel().spanDeletionEventsCaptureEnabled()) {
                return Mono.empty();
            }
            var events = ids.stream()
                    .map(id -> DeletionEvent.builder()
                            .sourceTable(SourceTable.SPANS)
                            .workspaceId(workspaceId)
                            .projectId(projectId)
                            .deletedId(id.toString())
                            .deletionReason(DeletionReason.CASCADE)
                            .build())
                    .collect(Collectors.toUnmodifiableSet());
            return deletionEventDAO.insert(events, userName)
                    .doOnSuccess(_ -> log.info(
                            "已捕获 span 删除事件，数量 '{}'，项目ID '{}'，工作区 '{}'",
                            ids.size(), projectId, workspaceId))
                    .onErrorResume(throwable -> {
                        log.warn(
                                "捕获 span 删除事件失败，数量 '{}'，项目ID '{}'，工作区 '{}'",
                                ids.size(), projectId, workspaceId, throwable);
                        return Mono.empty();
                    });
        });
    }

    @WithSpan
    public Mono<SpansCountResponse> countSpansPerWorkspace() {
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(spanDAO::countSpansPerWorkspace)
                .collectList()
                .flatMap(items -> Mono.just(
                        SpansCountResponse.builder()
                                .workspacesSpansCount(items)
                                .build()))
                .switchIfEmpty(Mono.just(SpansCountResponse.empty()));
    }

    @WithSpan
    public Mono<BiInformationResponse> getSpanBIInformation() {
        log.info("获取 span BI 事件每日数据");
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(spanDAO::getSpanBIInformation)
                .collectList()
                .map(items -> BiInformationResponse.builder()
                        .biInformation(items)
                        .build())
                .switchIfEmpty(Mono.just(BiInformationResponse.empty()));
    }

    @WithSpan
    public Mono<UsageByWorkspaceProjectUserResponse> getSpanBreakdownPerWorkspace() {
        log.info("按工作区、项目和用户获取 span 用量明细");
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(spanDAO::countSpansBreakdownPerWorkspace)
                .collectList()
                .map(rows -> UsageByWorkspaceProjectUserResponse.builder().breakdown(rows).build());
    }
}
