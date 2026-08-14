package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemChanges;
import com.comet.opik.api.DatasetItemEdit;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.PageColumns;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.api.validation.DatasetItemBatchValidator;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RetryUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.infrastructure.FilterUtils.generateUuidPool;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetItemServiceImpl.class)
public interface DatasetItemService {

    Mono<Long> saveBatch(UUID datasetId, List<DatasetItem> items);

    Mono<Void> createFromTraces(UUID datasetId, Set<UUID> traceIds, TraceEnrichmentOptions enrichmentOptions,
            List<EvaluatorItem> evaluators, ExecutionPolicy executionPolicy);

    Mono<Void> createFromSpans(UUID datasetId, Set<UUID> spanIds,
            SpanEnrichmentOptions enrichmentOptions, List<EvaluatorItem> evaluators,
            ExecutionPolicy executionPolicy);

    Mono<DatasetItem> get(UUID id);

    Mono<DatasetItem> get(UUID id, UUID datasetVersionId);

    Mono<Void> patch(UUID id, DatasetItem item);

    Mono<Void> batchUpdate(DatasetItemBatchUpdate batchUpdate);

    Mono<Void> delete(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters, UUID batchGroupId);

    Mono<DatasetItemPage> getItems(int page, int size, DatasetItemSearchCriteria datasetItemSearchCriteria);

    Flux<DatasetItem> getItems(DatasetItemStreamRequest request, List<DatasetItemFilter> filters);

    Mono<PageColumns> getOutputColumns(UUID datasetId, Set<UUID> experimentIds);

    Mono<ProjectStats> getExperimentItemsStats(UUID datasetId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters);

    /**
     * 对数据集版本应用增量变更，创建包含变更的新版本。
     * <p>
     * 此操作：
     * <ul>
     *   <li>验证 baseVersion 存在且属于该数据集</li>
     *   <li>检查 baseVersion 是否等于最新版本（除非 override 为 true）</li>
     *   <li>从 baseVersion 获取条目</li>
     *   <li>应用增量：添加新条目、更新已编辑条目、移除已删除条目</li>
     *   <li>使用提供的元数据创建新版本记录</li>
     *   <li>更新 'latest' 标签指向新版本</li>
     * </ul>
     *
     * @param datasetId 数据集 ID
     * @param changes 要应用的增量变更（添加、编辑、删除的条目及元数据）
     * @param override 如果为 true，即使 baseVersion 已过期也强制创建版本
     * @return 发出新创建版本的 Mono
     * @throws NotFoundException 如果数据集或 baseVersion 未找到
     * @throws ClientErrorException 如果 baseVersion 已过期且 override 为 false，返回 409 状态
     */
    Mono<DatasetVersion> applyDeltaChanges(UUID datasetId, DatasetItemChanges changes, boolean override);

    /**
     * 保存数据集条目，根据配置路由到版本化或传统存储。
     * <p>
     * 当数据集版本控制启用时：
     * <ul>
     *   <li>从批次解析数据集 ID（如需要则创建数据集）</li>
     *   <li>如果 batchGroupId 为空：通过追加条目修改最新版本（向后兼容）</li>
     *   <li>如果提供了 batchGroupId：使用批次分组创建新版本（多个批次可共享同一版本）</li>
     *   <li>如果不存在版本，则忽略 batchGroupId 创建第一个版本</li>
     *   <li>返回 DatasetVersion（新创建或已修改的）</li>
     * </ul>
     * 当版本控制禁用时（传统模式）：
     * <ul>
     *   <li>将条目保存到传统 dataset_items 表</li>
     *   <li>返回空 Mono</li>
     * </ul>
     *
     * @param batch 要保存的条目批次（必须包含 datasetId 或 datasetName，可包含 batchGroupId）
     * @return 当版本控制启用时发出 DatasetVersion 的 Mono，禁用时为空
     */
    Mono<DatasetVersion> save(DatasetItemBatch batch);

}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemServiceImpl implements DatasetItemService {

    private static final String DATASET_VERSION_LOCK = DatasetVersionService.DATASET_VERSION_LOCK;

    private final @NonNull DatasetItemDAO dao;
    private final @NonNull DatasetItemVersionDAO versionDao;
    private final @NonNull DatasetService datasetService;
    private final @NonNull DatasetVersionService versionService;
    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull TraceEnrichmentService traceEnrichmentService;
    private final @NonNull SpanEnrichmentService spanEnrichmentService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull TransactionTemplate template;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull DatasetVersioningMigrationService migrationService;
    private final @NonNull ProjectService projectService;
    private final @NonNull @Config OpikConfiguration config;
    private final @NonNull LockService lockService;

    // 按数据集串行化 read-latest -> create-version -> flip-latest 序列，使并行
    // 上传无法竞争数据集的 'latest' 可变指针（OPIK-7264）。
    private <T> Mono<T> withDatasetVersionLock(UUID datasetId, Mono<T> action) {
        Duration lockLease = config.getDatasetVersioning().lockLease().toJavaDuration();
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(datasetId, DATASET_VERSION_LOCK), action, lockLease);
    }

    @WithSpan
    private Mono<Void> verifyDatasetExistsAndSave(@NonNull DatasetItemBatch batch) {
        if (batch.datasetId() == null && batch.datasetName() == null) {
            return Mono.error(failWithError("dataset_id or dataset_name must be provided"));
        }

        return getDatasetId(batch)
                .flatMap(it -> saveBatch(batch, it))
                .then();
    }

    @Override
    @WithSpan
    public Mono<Void> createFromTraces(
            @NonNull UUID datasetId,
            @NonNull Set<UUID> traceIds,
            @NonNull TraceEnrichmentOptions enrichmentOptions,
            List<EvaluatorItem> evaluators,
            ExecutionPolicy executionPolicy) {

        log.info("从 '{}' 条 trace 为数据集 '{}' 创建数据集条目", traceIds.size(), datasetId);

        traceIds.forEach(traceId -> idGenerator.validateIdNotInFuture(traceId, "dataset_item trace"));

        // 验证数据集是否存在
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DatasetDAO.class);
                return dao.findById(datasetId, workspaceId)
                        .orElseThrow(() -> new NotFoundException("Dataset not found: '%s'".formatted(datasetId)));
            })).subscribeOn(Schedulers.boundedElastic());
        }).flatMap(dataset -> {
            // 使用元数据丰富 trace
            return traceEnrichmentService.enrichTraces(traceIds, enrichmentOptions)
                    .flatMap(enrichedTraces -> {
                        // 将丰富的 trace 转换为数据集条目
                        List<DatasetItem> datasetItems = enrichedTraces.entrySet().stream()
                                .map(entry -> DatasetItem.builder()
                                        .id(idGenerator.generateId())
                                        .source(DatasetItemSource.TRACE)
                                        .traceId(entry.getKey())
                                        .data(filterDataForDatasetType(entry.getValue(), dataset.type()))
                                        .evaluators(evaluators)
                                        .executionPolicy(executionPolicy)
                                        .build())
                                .toList();

                        // 保存数据集条目 - 根据开关路由到版本化或传统存储
                        if (featureFlags.isDatasetVersioningEnabled()) {
                            log.info("为数据集 '{}' 从 trace 创建数据集条目（版本化）", datasetId);
                            return withDatasetVersionLock(datasetId, saveItemsWithVersion(
                                    DatasetItemBatch.builder().datasetId(datasetId).items(datasetItems).build(),
                                    datasetId, null)
                                    .then(Mono.just(0L)));
                        }

                        // 传统方式：保存到传统表
                        DatasetItemBatch batch = DatasetItemBatch.builder().datasetId(datasetId).items(datasetItems)
                                .build();
                        return saveBatch(batch, datasetId);
                    });
        }).then();
    }

    @Override
    @WithSpan
    public Mono<Void> createFromSpans(
            @NonNull UUID datasetId,
            @NonNull Set<UUID> spanIds,
            @NonNull SpanEnrichmentOptions enrichmentOptions,
            List<EvaluatorItem> evaluators,
            ExecutionPolicy executionPolicy) {

        log.info("从 '{}' 条 span 为数据集 '{}' 创建数据集条目", spanIds.size(), datasetId);

        spanIds.forEach(spanId -> idGenerator.validateIdNotInFuture(spanId, "dataset_item span"));

        // 验证数据集是否存在
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(DatasetDAO.class);
                return dao.findById(datasetId, workspaceId)
                        .orElseThrow(() -> new NotFoundException("Dataset not found: '%s'".formatted(datasetId)));
            })).subscribeOn(Schedulers.boundedElastic());
        }).flatMap(dataset -> {
            // 使用元数据丰富 span
            return spanEnrichmentService.enrichSpans(spanIds, enrichmentOptions)
                    .flatMap(enrichedSpans -> {
                        // 将丰富的 span 转换为数据集条目
                        List<DatasetItem> datasetItems = enrichedSpans.entrySet().stream()
                                .map(entry -> DatasetItem.builder()
                                        .id(idGenerator.generateId())
                                        .source(DatasetItemSource.SPAN)
                                        .spanId(entry.getKey())
                                        .data(filterDataForDatasetType(entry.getValue(), dataset.type()))
                                        .evaluators(evaluators)
                                        .executionPolicy(executionPolicy)
                                        .build())
                                .toList();

                        // 保存数据集条目 - 根据开关路由到版本化或传统存储
                        if (featureFlags.isDatasetVersioningEnabled()) {
                            log.info("为数据集 '{}' 从 span 创建数据集条目（版本化）", datasetId);
                            return withDatasetVersionLock(datasetId, saveItemsWithVersion(
                                    DatasetItemBatch.builder().datasetId(datasetId).items(datasetItems).build(),
                                    datasetId, null)
                                    .then(Mono.just(0L)));
                        }

                        // 传统方式：保存到传统表
                        DatasetItemBatch batch = DatasetItemBatch.builder().datasetId(datasetId).items(datasetItems)
                                .build();
                        return saveBatch(batch, datasetId);
                    });
        }).then();
    }

    Map<String, JsonNode> filterDataForDatasetType(
            Map<String, JsonNode> data, DatasetType datasetType) {
        if (datasetType != DatasetType.TEST_SUITE) {
            return data;
        }

        // 对于测试套件，仅使用 input 值作为顶层数据
        var inputNode = data.get("input");
        if (inputNode == null || inputNode.isNull()) {
            return Map.of();
        }

        // 对象输入：将字段展开为顶层键（现有行为）
        if (inputNode.isObject()) {
            return JsonUtils.convertValue(inputNode, new TypeReference<Map<String, JsonNode>>() {
            });
        }

        // 非对象输入（数组、字符串、数字、布尔值）：包装在 "input" 键下
        return Map.of("input", inputNode);
    }

    private Mono<UUID> resolveProjectId(DatasetItemBatch batch) {
        return Mono.deferContextual(ctx -> Mono.fromCallable(() -> Optional.ofNullable(batch.projectId())
                .map(projectId -> projectService.get(projectId, ctx.get(RequestContext.WORKSPACE_ID)))
                .or(() -> Optional.ofNullable(batch.projectName())
                        .map(projectName -> projectService.getOrCreate(ctx.get(RequestContext.WORKSPACE_ID),
                                projectName, ctx.get(RequestContext.USER_NAME))))
                .map(Project::id)
                .orElse(null)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<UUID> getDatasetId(DatasetItemBatch batch) {
        return resolveProjectId(batch)
                .flatMap(projectId -> getDatasetIdForProject(batch, projectId))
                .switchIfEmpty(Mono.defer(() -> getDatasetIdForProject(batch, null)));
    }

    private Mono<UUID> getDatasetIdForProject(DatasetItemBatch batch, UUID projectId) {
        if (batch.datasetId() == null) {
            return datasetService.getOrCreateDataset(batch.datasetName(), projectId);
        }

        return Mono.deferContextual(ctx -> Mono.fromCallable(() -> {

            Dataset dataset = datasetService.findById(batch.datasetId(),
                    ctx.get(RequestContext.WORKSPACE_ID), ctx.get(RequestContext.VISIBILITY));

            if (dataset == null) {
                throw newConflict(
                        "workspace_name from dataset item batch and dataset_id from item does not match");
            }

            return dataset.id();
        })).subscribeOn(Schedulers.boundedElastic());
    }

    private Throwable failWithError(String error) {
        log.info(error);
        return new ClientErrorException(Response.status(422).entity(new ErrorMessage(List.of(error))).build());
    }

    private ClientErrorException newConflict(String error) {
        log.info(error);
        return new ClientErrorException(Response.status(409).entity(new ErrorMessage(List.of(error))).build());
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> get(@NonNull UUID id) {
        if (featureFlags.isDatasetVersioningEnabled()) {
            return authorizeItem(versionDao.getItemById(id));
        }
        return authorizeItem(dao.get(id));
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> get(@NonNull UUID id, UUID datasetVersionId) {
        if (featureFlags.isDatasetVersioningEnabled()) {
            return authorizeItem(versionDao.getItemById(id, datasetVersionId));
        }
        return authorizeItem(dao.get(id));
    }

    private Mono<DatasetItem> authorizeItem(Mono<DatasetItem> itemMono) {
        return itemMono
                .flatMap(item -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    Visibility visibility = ctx.get(RequestContext.VISIBILITY);
                    datasetService.findById(item.datasetId(), workspaceId, visibility);
                    return Mono.just(item);
                }))
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Dataset item not found"))));
    }

    @Override
    @WithSpan
    public Mono<Void> patch(@NonNull UUID id, @NonNull DatasetItem item) {
        validateReferencedTraceAndSpan(item);
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (featureFlags.isDatasetVersioningEnabled()) {
                log.info("版本化修补条目 '{}'", id);
                return patchItemWithVersion(id, item, workspaceId, userName);
            }

            // 传统模式：从草稿表获取并更新
            return get(id)
                    .flatMap(existingItem -> {
                        // 通过合并提供的字段与现有条目构建修补后的条目
                        var builder = existingItem.toBuilder();
                        applyPatchFields(builder, item);
                        DatasetItem patchedItem = builder.build();

                        log.info("在传统表中修补条目 '{}'（数据集 '{}'）",
                                id, existingItem.datasetId());
                        DatasetItemBatch batch = DatasetItemBatch.builder()
                                .datasetId(existingItem.datasetId())
                                .items(List.of(patchedItem))
                                .build();
                        return saveBatch(batch, existingItem.datasetId());
                    });
        }).then();
    }

    private Mono<Long> patchItemWithVersion(UUID datasetItemId, DatasetItem patchData,
            String workspaceId, String userName) {
        // 解析哪个数据集包含此条目
        return versionDao.resolveDatasetIdFromItemId(datasetItemId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("条目 '{}' 未在版本化表中找到", datasetItemId);
                    return Mono.error(failWithNotFound("Dataset item not found"));
                }))
                .flatMap(datasetId -> {
                    // 如果启用了延迟迁移，确保数据集已迁移
                    return ensureLazyMigration(datasetId, workspaceId)
                            .thenReturn(datasetId);
                })
                .flatMap(datasetId -> withDatasetVersionLock(datasetId, Mono.defer(() -> {
                    // 获取最新版本（使用接受 workspaceId 的重载方法）
                    Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

                    if (latestVersion.isEmpty()) {
                        log.info("数据集 '{}' 不存在任何版本，无法在版本化模式下修补", datasetId);
                        return Mono.error(failWithNotFound("No versions exist for dataset"));
                    }

                    UUID baseVersionId = latestVersion.get().id();
                    UUID newVersionId = idGenerator.generateId();

                    // 从最新版本获取现有条目
                    return versionDao.getItemByDatasetItemId(datasetId, baseVersionId, datasetItemId)
                            .switchIfEmpty(Mono.defer(() -> {
                                log.warn("条目 '{}' 未在数据集 '{}' 的版本 '{}' 中找到",
                                        datasetItemId, datasetId, baseVersionId);
                                return Mono.error(failWithNotFound("Dataset item not found"));
                            }))
                            .flatMap(existingItem -> {
                                // 将补丁应用到现有条目
                                DatasetItem patchedItem = applyPatchToItem(existingItem, patchData, userName);

                                log.info("为数据集 '{}' 创建包含单条目编辑的版本，baseVersion='{}'",
                                        datasetId, baseVersionId);

                                DatasetItem patchedItemWithId = patchedItem.toBuilder()
                                        .id(existingItem.id()) // 保留原始行 ID
                                        .build();

                                return applyEditDeleteWithLiveCount(datasetId, baseVersionId, newVersionId,
                                        List.of(patchedItemWithId), Set.of(), workspaceId)
                                        .flatMap(itemsTotal -> {
                                            log.info("已向数据集 '{}' 应用补丁增量：itemsTotal '{}'",
                                                    datasetId, itemsTotal);

                                            // 创建版本元数据
                                            return createVersionFromDelta(
                                                    datasetId,
                                                    newVersionId,
                                                    itemsTotal.intValue(),
                                                    baseVersionId,
                                                    null, // 无标签
                                                    "Updated 1 item",
                                                    null, // 从基础版本继承评估器
                                                    null, // 从基础版本继承执行策略
                                                    false, // 不清除执行策略
                                                    null, // 无批次组 ID
                                                    true, // enforceLatestCas: 在锁保护下与当前最新版本进行差异比较
                                                    workspaceId,
                                                    userName)
                                                    .thenReturn(itemsTotal);
                                        });
                            });
                })));
    }

    /**
     * 将 patchData 中的补丁字段应用到构建器。
     * 仅应用 patchData 中的非空字段。
     */
    private void applyPatchFields(DatasetItem.DatasetItemBuilder builder, DatasetItem patchData) {
        Optional.ofNullable(patchData.data()).ifPresent(builder::data);
        Optional.ofNullable(patchData.source()).ifPresent(builder::source);
        Optional.ofNullable(patchData.traceId()).ifPresent(builder::traceId);
        Optional.ofNullable(patchData.spanId()).ifPresent(builder::spanId);
        Optional.ofNullable(patchData.tags()).ifPresent(builder::tags);
    }

    /**
     * 将补丁数据应用到条目，返回包含变更的新 DatasetItem。
     */
    private DatasetItem applyPatchToItem(DatasetItem existingItem, DatasetItem patchData, String userName) {
        var builder = existingItem.toBuilder()
                .lastUpdatedAt(java.time.Instant.now())
                .lastUpdatedBy(userName);

        applyPatchFields(builder, patchData);

        return builder.build();
    }

    @WithSpan
    public Mono<Void> batchUpdate(@NonNull DatasetItemBatchUpdate batchUpdate) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (featureFlags.isDatasetVersioningEnabled()) {
                log.info("版本化批量更新条目，idsSize='{}'、filtersSize='{}'",
                        batchUpdate.ids() != null ? batchUpdate.ids().size() : 0,
                        batchUpdate.filters() != null ? batchUpdate.filters().size() : 0);
                return batchUpdateWithVersion(batchUpdate, workspaceId, userName);
            }

            // 传统方式：在传统表中批量更新
            log.info("在传统表中批量更新条目，idsSize='{}'、filtersSize='{}'",
                    batchUpdate.ids() != null ? batchUpdate.ids().size() : 0,
                    batchUpdate.filters() != null ? batchUpdate.filters().size() : 0);
            return dao.bulkUpdate(batchUpdate.ids(), batchUpdate.datasetId(), batchUpdate.filters(),
                    batchUpdate.update(),
                    batchUpdate.mergeTags());
        }).onErrorResume(TagOperations::mapTagLimitError);
    }

    /**
     * 批量更新条目并创建包含编辑的新版本。
     * <p>
     * 此操作在数据集版本控制启用时使用。
     * 它从最新版本获取匹配条件的条目，应用更新，并创建包含已编辑条目的新版本。
     */
    private Mono<Void> batchUpdateWithVersion(DatasetItemBatchUpdate batchUpdate, String workspaceId, String userName) {
        // 确定数据集 ID
        UUID datasetId;

        if (batchUpdate.datasetId() != null) {
            // 使用显式提供的数据集 ID（基于过滤器的更新必须提供）
            datasetId = batchUpdate.datasetId();
            log.info("使用提供的数据集 ID '{}' 进行按过滤器的版本化批量更新", datasetId);

            return withDatasetVersionLock(datasetId,
                    batchUpdateByFiltersWithVersioning(datasetId, batchUpdate, workspaceId, userName));
        }

        if (CollectionUtils.isNotEmpty(batchUpdate.ids())) {
            return resolveDatasetIdFromItemIds(batchUpdate.ids())
                    .switchIfEmpty(Mono.error(failWithNotFound("Dataset items not found")))
                    .flatMap(resolvedDatasetId -> withDatasetVersionLock(resolvedDatasetId,
                            batchUpdateByIdsWithVersioning(resolvedDatasetId, batchUpdate, workspaceId, userName)));
        }

        // 由于验证逻辑，此情况不应发生，但做优雅处理
        log.error("版本化批量更新需要提供条目 ID 或带过滤器的数据集 ID");
        return Mono.error(new BadRequestException(
                "Batch update requires either item IDs or dataset ID with filters"));
    }

    /**
     * 按 ID 批量更新条目并创建新版本。
     */
    private Mono<Void> batchUpdateByIdsWithVersioning(UUID datasetId, DatasetItemBatchUpdate batchUpdate,
            String workspaceId, String userName) {
        int updateSize = batchUpdate.ids().size();
        log.info("按 ID 为数据集 '{}' 版本化批量更新条目（count='{}'）", datasetId, updateSize);

        return runVersionedBatchUpdate(datasetId, workspaceId, userName, latestVersion -> {
            UUID baseVersionId = latestVersion.id();
            UUID newVersionId = idGenerator.generateId();

            // 用于更新条目 INSERT...SELECT 的 UUID。先生成以便它们
            // 排在未更改条目 UUID（在下方生成）之前。
            List<UUID> updateUuids = generateUuidPool(idGenerator, updateSize);

            // 执行批量更新
            return versionDao
                    .batchUpdateItems(datasetId, baseVersionId, newVersionId, batchUpdate, updateUuids)
                    .flatMap(updatedCount -> {
                        if (updatedCount == 0) {
                            log.info("未找到数据集 '{}' 需要更新的条目", datasetId);
                            return Mono.empty();
                        }

                        log.info("已按 ID 为数据集 '{}' 批量更新条目（count='{}'、baseVersion='{}'）",
                                datasetId, updatedCount, baseVersionId);

                        // OPIK-6390: 将刚更新的 ID 作为 applyDelta 的"已删除"槽位传入，
                        // 使它们从未更改条目的复制中排除。
                        // 辅助方法根据实时 ClickHouse 计数确定 UUID 池大小。
                        return applyEditDeleteWithLiveCount(datasetId, baseVersionId,
                                newVersionId, List.of(), batchUpdate.ids(), workspaceId)
                                .flatMap(unchangedCount -> createVersionMetadata(
                                        datasetId, newVersionId, baseVersionId,
                                        updatedCount, unchangedCount, false,
                                        workspaceId, userName));
                    });
        });
    }

    /**
     * 版本化批量更新路径的共享脚手架：延迟执行，直到调用方持有
     * 按数据集锁（OPIK-7264），确保延迟迁移，读取最新版本，并在请求上下文中
     * 针对该版本运行 {@code body}。只有路径特定工作（按 ID 与按过滤器）不同，
     * 并作为 {@code body} 提供。
     */
    private Mono<Void> runVersionedBatchUpdate(UUID datasetId, String workspaceId, String userName,
            Function<DatasetVersion, Mono<Void>> body) {
        return Mono.defer(() -> ensureLazyMigration(datasetId, workspaceId)
                .then(Mono.defer(() -> getLatestVersionOrError(datasetId, workspaceId).flatMap(body)))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .then());
    }

    /**
     * 按过滤器批量更新条目并创建新版本。
     */
    private Mono<Void> batchUpdateByFiltersWithVersioning(UUID datasetId, DatasetItemBatchUpdate batchUpdate,
            String workspaceId, String userName) {
        log.info("按过滤器为数据集 '{}' 版本化批量更新条目", datasetId);

        return runVersionedBatchUpdate(datasetId, workspaceId, userName, latestVersion -> {
            UUID baseVersionId = latestVersion.id();
            UUID newVersionId = idGenerator.generateId();

            // OPIK-6390: 从基础版本的实时 ClickHouse 计数中调整两个 UUID 池的大小，
            // 而非使用（易漂移的）MySQL items_total。精确的基础计数是匹配（更新）
            // 和不匹配（复制）行数的上限，因此不需要余量乘数；
            // COPY_VERSION_ITEMS 中的防御性 arrayElement 回退
            // 可以覆盖任何残余的不匹配而不会丢失数据。
            return versionDao
                    .countRowsInVersion(datasetId, baseVersionId, Set.of(), null, workspaceId)
                    .flatMap(baseRowCount -> {
                        int poolSize = baseRowCount.intValue();
                        List<UUID> updateUuids = generateUuidPool(idGenerator, poolSize);
                        // 全选（空过滤器）不会复制未更改的行，因此跳过
                        // 其复制池分配。
                        boolean selectAll = batchUpdate.filters() != null && batchUpdate.filters().isEmpty();
                        List<UUID> copyUuids = selectAll ? List.of() : generateUuidPool(idGenerator, poolSize);

                        log.debug(
                                "为基于过滤器的更新生成独立的 UUID 池：updateSize='{}'、copySize='{}'",
                                updateUuids.size(), copyUuids.size());

                        // 执行批量更新
                        return versionDao
                                .batchUpdateItems(datasetId, baseVersionId, newVersionId, batchUpdate, updateUuids)
                                .flatMap(updatedCount -> {
                                    if (updatedCount == 0) {
                                        log.info("未找到数据集 '{}' 需要更新的条目", datasetId);
                                        return Mono.empty();
                                    }

                                    log.info(
                                            "已按过滤器为数据集 '{}' 批量更新条目（count='{}'、baseVersion='{}'）",
                                            datasetId, updatedCount, baseVersionId);

                                    // 复制未更改的条目（不匹配过滤器的条目）
                                    // 特殊情况：空过滤器列表表示"全选" - 没有未更改的条目需要复制
                                    if (selectAll) {
                                        // 空过滤器表示所有条目都已更新 - 无需复制
                                        log.info("空过滤器（全选）- 跳过复制未更改的条目");
                                        return createVersionMetadata(
                                                datasetId, newVersionId, baseVersionId,
                                                updatedCount, 0L, true,
                                                workspaceId, userName);
                                    }

                                    // 使用 copyVersionItems 复制未更改的条目（排除匹配过滤器的）
                                    return versionDao
                                            .copyVersionItems(datasetId, baseVersionId,
                                                    datasetId, newVersionId,
                                                    batchUpdate.filters(), copyUuids)
                                            .flatMap(unchangedCount -> createVersionMetadata(
                                                    datasetId, newVersionId, baseVersionId,
                                                    updatedCount, unchangedCount, true,
                                                    workspaceId, userName));
                                });
                    });
        });
    }

    /**
     * 共享辅助方法：获取最新版本，如果不存在则返回错误。
     */
    private Mono<DatasetVersion> getLatestVersionOrError(UUID datasetId, String workspaceId) {
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

        if (latestVersion.isEmpty()) {
            log.error("数据集 '{}' 不存在任何版本", datasetId);
            return Mono.error(failWithNotFound("No versions exist for dataset"));
        }

        return Mono.just(latestVersion.get());
    }

    /**
     * 共享辅助方法：在成功的批量更新后创建版本元数据。
     */
    private Mono<Void> createVersionMetadata(UUID datasetId, UUID newVersionId, UUID baseVersionId,
            long updatedCount, long unchangedCount, boolean isFilterBased,
            String workspaceId, String userName) {

        long itemsTotal = updatedCount + unchangedCount;
        log.info("已向数据集 '{}' 应用批量更新增量：updated='{}'、unchanged='{}'、total='{}'、type='{}'",
                datasetId, updatedCount, unchangedCount, itemsTotal, isFilterBased ? "filter" : "ids");

        // 创建版本元数据
        String changeDescription = createChangeDescription(updatedCount, isFilterBased);

        return createVersionFromDelta(
                datasetId,
                newVersionId,
                (int) itemsTotal,
                baseVersionId,
                null, // 无标签
                changeDescription,
                null, // 从基础版本继承评估器
                null, // 从基础版本继承执行策略
                false, // 不清除执行策略
                null, // 无批次组 ID
                true, // enforceLatestCas: 在锁保护下与当前最新版本进行差异比较
                workspaceId,
                userName)
                .then();
    }

    /**
     * 为版本元数据创建人类可读的变更描述。
     */
    private String createChangeDescription(long count, boolean isFilterBased) {
        if (count == 1) {
            return isFilterBased ? "Updated 1 item by filters" : "Updated 1 item";
        }
        return isFilterBased
                ? "Updated " + count + " items by filters"
                : "Updated " + count + " items";
    }

    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull DatasetItemStreamRequest request,
            @NonNull List<DatasetItemFilter> filters) {
        return Flux.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            log.info("获取数据集 '{}' 的数据集条目（hasFilters={}）、version='{}'、workspaceId='{}'",
                    request.datasetName(), !filters.isEmpty(),
                    request.datasetVersion(), workspaceId);

            return datasetService.resolveDatasetByNameAsync(
                    DatasetIdentifier.builder()
                            .datasetName(request.datasetName())
                            .projectName(request.projectName())
                            .build())
                    .flatMap(dataset -> Mono.deferContextual(ctx2 -> {
                        return ensureLazyMigration(dataset.id(), workspaceId)
                                .thenReturn(dataset);
                    }))
                    .flatMapMany(dataset -> {
                        String versionHashOrTag = request.datasetVersion();

                        if (versionHashOrTag != null && !versionHashOrTag.isBlank()) {
                            log.info("使用显式提供的版本 '{}' 流式获取数据集 '{}' 的条目",
                                    versionHashOrTag, dataset.id());
                            return Mono.fromCallable(() -> versionService.resolveVersionId(workspaceId, dataset.id(),
                                    versionHashOrTag))
                                    .flatMapMany(versionId -> versionDao.getItems(dataset.id(), versionId,
                                            request.steamLimit(), request.lastRetrievedId(), filters));
                        }

                        if (featureFlags.isDatasetVersioningEnabled()) {
                            log.info("功能开关已开启，使用最新版本流式获取数据集 '{}' 的条目",
                                    dataset.id());
                            return Mono
                                    .fromCallable(() -> versionService.getLatestVersionId(dataset.id(), workspaceId))
                                    .flatMapMany(latestVersionIdOpt -> {
                                        if (latestVersionIdOpt.isPresent()) {
                                            UUID versionId = latestVersionIdOpt.get();
                                            log.info("从最新版本 '{}' 流式获取数据集 '{}' 的条目", versionId,
                                                    dataset.id());
                                            return versionDao.getItems(dataset.id(), versionId, request.steamLimit(),
                                                    request.lastRetrievedId(), filters);
                                        } else {
                                            log.warn("数据集 '{}' 不存在任何版本，返回空流",
                                                    dataset.id());
                                            return Flux.empty();
                                        }
                                    });
                        }

                        log.info("功能开关已关闭，使用传统表流式获取数据集 '{}' 的条目",
                                dataset.id());
                        return dao.getItems(dataset.id(), request.steamLimit(), request.lastRetrievedId(), filters);
                    });
        });
    }

    @Override
    public Mono<PageColumns> getOutputColumns(@NonNull UUID datasetId, Set<UUID> experimentIds) {
        if (featureFlags.isDatasetVersioningEnabled()) {
            log.info("版本化获取数据集 '{}' 的输出列，experimentIds '{}'", datasetId,
                    experimentIds);

            return versionDao.getExperimentItemsOutputColumns(datasetId, experimentIds)
                    .map(columns -> PageColumns.builder().columns(columns).build())
                    .switchIfEmpty(Mono.just(PageColumns.empty()));
        }

        // 版本控制开关关闭：使用传统表
        return dao.getOutputColumns(datasetId, experimentIds)
                .map(columns -> PageColumns.builder().columns(columns).build())
                .switchIfEmpty(Mono.just(PageColumns.empty()));
    }

    @Override
    @WithSpan
    public Mono<Long> saveBatch(@NonNull UUID datasetId, @NonNull List<DatasetItem> items) {
        if (items.isEmpty()) {
            return Mono.just(0L);
        }

        // 创建包含条目的批次
        DatasetItemBatch batch = DatasetItemBatch.builder().datasetId(datasetId).items(items).build();

        // 根据开关路由到版本化或传统存储
        if (featureFlags.isDatasetVersioningEnabled()) {
            log.info("为数据集 '{}' 版本化保存批次，itemCount '{}'", datasetId, items.size());
            // 与 saveItemsWithVersion 的其他调用方不同，此公共重载没有先前的
            // 数据集存在性读取，因此在此（锁内）进行保护，以便在数据集缺失时快速失败。
            // 修改最新版本而非每个批次新建一个：多批次上传
            // （例如拆分为块的大型 CSV/JSON 文件）必须落在单一版本中，符合
            // 数组 API 通过 save(DatasetItemBatch) 遵循的 null-batch_group_id 约定。
            return withDatasetVersionLock(datasetId, Mono.deferContextual(ctx -> {
                datasetService.findById(datasetId, ctx.get(RequestContext.WORKSPACE_ID), null);
                return mutateLatestVersionWithInsert(batch, datasetId,
                        ctx.get(RequestContext.WORKSPACE_ID), ctx.get(RequestContext.USER_NAME));
            })
                    .map(version -> (long) items.size())
                    .defaultIfEmpty((long) items.size()));
        }

        // 传统方式：保存到传统表
        return saveBatch(batch, datasetId);
    }

    private Mono<Long> saveBatch(DatasetItemBatch batch, UUID id) {
        if (batch.items().isEmpty()) {
            return Mono.empty();
        }

        List<DatasetItem> items = addIdIfAbsent(batch);

        return Mono.deferContextual(ctx -> {

            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return validateSpans(workspaceId, items)
                    .then(Mono.defer(() -> validateTraces(workspaceId, items)))
                    .then(Mono.defer(() -> dao.save(id, items)));
        });
    }

    private Mono<Void> validateSpans(String workspaceId, List<DatasetItem> items) {
        Set<UUID> spanIds = items.stream()
                .map(DatasetItem::spanId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return spanService.validateSpanWorkspace(workspaceId, spanIds)
                .flatMap(valid -> {
                    if (Boolean.FALSE.equals(valid)) {
                        return failWithConflict("span workspace and dataset item workspace does not match");
                    }

                    return Mono.empty();
                });
    }

    private Mono<Boolean> validateTraces(String workspaceId, List<DatasetItem> items) {
        Set<UUID> traceIds = items.stream()
                .map(DatasetItem::traceId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return traceService.validateTraceWorkspace(workspaceId, traceIds)
                .flatMap(valid -> {
                    if (Boolean.FALSE.equals(valid)) {
                        return failWithConflict("trace workspace and dataset item workspace does not match");
                    }

                    return Mono.empty();
                });
    }

    private List<DatasetItem> addIdIfAbsent(DatasetItemBatch batch) {
        return batch.items()
                .stream()
                .map(item -> {
                    IdGenerator.validateVersion(item.id(), "dataset_item");
                    validateReferencedTraceAndSpan(item);
                    return item;
                })
                .toList();
    }

    // dataset_item 引用的 trace_id / span_id 必须是时间有序的 UUIDv7（允许过去时间：
    // 条目通常链接到更早的 trace/span）。复用共享的引用 ID 策略。
    private void validateReferencedTraceAndSpan(DatasetItem item) {
        if (item.traceId() != null) {
            idGenerator.validateIdNotInFuture(item.traceId(), "dataset_item trace");
        }
        if (item.spanId() != null) {
            idGenerator.validateIdNotInFuture(item.spanId(), "dataset_item span");
        }
    }

    private <T> Mono<T> failWithConflict(String message) {
        log.info(message);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(message))));
    }

    private NotFoundException failWithNotFound(String message) {
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    @Override
    @WithSpan
    public Mono<Void> delete(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters, UUID batchGroupId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (!featureFlags.isDatasetVersioningEnabled()) {
                // 传统方式：从传统表删除
                log.info("从传统表删除条目。datasetId='{}'、itemIdsSize='{}'、filtersSize='{}'",
                        datasetId, ids != null ? ids.size() : 0, filters != null ? filters.size() : 0);
                return dao.delete(ids, datasetId, filters).then();
            }

            if (batchGroupId == null) {
                // 没有 batch_group_id：修改最新版本（向后兼容）
                log.info(
                        "使用删除修改最新版本（无 batch_group_id）。datasetId='{}'、itemIdsSize='{}'、filtersSize='{}'",
                        datasetId, ids != null ? ids.size() : 0, filters != null ? filters.size() : 0);
                return getDatasetIdOrResolveItemDatasetId(datasetId, ids)
                        .flatMap(resolvedDatasetId -> withDatasetVersionLock(resolvedDatasetId,
                                deleteItemsWithVersion(ids, resolvedDatasetId, filters, workspaceId, userName, null)));
            }

            // 提供了 batch_group_id：使用批次分组创建新版本
            log.info(
                    "使用批次分组创建删除版本。batchGroupId='{}'、datasetId='{}'、itemIdsSize='{}'、filtersSize='{}'",
                    batchGroupId, datasetId, ids != null ? ids.size() : 0, filters != null ? filters.size() : 0);
            return getDatasetIdOrResolveItemDatasetId(datasetId, ids)
                    .flatMap(resolvedDatasetId -> withDatasetVersionLock(resolvedDatasetId, handleGroupedDeletion(
                            batchGroupId, ids, resolvedDatasetId, filters, workspaceId, userName)));
        });
    }

    /**
     * 删除条目并创建不包含已删除条目的新版本。
     * <p>
     * 此操作在数据集版本控制启用时使用。不是从传统表中删除，
     * 而是创建排除条目的新版本。
     * <ul>
     *   <li>解析数据集 ID（从提供的 datasetId 或通过查询条目）</li>
     *   <li>获取数据集的最新版本</li>
     *   <li>如果不存在版本，则回退到传统删除</li>
     *   <li>否则，应用包含删除的增量以创建新版本</li>
     * </ul>
     */
    private Mono<Void> deleteItemsWithVersion(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters,
            String workspaceId, String userName, UUID batchGroupId) {
        // 延迟执行，直到调用方持有 withDatasetVersionLock 锁（OPIK-7264）。
        return Mono.defer(() -> {
            // 情况 1：按条目 ID 删除
            if (CollectionUtils.isNotEmpty(ids)) {
                return deleteByItemIdsWithVersion(ids, datasetId, workspaceId, userName, batchGroupId);
            }

            // 情况 2：按 datasetId 和过滤器删除
            if (datasetId != null) {
                return deleteByDatasetIdWithVersion(datasetId, filters, workspaceId, userName, batchGroupId);
            }

            // 无有效输入
            return Mono.empty();
        });
    }

    /**
     * 按 datasetId 和可选过滤器删除条目，创建新版本。
     * <p>
     * 使用高效的基于 SQL 的方法，将不匹配过滤器的条目复制到新版本，
     * 避免将所有条目 ID 加载到内存中。
     */
    private Mono<Void> deleteByDatasetIdWithVersion(UUID datasetId, List<DatasetItemFilter> filters,
            String workspaceId, String userName, UUID batchGroupId) {
        // 从 batchGroupId 派生 createVersion：null 表示修改最新版本，非 null 表示创建新版本
        boolean createVersion = batchGroupId != null;
        log.info(
                "按 datasetId '{}' 版本化删除条目，filtersSize='{}'、batchGroupId='{}'、createVersion='{}'",
                datasetId, filters != null ? filters.size() : 0, batchGroupId, createVersion);

        // 延迟数据集存在性读取及其后的一切操作，使其在调用方的
        // 按数据集锁下运行，而非在组装时运行（OPIK-7264）。
        return Mono.defer(() -> {
            // 验证数据集是否存在
            datasetService.findById(datasetId, workspaceId, null);

            // 如果启用了延迟迁移，确保数据集已迁移
            return ensureLazyMigration(datasetId, workspaceId);
        })
                .then(Mono.defer(() -> {
                    // 获取最新版本（使用接受 workspaceId 的重载方法）
                    Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

                    if (latestVersion.isEmpty()) {
                        // 不存在版本 - 回退到传统删除
                        log.info("数据集 '{}' 不存在任何版本，回退到传统删除", datasetId);
                        return dao.delete(null, datasetId, filters).then();
                    }

                    // 当 createVersion=false 时处理基于过滤器的就地修改
                    if (!createVersion) {
                        log.info("修改最新版本 '{}'（数据集 '{}'，createVersion=false）",
                                latestVersion.get().id(), datasetId);

                        return deleteItemsFromExistingVersionByFilters(datasetId, latestVersion.get().id(), filters,
                                workspaceId,
                                userName);
                    }

                    // 创建包含删除的新版本
                    UUID baseVersionId = latestVersion.get().id();
                    UUID newVersionId = idGenerator.generateId();

                    // OPIK-6390: 从 ClickHouse 获取基础行数，使复制池和
                    // 计算的 deletedCount 都基于真实数据，而非 items_total 漂移。
                    return versionDao
                            .countRowsInVersion(datasetId, baseVersionId, Set.of(), null, workspaceId)
                            .flatMap(baseRowCount -> {
                                int baseItemsCount = baseRowCount.intValue();

                                // 空过滤器 = 删除全部（不复制到新版本）
                                Mono<Long> copyMono;
                                if (filters == null || filters.isEmpty()) {
                                    log.info("空过滤器 = 删除全部。创建空版本 '{}'（数据集 '{}'）",
                                            newVersionId, datasetId);
                                    copyMono = Mono.just(0L);
                                } else {
                                    // 为复制操作生成 UUID 池（最坏情况 = 复制所有条目）
                                    List<UUID> uuids = generateUuidPool(idGenerator, baseItemsCount);

                                    // 使用高效的基于过滤器的复制 - 复制不匹配过滤器的条目
                                    copyMono = versionDao.copyVersionItems(datasetId, baseVersionId,
                                            datasetId, newVersionId,
                                            filters, uuids);
                                }

                                return copyMono
                                        .flatMap(newVersionItemCount -> {
                                            int deletedCount = baseItemsCount - newVersionItemCount.intValue();

                                            log.info(
                                                    "创建版本元数据：dataset='{}'、baseVersion='{}'、newVersion='{}'、deletedCount='{}'、newItemCount='{}'",
                                                    datasetId, baseVersionId, newVersionId, deletedCount,
                                                    newVersionItemCount);

                                            // 创建版本元数据
                                            String changeDescription = deletedCount == 1
                                                    ? "Deleted 1 item"
                                                    : "Deleted " + deletedCount + " items";

                                            return createVersionFromDelta(
                                                    datasetId,
                                                    newVersionId,
                                                    newVersionItemCount.intValue(),
                                                    baseVersionId,
                                                    null, // 无标签
                                                    changeDescription,
                                                    null, // 从基础版本继承评估器
                                                    null, // 从基础版本继承执行策略
                                                    false, // 不清除执行策略
                                                    batchGroupId,
                                                    true, // enforceLatestCas: 在锁保护下与当前最新版本进行差异比较
                                                    workspaceId,
                                                    userName);
                                        });
                            })
                            .then();
                }));
    }

    /**
     * 按条目 ID 删除条目，创建新版本或修改最新版本。
     */
    private Mono<Void> deleteByItemIdsWithVersion(Set<UUID> ids, UUID resolvedDatasetId, String workspaceId,
            String userName, UUID batchGroupId) {
        // 从 batchGroupId 派生 createVersion：null 表示修改最新版本，非 null 表示创建新版本
        boolean createVersion = batchGroupId != null;
        log.info("按 ID 版本化删除 '{}' 条条目，batchGroupId='{}'、createVersion='{}'",
                ids.size(), batchGroupId, createVersion);

        // 复用调用方已解析的 datasetId（用于加锁），而非第二次从
        // ClickHouse 解析；仅在调用方没有时在此解析。
        Mono<UUID> datasetIdMono = resolvedDatasetId != null
                ? Mono.just(resolvedDatasetId)
                : resolveDatasetIdFromItemIds(ids);

        return datasetIdMono
                .flatMap(datasetId -> {
                    log.info("已解析删除请求的数据集 '{}'（包含 '{}' 个条目 ID）", datasetId, ids.size());
                    return deleteByDatasetItemIdsInDataset(ids, datasetId, workspaceId, userName,
                            batchGroupId, createVersion);
                });
    }

    /**
     * 在已知数据集中按 dataset_item_id 值删除条目。
     */
    private Mono<Void> deleteByDatasetItemIdsInDataset(Set<UUID> datasetItemIds, UUID datasetId,
            String workspaceId, String userName, UUID batchGroupId, boolean createVersion) {
        log.info("版本化删除 '{}' 条条目（数据集 '{}'），batchGroupId='{}'、createVersion='{}'",
                datasetItemIds.size(), datasetId, batchGroupId, createVersion);

        // 如果启用了延迟迁移，确保数据集已迁移
        return ensureLazyMigration(datasetId, workspaceId)
                .then(Mono.defer(() -> {
                    // 获取最新版本（使用接受 workspaceId 的重载方法，因为我们在响应式上下文中）
                    Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

                    if (latestVersion.isEmpty()) {
                        // 不存在版本
                        if (!createVersion) {
                            // createVersion=false：没有要修改的内容，直接返回空（幂等删除）
                            log.info("数据集 '{}' 不存在任何版本，无可删除内容（createVersion=false）",
                                    datasetId);
                            return Mono.empty();
                        }
                        // createVersion=true：回退到传统删除
                        log.info("数据集 '{}' 不存在任何版本，回退到传统删除", datasetId);
                        return dao.delete(datasetItemIds, null, null).then();
                    }

                    UUID latestVersionId = latestVersion.get().id();

                    // 如果 createVersion=false，修改最新版本而非创建新版本
                    if (!createVersion) {
                        log.info("修改最新版本 '{}'（数据集 '{}'，createVersion=false）",
                                latestVersionId, datasetId);
                        return deleteItemsFromExistingVersion(datasetItemIds, datasetId, latestVersionId,
                                workspaceId,
                                userName);
                    }

                    // createVersion=true：创建包含删除的新版本
                    UUID newVersionId = idGenerator.generateId();
                    log.info("为数据集 '{}' 创建删除了 '{}' 条条目的新版本",
                            datasetId, datasetItemIds.size());

                    return createVersionWithDeletion(datasetId, latestVersionId, newVersionId, datasetItemIds,
                            batchGroupId, workspaceId, userName);
                }));
    }

    /**
     * 创建已删除指定条目（从新版本中排除）的新版本。
     */
    private Mono<Void> createVersionWithDeletion(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            Set<UUID> deletedIds, UUID batchGroupId,
            String workspaceId, String userName) {

        return applyEditDeleteWithLiveCount(datasetId, baseVersionId, newVersionId,
                List.of(), deletedIds, workspaceId)
                .flatMap(itemsTotal -> {
                    log.info("已向数据集 '{}' 应用删除增量：itemsTotal '{}'", datasetId, itemsTotal);

                    // 创建版本元数据
                    return createVersionFromDelta(
                            datasetId,
                            newVersionId,
                            itemsTotal.intValue(),
                            baseVersionId,
                            null, // 无标签
                            null, // 无变更描述（自动生成）
                            null, // 从基础版本继承评估器
                            null, // 从基础版本继承执行策略
                            false, // 不清除执行策略
                            batchGroupId,
                            true, // enforceLatestCas: 在锁保护下与当前最新版本进行差异比较
                            workspaceId,
                            userName);
                })
                .then();
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(
            int page, int size, @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria) {

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            Visibility visibility = ctx.get(RequestContext.VISIBILITY);

            return Mono.fromCallable(
                    () -> {
                        datasetService.verifyVisibilityIfExists(datasetItemSearchCriteria.datasetId(), workspaceId,
                                visibility);
                        return datasetItemSearchCriteria.datasetId();
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(__ -> ensureLazyMigration(datasetItemSearchCriteria.datasetId(), workspaceId)
                            .then(Mono.defer(() -> getItemsInternal(page, size, datasetItemSearchCriteria))));
        });
    }

    private Mono<DatasetItemPage> getItemsInternal(
            int page, int size, @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria) {

        if (StringUtils.isNotBlank(datasetItemSearchCriteria.versionHashOrTag())) {
            // 从 dataset_item_versions 表获取版本化（不可变）条目
            log.info("按 '{}' 查找版本化数据集条目，page '{}'、size '{}'", datasetItemSearchCriteria, page,
                    size);

            return Mono.deferContextual(ctx -> {
                String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                // 将版本哈希/标签解析为版本 ID
                UUID versionId = versionService.resolveVersionId(workspaceId,
                        datasetItemSearchCriteria.datasetId(),
                        datasetItemSearchCriteria.versionHashOrTag());
                log.info("已将版本 '{}' 解析为版本 ID '{}'（数据集 '{}'）",
                        datasetItemSearchCriteria.versionHashOrTag(), versionId, datasetItemSearchCriteria.datasetId());

                // 对于版本化条目，hasDraft 始终为 false（此概念不适用于不可变版本）
                return versionDao.getItems(datasetItemSearchCriteria, page, size, versionId)
                        .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
            });
        } else if (featureFlags.isDatasetVersioningEnabled()) {
            // 版本控制开关已开启
            return Mono.deferContextual(ctx -> {
                String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                // 如果存在 experimentIds，使用实验特定版本获取条目
                if (CollectionUtils.isNotEmpty(datasetItemSearchCriteria.experimentIds())) {
                    log.info(
                            "按 '{}' 查找带实验条目的数据集条目，page '{}'、size '{}'（使用实验特定版本）",
                            datasetItemSearchCriteria, page, size);

                    return getItemsWithExperimentItems(datasetItemSearchCriteria, page, size,
                            workspaceId);
                }

                // 否则，从最新版本获取条目
                log.info("按 '{}' 查找最新版本数据集条目，page '{}'、size '{}'",
                        datasetItemSearchCriteria, page, size);
                return getItemsFromLatestVersion(datasetItemSearchCriteria, page, size, workspaceId);
            });
        } else {
            // 版本控制开关已关闭：从 dataset_items 表获取条目
            log.info("按 '{}' 查找草稿数据集条目，page '{}'、size '{}'",
                    datasetItemSearchCriteria, page, size);

            return dao.getItems(datasetItemSearchCriteria, page, size)
                    .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
        }
    }

    private Mono<DatasetItemPage> getItemsFromLatestVersion(DatasetItemSearchCriteria criteria, int page, int size,
            String workspaceId) {
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(criteria.datasetId(), workspaceId);

        if (latestVersion.isEmpty()) {
            // 尚不存在版本 - 回退到传统条目
            log.info("未找到数据集 '{}' 的版本，回退到草稿条目", criteria.datasetId());
            return dao.getItems(criteria, page, size)
                    .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
        }

        UUID versionId = latestVersion.get().id();
        log.info("从最新版本 '{}' 获取数据集 '{}' 的条目", versionId, criteria.datasetId());

        return versionDao.getItems(criteria, page, size, versionId)
                .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
    }

    /**
     * 获取数据集回退版本 ID 的辅助方法。
     * 如果存在则返回最新版本 ID，否则返回空。
     *
     * @param datasetId 数据集 ID
     * @param workspaceId 工作空间 ID
     * @return 包含回退版本 ID 的 Optional，如果不存在版本则为空
     */
    private Optional<UUID> getFallbackVersionId(UUID datasetId, String workspaceId) {
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

        if (latestVersion.isEmpty()) {
            log.error("版本控制启用时未找到数据集 '{}' 的版本", datasetId);
            return Optional.empty();
        }

        return Optional.of(latestVersion.get().id());
    }

    /**
     * 使用传统 DAO 获取实验条目统计的辅助方法。
     * 当版本控制禁用或尚不存在版本时作为回退使用。
     *
     * @param datasetId 数据集 ID
     * @param experimentIds 实验 ID
     * @param filters 要应用的过滤器
     * @return 包含项目统计的 Mono
     */
    private Mono<ProjectStats> getExperimentItemsStatsFromLegacyDao(UUID datasetId,
            Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        return dao.getExperimentItemsStats(datasetId, experimentIds, filters)
                .switchIfEmpty(Mono.just(ProjectStats.empty()))
                .doOnSuccess(stats -> log.info("找到数据集 '{}' 的实验条目统计，count '{}'",
                        datasetId, stats.stats().size()));
    }

    private Mono<DatasetItemPage> getItemsWithExperimentItems(DatasetItemSearchCriteria criteria,
            int page, int size, String workspaceId) {
        Optional<UUID> fallbackVersionId = getFallbackVersionId(criteria.datasetId(), workspaceId);

        // 当数据集不再存在时（例如测试套件已删除），版本记录从 MySQL 中消失。
        // 使用空字符串作为无害占位符：测试套件实验始终在 ClickHouse 中携带自己的显式
        // dataset_version_id，因此空回退永远不会用于匹配，assertion_results
        // 仍通过版本化查询正确返回。
        String resolvedFallbackVersionId = fallbackVersionId.map(UUID::toString).orElseGet(() -> {
            log.info(
                    "未找到数据集 '{}' 的版本，使用空字符串作为回退版本以查询实验条目",
                    criteria.datasetId());
            return "";
        });

        log.info(
                "获取数据集 '{}' 带实验条目的条目，使用版本 '{}' 作为无显式版本实验的回退",
                criteria.datasetId(), resolvedFallbackVersionId);

        return versionDao.getItemsWithExperimentItems(criteria, page, size, resolvedFallbackVersionId)
                .defaultIfEmpty(DatasetItemPage.empty(page, sortingFactory.getSortableFields()));
    }

    public Mono<ProjectStats> getExperimentItemsStats(@NonNull UUID datasetId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        log.info("获取数据集 '{}' 和实验 '{}' 的实验条目统计（过滤器 '{}'）", datasetId,
                experimentIds, filters);

        if (!featureFlags.isDatasetVersioningEnabled()) {
            // 功能开关关闭 - 使用传统 DAO
            log.debug("数据集版本控制已禁用，使用传统 DAO 获取统计");
            return getExperimentItemsStatsFromLegacyDao(datasetId, experimentIds, filters);
        }

        // 功能开关开启 - 使用带实验特定版本的版本化 DAO
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            Optional<UUID> fallbackVersionId = getFallbackVersionId(datasetId, workspaceId);

            if (fallbackVersionId.isEmpty()) {
                // 尚不存在版本 - 回退到传统 DAO
                log.info("未找到数据集 '{}' 的版本，回退到传统 DAO 获取统计", datasetId);
                return getExperimentItemsStatsFromLegacyDao(datasetId, experimentIds, filters);
            }

            log.debug(
                    "数据集版本控制已启用，使用版本 '{}' 作为无显式版本实验的回退",
                    fallbackVersionId.get());
            return versionDao.getExperimentItemsStats(datasetId, fallbackVersionId.get(), experimentIds, filters)
                    .switchIfEmpty(Mono.just(ProjectStats.empty()))
                    .doOnSuccess(stats -> log.info(
                            "找到数据集 '{}' 的实验条目统计，count '{}'（使用实验特定版本，回退 '{}'）",
                            datasetId, stats.stats().size(), fallbackVersionId.get()));
        });
    }

    @Override
    @WithSpan
    public Mono<DatasetVersion> applyDeltaChanges(@NonNull UUID datasetId,
            @NonNull DatasetItemChanges changes, boolean override) {

        // 与其他所有创建版本的路径一样，在按数据集锁下串行化，使
        // is-latest 检查和 'latest' 翻转原子化。没有锁时，下方
        // isLatestVersion(...) 的检查后执行属于 TOCTOU：并发写入方可能在检查与
        // 翻转之间移动 'latest'，重新触发此端点的 OPIK-7264 丢失更新（OPIK-7383）。
        return withDatasetVersionLock(datasetId, Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            log.info("为数据集 '{}' 应用增量变更，baseVersion '{}'、override '{}'",
                    datasetId, changes.baseVersion(), override);

            // OPIK-6696: copy_from_dataset_id 和 copy_from_version_id 必须一起设置。
            // 与 DatasetItemBatchValidator（DatasetItemBatch 端点）共享规则和错误文本。
            if (!DatasetItemBatchValidator.isCopyFromPairConsistent(changes.copyFromDatasetId(),
                    changes.copyFromVersionId())) {
                return Mono.error(new BadRequestException(DatasetItemBatchValidator.COPY_FROM_PAIR_MESSAGE));
            }

            // 验证数据集是否存在（使用显式 workspaceId，因为我们在响应式上下文中）
            datasetService.findById(datasetId, workspaceId, null);

            // baseVersion 直接是版本 ID（不是哈希或标签）
            UUID baseVersionId = changes.baseVersion();

            // 没有基础版本：创建第一个版本（仅元数据，无条目增量）
            if (baseVersionId == null) {
                if (!override) {
                    return Mono.error(new BadRequestException(
                            "baseVersion is required. Use override=true to create the first version without a base."));
                }
                if (versionService.hasVersions(workspaceId, datasetId)) {
                    return Mono.error(new BadRequestException(
                            "baseVersion is required when the dataset already has versions."));
                }
                boolean hasItems = (changes.addedItems() != null && !changes.addedItems().isEmpty())
                        || (changes.editedItems() != null && !changes.editedItems().isEmpty())
                        || (changes.deletedIds() != null && !changes.deletedIds().isEmpty());
                if (hasItems) {
                    return Mono.error(new BadRequestException(
                            "addedItems, editedItems, and deletedIds must be empty when baseVersion is null."));
                }
                UUID newVersionId = idGenerator.generateId();
                return Mono.fromCallable(() -> {
                    DatasetVersion version = versionService.createVersionFromDelta(
                            datasetId, newVersionId, 0, null,
                            changes.tags(), changes.changeDescription(),
                            changes.evaluators(), changes.executionPolicy(),
                            Boolean.TRUE.equals(changes.clearExecutionPolicy()),
                            // 首个版本（baseVersionId == null）：没有先前的 'latest' 可供 CAS 比较。
                            null, false, workspaceId, userName);
                    log.info("已创建首个版本 '{}'（数据集 '{}'），hash '{}'",
                            version.id(), datasetId, version.versionHash());
                    return version;
                });
            }

            // 验证基础版本是否存在（否则抛出 NotFoundException）。
            // OPIK-6390: 不再在此处读取 items_total — UUID 池大小现在来自下方的实时
            // ClickHouse 计数。
            versionService.getVersionById(workspaceId, datasetId, baseVersionId);

            // 检查 baseVersion 是否是最新版本（除非设置了 override）
            if (!override && !versionService.isLatestVersion(workspaceId, datasetId, baseVersionId)) {
                log.warn("版本冲突：baseVersion '{}' 不是数据集 '{}' 的最新版本",
                        changes.baseVersion(), datasetId);
                return Mono.error(new ClientErrorException(
                        Response.status(Response.Status.CONFLICT)
                                .entity(new ErrorMessage(List.of(
                                        "Version conflict: baseVersion is not the latest. " +
                                                "Use override=true to force creation.")))
                                .build()));
            }

            // 生成新版本 ID
            UUID newVersionId = idGenerator.generateId();
            log.info("已生成新版本 ID '{}'（数据集 '{}'）", newVersionId, datasetId);

            // 准备添加的条目（同步 - 无需合并）
            List<DatasetItem> addedItems = prepareAddedItems(changes, datasetId);
            Set<UUID> deletedIds = changes.deletedIds() != null ? changes.deletedIds() : Set.of();

            List<DatasetItemEdit> editedItemEdits = changes.editedItems() != null ? changes.editedItems() : List.of();
            Set<UUID> editedDatasetItemIds = editedItemEdits.stream()
                    .map(DatasetItemEdit::id)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            // OPIK-6696: 当提供 copy-from 坐标时，COPY（和支持读取：
            // 池大小调整、通过 SELECT-INSERT 编辑）都针对调用者提供的
            // (dataset, version) 对运行，而非目标刚创建的先前版本。对于迁移
            // 这是源 v_i — 稳定且完全复制，避免多副本写后读窗口。
            CopyFromCoordinates copyFrom = resolveAndValidateCopyFrom(workspaceId, changes.copyFromDatasetId(),
                    changes.copyFromVersionId(), datasetId, baseVersionId);
            UUID copyDatasetId = copyFrom.datasetId();
            UUID copyVersionId = copyFrom.versionId();

            // OPIK-6390: 从实时 ClickHouse 计数中调整未更改 UUID 池的大小，而非使用
            // MySQL items_total，后者可能漂移到实际行数以下并静默截断复制。
            // 排除后续 applyDelta 从 COPY 中排除的相同 ID。
            Set<UUID> excludedFromCopy = new HashSet<>(deletedIds);
            excludedFromCopy.addAll(editedDatasetItemIds);

            return versionDao.countRowsInVersion(copyDatasetId, copyVersionId, excludedFromCopy, null, workspaceId)
                    .flatMap(unchangedCount -> {

                        // 为所有条目生成 UUID，按 ClickHouse 的 ORDER BY id DESC 正确排序
                        // 由于 UUIDv7 是时间有序的（越大 = 越新）且我们按 DESC 排序（最大的在前），
                        // 我们需要按期望出现的逆序生成 UUID：
                        // 1. 未更改条目优先（最小的 UUID）- 将出现在最后
                        // 2. 已编辑条目其次（中间的 UUID）- 将出现在中间
                        // 3. 添加的条目最后（最大的 UUID）- 将出现在最前

                        // 但是，我们反转未更改 UUID 池以保持原始顺序
                        List<UUID> unchangedUuids = generateUnchangedUuidsReversed(unchangedCount.intValue());
                        List<UUID> editedUuids = generateUuidPool(idGenerator, editedItemEdits.size());
                        List<UUID> addedUuids = generateUuidPool(idGenerator, addedItems.size());

                        List<DatasetItem> addedItemsWithIds = withAssignedRowIds(addedItems, addedUuids);

                        // 验证所有待插入或编辑条目的标签限制
                        Stream.concat(
                                addedItemsWithIds.stream().map(DatasetItem::tags),
                                editedItemEdits.stream().map(DatasetItemEdit::tags))
                                .filter(tags -> tags != null && tags.size() > TagOperations.MAX_TAGS_PER_ITEM)
                                .findFirst()
                                .ifPresent(tags -> {
                                    throw new ClientErrorException(
                                            Response.status(422)
                                                    .entity(new ErrorMessage(List.of(
                                                            TagOperations.TAG_LIMIT_ERROR)))
                                                    .build());
                                });

                        // 通过 INSERT...SELECT 编辑条目（合并在 SQL 中完成，而非 Java）。
                        // OPIK-6696: 从 copy-from 坐标读取源行，插入到 datasetId。
                        Mono<Long> editedCountMono = versionDao.editItemsViaSelectInsert(
                                copyDatasetId, copyVersionId,
                                datasetId, newVersionId,
                                editedItemEdits, editedUuids);

                        // 为添加的条目应用增量 + 复制未更改的（排除已编辑 + 已删除的）。
                        // OPIK-6696: COPY 从 copy-from 坐标读取，写入 datasetId/newVersionId。
                        return editedCountMono
                                .flatMap(editedCount -> versionDao.applyDelta(datasetId, newVersionId,
                                        addedItemsWithIds, List.of(), deletedIds, unchangedUuids,
                                        editedDatasetItemIds, copyDatasetId, copyVersionId)
                                        .map(otherCount -> editedCount + otherCount))
                                .flatMap(itemsTotal -> {
                                    log.info("已向数据集 '{}' 应用增量：itemsTotal '{}'", datasetId, itemsTotal);

                                    return createVersionFromDelta(
                                            datasetId,
                                            newVersionId,
                                            itemsTotal.intValue(),
                                            baseVersionId,
                                            changes.tags(),
                                            changes.changeDescription(),
                                            changes.evaluators(),
                                            changes.executionPolicy(),
                                            Boolean.TRUE.equals(changes.clearExecutionPolicy()),
                                            null, // 无批次组 ID
                                            // 除非 override=true，否则对 baseVersionId 进行 'latest' 翻转的 CAS，
                                            // 后者有意从可能非最新的基础版本分支出去。
                                            !override,
                                            workspaceId,
                                            userName);
                                });
                    });
        }));
    }

    /**
     * 通过设置稳定 ID 来准备添加的条目。
     * 对于新条目，我们生成新的稳定 ID。
     */
    private List<DatasetItem> prepareAddedItems(DatasetItemChanges changes, UUID datasetId) {
        if (changes.addedItems() == null || changes.addedItems().isEmpty()) {
            return List.of();
        }

        return changes.addedItems().stream()
                .map(item -> {
                    // 为新条目生成新的稳定 ID
                    UUID stableId = idGenerator.generateId();
                    // 设置 datasetItemId（稳定 ID）但将 id 留空 - 它将在此方法后面分配
                    return item.toBuilder()
                            .id(null)
                            .datasetItemId(stableId)
                            .datasetId(datasetId)
                            .build();
                })
                .toList();
    }

    @Override
    @WithSpan
    public Mono<DatasetVersion> save(@NonNull DatasetItemBatch batch) {

        if (!featureFlags.isDatasetVersioningEnabled()) {
            // 传统方式：保存到传统表
            log.info("为数据集 '{}' 将条目保存到传统表", batch.datasetId());
            return verifyDatasetExistsAndSave(batch).then(Mono.empty());
        }

        return getDatasetId(batch)
                .flatMap(datasetId -> withDatasetVersionLock(datasetId, Mono.deferContextual(ctx -> {

                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    String userName = ctx.get(RequestContext.USER_NAME);

                    UUID batchGroupId = batch.batchGroupId();

                    if (batchGroupId == null) {
                        // 没有 batch_group_id：修改最新版本（向后兼容）
                        log.info("修改数据集 '{}' 的最新版本（无 batch_group_id）", datasetId);
                        return mutateLatestVersionWithInsert(batch, datasetId, workspaceId, userName);
                    }

                    // 提供了 batch_group_id：使用批次分组创建新版本
                    log.info("为数据集 '{}' 使用批次分组创建版本，batch_group_id：'{}'", datasetId,
                            batchGroupId);
                    return handleGroupedInsertion(batchGroupId, batch, datasetId, workspaceId, userName);
                })));
    }

    /**
     * 通过插入/更新条目来修改最新版本。
     * 当 batchGroupId 为空时使用（向后兼容）。
     */
    private Mono<DatasetVersion> mutateLatestVersionWithInsert(DatasetItemBatch batch, UUID datasetId,
            String workspaceId, String userName) {
        log.info("修改数据集 '{}' 的最新版本（'{}' 条条目）", datasetId, batch.items().size());

        // 获取最新版本
        Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId, workspaceId);

        if (latestVersion.isEmpty()) {
            // 不存在版本 - 创建第一个版本
            log.info("数据集 '{}' 不存在任何版本，创建首个版本", datasetId);
            return saveItemsWithVersion(batch, datasetId, null)
                    .contextWrite(c -> c.put(RequestContext.WORKSPACE_ID, workspaceId)
                            .put(RequestContext.USER_NAME, userName));
        }

        // 版本存在 - 直接向其中插入条目
        UUID latestVersionId = latestVersion.get().id();
        log.info("插入 '{}' 条条目到现有版本 '{}'", batch.items().size(), latestVersionId);

        return insertItemsIntoVersion(batch, datasetId, latestVersionId, workspaceId, userName);
    }

    /**
     * 向现有版本插入条目的共享方法。
     * 处理验证、新条目与更新条目的分类以及计数更新。
     * 由 mutateLatestVersionWithInsert 和 handleGroupedInsertion 使用。
     *
     * @param batch 要插入的条目批次
     * @param datasetId 数据集 ID
     * @param versionId 要插入的版本 ID
     * @param workspaceId 工作空间 ID
     * @param userName 用户名
     * @return 发出更新后的数据集版本的 Mono
     */
    private Mono<DatasetVersion> insertItemsIntoVersion(DatasetItemBatch batch, UUID datasetId, UUID versionId,
            String workspaceId, String userName) {
        // 验证并准备条目
        List<DatasetItem> validatedItems = addIdIfAbsent(batch);

        // 确保所有条目都设置了 datasetItemId（如果 datasetItemId 为空则使用 id 字段）
        List<DatasetItem> normalizedItems = validatedItems.stream()
                .map(item -> {
                    if (item.datasetItemId() == null) {
                        UUID stableId = item.id() != null ? item.id() : idGenerator.generateId();
                        return item.toBuilder()
                                .datasetItemId(stableId)
                                .build();
                    }
                    return item;
                })
                .toList();

        return Mono.deferContextual(ctx -> {
            // 验证 span 和 trace
            return validateSpans(workspaceId, normalizedItems)
                    .then(validateTraces(workspaceId, normalizedItems))
                    .then(Mono.defer(() -> {
                        // 通过以批次为界的查找将传入条目分类为新条目与更新条目，
                        // 而非从 ClickHouse 读取整个版本（OPIK-7705）。
                        Set<UUID> incomingItemIds = normalizedItems.stream()
                                .map(DatasetItem::datasetItemId)
                                .collect(Collectors.toSet());

                        return versionDao.countExistingItemIds(datasetId, versionId, incomingItemIds)
                                .flatMap(existingCount -> {
                                    int finalUpdatedItemsCount = existingCount.intValue();
                                    int finalNewItemsCount = incomingItemIds.size() - finalUpdatedItemsCount;

                                    log.info("向版本 '{}' 插入：new='{}'、updated='{}'",
                                            versionId, finalNewItemsCount, finalUpdatedItemsCount);

                                    // 直接向现有版本插入条目
                                    return versionDao
                                            .insertItems(datasetId, versionId, normalizedItems, workspaceId, userName)
                                            .then(Mono.fromCallable(() -> {
                                                updateVersionCountsForInsert(versionId, workspaceId, finalNewItemsCount,
                                                        finalUpdatedItemsCount, userName);
                                                return versionService.getVersionById(workspaceId, datasetId, versionId);
                                            }).subscribeOn(Schedulers.boundedElastic()));
                                });
                    }));
        }).contextWrite(c -> c.put(RequestContext.WORKSPACE_ID, workspaceId)
                .put(RequestContext.USER_NAME, userName));
    }

    /**
     * 在向现有版本插入条目后更新版本计数。
     * 提取出来以降低复杂度并提高可测试性。
     *
     * @param versionId 要更新的版本 ID
     * @param workspaceId 工作空间 ID
     * @param newItemsCount 插入的新条目数量
     * @param updatedItemsCount 更新的条目数量
     * @param userName 执行更新的用户
     */
    private void updateVersionCountsForInsert(UUID versionId, String workspaceId, int newItemsCount,
            int updatedItemsCount, String userName) {
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            var currentVersion = dao.findById(versionId, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            "Version not found: '%s'".formatted(versionId)));

            // 仅按新条目（非更新）增加总数
            int newTotal = currentVersion.itemsTotal() + newItemsCount;
            int newAdded = currentVersion.itemsAdded() + newItemsCount;
            int newModified = currentVersion.itemsModified() + updatedItemsCount;

            dao.updateCounts(versionId, newTotal, newAdded, newModified,
                    currentVersion.itemsDeleted(), workspaceId, userName);
            return null;
        });
    }

    /**
     * 在从现有版本删除条目后更新版本计数。
     * 提取出来以降低复杂度并提高可测试性。
     *
     * @param versionId 要更新的版本 ID
     * @param workspaceId 工作空间 ID
     * @param currentVersion 删除前的当前版本
     * @param deletedCount 删除的条目数量
     * @param userName 执行更新的用户
     */
    private void updateVersionCountsForDelete(UUID versionId, String workspaceId, DatasetVersion currentVersion,
            int deletedCount, String userName) {
        int newTotal = currentVersion.itemsTotal() - deletedCount;
        int newDeleted = currentVersion.itemsDeleted() + deletedCount;

        log.info("deleteItemsFromExistingVersion：更新计数 - newTotal='{}'、newDeleted='{}'",
                newTotal, newDeleted);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            dao.updateCounts(versionId, newTotal, currentVersion.itemsAdded(),
                    currentVersion.itemsModified(), newDeleted, workspaceId, userName);
            return null;
        });
    }

    /**
     * 通过复用现有版本创建逻辑，使用 batch_group_id 创建新版本。
     * 此方法委托给 createFirstVersion 或 createVersionWithDelta，
     * 然后将 batch_group_id 与创建的版本关联。
     */
    private Mono<DatasetVersion> saveItemsWithVersion(DatasetItemBatch batch, UUID datasetId, UUID batchGroupId) {
        // 延迟所有操作，使调用方可以将其包裹在 withDatasetVersionLock 中，并确保
        // 在获取锁之前不会执行任何工作（甚至下方的条目验证也不会）（OPIK-7264）。
        return Mono.defer(() -> {
            if (batch.items() == null || batch.items().isEmpty()) {
                log.debug("批次为空，跳过为数据集 '{}' 创建版本", datasetId);
                return Mono.empty();
            }

            // 验证 UUID 版本并在缺失时添加 ID
            List<DatasetItem> validatedItems = addIdIfAbsent(batch);

            return Mono.deferContextual(ctx -> {
                String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                String userName = ctx.get(RequestContext.USER_NAME);

                log.debug("为数据集 '{}' 版本化保存条目，itemCount '{}'、batchGroupId '{}'",
                        datasetId, batch.items().size(), batchGroupId);

                // 如果启用了延迟迁移，确保数据集已迁移。数据集存在性已由
                // 每个调用方（createFromTraces/Spans、save()、saveBatch）保证，因此这里
                // 无需多余的 findById。
                return ensureLazyMigration(datasetId, workspaceId)
                        // 在继续之前验证 span 和 trace 工作空间
                        .then(Mono.defer(() -> validateSpans(workspaceId, validatedItems)))
                        .then(Mono.defer(() -> validateTraces(workspaceId, validatedItems)))
                        .then(Mono.defer(() -> {

                            // 获取最新版本（如果存在）- 使用接受 workspaceId 的重载方法
                            Optional<DatasetVersion> latestVersion = versionService.getLatestVersion(datasetId,
                                    workspaceId);

                            if (latestVersion.isEmpty()) {
                                // 尚不存在版本 - 创建第一个版本，所有条目为"已添加"
                                return createFirstVersion(datasetId, validatedItems, batchGroupId, workspaceId,
                                        userName);
                            }

                            // 版本存在 - 在最新版本之上应用增量。OPIK-6696：如果调用者
                            // 提供了 copy-from 坐标，未更改行的 COPY 将从该
                            // (dataset, version) 对读取，而非目标刚创建的先前版本。
                            UUID baseVersionId = latestVersion.get().id();
                            return createVersionWithDelta(datasetId, baseVersionId, validatedItems, batchGroupId,
                                    workspaceId, userName, batch.copyFromDatasetId(), batch.copyFromVersionId());
                        }));
            });
        });
    }

    private Mono<DatasetVersion> createFirstVersion(UUID datasetId, List<DatasetItem> items,
            UUID batchGroupId, String workspaceId, String userName) {
        log.info("为数据集 '{}' 创建包含 '{}' 条条目的首个版本", datasetId, items.size());

        UUID newVersionId = idGenerator.generateId();

        // 对于第一个版本，所有条目都是"已添加"
        // 为每个条目设置 datasetItemId 作为稳定 ID
        // 如果已设置则使用 datasetItemId，否则使用 id，否则生成新的
        List<DatasetItem> addedItems = items.stream()
                .map(item -> {
                    UUID stableId = item.id() != null ? item.id() : idGenerator.generateId();
                    return item.toBuilder()
                            .datasetItemId(stableId)
                            .datasetId(datasetId)
                            .build();
                })
                .toList();

        // 使用 applyDelta，没有基础版本条目（空复制）
        // 我们需要特殊路径，因为没有基础版本
        return versionDao.insertItems(datasetId, newVersionId, addedItems, workspaceId, userName)
                .flatMap(itemsTotal -> {
                    log.info("已插入 '{}' 条条目（数据集 '{}' 的首个版本）", itemsTotal, datasetId);

                    // 根据是否为批操作确定变更描述
                    String changeDescription = batchGroupId != null
                            ? "Auto-created from SDK batch operation"
                            : null;

                    // 创建版本元数据（第一个版本 - 所有条目为"已添加"）
                    return createVersionFromDelta(
                            datasetId,
                            newVersionId,
                            itemsTotal.intValue(),
                            null, // 首个版本无基础版本
                            null, // 无标签
                            changeDescription,
                            null, // 首个版本无评估器
                            null, // 首个版本无执行策略
                            false, // 不清除执行策略
                            batchGroupId,
                            true, // enforceLatestCas（首个版本为空操作，基础版本为 null）
                            workspaceId,
                            userName);
                });
    }

    private record CopyFromCoordinates(UUID datasetId, UUID versionId) {
    }

    // OPIK-6696: 解析延续 COPY（和支持读取）运行的源 — 调用者提供的
    // (dataset, version) 对（如果提供），否则为目标的先前版本。
    // 当调用者提供坐标时，验证它们解析为命名数据集上的真实工作空间范围版本，
    // 否则抛出 NotFound — 没有这个，虚假或跨工作空间的对会静默生成
    // 所有传入条目分类为添加且零延续的版本。
    private CopyFromCoordinates resolveAndValidateCopyFrom(String workspaceId, UUID copyFromDatasetId,
            UUID copyFromVersionId, UUID fallbackDatasetId, UUID fallbackVersionId) {

        if (copyFromDatasetId == null) {
            return new CopyFromCoordinates(fallbackDatasetId, fallbackVersionId);
        }

        DatasetVersion sourceVersion = versionService.getVersionById(workspaceId, copyFromDatasetId,
                copyFromVersionId);
        if (!sourceVersion.datasetId().equals(copyFromDatasetId)) {
            throw new NotFoundException(
                    "Version '%s' does not belong to dataset '%s'".formatted(copyFromVersionId, copyFromDatasetId));
        }
        return new CopyFromCoordinates(copyFromDatasetId, copyFromVersionId);
    }

    private Mono<DatasetVersion> createVersionWithDelta(UUID datasetId, UUID baseVersionId,
            List<DatasetItem> items, UUID batchGroupId, String workspaceId, String userName,
            UUID copyFromDatasetId, UUID copyFromVersionId) {

        // OPIK-6696: 当提供 copy-from 坐标时，未更改行的 COPY 和分类 SELECT
        // 都从该 (dataset, version) 对读取，而非目标刚创建的先前版本。
        // 对于迁移这是源 v_i — 稳定、完全复制、无多副本写后读窗口。
        CopyFromCoordinates copyFrom = resolveAndValidateCopyFrom(workspaceId, copyFromDatasetId, copyFromVersionId,
                datasetId, baseVersionId);
        UUID copyDatasetId = copyFrom.datasetId();
        UUID copyVersionId = copyFrom.versionId();

        log.info(
                "为数据集 '{}' 创建带增量的版本，baseVersion '{}'、itemCount '{}'、copyFromDatasetId '{}'、copyFromVersionId '{}'",
                datasetId, baseVersionId, items.size(), copyDatasetId, copyVersionId);

        UUID newVersionId = idGenerator.generateId();

        // 从 copy-from 版本获取现有条目 ID 以确定添加 vs 编辑
        return versionDao.getItemIdsAndHashes(copyDatasetId, copyVersionId)
                .collectList()
                .flatMap(existingItems -> {
                    Set<UUID> existingItemIds = existingItems.stream()
                            .map(DatasetItemIdAndHash::itemId)
                            .collect(Collectors.toSet());

                    // 将传入条目分类为添加或编辑
                    List<DatasetItem> addedItems = new ArrayList<>();
                    List<DatasetItem> editedItems = new ArrayList<>();

                    for (DatasetItem item : items) {
                        // 首先尝试 datasetItemId，然后回退到 id 以保持向后兼容性
                        UUID stableId = item.datasetItemId() != null ? item.datasetItemId() : item.id();
                        if (stableId != null && existingItemIds.contains(stableId)) {
                            // 现有条目 - 视为编辑
                            editedItems.add(item.toBuilder()
                                    .datasetItemId(stableId)
                                    .datasetId(datasetId)
                                    .build());
                        } else {
                            // 新条目 - 视为添加
                            UUID newItemId = stableId != null ? stableId : idGenerator.generateId();
                            addedItems.add(item.toBuilder()
                                    .datasetItemId(newItemId)
                                    .datasetId(datasetId)
                                    .build());
                        }
                    }

                    log.info("条目分类结果：added='{}'、edited='{}'（数据集 '{}'）",
                            addedItems.size(), editedItems.size(), datasetId);

                    // 计算未更改条目：copy-from 版本中未被编辑的条目
                    Set<UUID> editedItemIds = editedItems.stream()
                            .map(DatasetItem::datasetItemId)
                            .collect(Collectors.toSet());
                    int unchangedItemCount = (int) existingItems.stream()
                            .filter(item -> !editedItemIds.contains(item.itemId()))
                            .count();

                    // 为未更改、添加和编辑的条目生成 UUID
                    List<UUID> unchangedUuids = generateUnchangedUuidsReversed(unchangedItemCount);
                    List<UUID> addedUuids = generateUuidPool(idGenerator, addedItems.size());

                    // 为新增条目分配行 ID
                    List<DatasetItem> addedItemsWithIds = withAssignedRowIds(addedItems, addedUuids);

                    // 应用增量变更 - PUT 流程中无删除。COPY 从 copy-from 坐标读取。
                    return versionDao.applyDelta(datasetId, newVersionId,
                            addedItemsWithIds, editedItems, Set.of(), unchangedUuids,
                            Set.of(), copyDatasetId, copyVersionId)
                            .flatMap(itemsTotal -> {
                                log.info("已向数据集 '{}' 应用增量：itemsTotal '{}'", datasetId, itemsTotal);

                                // 根据是否为批操作确定变更描述
                                String changeDescription = batchGroupId != null
                                        ? "Auto-created from SDK batch operation"
                                        : null;

                                // 创建版本元数据
                                return createVersionFromDelta(
                                        datasetId,
                                        newVersionId,
                                        itemsTotal.intValue(),
                                        baseVersionId,
                                        null, // 无标签
                                        changeDescription,
                                        null, // 从基础版本继承评估器
                                        null, // 从基础版本继承执行策略
                                        false, // 不清除执行策略
                                        batchGroupId,
                                        true, // enforceLatestCas: 在锁保护下与当前最新版本进行差异比较
                                        workspaceId,
                                        userName);
                            });
                });
    }

    /**
    * 从增量变更创建数据集版本的规范方法。
    * 所有其他 createVersionFromDelta 重载都委托给此方法。
    *
    * @param datasetId 数据集 ID
    * @param newVersionId 新版本 ID
    * @param itemsTotal 新版本中的条目总数
    * @param baseVersionId 基础版本 ID（第一个版本为 null）
    * @param tags 版本标签（未指定为 null）
    * @param changeDescription 变更描述（自动生成为 null）
    * @param batchGroupId 批次组 ID（非批操作为 null）
    * @param workspaceId 工作空间 ID
    * @param userName 用户名
    * @return 发出创建的 DatasetVersion 的 Mono
    */
    private Mono<DatasetVersion> createVersionFromDelta(
            UUID datasetId,
            UUID newVersionId,
            int itemsTotal,
            UUID baseVersionId,
            List<String> tags,
            String changeDescription,
            List<EvaluatorItem> evaluators,
            ExecutionPolicy executionPolicy,
            boolean clearExecutionPolicy,
            UUID batchGroupId,
            boolean enforceLatestCas,
            String workspaceId,
            String userName) {

        return Mono.fromCallable(() -> versionService.createVersionFromDelta(
                datasetId,
                newVersionId,
                itemsTotal,
                baseVersionId,
                tags,
                changeDescription,
                evaluators,
                executionPolicy,
                clearExecutionPolicy,
                batchGroupId,
                enforceLatestCas,
                workspaceId,
                userName))
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(RetryUtils.handleOnDeadLocks())
                .doOnSuccess(version -> {
                    if (baseVersionId == null) {
                        log.info("已创建首个版本 '{}'（数据集 '{}'），hash '{}'",
                                version.id(), datasetId, version.versionHash());
                    } else {
                        log.info("已创建版本 '{}'（数据集 '{}'），hash '{}'",
                                version.id(), datasetId, version.versionHash());
                    }
                });
    }

    /**
     * 为未更改条目生成 UUID，反转以保持其原始顺序。
     * 这是必要的，因为 ClickHouse 按 id DESC 排序，而 UUIDv7 是时间有序的。
     */
    private List<UUID> generateUnchangedUuidsReversed(int count) {
        List<UUID> uuids = generateUuidPool(idGenerator, count);
        List<UUID> reversed = new ArrayList<>(uuids);
        Collections.reverse(reversed);
        return reversed;
    }

    /**
     * OPIK-6390 辅助方法：应用仅编辑和/或删除条目的增量，从基础版本的实时
     * ClickHouse 计数中调整未更改 UUID 池的大小（排除正在编辑或删除的稳定 ID）。
     * 替换了之前使用的 {@code DatasetVersion.itemsTotal()}，后者可能漂移到实际行数以下。
     *
     * <p>同时 INSERT 新条目的调用者（{@code applyDeltaChanges} 中的完整添加/编辑/删除流程）
     * 保持其内联编排，因为它们需要单独运行 {@code editItemsViaSelectInsert}
     * 并将其行数与 {@code applyDelta} 结果组合。
     *
     * @param editedItems 要在新版本中以相同稳定 ID 重新插入的条目
     * @param deletedIds 要从新版本中删除的稳定 ID（执行上游批更新的调用者
     *        在此处传递更新的 ID，以便从复制中排除）
     * @return 新版本中的总行数（从 applyDelta 传递）
     */
    private Mono<Long> applyEditDeleteWithLiveCount(
            UUID datasetId, UUID baseVersionId, UUID newVersionId,
            List<DatasetItem> editedItems, Set<UUID> deletedIds,
            String workspaceId) {

        Set<UUID> excludedFromCopy = new HashSet<>(deletedIds);
        editedItems.stream()
                .map(DatasetItem::datasetItemId)
                .filter(Objects::nonNull)
                .forEach(excludedFromCopy::add);

        return versionDao.countRowsInVersion(datasetId, baseVersionId, excludedFromCopy, null, workspaceId)
                .flatMap(unchangedCount -> {
                    List<UUID> unchangedUuids = generateUnchangedUuidsReversed(unchangedCount.intValue());
                    // 同数据集复制（无 copy-from 覆盖）— 源 = 目标 = (datasetId, baseVersionId)。
                    return versionDao.applyDelta(datasetId, newVersionId,
                            List.of(), // 此流程中无新增条目
                            editedItems,
                            deletedIds,
                            unchangedUuids,
                            Set.of(),
                            datasetId, baseVersionId);
                });
    }

    /**
     * 为数据集条目列表分配行 ID。
     * 创建以指定 UUID 作为行 ID 的新 DatasetItem 实例。
     *
     * @param items 要分配行 ID 的条目
     * @param uuids 用作行 ID 的 UUID（必须与条目大小相同）
     * @return 包含已分配行 ID 条目的新列表
     */
    private List<DatasetItem> withAssignedRowIds(List<DatasetItem> items, List<UUID> uuids) {
        return IntStream.range(0, items.size())
                .mapToObj(i -> items.get(i).toBuilder()
                        .id(uuids.get(i))
                        .build())
                .toList();
    }

    /**
     * 从现有版本删除条目（具有相同 batch_group_id 的后续批次）。
     * 类似于插入的 appendItemsToVersion，但改为移除条目。
     * 注意：仅支持通过显式条目 ID 删除。基于过滤器的删除无法批处理，
     * 因为客户端不知道哪些条目将被删除。
     */
    private Mono<Void> deleteItemsFromExistingVersion(
            Set<UUID> ids, UUID datasetId, UUID versionId, String workspaceId, String userName) {

        log.info("从现有版本 '{}' 删除数据集 '{}' 的条目", versionId, datasetId);

        // 批量删除仅支持显式 ID
        if (CollectionUtils.isEmpty(ids)) {
            log.warn("批量删除需要显式的条目 ID。批量删除不支持过滤器。");
            return Mono.empty();
        }

        return Mono.defer(() -> {
            // 获取当前版本以更新计数
            DatasetVersion currentVersion = versionService.getVersionById(workspaceId, datasetId, versionId);

            log.info(
                    "deleteItemsFromExistingVersion：当前版本 itemsTotal='{}'、itemsDeleted='{}'、versionId='{}'",
                    currentVersion.itemsTotal(), currentVersion.itemsDeleted(), versionId);

            log.info("deleteItemsFromExistingVersion：尝试移除 '{}' 条条目", ids.size());

            // 从版本中移除条目
            return versionDao.removeItemsFromVersion(datasetId, versionId, ids, workspaceId)
                    .flatMap(deletedCount -> {
                        log.info("deleteItemsFromExistingVersion：removeItemsFromVersion 返回 deletedCount='{}'",
                                deletedCount);

                        if (deletedCount == 0) {
                            log.info("版本 '{}' 中未删除任何条目", versionId);
                            return Mono.<Void>empty();
                        }

                        // 在 MySQL 中更新版本计数
                        return Mono.fromCallable(() -> {
                            updateVersionCountsForDelete(versionId, workspaceId, currentVersion,
                                    deletedCount.intValue(), userName);
                            log.info("已删除 '{}' 条条目（版本 '{}'），新总数 '{}'",
                                    deletedCount, versionId, currentVersion.itemsTotal() - deletedCount.intValue());
                            return null;
                        }).subscribeOn(Schedulers.boundedElastic());
                    })
                    .then();
        });
    }

    /**
     * 使用过滤器从现有版本删除条目。
     * 当 createVersion=false 时用于基于过滤器的删除。
     * 空或空过滤器列表表示"删除全部"（无过滤器 = 匹配所有）。
     */
    private Mono<Void> deleteItemsFromExistingVersionByFilters(UUID datasetId, UUID versionId,
            List<DatasetItemFilter> filters, String workspaceId, String userName) {

        log.info(
                "使用过滤器从现有版本 '{}' 删除数据集 '{}' 的条目（null 或空 = 删除全部）",
                versionId, datasetId);

        return Mono.defer(() -> {
            // 获取当前版本以更新计数
            DatasetVersion currentVersion = versionService.getVersionById(workspaceId, datasetId, versionId);

            log.info(
                    "deleteItemsFromExistingVersionByFilters：当前版本 itemsTotal='{}'、itemsDeleted='{}'、versionId='{}'",
                    currentVersion.itemsTotal(), currentVersion.itemsDeleted(), versionId);

            // 从版本中移除匹配过滤器的条目
            return versionDao.removeItemsFromVersionByFilters(datasetId, versionId, filters, workspaceId)
                    .flatMap(deletedCount -> {
                        log.info(
                                "deleteItemsFromExistingVersionByFilters：removeItemsFromVersionByFilters 返回 deletedCount='{}'",
                                deletedCount);

                        if (deletedCount == 0) {
                            log.info("版本 '{}' 中未删除任何条目", versionId);
                            return Mono.<Void>empty();
                        }

                        // 在 MySQL 中更新版本计数
                        return Mono.fromCallable(() -> {
                            updateVersionCountsForDelete(versionId, workspaceId, currentVersion,
                                    deletedCount.intValue(), userName);
                            log.info("已删除 '{}' 条条目（版本 '{}'），新总数 '{}'",
                                    deletedCount, versionId, currentVersion.itemsTotal() - deletedCount.intValue());
                            return null;
                        }).subscribeOn(Schedulers.boundedElastic());
                    })
                    .then();
        });
    }

    /**
     * 解析拥有给定稳定数据集条目 ID 的数据集 ID。
     * 验证所有条目属于同一数据集。
     *
     * @param itemIds 稳定的 dataset_item_id 值
     * @return 发出解析的 datasetId 的 Mono，如果未找到则为空
     */
    private Mono<UUID> resolveDatasetIdFromItemIds(Set<UUID> itemIds) {
        return versionDao.resolveDatasetIdsFromItemIds(itemIds)
                .flatMap(datasetIds -> {
                    if (datasetIds.isEmpty()) {
                        return Mono.empty();
                    }
                    if (datasetIds.size() > 1) {
                        log.error("条目 ID 跨越多个数据集：'{}'", datasetIds);
                        return Mono.error(new BadRequestException(
                                "Cannot operate on items across multiple datasets"));
                    }
                    return Mono.just(datasetIds.getFirst());
                });
    }

    private Mono<UUID> getDatasetIdOrResolveItemDatasetId(UUID datasetId, Set<UUID> ids) {
        if (datasetId != null) {
            return Mono.just(datasetId);
        } else if (CollectionUtils.isNotEmpty(ids)) {
            return resolveDatasetIdFromItemIds(ids);
        } else {
            return Mono.error(new BadRequestException("Must provide either datasetId or itemIds"));
        }
    }

    /**
     * 使用 batch_group_id 处理分组删除操作。
     * 如果 batch_group_id 存在版本，则将删除追加到该版本。
     * 否则，创建包含删除的新版本。
     * 在处理前将传入的行 ID 映射到稳定的 dataset_item_ids。
     *
     * @param batchGroupId 批次组 ID
     * @param ids          要删除的条目 ID（可能是 UI 行 ID）
     * @param datasetId    解析的数据集 ID
     * @param filters      可选过滤器
     * @param workspaceId  工作空间 ID
     * @param userName     用户名
     * @return 删除完成时完成的 Mono
     */
    private Mono<Void> handleGroupedDeletion(UUID batchGroupId, Set<UUID> ids, UUID datasetId,
            List<DatasetItemFilter> filters, String workspaceId, String userName) {
        // 延迟执行，直到调用方持有 withDatasetVersionLock 锁（OPIK-7264）。
        return Mono.defer(() -> {
            // 对于基于过滤器的删除，ids 为空 - 跳过映射并直接继续
            if (ids == null) {
                return proceedWithGroupedDeletion(batchGroupId, Set.of(), datasetId, filters, workspaceId, userName);
            }

            if (datasetId != null) {
                return proceedWithGroupedDeletion(batchGroupId, ids, datasetId, filters, workspaceId, userName);
            }

            return resolveDatasetIdFromItemIds(ids)
                    .flatMap(resolvedId -> {
                        log.info("已解析数据集 '{}'（batch_group_id '{}'）", resolvedId, batchGroupId);
                        return proceedWithGroupedDeletion(batchGroupId, ids, resolvedId, filters, workspaceId,
                                userName);
                    });
        });
    }

    /**
     * 在行 ID 映射到 dataset_item_ids 后继续进行分组删除。
     */
    private Mono<Void> proceedWithGroupedDeletion(UUID batchGroupId, Set<UUID> datasetItemIds, UUID datasetId,
            List<DatasetItemFilter> filters, String workspaceId, String userName) {
        return Mono.fromCallable(() -> versionService.findByBatchGroupId(batchGroupId, datasetId, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optionalVersion -> {
                    if (optionalVersion.isPresent()) {
                        // 版本存在 - 这是后续的删除批次
                        var existingVersion = optionalVersion.get();
                        log.info("删除 '{}' 条条目（现有版本 '{}'，batch_group_id '{}'）",
                                datasetItemIds.size(), existingVersion.id(), batchGroupId);
                        return deleteItemsFromExistingVersion(datasetItemIds, datasetId,
                                existingVersion.id(), workspaceId, userName);
                    } else {
                        // 没有此 batch_group_id 的版本 - 创建包含删除的新版本
                        log.info("使用 batch_group_id '{}' 为数据集 '{}' 创建包含 '{}' 条删除的新版本",
                                batchGroupId, datasetId, datasetItemIds.size());
                        return deleteItemsWithVersion(datasetItemIds, datasetId, filters, workspaceId, userName,
                                batchGroupId);
                    }
                });
    }

    /**
     * 使用 batch_group_id 处理分组插入操作。
     * 如果 batch_group_id 存在版本，则将条目追加到该版本。
     * 否则，创建包含条目的新版本。
     *
     * @param batchGroupId 批次组 ID
     * @param batch 要插入的条目批次
     * @param datasetId 数据集 ID
     * @param workspaceId 工作空间 ID
     * @param userName 用户名
     * @return 发出数据集版本的 Mono
     */
    private Mono<DatasetVersion> handleGroupedInsertion(UUID batchGroupId, DatasetItemBatch batch,
            UUID datasetId, String workspaceId, String userName) {
        return Mono.fromCallable(() -> versionService.findByBatchGroupId(batchGroupId, datasetId, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(optionalVersion -> {
                    if (optionalVersion.isPresent()) {
                        // 版本存在 - 将条目追加到该版本
                        var existingVersion = optionalVersion.get();
                        log.info("追加 '{}' 条条目到现有版本 '{}'（batch_group_id '{}'）",
                                batch.items().size(), existingVersion.id(), batchGroupId);
                        return insertItemsIntoVersion(batch, datasetId, existingVersion.id(), workspaceId, userName);
                    } else {
                        // 没有此 batch_group_id 的版本 - 创建新版本
                        log.info("使用 batch_group_id '{}' 为数据集 '{}' 创建新版本",
                                batchGroupId, datasetId);
                        return saveItemsWithVersion(batch, datasetId, batchGroupId)
                                .contextWrite(ctx -> ctx
                                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                                        .put(RequestContext.USER_NAME, userName));
                    }
                });
    }

    /**
     * 如果启用了延迟迁移，确保数据集已迁移到版本控制系统。
     * <p>
     * 此方法检查配置中是否启用了延迟迁移。如果是，则调用迁移服务
     * 确保数据集在继续 CRUD 操作之前已迁移。
     *
     * @param datasetId   要确保迁移的数据集 ID
     * @param workspaceId 工作空间 ID
     * @return 当数据集确保已迁移时完成的 Mono（如果延迟迁移禁用则立即完成）
     */
    private Mono<Void> ensureLazyMigration(UUID datasetId, String workspaceId) {
        if (!config.getDatasetVersioningMigration().isLazyEnabled()) {
            return Mono.empty();
        }

        log.debug("延迟迁移已启用，确保数据集 '{}' 已迁移", datasetId);
        return migrationService.ensureDatasetMigrated(datasetId, workspaceId);
    }

}
