package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.DeleteTraceThreads;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceBatchUpdate;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.api.TraceDetails;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.attachment.AttachmentReinjectorService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentStripperService;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.BinaryOperatorUtils;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.http.HttpStatus;
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

import static com.comet.opik.api.Trace.TracePage;
import static com.comet.opik.infrastructure.FilterUtils.ANALYTICS_DELETE_BATCH_SIZE;
import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(TraceServiceImpl.class)
public interface TraceService {

    /** 项目名称和工作区名称与现有追踪记录不匹配 */
    String PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH = "Project name and workspace name do not match the existing trace";

    /**
     * 创建单个追踪记录
     * @param trace 追踪对象
     * @return 追踪记录的UUID
     */
    Mono<UUID> create(Trace trace);

    /**
     * 批量创建追踪记录
     * @param batch 追踪批次对象
     * @return 创建的记录数量
     */
    Mono<Long> create(TraceBatch batch);

    /**
     * 更新追踪记录
     * @param trace 追踪更新对象
     * @param id 追踪记录的UUID
     * @return 空返回值
     */
    Mono<Void> update(TraceUpdate trace, UUID id);

    /**
     * 批量更新追踪记录
     * @param batchUpdate 批量更新对象
     * @return 空返回值
     */
    Mono<Void> batchUpdate(TraceBatchUpdate batchUpdate);

    /**
     * 根据ID获取追踪记录
     * @param id 追踪记录的UUID
     * @return 追踪对象
     */
    Mono<Trace> get(UUID id);

    /**
     * 根据ID获取追踪记录，可选择是否剥离附件
     * @param id 追踪记录的UUID
     * @param stripAttachments 是否剥离附件
     * @return 追踪对象
     */
    Mono<Trace> get(UUID id, boolean stripAttachments);

    /**
     * 根据ID列表批量获取追踪记录
     * @param ids 追踪记录的UUID列表
     * @return 追踪对象流
     */
    Flux<Trace> getByIds(List<UUID> ids);

    /**
     * 根据ID获取追踪详情
     * @param id 追踪记录的UUID
     * @return 追踪详情对象
     */
    Mono<TraceDetails> getTraceDetailsById(UUID id);

    /**
     * 删除指定的追踪记录
     * @param ids 追踪记录的UUID集合
     * @param projectId 项目ID
     * @return 空返回值
     */
    Mono<Void> delete(Set<UUID> ids, UUID projectId);

    /**
     * 分页查询追踪记录
     * @param page 页码
     * @param size 每页大小
     * @param criteria 搜索条件
     * @return 追踪分页结果
     */
    Mono<TracePage> find(int page, int size, TraceSearchCriteria criteria);

    /**
     * 验证追踪记录是否属于指定工作区
     * @param workspaceId 工作区ID
     * @param traceIds 追踪记录的UUID集合
     * @return 验证结果
     */
    Mono<Boolean> validateTraceWorkspace(String workspaceId, Set<UUID> traceIds);

    /**
     * 统计每个工作区的追踪记录数量
     * @return 追踪计数响应
     */
    Mono<TraceCountResponse> countTracesPerWorkspace();

    /**
     * 获取追踪记录的BI信息
     * @return BI信息响应
     */
    Mono<BiInformationResponse> getTraceBIInformation();

    /**
     * 获取项目统计信息
     * @param searchCriteria 搜索条件
     * @return 项目统计对象
     */
    Mono<ProjectStats> getStats(TraceSearchCriteria searchCriteria);

    /**
     * 获取每日创建的追踪记录数量
     * @return 每日创建数量
     */
    Mono<Long> getDailyCreatedCount();

    /**
     * 获取项目中最后更新的追踪记录时间
     * @param projectIds 项目ID集合
     * @param workspaceId 工作区ID
     * @param lastUpdatedAfter 最后更新时间之后的时间点
     * @return 项目ID与最后更新时间的映射
     */
    Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(Set<UUID> projectIds, String workspaceId, Instant lastUpdatedAfter);

    /**
     * 获取指定时间范围内有追踪记录的项目
     * @param workspaceProjectPairs 工作区-项目对集合
     * @param from 开始时间
     * @param to 结束时间
     * @return 项目ID集合
     */
    Mono<Set<UUID>> getProjectsWithTracesInRange(@NonNull Collection<Pair<String, UUID>> workspaceProjectPairs,
            @NonNull Instant from, @NonNull Instant to);

    /**
     * 删除追踪线程
     * @param traceThreads 追踪线程删除对象
     * @return 空返回值
     */
    Mono<Void> deleteTraceThreads(DeleteTraceThreads traceThreads);

    /**
     * 搜索追踪记录
     * @param limit 结果数量限制
     * @param searchCriteria 搜索条件
     * @return 追踪对象流
     */
    Flux<Trace> search(int limit, TraceSearchCriteria searchCriteria);

    /**
     * 统计项目的追踪记录数量
     * @param projectIds 项目ID集合
     * @return 追踪记录数量
     */
    Mono<Long> countTraces(Set<UUID> projectIds);

    /**
     * 根据ID获取线程的最小信息
     * @param projectId 项目ID
     * @param threadId 线程ID集合
     * @return 线程信息列表
     */
    Mono<List<TraceThread>> getMinimalThreadInfoByIds(UUID projectId, Set<String> threadId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceServiceImpl implements TraceService {

    /** 追踪记录键名 */
    public static final String TRACE_KEY = "Trace";

    private final @NonNull TraceDAO dao;
    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull LockService lockService;
    private final @NonNull EventBus eventBus;
    private final @NonNull TraceSortingFactory traceSortingFactory;
    private final @NonNull AttachmentStripperService attachmentStripperService;
    private final @NonNull AttachmentService attachmentService;
    private final @NonNull AttachmentReinjectorService attachmentReinjectorService;

    /**
     * 创建单个追踪记录
     * @param trace 追踪对象
     * @return 追踪记录的UUID
     */
    @Override
    @WithSpan
    public Mono<UUID> create(@NonNull Trace trace) {

        String projectName = WorkspaceUtils.getProjectName(trace.projectName());
        UUID id = trace.id() == null ? idGenerator.generateId() : trace.id();

        return Mono.deferContextual(ctx -> idGenerator
                .validateIdAsync(id, TRACE_KEY)
                .then(Mono.defer(() -> projectService.getOrCreate(projectName)))
                .flatMap(project -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME, "");
                    String userName = ctx.get(RequestContext.USER_NAME);

                    // 从追踪记录中剥离附件，使用生成的ID和项目ID
                    Trace traceWithId = trace.toBuilder().id(id).projectId(project.id()).build();
                    return attachmentStripperService.stripAttachments(traceWithId, workspaceId,
                            userName, projectName)
                            .flatMap(processedTrace -> lockService.executeWithLock(
                                    new LockService.Lock(id, TRACE_KEY),
                                    Mono.defer(() -> insertTrace(processedTrace, project, id)))
                                    .doOnSuccess(__ -> {
                                        var savedTrace = processedTrace.toBuilder().projectId(project.id())
                                                .projectName(projectName).build();
                                        eventBus.post(new TracesCreated(List.of(savedTrace), workspaceId, userName,
                                                workspaceName));
                                    }));
                }));
    }

    /**
     * 批量创建追踪记录
     * @param batch 追踪批次对象
     * @return 创建的记录数量
     */
    @WithSpan
    public Mono<Long> create(TraceBatch batch) {

        Preconditions.checkArgument(!batch.traces().isEmpty(), "Batch traces cannot be empty");

        List<Trace> dedupedTraces = dedupTraces(batch.traces());

        List<String> projectNames = dedupedTraces
                .stream()
                .map(Trace::projectName)
                .map(WorkspaceUtils::getProjectName)
                .distinct()
                .toList();

        // 在处理前删除批次中所有追踪记录的自动剥离附件
        // 这可以防止SDK多次发送相同追踪数据时产生重复的自动剥离附件
        // 同时保留用户上传的附件
        Set<UUID> traceIds = dedupedTraces.stream()
                .map(Trace::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return attachmentService.deleteAutoStrippedAttachments(EntityType.TRACE, traceIds)
                .then(Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME, "");
                    String userName = ctx.get(RequestContext.USER_NAME);

                    Mono<List<Trace>> resolveProjects = Flux.fromIterable(projectNames)
                            .flatMap(projectService::getOrCreate)
                            .collectList()
                            .map(projects -> bindTraceToProjectAndId(dedupedTraces, projects))
                            .flatMapMany(Flux::fromIterable)
                            .flatMap(trace -> attachmentStripperService.stripAttachments(trace, workspaceId,
                                    userName,
                                    trace.projectName()))
                            .collectList();

                    return resolveProjects
                            .flatMap(traces -> template
                                    .nonTransaction(connection -> dao.batchInsert(traces, connection))
                                    .doOnSuccess(__ -> {
                                        eventBus.post(new TracesCreated(traces, workspaceId, userName,
                                                workspaceName));
                                    }));
                }));
    }

    /**
     * 对追踪记录进行去重处理
     * 按照ID和最后更新时间进行去重，保留最新的记录
     * @param initialTraces 原始追踪记录列表
     * @return 去重后的追踪记录列表
     */
    private List<Trace> dedupTraces(List<Trace> initialTraces) {

        Map<Boolean, List<Trace>> shouldBeDeduped = initialTraces.stream()
                .collect(Collectors.partitioningBy(trace -> trace.id() != null && trace.lastUpdatedAt() != null));

        List<Trace> result = new ArrayList<>(shouldBeDeduped.get(false));

        Collection<Trace> dedupedTraces = shouldBeDeduped.get(true)
                .stream()
                .collect(Collectors.toMap(
                        Trace::id,
                        Function.identity(),
                        (trace1, trace2) -> trace1.lastUpdatedAt().isAfter(trace2.lastUpdatedAt()) ? trace1 : trace2))
                .values();

        result.addAll(dedupedTraces);

        return result;
    }

    /**
     * 将追踪记录绑定到项目和ID
     * @param traces 追踪记录列表
     * @param projects 项目列表
     * @return 绑定后的追踪记录列表
     */
    private List<Trace> bindTraceToProjectAndId(List<Trace> traces, List<Project> projects) {
        Map<String, Project> projectPerName = projects.stream()
                .collect(Collectors.toMap(
                        WorkspaceUtils::stripProjectName,
                        Function.identity(),
                        BinaryOperatorUtils.last(),
                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));

        return traces
                .stream()
                .map(trace -> {
                    String projectName = WorkspaceUtils.getProjectName(trace.projectName());
                    Project project = projectPerName.get(projectName);

                    UUID id = trace.id() == null ? idGenerator.generateId() : trace.id();
                    idGenerator.validateId(id, TRACE_KEY);

                    return trace.toBuilder().id(id).projectId(project.id()).projectName(project.name()).build();
                })
                .toList();
    }

    /**
     * 插入追踪记录
     * @param newTrace 新的追踪记录
     * @param project 项目对象
     * @param id 追踪记录ID
     * @return 追踪记录的UUID
     */
    private Mono<UUID> insertTrace(Trace newTrace, Project project, UUID id) {
        return dao.getPartialById(id)
                .flatMap(existingTrace -> insertTrace(newTrace, project, id, existingTrace))
                .switchIfEmpty(Mono.defer(() -> create(newTrace, project, id)))
                .onErrorResume(this::handleDBError);
    }

    /**
     * 处理数据库错误
     * @param ex 异常对象
     * @return 错误结果
     */
    private <T> Mono<T> handleDBError(Throwable ex) {
        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && ex.getMessage().contains("String too long for type FixedString")
                && (ex.getMessage().contains("project_id") || ex.getMessage().contains("workspace_id"))) {

            return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
        }

        return TagOperations.mapTagLimitError(ex);
    }

    /**
     * 根据追踪更新对象获取项目
     * @param traceUpdate 追踪更新对象
     * @return 项目对象
     */
    private Mono<Project> getProjectById(TraceUpdate traceUpdate) {
        return AsyncUtils.makeMonoContextAware((userName, workspaceId) -> {

            if (traceUpdate.projectId() != null) {
                return Mono.fromCallable(() -> projectService.get(traceUpdate.projectId(), workspaceId));
            }

            return Mono.empty();
        });
    }

    /**
     * 插入追踪记录（已存在追踪记录时）
     * @param newTrace 新的追踪记录
     * @param project 项目对象
     * @param id 追踪记录ID
     * @param existingTrace 已存在的追踪记录
     * @return 追踪记录的UUID
     */
    private Mono<UUID> insertTrace(Trace newTrace, Project project, UUID id, Trace existingTrace) {
        return Mono.defer(() -> {
            // 检查是否存在由补丁请求引起的部分追踪记录
            if (existingTrace.startTime().equals(Instant.EPOCH)
                    && existingTrace.projectId().equals(project.id())) {

                return create(newTrace, project, id);
            }

            if (!project.id().equals(existingTrace.projectId())) {
                return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
            }

            // 否则，拒绝追踪记录创建
            return Mono
                    .error(new EntityAlreadyExistsException(new ErrorMessage(List.of("Trace already exists"))));
        });
    }

    /**
     * 创建追踪记录（带项目和ID）
     * @param trace 追踪对象
     * @param project 项目对象
     * @param id 追踪记录ID
     * @return 追踪记录的UUID
     */
    private Mono<UUID> create(Trace trace, Project project, UUID id) {
        return template.nonTransaction(connection -> {
            var newTrace = trace.toBuilder().id(id).projectId(project.id()).build();
            return dao.insert(newTrace, connection);
        });
    }

    /**
     * 更新追踪记录
     * @param traceUpdate 追踪更新对象
     * @param id 追踪记录的UUID
     * @return 空返回值
     */
    @Override
    @WithSpan
    public Mono<Void> update(@NonNull TraceUpdate traceUpdate, @NonNull UUID id) {

        var projectName = WorkspaceUtils.getProjectName(traceUpdate.projectName());

        return Mono.deferContextual(ctx -> idGenerator
                .validateIdForUpdateAsync(id, TRACE_KEY)
                .then(getProjectById(traceUpdate)
                        .switchIfEmpty(Mono.defer(() -> projectService.getOrCreate(projectName)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(project -> lockService.executeWithLock(
                                new LockService.Lock(id, TRACE_KEY),
                                Mono.defer(() -> dao.getPartialById(id)
                                        .flatMap(trace -> updateOrFail(traceUpdate, id, trace, project).thenReturn(id))
                                        .switchIfEmpty(Mono.defer(() -> insertUpdate(project, traceUpdate, id))
                                                .thenReturn(id))
                                        .onErrorResume(this::handleDBError)
                                        .doOnSuccess(__ -> eventBus.post(new TracesUpdated(
                                                Set.of(project.id()),
                                                Set.of(id),
                                                ctx.get(RequestContext.WORKSPACE_ID),
                                                ctx.get(RequestContext.USER_NAME),
                                                traceUpdate,
                                                ctx.getOrDefault(RequestContext.WORKSPACE_NAME, "")))))))
                        .then()));
    }

    /**
     * 批量更新追踪记录
     * @param batchUpdate 批量更新对象
     * @return 空返回值
     */
    @Override
    @WithSpan
    public Mono<Void> batchUpdate(@NonNull TraceBatchUpdate batchUpdate) {
        log.info("批量更新 '{}' 条追踪记录", batchUpdate.ids().size());

        boolean mergeTags = Boolean.TRUE.equals(batchUpdate.mergeTags());
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME, "");
            return dao.getProjectIdsByTraceIds(new ArrayList<>(batchUpdate.ids()))
                    .flatMap(traceToProjectMap -> {
                        var projectIds = Set.copyOf(traceToProjectMap.values());
                        return dao.bulkUpdate(batchUpdate.ids(), batchUpdate.update(), mergeTags)
                                .onErrorResume(TagOperations::mapTagLimitError)
                                .doOnSuccess(__ -> {
                                    log.info("完成 '{}' 条追踪记录的批量更新", batchUpdate.ids().size());
                                    eventBus.post(new TracesUpdated(projectIds, batchUpdate.ids(), workspaceId,
                                            userName, batchUpdate.update(), workspaceName));
                                });
                    });
        });
    }

    /**
     * 插入更新的追踪记录
     * @param project 项目对象
     * @param traceUpdate 追踪更新对象
     * @param id 追踪记录ID
     * @return 空返回值
     */
    private Mono<Void> insertUpdate(Project project, TraceUpdate traceUpdate, UUID id) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            String projectName = project.name();

            // 在插入前从新的追踪数据中剥离附件
            return attachmentStripperService.stripAttachments(
                    traceUpdate, id, workspaceId, userName, projectName)
                    .flatMap(processedUpdate -> template.nonTransaction(
                            connection -> dao.partialInsert(project.id(), processedUpdate, id, connection)));
        });
    }

    /**
     * 更新追踪记录或失败
     * @param traceUpdate 追踪更新对象
     * @param id 追踪记录ID
     * @param trace 已存在的追踪记录
     * @param project 项目对象
     * @return 空返回值
     */
    private Mono<Void> updateOrFail(TraceUpdate traceUpdate, UUID id, Trace trace, Project project) {
        if (!project.id().equals(trace.projectId())) {
            return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            String projectName = project.name();

            // 步骤1：在数据库事务外获取现有附件
            return attachmentService.getAttachmentInfoByEntity(id, EntityType.TRACE, trace.projectId())
                    .flatMap(existingAttachments ->
            // 步骤2：在数据库事务外剥离附件
            attachmentStripperService.stripAttachments(
                    traceUpdate, id, workspaceId, userName, projectName)
                    .flatMap(processedUpdate ->
            // 步骤3：在数据库事务中更新
            template.nonTransaction(connection -> dao.update(processedUpdate, id, connection))
                    .then(Mono.defer(() -> {
                        // 步骤4：只删除旧数据中的自动剥离附件
                        // 用户上传的附件会被保留，除非用户明确删除
                        List<AttachmentInfo> autoStrippedAttachments = AttachmentUtils
                                .filterAutoStrippedAttachments(existingAttachments);

                        if (autoStrippedAttachments.isEmpty()) {
                            return Mono.empty();
                        }

                        return attachmentService.deleteSpecificAttachments(autoStrippedAttachments, id,
                                EntityType.TRACE, trace.projectId());
                    }))));
        });
    }

    /**
     * 根据项目名称获取项目
     * @param projectName 项目名称
     * @return 项目对象
     */
    private Mono<Project> getProjectByName(String projectName) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> projectService.findByNames(workspaceId, List.of(projectName)))
                    .flatMap(projects -> projects.stream().findFirst().map(Mono::just).orElseGet(Mono::empty))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * 查找项目并验证可见性
     * @param criteria 搜索条件
     * @return 更新后的搜索条件
     */
    private Mono<TraceSearchCriteria> findProjectAndVerifyVisibility(TraceSearchCriteria criteria) {
        return projectService.resolveProjectIdAndVerifyVisibility(criteria.projectId(), criteria.projectName())
                .map(projectId -> criteria.toBuilder()
                        .projectId(projectId)
                        .build());
    }

    /**
     * 抛出冲突错误
     * @param error 错误信息
     * @return 错误结果
     */
    private <T> Mono<T> failWithConflict(String error) {
        log.info(error);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(error))));
    }

    /**
     * 根据ID获取追踪记录
     * @param id 追踪记录的UUID
     * @return 追踪对象
     */
    @Override
    @WithSpan
    public Mono<Trace> get(@NonNull UUID id) {
        return get(id, false);
    }

    /**
     * 根据ID获取追踪记录，可选择是否剥离附件
     * @param id 追踪记录的UUID
     * @param stripAttachments 是否剥离附件
     * @return 追踪对象
     */
    @WithSpan
    public Mono<Trace> get(@NonNull UUID id, boolean stripAttachments) {
        return template.nonTransaction(connection -> dao.findById(id, connection))
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Trace", id))))
                .flatMap(trace -> attachmentReinjectorService.reinjectAttachments(trace, !stripAttachments));
    }

    @Override
    @WithSpan
    public Flux<Trace> getByIds(@NonNull List<UUID> ids) {
        Preconditions.checkArgument(!ids.isEmpty(), "ids must not be empty");
        log.info("根据ID获取 '{}' 条追踪记录", ids.size());

        return template.stream(connection -> dao.findByIds(ids, connection));
    }

    /**
     * 根据ID获取追踪详情
     * @param id 追踪记录的UUID
     * @return 追踪详情对象
     */
    @Override
    public Mono<TraceDetails> getTraceDetailsById(UUID id) {
        return template.nonTransaction(connection -> dao.getTraceDetailsById(id, connection))
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Trace", id.toString()))));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull Set<UUID> ids, UUID projectId) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("删除追踪记录，数量 '{}'", ids.size());
        return template.nonTransaction(connection -> delete(ids, projectId, connection));
    }

    /**
     * 删除追踪记录（带连接）
     * @param ids 追踪记录的UUID集合
     * @param projectId 项目ID
     * @param connection 数据库连接
     * @return 空返回值
     */
    private Mono<Void> delete(Set<UUID> ids, UUID projectId, Connection connection) {
        return Mono.deferContextual(
                ctx -> Flux.fromIterable(Lists.partition(new ArrayList<>(ids), ANALYTICS_DELETE_BATCH_SIZE))
                        .flatMap(batch -> dao.delete(Set.copyOf(batch), projectId, connection)
                                .doOnSuccess(__ -> {
                                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                                    String userName = ctx.get(RequestContext.USER_NAME);
                                    eventBus.post(TracesDeleted.builder()
                                            .traceIds(Set.copyOf(batch))
                                            .projectId(projectId)
                                            .workspaceId(workspaceId)
                                            .userName(userName)
                                            .build());
                                    log.info(
                                            "发布TracesDeleted事件，追踪ID数量 '{}'，项目ID '{}'，工作区 '{}'",
                                            batch.size(), projectId, workspaceId);
                                }))
                        .then());
    }

    /**
     * 分页查询追踪记录
     * @param page 页码
     * @param size 每页大小
     * @param criteria 搜索条件
     * @return 追踪分页结果
     */
    @Override
    @WithSpan
    public Mono<TracePage> find(int page, int size, @NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(resolvedCriteria -> template
                        .nonTransaction(connection -> dao.find(size, page, resolvedCriteria, connection))
                        .flatMap(tracePage -> {
                            // 如果stripAttachments=false，则将附件重新注入所有追踪记录
                            var reinjectAttachments = !resolvedCriteria.stripAttachments();
                            if (reinjectAttachments) {
                                return Flux.fromIterable(tracePage.content())
                                        .concatMap(trace -> attachmentReinjectorService
                                                .reinjectAttachments(trace, reinjectAttachments))
                                        .collectList()
                                        .map(reinjectedTraces -> tracePage.toBuilder()
                                                .content(reinjectedTraces)
                                                .build());
                            }
                            return Mono.just(tracePage);
                        }))
                .switchIfEmpty(Mono.just(TracePage.empty(page, traceSortingFactory.getSortableFields())));
    }

    /**
     * 验证追踪记录是否属于指定工作区
     * @param workspaceId 工作区ID
     * @param traceIds 追踪记录的UUID集合
     * @return 验证结果
     */
    @Override
    @WithSpan
    public Mono<Boolean> validateTraceWorkspace(@NonNull String workspaceId, @NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Mono.just(true);
        }

        return template.nonTransaction(connection -> dao.getTraceWorkspace(traceIds, connection)
                .map(traceWorkspace -> traceWorkspace.stream()
                        .allMatch(trace -> workspaceId.equals(trace.workspaceId()))));
    }

    /**
     * 统计每个工作区的追踪记录数量
     * @return 追踪计数响应
     */
    @Override
    @WithSpan
    public Mono<TraceCountResponse> countTracesPerWorkspace() {

        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(dao::countTracesPerWorkspace)
                .collectList()
                .map(items -> TraceCountResponse.builder()
                        .workspacesTracesCount(items)
                        .build())
                .switchIfEmpty(Mono.just(TraceCountResponse.empty()));
    }

    /**
     * 获取追踪记录的BI信息
     * @return BI信息响应
     */
    @Override
    @WithSpan
    public Mono<BiInformationResponse> getTraceBIInformation() {
        log.info("获取追踪BI事件每日数据");

        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(dao::getTraceBIInformation)
                .collectList()
                .map(items -> BiInformationResponse.builder()
                        .biInformation(items)
                        .build())
                .switchIfEmpty(Mono.just(BiInformationResponse.empty()));
    }

    /**
     * 获取项目统计信息
     * @param criteria 搜索条件
     * @return 项目统计对象
     */
    @Override
    @WithSpan
    public Mono<ProjectStats> getStats(@NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(dao::getStats)
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    /**
     * 获取每日创建的追踪记录数量
     * @return 每日创建数量
     */
    @Override
    @WithSpan
    public Mono<Long> getDailyCreatedCount() {
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of())).flatMap(dao::getDailyTraces);
    }

    /**
     * 获取项目中最后更新的追踪记录时间
     * @param projectIds 项目ID集合
     * @param workspaceId 工作区ID
     * @param lastUpdatedAfter 最后更新时间之后的时间点
     * @return 项目ID与最后更新时间的映射
     */
    @Override
    public Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(Set<UUID> projectIds, String workspaceId,
            Instant lastUpdatedAfter) {
        return template
                .nonTransaction(
                        connection -> dao.getLastUpdatedTraceAt(projectIds, workspaceId, lastUpdatedAfter, connection));
    }

    /**
     * 获取指定时间范围内有追踪记录的项目
     * @param workspaceProjectPairs 工作区-项目对集合
     * @param from 开始时间
     * @param to 结束时间
     * @return 项目ID集合
     */
    @Override
    public Mono<Set<UUID>> getProjectsWithTracesInRange(@NonNull Collection<Pair<String, UUID>> workspaceProjectPairs,
            @NonNull Instant from, @NonNull Instant to) {
        if (workspaceProjectPairs.isEmpty()) {
            return Mono.just(Set.of());
        }
        return template.nonTransaction(
                connection -> dao.getProjectsWithTracesInRange(workspaceProjectPairs, from, to, connection));
    }

    /**
     * 删除追踪线程
     * @param traceThreads 追踪线程删除对象
     * @return 空返回值
     */
    @Override
    public Mono<Void> deleteTraceThreads(@NonNull DeleteTraceThreads traceThreads) {
        if (traceThreads.projectId() == null && traceThreads.projectName() == null) {
            return Mono.error(new ClientErrorException("must provide either a project_name or a project_id",
                    HttpStatus.SC_UNPROCESSABLE_ENTITY));
        }

        if (traceThreads.projectId() != null) {
            return deleteTraceThreadsByProjectId(traceThreads.projectId(), traceThreads.threadIds());
        }

        return getProjectByName(traceThreads.projectName())
                .flatMap(project -> deleteTraceThreadsByProjectId(project.id(), traceThreads.threadIds()));
    }

    /**
     * 根据项目ID删除追踪线程
     * @param projectId 项目ID
     * @param threadIds 线程ID列表
     * @return 空返回值
     */
    private Mono<Void> deleteTraceThreadsByProjectId(@NonNull UUID projectId, @NonNull List<String> threadIds) {
        log.info("根据项目ID '{}' 和线程ID数量 '{}' 删除追踪线程", projectId, threadIds.size());

        return Mono.deferContextual(ctx -> template.nonTransaction(connection ->
        // 首先获取线程ID对应的所有追踪ID
        dao.getTraceIdsByThreadIds(projectId, threadIds, connection)
                .flatMap(traceIds -> {
                    if (traceIds.isEmpty()) {
                        log.info("未找到线程ID对应的追踪记录，跳过删除");
                        return Mono.empty();
                    }
                    log.info("找到 '{}' 条线程ID对应的追踪记录，继续删除", traceIds.size());

                    return delete(traceIds, projectId, connection);
                })));
    }

    /**
     * 搜索追踪记录
     * @param limit 结果数量限制
     * @param criteria 搜索条件
     * @return 追踪对象流
     */
    @Override
    public Flux<Trace> search(int limit, @NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMapMany(it -> dao.search(limit, it)
                        .concatMap(trace -> attachmentReinjectorService.reinjectAttachments(trace,
                                !it.stripAttachments())));
    }

    /**
     * 统计项目的追踪记录数量
     * @param projectIds 项目ID集合
     * @return 追踪记录数量
     */
    @Override
    public Mono<Long> countTraces(@NonNull Set<UUID> projectIds) {
        return dao.countTraces(projectIds);
    }

    /**
     * 根据ID获取线程的最小信息
     * @param projectId 项目ID
     * @param threadId 线程ID集合
     * @return 线程信息列表
     */
    @Override
    public Mono<List<TraceThread>> getMinimalThreadInfoByIds(@NonNull UUID projectId, @NonNull Set<String> threadId) {
        return dao.getMinimalThreadInfoByIds(projectId, threadId)
                .switchIfEmpty(Mono.just(List.of()));
    }

}
