package com.comet.opik.domain;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.comet.opik.api.Column;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItem.DatasetItemPage;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemEdit;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.domain.experiments.aggregations.AggregatedExperimentCounts;
import com.comet.opik.domain.experiments.aggregations.AggregationBranchCountsCriteria;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesDAO;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.FilterUtils;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.db.ZeroRowsRetryPolicy;
import com.comet.opik.utils.ErrorUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.JsonUtils.getJsonNodeFromStringWithFallback;
import static java.util.Collections.emptyList;

@ImplementedBy(DatasetItemVersionDAOImpl.class)
public interface DatasetItemVersionDAO {
    Mono<DatasetItemPage> getItems(DatasetItemSearchCriteria searchCriteria, int page, int size, UUID versionId);

    /**
     * 获取数据集条目及其关联的实验条目。
     * 此方法将数据集条目与实验条目、trace、反馈评分和评论进行连接。
     *
     * @param searchCriteria 包含实验 ID 的搜索条件
     * @param page 页码
     * @param size 每页大小
     * @param versionId 数据集版本 ID
     * @return 包含带实验条目的数据集条目分页的 Mono
     */
    Mono<DatasetItemPage> getItemsWithExperimentItems(DatasetItemSearchCriteria searchCriteria, int page, int size,
            String versionId);

    Mono<List<Column>> getExperimentItemsOutputColumns(UUID datasetId, Set<UUID> experimentIds);

    Mono<ProjectStats> getExperimentItemsStats(UUID datasetId, UUID versionId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters);

    Flux<DatasetItem> getItems(UUID datasetId, UUID versionId, int limit, UUID lastRetrievedId);

    Flux<DatasetItem> getItems(UUID datasetId, UUID versionId, int limit, UUID lastRetrievedId,
            @NonNull List<DatasetItemFilter> filters);

    Flux<DatasetItemIdAndHash> getItemIdsAndHashes(UUID datasetId, UUID versionId);

    /**
     * 统计 {@code itemIds} 中有多少个已经存在于给定版本中。
     * <p>
     * 用于插入路径的 {@link #getItemIdsAndHashes(UUID, UUID)} 的有界替代方案，插入路径只需要
     * 将传入批次分类为新增 vs. 已更新，而无需哈希。读取的行数随
     * 批次大小增长，而不是随版本大小增长。
     *
     * @param itemIds 要查找的稳定 ID；应由调用方去重。为 null 或空时
     *                不查询直接返回 {@code 0}。
     * @return 版本中存在的不同 {@code itemIds} 数量
     */
    Mono<Long> countExistingItemIds(UUID datasetId, UUID versionId, Set<UUID> itemIds);

    /**
     * 直接在 dataset_item_versions 内将条目从源版本复制到新的目标版本。
     * 每个复制的条目获得一个新的 UUIDv7，但保留相同的 dataset_item_id。
     * <p>
     * 可选地排除匹配过滤器的条目（匹配过滤器的条目将不会被复制）。
     * 如果 excludeFilters 为 null 或空，则复制所有条目。
     *
     * @param datasetId 数据集 ID
     * @param sourceDatasetId 要从中复制行的源数据集（通常等于 targetDatasetId；
     *                        OPIK-6696 允许它们不同，当调用方希望从稳定的上游数据集读取
     *                        结转行以避免多副本的读后写时）
     * @param sourceVersionId 要从中复制的源版本
     * @param targetDatasetId 目标数据集（插入的行携带此 dataset_id，而非源数据集的）
     * @param targetVersionId 要复制到的新版本 ID
     * @param excludeFilters 可选的要排除条目的过滤器（null 或空 = 复制全部）
     * @param uuids 为新条目 ID 预生成的 UUIDv7 池（至少应为预期条目数的 2 倍）
     * @return 复制的条目数量
     */
    Mono<Long> copyVersionItems(UUID sourceDatasetId, UUID sourceVersionId,
            UUID targetDatasetId, UUID targetVersionId,
            List<DatasetItemFilter> excludeFilters, List<UUID> uuids);

    /**
     * 应用增量变更以创建新的数据集版本。
     * 新增和已编辑的条目应已设置其行 ID（id 字段）。
     * 未更改的条目将使用 unchangedUuids 中的 UUID 复制。
     *
     * @param datasetId         数据集 ID
     * @param datasetId         其版本正在被变更的数据集（目标）
     * @param newVersionId      要创建的新版本 ID
     * @param addedItems        要添加的条目（id 已设置）
     * @param editedItems       要编辑的条目（id 已设置）
     * @param deletedIds        要删除的稳定 dataset_item_ids
     * @param unchangedUuids    要分配给未更改条目的 UUID（按正确顺序预生成）
     * @param additionalExcludeIds  要从复制中排除的额外稳定 ID（运行了单独
     *                              编辑/插入步骤的调用方将这些 ID 传到这里）
     * @param copyFromDatasetId 要从中读取结转行的数据集。OPIK-6696：当它与
     *                          {@code datasetId} 不同时，COPY 从（通常是稳定的）源
     *                          版本读取，而不是从目标刚刚创建的前一个版本读取，
     *                          从而避免多副本的读后写窗口。
     * @param copyFromVersionId {@code copyFromDatasetId} 中要从中读取结转行的版本
     * @return 新版本中的条目数量
     */
    Mono<Long> applyDelta(UUID datasetId, UUID newVersionId,
            List<DatasetItem> addedItems, List<DatasetItem> editedItems, Set<UUID> deletedIds,
            List<UUID> unchangedUuids, Set<UUID> additionalExcludeIds,
            UUID copyFromDatasetId, UUID copyFromVersionId);

    /**
     * 通过 INSERT...SELECT 编辑条目。从
     * {@code (sourceDatasetId, sourceVersionId)} 读取每个条目的基础行，并将编辑后的行插入
     * {@code (targetDatasetId, newVersionId)}。OPIK-6696：源坐标可以指向稳定的
     * 上游版本，以避免目标的读后写窗口。
     */
    Mono<Long> editItemsViaSelectInsert(UUID sourceDatasetId, UUID sourceVersionId,
            UUID targetDatasetId, UUID newVersionId,
            List<DatasetItemEdit> editedItems, List<UUID> newRowIds);

    /**
     * 对基础版本中的条目应用批量更新，在新版本中创建更新后的副本。
     * 这是一个高效的数据库端操作，使用带条件更新的 INSERT ... SELECT。
     * <p>
     * 既支持基于 ID 的更新（通过 batchUpdate.ids()），也支持基于过滤器的更新（通过 batchUpdate.filters()）。
     * 更新中仅应用非 null 字段。
     *
     * @param datasetId 数据集 ID
     * @param baseVersionId 要从中复制的基础版本
     * @param newVersionId 要插入到的新版本
     * @param batchUpdate 包含 ID 或过滤器以及要应用的更新的批量更新
     * @param uuids 为新行 ID 预生成的 UUIDv7 池
     * @return 更新的条目数量
     */
    Mono<Long> batchUpdateItems(UUID datasetId, UUID baseVersionId, UUID newVersionId,
            DatasetItemBatchUpdate batchUpdate, List<UUID> uuids);

    /**
     * 直接将条目插入新版本，不从任何基础版本复制。
     * <p>
     * 对于传入此方法的条目：
     * - 使用 {@code datasetItemId} 字段作为稳定 ID（跨版本维护）
     * - {@code id} 字段被忽略（行 ID 在内部生成）
     *
     * @param datasetId 数据集 ID
     * @param versionId 要插入到的版本 ID
     * @param items 要插入的条目
     * @param workspaceId 工作区 ID
     * @param userName 用户名
     * @return 插入的条目数量
     */
    Mono<Long> insertItems(UUID datasetId, UUID versionId, List<DatasetItem> items,
            String workspaceId, String userName);

    /**
     * 从 ClickHouse 中现有版本移除条目。
     * 用于多个批次共享同一 batch_group_id 的批量删除操作。
     *
     * @param datasetId 数据集 ID
     * @param versionId 要从中移除条目的版本 ID
     * @param itemIds 要移除的 dataset_item_id 值集合
     * @param workspaceId 工作区 ID
     * @return 移除的条目数量
     */
    Mono<Long> removeItemsFromVersion(UUID datasetId, UUID versionId, Set<UUID> itemIds, String workspaceId);

    /**
     * 基于过滤器从 ClickHouse 中现有版本移除条目。
     * 用于基于过滤器的删除操作，其中匹配过滤器的条目应被移除。
     * null 或空过滤器列表表示“全部删除”（无过滤器 = 匹配所有）。
     *
     * @param datasetId 数据集 ID
     * @param versionId 要从中移除条目的版本 ID
     * @param filters 要匹配以移除条目的过滤器（null 或空 = 删除全部）
     * @param workspaceId 工作区 ID
     * @return 移除的条目数量
     */
    Mono<Long> removeItemsFromVersionByFilters(UUID datasetId, UUID versionId, List<DatasetItemFilter> filters,
            String workspaceId);

    /**
     * 通过查找所有版本来解析哪个数据集包含给定条目。
     * 用于只知道条目 ID 时的初始查找。
     * 注意：此方法跨版本查询以找出哪个数据集包含该条目。
     * 它仅用于数据集解析——实际数据检索应使用版本特定的方法。
     *
     * @param datasetItemId 稳定条目 ID（dataset_item_id）
     * @return 发出数据集 ID 的 Mono，若未找到条目则为空
     */
    Mono<UUID> resolveDatasetIdFromItemId(UUID datasetItemId);

    /**
     * 在单个查询中从一组 dataset_item_ids 解析数据集 ID。
     * 返回找到的第一个有效数据集 ID。
     * 这比多次调用 resolveDatasetIdFromItemId 更高效。
     *
     * @param datasetItemIds 稳定条目 ID 集合（dataset_item_ids）
     * @return 发出找到的不同数据集 ID 列表的 Mono，若不存在则为空列表
     */
    Mono<List<UUID>> resolveDatasetIdsFromItemIds(Set<UUID> datasetItemIds);

    /**
     * 从特定版本按其 dataset_item_id 获取条目。
     *
     * @param datasetId 数据集 ID
     * @param versionId 要从中检索条目的版本 ID
     * @param datasetItemId 稳定条目 ID（dataset_item_id）
     * @return 发出 DatasetItem 的 Mono，若未找到则为空
     */
    Mono<DatasetItem> getItemByDatasetItemId(UUID datasetId, UUID versionId, UUID datasetItemId);

    /**
     * 按其 ID（id 字段）获取条目。
     * 当前端发送来自 API 响应的 ID 时使用。
     *
     * @param id 条目 ID（id 字段值）
     * @return 发出 DatasetItem 的 Mono，若未找到则为空
     */
    Mono<DatasetItem> getItemById(UUID id);

    Mono<DatasetItem> getItemById(UUID id, UUID datasetVersionId);

    /**
     * 获取稳定数据集条目 ID（dataset_item_versions 的 dataset_item_id 字段）的工作区 ID。
     * 用于验证数据集条目属于正确的工作区。
     * 有意地不按工作区限定作用域，以便跨工作区条目返回其真实 workspace_id。
     *
     * @param datasetItemIds 稳定 dataset_item_id 值
     * @return 发出工作区与资源 ID 对列表的 Mono
     */
    Mono<List<WorkspaceAndResourceId>> getDatasetItemWorkspace(Set<UUID> datasetItemIds);

    record DatasetItemPolicyEntry(UUID datasetVersionId, UUID datasetItemId, ExecutionPolicy policy) {
    }

    Flux<DatasetItemPolicyEntry> getExecutionPoliciesByDatasetItemIds(Set<UUID> datasetItemIds,
            Set<UUID> datasetVersionIds);

    /**
     * 软删除特定数据集版本中的所有条目。
     *
     * @param datasetId 数据集 ID
     * @param versionId 版本 ID
     * @param workspaceId 工作区 ID
     * @return 发出已删除行数的 Mono
     */
    Mono<Long> deleteItemsFromVersion(UUID datasetId, UUID versionId, String workspaceId);

    /**
     * 将指定数据集的所有条目从旧版 dataset_items 表复制到 dataset_item_versions。
     * 保留所有原始时间戳和用户信息。
     *
     * @param datasetId 数据集 ID
     * @param versionId 版本 ID（对于版本 1 应等于 datasetId）
     * @param workspaceId 工作区 ID
     * @return 发出已复制行数的 Mono
     */
    Mono<Long> copyItemsFromLegacy(UUID datasetId, UUID versionId, String workspaceId);

    /**
     * 统计特定数据集版本中的条目。
     *
     * @param datasetId 数据集 ID
     * @param versionId 版本 ID
     * @param workspaceId 工作区 ID
     * @return 发出条目计数的 Mono
     */
    Mono<Long> countItemsInVersion(UUID datasetId, UUID versionId, String workspaceId);

    /**
     * 在应用与从基础版本复制路径相同的排除语义后，统计版本中不同的
     * {@code dataset_item_id}：排除一组稳定条目 ID 和/或排除
     * 匹配一组过滤器的行。
     *
     * <p>这是传给 {@link #copyVersionItems} 和 {@link #applyDelta} 的 UUID 池大小的事实来源。
     * 取代了之前依赖 MySQL 存储的 {@code items_total}，后者可能偏离
     * 实际的 ClickHouse 行数并静默截断复制（OPIK-6390）。
     *
     * @param datasetId 数据集 ID
     * @param versionId 源版本 ID
     * @param excludedIds 要排除的稳定 {@code dataset_item_id}（删除 + 编辑）；可为空
     * @param excludeFilters 匹配行应被排除的过滤器；可为 null/空
     * @param workspaceId 工作区 ID
     * @return 发出将被复制的行数的 Mono
     */
    Mono<Long> countRowsInVersion(UUID datasetId, UUID versionId, Set<UUID> excludedIds,
            List<DatasetItemFilter> excludeFilters, String workspaceId);

    /**
     * 在单个查询中统计多个数据集版本的条目。
     * 用于 items_total 字段的批量迁移。
     * 使用 workspace_id、dataset_id 和 dataset_version_id 根据表的排序键
     * (workspace_id, dataset_id, dataset_version_id, id) 优化查询。
     *
     * @param versions 要统计条目的版本信息列表（workspace_id、dataset_id、version_id）
     * @return 发出每个版本条目计数的 Flux
     */
    Flux<DatasetVersionItemsCount> countItemsInVersionsBatch(List<DatasetVersionInfo> versions);

}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemVersionDAOImpl implements DatasetItemVersionDAO {

    private static final String DATASET_ITEM_VERSIONS = "dataset_item_versions";
    private static final String CLICKHOUSE = "Clickhouse";

    private static final List<FilterQueryBuilder.FilterStrategyParam> FILTER_STRATEGY_PARAMS = List.of(
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.DATASET_ITEM, "dataset_item_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.EXPERIMENT_ITEM, "experiment_item_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES, "feedback_scores_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES_IS_EMPTY,
                    "feedback_scores_empty_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES_AGGREGATED,
                    "feedback_scores_filters_agg"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
                    "feedback_scores_empty_filters_agg"));

    private static final List<FilterStrategy> BIND_STRATEGIES = List.of(
            FilterStrategy.DATASET_ITEM,
            FilterStrategy.EXPERIMENT_ITEM,
            FilterStrategy.FEEDBACK_SCORES,
            FilterStrategy.FEEDBACK_SCORES_IS_EMPTY,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY);

    private static final String SELECT_ITEM_IDS_AND_HASHES = """
            SELECT
                dataset_item_id,
                data_hash,
                tags,
                evaluators_hash,
                execution_policy_hash,
                description_hash
            FROM dataset_item_versions
            WHERE dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND workspace_id = :workspace_id
            ORDER BY dataset_item_id DESC, last_updated_at DESC
            LIMIT 1 BY dataset_item_id
            """;

    /**
     * 统计给定稳定 ID 中有多少个已经存在于版本中。
     * <p>
     * 前三个谓词是表排序键 {@code (workspace_id, dataset_id, dataset_version_id, id)}
     * 的前缀，将范围缩小到该版本的 granule；
     * {@code dataset_item_id} 不在排序键中，而是由迁移 {@code 000074} 中添加的
     * bloom-filter 和 minmax 跳过索引来服务。这使读取的行数
     * 受传入批次限制，而不是受正在写入的版本大小限制。
     */
    private static final String COUNT_EXISTING_ITEM_IDS = """
            SELECT count(DISTINCT dataset_item_id) AS count
            FROM dataset_item_versions
            WHERE workspace_id = :workspace_id
            AND dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND dataset_item_id IN :itemIds
            """;

    private static final String SELECT_DATASET_ITEM_VERSIONS = """
            SELECT
                dataset_item_id AS id,
                dataset_id,
                <if(truncate)> mapApply((k, v) -> (k, substring(replaceRegexpAll(v, '<truncate>', '"[image]"'), 1, <truncationSize>)), data) as data <else> data <endif>,
                description,
                trace_id,
                span_id,
                source,
                tags,
                evaluators,
                execution_policy,
                item_created_at as created_at,
                item_last_updated_at as last_updated_at,
                item_created_by as created_by,
                item_last_updated_by as last_updated_by,
                null AS experiment_items_array
            FROM dataset_item_versions
            WHERE dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND workspace_id = :workspace_id
            <if(lastRetrievedId)>AND dataset_item_id \\< :lastRetrievedId<endif>
            <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            ORDER BY dataset_item_id DESC, last_updated_at DESC
            LIMIT 1 BY dataset_item_id
            <if(lastRetrievedId)>
            LIMIT :limit
            <else>
            LIMIT :limit OFFSET :offset
            <endif>
            """;

    private static final String SELECT_DATASET_ITEM_VERSIONS_COUNT = """
            SELECT count(DISTINCT dataset_item_id) as count
            FROM dataset_item_versions
            WHERE dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND workspace_id = :workspace_id
            <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            """;

    private static final String DELETE_ITEMS_FROM_VERSION = """
            DELETE FROM dataset_item_versions
            WHERE dataset_id = :dataset_id
              AND dataset_version_id = :version_id
              AND workspace_id = :workspace_id
              <if(item_ids)>AND dataset_item_id IN (:item_ids)<endif>
              <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            """;

    private static final String COUNT_ITEMS = """
            SELECT count(DISTINCT dataset_item_id) as count
            FROM dataset_item_versions
            WHERE dataset_id = :dataset_id
              AND dataset_version_id = :version_id
              AND workspace_id = :workspace_id
              <if(item_ids)>AND dataset_item_id IN :item_ids<endif>
              <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            """;

    // OPIK-6390：统计在应用与 COPY_VERSION_ITEMS 相同的排除语义后
    // 将从源版本复制的不同稳定条目数。用于从实际的 ClickHouse 行数
    // 而非（易漂移的）MySQL items_total 来确定 UUID 池的大小。
    private static final String COUNT_ROWS_IN_VERSION = """
            SELECT count(DISTINCT dataset_item_id) as count
            FROM dataset_item_versions
            WHERE dataset_id = :dataset_id
              AND dataset_version_id = :version_id
              AND workspace_id = :workspace_id
              <if(exclude_filters)>AND NOT (<exclude_filters>)<endif>
              <if(exclude_ids)>AND dataset_item_id NOT IN :excluded_ids<endif>
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * 统计带实验条目的数据集条目。{@code slim_count} 分支将计数
     * 路由经过 {@code experiment_item_aggregates}；旧版分支为搜索和仅 raw 的输入保留完整的 CTE 链。
     * 两者都保留了 OPIK-6177 稳定 ID 解析的形态。
     *
     * <p>slim 分支的 {@code dataset_items_filtered_ids} CTE 与
     * {@link #SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS} 的 {@code push_top_limit} 分支中的对应 CTE
     * 相同，只是少了 {@code dataset_version_id} 谓词（slim 路径的作用域中没有
     * {@code experiment_aggregated_scope_ids}）。请保持两处调用点的列列表和数据集作用域对齐。
     */
    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT = """
            <if(slim_count)>
            <if(dataset_item_filters)>
            WITH dataset_items_filtered_ids AS (
                SELECT id, row_id
                FROM (
                    SELECT
                        dataset_item_id AS id,
                        id AS row_id,
                        data,
                        description,
                        source,
                        trace_id,
                        span_id,
                        tags,
                        evaluators,
                        execution_policy,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    FROM dataset_item_versions FINAL
                    WHERE workspace_id = :workspace_id
                      AND dataset_id = :datasetId
                ) AS resolved
                WHERE <dataset_item_filters>
            )
            <endif>
            SELECT count(DISTINCT
                if(notEmpty(lookup_div.dataset_item_id),
                   lookup_div.dataset_item_id,
                   eia.dataset_item_id)
            ) AS count
            FROM experiment_item_aggregates AS eia FINAL
            LEFT JOIN (
                SELECT id, workspace_id, dataset_item_id
                FROM dataset_item_versions FINAL
                WHERE workspace_id = :workspace_id
                  AND dataset_id = :datasetId
            ) AS lookup_div
                ON lookup_div.workspace_id = eia.workspace_id
                AND lookup_div.id = eia.dataset_item_id
            WHERE eia.workspace_id = :workspace_id
            AND eia.experiment_id IN (
                SELECT id
                FROM experiment_aggregates
                WHERE workspace_id = :workspace_id
                  AND dataset_id = :datasetId
                  <if(experiment_ids)>AND id IN :experiment_ids<endif>
            )
            <if(experiment_item_filters)>AND <experiment_item_filters><endif>
            <if(feedback_scores_filters_agg)>AND <feedback_scores_filters_agg><endif>
            <if(feedback_scores_empty_filters_agg)>AND <feedback_scores_empty_filters_agg><endif>
            <if(dataset_item_filters)>AND eia.dataset_item_id IN (SELECT arrayJoin([id, row_id]) FROM dataset_items_filtered_ids)<endif>
            SETTINGS log_comment = '<log_comment>'
            <else>
            WITH experiment_aggregated_scope_ids AS (
                SELECT
                    id,
                    COALESCE(nullIf(dataset_version_id, ''), :versionId) AS resolved_dataset_version_id
                FROM experiment_aggregates FINAL
                WHERE workspace_id = :workspace_id
                AND dataset_id = :datasetId
                <if(experiment_ids)>AND id IN :experiment_ids<endif>
            ),
            experiments_resolved AS (
                SELECT
                    id,
                    COALESCE(nullIf(dataset_version_id, ''), :versionId) AS resolved_dataset_version_id
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND dataset_id = :datasetId
                <if(experiment_ids)>AND id IN :experiment_ids<endif>
                AND id NOT IN (SELECT id FROM experiment_aggregated_scope_ids)
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ),
            experiment_items_scope AS (
            	SELECT
            	    ei.id AS id,
            	    ei.experiment_id AS experiment_id,
            	    ei.dataset_item_id AS dataset_item_id,
            	    ei.trace_id AS trace_id,
            	    ei.workspace_id AS workspace_id,
            	    ei.created_at AS created_at,
            	    ei.last_updated_at AS last_updated_at,
            	    ei.created_by AS created_by,
            	    ei.last_updated_by AS last_updated_by,
            	    ei.project_id AS project_id,
            	    ei.execution_policy AS execution_policy,
            	    e.resolved_dataset_version_id AS resolved_dataset_version_id,
            	    if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, ei.dataset_item_id) AS stable_dataset_item_id
            	FROM experiment_items ei
            	INNER JOIN experiments_resolved e ON e.id = ei.experiment_id
            	LEFT JOIN dataset_item_versions AS lookup_div FINAL
            	    ON lookup_div.workspace_id = ei.workspace_id
            	    AND lookup_div.id = ei.dataset_item_id
            	WHERE ei.workspace_id = :workspace_id
            	<if(experiment_ids)>AND ei.experiment_id IN :experiment_ids<endif>
            	ORDER BY (ei.workspace_id, ei.experiment_id, ei.dataset_item_id, ei.trace_id, ei.id) DESC, ei.last_updated_at DESC
            	LIMIT 1 BY ei.id
            ),
            experiment_items_trace_scope AS (
                SELECT DISTINCT ei.trace_id
                FROM experiment_items ei
                INNER JOIN experiments_resolved e ON e.id = ei.experiment_id
                WHERE ei.workspace_id = :workspace_id
                <if(experiment_ids)>AND ei.experiment_id IN :experiment_ids<endif>
            ),
            trace_ids AS (
                SELECT
                    id
                FROM traces
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND id IN (SELECT DISTINCT trace_id FROM experiment_items_trace_scope)
            ),
            feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           feedback_scores.last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'trace'
                      AND workspace_id = :workspace_id
                      <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                      AND entity_id IN (SELECT trace_id FROM experiment_items_trace_scope)
                    UNION ALL
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           author,
                           source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'trace'
                      AND workspace_id = :workspace_id
                      <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                      AND entity_id IN (SELECT trace_id FROM experiment_items_trace_scope)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ),
            feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_deduped fsc
                INNER JOIN trace_ids td ON td.id = fsc.entity_id
                GROUP BY workspace_id, project_id, entity_id, name
            )
            <if(feedback_scores_empty_filters)>
            , fsc AS (
                SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                FROM feedback_scores_final
                GROUP BY entity_id
                HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , dataset_items_resolved AS (
                SELECT
                    div_dedup.dataset_item_id AS id,
                    div_dedup.id AS row_id,
                    div_dedup.dataset_version_id AS dataset_version_id,
                    div_dedup.data AS data,
                    div_dedup.source AS source,
                    div_dedup.trace_id AS trace_id,
                    div_dedup.span_id AS span_id,
                    div_dedup.tags AS tags,
                    div_dedup.created_at AS created_at,
                    div_dedup.last_updated_at AS last_updated_at,
                    div_dedup.created_by AS created_by,
                    div_dedup.last_updated_by AS last_updated_by
                FROM (
                    SELECT *
                    FROM dataset_item_versions
                    WHERE workspace_id = :workspace_id
                    AND dataset_id = :datasetId
                    AND dataset_version_id IN (SELECT resolved_dataset_version_id FROM experiments_resolved)
                    ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY dataset_item_id
                ) AS div_dedup
            )
            , experiment_items_final AS (
            	SELECT
            	    ei.id AS id,
            	    ei.experiment_id AS experiment_id,
            	    ei.dataset_item_id AS dataset_item_id,
            	    ei.stable_dataset_item_id AS stable_dataset_item_id,
            	    ei.trace_id AS trace_id,
            	    ei.workspace_id AS workspace_id,
            	    ei.created_at AS created_at,
            	    ei.last_updated_at AS last_updated_at,
            	    ei.created_by AS created_by,
            	    ei.last_updated_by AS last_updated_by,
            	    ei.project_id AS project_id,
            	    ei.execution_policy AS execution_policy,
            	    ei.resolved_dataset_version_id AS resolved_dataset_version_id
            	FROM experiment_items_scope ei
            	WHERE ei.workspace_id = :workspace_id
            	<if(experiment_item_filters || feedback_scores_filters || feedback_scores_empty_filters || dataset_item_filters)>
                AND ei.trace_id IN (
                    SELECT
                        id
                    FROM (
                       SELECT
                            id
                       FROM (
                            SELECT
                                id,
                                duration,
                                output,
                                input,
                                metadata
                           FROM traces
                           WHERE workspace_id = :workspace_id
                           <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                           AND id IN (SELECT trace_id FROM experiment_items_scope)
                           ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                           LIMIT 1 BY id
                       )
                       <if(experiment_item_filters)>
                       WHERE <experiment_item_filters>
                       <endif>
                    ) t
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = t.id
                    <endif>
                    WHERE 1=1
                    <if(feedback_scores_filters)>
                    AND t.id IN (
                        SELECT
                            entity_id
                        FROM feedback_scores_final
                        GROUP BY entity_id
                        HAVING <feedback_scores_filters>
                    )
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    AND fsc.feedback_scores_count = 0
                    <endif>
                )
                <endif>
                <if(dataset_item_filters)>
                AND ei.stable_dataset_item_id IN (SELECT id FROM dataset_items_resolved WHERE <dataset_item_filters>)
                <endif>
            	ORDER BY id DESC, last_updated_at DESC
            ), dataset_items_agg_resolved AS (
                SELECT
                    div_dedup.dataset_item_id AS id,
                    div_dedup.id AS row_id,
                    div_dedup.dataset_version_id AS dataset_version_id,
                    div_dedup.data AS data,
                    div_dedup.source AS source,
                    div_dedup.trace_id AS trace_id,
                    div_dedup.span_id AS span_id,
                    div_dedup.tags AS tags,
                    div_dedup.created_at AS created_at,
                    div_dedup.last_updated_at AS last_updated_at,
                    div_dedup.created_by AS created_by,
                    div_dedup.last_updated_by AS last_updated_by
                FROM (
                    SELECT *
                    FROM dataset_item_versions
                    WHERE workspace_id = :workspace_id
                    AND dataset_id = :datasetId
                    AND dataset_version_id IN (SELECT resolved_dataset_version_id FROM experiment_aggregated_scope_ids)
                    ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY dataset_item_id
                ) AS div_dedup
            ),
            <if(dataset_item_filters)>
            lookup_for_count AS (
                SELECT
                    arrayJoin([div.id, latest_passing.id]) AS lookup_id
                FROM (
                    SELECT id FROM dataset_items_agg_resolved WHERE <dataset_item_filters>
                ) AS latest_passing
                INNER JOIN dataset_item_versions AS div FINAL
                    ON div.workspace_id = :workspace_id
                    AND div.dataset_id = :datasetId
                    AND div.dataset_version_id IN (SELECT resolved_dataset_version_id FROM experiment_aggregated_scope_ids)
                    AND div.dataset_item_id = latest_passing.id
            ),
            <endif>
            item_agg_count AS (
                SELECT
                    eia.id AS id,
                    eia.experiment_id AS experiment_id,
                    if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, eia.dataset_item_id) AS stable_dataset_item_id,
                    eia.trace_id AS trace_id,
                    eia.input AS input,
                    eia.output AS output
                FROM experiment_item_aggregates AS eia FINAL
                LEFT JOIN dataset_item_versions AS lookup_div FINAL
                    ON lookup_div.workspace_id = eia.workspace_id
                    AND lookup_div.id = eia.dataset_item_id
                WHERE eia.workspace_id = :workspace_id
                AND eia.experiment_id IN (SELECT id FROM experiment_aggregated_scope_ids)
                <if(experiment_item_filters)> AND <experiment_item_filters> <endif>
                <if(feedback_scores_filters_agg)> AND <feedback_scores_filters_agg> <endif>
                <if(feedback_scores_empty_filters_agg)> AND <feedback_scores_empty_filters_agg> <endif>
                <if(dataset_item_filters)>
                AND eia.dataset_item_id IN (SELECT lookup_id FROM lookup_for_count)
                <endif>
                -- all duplicated rows share the same stable_dataset_item_id, so arbitrary pick is safe
                LIMIT 1 BY eia.id
            )
            SELECT COUNT(DISTINCT di_id) AS count
            FROM (
                <if(has_aggregated)>
                SELECT eia.stable_dataset_item_id AS di_id
                FROM item_agg_count AS eia
                <if(search)>
                LEFT JOIN dataset_items_agg_resolved di ON di.id = eia.stable_dataset_item_id
                WHERE multiSearchAnyCaseInsensitive(toString(COALESCE(di.data, map())), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(eia.input), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(eia.output), :searchTerms)
                <endif>
                <endif>

                <if(has_aggregated)><if(has_raw)>UNION ALL<endif><endif>

                <if(has_raw)>
                SELECT ei.stable_dataset_item_id AS di_id
                FROM experiment_items_final AS ei
                LEFT JOIN dataset_items_resolved AS di ON di.id = ei.stable_dataset_item_id
                <if(search)>
                LEFT JOIN (
                    SELECT
                        id,
                        input,
                        output
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS tfs ON ei.trace_id = tfs.id
                WHERE multiSearchAnyCaseInsensitive(toString(COALESCE(di.data, map())), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(tfs.input), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(tfs.output), :searchTerms)
                <endif>
                <endif>
            )
            <endif>
            """;

    // 从 trace 输出中提取实验条目视图列的查询
    private static final String SELECT_EXPERIMENT_ITEMS_OUTPUT_COLUMNS = """
            WITH experiments_resolved AS (
                SELECT DISTINCT
                    id
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND dataset_id = :datasetId
                <if(experiment_ids)>AND id IN :experiment_ids<endif>
            ),
            experiment_items_scope AS (
                SELECT DISTINCT
                    ei.trace_id
                FROM experiment_items ei
                WHERE ei.workspace_id = :workspace_id
                <if(experiment_ids)>AND ei.experiment_id IN (SELECT id FROM experiments_resolved)<endif>
            )
            SELECT
                mapFromArrays(
                    groupArray(key),
                    groupArray(types)
                ) AS columns
            FROM (
                SELECT
                    tupleElement(key_type, 1) AS key,
                    arrayDistinct(groupArray(tupleElement(key_type, 2))) AS types
                FROM (
                    SELECT
                        output_keys
                    FROM traces FINAL
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT trace_id FROM experiment_items_scope)
                ) AS traces_with_keys
                ARRAY JOIN output_keys AS key_type
                GROUP BY key
            )
            """;

    // 从 trace 中获取实验条目目标 project_ids 的查询（单独执行以减少表扫描）
    private static final String SELECT_TARGET_PROJECTS = """
            WITH experiments_scope AS (
                SELECT id
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND dataset_id = :datasetId
                <if(experiment_ids)>AND id IN :experiment_ids<endif>
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ),
            experiment_items_trace_scope AS (
                SELECT DISTINCT ei.trace_id
                FROM experiment_items ei
                WHERE ei.workspace_id = :workspace_id
                AND ei.experiment_id IN (SELECT id FROM experiments_scope)
                <if(experiment_ids)>AND ei.experiment_id IN :experiment_ids<endif>
            )
            SELECT DISTINCT project_id
            FROM traces
            WHERE workspace_id = :workspace_id
            AND id IN (SELECT DISTINCT trace_id FROM experiment_items_trace_scope)
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取版本化数据集条目及其关联的实验条目。
     *
     * <p><b>OPIK-6177 稳定 ID 解析。</b>{@code experiment_items_scope} 在
     * {@code lookup_div.id = ei.dataset_item_id} 上 LEFT JOIN
     * {@code dataset_item_versions} 以投影出一个
     * {@code stable_dataset_item_id}。这解决了旧版行（OPIK-4518 BE 切换之前）中
     * {@code ei.dataset_item_id} 是按版本的 {@code dataset_item_versions.id} 的问题；对于现代
     * 行，它已经是稳定 ID，JOIN 会缺失，通过
     * {@code if(notEmpty(...))} 回退到原始值。
     *
     * <p>JOIN 直接使用 {@code dataset_item_versions} 表——而不是基于 CTE 的查找。
     * 在 ClickHouse 中，基于 CTE 的 LEFT JOIN 会在删除级联场景中丢失行（已知的
     * 分析器行为；直接表引用可正确工作）。
     *
     * <p><b>{@code lookup_for_count} CTE。</b>由聚合分支（count +
     * 行 {@code !push_top_limit}）在 DI 过滤器活跃时使用，用于构建对跳过索引友好的 IN 列表：
     * 它先按 {@code <dataset_item_filters>} 缩小范围，然后 INNER JOIN
     * {@code dataset_item_versions FINAL}，条件为 {@code div.dataset_item_id = latest_passing.id}，
     * 并发出 {@code arrayJoin([div.id, latest_passing.id])}，使 IN 列表覆盖每个
     * 版本的 {@code row_id}（引用旧版本 {@code row_id} 的旧版 EIA）
     * 加上稳定 ID。按 {@code dataset_item_id} 缩小的内连接使得
     * {@code idx_experiment_item_aggregates_dataset_item_id} 上的
     * {@code bloom_filter} 跳过索引能像 #6567 那样剪枝。
     * {@code push_top_limit} 分支不使用此 CTE——它通过
     * {@code top_dataset_items}（已通过 {@code lookup_div} 完成稳定 ID 解析）来过滤。
     *
     * <p><b>{@code dataset_item_versions} 读取上的 {@code FINAL} 是承重关键。</b>
     * 该表是一个 {@code ReplicatedReplacingMergeTree}，按
     * {@code (workspace_id, dataset_id, dataset_version_id, id)} 排序，以 {@code last_updated_at}
     * 作为版本列。upsert 流程（PUT {@code /datasets/items} →
     * {@code BATCH_INSERT_ITEMS}）在客户端用变化的 {@code data} / {@code description} /
     * {@code tags} / {@code evaluators} / {@code execution_policy} 两次 PUT 相同的条目 ID 时，
     * 会重新 INSERT 相同的 {@code (ws, ds, dvid, id)} 元组（端点契约
     * 规定：“每个条目的 id 是稳定标识符和 upsert 键”）。合并前的重复
     * 是常态；需要 {@code FINAL} 才能让读取只看到每个 PK 的最新行。
     * 已经执行 {@code LIMIT 1 BY dataset_item_id ORDER BY dvid DESC,
     * last_updated_at DESC} 的子查询会在读取时去重，因此内部
     * 扫描不需要 {@code FINAL}。
     *
     * <p>聚合/raw UNION 上的外层 SELECT 跨分支去重，因此一个出现在两个分支中的
     * 稳定 ID 会产生一行带有扁平化 {@code experiment_items_array} 的结果。
     * {@code dataset_version_id} 上的 {@code argMax} 决胜规则与单分支的
     * “最新版本胜出”语义一致（{@code dataset_items_(aggr_)resolved} 按 {@code dataset_version_id} DESC 排序）。
     */
    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS = """
            WITH experiment_aggregated_scope_ids AS (
                SELECT
                    id,
                    COALESCE(nullIf(dataset_version_id, ''), :versionId) AS resolved_dataset_version_id
                FROM experiment_aggregates FINAL
                WHERE workspace_id = :workspace_id
                AND dataset_id = :datasetId
                <if(experiment_ids)>AND id IN :experiment_ids<endif>
            ), experiments_resolved AS (
                SELECT
                    *,
                    COALESCE(nullIf(dataset_version_id, ''), :versionId) AS resolved_dataset_version_id
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND dataset_id = :datasetId
                <if(experiment_ids)>AND id IN :experiment_ids<endif>
                AND id NOT IN (SELECT id FROM experiment_aggregated_scope_ids)
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_items_scope AS (
            	SELECT
            	    ei.id AS id,
            	    ei.experiment_id AS experiment_id,
            	    ei.dataset_item_id AS dataset_item_id,
            	    ei.trace_id AS trace_id,
            	    ei.workspace_id AS workspace_id,
            	    ei.created_at AS created_at,
            	    ei.last_updated_at AS last_updated_at,
            	    ei.created_by AS created_by,
            	    ei.last_updated_by AS last_updated_by,
            	    ei.project_id AS project_id,
            	    ei.execution_policy AS execution_policy,
            	    e.resolved_dataset_version_id AS resolved_dataset_version_id,
            	    if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, ei.dataset_item_id) AS stable_dataset_item_id
            	FROM experiment_items ei
            	INNER JOIN experiments_resolved e ON e.id = ei.experiment_id
            	LEFT JOIN dataset_item_versions AS lookup_div FINAL
            	    ON lookup_div.workspace_id = ei.workspace_id
            	    AND lookup_div.id = ei.dataset_item_id
            	WHERE ei.workspace_id = :workspace_id
            	<if(experiment_ids)>AND ei.experiment_id IN :experiment_ids<endif>
            	ORDER BY (ei.workspace_id, ei.experiment_id, ei.dataset_item_id, ei.trace_id, ei.id) DESC, ei.last_updated_at DESC
            	LIMIT 1 BY ei.id
            ), experiment_items_trace_scope AS (
                SELECT DISTINCT ei.trace_id
                FROM experiment_items ei
                INNER JOIN experiments_resolved e ON e.id = ei.experiment_id
                WHERE ei.workspace_id = :workspace_id
                <if(experiment_ids)>AND ei.experiment_id IN :experiment_ids<endif>
            ), experiment_item_aggr_trace_scope AS (
                SELECT DISTINCT trace_id
                FROM experiment_item_aggregates ei
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiment_aggregated_scope_ids)
                <if(experiment_ids)>AND experiment_id IN :experiment_ids<endif>
            ), trace_data AS (
                SELECT
                    id,
                    if(isNaN(duration), NULL, duration) AS duration,
                    <if(truncate)> replaceRegexpAll(if(notEmpty(input_slim), input_slim, truncated_input), '<truncate>', '"[image]"') as input <else> input <endif>,
                    <if(truncate)> replaceRegexpAll(if(notEmpty(output_slim), output_slim, truncated_output), '<truncate>', '"[image]"') as output <else> output <endif>,
                    output as full_output,
                    input as full_input,
                    metadata,
                    visibility_mode
                FROM traces
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND id IN (SELECT DISTINCT trace_id FROM experiment_items_trace_scope)
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ),
            dataset_items_resolved AS (
                SELECT
                    div_dedup.dataset_item_id AS id,
                    div_dedup.id AS row_id,
                    div_dedup.dataset_version_id AS dataset_version_id,
                    div_dedup.data AS data,
                    div_dedup.description AS description,
                    div_dedup.source AS source,
                    div_dedup.trace_id AS trace_id,
                    div_dedup.span_id AS span_id,
                    div_dedup.tags AS tags,
                    div_dedup.evaluators AS evaluators,
                    div_dedup.execution_policy AS execution_policy,
                    div_dedup.created_at AS item_created_at,
                    div_dedup.last_updated_at AS item_last_updated_at,
                    div_dedup.created_by AS item_created_by,
                    div_dedup.last_updated_by AS item_last_updated_by
                FROM (
                    SELECT *
                    FROM dataset_item_versions
                    WHERE workspace_id = :workspace_id
                    AND dataset_id  = :datasetId
                    AND dataset_version_id IN (SELECT resolved_dataset_version_id FROM experiments_resolved)
                    ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY dataset_item_id
                ) AS div_dedup
            ),
            feedback_scores_deduped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    category_name,
                    value,
                    reason,
                    source,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at,
                    author,
                    source_queue_id
                FROM (
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        feedback_scores.last_updated_by AS author,
                        CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'trace'
                      AND workspace_id = :workspace_id
                      <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                      AND entity_id IN (SELECT trace_id FROM experiment_items_trace_scope)
                    UNION ALL
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        created_by,
                        last_updated_by,
                        created_at,
                        last_updated_at,
                        author,
                        source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'trace'
                      AND workspace_id = :workspace_id
                      <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                      AND entity_id IN (SELECT trace_id FROM experiment_items_trace_scope)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ),
            feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ),
            feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, arrayElement(entries, 1).1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, arrayElement(entries, 1).2, arrayStringConcat(arrayMap(x -> if(x = '', '\\<no reason>', x), arrayMap(e -> e.2, entries)), ', ')) AS reason,
                    arrayElement(entries, 1).4 AS source,
                    mapFromArrays(
                        arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                        arrayMap(e -> tuple(e.1, e.2, e.3, e.4, CAST(e.9 AS DateTime64(9, 'UTC')), '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    CAST(arrayMin(arrayMap(e -> e.8, entries)) AS DateTime64(9, 'UTC')) AS created_at,
                    CAST(arrayMax(arrayMap(e -> e.9, entries)) AS DateTime64(9, 'UTC')) AS last_updated_at
                FROM feedback_scores_grouped
            )
            <if(feedback_scores_empty_filters)>
            , fsc AS (
                SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                FROM feedback_scores_final
                GROUP BY entity_id
                HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , experiment_items_final AS (
            	SELECT
            	    ei.id AS id,
            	    ei.experiment_id AS experiment_id,
            	    ei.dataset_item_id AS dataset_item_id,
            	    ei.stable_dataset_item_id AS stable_dataset_item_id,
            	    ei.trace_id AS trace_id,
            	    ei.workspace_id AS workspace_id,
            	    ei.created_at AS created_at,
            	    ei.last_updated_at AS last_updated_at,
            	    ei.created_by AS created_by,
            	    ei.last_updated_by AS last_updated_by,
            	    ei.project_id AS project_id,
            	    ei.execution_policy AS execution_policy,
            	    ei.resolved_dataset_version_id AS resolved_dataset_version_id
            	FROM experiment_items_scope ei
            	WHERE ei.workspace_id = :workspace_id
            	<if(experiment_item_filters || feedback_scores_filters || feedback_scores_empty_filters || dataset_item_filters)>
                AND ei.trace_id IN (
                  SELECT
                    id
                  FROM (
                      SELECT
                          id,
                          output,
                          input,
                          duration,
                          metadata
                      FROM traces
                      WHERE workspace_id = :workspace_id
                      <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                      AND id IN (SELECT DISTINCT trace_id FROM experiment_items_trace_scope)
                      ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                      LIMIT 1 BY id
                  ) t
                  <if(feedback_scores_empty_filters)>
                  LEFT JOIN fsc ON fsc.entity_id = t.id
                  <endif>
                  WHERE 1 = 1
                  <if(experiment_item_filters)>
                  AND <experiment_item_filters>
                  <endif>
                  <if(feedback_scores_filters)>
                    AND id IN (
                        SELECT
                            entity_id
                        FROM feedback_scores_final
                        GROUP BY entity_id
                        HAVING <feedback_scores_filters>
                    )
                  <endif>
                  <if(feedback_scores_empty_filters)>
                  AND fsc.feedback_scores_count = 0
                  <endif>
                )
                <endif>
                <if(dataset_item_filters)>
                AND ei.stable_dataset_item_id IN (SELECT id FROM dataset_items_resolved WHERE <dataset_item_filters>)
                <endif>
            )
            , comments_final AS (
                SELECT
                    id AS comment_id,
                    text,
                    created_at AS comment_created_at,
                    last_updated_at AS comment_last_updated_at,
                    created_by AS comment_created_by,
                    last_updated_by AS comment_last_updated_by,
                    entity_id
                FROM comments
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND entity_id IN (SELECT trace_id FROM experiment_items_trace_scope)
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            , assertion_results_per_trace AS (
                SELECT
                    entity_id,
                    toJSONString(
                        groupArray(
                            CAST(
                                (name, toString(passed), reason),
                                'Tuple(value String, passed String, reason String)'
                            )
                        )
                    ) AS assertions_array
                FROM assertion_results FINAL
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                  AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                GROUP BY entity_id
            )
            <if(push_top_limit && !push_top_needs_div && dataset_item_filters)>
            , dataset_items_filtered_ids AS (
                SELECT id, row_id
                FROM (
                    SELECT
                        dataset_item_id AS id,
                        id AS row_id,
                        data,
                        description,
                        source,
                        trace_id,
                        span_id,
                        tags,
                        evaluators,
                        execution_policy,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    FROM dataset_item_versions FINAL
                    WHERE workspace_id = :workspace_id
                    AND dataset_id  = :datasetId
                    AND dataset_version_id IN (SELECT resolved_dataset_version_id FROM experiment_aggregated_scope_ids)
                ) AS resolved
                WHERE <dataset_item_filters>
            )
            <endif>
            <if(push_top_limit && !push_top_needs_div)>
            , top_dataset_items AS (
                SELECT eia_t.dataset_item_id
                FROM experiment_item_aggregates AS eia_t FINAL
                WHERE eia_t.workspace_id = :workspace_id
                AND eia_t.experiment_id IN (SELECT id FROM experiment_aggregated_scope_ids)
                <if(experiment_item_filters)> AND <experiment_item_filters> <endif>
                <if(feedback_scores_filters_agg)> AND <feedback_scores_filters_agg> <endif>
                <if(feedback_scores_empty_filters_agg)> AND <feedback_scores_empty_filters_agg> <endif>
                <if(dataset_item_filters)>
                AND eia_t.dataset_item_id IN (SELECT arrayJoin([id, row_id]) FROM dataset_items_filtered_ids)
                <endif>
                GROUP BY eia_t.dataset_item_id
                ORDER BY <if(top_sorting)><top_sorting><else>eia_t.dataset_item_id DESC<endif>
                LIMIT :top_limit OFFSET :top_offset
            )
            <endif>
            , dataset_items_aggr_resolved AS (
                SELECT
                    div_dedup.dataset_item_id AS id,
                    div_dedup.id AS row_id,
                    div_dedup.dataset_version_id AS dataset_version_id,
                    div_dedup.data AS data,
                    div_dedup.description AS description,
                    div_dedup.source AS source,
                    div_dedup.trace_id AS trace_id,
                    div_dedup.span_id AS span_id,
                    div_dedup.tags AS tags,
                    div_dedup.evaluators AS evaluators,
                    div_dedup.execution_policy AS execution_policy,
                    div_dedup.created_at AS item_created_at,
                    div_dedup.last_updated_at AS item_last_updated_at,
                    div_dedup.created_by AS item_created_by,
                    div_dedup.last_updated_by AS item_last_updated_by
                FROM (
                    SELECT *
                    FROM dataset_item_versions
                    WHERE workspace_id = :workspace_id
                    AND dataset_id  = :datasetId
                    AND dataset_version_id IN (SELECT resolved_dataset_version_id FROM experiment_aggregated_scope_ids)
                    <if(push_top_limit && !push_top_needs_div)>
                    AND dataset_item_id IN (SELECT dataset_item_id FROM top_dataset_items)
                    <endif>
                    ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY dataset_item_id
                ) AS div_dedup
            )
            <if(!push_top_limit && dataset_item_filters)>
            , lookup_for_count AS (
                SELECT
                    arrayJoin([div.id, latest_passing.id]) AS lookup_id
                FROM (
                    SELECT id FROM dataset_items_aggr_resolved WHERE <dataset_item_filters>
                ) AS latest_passing
                INNER JOIN dataset_item_versions AS div FINAL
                    ON div.workspace_id = :workspace_id
                    AND div.dataset_id = :datasetId
                    AND div.dataset_version_id IN (SELECT resolved_dataset_version_id FROM experiment_aggregated_scope_ids)
                    AND div.dataset_item_id = latest_passing.id
            )
            <endif>
            <if(push_top_limit && push_top_needs_div)>
            , top_dataset_items AS (
                SELECT eia_t.dataset_item_id
                FROM experiment_item_aggregates AS eia_t FINAL
                LEFT JOIN dataset_items_aggr_resolved AS di_t
                    ON (di_t.id = eia_t.dataset_item_id OR di_t.row_id = eia_t.dataset_item_id)
                WHERE eia_t.workspace_id = :workspace_id
                AND eia_t.experiment_id IN (SELECT id FROM experiment_aggregated_scope_ids)
                GROUP BY eia_t.dataset_item_id
                ORDER BY <top_sorting>
                LIMIT :top_limit OFFSET :top_offset
            )
            <endif>
            SELECT
                u.id AS id,
                any(u.dataset_id) AS dataset_id,
                argMax(u.data_final, u.dataset_version_id) AS data_final,
                argMax(u.data, u.dataset_version_id) AS data,
                argMax(u.description, u.dataset_version_id) AS description,
                argMax(u.trace_id, u.dataset_version_id) AS trace_id,
                argMax(u.span_id, u.dataset_version_id) AS span_id,
                argMax(u.source, u.dataset_version_id) AS source,
                argMax(u.tags, u.dataset_version_id) AS tags,
                argMax(u.evaluators, u.dataset_version_id) AS evaluators,
                argMax(u.execution_policy, u.dataset_version_id) AS execution_policy,
                argMax(u.created_at, u.dataset_version_id) AS created_at,
                argMax(u.last_updated_at, u.dataset_version_id) AS last_updated_at,
                argMax(u.created_by, u.dataset_version_id) AS created_by,
                argMax(u.last_updated_by, u.dataset_version_id) AS last_updated_by,
                argMax(u.duration, u.dataset_version_id) AS duration,
                argMax(u.total_estimated_cost, u.dataset_version_id) AS total_estimated_cost,
                argMax(u.usage, u.dataset_version_id) AS usage,
                argMax(u.feedback_scores, u.dataset_version_id) AS feedback_scores,
                argMax(u.input, u.dataset_version_id) AS input,
                argMax(u.output, u.dataset_version_id) AS output,
                argMax(u.metadata, u.dataset_version_id) AS metadata,
                argMax(u.visibility_mode, u.dataset_version_id) AS visibility_mode,
                argMax(u.comments, u.dataset_version_id) AS comments,
                groupArrayArray(u.experiment_items_array) AS experiment_items_array
            FROM (
                <if(has_aggregated)>
                SELECT
                    ei.stable_dataset_item_id AS id,
                    :datasetId AS dataset_id,
                    di.dataset_version_id AS dataset_version_id,
                    <if(truncate)> mapApply((k, v) -> (k, substring(replaceRegexpAll(v, '<truncate>', '"[image]"'), 1, <truncationSize>)), COALESCE(di.data, map())) <else> COALESCE(di.data, map()) <endif> AS data_final,
                    COALESCE(di.data, map()) AS data,
                    di.description AS description,
                    di.trace_id AS trace_id,
                    di.span_id AS span_id,
                    di.source AS source,
                    di.tags AS tags,
                    di.evaluators AS evaluators,
                    di.execution_policy AS execution_policy,
                    di.item_created_at AS created_at,
                    di.item_last_updated_at AS last_updated_at,
                    di.item_created_by AS created_by,
                    di.item_last_updated_by AS last_updated_by,
                    avg(ei.duration) AS duration,
                    avg(ei.total_estimated_cost) AS total_estimated_cost,
                    avgMap(ei.usage) AS usage,
                    avgMap(ei.feedback_scores) AS feedback_scores,
                    <if(truncate)>replaceRegexpAll(if(notEmpty(argMax(ei.input_slim, ei.id)), argMax(ei.input_slim, ei.id), argMax(ei.input, ei.id)), '<truncate>', '"[image]"')<else>argMax(ei.input, ei.id)<endif> AS input,
                    <if(truncate)>replaceRegexpAll(if(notEmpty(argMax(ei.output_slim, ei.id)), argMax(ei.output_slim, ei.id), argMax(ei.output, ei.id)), '<truncate>', '"[image]"')<else>argMax(ei.output, ei.id)<endif> AS output,
                    argMax(ei.metadata, ei.id) AS metadata,
                    argMax(ei.visibility_mode, ei.id) AS visibility_mode,
                    argMax(ei.comments_array_agg, ei.id) AS comments,
                    groupArray(tuple(
                        ei.id,
                        ei.experiment_id,
                        ei.stable_dataset_item_id,
                        ei.trace_id,
                        <if(truncate)>replaceRegexpAll(if(notEmpty(ei.input_slim), ei.input_slim, ei.input), '<truncate>', '"[image]"')<else>ei.input<endif>,
                        <if(truncate)>replaceRegexpAll(if(notEmpty(ei.output_slim), ei.output_slim, ei.output), '<truncate>', '"[image]"')<else>ei.output<endif>,
                        ei.feedback_scores_array,
                        ei.created_at,
                        ei.last_updated_at,
                        ei.created_by,
                        ei.last_updated_by,
                        ei.comments_array_agg,
                        ei.duration,
                        ei.total_estimated_cost,
                        ei.usage,
                        ei.visibility_mode,
                        ei.metadata,
                        di.description,
                        ei.execution_policy,
                        ei.assertions_array
                    )) AS experiment_items_array
                FROM (
                    SELECT
                        eia.id AS id,
                        eia.trace_id AS trace_id,
                        if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, eia.dataset_item_id) AS stable_dataset_item_id,
                        eia.experiment_id AS experiment_id,
                        eia.project_id AS project_id,
                        eia.input AS input,
                        eia.output AS output,
                        eia.input_slim AS input_slim,
                        eia.output_slim AS output_slim,
                        eia.feedback_scores_array AS feedback_scores_array,
                        eia.duration AS duration,
                        eia.total_estimated_cost AS total_estimated_cost,
                        eia.usage AS usage,
                        eia.visibility_mode AS visibility_mode,
                        eia.created_at AS created_at,
                        eia.last_updated_at AS last_updated_at,
                        eia.created_by AS created_by,
                        eia.last_updated_by AS last_updated_by,
                        eia.metadata AS metadata,
                        eia.feedback_scores AS feedback_scores,
                        eia.comments_array_agg AS comments_array_agg,
                        eia.execution_policy AS execution_policy,
                        eia.assertions_array AS assertions_array
                    FROM experiment_item_aggregates AS eia FINAL
                    LEFT JOIN dataset_item_versions AS lookup_div FINAL
                        ON lookup_div.workspace_id = eia.workspace_id
                        AND lookup_div.id = eia.dataset_item_id
                    WHERE eia.workspace_id = :workspace_id
                    AND eia.experiment_id IN (SELECT id FROM experiment_aggregated_scope_ids)
                    <if(push_top_limit)>AND eia.dataset_item_id IN (SELECT dataset_item_id FROM top_dataset_items)<endif>
                    <if(experiment_item_filters)> AND <experiment_item_filters> <endif>
                    <if(feedback_scores_filters_agg)> AND <feedback_scores_filters_agg> <endif>
                    <if(feedback_scores_empty_filters_agg)> AND <feedback_scores_empty_filters_agg> <endif>
                    <if(dataset_item_filters)>
                    <if(!push_top_limit)>
                    AND eia.dataset_item_id IN (SELECT lookup_id FROM lookup_for_count)
                    <else>
                    AND if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, eia.dataset_item_id)
                        IN (SELECT id FROM dataset_items_aggr_resolved WHERE <dataset_item_filters>)
                    <endif>
                    <endif>
                    -- all duplicated rows share the same stable_dataset_item_id, so arbitrary pick is safe
                    LIMIT 1 BY eia.id
                ) ei
                LEFT JOIN dataset_items_aggr_resolved AS di ON di.id = ei.stable_dataset_item_id
                GROUP BY
                    ei.stable_dataset_item_id,
                    :datasetId,
                    di.dataset_version_id,
                    COALESCE(di.data, map()),
                    di.trace_id,
                    di.description,
                    di.span_id,
                    di.source,
                    di.tags,
                    di.evaluators,
                    di.execution_policy,
                    di.item_created_at,
                    di.item_last_updated_at,
                    di.item_created_by,
                    di.item_last_updated_by
                <if(search || filters)>
                  HAVING 1=1

                  <if(search)>
                  AND (multiSearchAnyCaseInsensitive(toString(data_final), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(argMax(ei.input, ei.id)), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(argMax(ei.output, ei.id)), :searchTerms))
                  <endif>

                  <if(filters)>
                  AND (<filters>)
                  <endif>

                <endif>
                <endif>

            <if(has_aggregated)><if(has_raw)>UNION ALL<endif><endif>

                <if(has_raw)>
                SELECT
                    ei.stable_dataset_item_id AS id,
                    :datasetId AS dataset_id,
                    di.dataset_version_id AS dataset_version_id,
                    <if(truncate)> mapApply((k, v) -> (k, substring(replaceRegexpAll(v, '<truncate>', '"[image]"'), 1, <truncationSize>)), COALESCE(di.data, map())) <else> COALESCE(di.data, map()) <endif> AS data_final,
                    COALESCE(di.data, map()) AS data,
                    di.description AS description,
                    di.trace_id AS trace_id,
                    di.span_id AS span_id,
                    di.source AS source,
                    di.tags AS tags,
                    di.evaluators AS evaluators,
                    di.execution_policy AS execution_policy,
                    di.item_created_at AS created_at,
                    di.item_last_updated_at AS last_updated_at,
                    di.item_created_by AS created_by,
                    di.item_last_updated_by AS last_updated_by,
                    avg(tfs.duration) AS duration,
                    avg(tfs.total_estimated_cost) AS total_estimated_cost,
                    avgMap(tfs.usage) AS usage,
                    avgMap(tfs.feedback_scores) AS feedback_scores,
                    argMax(tfs.input, ei.id) AS input,
                    argMax(tfs.output, ei.id) AS output,
                    argMax(tfs.metadata, ei.id) AS metadata,
                    argMax(tfs.visibility_mode, ei.id) AS visibility_mode,
                    argMax(tfs.comments_array_agg, ei.id) AS comments,
                    groupArray(tuple(
                        ei.id,
                        ei.experiment_id,
                        ei.stable_dataset_item_id,
                        ei.trace_id,
                        tfs.input,
                        tfs.output,
                        toString(tfs.feedback_scores_array),
                        ei.created_at,
                        ei.last_updated_at,
                        ei.created_by,
                        ei.last_updated_by,
                        tfs.comments_array_agg,
                        tfs.duration,
                        tfs.total_estimated_cost,
                        tfs.usage,
                        tfs.visibility_mode,
                        tfs.metadata,
                        di.description,
                        ei.execution_policy,
                        arp.assertions_array
                    )) AS experiment_items_array
                FROM experiment_items_final AS ei
                LEFT JOIN dataset_items_resolved AS di ON di.id = ei.stable_dataset_item_id
                LEFT JOIN (
                    SELECT
                        ei2.id AS item_id,
                        t.input,
                        t.output,
                        t.full_input,
                        t.full_output,
                        t.metadata,
                        t.duration,
                        t.visibility_mode,
                        s.total_estimated_cost,
                        s.usage,
                        any(fsa.feedback_scores_array) AS feedback_scores_array,
                        any(fsa.feedback_scores) AS feedback_scores,
                        any(co.comments_array_agg) AS comments_array_agg
                    FROM experiment_items_final ei2
                    INNER JOIN trace_data AS t ON ei2.trace_id = t.id
                    LEFT JOIN (
                        SELECT
                            entity_id,
                            toJSONString(
                                groupUniqArray(
                                    CAST(
                                        (
                                            name,
                                            category_name,
                                            value,
                                            reason,
                                            toString(source),
                                            concat(replaceOne(toString(created_at), ' ', 'T'), 'Z'),
                                            concat(replaceOne(toString(last_updated_at), ' ', 'T'), 'Z'),
                                            created_by,
                                            last_updated_by,
                                            mapFromArrays(
                                                mapKeys(value_by_author),
                                                arrayMap(
                                                    v -> CAST(
                                                        (
                                                            v.1,
                                                            v.2,
                                                            v.3,
                                                            toString(v.4),
                                                            concat(replaceOne(toString(v.5), ' ', 'T'), 'Z'),
                                                            v.6,
                                                            v.7,
                                                            v.8,
                                                            v.9
                                                        ),
                                                        'Tuple(
                                                            value Decimal(18,9),
                                                            reason String,
                                                            category_name String,
                                                            source String,
                                                            last_updated_at String,
                                                            span_type String,
                                                            span_id String,
                                                            source_queue_id String,
                                                            author String
                                                        )'
                                                    ),
                                                    mapValues(value_by_author)
                                                )
                                            )
                                        ),
                                        'Tuple(
                                            name String,
                                            category_name String,
                                            value Decimal(18,9),
                                            reason String,
                                            source String,
                                            created_at String,
                                            last_updated_at String,
                                            created_by String,
                                            last_updated_by String,
                                            value_by_author Map(
                                                String,
                                                Tuple(
                                                    value Decimal(18,9),
                                                    reason String,
                                                    category_name String,
                                                    source String,
                                                    last_updated_at String,
                                                    span_type String,
                                                    span_id String,
                                                    source_queue_id String,
                                                    author String
                                                )
                                            )
                                        )'
                                    )
                                )
                            ) AS feedback_scores_array,
                            mapFromArrays(
                                groupArray(name),
                                groupArray(value)
                            ) AS feedback_scores
                        FROM feedback_scores_final
                        GROUP BY entity_id
                    ) AS fsa ON t.id = fsa.entity_id
                    LEFT JOIN (
                        SELECT
                            entity_id,
                            toJSONString(groupUniqArray(CAST(tuple(
                                c.comment_id,
                                c.text,
                                concat(replaceOne(toString(c.comment_created_at), ' ', 'T'), 'Z'),
                                concat(replaceOne(toString(c.comment_last_updated_at), ' ', 'T'), 'Z'),
                                c.comment_created_by,
                                c.comment_last_updated_by,
                                c.entity_id
                            ), 'Tuple(
                                id FixedString(36),
                                text String,
                                created_at String,
                                last_updated_at String,
                                created_by String,
                                last_updated_by String,
                                entity_id FixedString(36)
                            )'))) AS comments_array_agg
                        FROM comments_final AS c
                        GROUP BY entity_id
                    ) AS co ON t.id = co.entity_id
                    LEFT JOIN (
                        SELECT
                            trace_id,
                            SUM(total_estimated_cost) AS total_estimated_cost,
                            sumMap(usage) AS usage
                        FROM spans final
                        WHERE workspace_id = :workspace_id
                        <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                        AND trace_id IN (SELECT trace_id FROM experiment_items_trace_scope)
                        GROUP BY workspace_id, project_id, trace_id
                    ) s ON t.id = s.trace_id
                    GROUP BY
                        ei2.id,
                        t.input,
                        t.output,
                        t.metadata,
                        t.duration,
                        t.visibility_mode,
                        t.full_input,
                        t.full_output,
                        s.total_estimated_cost,
                        s.usage
                ) AS tfs ON ei.id = tfs.item_id
                LEFT JOIN assertion_results_per_trace AS arp ON ei.trace_id = arp.entity_id
                GROUP BY
                    ei.stable_dataset_item_id,
                    :datasetId,
                    di.dataset_version_id,
                    COALESCE(di.data, map()),
                    di.trace_id,
                    di.description,
                    di.span_id,
                    di.source,
                    di.tags,
                    di.evaluators,
                    di.execution_policy,
                    di.item_created_at,
                    di.item_last_updated_at,
                    di.item_created_by,
                    di.item_last_updated_by
                <if(search || filters)>
                  HAVING 1=1

                  <if(search)>
                  AND (multiSearchAnyCaseInsensitive(toString(data_final), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(argMax(tfs.full_input, ei.id)), :searchTerms) OR multiSearchAnyCaseInsensitive(toString(argMax(tfs.full_output, ei.id)), :searchTerms))
                  <endif>

                  <if(filters)>
                  AND (<filters>)
                  <endif>

                <endif>
                <endif>
            ) AS u
            GROUP BY u.id
            <if(sorting)>
            ORDER BY <sorting>, u.id DESC
            <else>
            ORDER BY u.id DESC
            <endif>
            LIMIT :limit
            <if(!push_top_limit)>OFFSET :offset<endif>
            SETTINGS output_format_json_named_tuples_as_objects = 1
            ;
            """;

    // 批量插入条目
    private static final String BATCH_INSERT_ITEMS = """
            INSERT INTO dataset_item_versions (
                id,
                dataset_item_id,
                dataset_id,
                dataset_version_id,
                data,
                description,
                metadata,
                source,
                trace_id,
                span_id,
                tags,
                evaluators,
                execution_policy,
                item_created_at,
                item_last_updated_at,
                item_created_by,
                item_last_updated_by,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                workspace_id
            ) VALUES
                <items:{item |
                    (
                        :id<item.index>,
                        :dataset_item_id<item.index>,
                        :dataset_id,
                        :dataset_version_id,
                        :data<item.index>,
                        :description<item.index>,
                        :metadata<item.index>,
                        :source<item.index>,
                        :trace_id<item.index>,
                        :span_id<item.index>,
                        :tags<item.index>,
                        :evaluators<item.index>,
                        :execution_policy<item.index>,
                        :item_created_at<item.index>,
                        :item_last_updated_at<item.index>,
                        :item_created_by<item.index>,
                        :item_last_updated_by<item.index>,
                        now64(9),
                        now64(9),
                        :created_by,
                        :last_updated_by,
                        :workspace_id
                    )<if(item.hasNext)>,<endif>
                }>
            """;

    // 使用带条件字段更新的 INSERT ... SELECT 批量更新条目
    // 类似旧版表的批量更新，但针对版本化条目
    // 同时支持基于 ID 和基于过滤器的更新
    private static final String BATCH_UPDATE_ITEMS = """
            INSERT INTO dataset_item_versions (
                id,
                dataset_item_id,
                dataset_id,
                dataset_version_id,
                data,
                description,
                metadata,
                source,
                trace_id,
                span_id,
                tags,
                evaluators,
                execution_policy,
                item_created_at,
                item_last_updated_at,
                item_created_by,
                item_last_updated_by,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                workspace_id
            )
            SELECT
                arrayElement(:uuids, row_number() OVER ()) as id,
                src.dataset_item_id,
                src.dataset_id,
                :newVersionId as dataset_version_id,
                <if(data)> :data <else> src.data <endif> as data,
                <if(description)> :description <else> src.description <endif> as description,
                src.metadata,
                src.source,
                src.trace_id,
                src.span_id,
            """
            + TagOperations.tagUpdateFragment("src.tags")
            + """
                        as tags,
                        <if(evaluators)> :evaluators <else> src.evaluators <endif> as evaluators,
                        <if(clear_execution_policy)> '' <else><if(execution_policy)> :execution_policy <else> src.execution_policy <endif><endif> as execution_policy,
                        src.item_created_at,
                        now64(9) as item_last_updated_at,
                        src.item_created_by,
                        :userName as item_last_updated_by,
                        now64(9) as created_at,
                        now64(9) as last_updated_at,
                        :userName as created_by,
                        :userName as last_updated_by,
                        src.workspace_id
                    FROM (
                        SELECT *
                        FROM dataset_item_versions
                        WHERE workspace_id = :workspace_id
                        AND dataset_id = :datasetId
                        AND dataset_version_id = :baseVersionId
                        <if(item_ids)>
                        AND dataset_item_id IN :itemIds
                        <endif>
                        <if(dataset_item_filters)>
                        AND <dataset_item_filters>
                        <endif>
                        ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY dataset_item_id
                    ) AS src
                    SETTINGS short_circuit_function_evaluation = 'force_enable'
                    """;

    // OPIK-6696：插入的行携带 :targetDatasetId，而不是源的 dataset_id。这支持
    // 跨数据集的通过-SELECT-INSERT 的编辑，其中读取源（:sourceDatasetId）不同于
    // 目标数据集。
    private static final String EDIT_ITEM_VIA_SELECT_INSERT = """
            INSERT INTO dataset_item_versions (
                id,
                dataset_item_id,
                dataset_id,
                dataset_version_id,
                data,
                description,
                metadata,
                source,
                trace_id,
                span_id,
                tags,
                evaluators,
                execution_policy,
                item_created_at,
                item_last_updated_at,
                item_created_by,
                item_last_updated_by,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                workspace_id
            )
            SELECT
                {newId:String} as id,
                src.dataset_item_id,
                {targetDatasetId:String} as dataset_id,
                {newVersionId:String} as dataset_version_id,
                <if(data)> mapFromArrays({data_keys:Array(String)}, {data_values:Array(String)}) <else> src.data <endif> as data,
                <if(description)> base64Decode({description:String}) <else> src.description <endif> as description,
                src.metadata,
                src.source,
                src.trace_id,
                src.span_id,
                <if(tags)> {tags:Array(String)} <else> src.tags <endif> as tags,
                <if(evaluators)> base64Decode({evaluators:String}) <else> src.evaluators <endif> as evaluators,
                <if(clear_execution_policy)> '' <else><if(execution_policy)> {execution_policy:String} <else> src.execution_policy <endif><endif> as execution_policy,
                src.item_created_at,
                now64(9) as item_last_updated_at,
                src.item_created_by,
                {userName:String} as item_last_updated_by,
                now64(9) as created_at,
                now64(9) as last_updated_at,
                {userName:String} as created_by,
                {userName:String} as last_updated_by,
                src.workspace_id
            FROM (
                SELECT *
                FROM dataset_item_versions
                WHERE workspace_id = {workspace_id:String}
                AND dataset_id = {sourceDatasetId:String}
                AND dataset_version_id = {sourceVersionId:String}
                AND dataset_item_id = {datasetItemId:String}
                ORDER by (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) AS src
            """;

    // 将条目从源版本复制到目标版本
    // 可选地排除匹配过滤器的条目（当设置 exclude_filters 时）
    // 可选地排除特定条目 ID（当设置 exclude_ids 时）
    //
    // OPIK-6390：
    //   - LIMIT 1 BY dataset_item_id（而非 id），使同一版本内同一稳定条目的任何重复物理行
    //     折叠为一行，与本文件中其它读取路径使用的去重模式一致。
    //   - row_number() OVER (ORDER BY id DESC) AS rn 是在*去重后*的结果上计算的
    //     （内部 `deduped` 子查询包裹 WHERE + LIMIT 1 BY）。在 LIMIT 1 BY 之前编号
    //     会在未合并的 ReplacingMergeTree 重复项上留下稀疏排名（例如 1,3,5），
    //     并且 `rn <= length(:uuids)` 谓词即使池大小正确也会把有效行推到 generateUUIDv7
    //     回退，破坏排序顺序不变量。
    //   - if(rn <= length(:uuids), arrayElement(...), generateUUIDv7()) 保证每个复制的行
    //     都获得唯一 ID，即使 Java 提供的池比源行数短。此前，越界的 arrayElement
    //     会返回空字符串，并被填充为 NUL 字节的 FixedString(36)；相同的 NUL ID 随后在
    //     ReplacingMergeTree 下折叠，条目静默消失。回退的 UUIDv7 保持了插入原子性，
    //     代价是让溢出的行在 id 降序中排在新增/已编辑行之前——这是一种
    //     可见但非破坏性的降级，仅在池被设置得过小时才会触发。
    //
    // OPIK-6696：
    //   - 插入的行携带 :targetDatasetId，而非源的 dataset_id。当
    //     copy_from_dataset_id 与目标不同时，读取源是一个不同的数据集
    //     （例如迁移重放从源工作区的数据集读取，并写入
    //     目标工作区的数据集），因此插入的行必须携带目标的 dataset_id。
    private static final String COPY_VERSION_ITEMS = """
            INSERT INTO dataset_item_versions (
                id,
                dataset_item_id,
                dataset_id,
                dataset_version_id,
                data,
                description,
                metadata,
                source,
                trace_id,
                span_id,
                tags,
                evaluators,
                execution_policy,
                item_created_at,
                item_last_updated_at,
                item_created_by,
                item_last_updated_by,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                workspace_id
            )
            SELECT
                if(src.rn \\<= length(<uuids_literal>),
                   arrayElement(<uuids_literal>, src.rn),
                   toString(generateUUIDv7())) AS id,
                src.dataset_item_id,
                {targetDatasetId:String} as dataset_id,
                {targetVersionId:String} as dataset_version_id,
                src.data,
                src.description,
                src.metadata,
                src.source,
                src.trace_id,
                src.span_id,
                src.tags,
                src.evaluators,
                src.execution_policy,
                src.item_created_at,
                src.item_last_updated_at,
                src.item_created_by,
                src.item_last_updated_by,
                now64(9) as created_at,
                now64(9) as last_updated_at,
                {user_name:String} as created_by,
                {user_name:String} as last_updated_by,
                src.workspace_id
            FROM (
                SELECT
                    *,
                    row_number() OVER (ORDER BY id DESC) AS rn
                FROM (
                    SELECT *
                    FROM dataset_item_versions
                    WHERE dataset_id = {sourceDatasetId:String}
                    AND dataset_version_id = {sourceVersionId:String}
                    AND workspace_id = {workspace_id:String}
                    <if(exclude_filters)>
                    AND NOT (<exclude_filters>)
                    <endif>
                    <if(exclude_ids)>
                    AND dataset_item_id NOT IN <excluded_ids_literal>
                    <endif>
                    ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY dataset_item_id
                ) AS deduped
            ) AS src
            ORDER BY src.id DESC
            """;

    private static final String RESOLVE_DATASET_ID_FROM_ITEM_ID = """
            SELECT
                dataset_id
            FROM dataset_item_versions
            WHERE dataset_item_id = :datasetItemId
            AND workspace_id = :workspace_id
            ORDER BY last_updated_at DESC
            LIMIT 1
            """;

    private static final String RESOLVE_DATASET_ID_FROM_ITEM_IDS = """
            SELECT
                dataset_id
            FROM dataset_item_versions
            WHERE dataset_item_id IN :datasetItemIds
            AND workspace_id = :workspace_id
            GROUP BY dataset_id
            """;

    private static final String SELECT_COLUMNS_BY_VERSION = """
            SELECT
                mapFromArrays(
                    groupArray(key),
                    groupArray(types)
                ) AS columns
            FROM (
                SELECT
                    key,
                    arrayDistinct(groupArray(type)) AS types
                FROM (
                    SELECT
                        id,
                        column_types
                    FROM dataset_item_versions FINAL
                    WHERE dataset_id = :datasetId
                    AND dataset_version_id = :versionId
                    AND workspace_id = :workspace_id
                ) AS lastRows
                ARRAY JOIN mapKeys(column_types) AS key
                ARRAY JOIN column_types[key] AS type
                GROUP BY key
            )
            """;

    private static final String SELECT_ITEM_BY_ID = """
            SELECT
                dataset_item_id AS id,
                dataset_id,
                data,
                description,
                source,
                trace_id,
                span_id,
                tags,
                evaluators,
                execution_policy,
                item_created_at as created_at,
                item_last_updated_at as last_updated_at,
                item_created_by as created_by,
                item_last_updated_by as last_updated_by
            FROM dataset_item_versions
            WHERE workspace_id = :workspace_id
            AND dataset_item_id = :id
            <if(dataset_version_id)>AND dataset_version_id = :dataset_version_id<endif>
            ORDER BY last_updated_at DESC
            LIMIT 1
            """;

    private static final String SELECT_DATASET_WORKSPACE_ITEMS_BY_ROW_IDS = """
            SELECT DISTINCT
                dataset_item_id AS id,
                workspace_id
            FROM dataset_item_versions
            WHERE dataset_item_id IN :datasetItemRowIds OR id IN :datasetItemRowIds
            ORDER BY last_updated_at DESC
            LIMIT 1 BY dataset_item_id
            """;

    private static final String SELECT_EXECUTION_POLICIES_BY_DATASET_ITEM_IDS = """
            SELECT DISTINCT
                dataset_item_id AS id,
                dataset_version_id,
                execution_policy
            FROM dataset_item_versions
            WHERE (id IN :datasetItemIds OR dataset_item_id IN :datasetItemIds)
            AND dataset_version_id IN :datasetVersionIds
            AND workspace_id = :workspace_id
            """;

    private static final String SELECT_ITEMS_BY_DATASET_ITEM_IDS = """
            SELECT
                dataset_item_id AS id,
                dataset_id,
                data,
                description,
                source,
                trace_id,
                span_id,
                tags,
                evaluators,
                execution_policy,
                item_created_at as created_at,
                item_last_updated_at as last_updated_at,
                item_created_by as created_by,
                item_last_updated_by as last_updated_by
            FROM dataset_item_versions
            WHERE workspace_id = :workspace_id
            AND dataset_id = :datasetId
            AND dataset_version_id = :versionId
            AND dataset_item_id IN :datasetItemIds
            ORDER BY dataset_item_id DESC, last_updated_at DESC
            LIMIT 1 BY dataset_item_id
            """;

    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_STATS = """
            WITH experiment_aggregated_scope_ids AS (
                SELECT
                    id,
                    COALESCE(nullIf(dataset_version_id, ''), :versionId) AS resolved_dataset_version_id
                FROM experiment_aggregates FINAL
                WHERE workspace_id = :workspace_id
                AND dataset_id = :datasetId
                <if(experiment_ids)>AND id IN :experiment_ids<endif>
            ), experiments_resolved AS (
                SELECT
                    id,
                    COALESCE(nullIf(dataset_version_id, ''), :versionId) AS resolved_version_id
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND dataset_id = :datasetId
                <if(experiment_ids)>AND id IN :experiment_ids<endif>
                AND id NOT IN (SELECT id FROM experiment_aggregated_scope_ids)
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_items_scope AS (
                SELECT
                    ei.id AS id,
                    ei.experiment_id AS experiment_id,
                    ei.dataset_item_id AS dataset_item_id,
                    ei.trace_id AS trace_id,
                    ei.workspace_id AS workspace_id,
                    e.resolved_version_id AS resolved_dataset_version_id,
                    if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, ei.dataset_item_id) AS stable_dataset_item_id
                FROM experiment_items ei
                INNER JOIN experiments_resolved e ON e.id = ei.experiment_id
                LEFT JOIN dataset_item_versions AS lookup_div FINAL
                    ON lookup_div.workspace_id = ei.workspace_id
                    AND lookup_div.id = ei.dataset_item_id
                WHERE ei.workspace_id = :workspace_id
                <if(experiment_ids)>AND ei.experiment_id IN :experiment_ids<endif>
                ORDER BY (ei.workspace_id, ei.experiment_id, ei.dataset_item_id, ei.trace_id, ei.id) DESC, ei.last_updated_at DESC
                LIMIT 1 BY ei.id
            ), experiment_items_trace_scope AS (
                SELECT DISTINCT ei.trace_id
                FROM experiment_items ei
                INNER JOIN experiments_resolved e ON e.id = ei.experiment_id
                WHERE ei.workspace_id = :workspace_id
                <if(experiment_ids)>AND ei.experiment_id IN :experiment_ids<endif>
            ), experiment_items_aggr_trace_scope AS (
                SELECT DISTINCT ei.trace_id
                FROM experiment_item_aggregates ei
                WHERE ei.workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiment_aggregated_scope_ids)
                <if(experiment_ids)>AND ei.experiment_id IN :experiment_ids<endif>
            ), trace_data AS (
                SELECT
                    id,
                    duration
                FROM traces
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND id IN (SELECT DISTINCT trace_id FROM experiment_items_trace_scope)
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), trace_ids AS (
                SELECT
                    id
                FROM traces
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND id IN (SELECT DISTINCT trace_id FROM experiment_items_trace_scope)
            ), feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           feedback_scores.last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    <if(has_target_projects)>
                    AND project_id IN :target_project_ids
                    <endif>
                    AND entity_id IN (SELECT trace_id FROM experiment_items_trace_scope)
                    UNION ALL
                    SELECT
                        workspace_id,
                        project_id,
                        entity_id,
                        name,
                        value,
                        last_updated_at,
                        author,
                        source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    <if(has_target_projects)>
                    AND project_id IN :target_project_ids
                    <endif>
                    AND entity_id IN (SELECT trace_id FROM experiment_items_trace_scope)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_deduped fsf
                INNER JOIN trace_ids td ON td.id = fsf.entity_id
                GROUP BY workspace_id, project_id, entity_id, name
            )<if(feedback_scores_empty_filters)>,
            fsc AS (
                SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                FROM (
                    SELECT *
                    FROM feedback_scores_final
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>,
             experiment_items_filtered AS (
                SELECT
                    ei.id,
                    ei.experiment_id,
                    ei.dataset_item_id,
                    ei.trace_id
                FROM experiment_items_scope ei
                INNER JOIN (
                    SELECT div_dedup.dataset_item_id, div_dedup.id AS row_id, div_dedup.dataset_version_id
                    FROM (
                        SELECT *
                        FROM dataset_item_versions div
                        WHERE div.workspace_id = :workspace_id
                        AND div.dataset_id = :datasetId
                        AND div.dataset_version_id IN (SELECT resolved_version_id FROM experiments_resolved)
                        ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                        LIMIT 1 BY div.id
                    ) div_dedup
                ) dibv ON dibv.dataset_item_id = ei.stable_dataset_item_id
                <if(experiment_item_filters)>
                AND ei.trace_id IN (
                    SELECT
                        id
                    FROM (
                        SELECT
                            id,
                            duration,
                            input,
                            output,
                            metadata
                        FROM traces
                        WHERE workspace_id = :workspace_id
                        <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                        AND id IN (SELECT DISTINCT trace_id FROM experiment_items_trace_scope)
                        ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
                    WHERE <experiment_item_filters>
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND ei.trace_id IN (
                    SELECT t.id
                    FROM trace_ids t
                    LEFT JOIN fsc ON fsc.entity_id = t.id
                    WHERE fsc.feedback_scores_count = 0
                )
                <endif>
                <if(feedback_scores_filters)>
                AND ei.trace_id IN (
                    SELECT entity_id
                    FROM feedback_scores_final
                    GROUP BY entity_id, name
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(dataset_item_filters)>
                AND ei.dataset_item_id IN (
                    SELECT arrayJoin([id, row_id])
                    FROM (
                        SELECT
                            div_dedup.dataset_item_id AS id,
                            div_dedup.id AS row_id,
                            div_dedup.data AS data,
                            div_dedup.source AS source,
                            div_dedup.trace_id AS trace_id,
                            div_dedup.span_id AS span_id,
                            div_dedup.tags AS tags,
                            div_dedup.created_at AS created_at,
                            div_dedup.last_updated_at AS last_updated_at,
                            div_dedup.created_by AS created_by,
                            div_dedup.last_updated_by AS last_updated_by,
                            div_dedup.dataset_version_id AS dataset_version_id
                        FROM (
                            SELECT *
                            FROM dataset_item_versions
                            WHERE workspace_id = :workspace_id
                            AND dataset_id = :datasetId
                            AND dataset_version_id IN (SELECT resolved_version_id FROM experiments_resolved)
                            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                            LIMIT 1 BY id
                        ) AS div_dedup
                    ) AS versioned
                    WHERE <dataset_item_filters>
                )
                <endif>
            ), item_agg AS (
                SELECT
                    eia.id,
                    eia.experiment_id,
                    eia.dataset_item_id,
                    eia.trace_id,
                    toFloat64(eia.duration) AS duration,
                    eia.total_estimated_cost,
                    eia.usage,
                    eia.feedback_scores
                FROM experiment_item_aggregates AS eia FINAL
                WHERE eia.workspace_id = :workspace_id
                AND eia.experiment_id IN (SELECT id FROM experiment_aggregated_scope_ids)
                <if(experiment_item_filters)> AND <experiment_item_filters> <endif>
                <if(feedback_scores_filters_agg)> AND <feedback_scores_filters_agg> <endif>
                <if(feedback_scores_empty_filters_agg)> AND <feedback_scores_empty_filters_agg> <endif>
                <if(dataset_item_filters)>
                AND eia.dataset_item_id IN (
                    SELECT arrayJoin([id, row_id])
                    FROM (
                        SELECT
                            div_dedup.dataset_item_id AS id,
                            div_dedup.id AS row_id,
                            div_dedup.data AS data,
                            div_dedup.source AS source,
                            div_dedup.trace_id AS trace_id,
                            div_dedup.span_id AS span_id,
                            div_dedup.tags AS tags
                        FROM (
                            SELECT *
                            FROM dataset_item_versions
                            WHERE workspace_id = :workspace_id
                            AND dataset_id = :datasetId
                            AND dataset_version_id IN (SELECT resolved_dataset_version_id FROM experiment_aggregated_scope_ids)
                            ORDER BY (workspace_id, dataset_id, dataset_version_id, id) DESC, last_updated_at DESC
                            LIMIT 1 BY id
                        ) AS div_dedup
                    ) AS versioned
                    WHERE <dataset_item_filters>
                )
                <endif>
            ), traces_with_cost_and_duration AS (
                SELECT DISTINCT
                    eif.trace_id as trace_id,
                    t.duration as duration,
                    s.total_estimated_cost as total_estimated_cost,
                    s.usage as usage
                FROM experiment_items_filtered eif
                INNER JOIN trace_data t ON t.id = eif.trace_id
                LEFT JOIN (
                    SELECT
                        trace_id,
                        sum(total_estimated_cost) as total_estimated_cost,
                        sumMap(usage) as usage
                    FROM spans FINAL
                    WHERE workspace_id = :workspace_id
                    <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                    AND trace_id IN (SELECT trace_id FROM experiment_items_trace_scope)
                    GROUP BY workspace_id, project_id, trace_id
                ) AS s ON eif.trace_id = s.trace_id
            ), feedback_scores_raw_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) AS feedback_scores
                FROM feedback_scores_final
                GROUP BY entity_id
            ), feedback_scores_percentiles AS (
                SELECT
                    name,
                    quantiles(0.5, 0.9, 0.99)(toFloat64(value)) AS percentiles
                FROM (
                    <if(has_aggregated)>
                    SELECT
                        name,
                        toDecimal64(value, 9) AS value
                    FROM item_agg
                    ARRAY JOIN mapKeys(feedback_scores) AS name, mapValues(feedback_scores) AS value
                    WHERE notEmpty(feedback_scores)
                    <endif>
                    <if(has_aggregated)><if(has_raw)>UNION ALL<endif><endif>
                    <if(has_raw)>
                    SELECT name, value
                    FROM feedback_scores_final
                    WHERE entity_id IN (SELECT trace_id FROM experiment_items_filtered)
                    <endif>
                )
                GROUP BY name
            )
            SELECT
                count(DISTINCT ei.id) as experiment_items_count,
                count(DISTINCT ei.trace_id) as trace_count,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toDecimal64(
                             greatest(
                               least(if(isFinite(v), v, 0), 999999999.999999999),
                               -999999999.999999999
                             ),
                             9
                           ),
                      quantiles(0.5, 0.9, 0.99)(ei.duration)
                    )
                ) AS duration,
                avgMap(ei.feedback_scores) AS feedback_scores,
                (SELECT mapFromArrays(
                    groupArray(name),
                    groupArray(mapFromArrays(
                        ['p50', 'p90', 'p99'],
                        arrayMap(v -> toDecimal64(if(isFinite(v), v, 0), 9), percentiles)
                    ))
                ) FROM feedback_scores_percentiles) AS feedback_scores_percentiles,
                avgIf(ei.total_estimated_cost, ei.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                sumIf(ei.total_estimated_cost, ei.total_estimated_cost > 0) AS total_estimated_cost_sum_,
                toDecimal128(total_estimated_cost_sum_, 12) AS total_estimated_cost_sum,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toDecimal128(
                             greatest(
                               least(if(isFinite(toFloat64(v)), toFloat64(v), 0), 999999999.999999999),
                               -999999999.999999999
                             ),
                             12
                           ),
                      quantilesIf(0.5, 0.9, 0.99)(ei.total_estimated_cost, ei.total_estimated_cost > 0)
                    )
                ) AS total_estimated_cost_percentiles,
                avgMap(ei.usage) AS usage,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toInt64(greatest(least(if(isFinite(v), v, 0), 999999999.999999999), -999999999.999999999)),
                      quantilesIf(0.5, 0.9, 0.99)(
                          toFloat64(ei.usage['total_tokens']),
                          ei.usage['total_tokens'] IS NOT NULL AND ei.usage['total_tokens'] > 0
                      )
                    )
                ) AS usage_total_tokens_percentiles
            FROM (
                <if(has_aggregated)>
                SELECT
                    ia.id AS id,
                    ia.trace_id AS trace_id,
                    ia.duration AS duration,
                    ia.total_estimated_cost AS total_estimated_cost,
                    ia.usage AS usage,
                    ia.feedback_scores AS feedback_scores
                FROM item_agg ia
                <endif>
                <if(has_aggregated)><if(has_raw)>UNION ALL<endif><endif>
                <if(has_raw)>
                SELECT
                    eif.id AS id,
                    eif.trace_id AS trace_id,
                    tc.duration AS duration,
                    tc.total_estimated_cost AS total_estimated_cost,
                    tc.usage AS usage,
                    fr.feedback_scores AS feedback_scores
                FROM experiment_items_filtered eif
                LEFT JOIN traces_with_cost_and_duration tc ON tc.trace_id = eif.trace_id
                LEFT JOIN feedback_scores_raw_agg fr ON fr.entity_id = eif.trace_id
                <endif>
            ) ei
            ;
            """;

    // 迁移查询
    private static final String DELETE_ITEMS_FROM_VERSION_MIGRATION = """
            DELETE FROM dataset_item_versions
            WHERE workspace_id = :workspaceId
              AND dataset_id = :datasetId
              AND dataset_version_id = :versionId
            """;

    private static final String COPY_ITEMS_FROM_LEGACY = """
            INSERT INTO dataset_item_versions (
                id, dataset_item_id, dataset_id, dataset_version_id,
                data, metadata, source, trace_id, span_id, tags,
                item_created_at, item_last_updated_at,
                item_created_by, item_last_updated_by,
                created_at, last_updated_at, created_by, last_updated_by,
                workspace_id
            )
            SELECT
                id,
                id as dataset_item_id,
                dataset_id,
                :versionId as dataset_version_id,
                data, metadata, source, trace_id, span_id, tags,
                created_at as item_created_at,
                last_updated_at as item_last_updated_at,
                created_by as item_created_by,
                last_updated_by as item_last_updated_by,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                workspace_id
            FROM dataset_items
            WHERE workspace_id = :workspaceId
              AND dataset_id = :datasetId
            """;

    private static final String COUNT_ITEMS_IN_VERSION = """
            SELECT count(DISTINCT dataset_item_id) as count
            FROM dataset_item_versions
            WHERE workspace_id = :workspaceId
              AND dataset_id = :datasetId
              AND dataset_version_id = :versionId
            """;

    /**
     * 在单个语句中统计多个版本条目的查询。
     * 使用 (workspace_id, dataset_id, dataset_version_id) 元组根据表的排序键
     * (workspace_id, dataset_id, dataset_version_id, id) 优化查询。
     * 这使 ClickHouse 能够高效地跳过无关的数据分区。
     */
    private static final String COUNT_ITEMS_IN_VERSIONS_BATCH = """
            SELECT
                dataset_version_id,
                count(DISTINCT dataset_item_id) as count
            FROM dataset_item_versions
            WHERE (workspace_id, dataset_id, dataset_version_id) IN (<version_tuples>)
            GROUP BY dataset_version_id
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull OpikConfiguration config;
    private final @NonNull ExperimentAggregatesDAO experimentAggregatesDAO;
    /**
     * 用于在 {@code dataset_item_versions} 上执行 {@code INSERT ... SELECT} 的 v2 ClickHouse 客户端，
     * 它会在响应中报告权威的 {@code written_rows}。r2dbc 驱动程序读取的是
     * 中间的进度事件，对这种查询形态不可靠；参见
     * {@code ClickHouse/clickhouse-java#2860}。
     */
    private final @NonNull Client clickHouseClient;
    private final @NonNull ZeroRowsRetryPolicy zeroRowsRetryPolicy;

    @Override
    @WithSpan
    public Flux<DatasetItemIdAndHash> getItemIdsAndHashes(@NonNull UUID datasetId, @NonNull UUID versionId) {
        log.debug("获取数据集 '{}'、版本 '{}' 的条目 ID 和哈希", datasetId, versionId);

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(SELECT_ITEM_IDS_AND_HASHES)
                    .bind("datasetId", datasetId)
                    .bind("versionId", versionId);

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_version_item_ids_and_hashes");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, metadata) -> {
                        var datasetItemId = UUID.fromString(row.get("dataset_item_id", String.class));
                        var hash = row.get("data_hash", Long.class);
                        Set<String> tags = Optional.ofNullable(row.get("tags", String[].class))
                                .map(arr -> new HashSet<>(Arrays.asList(arr)))
                                .orElseGet(HashSet::new);
                        var evaluatorsHash = row.get("evaluators_hash", Long.class);
                        var executionPolicyHash = row.get("execution_policy_hash", Long.class);
                        var descriptionHash = row.get("description_hash", Long.class);
                        log.debug("已检索版本化条目：dataset_item_id='{}'、hash='{}'、tags='{}'",
                                datasetItemId, hash, tags);
                        return DatasetItemIdAndHash.builder()
                                .itemId(datasetItemId)
                                .dataHash(hash)
                                .tags(tags)
                                .evaluatorsHash(evaluatorsHash)
                                .executionPolicyHash(executionPolicyHash)
                                .descriptionHash(descriptionHash)
                                .build();
                    }))
                    .collectList()
                    .doOnSuccess(items -> log.info("已检索 '{}' 个条目 ID 和哈希（版本 '{}'）", items.size(),
                            versionId))
                    .flatMapMany(Flux::fromIterable);
        });
    }

    @Override
    @WithSpan
    public Mono<Long> countExistingItemIds(@NonNull UUID datasetId, @NonNull UUID versionId,
            Set<UUID> itemIds) {
        if (CollectionUtils.isEmpty(itemIds)) {
            return Mono.just(0L);
        }

        log.debug("统计 '{}' 个条目 ID 中已存在的数量（数据集 '{}'，版本 '{}'）", itemIds.size(), datasetId,
                versionId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(COUNT_EXISTING_ITEM_IDS)
                    .bind("datasetId", datasetId)
                    .bind("versionId", versionId)
                    .bind("itemIds", itemIds.stream().map(UUID::toString).toArray(String[]::new));

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "count_existing_item_ids");

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L)
                    .doOnSuccess(count -> log.debug(
                            "在版本 '{}' 中、'{}' 个传入 ID 中找到 '{}' 个已存在的条目", versionId,
                            itemIds.size(), count))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull UUID datasetId, @NonNull UUID versionId, int limit,
            UUID lastRetrievedId) {
        return getItems(datasetId, versionId, limit, lastRetrievedId, emptyList());
    }

    @Override
    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull UUID datasetId, @NonNull UUID versionId, int limit,
            UUID lastRetrievedId, @NonNull List<DatasetItemFilter> filters) {

        ST template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS);
        if (lastRetrievedId != null) {
            template.add("lastRetrievedId", true);
        }

        addDatasetItemFiltersToTemplate(template, filters);

        String query = template.render();

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(query)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId.toString())
                    .bind("limit", limit);

            if (lastRetrievedId != null) {
                statement.bind("lastRetrievedId", lastRetrievedId.toString());
            } else {
                statement.bind("offset", 0);
            }

            bindDatasetItemFilters(statement, filters);

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "stream_version_items");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(DatasetItemResultMapper::mapItem);
        });
    }

    /**
     * 向 StringTemplate 添加通用过滤条件的辅助方法。
     * 封装了向模板添加过滤器和搜索条件的重复模式。
     *
     * @param template 要向其添加过滤器的 StringTemplate
     * @param criteria 包含过滤器和搜索词的搜索条件
     */
    private void addFiltersToTemplate(@NonNull ST template, @NonNull DatasetItemSearchCriteria criteria) {
        DatasetItemSearchCriteriaMapper.applyToTemplate(template, criteria, FILTER_STRATEGY_PARAMS);
    }

    /**
     * 如果存在过滤器，则向 StringTemplate 添加数据集条目过滤器。
     *
     * @param template 要向其添加过滤器的 StringTemplate
     * @param filters 要应用的过滤器列表，可为 null 或空
     */
    private void addDatasetItemFiltersToTemplate(ST template, List<? extends Filter> filters) {
        if (CollectionUtils.isNotEmpty(filters)) {
            FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM)
                    .ifPresent(datasetItemFilters -> template.add("dataset_item_filters",
                            datasetItemFilters));
        }
    }

    /**
     * 将数据集条目过滤参数绑定到 R2DBC 语句。
     *
     * @param statement 要绑定参数的 R2DBC 语句
     * @param filters 要绑定的过滤器列表，可为 null 或空
     */
    private void bindDatasetItemFilters(Statement statement, List<? extends Filter> filters) {
        if (CollectionUtils.isNotEmpty(filters)) {
            FilterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
        }
    }

    /**
     * 将搜索词和过滤器绑定到语句的辅助方法。
     * 封装了绑定搜索和过滤参数的重复模式。
     *
     * @param statement 要绑定参数的 R2DBC 语句
     * @param criteria 包含搜索词和过滤器的搜索条件
     * @return 所有参数都绑定完毕的语句
     */
    private Statement bindSearchAndFilters(@NonNull Statement statement, @NonNull DatasetItemSearchCriteria criteria) {
        return DatasetItemSearchCriteriaMapper.bindSearchCriteria(statement, criteria, BIND_STRATEGIES,
                filterQueryBuilder);
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(@NonNull DatasetItemSearchCriteria criteria, int page, int size,
            @NonNull UUID versionId) {
        return Mono.zip(
                getCount(criteria, versionId),
                getColumns(criteria.datasetId(), versionId.toString())).flatMap(tuple -> {
                    Long total = tuple.getT1();
                    Set<Column> columns = tuple.getT2();

                    return asyncTemplate.nonTransaction(connection -> {
                        // 构建带过滤器和截断的模板
                        ST template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS);
                        template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());
                        template.add("truncationSize", config.getResponseFormatting().getTruncationSize());
                        addDatasetItemFiltersToTemplate(template, criteria.filters());

                        var statement = connection.createStatement(template.render())
                                .bind("datasetId", criteria.datasetId().toString())
                                .bind("versionId", versionId.toString())
                                .bind("limit", size)
                                .bind("offset", (page - 1) * size);

                        // 绑定过滤参数
                        bindDatasetItemFilters(statement, criteria.filters());

                        Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                                "select_dataset_item_versions");

                        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                                .doFinally(signalType -> endSegment(segment))
                                .flatMap(DatasetItemResultMapper::mapItem)
                                .collectList()
                                .onErrorResume(e -> ErrorUtils.handleMalformedJsonPath(e, List.of()))
                                .map(items -> new DatasetItemPage(items, page, items.size(), total, columns,
                                        sortingFactory.getSortableFields()));
                    });
                });
    }

    @Override
    public Mono<DatasetItemPage> getItemsWithExperimentItems(@NonNull DatasetItemSearchCriteria criteria, int page,
            int size, @NonNull String versionId) {
        log.info(
                "获取数据集 '{}'、版本 '{}'、实验 '{}' 的带实验条目版本化数据集条目",
                criteria.datasetId(), versionId, criteria.experimentIds());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            var aggregationCriteria = AggregationBranchCountsCriteria.builder()
                    .datasetId(criteria.datasetId())
                    .experimentIds(criteria.experimentIds())
                    .build();

            // 并行运行预查询：目标项目 ID 和聚合实验 ID
            var targetProjectIdsMono = getTargetProjectIds(workspaceId, criteria.datasetId(), criteria.experimentIds());
            var branchCountsMono = getAggregationBranchCounts(aggregationCriteria);

            return Mono.zip(targetProjectIdsMono, branchCountsMono)
                    .flatMap(preQueryResults -> {
                        var targetProjectIds = preQueryResults.getT1();
                        var counts = preQueryResults.getT2();

                        boolean hasAggregated = counts.hasAggregated();
                        boolean hasRaw = counts.hasRaw();

                        return asyncTemplate.nonTransaction(connection -> {
                            // 使用 StringTemplate 构建查询
                            ST template = TemplateUtils
                                    .newST(SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS);

                            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());
                            template.add("truncationSize",
                                    config.getResponseFormatting().getTruncationSize());

                            // 向模板添加实验 ID
                            if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
                                template.add("experiment_ids", criteria.experimentIds());
                            }

                            // 添加分支标志以有条件地包含/排除 UNION ALL 分支
                            template.add("has_aggregated", hasAggregated);
                            template.add("has_raw", hasRaw);

                            boolean pushTopLimit = applyPushTopLimit(template, criteria, hasAggregated,
                                    hasRaw);

                            // 使用辅助方法添加过滤器和搜索条件
                            addFiltersToTemplate(template, criteria);

                            // 如果存在排序则添加
                            var fieldMapping = criteria.sortingFields() != null
                                    ? filterQueryBuilder
                                            .buildDatasetItemFieldMapping(criteria.sortingFields())
                                    : null;

                            var hasDynamicKeys = criteria.sortingFields() != null
                                    && sortingQueryBuilder.hasDynamicKeys(criteria.sortingFields());

                            if (criteria.sortingFields() != null && !criteria.sortingFields().isEmpty()) {
                                String sortingQuery = sortingQueryBuilder.toOrderBySql(
                                        criteria.sortingFields(), fieldMapping);
                                if (sortingQuery != null) {
                                    template.add("sorting", sortingQuery);
                                }
                            }

                            // 向模板添加目标项目 ID 标志（来自单独查询以减少 traces 表扫描）
                            if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                                template.add("has_target_projects", true);
                            }

                            String query = template.render();

                            var statement = connection.createStatement(query)
                                    .bind("workspace_id", workspaceId)
                                    .bind("datasetId", criteria.datasetId())
                                    .bind("versionId", versionId)
                                    .bind("limit", size);

                            if (pushTopLimit) {
                                statement.bind("top_limit", size);
                                statement.bind("top_offset", (page - 1) * size);
                            } else {
                                statement.bind("offset", (page - 1) * size);
                            }

                            // 绑定目标项目 ID（来自单独查询以减少 traces 表扫描）
                            if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                                statement.bind("target_project_ids",
                                        targetProjectIds.toArray(UUID[]::new));
                            }

                            // 绑定实验 ID 数组
                            if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
                                statement.bind("experiment_ids",
                                        criteria.experimentIds().toArray(UUID[]::new));
                            }

                            // 如果存在动态排序键则绑定。
                            // 不传 fieldMapping，以便绑定 ALL 动态键，
                            // 包括 top_sorting SELECT 表达式中使用的那些。
                            if (hasDynamicKeys) {
                                statement = sortingQueryBuilder.bindDynamicKeys(statement,
                                        criteria.sortingFields());
                            }

                            // 使用辅助方法绑定搜索和过滤参数
                            statement = bindSearchAndFilters(statement, criteria);

                            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                                    "select_dataset_item_versions_with_experiment_items");

                            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                                    .doFinally(signalType -> endSegment(segment))
                                    .flatMap(DatasetItemResultMapper::mapItem)
                                    .collectList()
                                    .onErrorResume(e -> ErrorUtils.handleMalformedJsonPath(e, List.of()))
                                    .zipWith(getCountWithExperimentFilters(criteria, versionId,
                                            targetProjectIds, hasAggregated, hasRaw))
                                    .zipWith(getColumns(criteria.datasetId(), versionId))

                                    .map(tuple -> {
                                        var itemsAndCount = tuple.getT1();
                                        List<DatasetItem> items = itemsAndCount.getT1();
                                        Long count = itemsAndCount.getT2();
                                        Set<Column> columns = tuple.getT2();

                                        return new DatasetItemPage(items, page, items.size(), count,
                                                columns, sortingFactory.getSortableFields());
                                    });
                        });
                    });
        });
    }

    /**
     * 从 trace 中获取给定实验条目的目标项目 ID。
     * 作为单独查询执行，以减少主查询中 traces 表的扫描次数。
     */
    private Mono<List<UUID>> getTargetProjectIds(String workspaceId, UUID datasetId, Set<UUID> experimentIds) {
        return asyncTemplate.nonTransaction(connection -> {
            ST template = getSTWithLogComment(SELECT_TARGET_PROJECTS, "get_target_project_ids", workspaceId, "",
                    datasetId);

            if (CollectionUtils.isNotEmpty(experimentIds)) {
                template.add("experiment_ids", true);
            }

            String query = template.render();

            var statement = connection.createStatement(query)
                    .bind("workspace_id", workspaceId)
                    .bind("datasetId", datasetId.toString());

            if (CollectionUtils.isNotEmpty(experimentIds)) {
                statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("project_id", UUID.class)))
                    .collectList();
        });
    }

    private Mono<AggregatedExperimentCounts> getAggregationBranchCounts(
            @NonNull AggregationBranchCountsCriteria criteria) {
        return experimentAggregatesDAO.getAggregationBranchCounts(criteria);
    }

    @Override
    public Mono<List<Column>> getExperimentItemsOutputColumns(@NonNull UUID datasetId, Set<UUID> experimentIds) {
        log.debug("获取数据集 '{}' 的实验条目输出列", datasetId);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return asyncTemplate.nonTransaction(connection -> {
                ST template = TemplateUtils.newST(SELECT_EXPERIMENT_ITEMS_OUTPUT_COLUMNS);

                if (CollectionUtils.isNotEmpty(experimentIds)) {
                    template.add("experiment_ids", true);
                }

                var statement = connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("datasetId", datasetId);

                if (CollectionUtils.isNotEmpty(experimentIds)) {
                    statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
                }

                Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                        "get_experiment_items_output_columns");

                return Flux.from(statement.execute())
                        .doFinally(signalType -> endSegment(segment))
                        .flatMap(result -> DatasetItemResultMapper.mapColumns(result, "output"))
                        .next()
                        .map(List::copyOf)
                        .defaultIfEmpty(List.of());
            });
        });
    }

    private Mono<Long> getCountWithExperimentFilters(@NonNull DatasetItemSearchCriteria criteria,
            @NonNull String versionId, List<UUID> targetProjectIds,
            boolean hasAggregated, boolean hasRaw) {
        log.debug("获取数据集 '{}' 版本 '{}' 带实验过滤器的过滤计数", criteria.datasetId(),
                versionId);

        // OPIK-6311：当没有搜索时，slim_count 将计数路由经过 EIA；过滤器使用
        // 与数据路径的 top_dataset_items CTE 相同的可渲染策略。
        boolean slimCount = hasAggregated && !hasRaw && StringUtils.isBlank(criteria.search());

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return asyncTemplate.nonTransaction(connection -> {
                ST template = slimCount
                        ? getSTWithLogComment(SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT,
                                "count_dataset_item_versions_with_experiment_items_slim",
                                workspaceId, userName, criteria.datasetId().toString())
                        : TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT);

                if (slimCount) {
                    template.add("slim_count", true);
                }

                template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());
                template.add("truncationSize", config.getResponseFormatting().getTruncationSize());

                // 如果存在实验 ID 则添加
                if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
                    template.add("experiment_ids", true);
                }

                // 添加分支标志以有条件地包含/排除 UNION ALL 分支
                template.add("has_aggregated", hasAggregated);
                template.add("has_raw", hasRaw);

                // 使用辅助方法添加过滤器和搜索条件
                addFiltersToTemplate(template, criteria);

                // 向模板添加目标项目 ID 标志（来自单独查询以减少 traces 表扫描）
                if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                    template.add("has_target_projects", true);
                }

                var statement = connection.createStatement(template.render())
                        .bind("datasetId", criteria.datasetId());

                if (!slimCount) {
                    statement.bind("versionId", versionId);

                    if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                        statement.bind("target_project_ids", targetProjectIds.toArray(UUID[]::new));
                    }
                }

                if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
                    statement.bind("experiment_ids", criteria.experimentIds().toArray(UUID[]::new));
                }

                statement = bindSearchAndFilters(statement, criteria);

                Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                        slimCount
                                ? "count_dataset_item_versions_with_experiment_items_slim"
                                : "count_dataset_item_versions_with_experiment_filters");

                return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                        .doFinally(signalType -> endSegment(segment))
                        .flatMap(result -> result.map((row, meta) -> row.get("count", Long.class)))
                        .reduce(0L, Long::sum)
                        .onErrorResume(e -> ErrorUtils.handleMalformedJsonPath(e, 0L));
            });
        });
    }

    private Mono<Long> getCount(DatasetItemSearchCriteria criteria, UUID versionId) {
        return asyncTemplate.nonTransaction(connection -> {
            // 构建带过滤器的模板
            ST template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS_COUNT);
            addDatasetItemFiltersToTemplate(template, criteria.filters());

            var statement = connection.createStatement(template.render())
                    .bind("datasetId", criteria.datasetId().toString())
                    .bind("versionId", versionId.toString());

            // 绑定过滤参数
            bindDatasetItemFilters(statement, criteria.filters());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "count_dataset_item_versions");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, meta) -> row.get("count", Long.class)))
                    .reduce(0L, Long::sum)
                    .onErrorResume(e -> ErrorUtils.handleMalformedJsonPath(e, 0L));
        });
    }

    /**
     * 通过 {@code INSERT ... SELECT} 将条目从源版本复制到目标版本。
     *
     * <p>针对 v2 ClickHouse 客户端（而非 r2dbc）执行，因此返回的计数是来自服务器的
     * 权威 {@code written_rows}，而不是中间的进度读数
     * （参见 OPIK-6674 和 {@code ClickHouse/clickhouse-java#2860}）。
     *
     * <p>结果被包装在 {@link ZeroRowsRetryPolicy} 中，因此当输入集非空却得到 0 行结果时，
     * 会先带退避地重试，再将其作为错误暴露出来。
     */
    @Override
    @WithSpan
    public Mono<Long> copyVersionItems(@NonNull UUID sourceDatasetId, @NonNull UUID sourceVersionId,
            @NonNull UUID targetDatasetId, @NonNull UUID targetVersionId,
            List<DatasetItemFilter> excludeFilters, @NonNull List<UUID> uuids) {

        log.debug(
                "将条目从（数据集 '{}'，版本 '{}'）复制到（数据集 '{}'，版本 '{}'），excludeFilters='{}'、uuidPoolSize='{}'",
                sourceDatasetId, sourceVersionId, targetDatasetId, targetVersionId,
                excludeFilters != null ? excludeFilters.size() : 0, uuids.size());

        // 当存在 excludeFilters 时，池大小不再是行数的有效下界：
        // 过滤器可以合法地排除每一行源数据（例如删除或批量更新
        // 其过滤器匹配所有条目），使 0 写入行成为合法结果，而不是
        // 灾难性的零副本滞后信号。仅在无过滤器的
        // 结转路径上断言零行守卫（OPIK-6674）；对过滤路径传 expectedRows=0 以绕过它。
        long expectedRows = CollectionUtils.isEmpty(excludeFilters)
                ? FilterUtils.expectedRowsFromPool(uuids)
                : 0L;

        return zeroRowsRetryPolicy.retryOnZeroRows(
                executeCopyVersionItems(sourceDatasetId, sourceVersionId, targetDatasetId, targetVersionId, uuids,
                        null /* excludedIds */, excludeFilters),
                expectedRows, "copyVersionItems");
    }

    /**
     * 通过 v2 客户端构建并执行 COPY_VERSION_ITEMS 查询。
     * 由 {@link #copyVersionItems} 和 {@link #copyUnchangedItems} 的排除分支共享。
     *
     * <p>通过 {@link com.comet.opik.utils.AsyncUtils#makeMonoContextAware}（{@code
     * Mono.deferContextual}）返回，因此 SQL 构建、参数格式化以及 {@code clickHouseClient.query()}
     * 调用会在每次订阅时重新运行，包括由重试驱动的重新订阅。
     */
    private Mono<Long> executeCopyVersionItems(UUID sourceDatasetId, UUID sourceVersionId, UUID targetDatasetId,
            UUID targetVersionId, List<UUID> uuids, Set<UUID> excludedIds, List<DatasetItemFilter> excludeFilters) {

        // makeMonoContextAware = Mono.deferContextual；该 lambda 会在每次订阅时重新运行，
        // 因此每次重试都会重建 SQL/参数并获得全新的 CompletableFuture。
        return makeMonoContextAware((userName, workspaceId) -> {
            boolean hasExcludedIds = CollectionUtils.isNotEmpty(excludedIds);
            boolean hasExcludeFilters = CollectionUtils.isNotEmpty(excludeFilters);

            ST template = TemplateUtils.newST(COPY_VERSION_ITEMS);
            // 通过 StringTemplate 将 UUID 数组直接内联到 SQL 主体中。它们可能
            // 很大（数千个 UUID，每个约 38 字节），否则会被 URL 编码
            // 为 HTTP 查询参数——v2 客户端把参数值放在请求行上，而请求行
            // 有约 8KB 的长度限制。SQL 本身放在请求体中发送，没有
            // 这种限制。之所以安全，是因为 UUID.toString() 只包含 [0-9a-f-]——没有注入途径。
            template.add("uuids_literal", uuidsToArrayLiteral(uuids));
            if (hasExcludedIds) {
                template.add("exclude_ids", true);
                template.add("excluded_ids_literal", uuidsToArrayLiteral(excludedIds));
            }
            if (hasExcludeFilters) {
                FilterQueryBuilder.toAnalyticsDbFiltersV2Client(excludeFilters, FilterStrategy.DATASET_ITEM)
                        .ifPresent(filters -> template.add("exclude_filters", filters));
            }
            String sql = template.render();

            Map<String, Object> params = new HashMap<>();
            params.put("sourceDatasetId", sourceDatasetId.toString());
            params.put("sourceVersionId", sourceVersionId.toString());
            params.put("targetDatasetId", targetDatasetId.toString());
            params.put("targetVersionId", targetVersionId.toString());
            params.put("workspace_id", workspaceId);
            params.put("user_name", userName);
            if (hasExcludeFilters) {
                FilterQueryBuilder.populateV2ClientParams(params, excludeFilters, FilterStrategy.DATASET_ITEM);
            }

            QuerySettings settings = new QuerySettings()
                    .setQueryId(UUID.randomUUID().toString())
                    .serverSetting("log_comment",
                            "copy_version_items:%s:%s:%s".formatted(workspaceId, targetDatasetId, targetVersionId));

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "copy_version_items");
            return Mono.fromFuture(() -> clickHouseClient.query(sql, params, settings))
                    .flatMap(response -> Mono.fromCallable(() -> {
                        try (response) {
                            long written = response.getWrittenRows();
                            log.info(
                                    "已将 '{}' 个条目从（数据集 '{}'，版本 '{}'）复制到（数据集 '{}'，版本 '{}'）",
                                    written, sourceDatasetId, sourceVersionId, targetDatasetId, targetVersionId);
                            return written;
                        }
                    }).subscribeOn(Schedulers.boundedElastic()))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    /** 委托给共享的 {@link FilterQueryBuilder#formatStringArrayLiteral} 辅助方法。 */
    private static String uuidsToArrayLiteral(Collection<UUID> ids) {
        return FilterQueryBuilder.formatStringArrayLiteral(ids.stream().map(UUID::toString).toList());
    }

    @Override
    @WithSpan
    public Mono<Long> applyDelta(@NonNull UUID datasetId, @NonNull UUID newVersionId,
            @NonNull List<DatasetItem> addedItems, @NonNull List<DatasetItem> editedItems,
            @NonNull Set<UUID> deletedIds, @NonNull List<UUID> unchangedUuids,
            @NonNull Set<UUID> additionalExcludeIds,
            @NonNull UUID copyFromDatasetId, @NonNull UUID copyFromVersionId) {

        log.info(
                "为数据集 '{}' 应用增量：newVersion='{}'、copyFromDataset='{}'、copyFromVersion='{}'、"
                        + "added='{}'、edited='{}'、deleted='{}'、additionalExclude='{}'",
                datasetId, newVersionId, copyFromDatasetId, copyFromVersionId, addedItems.size(),
                editedItems.size(), deletedIds.size(), additionalExcludeIds.size());

        // 收集所有正在被编辑的稳定条目 ID（这样我们就不会从基础版本复制它们）
        Set<UUID> editedItemIds = editedItems.stream()
                .map(DatasetItem::datasetItemId)
                .collect(Collectors.toSet());

        // 复制时合并已删除、已编辑和额外 ID 用于排除
        Set<UUID> excludedIds = new HashSet<>(deletedIds);
        excludedIds.addAll(editedItemIds);
        excludedIds.addAll(additionalExcludeIds);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            // 步骤 1：插入新增条目（由于最新/最大的 UUID，将排在最前）
            Mono<Long> insertAdded = insertItems(datasetId, newVersionId, addedItems, workspaceId, userName);

            // 步骤 2：插入已编辑条目（由于中间的 UUID，将排在新增之后）
            Mono<Long> insertEdited = insertItems(datasetId, newVersionId, editedItems, workspaceId, userName);

            // 步骤 3：复制未更改条目（由于最早/最小的 UUID，将排在最后）。
            // OPIK-6696：从调用方提供的源坐标读取，而不是从目标的前一个版本读取。
            // 源坐标 = (copyFromDatasetId, copyFromVersionId)；目标坐标 = (datasetId, newVersionId)。
            Mono<Long> copyUnchanged = copyUnchangedItems(
                    copyFromDatasetId, copyFromVersionId,
                    datasetId, newVersionId,
                    excludedIds, unchangedUuids, workspaceId, userName);

            // 执行所有操作并汇总结果
            return insertAdded
                    .zipWith(insertEdited, Long::sum)
                    .zipWith(copyUnchanged, Long::sum)
                    .doOnSuccess(total -> log.info("已为数据集 '{}' 应用增量：新版本中条目总数 '{}'",
                            datasetId, total));
        });
    }

    /**
     * 通过 {@code INSERT ... SELECT} 为每个条目 INSERT 一行新行来编辑一批数据集条目。
     *
     * <p>每个条目针对 v2 ClickHouse 客户端（OPIK-6674）运行，因此 {@code getWrittenRows()}
     * 反映权威的服务器计数。实际总和被送入 {@link ZeroRowsRetryPolicy}；
     * 成功后我们仍上报 {@code itemCount} 以保留原始 API 契约。
     *
     * <p>重试会用相同的 {@code newRowIds} 重新插入相同的行；ReplacingMergeTree
     * 在 {@code (workspace_id, dataset_id, dataset_version_id, id)} 上去重，使其保持幂等。
     */
    @Override
    @WithSpan
    public Mono<Long> editItemsViaSelectInsert(@NonNull UUID sourceDatasetId, @NonNull UUID sourceVersionId,
            @NonNull UUID targetDatasetId, @NonNull UUID newVersionId,
            @NonNull List<DatasetItemEdit> editedItems, @NonNull List<UUID> newRowIds) {

        if (editedItems.isEmpty()) {
            return Mono.just(0L);
        }

        long itemCount = editedItems.size();

        return zeroRowsRetryPolicy.retryOnZeroRows(
                executeEditItemsViaSelectInsert(sourceDatasetId, sourceVersionId, targetDatasetId, newVersionId,
                        editedItems, newRowIds),
                itemCount, "editItemsViaSelectInsert")
                .map(actualSum -> itemCount);
    }

    private Mono<Long> executeEditItemsViaSelectInsert(UUID sourceDatasetId, UUID sourceVersionId,
            UUID targetDatasetId, UUID newVersionId, List<DatasetItemEdit> editedItems, List<UUID> newRowIds) {

        return makeMonoContextAware((userName, workspaceId) -> {
            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "edit_items_via_select_insert");
            return Flux.range(0, editedItems.size())
                    .concatMap(i -> executeEditOneItem(editedItems.get(i), newRowIds.get(i),
                            sourceDatasetId, sourceVersionId, targetDatasetId, newVersionId, userName, workspaceId))
                    .reduce(0L, Long::sum)
                    .doOnSuccess(actualSum -> log.info(
                            "已通过 SELECT INSERT 编辑 '{}' 个条目到（数据集 '{}'，版本 '{}'）（实际写入行数：{}）",
                            editedItems.size(), targetDatasetId, newVersionId, actualSum))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private Mono<Long> executeEditOneItem(DatasetItemEdit edit, UUID newRowId,
            UUID sourceDatasetId, UUID sourceVersionId, UUID targetDatasetId, UUID newVersionId,
            String userName, String workspaceId) {

        ST template = TemplateUtils.newST(EDIT_ITEM_VIA_SELECT_INSERT);
        if (edit.data() != null) {
            template.add("data", true);
        }
        if (edit.tags() != null) {
            template.add("tags", true);
        }
        if (edit.description() != null) {
            template.add("description", true);
        }
        if (edit.evaluators() != null) {
            template.add("evaluators", true);
        }
        if (Boolean.TRUE.equals(edit.clearExecutionPolicy())) {
            template.add("clear_execution_policy", true);
        } else if (edit.executionPolicy() != null) {
            template.add("execution_policy", true);
        }
        String sql = template.render();

        Map<String, Object> params = new HashMap<>();
        params.put("workspace_id", workspaceId);
        params.put("sourceDatasetId", sourceDatasetId.toString());
        params.put("sourceVersionId", sourceVersionId.toString());
        params.put("targetDatasetId", targetDatasetId.toString());
        params.put("newVersionId", newVersionId.toString());
        params.put("datasetItemId", edit.id().toString());
        params.put("newId", newRowId.toString());
        params.put("userName", userName);

        if (edit.data() != null) {
            Map<String, String> dataAsStrings = DatasetItemResultMapper.getOrDefault(edit.data());
            // Array(String) 参数必须是 ClickHouse 数组字面量——v2 客户端会通过 String.valueOf
            // 序列化 Map 值，否则会输出 Java 的未加引号 [a, b] 形式。
            params.put("data_keys", FilterQueryBuilder.formatStringArrayLiteral(dataAsStrings.keySet()));
            params.put("data_values", FilterQueryBuilder.formatStringArrayLiteral(dataAsStrings.values()));
        }
        // 通过 v2 客户端 {:String} 替换绑定的自由文本 / JSON 参数必须先 base64：
        // ClickHouse 会处理替换值中的反斜杠转义，因此原始 '\n' 会被转换为
        // 字面换行——这会损坏 JSON（evaluators）或存储的文本，甚至导致
        // 写入失败（description）。SQL 中的 base64Decode 会还原精确字节。请对
        // 这里新增的任何自由文本/JSON {:String} 参数做同样的处理。
        if (edit.description() != null) {
            params.put("description", base64Encode(edit.description()));
        }
        if (edit.tags() != null) {
            params.put("tags", FilterQueryBuilder.formatStringArrayLiteral(edit.tags()));
        }
        if (edit.evaluators() != null) {
            params.put("evaluators", base64Encode(serializeEvaluators(edit.evaluators())));
        }
        if (!Boolean.TRUE.equals(edit.clearExecutionPolicy()) && edit.executionPolicy() != null) {
            params.put("execution_policy", serializeExecutionPolicy(edit.executionPolicy()));
        }

        QuerySettings settings = new QuerySettings()
                .setQueryId(UUID.randomUUID().toString())
                .serverSetting("log_comment",
                        "edit_item_via_select_insert:%s:%s:%s".formatted(workspaceId, targetDatasetId, newVersionId));

        return Mono.fromFuture(() -> clickHouseClient.query(sql, params, settings))
                .flatMap(response -> Mono.fromCallable(() -> {
                    try (response) {
                        return response.getWrittenRows();
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 将一个版本条目的未更改子集复制到新版本，可选地
     * 排除特定 {@code dataset_item_id}（例如正在被删除或替换的条目）。
     *
     * <p>路由经过 {@link #executeCopyVersionItems}，因此底层 {@code INSERT ... SELECT}
     * 在 v2 ClickHouse 客户端上运行并报告权威的 {@code written_rows}（OPIK-6674）。
     *
     * <p>OPIK-6696：行从 {@code (sourceDatasetId, sourceVersionId)} 读取，插入的
     * 行以 {@code targetDatasetId} 作为其 dataset_id，因此跨数据集复制（迁移重放
     * 从稳定的源数据集读取并写入目标数据集）会落入正确的
     * 数据集。当 source == destination 时即为旧版行为。
     */
    private Mono<Long> copyUnchangedItems(UUID sourceDatasetId, UUID sourceVersionId,
            UUID targetDatasetId, UUID newVersionId,
            Set<UUID> excludedIds, List<UUID> uuids, String workspaceId, String userName) {

        Mono<Long> copy = executeCopyVersionItems(sourceDatasetId, sourceVersionId, targetDatasetId, newVersionId,
                uuids, CollectionUtils.isEmpty(excludedIds) ? null : excludedIds, null /* no filters in this path */)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName));

        return zeroRowsRetryPolicy.retryOnZeroRows(copy, FilterUtils.expectedRowsFromPool(uuids),
                "copyUnchangedItems");
    }

    @Override
    @WithSpan
    public Mono<Long> batchUpdateItems(@NonNull UUID datasetId, @NonNull UUID baseVersionId,
            @NonNull UUID newVersionId, @NonNull DatasetItemBatchUpdate batchUpdate, @NonNull List<UUID> uuids) {

        // 仅当 ID 明确为空且 filters 为 null（完全未提供）时才提前返回
        // 注意：空 filters 列表表示“选择全部条目”，因此这种情况不应提前返回
        boolean hasIds = CollectionUtils.isNotEmpty(batchUpdate.ids());
        boolean hasFilters = batchUpdate.filters() != null; // null 表示未提供，空列表表示“选择全部”

        if (!hasIds && !hasFilters) {
            // 既没有提供 ID 也没有提供过滤器——无需更新
            return Mono.just(0L);
        }

        log.info(
                "批量更新数据集 '{}' 中从版本 '{}' 到版本 '{}' 的条目，idsSize='{}'、filtersSize='{}'",
                datasetId, baseVersionId, newVersionId,
                batchUpdate.ids() != null ? batchUpdate.ids().size() : 0,
                batchUpdate.filters() != null ? batchUpdate.filters().size() : 0);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return asyncTemplate.nonTransaction(connection -> {
                // 使用 StringTemplate 为条件字段构建查询
                ST template = new ST(BATCH_UPDATE_ITEMS);

                // 根据正在更新的字段添加条件参数
                if (batchUpdate.update().data() != null) {
                    template.add("data", true);
                }
                if (batchUpdate.update().description() != null) {
                    template.add("description", true);
                }

                TagOperations.configureTagTemplate(template, batchUpdate.update(),
                        Boolean.TRUE.equals(batchUpdate.mergeTags()));

                if (batchUpdate.update().evaluators() != null) {
                    template.add("evaluators", true);
                }
                if (Boolean.TRUE.equals(batchUpdate.update().clearExecutionPolicy())) {
                    template.add("clear_execution_policy", true);
                } else if (batchUpdate.update().executionPolicy() != null) {
                    template.add("execution_policy", true);
                }

                // 根据所提供的内容添加条目 ID 或过滤器
                if (batchUpdate.ids() != null && !batchUpdate.ids().isEmpty()) {
                    template.add("item_ids", true);
                } else if (batchUpdate.filters() != null && !batchUpdate.filters().isEmpty()) {
                    FilterQueryBuilder.toAnalyticsDbFilters(batchUpdate.filters(), FilterStrategy.DATASET_ITEM)
                            .ifPresent(datasetItemFilters -> template.add("dataset_item_filters", datasetItemFilters));
                }

                String query = template.render();

                // 将 UUID 转换为字符串以供 ClickHouse 使用
                String[] uuidStrings = uuids.stream()
                        .map(UUID::toString)
                        .toArray(String[]::new);

                var statement = connection.createStatement(query)
                        .bind("workspace_id", workspaceId)
                        .bind("datasetId", datasetId.toString())
                        .bind("baseVersionId", baseVersionId.toString())
                        .bind("newVersionId", newVersionId.toString())
                        .bind("uuids", uuidStrings)
                        .bind("userName", userName);

                // 如果提供了条目 ID 则绑定
                if (batchUpdate.ids() != null && !batchUpdate.ids().isEmpty()) {
                    String[] itemIdStrings = batchUpdate.ids().stream()
                            .map(UUID::toString)
                            .toArray(String[]::new);
                    statement.bind("itemIds", itemIdStrings);
                }

                // 如果提供了过滤参数则绑定
                if (batchUpdate.filters() != null && !batchUpdate.filters().isEmpty()) {
                    FilterQueryBuilder.bind(statement, batchUpdate.filters(), FilterStrategy.DATASET_ITEM);
                }

                // 绑定可选更新字段
                if (batchUpdate.update().data() != null) {
                    Map<String, String> dataAsStrings = DatasetItemResultMapper
                            .getOrDefault(batchUpdate.update().data());
                    statement.bind("data", dataAsStrings);
                }
                if (batchUpdate.update().description() != null) {
                    statement.bind("description", batchUpdate.update().description());
                }

                TagOperations.bindTagParams(statement, batchUpdate.update());

                if (batchUpdate.update().evaluators() != null) {
                    statement.bind("evaluators", serializeEvaluators(batchUpdate.update().evaluators()));
                }
                if (!Boolean.TRUE.equals(batchUpdate.update().clearExecutionPolicy())
                        && batchUpdate.update().executionPolicy() != null) {
                    statement.bind("execution_policy",
                            serializeExecutionPolicy(batchUpdate.update().executionPolicy()));
                }

                Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "batch_update_items");

                return Flux.from(statement.execute())
                        .flatMap(Result::getRowsUpdated)
                        .reduce(0L, Long::sum)
                        .doOnSuccess(count -> log.info("已批量更新 '{}' 个条目（数据集 '{}'）", count, datasetId))
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Mono<Long> insertItems(@NonNull UUID datasetId, @NonNull UUID newVersionId,
            @NonNull List<DatasetItem> items, @NonNull String workspaceId, @NonNull String userName) {

        if (items.isEmpty()) {
            return Mono.just(0L);
        }

        // 注意：ClickHouse 的异步插入会在提交前立即返回 0。
        // 我们返回正在插入的条目数，而不是依赖 getRowsUpdated。
        long itemCount = items.size();

        return asyncTemplate.nonTransaction(connection -> {
            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "insert_delta_items");

            // 使用模板构建批量插入查询
            List<TemplateUtils.QueryItem> queryItems = TemplateUtils.getQueryItemPlaceHolder(items.size());
            var template = TemplateUtils.newST(BATCH_INSERT_ITEMS)
                    .add("items", queryItems);

            var statement = connection.createStatement(template.render())
                    .bind("dataset_id", datasetId.toString())
                    .bind("dataset_version_id", newVersionId.toString())
                    .bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);

            // 绑定所有条目特定的参数
            int i = 0;
            for (DatasetItem item : items) {
                UUID stableItemId = item.datasetItemId();
                Map<String, String> dataAsStrings = DatasetItemResultMapper.getOrDefault(item.data());

                statement
                        .bind("id" + i, item.id().toString())
                        .bind("dataset_item_id" + i, stableItemId.toString())
                        .bind("data" + i, dataAsStrings)
                        .bind("description" + i, item.description() != null ? item.description() : "")
                        .bind("metadata" + i, "")
                        .bind("source" + i, item.source() != null ? item.source().getValue() : "sdk")
                        .bind("trace_id" + i, DatasetItemResultMapper.getOrDefault(item.traceId()))
                        .bind("span_id" + i, DatasetItemResultMapper.getOrDefault(item.spanId()))
                        .bind("tags" + i, item.tags() != null ? item.tags().toArray(new String[0]) : new String[0])
                        .bind("evaluators" + i, serializeEvaluators(item.evaluators()))
                        .bind("execution_policy" + i, serializeExecutionPolicy(item.executionPolicy()))
                        .bind("item_created_at" + i, formatTimestamp(item.createdAt()))
                        .bind("item_last_updated_at" + i, formatTimestamp(item.lastUpdatedAt()))
                        .bind("item_created_by" + i, item.createdBy() != null ? item.createdBy() : userName)
                        .bind("item_last_updated_by" + i,
                                item.lastUpdatedBy() != null ? item.lastUpdatedBy() : userName);

                i++;
            }

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum)
                    .map(results -> itemCount) // 返回条目数而不是结果之和
                    .doOnSuccess(count -> log.debug("已批量插入 '{}' 个条目", count))
                    .doOnError(e -> log.error("数据集 '{}'、版本 '{}' 的批量插入条目失败",
                            datasetId, newVersionId, e))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> removeItemsFromVersion(@NonNull UUID datasetId, @NonNull UUID versionId,
            @NonNull Set<UUID> itemIds, @NonNull String workspaceId) {

        if (itemIds.isEmpty()) {
            return Mono.just(0L);
        }

        log.info("移除 '{}' 个条目（版本 '{}'，数据集 '{}'）", itemIds.size(), versionId, datasetId);

        return asyncTemplate.nonTransaction(connection -> {
            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "remove_items_from_version");

            // 首先统计实际存在多少条目（以处理不存在的 ID）
            return countItemsByIds(datasetId, versionId, itemIds, workspaceId)
                    .flatMap(existingCount -> {
                        if (existingCount == 0) {
                            log.info("未找到版本 '{}' 需要删除的条目", versionId);
                            return Mono.just(0L);
                        }

                        // 使用 StringTemplate 生成带 item_ids 条件的查询
                        var template = new ST(DELETE_ITEMS_FROM_VERSION);
                        template.add("item_ids", true); // 启用 item_ids 条件
                        String deleteQuery = template.render();

                        var statement = connection.createStatement(deleteQuery)
                                .bind("dataset_id", datasetId.toString())
                                .bind("version_id", versionId.toString())
                                .bind("workspace_id", workspaceId);

                        // 绑定条目 ID 数组
                        String[] itemIdStrings = itemIds.stream()
                                .map(UUID::toString)
                                .toArray(String[]::new);
                        statement.bind("item_ids", itemIdStrings);

                        // delete 是异步的并返回 0，因此返回我们计算出的计数
                        return Flux.from(statement.execute())
                                .flatMap(Result::getRowsUpdated)
                                .reduce(0L, Long::sum)
                                .map(results -> existingCount) // 返回实际存在的条目数
                                .doOnSuccess(count -> log.info(
                                        "已移除 '{}' 个条目（版本 '{}'）（请求了 '{}' 个 ID，其中 '{}' 个存在）",
                                        count, versionId, itemIds.size(), existingCount))
                                .doOnError(e -> log.error("从版本 '{}'（数据集 '{}'）移除条目失败",
                                        versionId, datasetId, e));
                    })
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> removeItemsFromVersionByFilters(@NonNull UUID datasetId, @NonNull UUID versionId,
            List<DatasetItemFilter> filters, @NonNull String workspaceId) {

        // null 或空过滤器列表表示“删除全部”（无过滤器 = 匹配所有）
        log.info("从版本 '{}'（数据集 '{}'）使用 '{}' 个过滤器移除条目（null 或空 = 删除全部）",
                versionId, datasetId, filters != null ? filters.size() : 0);

        return asyncTemplate.nonTransaction(connection -> {
            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                    "remove_items_from_version_by_filters");

            // 首先统计将被删除的条目数
            return countItemsMatchingFilters(datasetId, versionId, filters, workspaceId)
                    .flatMap(deletedCount -> {
                        if (deletedCount == 0) {
                            log.info("没有条目匹配版本 '{}' 的过滤器", versionId);
                            return Mono.just(0L);
                        }

                        // 使用 StringTemplate 构建过滤器查询
                        // 空过滤器表示“删除全部”——没有过滤条件
                        Optional<String> filterConditionsOpt = CollectionUtils.isEmpty(filters)
                                ? Optional.empty()
                                : FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM);

                        // 使用 StringTemplate 生成带可选过滤条件的查询
                        var template = new ST(DELETE_ITEMS_FROM_VERSION);
                        filterConditionsOpt.ifPresent(filterConditions -> template.add("dataset_item_filters",
                                filterConditions));
                        String deleteQuery = template.render();

                        var statement = connection.createStatement(deleteQuery)
                                .bind("dataset_id", datasetId.toString())
                                .bind("version_id", versionId.toString())
                                .bind("workspace_id", workspaceId);

                        // 使用 FilterQueryBuilder 绑定过滤参数（仅当存在过滤器时）
                        if (CollectionUtils.isNotEmpty(filters)) {
                            statement = FilterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
                        }

                        return Flux.from(statement.execute())
                                .flatMap(Result::getRowsUpdated)
                                .reduce(0L, Long::sum)
                                .map(results -> deletedCount) // 返回之前计算出的计数
                                .doOnSuccess(
                                        count -> log.info("已移除 '{}' 个条目（版本 '{}'）", count, versionId))
                                .doOnError(e -> log.error(
                                        "使用过滤器从版本 '{}'（数据集 '{}'）移除条目失败",
                                        versionId, datasetId, e));
                    })
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    /**
     * 统计特定版本中匹配给定过滤器的条目。
     * 用于在执行删除前确定将删除多少个条目。
     */
    private Mono<Long> countItemsMatchingFilters(UUID datasetId, UUID versionId, List<DatasetItemFilter> filters,
            String workspaceId) {

        return asyncTemplate.nonTransaction(connection -> {
            // 空过滤器表示“统计全部”——没有过滤条件
            Optional<String> filterConditionsOpt = CollectionUtils.isEmpty(filters)
                    ? Optional.empty()
                    : FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM);

            // 使用 StringTemplate 生成带可选过滤条件的查询
            var template = new ST(COUNT_ITEMS);
            filterConditionsOpt.ifPresent(filterConditions -> template.add("dataset_item_filters", filterConditions));
            String countQuery = template.render();

            var statement = connection.createStatement(countQuery)
                    .bind("dataset_id", datasetId.toString())
                    .bind("version_id", versionId.toString())
                    .bind("workspace_id", workspaceId);

            // 绑定过滤参数（仅当存在过滤器时）
            if (CollectionUtils.isNotEmpty(filters)) {
                statement = FilterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L);
        });
    }

    @Override
    @WithSpan
    public Mono<Long> countRowsInVersion(@NonNull UUID datasetId, @NonNull UUID versionId,
            @NonNull Set<UUID> excludedIds, List<DatasetItemFilter> excludeFilters,
            @NonNull String workspaceId) {

        return asyncTemplate.nonTransaction(connection -> {
            Optional<String> filterConditionsOpt = CollectionUtils.isEmpty(excludeFilters)
                    ? Optional.empty()
                    : FilterQueryBuilder.toAnalyticsDbFilters(excludeFilters, FilterStrategy.DATASET_ITEM);

            ST template = getSTWithLogComment(COUNT_ROWS_IN_VERSION, "count_rows_in_version", workspaceId, "",
                    datasetId);
            filterConditionsOpt.ifPresent(filterConditions -> template.add("exclude_filters", filterConditions));
            if (CollectionUtils.isNotEmpty(excludedIds)) {
                template.add("exclude_ids", true);
            }
            String countQuery = template.render();

            var statement = connection.createStatement(countQuery)
                    .bind("dataset_id", datasetId.toString())
                    .bind("version_id", versionId.toString())
                    .bind("workspace_id", workspaceId);

            if (CollectionUtils.isNotEmpty(excludedIds)) {
                String[] excludedIdStrings = excludedIds.stream()
                        .map(UUID::toString)
                        .toArray(String[]::new);
                statement.bind("excluded_ids", excludedIdStrings);
            }

            if (CollectionUtils.isNotEmpty(excludeFilters)) {
                statement = FilterQueryBuilder.bind(statement, excludeFilters, FilterStrategy.DATASET_ITEM);
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L);
        });
    }

    /**
     * 按特定版本中的 ID 统计条目。
     * 用于在删除前确定请求的 ID 中实际存在多少个。
     */
    private Mono<Long> countItemsByIds(UUID datasetId, UUID versionId, Set<UUID> itemIds, String workspaceId) {
        return asyncTemplate.nonTransaction(connection -> {
            var template = new ST(COUNT_ITEMS);
            template.add("item_ids", true); // 启用 item_ids 条件
            String countQuery = template.render();

            var statement = connection.createStatement(countQuery)
                    .bind("dataset_id", datasetId.toString())
                    .bind("version_id", versionId.toString())
                    .bind("workspace_id", workspaceId)
                    .bind("item_ids", itemIds.toArray(UUID[]::new));

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L);
        });
    }

    /**
     * 将 Instant 格式化为 ClickHouse 的 DateTime64(9, 'UTC')。
     * ClickHouse 不接受 ISO-8601 格式中的 'Z' 后缀。
     */
    private static String formatTimestamp(Instant timestamp) {
        if (timestamp == null) {
            return Instant.now().toString().replace("Z", "");
        }
        return timestamp.toString().replace("Z", "");
    }

    private static String base64Encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String serializeEvaluators(List<EvaluatorItem> evaluators) {
        if (evaluators == null || evaluators.isEmpty()) {
            return EvaluatorItem.EMPTY_LIST_JSON;
        }
        return JsonUtils.writeValueAsString(evaluators);
    }

    private static String serializeExecutionPolicy(ExecutionPolicy executionPolicy) {
        if (executionPolicy == null) {
            return "";
        }
        return JsonUtils.writeValueAsString(executionPolicy);
    }

    @Override
    @WithSpan
    public Mono<UUID> resolveDatasetIdFromItemId(@NonNull UUID datasetItemId) {
        log.debug("正在解析条目 '{}' 的数据集 ID", datasetItemId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(RESOLVE_DATASET_ID_FROM_ITEM_ID)
                    .bind("datasetItemId", datasetItemId.toString());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "resolve_dataset_id_from_item_id");

            return makeMonoContextAware((userName, workspaceId) -> {
                statement.bind("workspace_id", workspaceId);

                return Flux.from(statement.execute())
                        .flatMap(result -> result
                                .map((row, rowMetadata) -> UUID.fromString(row.get("dataset_id", String.class))))
                        .next()
                        .doOnSuccess(datasetId -> {
                            if (datasetId != null) {
                                log.debug("已解析出数据集 '{}'（条目 '{}'）", datasetId, datasetItemId);
                            } else {
                                log.debug("未为条目 '{}' 找到数据集", datasetItemId);
                            }
                        })
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Mono<List<UUID>> resolveDatasetIdsFromItemIds(@NonNull Set<UUID> datasetItemIds) {
        if (datasetItemIds.isEmpty()) {
            log.debug("提供了空的 dataset_item_ids 集合，返回空列表");
            return Mono.just(List.of());
        }

        log.debug("从 '{}' 个条目 ID 解析数据集 ID", datasetItemIds.size());

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(RESOLVE_DATASET_ID_FROM_ITEM_IDS)
                    .bind("datasetItemIds", datasetItemIds.stream()
                            .map(UUID::toString)
                            .toArray(String[]::new));

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "resolve_dataset_id_from_item_ids");

            return makeMonoContextAware((userName, workspaceId) -> {
                statement.bind("workspace_id", workspaceId);

                return Flux.from(statement.execute())
                        .flatMap(result -> result
                                .map((row, rowMetadata) -> UUID.fromString(row.get("dataset_id", String.class))))
                        .collectList()
                        .doOnSuccess(datasetIds -> log.debug("已解析出 '{}' 个数据集（来自 '{}' 个条目 ID）",
                                datasetIds.size(), datasetItemIds.size()))
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> getItemByDatasetItemId(@NonNull UUID datasetId, @NonNull UUID versionId,
            @NonNull UUID datasetItemId) {
        log.debug("按 dataset_item_id '{}' 从数据集 '{}' 版本 '{}' 获取条目", datasetItemId, datasetId,
                versionId);

        // 为保持一致性，使用单个条目的批量方法
        return getItemsByDatasetItemIds(datasetId, versionId, Set.of(datasetItemId)).next();
    }

    @WithSpan
    private Flux<DatasetItem> getItemsByDatasetItemIds(@NonNull UUID datasetId, @NonNull UUID versionId,
            @NonNull Set<UUID> datasetItemIds) {
        if (datasetItemIds.isEmpty()) {
            return Flux.empty();
        }

        log.debug("按 dataset_item_ids 获取 '{}' 个条目（数据集 '{}'，版本 '{}'）", datasetItemIds.size(),
                datasetId, versionId);

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(SELECT_ITEMS_BY_DATASET_ITEM_IDS)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId.toString())
                    .bind("datasetItemIds", datasetItemIds.toArray(UUID[]::new));

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_items_by_dataset_item_ids");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result
                            .map(this::mapVersionedItemToDatasetItem));
        });
    }

    private DatasetItem mapVersionedItemToDatasetItem(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata rowMetadata) {
        // 映射 data 字段——在 ClickHouse 中存储为 Map<String, String>
        Map<String, JsonNode> data = Optional.ofNullable(row.get("data", Map.class))
                .filter(m -> !m.isEmpty())
                .map(value -> (Map<String, String>) value)
                .stream()
                .map(Map::entrySet)
                .flatMap(Collection::stream)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> getJsonNodeFromStringWithFallback(entry.getValue())));

        UUID id = UUID.fromString(row.get("id", String.class));
        return DatasetItem.builder()
                .id(id)
                .datasetItemId(id)
                .datasetId(UUID.fromString(row.get("dataset_id", String.class)))
                .data(data.isEmpty() ? null : data)
                .description(DatasetItemResultMapper.getDescription(row, rowMetadata))
                .source(Optional.ofNullable(row.get("source", String.class))
                        .map(com.comet.opik.api.DatasetItemSource::fromString)
                        .orElse(null))
                .traceId(Optional.ofNullable(row.get("trace_id", String.class))
                        .filter(s -> !s.isBlank())
                        .map(UUID::fromString)
                        .orElse(null))
                .spanId(Optional.ofNullable(row.get("span_id", String.class))
                        .filter(s -> !s.isBlank())
                        .map(UUID::fromString)
                        .orElse(null))
                .tags(Optional.ofNullable(row.get("tags", String[].class))
                        .map(java.util.Arrays::asList)
                        .map(Set::copyOf)
                        .orElse(null))
                .evaluators(DatasetItemResultMapper.getEvaluators(row, rowMetadata))
                .executionPolicy(DatasetItemResultMapper.getExecutionPolicy(row, rowMetadata))
                .createdAt(DatasetItemResultMapper.nullIfEpoch(row.get("created_at", Instant.class)))
                .lastUpdatedAt(DatasetItemResultMapper.nullIfEpoch(row.get("last_updated_at", Instant.class)))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build();
    }

    private Mono<Set<Column>> getColumns(UUID datasetId, String versionId) {
        log.debug("获取数据集 '{}'、版本 '{}' 的列", datasetId, versionId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_COLUMNS_BY_VERSION)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId);

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_columns_by_version");

            return makeMonoContextAware((userName, workspaceId) -> {
                statement.bind("workspace_id", workspaceId);

                return Flux.from(statement.execute())
                        .flatMap(result -> DatasetItemResultMapper.mapColumns(result, "data"))
                        .next()
                        .defaultIfEmpty(Set.of())
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> getItemById(@NonNull UUID id) {
        return getItemById(id, null);
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> getItemById(@NonNull UUID id, UUID datasetVersionId) {
        log.debug("按 ID '{}'、版本 '{}' 获取条目", id, datasetVersionId);

        var template = TemplateUtils.newST(SELECT_ITEM_BY_ID);
        if (datasetVersionId != null) {
            template.add("dataset_version_id", true);
        }
        var query = template.render();

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(query)
                    .bind("id", id.toString());

            if (datasetVersionId != null) {
                statement.bind("dataset_version_id", datasetVersionId.toString());
            }

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_item_by_id");

            return makeMonoContextAware((userName, workspaceId) -> {
                statement.bind("workspace_id", workspaceId);

                return Flux.from(statement.execute())
                        .flatMap(result -> result
                                .map(this::mapVersionedItemToDatasetItem))
                        .next()
                        .doOnSuccess(item -> {
                            if (item != null) {
                                log.debug("已按 ID '{}'、版本 '{}' 找到条目", id, datasetVersionId);
                            } else {
                                log.debug("按 ID '{}'、版本 '{}' 未找到条目", id, datasetVersionId);
                            }
                        })
                        .doFinally(signalType -> endSegment(segment));
            });
        });
    }

    @Override
    @WithSpan
    public Mono<List<WorkspaceAndResourceId>> getDatasetItemWorkspace(@NonNull Set<UUID> datasetItemRowIds) {
        if (datasetItemRowIds.isEmpty()) {
            return Mono.just(List.of());
        }

        log.debug("获取 '{}' 个数据集条目行 ID 的工作区 ID", datasetItemRowIds.size());

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_DATASET_WORKSPACE_ITEMS_BY_ROW_IDS)
                    .bind("datasetItemRowIds", datasetItemRowIds.toArray(UUID[]::new));

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "get_dataset_item_workspace");

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
                            row.get("workspace_id", String.class),
                            UUID.fromString(row.get("id", String.class)))))
                    .collectList()
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Flux<DatasetItemPolicyEntry> getExecutionPoliciesByDatasetItemIds(
            @NonNull Set<UUID> datasetItemIds,
            @NonNull Set<UUID> datasetVersionIds) {
        if (datasetItemIds.isEmpty() || datasetVersionIds.isEmpty()) {
            return Flux.empty();
        }

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(SELECT_EXECUTION_POLICIES_BY_DATASET_ITEM_IDS)
                    .bind("datasetItemIds", datasetItemIds.toArray(UUID[]::new))
                    .bind("datasetVersionIds", datasetVersionIds.toArray(UUID[]::new));

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE,
                    "get_execution_policies_by_dataset_item_ids");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, rowMetadata) -> {
                        var datasetItemId = UUID.fromString(row.get("id", String.class));
                        var versionId = UUID.fromString(row.get("dataset_version_id", String.class));
                        var policy = ExecutionPolicyMapper.fromJson(row.get("execution_policy", String.class));
                        return new DatasetItemPolicyEntry(versionId, datasetItemId, policy);
                    }))
                    .filter(entry -> entry.policy() != null);
        });
    }

    @Override
    @WithSpan
    public Mono<ProjectStats> getExperimentItemsStats(
            @NonNull UUID datasetId,
            @NonNull UUID versionId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        log.info("获取数据集 '{}'、版本 '{}'、实验 '{}' 带过滤器 '{}' 的实验条目统计",
                datasetId, versionId, experimentIds, filters);

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            var aggregationCriteria = AggregationBranchCountsCriteria.builder()
                    .datasetId(datasetId)
                    .experimentIds(experimentIds)
                    .build();
            var targetProjectIdsMono = getTargetProjectIds(workspaceId, datasetId, experimentIds);
            var branchCountsMono = getAggregationBranchCounts(aggregationCriteria);

            return Mono.zip(targetProjectIdsMono, branchCountsMono)
                    .flatMap(preQueryResults -> {
                        var targetProjectIds = preQueryResults.getT1();
                        var counts = preQueryResults.getT2();

                        boolean hasAggregated = counts.hasAggregated();
                        boolean hasRaw = counts.hasRaw();

                        var template = TemplateUtils.newST(SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_STATS);

                        if (CollectionUtils.isNotEmpty(experimentIds)) {
                            template.add("experiment_ids", true);
                        }

                        template.add("has_aggregated", hasAggregated);
                        template.add("has_raw", hasRaw);

                        applyFiltersToTemplate(template, filters);

                        if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                            template.add("has_target_projects", true);
                        }

                        String sql = template.render();

                        return asyncTemplate.nonTransaction(connection -> {
                            Statement statement = connection.createStatement(sql);
                            bindStatementParameters(statement, datasetId, versionId, experimentIds, filters);

                            if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                                statement.bind("target_project_ids", targetProjectIds.toArray(UUID[]::new));
                            }

                            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                                    .flatMap(result -> result.map(
                                            (row, rowMetadata) -> com.comet.opik.domain.stats.StatsMapper
                                                    .mapExperimentItemsStats(row)))
                                    .singleOrEmpty();
                        });
                    });
        })
                .doOnError(error -> log.error("获取实验条目统计失败", error));
    }

    private void applyFiltersToTemplate(ST template, List<ExperimentsComparisonFilter> filters) {
        Optional.ofNullable(filters)
                .ifPresent(filtersParam -> {
                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.EXPERIMENT_ITEM)
                            .ifPresent(experimentItemFilters -> template.add("experiment_item_filters",
                                    experimentItemFilters));

                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(feedbackScoresFilters -> template.add("feedback_scores_filters",
                                    feedbackScoresFilters));

                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoresEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoresEmptyFilters));

                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.DATASET_ITEM)
                            .ifPresent(datasetItemFilters -> template.add("dataset_item_filters",
                                    datasetItemFilters));

                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_AGGREGATED)
                            .ifPresent(feedbackScoresAggFilters -> template.add("feedback_scores_filters_agg",
                                    feedbackScoresAggFilters));

                    FilterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY)
                            .ifPresent(feedbackScoresAggEmptyFilters -> template.add(
                                    "feedback_scores_empty_filters_agg", feedbackScoresAggEmptyFilters));
                });
    }

    private void bindStatementParameters(Statement statement, UUID datasetId, UUID versionId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        statement.bind("datasetId", datasetId);
        statement.bind("versionId", versionId);
        if (CollectionUtils.isNotEmpty(experimentIds)) {
            statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
        }

        Optional.ofNullable(filters)
                .ifPresent(filtersParam -> {
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.EXPERIMENT_ITEM);
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES);
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.DATASET_ITEM);
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_AGGREGATED);
                    FilterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY);
                });
    }

    @Override
    public Mono<Long> deleteItemsFromVersion(UUID datasetId, UUID versionId, String workspaceId) {
        log.debug("从版本 '{}' 中删除数据集 '{}' 在工作区 '{}' 的条目", versionId, datasetId,
                workspaceId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(DELETE_ITEMS_FROM_VERSION_MIGRATION)
                    .bind("workspaceId", workspaceId)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId.toString());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "delete_items_from_version_migration");

            return Flux.from(statement.execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .next()
                    .defaultIfEmpty(0L)
                    .doOnSuccess(count -> log.debug("已删除 '{}' 个条目（版本 '{}'）", count, versionId))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    public Mono<Long> copyItemsFromLegacy(UUID datasetId, UUID versionId, String workspaceId) {
        log.debug("将数据集 '{}' 的条目从旧版表复制到版本 '{}'（工作区 '{}'）", datasetId,
                versionId, workspaceId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(COPY_ITEMS_FROM_LEGACY)
                    .bind("workspaceId", workspaceId)
                    .bind("versionId", versionId.toString())
                    .bind("datasetId", datasetId.toString());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "copy_items_from_legacy");

            return Flux.from(statement.execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .next()
                    .defaultIfEmpty(0L)
                    .doOnSuccess(count -> log.debug("已从旧版表复制 '{}' 个条目（数据集 '{}'）", count,
                            datasetId))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    public Mono<Long> countItemsInVersion(UUID datasetId, UUID versionId, String workspaceId) {
        log.debug("统计版本 '{}' 中数据集 '{}'（工作区 '{}'）的条目", versionId, datasetId,
                workspaceId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(COUNT_ITEMS_IN_VERSION)
                    .bind("workspaceId", workspaceId)
                    .bind("datasetId", datasetId.toString())
                    .bind("versionId", versionId.toString());

            Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "count_items_in_version");

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L)
                    .doOnSuccess(count -> log.debug("已统计 '{}' 个条目（版本 '{}'）", count, versionId))
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    public Flux<DatasetVersionItemsCount> countItemsInVersionsBatch(List<DatasetVersionInfo> versions) {
        if (versions.isEmpty()) {
            log.debug("没有需要统计条目的版本");
            return Flux.empty();
        }

        log.debug("批量为 '{}' 个版本统计条目", versions.size());

        // 构建元组 IN 子句以匹配 ClickHouse 排序键
        // 格式：('workspace1', 'dataset1', 'version1'), ('workspace2', 'dataset2', 'version2'), ...
        String versionTuples = versions.stream()
                .map(v -> "('" + v.workspaceId() + "', '" + v.datasetId() + "', '" + v.versionId() + "')")
                .collect(Collectors.joining(", "));

        String query = COUNT_ITEMS_IN_VERSIONS_BATCH.replace("<version_tuples>", versionTuples);

        Segment segment = startSegment(DATASET_ITEM_VERSIONS, CLICKHOUSE, "count_items_in_versions_batch");

        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(query);

            // 注意：这里不使用 bindWorkspaceIdToFlux，因为 workspace_id 已显式
            // 包含在查询元组中。这是一个跨工作区的迁移查询。
            return Flux.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, rowMetadata) -> {
                        UUID versionId = Optional.ofNullable(row.get("dataset_version_id", String.class))
                                .map(UUID::fromString)
                                .orElseThrow(() -> new IllegalStateException("dataset_version_id cannot be null"));
                        long count = Optional.ofNullable(row.get("count", Long.class))
                                .orElse(0L);
                        return DatasetVersionItemsCount.builder()
                                .versionId(versionId)
                                .count(count)
                                .build();
                    }));
        })
                .collectList()
                .doOnSuccess(itemCounts -> log.debug("已完成对 '{}' 个版本的条目统计", itemCounts.size()))
                .flatMapMany(Flux::fromIterable);
    }

    /**
     * 在数据集条目 + 实验条目查询的聚合分支上有条件地启用 push-top-limit 优化，
     * 返回是否已应用，以便调用方可以相应地绑定 {@code top_limit}/{@code top_offset} 参数。
     *
     * <p>该优化将结果页包装在 {@code top_dataset_items} CTE 中，
     * 预解析该页的 {@code dataset_item_id}s，因此对
     * {@code dataset_item_id} 的 IN 过滤可以通过现有的 minmax / bloom_filter
     * 跳过索引同时剪枝 EIA 外层扫描和
     * {@code dataset_items_aggr_resolved} 去重 CTE。
     *
     * <p>聚合分支的过滤器在其 {@code LIMIT} 之前折叠进 Top-N CTE：
     * <ul>
     *     <li>仅 EIA 的过滤器（{@code experiment_item_filters}、
     *         {@code feedback_scores_filters_agg}、{@code feedback_scores_empty_filters_agg}）
     *         会被直接内联。</li>
     *     <li>{@code dataset_item_filters} 驱动一个精简的 {@code dataset_items_filtered_ids}
     *         CTE；其 ID 集合通过 {@code arrayJoin([id, row_id]) IN} 馈入 Top-N CTE。</li>
     * </ul>
     *
     * <p>在以下情况下跳过该优化：
     * <ul>
     *     <li>查询不在仅聚合分支上（{@code !hasAggregated || hasRaw}）。</li>
     *     <li>搜索处于活跃状态——搜索引用 {@code di.data} 并强制采用 post-DI 放置，
     *         而该放置方式被测量为净回归。</li>
     *     <li>排序需要 DI 字段且存在任何过滤器——post-DI 的 Top-N CTE
     *         不会折叠过滤器；在那里折叠会重复外层的 JOIN+GROUP BY。</li>
     *     <li>提供的排序字段不被
     *         {@link SortingFactoryDatasets#supportsPushTopLimit} 支持。</li>
     * </ul>
     *
     * <p>视情况设置 {@code push_top_limit}、{@code top_sorting} 和 {@code push_top_needs_div}
     * 模板变量。
     *
     * @return 当优化被应用到模板时返回 {@code true}，否则返回 {@code false}。
     */
    private boolean applyPushTopLimit(ST template, DatasetItemSearchCriteria criteria,
            boolean hasAggregated, boolean hasRaw) {
        boolean hasSortingFields = CollectionUtils.isNotEmpty(criteria.sortingFields());
        boolean hasFilters = CollectionUtils.isNotEmpty(criteria.filters());
        boolean hasSearch = StringUtils.isNotBlank(criteria.search());
        boolean isDiNeededForSort = hasSortingFields
                && sortingFactory.pushTopLimitNeedsDivJoin(criteria.sortingFields());
        boolean pushTopLimit = hasAggregated && !hasRaw
                && !hasSearch
                && !(isDiNeededForSort && hasFilters)
                && (!hasSortingFields
                        || sortingFactory.supportsPushTopLimit(criteria.sortingFields()));

        if (pushTopLimit) {
            template.add("push_top_limit", true);
            if (hasSortingFields) {
                template.add("top_sorting", buildTopItemsSorting(criteria.sortingFields()));
                if (isDiNeededForSort) {
                    template.add("push_top_needs_div", true);
                }
            }
        }
        return pushTopLimit;
    }

    /**
     * 构建 top_dataset_items CTE 的 ORDER BY 表达式。
     * 使用 experiment_item_aggregates (eia_t) 以及可选的 dataset_items_aggr_resolved (di_t)
     * 将外层查询字段名映射到 CTE 上下文的表达式。
     */
    private String buildTopItemsSorting(List<com.comet.opik.api.sorting.SortingField> sortingFields) {
        String primarySort = sortingFields.stream()
                .map(sf -> {
                    String expr = getTopSortExpression(sf);
                    String dir = sf.direction() != null ? sf.direction().name() : "ASC";
                    return expr + " " + dir;
                })
                .collect(Collectors.joining(", "));
        return primarySort
                + ", eia_t.dataset_item_id DESC";
    }

    private String getTopSortExpression(com.comet.opik.api.sorting.SortingField sf) {
        String field = sf.field();

        if ("id".equals(field)) {
            return "eia_t.dataset_item_id";
        }
        if ("description".equals(field)) {
            return "any(di_t.description)";
        }
        if ("tags".equals(field)) {
            return "any(di_t.tags)";
        }
        if ("created_at".equals(field)) {
            return "any(di_t.item_created_at)";
        }
        if ("last_updated_at".equals(field)) {
            return "any(di_t.item_last_updated_at)";
        }
        if ("created_by".equals(field)) {
            return "any(di_t.item_created_by)";
        }
        if ("last_updated_by".equals(field)) {
            return "any(di_t.item_last_updated_by)";
        }
        if ("duration".equals(field)) {
            return "avg(eia_t.duration)";
        }
        if ("total_estimated_cost".equals(field)) {
            return "avg(eia_t.total_estimated_cost)";
        }
        if (field.startsWith("data.")) {
            return "any(di_t.data)[:%s]".formatted(sf.bindKey());
        }
        if (field.startsWith("usage.")) {
            return "avgMap(eia_t.usage)[:%s]".formatted(sf.bindKey());
        }
        if (field.startsWith("feedback_scores.")) {
            return "avgMap(eia_t.feedback_scores)[:%s]".formatted(sf.bindKey());
        }
        if (field.startsWith("input.")) {
            return "JSONExtractRaw(argMax(eia_t.input, eia_t.id), :%s)".formatted(sf.bindKey());
        }
        if (field.startsWith("output.")) {
            return "JSONExtractRaw(argMax(eia_t.output, eia_t.id), :%s)".formatted(sf.bindKey());
        }
        if (field.startsWith("metadata.")) {
            return "JSONExtractRaw(argMax(eia_t.metadata, eia_t.id), :%s)".formatted(sf.bindKey());
        }

        // 回退——如果先检查了 supportsPushTopLimit，则不应走到这里
        return "eia_t.dataset_item_id";
    }

}
