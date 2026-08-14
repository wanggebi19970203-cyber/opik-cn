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
import com.comet.opik.api.events.TraceCostIntelligenceChanged;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.attachment.AttachmentReinjectorService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentStripperService;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.BinaryOperatorUtils;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
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
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.Trace.TracePage;
import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(TraceServiceImpl.class)
public interface TraceService {

    String PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH = "Project name and workspace name do not match the existing trace";

    Mono<UUID> create(Trace trace);

    Mono<Long> create(TraceBatch batch);

    Mono<Void> update(TraceUpdate trace, UUID id);

    Mono<Void> batchUpdate(TraceBatchUpdate batchUpdate);

    Mono<Trace> get(UUID id);

    Mono<Trace> get(UUID id, boolean stripAttachments);

    Flux<Trace> getByIds(List<UUID> ids);

    Mono<TraceDetails> getTraceDetailsById(UUID id);

    Mono<Void> delete(Set<UUID> ids, UUID projectId);

    Mono<TracePage> find(int page, int size, TraceSearchCriteria criteria);

    Mono<Boolean> existsByProjectId(TraceSearchCriteria criteria, boolean threadScoped);

    Mono<Boolean> validateTraceWorkspace(String workspaceId, Set<UUID> traceIds);

    Mono<TraceCountResponse> countTracesPerWorkspace();

    Mono<BiInformationResponse> getTraceBIInformation();

    Mono<ProjectStats> getStats(TraceSearchCriteria searchCriteria);

    Mono<Long> getDailyCreatedCount();

    Mono<Set<UUID>> getProjectsWithTracesInRange(@NonNull Collection<Pair<String, UUID>> workspaceProjectPairs,
            @NonNull Instant from, @NonNull Instant to);

    Mono<Void> deleteTraceThreads(DeleteTraceThreads traceThreads);

    Flux<Trace> search(int limit, TraceSearchCriteria searchCriteria);

    Mono<Long> countTraces(Set<UUID> projectIds);

    Mono<List<TraceThread>> getMinimalThreadInfoByIds(UUID projectId, Set<String> threadId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceServiceImpl implements TraceService {

    public static final String TRACE_KEY = "Trace";

    private final @NonNull TraceDAO dao;
    private final @NonNull DeletionEventDAO deletionEventDAO;
    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull LockService lockService;
    private final @NonNull EventBus eventBus;
    private final @NonNull TraceSortingFactory traceSortingFactory;
    private final @NonNull AttachmentStripperService attachmentStripperService;
    private final @NonNull AttachmentService attachmentService;
    private final @NonNull AttachmentReinjectorService attachmentReinjectorService;
    private final @NonNull @Config OpikConfiguration config;

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

        // 在下方任何副作用（自动剥离附件删除、项目创建）之前，对无效 id 快速失败，
        // 这样被拒绝的批次永远不会修改状态。在 deferContextual 中运行，以便
        // audit metric 可以将批次自身的 id 归属到请求工作区。
        return Mono.deferContextual(validationCtx -> {
            String validationWorkspaceId = validationCtx.get(RequestContext.WORKSPACE_ID);
            dedupedTraces.forEach(trace -> {
                if (trace.id() != null) {
                    idGenerator.validateId(trace.id(), TRACE_KEY, validationWorkspaceId);
                }
            });
            return attachmentService.deleteAutoStrippedAttachments(EntityType.TRACE, traceIds);
        })
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

                    // id 已在 create(TraceBatch) 中预先校验；生成的 id 天然有效。
                    UUID id = trace.id() == null ? idGenerator.generateId() : trace.id();

                    return trace.toBuilder().id(id).projectId(project.id()).projectName(project.name()).build();
                })
                .toList();
    }

    private Mono<UUID> insertTrace(Trace newTrace, Project project, UUID id) {
        return dao.getPartialById(id)
                .flatMap(existingTrace -> insertTrace(newTrace, project, id, existingTrace))
                .switchIfEmpty(Mono.defer(() -> create(newTrace, project, id)))
                .onErrorResume(this::handleDBError);
    }

    private <T> Mono<T> handleDBError(Throwable ex) {
        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && ex.getMessage().contains("String too long for type FixedString")
                && (ex.getMessage().contains("project_id") || ex.getMessage().contains("workspace_id"))) {

            return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
        }

        return TagOperations.mapTagLimitError(ex);
    }

    private Mono<Project> getProjectById(TraceUpdate traceUpdate) {
        return AsyncUtils.makeMonoContextAware((userName, workspaceId) -> {

            if (traceUpdate.projectId() != null) {
                return Mono.fromCallable(() -> projectService.get(traceUpdate.projectId(), workspaceId));
            }

            return Mono.empty();
        });
    }

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

    private Mono<UUID> create(Trace trace, Project project, UUID id) {
        return template.nonTransaction(connection -> {
            var newTrace = trace.toBuilder().id(id).projectId(project.id()).build();
            return dao.insert(newTrace, connection);
        });
    }

    @Override
    @WithSpan
    public Mono<Void> update(@NonNull TraceUpdate traceUpdate, @NonNull UUID id) {

        var projectName = WorkspaceUtils.getProjectName(traceUpdate.projectName());

        return Mono.deferContextual(ctx -> idGenerator
                .validateIdNotInFutureAsync(id, TRACE_KEY)
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
                                                ctx.getOrDefault(RequestContext.WORKSPACE_NAME, ""),
                                                Map.of(id, project.id()))))
                                        .doOnSuccess(__ -> eventBus.post(new TraceCostIntelligenceChanged(
                                                Map.of(id, project.id()), traceUpdate,
                                                ctx.get(RequestContext.WORKSPACE_ID),
                                                ctx.get(RequestContext.USER_NAME)))))))
                        .then()));
    }

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
                                            userName, batchUpdate.update(), workspaceName, traceToProjectMap));
                                    eventBus.post(new TraceCostIntelligenceChanged(traceToProjectMap,
                                            batchUpdate.update(), workspaceId, userName));
                                });
                    });
        });
    }

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

    private Mono<Project> getProjectByName(String projectName) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> projectService.findByNames(workspaceId, List.of(projectName)))
                    .flatMap(projects -> projects.stream().findFirst().map(Mono::just).orElseGet(Mono::empty))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    private Mono<TraceSearchCriteria> findProjectAndVerifyVisibility(TraceSearchCriteria criteria) {
        return projectService.resolveProjectIdAndVerifyVisibility(criteria.projectId(), criteria.projectName())
                .map(projectId -> criteria.toBuilder()
                        .projectId(projectId)
                        .build());
    }

    private <T> Mono<T> failWithConflict(String error) {
        log.info(error);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(error))));
    }

    @Override
    @WithSpan
    public Mono<Trace> get(@NonNull UUID id) {
        return get(id, false);
    }

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

    @Override
    public Mono<TraceDetails> getTraceDetailsById(UUID id) {
        return template.nonTransaction(connection -> dao.getTraceDetailsById(id, connection))
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Trace", id.toString()))));
    }

    /**
     * 删除给定的 trace id。若显式传入 {@code projectId}，则仅删除该项目内的追踪记录。若未传入
     * （按 id 删除，或跨项目的批次），则为每个 id 解析其所属项目，并按完整的
     * {@code (workspace_id, project_id, id)} 键在每个项目分组下各删除一次 —— 因此一个在多个项目中复用的 id
     * 会从所有这些项目中移除，且不会有任何删除是无项目的（OPIK-7483）。解析不到所属项目的 id
     * 在任何地方都没有存活的 trace，会被跳过：不会为它们发出 {@code TracesDeleted}，因为无项目的
     * 级联删除将是一次无作用域、跨工作区的子记录删除，可能会过度删除一个正在并发写入的 trace 的
     * 子记录；真正的孤儿子行则通过子实体自身的删除端点来清理。
     */
    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull Set<UUID> ids, UUID projectId) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("删除追踪记录，数量 '{}'", ids.size());

        if (projectId != null) {
            var pairs = ids.stream().map(id -> Pair.of(projectId, id)).collect(Collectors.toUnmodifiableSet());
            return template.nonTransaction(connection -> delete(pairs, connection));
        }

        log.info("解析所属项目以删除追踪记录，数量 '{}'", ids.size());
        return resolveOwningProjects(ids)
                .flatMap(projectsByTrace -> {
                    // 展平为 (project_id, trace_id) 键值对，使一个复用的 id 对应到每个所属项目的一组键值对。
                    var pairs = projectsByTrace.entrySet().stream()
                            .flatMap(entry -> entry.getValue().stream()
                                    .map(project -> Pair.of(project, entry.getKey())))
                            .collect(Collectors.toUnmodifiableSet());

                    // 解析只返回被查询的 id，因此其键集是 ids 的子集。
                    var unresolvedIds = ids.stream()
                            .filter(id -> !projectsByTrace.containsKey(id))
                            .collect(Collectors.toUnmodifiableSet());
                    if (!unresolvedIds.isEmpty()) {
                        // 任何项目中都没有存活的 trace：跳过，且不发出无项目的级联删除（见 delete() 的 javadoc）。
                        log.info(
                                "无存活行的 trace id（已不存在），从追踪删除中跳过 '{}'，总数 '{}'",
                                unresolvedIds.size(), ids.size());
                    }

                    return pairs.isEmpty()
                            ? Mono.empty()
                            : template.nonTransaction(connection -> delete(pairs, connection));
                });
    }

    /**
     * 为每个 id 解析其所属项目：先进行有界的快速遍历，再仅对有界遍历未解析到的 id 进行无界遍历。
     * 返回 id -> 所属项目；结果中缺失的 id 表示没有存活的行。
     * <p>
     * 有界遍历的 {@code toMonday(id_at)} 窗口可能漏掉 {@code id_at} 与其 id 不单调对应的行
     * （例如时间戳回绕，OPIK-7456），因此无界遍历会重新解析未命中的集合 —— 有界查询
     * 永远不是一次删除的唯一解析器。解析器查询的 javadoc 描述了每次遍历如何进行剪枝。
     */
    private Mono<Map<UUID, Set<UUID>>> resolveOwningProjects(Set<UUID> ids) {
        return dao.getAllProjectIdsByTraceIdsBounded(ids)
                .flatMap(bounded -> {
                    var missSet = ids.stream()
                            .filter(id -> !bounded.containsKey(id))
                            .collect(Collectors.toUnmodifiableSet());
                    if (missSet.isEmpty()) {
                        return Mono.just(bounded);
                    }
                    log.info(
                            "有界项目解析不完整，无界重新解析未命中集合，未命中 '{}'，总数 '{}'",
                            missSet.size(), ids.size());
                    return dao.getAllProjectIdsByTraceIds(missSet)
                            .map(unbounded -> {
                                // 键集互不相交：无界遍历只携带未命中集合，其中没有任何一个存在于有界结果中。
                                var merged = new HashMap<>(bounded);
                                merged.putAll(unbounded);
                                return merged;
                            });
                });
    }

    private Mono<Void> delete(Set<Pair<UUID, UUID>> projectIdTraceIdPairs, Connection connection) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            return dao.delete(projectIdTraceIdPairs, connection)
                    .doOnSuccess(_ -> projectIdTraceIdPairs.stream()
                            .collect(Collectors.groupingBy(Pair::getLeft,
                                    Collectors.mapping(Pair::getRight, Collectors.toUnmodifiableSet())))
                            .forEach((projectId, traceIds) -> {
                                eventBus.post(TracesDeleted.builder()
                                        .traceIds(traceIds)
                                        .projectId(projectId)
                                        .workspaceId(workspaceId)
                                        .userName(userName)
                                        .build());
                                log.info(
                                        "已发布 TracesDeleted 事件，trace id 数量 '{}'，项目 id '{}'，工作区 '{}'",
                                        traceIds.size(), projectId, workspaceId);
                            }))
                    .then(captureDeletions(projectIdTraceIdPairs, workspaceId, userName));
        });
    }

    /**
     * 在 deletion-events bridge 中记录已删除的 (project_id, trace_id) 键值对，使表迁移期间发起的删除
     * 在复制后仍能存活。在删除之后运行，且为尽力而为：捕获是辅助性的，绝不能
     * 干扰删除，因此失败会被记录并吞掉。在删除之后运行也避免了记录一个
     * 实际并未发生的删除。除非启用捕获，否则为无操作。使用延迟执行，使订阅前（即仅在删除成功后）
     * 不会构建或运行任何内容。
     */
    private Mono<Void> captureDeletions(Set<Pair<UUID, UUID>> projectIdTraceIdPairs, String workspaceId,
            String userName) {
        return Mono.defer(() -> {
            if (!config.getDatabaseAnalyticsDataModel().traceDeletionEventsCaptureEnabled()) {
                return Mono.empty();
            }
            var events = projectIdTraceIdPairs.stream()
                    .map(pair -> DeletionEvent.builder()
                            .sourceTable(SourceTable.TRACES)
                            .workspaceId(workspaceId)
                            .projectId(pair.getLeft())
                            .deletedId(pair.getRight().toString())
                            .deletionReason(DeletionReason.USER_REQUEST)
                            .build())
                    .collect(Collectors.toUnmodifiableSet());
            return deletionEventDAO.insert(events, userName)
                    .doOnSuccess(_ -> log.info("已捕获 trace 删除事件，数量 '{}'，工作区 '{}'",
                            events.size(), workspaceId))
                    .onErrorResume(throwable -> {
                        log.warn("捕获 trace 删除事件失败，数量 '{}'，工作区 '{}'",
                                projectIdTraceIdPairs.size(), workspaceId, throwable);
                        return Mono.empty();
                    });
        });
    }

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

    @Override
    @WithSpan
    public Mono<Boolean> existsByProjectId(@NonNull TraceSearchCriteria criteria, boolean threadScoped) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(resolvedCriteria -> template
                        .nonTransaction(
                                connection -> dao.existsByProjectId(resolvedCriteria, threadScoped, connection)))
                .switchIfEmpty(Mono.just(false));
    }

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

    @Override
    @WithSpan
    public Mono<ProjectStats> getStats(@NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(dao::getStats)
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    @Override
    @WithSpan
    public Mono<Long> getDailyCreatedCount() {
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of())).flatMap(dao::getDailyTraces);
    }

    @Override
    public Mono<Set<UUID>> getProjectsWithTracesInRange(@NonNull Collection<Pair<String, UUID>> workspaceProjectPairs,
            @NonNull Instant from, @NonNull Instant to) {
        if (workspaceProjectPairs.isEmpty()) {
            return Mono.just(Set.of());
        }
        return template.nonTransaction(
                connection -> dao.getProjectsWithTracesInRange(workspaceProjectPairs, from, to, connection));
    }

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

                    var pairs = traceIds.stream().map(id -> Pair.of(projectId, id))
                            .collect(Collectors.toUnmodifiableSet());
                    return delete(pairs, connection);
                })));
    }

    @Override
    public Flux<Trace> search(int limit, @NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMapMany(it -> dao.search(limit, it)
                        .concatMap(trace -> attachmentReinjectorService.reinjectAttachments(trace,
                                !it.stripAttachments())));
    }

    @Override
    public Mono<Long> countTraces(@NonNull Set<UUID> projectIds) {
        return dao.countTraces(projectIds);
    }

    @Override
    public Mono<List<TraceThread>> getMinimalThreadInfoByIds(@NonNull UUID projectId, @NonNull Set<String> threadId) {
        return dao.getMinimalThreadInfoByIds(projectId, threadId)
                .switchIfEmpty(Mono.just(List.of()));
    }

}
