package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.ErrorInfo;
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
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.queues.Queue;
import com.comet.opik.infrastructure.queues.QueueProducer;
import com.comet.opik.utils.JsonUtils;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

    /**
     * 将超过给定阈值、卡在非终态状态的 Studio 运行转换为 {@code ERROR}，
     * 并在其日志中记录一条人类可读的原因（OPIK-7159）。由停滞运行回收器调用。
     *
     * @return 本次遍历中被转换为 ERROR 的运行数量。
     */
    Mono<Long> reconcileStalledStudioOptimizations(Duration initializedTimeout, Duration runningTimeout,
            Duration runningHardTimeout, Duration lookbackMargin, int batchSize, int candidateScanFactor);
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
    private final @NonNull LockService lockService;

    // 取消信号的 Redis 键模式（Python worker 会检查此键）
    private static final String CANCEL_KEY_PATTERN = "opik:cancel:%s";
    // 可取消的状态
    private static final Set<OptimizationStatus> CANCELLABLE_STATUSES = EnumSet.of(
            OptimizationStatus.INITIALIZED,
            OptimizationStatus.RUNNING);
    // 用于平台观测到（而非 worker 上报）的失败的 ErrorInfo。没有可附加的
    // 堆栈，而 ErrorInfo#traceback 是 @NotBlank，所以这里明确说明这一点。
    private static final String SYSTEM_ERROR_TYPE = "SystemDetectedFailure";
    private static final String SYSTEM_ERROR_TRACEBACK = "[System] No traceback: this failure was detected by "
            + "the platform, not reported by the optimizer worker.";

    @Override
    @WithSpan
    public Mono<Optimization> getById(@NonNull UUID id) {
        log.info("按 id '{}' 获取优化", id);
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
                // 创建时传入错误的 project_id 属于客户端错误，而不是此端点上缺少资源 ——
                // 仅在此处将共享的 ProjectService NotFoundException（404）映射为 400，
                // 以免改动 ProjectService.validateProjectIdExists（其他位置也在使用）（OPIK-7029，C5）。
                .onErrorMap(NotFoundException.class,
                        e -> new BadRequestException(e.getMessage(), e))
                .flatMap(resolvedProjectId -> datasetService.getOrCreateDataset(optimization.datasetName(),
                        resolvedProjectId.orElse(null))
                        .map(datasetId -> new AbstractMap.SimpleEntry<>(resolvedProjectId.orElse(null), datasetId)))
                .flatMap(projectAndDataset -> Mono.deferContextual(ctx -> {
                    UUID resolvedProjectId = projectAndDataset.getKey();
                    UUID datasetId = projectAndDataset.getValue();
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    String userName = ctx.get(RequestContext.USER_NAME);

                    // 检查优化是否已存在，以便保留某些字段。
                    // 与 applyUpdate 一样不依赖 FIND，而此路径最需要这一点：这里的结果为空
                    // 不是跳过的更新，而是被当作全新运行处理 —— 强制设为 INITIALIZED、
                    // 重新生成名称，并且不保留 studioConfig、errorInfo 或 createdAt。
                    // 因此，FIND 映射若出现回归，就会把存活的运行复活为全新运行，
                    // 并随之重置回收器的硬上限。
                    return optimizationDAO.getById(id)
                            .switchIfEmpty(Mono.defer(() -> optimizationDAO.getRowById(id)))
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
                                    log.info("优化 '{}' 已存在，保留 studioConfig", id);

                                    // 如果更新中未提供 studioConfig，则保留原有的
                                    if (optimization.studioConfig() == null
                                            && existing.studioConfig() != null) {
                                        builder.studioConfig(existing.studioConfig());
                                    }

                                    // 把已完成的运行移回非终态状态的重新 upsert 是对该 id 的
                                    // 重启，而不是对同一次尝试的再次写入：下面的保留逻辑被限定为
                                    // 排除这种情况。继承 createdAt 会让新尝试沿用首次尝试的时间，
                                    // 因此原始启动时间早于 runningHardTimeout 的运行会一出生就
                                    // 超过上限 —— isPastHardCap 会短路否决，回收器会在启动后
                                    // 几秒钟的下一个 tick 就把它标记为 ERROR。继承 errorInfo 同样会
                                    // 把上一次尝试的失败永久钉在它身上，因为该字段永远不会被清除。
                                    boolean isRestart = existing.status() != null
                                            && existing.status().isTerminal()
                                            && optimization.status() != null
                                            && !optimization.status().isTerminal();

                                    // 清除原因的范围比重启本身更窄，以保持与更新路径上的
                                    // 覆盖规则一致：只有平台猜测出来的失败才会被丢弃。用户取消（CANCELLED）
                                    // 或 worker 上报的 ERROR 是真实结果，在其之上重新 upsert
                                    // 仍必须保留它 —— 这是此路径为之构建的既有约定
                                    // （SDK 重新 upsert 时携带 null errorInfo）。
                                    boolean discardsGuessedFailure = isRestart && isSystemDetectedFailure(existing);

                                    // 如果更新中未提供失败原因，则保留已持久化的失败原因
                                    // （upsert 会整行替换；SDK 以 null errorInfo 重新 upsert，
                                    // 否则会覆盖之前记录的失败）。errorInfo 通常通过 PATCH/update 路径设置。
                                    if (!discardsGuessedFailure && optimization.errorInfo() == null
                                            && existing.errorInfo() != null) {
                                        builder.errorInfo(existing.errorInfo());
                                    }

                                    // 保留原始创建时间：upsert 会整行替换，所以没有这一步的话，
                                    // 每次重新 upsert 时列的 DEFAULT 都会重新打上 created_at。
                                    // 除了界面上时间戳错误之外，停滞运行回收器的硬上限是以
                                    // created_at 为基准计算的，漂移的值会让非状态写入无限期推迟
                                    // 该上限（OPIK-7459）。重启是唯一不得继承它的情形。
                                    if (!isRestart && existing.createdAt() != null) {
                                        builder.createdAt(existing.createdAt());
                                    }

                                    if (isRestart) {
                                        // 强制由服务端打上 last_updated_at。与 createdAt 不同，
                                        // 该列在 View.Write 中，因此客户端可以发送一个过期的值 ——
                                        // 而 UPSERT 会绑定到达的任何值。携带比它所替换的终态版本
                                        // 更旧时间戳的重启会彻底失去 ReplacingMergeTree 的去重：
                                        // argMax 会一直返回旧的终态行，于是该运行读起来仍像是已结束，
                                        // 回收器永远看不到这次新尝试。
                                        builder.lastUpdatedAt(null);
                                        log.info(
                                                "优化 '{}' 从终态状态 '{}' 重启：重置 createdAt 和 lastUpdatedAt，discardingGuessedFailure '{}'",
                                                id, existing.status(), discardsGuessedFailure);
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
                                            "为新的 Studio 优化强制设置 INITIALIZED（原为 '{}'）状态，id '{}'",
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
                                                                "获取 workspaceId '{}' 的工作区名称失败，使用 workspaceId 作为名称：{}",
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
        if (update.name() == null && update.status() == null && update.errorInfo() == null
                && update.metadata() == null) {
            return Mono.empty();
        }

        // 在锁下串行化每次按 id 的状态变更。DAO 的 UPDATE_BY_ID 是一条 INSERT...SELECT，
        // 会把每个未更新的列从它读取的基准版本向前拷贝，因此两个并发的部分写入
        // （重命名与状态写入竞争，或 worker 与回收器竞争）会丢掉其中一边 ——
        // 而丢失的终态状态会让一个已完成的运行卡在非终态。这个锁很轻量，
        // 用丢失更新防护守护每一次写入。Redis 故障导致写入失败是可接受的：
        // 停滞运行回收器是被留在非终态运行的后备保障，所以这里更倾向于保护数据，
        // 而不是无锁回退。注意：生产环境 ClickHouse 没有
        // 读己之写（异步 insert、2 个副本），因此 studio 元数据仍必须保持事实上的
        // 单写者 —— 锁加固的是进程内竞争，而不是跨副本竞争。
        var lock = new LockService.Lock(id, "optimization-update");
        return lockService.executeWithLock(lock, Mono.defer(() -> applyUpdate(id, update)));
    }

    private Mono<Long> applyUpdate(@NonNull UUID id, @NonNull OptimizationUpdate update) {
        return optimizationDAO.getById(id)
                // 纵深防御：getById 的 FIND 曾会丢弃那些无法映射其相关数据的运行
                // （一个指向仍未完成 trace 的试验项 —— 是 worker 在试验中途被终止时
                // 留下的；已由 FIND 的 NaN 防护修复，OPIK-7459）。即使 FIND 再次回归，
                // 状态写入仍必须落地，否则 worker 的终态上报和停滞运行回收器都无法
                // 将这类运行移出 RUNNING。原始行回退携带 null 聚合值，只会降低
                // 完成分析事件的质量。
                .switchIfEmpty(Mono.defer(() -> optimizationDAO.getRowById(id)))
                .switchIfEmpty(Mono.error(failWithNotFound("Optimization", id)))
                .flatMap(optimization -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    // 内部路径（markOptimizationFailedToStart、停滞运行回收器）用 SYSTEM_USER
                    // 填充 USER_NAME；getOrDefault 让分析身份解析保持宽容。
                    String userName = ctx.getOrDefault(RequestContext.USER_NAME, null);

                    // 校验 Studio 优化的取消请求。什么算作可取消 ——
                    // 包括停在一个平台检测到的失败上的运行，理由与下面 worker
                    // 上报会覆盖该 ERROR 相同 —— 由 isCancellable 决定，下面的 Redis 信号
                    // 也共享它：拒绝请求与向 worker 发信号必须保持一致。
                    boolean isStudioCancellation = update.status() == OptimizationStatus.CANCELLED
                            && optimization.studioConfig() != null;

                    if (isStudioCancellation && !isCancellable(optimization)) {
                        return Mono.error(new ClientErrorException(
                                "Cannot cancel optimization with status '%s'. Only optimizations with status %s can be cancelled."
                                        .formatted(optimization.status(), CANCELLABLE_STATUSES),
                                Response.Status.CONFLICT));
                    }

                    // 回收器写入的 ERROR 是一种猜测 —— "worker 已经有一段时间没有上报" —— 而
                    // worker 才是它自己运行的权威。如果之后 worker 上报表明这个猜测
                    // 是错的，那么运行会恢复，而不是被冻结在一个从未发生过的失败上。
                    // 这正是让误回收能够自愈的原因，而对于一个排队时间超过
                    // initializedTimeout、排在 OPTSTUDIO_MAX_CONCURRENT_JOBS 之后的运行来说
                    // 尤其重要：以前回收器会把它标成 ERROR，之后 worker 的每次写入都被
                    // 下面的防护丢弃，子进程最终跑完却把整个 LLM 预算花在一个
                    // 永久显示为失败的运行上（review: thiagohora）。
                    //
                    // 刻意保持收窄。仅限 ERROR，而且仅限本服务自己写入的 ERROR
                    // （SYSTEM_ERROR_TYPE）—— worker 上报的失败和用户 CANCELLED 都是真实
                    // 结果，仍然胜出。真正死掉的运行不会上报任何东西，所以没有任何东西会
                    // 覆盖它，硬上限仍能约束一个只上报却从不完成的僵尸运行。
                    // CANCELLED 与非终态状态一起被允许：它是用户基于同样的疑虑采取的行动，
                    // 上面的取消信号需要这次写入落地。
                    boolean supersedesSystemFailure = isSystemDetectedFailure(optimization)
                            && update.status() != null
                            && update.status() != OptimizationStatus.ERROR;

                    // 绝不允许迟到的状态写入用一个不同的终态状态覆盖一个已经处于终态的
                    // Studio 运行 —— 例如用户 CANCELLED 之后 worker 迟到的 ERROR，
                    // 或者回收器与刚在其过期读取之后落地的 COMPLETED 竞争（OPIK-7159）。
                    // 相同状态的写入和仅改名的更新仍然放行；显式取消
                    // 则保留上面的 409。
                    boolean isTerminalOverwrite = optimization.studioConfig() != null
                            && update.status() != null
                            && optimization.status().isTerminal()
                            && update.status() != optimization.status()
                            && !supersedesSystemFailure;
                    if (isTerminalOverwrite) {
                        log.info(
                                "跳过优化 '{}' 的状态更新：已处于终态 '{}'，忽略请求的 '{}'",
                                id, optimization.status(), update.status());
                        return Mono.just(0L);
                    }

                    // 在交给 DAO 之前，先把任何传入的 metadata 合并到现有 metadata 上，
                    // 这样新的 ReplacingMergeTree 行就携带完整对象（提供的键覆盖，
                    // 现有的键如 optimizer/model 被保留）。当 update.metadata() 为 null 时，
                    // 生效更新保持为 null，DAO 会原样向前拷贝现有列
                    // —— 这能防止 Wave-0 的仅状态更新清空 metadata（OPIK-7159 回归风险）。
                    OptimizationUpdate effectiveUpdate = update.metadata() == null
                            ? update
                            : update.toBuilder()
                                    .metadata(JsonUtils.merge(optimization.metadata(), update.metadata()))
                                    .build();

                    // 覆盖的同时也清除已记录的原因：它描述的是一个并未发生的失败，
                    // 而该列再没有别的东西会去清除，否则它会一直跟着运行
                    // 直到最终 COMPLETED 的版本。
                    return signalCancellationIfNeeded(id, optimization, update)
                            .then(optimizationDAO.update(id, effectiveUpdate,
                                    supersedesSystemFailure && update.errorInfo() == null))
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

        if (!isStudioCancellation || !isCancellable(optimization)) {
            return Mono.empty();
        }

        log.info("为 Studio 优化 '{}' 发送取消信号（当前状态：'{}'）",
                id, optimization.status());

        String cancelKey = String.format(CANCEL_KEY_PATTERN, id);
        long ttlSeconds = config.getOptimizationLogs().getCancellationKeyTtlSeconds();

        return redisClient.getBucket(cancelKey)
                .set("1", ttlSeconds, TimeUnit.SECONDS)
                .doOnSuccess(__ -> log.debug("已在 Redis 中为优化 '{}' 设置取消信号", id))
                // 尽力而为：Redis 的短暂抖动不应让取消请求返回 500，也不应阻塞 CANCELLED 状态
                // 写入。worker 也会轮询 DB 状态，因此漏掉的信号不是唯一的停止
                // 路径；吞掉它（就像 OptimizationLogSyncService.appendSystemLogLine 那样）能保持取消
                // 幂等，并让 .then(update) 仍然持久化 CANCELLED（OPIK-7029，U1）。
                .onErrorResume(error -> {
                    log.warn("在 Redis 中为优化 '{}' 设置取消信号失败；"
                            + "仍继续持久化 CANCELLED 状态", id, error);
                    return Mono.empty();
                })
                .then();
    }

    private void finalizeLogsAsync(String workspaceId, UUID optimizationId) {
        logSyncService.finalizeLogsOnCompletion(workspaceId, optimizationId)
                .doOnError(error -> log.error("为优化 '{}' 完成日志收尾失败",
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
            log.warn("已存在 id 为 '{}' 的优化", id);
            return Mono.just(id);
        }
        log.error("创建 id 为 '{}' 的优化时发生意外异常", id);
        return Mono.error(throwable);
    }

    private void postOptimizationCreatedEvent(Optimization newOptimization, String workspaceId, String userName) {
        log.info("发布优化创建事件，优化 id '{}'，datasetId '{}'，workspaceId '{}'",
                newOptimization.id(), newOptimization.datasetId(), workspaceId);
        eventBus.post(new OptimizationCreated(
                newOptimization.id(),
                newOptimization.datasetId(),
                Instant.now(),
                workspaceId,
                userName));
        log.info("已发布优化创建事件，优化 id '{}'，datasetId '{}'，workspaceId '{}'",
                newOptimization.id(), newOptimization.datasetId(), workspaceId);
    }

    private void enqueueStudioOptimizationJob(Optimization optimization, String workspaceId, String workspaceName,
            String opikApiKey) {
        if (workspaceName == null) {
            log.error(
                    "无法为 id '{}' 的 Studio 优化入队任务 - workspaceName 为 null，标记为 ERROR",
                    optimization.id());
            markOptimizationFailedToStart(optimization.id(), workspaceId);
            return;
        }

        log.info("为 id '{}' 的优化入队 Studio 任务，工作区：'{}'（名称：'{}'）",
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
                        jobId -> log.info("Studio 优化任务成功入队，id：'{}'，jobId：'{}'",
                                optimization.id(), jobId))
                .doOnError(error -> {
                    log.error("为 id '{}' 的 Studio 优化入队任务失败，标记为 ERROR",
                            optimization.id(), error);
                    markOptimizationFailedToStart(optimization.id(), workspaceId);
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
            log.warn("未找到项目 '{}'，无法为优化 '{}' 解析项目名称",
                    optimization.projectId(), optimization.id(), exception);
            return null;
        }
    }

    /**
     * 任务无法入队（Redis 不可达，或工作区名称始终未能解析），因此
     * worker 永远不会运行，运行否则会一直停在 INITIALIZED 直到回收器将其收集。
     * 现在将其标记为 ERROR，并附带一条人类可读的原因 —— 参照 {@link #markStalledOptimizationAsError}：
     * 先把原因记录到运行日志中（这样即使 worker 没有产生任何日志，UI 也能展示出来），
     * 然后复用标准的 {@link #update} 路径。入队失败不是用户主动发起的，所以
     * ERROR（而非 CANCELLED）才是如实的状态（OPIK-7029，Q1）。
     */
    private void markOptimizationFailedToStart(UUID optimizationId, String workspaceId) {
        String reason = "[System] Optimization failed to start: the run could not be queued.";

        appendSystemReasonAndMarkError(workspaceId, optimizationId, reason)
                .contextWrite(headlessSystemContext(workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> log.info("已将优化 '{}'（工作区 '{}'）标记为 ERROR（启动失败）",
                                optimizationId, workspaceId),
                        error -> log.error("将优化 '{}'（工作区 '{}'）标记为 ERROR 失败",
                                optimizationId, workspaceId, error));
    }

    /**
     * 入队失败路径（{@link #markOptimizationFailedToStart}）和停滞运行回收器
     * （{@link #markStalledOptimizationAsError}）共用的"记录系统原因，然后翻转为 ERROR"工作流：
     * 先把 {@code [System]} 行追加到运行日志中（这样即使 worker 没有产生任何日志，UI 也能展示出来），
     * 然后复用标准的 {@link #update} 路径（它会完成日志收尾 + 发出
     * 完成事件）。调用方应用 {@link #headlessSystemContext} 及其各自的订阅/防护语义。
     * <p>
     * 原因被有意记录两次：UI 优先读取 {@code error_info.message}，只有在无法获取日志时才
     * 回退到抓取 studio 日志，因此一个日志无法获取（S3 错误、预签名 URL 过期）的运行
     * 否则只会显示一个笼统的"以错误结束"，而丢掉平台已经确切知道的原因。
     */
    private Mono<Long> appendSystemReasonAndMarkError(String workspaceId, UUID optimizationId, String reason) {
        var errorUpdate = OptimizationUpdate.builder()
                .status(OptimizationStatus.ERROR)
                .errorInfo(ErrorInfo.builder()
                        .exceptionType(SYSTEM_ERROR_TYPE)
                        .message(reason)
                        .traceback(SYSTEM_ERROR_TRACEBACK)
                        .build())
                .build();
        return logSyncService.appendSystemLogLine(workspaceId, optimizationId, reason)
                .then(Mono.defer(() -> update(optimizationId, errorUpdate)));
    }

    /**
     * 两个系统驱动的转换都需要的无头响应式上下文。要填充两个键：getById/update 会
     * 通过 makeFluxContextAware / bindUserNameAndWorkspaceContextToStream 解析，它们调用 ctx.get(USER_NAME)，
     * 若缺失则抛出 NoSuchElementException —— 因此只有 WORKSPACE_ID 会静默地让整个
     * update 失败（这正是入队失败 / 停滞运行此前一直保持非终态的原因，OPIK-7159/7029）。
     */
    private static Function<Context, Context> headlessSystemContext(String workspaceId) {
        return ctx -> ctx
                .put(RequestContext.WORKSPACE_ID, workspaceId)
                .put(RequestContext.USER_NAME, RequestContext.SYSTEM_USER);
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

    // ==================== Studio 方法 ====================

    @Override
    public Mono<OptimizationStudioLog> generateStudioLogsResponse(@NonNull UUID optimizationId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            log.debug("为 Studio 优化 '{}' 生成日志响应，工作区：'{}'", optimizationId,
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

    @Override
    @WithSpan
    public Mono<Long> reconcileStalledStudioOptimizations(@NonNull Duration initializedTimeout,
            @NonNull Duration runningTimeout, @NonNull Duration runningHardTimeout, @NonNull Duration lookbackMargin,
            int batchSize, int candidateScanFactor) {
        // 先截断到整秒，因为这是 SQL 侧的分辨率：DAO
        // 把它们绑定为 :..._seconds。若在 Java 副本上保留亚秒余数，会让
        // 查询和下面的防护相差最多一秒 —— 查询会选出一个随后被防护
        // 否决的运行，而 buildStalledReason 可能会引用一个查询并未使用的阈值。在一个
        // 位置统一截断能让每个消费者使用相同的数值（review: baz-reviewer）。
        var initialized = initializedTimeout.truncatedTo(ChronoUnit.SECONDS);
        var running = runningTimeout.truncatedTo(ChronoUnit.SECONDS);
        var hardCap = runningHardTimeout.truncatedTo(ChronoUnit.SECONDS);
        var lookback = lookbackMargin.truncatedTo(ChronoUnit.SECONDS);

        return optimizationDAO
                .findStalledStudioOptimizations(initialized, running, hardCap, lookback, batchSize,
                        candidateScanFactor)
                // 串行处理：停滞运行很少见，这样能保持回收器的 DB/Redis 占用很小。
                .concatMap(stalled -> markStalledOptimizationAsError(stalled, initialized, running, hardCap))
                .reduce(0L, Long::sum);
    }

    /**
     * 尽力将单个停滞运行转换为 ERROR。先把原因记录到运行日志中
     * （这样即使 worker 从未产生任何日志，UI 也能展示出来），然后复用标准的
     * {@link #update} 路径 —— 它会完成日志收尾并发出完成分析事件。绝不
     * 使整轮处理失败：单行失败会被记录日志并计为 0。
     */
    private Mono<Long> markStalledOptimizationAsError(OptimizationDAO.StalledOptimization stalled,
            Duration initializedTimeout, Duration runningTimeout, Duration runningHardTimeout) {
        UUID id = stalled.id();
        String workspaceId = stalled.workspaceId();

        // 在工作区上下文下重新读取，并且只有在运行仍处于非终态、且按照回收器查询所用
        // 相同的活性标准仍处于死掉状态时才翻转：全量查询和这次更新并非
        // 原子操作，因此中间上报的终态状态（状态过滤）或中间写入的 trial/item
        // （活性复查，OPIK-7459）必须否决该转换 —— 否则一个真实的
        // 完成或一个横跨窗口边界的慢而存活的 trial 就会被覆盖为 ERROR。
        // 重新读取的是裸状态快照，而非 getById：参见 OptimizationDAO#getStatusSnapshotById
        // 了解为什么完整的 FIND 不得把关回收。
        return optimizationDAO.getStatusSnapshotById(id)
                .filter(current -> CANCELLABLE_STATUSES.contains(current.status()))
                .filterWhen(current -> isStillDead(current, id, initializedTimeout, runningTimeout,
                        runningHardTimeout))
                .flatMap(current -> {
                    // 从重新读取的当前状态（而非回收器查询到的过期状态）构建原因：
                    // 一个在查询与这里之间从 INITIALIZED 变为 RUNNING 的运行必须得到
                    // "无活动"消息，而不是"启动失败"消息。
                    String reason = buildStalledReason(current, initializedTimeout, runningTimeout,
                            runningHardTimeout);
                    log.warn(
                            "正在将停滞的 studio 优化 '{}'（工作区 '{}'，状态 '{}'）调和为 ERROR：{}",
                            id, workspaceId, current.status(), reason);
                    // 只有当 update() 真正转换了这一行时才计数。当 update() 自身的
                    // 终态覆盖防护触发时（worker 在上面的重新读取与 update() 自己的重新读取
                    // 之间的狭小窗口内上报了终态状态），它返回 Mono.just(0L)，
                    // 因此一个裸的 thenReturn(1L) 会把这个无操作多算一次。空
                    // 完成意味着该行已写入但 ClickHouse 报告无计数 -> 仍计为 1。
                    return appendSystemReasonAndMarkError(workspaceId, id, reason)
                            .map(rowsUpdated -> rowsUpdated > 0 ? 1L : 0L)
                            .defaultIfEmpty(1L);
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info(
                            "跳过优化 '{}'（工作区 '{}'）的停滞调和：已不再停滞",
                            id, workspaceId);
                    return 0L;
                }))
                .contextWrite(headlessSystemContext(workspaceId))
                .onErrorResume(error -> {
                    log.error("调和停滞的 studio 优化 '{}'（工作区 '{}'）失败",
                            id, workspaceId, error);
                    return Mono.just(0L);
                });
    }

    /**
     * 按回收器自身的活性定义复查候选者是否仍然死掉。超过硬上限的运行
     * 无论最近是否有 trial/item 写入都会被回收（该上限正是为那些持续产生行的
     * 僵尸运行而存在的），因此活性探测只对少数非硬上限候选者
     * 运行 —— 而且只花费一次查询。
     *
     * <p>该探测同时覆盖 {@code INITIALIZED} 和 {@code RUNNING}，且使用与全量查询对两者
     * 都相同的 {@code runningTimeout} 窗口：一个正在写试验实验的运行无论其行卡在哪个状态
     * 都是存活的，而且此防护必须与查询的否决保持完全一致，否则一个查询放过的运行
     * 仍可能在这里被回收（反之亦然）。一个确实从未启动的运行没有任何
     * trial，所以探测一无所获，它仍会在 {@code initializedTimeout} 上被回收。
     *
     * <p>活性是三个信号中最新的一环，三者都在这里检查 —— 包括行自身的
     * {@code last_updated_at}。只检查 trial/item 探测会让防护严格弱于它所应镜像的
     * 全量查询：批处理按顺序清空（最多 {@code batchSize} 个运行，每个都是一次快照读取 + 活性探测 +
     * 日志追加 + 更新），因此从扫描到某一行的更新之间会经过数秒到数分钟。一个被选为
     * 过期 {@code INITIALIZED} 候选者的运行，若其 worker 在这段间隙调用了 {@code mark_running}，
     * 就会以全新的行时间戳变回 {@code RUNNING} 且还没有任何 trial —— 全量查询不会
     * 在 {@code RUNNING} 分支下选中它，因此此防护也不可以。所以阈值跟随重新读取的状态，
     * 与查询的 {@code HAVING} 分支完全一致。
     */
    private Mono<Boolean> isStillDead(OptimizationDAO.OptimizationStatusSnapshot current, UUID id,
            Duration initializedTimeout, Duration runningTimeout, Duration runningHardTimeout) {
        if (isPastHardCap(current, runningHardTimeout)) {
            return Mono.just(true);
        }
        Duration rowTimeout = current.status() == OptimizationStatus.INITIALIZED
                ? initializedTimeout
                : runningTimeout;
        if (current.lastUpdatedAt().isAfter(Instant.now().minus(rowTimeout))) {
            return Mono.just(false);
        }
        return optimizationDAO.hasRecentStudioActivity(id, runningTimeout).map(active -> !active);
    }

    /**
     * 该运行是否可以被接受取消？取消的两面 —— 用 409 拒绝请求与
     * 通过 Redis 向 worker 发信号 —— 必须询问这一个谓词，绝不直接询问
     * {@link #CANCELLABLE_STATUSES}。它们曾经分叉过：只在 409 侧接纳系统检测到的失败
     * 会让状态翻转为 {@code CANCELLED} 而 {@code opik:cancel:<id>} 却从未写入，
     * 而那个键是 worker 唯一的取消通道（它通过 MGET 轮询它，没有 DB 状态
     * 回退）。于是运行在 UI 中显示为已取消，而它的子进程仍在运行并花光了整个 LLM
     * 预算 —— 这恰恰是接纳该状态本要避免的结果（review: thiagohora）。
     *
     * <p>除了非终态状态外，这里还接纳停在一个由回收器写入的 ERROR 上的运行：该 ERROR 是
     * 猜测，它的 worker 可能仍在运行，拒绝取消会让用户失去唯一能
     * 停止工作的动作。worker 上报的失败是真实结果，保持不可取消。
     */
    private static boolean isCancellable(Optimization optimization) {
        return CANCELLABLE_STATUSES.contains(optimization.status()) || isSystemDetectedFailure(optimization);
    }

    /**
     * 该运行的 ERROR 是由平台观测到停滞而写入的，还是由 worker 上报的？
     * 以本服务打上的 {@code exceptionType} 为依据，它是区分两者的唯一标记 ——
     * 两者都通过同一条更新路径落到同一列。
     */
    private static boolean isSystemDetectedFailure(Optimization optimization) {
        return optimization.studioConfig() != null
                && optimization.status() == OptimizationStatus.ERROR
                && optimization.errorInfo() != null
                && SYSTEM_ERROR_TYPE.equals(optimization.errorInfo().exceptionType());
    }

    /**
     * 上限从运行创建时刻起算，而不是从 {@code last_updated_at} 起算：对行的任何写入
     * 都会刷新后者（一次 metadata PATCH、一次 SDK 重新 upsert），这会放任运行无限期推迟
     * 后备保障 —— 恰恰是这个任务所要终结的永恒转圈。{@code created_at} 现在
     * 在重新 upsert 之间被保留（参见本类中的 upsert 路径），因此普通客户端写入无法
     * 移动它（review: baz-reviewer，OPIK-7459）。
     *
     * <p>在相同 id 下重启一个已完成的运行是刻意重置它的唯一情形：那是
     * 一次新尝试，继承旧时钟会让它一出生就超过上限。upsert 路径
     * 将其 {@code createdAt} 保留限定为排除该转换。
     *
     * <p>{@code startedAt} 之所以能在全量查询与这次重新读取之间比较，是因为两者
     * 都在相同的回溯窗口上推导它 —— 参见 {@link OptimizationDAO#getStatusSnapshotById}。
     */
    private static boolean isPastHardCap(OptimizationDAO.OptimizationStatusSnapshot current,
            Duration runningHardTimeout) {
        return current.startedAt().isBefore(Instant.now().minus(runningHardTimeout));
    }

    /**
     * 硬上限情形在状态分支之前检查，因为该上限现在同样适用于
     * {@code INITIALIZED} 运行 —— 一个卡在 INITIALIZED 而僵尸 worker 仍持续写行的运行
     * 会被上限回收，而"启动失败"对于告知用户这件事是错误的说辞。
     * 每个时长都用 {@link DurationFormatUtils} 渲染，而非固定单位，这样亚小时和
     * 多小时的配置都能正确显示（裸 {@code toMinutes()} 会把 {@code initializedTimeout}
     * 允许的 24 小时最大值渲染成 "1440 分钟"）。
     */
    private String buildStalledReason(OptimizationDAO.OptimizationStatusSnapshot current,
            Duration initializedTimeout, Duration runningTimeout, Duration runningHardTimeout) {
        if (isPastHardCap(current, runningHardTimeout)) {
            return ("[System] Optimization failed: the run exceeded the maximum running time of %s without "
                    + "completing and was marked as failed. The optimizer worker may be stuck.")
                    .formatted(DurationFormatUtils.formatDurationWords(runningHardTimeout.toMillis(), true, true));
        }
        if (current.status() == OptimizationStatus.RUNNING) {
            return ("[System] Optimization failed: the run made no progress (no status change, new trial, or "
                    + "evaluated item) for over %s and was marked as failed. The optimizer worker may have crashed "
                    + "or been terminated.")
                    .formatted(DurationFormatUtils.formatDurationWords(runningTimeout.toMillis(), true, true));
        }
        return ("[System] Optimization failed to start: the optimizer worker did not begin processing within %s "
                + "and may be unavailable. The run was marked as failed.")
                .formatted(DurationFormatUtils.formatDurationWords(initializedTimeout.toMillis(), true, true));
    }
}
