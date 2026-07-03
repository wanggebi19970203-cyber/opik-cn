package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationStudioLog;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.api.events.OptimizationCreated;
import com.comet.opik.api.events.OptimizationsDeleted;
import com.comet.opik.domain.attachment.PreSignerService;
import com.comet.opik.domain.optimization.OptimizationLogSyncService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.infrastructure.queues.Queue;
import com.comet.opik.infrastructure.queues.QueueProducer;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(OptimizationServiceImpl.class)
public interface OptimizationService {

    Mono<UUID> upsert(@NonNull Optimization optimization);

    Mono<Optimization> getById(UUID id);

    Mono<Optimization.OptimizationPage> find(int page, int size, OptimizationSearchCriteria searchCriteria);

    Mono<Void> delete(@NonNull Set<UUID> ids);

    Flux<DatasetLastOptimizationCreated> getMostRecentCreatedOptimizationFromDatasets(Set<UUID> datasetIds);

    Mono<Long> update(UUID commentId, OptimizationUpdate update);

    Mono<Long> updateDatasetDeleted(Set<UUID> datasetIds);

    // Studio 方法
    Mono<OptimizationStudioLog> generateStudioLogsResponse(UUID optimizationId);
}

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
class OptimizationServiceImpl implements OptimizationService {

    private final @NonNull OptimizationDAO optimizationDAO;
    private final @NonNull DatasetService datasetService;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull NameGenerator nameGenerator;
    private final @NonNull EventBus eventBus;
    private final @NonNull PreSignerService preSignerService;
    private final @NonNull QueueProducer queueProducer;
    private final @NonNull WorkspaceNameService workspaceNameService;
    private final @NonNull OpikConfiguration config;
    private final @NonNull OptimizationLogSyncService logSyncService;
    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull AnalyticsService analyticsService;

    // 取消信号的 Redis 键模式（Python worker 会检查此键）
    private static final String CANCEL_KEY_PATTERN = "opik:cancel:%s";
    // 可取消的状态
    private static final Set<OptimizationStatus> CANCELLABLE_STATUSES = EnumSet.of(
            OptimizationStatus.INITIALIZED,
            OptimizationStatus.RUNNING);

    @Override
    @WithSpan
    public Mono<Optimization> getById(@NonNull UUID id) {
        log.info("Getting optimization by id '{}'", id);
        return optimizationDAO.getById(id)
                .flatMap(optimization -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    var enriched = enrichOptimizations(List.of(optimization), workspaceId).getFirst();
                    return Mono.just(enriched);
                }))
                .switchIfEmpty(Mono.defer(
                        () -> Mono.error(new NotFoundException("Not found optimization with id '%s'".formatted(id)))));
    }

    @Override
    @WithSpan
    public Mono<Optimization.OptimizationPage> find(int page, int size,
            @NonNull OptimizationSearchCriteria searchCriteria) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            var resolvedCriteria = resolveDatasetNameFilter(searchCriteria, workspaceId);

            if (resolvedCriteria == null) {
                return Mono.just(Optimization.OptimizationPage.empty(page, List.of()));
            }

            return optimizationDAO.find(page, size, resolvedCriteria)
                    .flatMap(optimizationPage -> {
                        var enrichedOptimizations = enrichOptimizations(optimizationPage.content(), workspaceId);
                        return Mono.just(optimizationPage.toBuilder()
                                .content(enrichedOptimizations).build());
                    });
        });
    }

    private Mono<Optional<UUID>> resolveProjectId(Optimization optimization) {
        return projectService.resolveProjectIdOrCreate(optimization.projectId(), optimization.projectName());
    }

    /**
     * @return 解析后的搜索条件，如果数据集名称过滤未匹配到任何数据集则返回 {@code null}（调用方应返回空结果）
     */
    private OptimizationSearchCriteria resolveDatasetNameFilter(
            OptimizationSearchCriteria searchCriteria, String workspaceId) {
        if (StringUtils.isBlank(searchCriteria.datasetName())) {
            return searchCriteria;
        }

        var datasetIds = datasetService.findIdsByPartialName(workspaceId, searchCriteria.datasetName());

        if (datasetIds.isEmpty()) {
            return null;
        }

        return searchCriteria.toBuilder()
                .datasetIds(datasetIds)
                .build();
    }

    @Override
    @WithSpan
    public Mono<UUID> upsert(@NonNull Optimization optimization) {
        UUID id = optimization.id() == null ? idGenerator.generateId() : optimization.id();
        IdGenerator.validateVersion(id, "Optimization");

        // 检测是否为 Studio 优化（请求中包含 studioConfig）
        boolean isStudioOptimization = optimization.studioConfig() != null;

        return resolveProjectId(optimization)
                .flatMap(resolvedProjectId -> datasetService.getOrCreateDataset(optimization.datasetName(),
                        resolvedProjectId.orElse(null))
                        .map(datasetId -> new AbstractMap.SimpleEntry<>(resolvedProjectId.orElse(null), datasetId)))
                .flatMap(projectAndDataset -> Mono.deferContextual(ctx -> {
                    UUID resolvedProjectId = projectAndDataset.getKey();
                    UUID datasetId = projectAndDataset.getValue();
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    String userName = ctx.get(RequestContext.USER_NAME);

                    // 检查优化是否已存在，以保留特定字段
                    return optimizationDAO.getById(id)
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(existingOpt -> {
                                var builder = optimization.toBuilder()
                                        .id(id)
                                        .datasetId(datasetId)
                                        .projectId(resolvedProjectId);

                                // 更新时保留已有字段（SDK 不了解 studioConfig）
                                if (existingOpt.isPresent()) {
                                    var existing = existingOpt.get();
                                    log.info("Optimization '{}' already exists, preserving studioConfig", id);

                                    // 如果更新中未提供 studioConfig，则保留原有的
                                    if (optimization.studioConfig() == null
                                            && existing.studioConfig() != null) {
                                        builder.studioConfig(existing.studioConfig());
                                    }

                                    // 仅当传入名称为空时保留原始名称
                                    //（SDK 发送空名称，但显式更新应被采纳）
                                    if (StringUtils.isBlank(optimization.name())) {
                                        builder.name(existing.name());
                                    } else {
                                        builder.name(optimization.name());
                                    }

                                    // 不为已存在的优化重新入队任务
                                } else {
                                    // 新优化：如果未提供名称则自动生成
                                    var name = StringUtils.getIfBlank(optimization.name(),
                                            nameGenerator::generateName);
                                    builder.name(name);
                                }

                                // 仅对新的 Studio 优化强制设置 INITIALIZED 状态
                                if (isStudioOptimization && existingOpt.isEmpty()) {
                                    builder.status(OptimizationStatus.INITIALIZED);
                                    log.info(
                                            "Force INITIALIZED (was '{}') status for NEW Studio optimization id '{}'",
                                            optimization.status(), id);
                                }

                                var newOptimization = builder.build();
                                boolean shouldEnqueueJob = isStudioOptimization && existingOpt.isEmpty();

                                return optimizationDAO.upsert(newOptimization)
                                        .thenReturn(newOptimization.id())
                                        .doOnSuccess(__ -> {
                                            postOptimizationCreatedEvent(newOptimization, workspaceId,
                                                    userName);
                                            if (existingOpt.isEmpty()) {
                                                Schedulers.boundedElastic().schedule(
                                                        () -> analyticsService.trackEvent(
                                                                "opik_optimization_created",
                                                                Map.of(
                                                                        "optimization_id",
                                                                        newOptimization.id().toString(),
                                                                        "dataset_name",
                                                                        String.valueOf(
                                                                                newOptimization.datasetName()),
                                                                        "objective_name",
                                                                        String.valueOf(
                                                                                newOptimization.objectiveName()),
                                                                        "project_id",
                                                                        String.valueOf(
                                                                                newOptimization.projectId()),
                                                                        "workspace_id", workspaceId),
                                                                userName));
                                            }

                                            // 仅对新的 Studio 优化入队任务
                                            if (shouldEnqueueJob) {
                                                String workspaceName = ctx.getOrDefault(
                                                        RequestContext.WORKSPACE_NAME,
                                                        null);
                                                if (StringUtils.isBlank(workspaceName)) {
                                                    try {
                                                        workspaceName = workspaceNameService.getWorkspaceName(
                                                                workspaceId,
                                                                config.getAuthentication().getReactService()
                                                                        .url());
                                                    } catch (Exception e) {
                                                        log.warn(
                                                                "Failed to get workspace name for workspaceId '{}', using workspaceId as name: {}",
                                                                workspaceId, e.getMessage());
                                                        workspaceName = workspaceId;
                                                    }
                                                }

                                                String opikApiKey = newOptimization.studioConfig() != null
                                                        ? newOptimization.studioConfig().opikApiKey()
                                                        : null;

                                                enqueueStudioOptimizationJob(newOptimization, workspaceId,
                                                        workspaceName, opikApiKey);
                                            }
                                        });
                            });
                }))
                .subscribeOn(Schedulers.boundedElastic())
                // 如果发生冲突，直接返回已有实验的 ID。
                // 如果发生其他错误，则抛出异常。两种情况都不会发布事件。
                .onErrorResume(throwable -> handleCreateError(throwable, id));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return optimizationDAO.getOptimizationDatasetIds(ids)
                .flatMap(optimizationDatasetIds -> Mono.deferContextual(ctx -> optimizationDAO.delete(ids)
                        .doOnSuccess(unused -> eventBus.post(new OptimizationsDeleted(
                                optimizationDatasetIds.stream()
                                        .map(DatasetEventInfoHolder::datasetId)
                                        .collect(Collectors.toSet()),
                                ctx.get(RequestContext.WORKSPACE_ID),
                                ctx.get(RequestContext.USER_NAME))))))
                .then();
    }

    @Override
    @WithSpan
    public Flux<DatasetLastOptimizationCreated> getMostRecentCreatedOptimizationFromDatasets(Set<UUID> datasetIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetIds), "Argument 'datasetIds' must not be empty");

        return optimizationDAO.getMostRecentCreatedExperimentFromDatasets(datasetIds);
    }

    @Override
    public Mono<Long> update(@NonNull UUID id, @NonNull OptimizationUpdate update) {
        if (update.name() == null && update.status() == null) {
            return Mono.empty();
        }

        return optimizationDAO.getById(id)
                .switchIfEmpty(Mono.error(failWithNotFound("Optimization", id)))
                .flatMap(optimization -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    // 在内部 cancelOptimization() 路径中 USER_NAME 不存在，仅设置了
                    // WORKSPACE_ID — 回退并让 AnalyticsService 解析身份。
                    String userName = ctx.getOrDefault(RequestContext.USER_NAME, null);

                    // 验证 Studio 优化的取消请求
                    boolean isStudioCancellation = update.status() == OptimizationStatus.CANCELLED
                            && optimization.studioConfig() != null;
                    boolean isNotCancellable = !CANCELLABLE_STATUSES.contains(optimization.status());

                    if (isStudioCancellation && isNotCancellable) {
                        return Mono.error(new ClientErrorException(
                                "Cannot cancel optimization with status '%s'. Only optimizations with status %s can be cancelled."
                                        .formatted(optimization.status(), CANCELLABLE_STATUSES),
                                Response.Status.CONFLICT));
                    }

                    return signalCancellationIfNeeded(id, optimization, update)
                            .then(optimizationDAO.update(id, update))
                            .doOnSuccess(__ -> {
                                // 当优化达到终态时同步日志
                                // 可安全多次调用 - 仅同步并减少 TTL
                                if (update.status() != null && update.status().isTerminal()) {
                                    finalizeLogsAsync(workspaceId, id);
                                    // 仅在转换到终态时发出完成事件
                                    if (!optimization.status().isTerminal()) {
                                        Schedulers.boundedElastic().schedule(() -> analyticsService.trackEvent(
                                                "opik_optimization_completed",
                                                Map.of(
                                                        "optimization_id", optimization.id().toString(),
                                                        "status", update.status().getValue(),
                                                        "workspace_id", workspaceId,
                                                        "num_trials", String.valueOf(optimization.numTrials()),
                                                        "baseline_objective_score",
                                                        String.valueOf(optimization.baselineObjectiveScore()),
                                                        "best_objective_score",
                                                        String.valueOf(optimization.bestObjectiveScore())),
                                                userName));
                                    }
                                }
                            });
                }));
    }

    /**
     * 如果是有效的 Studio 优化取消请求，向 Redis 发送取消信号。
     * Python worker 轮询此 Redis 键以检测取消请求。
     *
     * @param id 优化 ID
     * @param optimization 当前优化状态
     * @param update 请求的更新
     * @return 信号设置完成时发出的 Mono，如果不需要信号则为空
     */
    private Mono<Void> signalCancellationIfNeeded(UUID id, Optimization optimization, OptimizationUpdate update) {
        boolean isStudioCancellation = update.status() == OptimizationStatus.CANCELLED
                && optimization.studioConfig() != null;
        boolean isCancellable = CANCELLABLE_STATUSES.contains(optimization.status());

        if (!isStudioCancellation || !isCancellable) {
            return Mono.empty();
        }

        log.info("Signalling cancellation for Studio optimization '{}' (current status: '{}')",
                id, optimization.status());

        String cancelKey = String.format(CANCEL_KEY_PATTERN, id);
        long ttlSeconds = config.getOptimizationLogs().getCancellationKeyTtlSeconds();

        return redisClient.getBucket(cancelKey)
                .set("1", ttlSeconds, TimeUnit.SECONDS)
                .doOnSuccess(__ -> log.debug("Set cancellation signal in Redis for optimization '{}'", id))
                .then();
    }

    private void finalizeLogsAsync(String workspaceId, UUID optimizationId) {
        logSyncService.finalizeLogsOnCompletion(workspaceId, optimizationId)
                .doOnError(error -> log.error("Failed to finalize logs for optimization '{}'",
                        optimizationId, error))
                .subscribe();
    }

    @Override
    public Mono<Long> updateDatasetDeleted(@NonNull Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Mono.empty();
        }

        return optimizationDAO.updateDatasetDeleted(datasetIds);
    }

    private Mono<UUID> handleCreateError(Throwable throwable, UUID id) {
        if (throwable instanceof ClickHouseException
                && throwable.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && throwable.getMessage().contains("_CAST(id, FixedString(36))")) {
            log.warn("Already exists optimization with id '{}'", id);
            return Mono.just(id);
        }
        log.error("Unexpected exception creating optimization with id '{}'", id);
        return Mono.error(throwable);
    }

    private void postOptimizationCreatedEvent(Optimization newOptimization, String workspaceId, String userName) {
        log.info("Posting optimization created event for optimization id '{}', datasetId '{}', workspaceId '{}'",
                newOptimization.id(), newOptimization.datasetId(), workspaceId);
        eventBus.post(new OptimizationCreated(
                newOptimization.id(),
                newOptimization.datasetId(),
                Instant.now(),
                workspaceId,
                userName));
        log.info("Posted optimization created event for optimization id '{}', datasetId '{}', workspaceId '{}'",
                newOptimization.id(), newOptimization.datasetId(), workspaceId);
    }

    private void enqueueStudioOptimizationJob(Optimization optimization, String workspaceId, String workspaceName,
            String opikApiKey) {
        if (workspaceName == null) {
            log.error(
                    "Cannot enqueue Studio optimization job for id: '{}' - workspaceName is null, marking as CANCELLED",
                    optimization.id());
            cancelOptimization(optimization.id(), workspaceId);
            return;
        }

        log.info("Enqueuing Optimization Studio job for id: '{}', workspace: '{}' (name: '{}')",
                optimization.id(), workspaceId, workspaceName);

        String projectName = resolveProjectNameForJob(optimization, workspaceId);

        // 构建任务消息（SDK 使用工作区名称，日志存储使用工作区 ID）
        var jobMessage = OptimizationStudioJobMessage.builder()
                .optimizationId(optimization.id())
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .config(optimization.studioConfig())
                .opikApiKey(opikApiKey)
                .projectName(projectName)
                .build();

        var queue = resolveQueue(optimization);
        queueProducer.enqueue(queue, jobMessage)
                .doOnSuccess(
                        jobId -> log.info("Studio optimization job enqueued successfully for id: '{}', jobId: '{}'",
                                optimization.id(), jobId))
                .doOnError(error -> {
                    log.error("Failed to enqueue Studio optimization job for id: '{}', marking as CANCELLED",
                            optimization.id(), error);
                    cancelOptimization(optimization.id(), workspaceId);
                })
                .subscribe();
    }

    private Queue resolveQueue(Optimization optimization) {
        return Queue.OPTIMIZER_CLOUD;
    }

    private String resolveProjectNameForJob(Optimization optimization, String workspaceId) {
        if (optimization.projectId() == null) {
            return null;
        }
        try {
            return projectService.get(optimization.projectId(), workspaceId).name();
        } catch (NotFoundException exception) {
            // 项目可能在优化创建和任务入队之间被删除。
            // 优雅降级：studio runner 将回退到 SDK 默认项目。
            // 其他异常则允许传播。
            log.warn("Project '{}' not found while resolving project name for optimization '{}'",
                    optimization.projectId(), optimization.id(), exception);
            return null;
        }
    }

    private void cancelOptimization(UUID optimizationId, String workspaceId) {
        var optimizationUpdate = OptimizationUpdate.builder()
                .status(OptimizationStatus.CANCELLED)
                .build();

        update(optimizationId, optimizationUpdate)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> log.info("Cancelled optimization '{}'", optimizationId),
                        error -> log.error("Failed to cancel optimization '{}'", optimizationId, error));
    }

    private List<Optimization> enrichOptimizations(List<Optimization> optimizations, String workspaceId) {
        var ids = optimizations.stream().map(Optimization::datasetId).collect(Collectors.toUnmodifiableSet());
        var datasetMap = datasetService.findByIds(ids, workspaceId)
                .stream().collect(Collectors.toMap(Dataset::id, Function.identity()));

        return optimizations.stream()
                .map(optimization -> optimization.toBuilder()
                        .datasetName(Optional
                                .ofNullable(datasetMap.get(optimization.datasetId()))
                                .map(Dataset::name)
                                .orElse(null))
                        .build())
                .toList();
    }

    // ==================== Studio Methods ====================

    @Override
    public Mono<OptimizationStudioLog> generateStudioLogsResponse(@NonNull UUID optimizationId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            log.debug("Generating logs response for Studio optimization: '{}' in workspace: '{}'", optimizationId,
                    workspaceId);

            // 使用 OptimizationLogSyncService 的共享方法构建 S3 键
            String s3Key = OptimizationLogSyncService.formatS3Key(workspaceId, optimizationId);

            // TODO: 检查日志文件是否存在于 S3 中并获取最后修改时间
            // 目前 lastModified 返回 null（新优化的文件尚不存在）
            Instant lastModified = null;

            // 生成预签名 URL 并计算过期时间
            String presignedUrl = preSignerService.presignDownloadUrl(s3Key);
            long expirationSeconds = preSignerService.getPresignedUrlExpirationSeconds();
            Instant expiresAt = Instant.now().plus(Duration.ofSeconds(expirationSeconds));

            return Mono.just(OptimizationStudioLog.builder()
                    .url(presignedUrl)
                    .lastModified(lastModified)
                    .expiresAt(expiresAt)
                    .build());
        });
    }
}
