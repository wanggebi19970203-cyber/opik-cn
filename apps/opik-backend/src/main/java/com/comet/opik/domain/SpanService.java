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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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

/**
 * Span服务类，处理跨度(Span)的创建、更新、查询和删除等业务逻辑。
 * <p>
 * 负责管理跨度的生命周期，包括附件处理、项目关联、事件发布等功能。
 * </p>
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
public class SpanService {

    /** 父跨度ID不匹配的错误信息 */
    public static final String PARENT_SPAN_IS_MISMATCH = "parent_span_id does not match the existing span";
    /** 跟踪ID不匹配的错误信息 */
    public static final String TRACE_ID_MISMATCH = "trace_id does not match the existing span";
    /** 跨度实体的键名 */
    public static final String SPAN_KEY = "Span";
    /** 项目名称和工作区名称不匹配的错误信息 */
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

    /**
     * 分页查询跨度列表。
     *
     * @param page           页码
     * @param size           每页大小
     * @param searchCriteria 搜索条件
     * @return 跨度分页结果
     */
    @WithSpan
    public Mono<Span.SpanPage> find(int page, int size, @NonNull SpanSearchCriteria searchCriteria) {
        log.info("Finding span by '{}'", searchCriteria);

        return findProjectAndVerifyVisibility(searchCriteria)
                .flatMap(resolvedCriteria -> spanDAO.find(page, size, resolvedCriteria)
                        .flatMap(spanPage -> {
                            // If stripAttachments=false, reinject attachments into all spans
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

    /**
     * 根据ID获取跨度（保留附件）。
     *
     * @param id 跨度ID
     * @return 跨度对象
     */
    @WithSpan
    public Mono<Span> getById(@NonNull UUID id) {
        return getById(id, false);
    }

    /**
     * 根据ID获取跨度。
     *
     * @param id              跨度ID
     * @param stripAttachments 是否剥离附件
     * @return 跨度对象
     */
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

    /**
     * 根据跟踪ID集合批量获取跨度。
     *
     * @param traceIds 跟踪ID集合
     * @return 跨度列表
     */
    @WithSpan
    public Flux<Span> getByTraceIds(@NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Flux.empty();
        }

        log.info("Getting spans for '{}' traces", traceIds.size());

        return spanDAO.getByTraceIds(traceIds)
                .flatMap(span -> attachmentReinjectorService.reinjectAttachments(span, true));
    }

    /**
     * 根据ID集合批量获取跨度。
     *
     * @param ids 跨度ID集合
     * @return 跨度列表
     */
    @WithSpan
    public Flux<Span> getByIds(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Flux.empty();
        }

        log.info("Getting '{}' spans by IDs", ids.size());

        return spanDAO.getByIds(ids)
                .flatMap(span -> attachmentReinjectorService.reinjectAttachments(span, true));
    }

    /**
     * 创建单个跨度。
     *
     * @param span 跨度对象
     * @return 跨度ID
     */
    @WithSpan
    public Mono<UUID> create(@NonNull Span span) {
        var id = span.id() == null ? idGenerator.generateId() : span.id();
        var projectName = WorkspaceUtils.getProjectName(span.projectName());
        return idGenerator
                .validateIdAsync(id, SPAN_KEY)
                .then(projectService.getOrCreate(projectName))
                .flatMap(project -> lockService.executeWithLock(
                        new LockService.Lock(id, SPAN_KEY),
                        Mono.defer(() -> insertSpan(span, project, id))));
    }

    /**
     * 插入跨度（内部方法）。
     *
     * @param span    跨度对象
     * @param project 项目对象
     * @param id      跨度ID
     * @return 跨度ID
     */
    private Mono<UUID> insertSpan(Span span, Project project, UUID id) {
        return spanDAO.getPartialById(id)
                .flatMap(partialExistingSpan -> insertSpan(span, project, id, partialExistingSpan))
                .switchIfEmpty(Mono.defer(() -> create(span, project, id)))
                .onErrorResume(this::handleSpanDBError);
    }

    /**
     * 插入跨度（处理部分跨度存在的情况）。
     *
     * @param span               跨度对象
     * @param project            项目对象
     * @param id                 跨度ID
     * @param partialExistingSpan 已存在的部分跨度
     * @return 跨度ID
     */
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

    /**
     * 创建跨度（内部方法）。
     *
     * @param span    跨度对象
     * @param project 项目对象
     * @param id      跨度ID
     * @return 跨度ID
     */
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
                        log.info("Inserting span with id '{}' , projectId '{}' , traceId '{}' , parentSpanId '{}'",
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

    /**
     * 更新跨度。
     *
     * @param id         跨度ID
     * @param spanUpdate 跨度更新数据
     * @return 空的Mono对象
     */
    @WithSpan
    public Mono<Void> update(@NonNull UUID id, @NonNull SpanUpdate spanUpdate) {
        log.info("Updating span with id '{}'", id);

        String projectName = WorkspaceUtils.getProjectName(spanUpdate.projectName());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return idGenerator
                    .validateIdForUpdateAsync(id, SPAN_KEY)
                    .then(Mono.defer(() -> getProjectById(spanUpdate)
                            .switchIfEmpty(Mono.defer(() -> projectService.getOrCreate(projectName)))
                            .subscribeOn(Schedulers.boundedElastic()))
                            //TODO: refactor to implement proper conflict resolution
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

    /**
     * 批量更新跨度。
     *
     * @param batchUpdate 批量更新数据
     * @return 空的Mono对象
     */
    @WithSpan
    public Mono<Void> batchUpdate(@NonNull SpanBatchUpdate batchUpdate) {
        log.info("Batch updating '{}' spans", batchUpdate.ids().size());

        boolean mergeTags = Boolean.TRUE.equals(batchUpdate.mergeTags());
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return spanDAO.bulkUpdate(batchUpdate.ids(), batchUpdate.update(), mergeTags)
                    .onErrorResume(TagOperations::mapTagLimitError)
                    .doOnSuccess(__ -> {
                        log.info("Completed batch update for '{}' spans", batchUpdate.ids().size());
                        eventBus.post(new SpansUpdated(Set.of(batchUpdate.update().traceId()), workspaceId, userName));
                    });
        });
    }

    /**
     * 插入更新数据（处理部分更新）。
     *
     * @param project    项目对象
     * @param spanUpdate 跨度更新数据
     * @param id         跨度ID
     * @return 更新的记录数
     */
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

    /**
     * 根据跨度更新数据获取项目。
     *
     * @param spanUpdate 跨度更新数据
     * @return 项目对象
     */
    private Mono<Project> getProjectById(SpanUpdate spanUpdate) {
        return makeMonoContextAware((userName, workspaceId) -> {

            if (spanUpdate.projectId() != null) {
                return Mono.fromCallable(() -> projectService.get(spanUpdate.projectId(), workspaceId));
            }

            return Mono.empty();
        });
    }

    /**
     * 处理跨度数据库错误。
     *
     * @param ex 异常对象
     * @return 错误结果
     */
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

    /**
     * 更新跨度或失败。
     *
     * @param spanUpdate  跨度更新数据
     * @param id          跨度ID
     * @param existingSpan 已存在的跨度
     * @param project     项目对象
     * @return 更新的记录数
     */
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

    /**
     * 返回冲突错误。
     *
     * @param error 错误信息
     * @return 错误结果
     */
    private <T> Mono<T> failWithConflict(String error) {
        log.info(error);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(error))));
    }

    /**
     * 验证跨度的工作区归属。
     *
     * @param workspaceId 工作区ID
     * @param spanIds     跨度ID集合
     * @return 验证结果
     */
    public Mono<Boolean> validateSpanWorkspace(@NonNull String workspaceId, @NonNull Set<UUID> spanIds) {
        if (spanIds.isEmpty()) {
            return Mono.just(true);
        }

        return spanDAO.getSpanWorkspace(spanIds)
                .map(spanWorkspace -> spanWorkspace.stream().allMatch(span -> workspaceId.equals(span.workspaceId())));
    }

    /**
     * 批量创建跨度。
     *
     * @param batch 批量跨度数据
     * @return 创建的跨度数量
     */
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

        log.info("Creating batch of spans for projects '{}'", projectNames);

        // 在处理前删除批次中所有跨度的自动剥离附件
        // 这可以防止SDK多次发送相同跨度数据时产生重复的自动剥离附件
        // 同时保留用户上传的附件
        Set<UUID> spanIds = dedupedSpans.stream()
                .map(Span::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return attachmentService.deleteAutoStrippedAttachments(SPAN, spanIds)
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

    /**
     * 从跨度批次中剥离附件。
     *
     * @param spans 跨度列表
     * @return 处理后的跨度列表
     */
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

    /**
     * 对跨度列表进行去重。
     *
     * @param initialSpans 初始跨度列表
     * @return 去重后的跨度列表
     */
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

    /**
     * 将跨度绑定到项目和ID。
     *
     * @param spans    跨度列表
     * @param projects 项目列表
     * @return 绑定后的跨度列表
     */
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
                        log.warn("Project not found for span project '{}' and default '{}'", span.projectName(),
                                projectName);
                        throw new IllegalStateException("Project not found: %s".formatted(span.projectName()));
                    }

                    UUID id = span.id() == null ? idGenerator.generateId() : span.id();
                    idGenerator.validateId(id, SPAN_KEY);

                    return span.toBuilder().id(id).projectId(project.id()).build();
                })
                .toList();
    }

    /**
     * 获取跨度统计信息。
     *
     * @param criteria 搜索条件
     * @return 项目统计信息
     */
    public Mono<ProjectStats> getStats(@NonNull SpanSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(spanDAO::getStats)
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    /**
     * 搜索跨度。
     *
     * @param limit    返回结果数量限制
     * @param criteria 搜索条件
     * @return 跨度列表
     */
    @WithSpan
    public Flux<Span> search(int limit, @NonNull SpanSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMapMany(resolvedCriteria -> spanDAO.search(limit, resolvedCriteria)
                        .concatMap(span ->
                        // 如果stripAttachments=false，重新注入附件
                        attachmentReinjectorService.reinjectAttachments(span,
                                !resolvedCriteria.stripAttachments())));
    }

    /**
     * 根据跟踪ID删除跨度。
     *
     * @param traceIds  跟踪ID集合
     * @param projectId 项目ID
     * @return 空的Mono对象
     */
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
                        return commentService.deleteByEntityIds(CommentDAO.EntityType.SPAN, spanIds)
                                .then(Mono.defer(() -> feedbackScoreService.deleteBySpanIds(spanIds, projectId)))
                                .then(Mono.defer(() -> attachmentService.deleteByEntityIds(SPAN, spanIds)))
                                .then(spanDAO.deleteByIds(spanIds, projectId)
                                        .doOnSuccess(__ -> log.info(
                                                "Deleted '{}' spans for workspace '{}', project '{}'",
                                                spanIds.size(), workspaceId, projectId)))
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
     * 统计每个工作区的跨度数量。
     *
     * @return 工作区跨度数量响应
     */
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

    /**
     * 获取跨度的BI信息（每日数据）。
     *
     * @return BI信息响应
     */
    @WithSpan
    public Mono<BiInformationResponse> getSpanBIInformation() {
        log.info("Getting span BI events daily data");
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(spanDAO::getSpanBIInformation)
                .collectList()
                .map(items -> BiInformationResponse.builder()
                        .biInformation(items)
                        .build())
                .switchIfEmpty(Mono.just(BiInformationResponse.empty()));
    }

    /**
     * 获取按工作区、项目和用户分解的跨度使用情况。
     *
     * @return 工作区跨度使用分解响应
     */
    @WithSpan
    public Mono<UsageByWorkspaceProjectUserResponse> getSpanBreakdownPerWorkspace() {
        log.info("Getting span usage breakdown by workspace, project and user");
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(spanDAO::countSpansBreakdownPerWorkspace)
                .collectList()
                .map(rows -> UsageByWorkspaceProjectUserResponse.builder().breakdown(rows).build());
    }
}
