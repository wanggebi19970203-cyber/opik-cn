package com.comet.opik.domain.experiments.aggregations;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.api.AssertionScoreAverage;
import com.comet.opik.api.DatasetItem.DatasetItemPage;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentGroupAggregationItem;
import com.comet.opik.api.ExperimentGroupCriteria;
import com.comet.opik.api.ExperimentGroupItem;
import com.comet.opik.api.ExperimentScore;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.domain.CommentResultMapper;
import com.comet.opik.domain.DatasetItemResultMapper;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetItemSearchCriteriaMapper;
import com.comet.opik.domain.ExperimentGroupMappers;
import com.comet.opik.domain.ExperimentSearchCriteriaBinder;
import com.comet.opik.domain.FeedbackScoreMapper;
import com.comet.opik.domain.GroupingQueryBuilder;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.MapArrays;
import com.comet.opik.domain.experiments.aggregations.ExperimentEntityData.ExperimentItemData;
import com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.SpanData;
import com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.TraceData;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.ExperimentGroupMappers.bindGroupCriteria;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.AssertionScoreAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.FeedbackScoreAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.PassRateAggregation;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.SpanAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesModel.TraceAggregations;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.BatchResult;
import static com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesUtils.resolveLabelProjectId;
import static com.comet.opik.domain.experiments.aggregations.ExperimentEntityData.ExperimentData;
import static com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.AssertionData;
import static com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.CommentsData;
import static com.comet.opik.domain.experiments.aggregations.ExperimentSourceData.FeedbackScoreData;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(ExperimentAggregatesDAOImpl.class)
public interface ExperimentAggregatesDAO {

    Mono<Void> populateExperimentAggregate(UUID experimentId);

    Mono<Set<UUID>> getProjectIds(UUID experimentId);

    Mono<UUID> getExperimentProjectId(UUID experimentId);

    Mono<BatchResult> populateExperimentItemAggregates(
            UUID experimentId, Set<UUID> projectIds, UUID labelProjectId, UUID cursor, int limit);

    Mono<Experiment> getExperimentFromAggregates(UUID experimentId);

    Mono<Long> countTotal(ExperimentSearchCriteria experimentSearchCriteria);

    Flux<ExperimentGroupItem> findGroups(ExperimentGroupCriteria criteria);

    Flux<ExperimentGroupAggregationItem> findGroupsAggregations(ExperimentGroupCriteria criteria);

    Mono<Long> countDatasetItemsWithExperimentItemsFromAggregates(DatasetItemSearchCriteria criteria, UUID versionId);

    Mono<DatasetItemPage> getDatasetItemsWithExperimentItemsFromAggregates(DatasetItemSearchCriteria criteria,
            UUID versionId, int page, int size);

    Mono<ProjectStats> getExperimentItemsStatsFromAggregates(UUID datasetId, UUID versionId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters);

    /**
     * 统计已聚合和未聚合的实验，以便调用方可以丢弃那些不可能产生任何行的查询分支。
     * <p>
     * 当设置 {@link AggregationBranchCountsCriteria#projectId()} 时，未聚合计数会被限制为从该项目可达的实验。
     * 可达性遵循实验自身的项目及其条目所引用的 trace 所属的项目。
     * <p>
     * 该限制仅适用于未聚合计数，且不得提升到外层过滤条件中。两个分支对项目归属的判定方式不同：原始分支来自
     * trace 和 {@code project_id}，聚合分支来自 {@code experiment_aggregates.project_id}。存在这样的聚合实验：
     * 其存储的项目已无法从其 trace 可达，因为这些 trace 在聚合之后被删除了。按原始分支的可达性概念过滤每一行
     * 会把此类实验从聚合计数中丢弃，进而丢弃返回它们的聚合分支。
     * <p>
     * trace 查找被有意限制为出现在 {@code experiment_items} 中的 trace id。一个项目可能包含数千万条 trace，
     * 而一个工作空间中的实验条目不足一百万条，因此仅从项目出发进行查找会构建出一个庞大的集合：针对一个有
     * 七百万条 trace 的项目进行测量，它读取了 719 万行、占用 1.4 GiB，而加上该限制后仅读取 19.7 万行、占用
     * 12 MiB，结果完全相同。没有该限制，这次计数的开销可能比它本应消除的那个分支还要高。
     */
    Mono<AggregatedExperimentCounts> getAggregationBranchCounts(AggregationBranchCountsCriteria criteria);

    Mono<Long> deleteByExperimentIds(Set<UUID> experimentIds);

    Mono<Long> deleteItemAggregatesByItemIds(UUID experimentId, Set<UUID> itemIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ExperimentAggregatesDAOImpl implements ExperimentAggregatesDAO {

    private static final TypeReference<List<ExperimentScore>> TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final String EMPTY_ARRAY_STR = "[]";

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull GroupingQueryBuilder groupingQueryBuilder;
    private final @NonNull Client clickHouseClient;

    /**
     * 用于实验聚合搜索绑定的过滤策略。
     * 在所有实验搜索操作中复用，以避免重复分配。
     */
    private static final List<FilterStrategy> FILTER_STRATEGIES = List.of(
            FilterStrategy.EXPERIMENT,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
            FilterStrategy.EXPERIMENT_SCORES,
            FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY);

    private static final List<FilterQueryBuilder.FilterStrategyParam> DATASET_ITEM_FILTER_STRATEGY_PARAMS = List.of(
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.EXPERIMENT_ITEM, "experiment_item_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES_AGGREGATED,
                    "feedback_scores_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
                    "feedback_scores_empty_filters"),
            new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.DATASET_ITEM, "dataset_item_filters"));

    private static final List<FilterStrategy> DATASET_ITEM_BIND_STRATEGIES = List.of(
            FilterStrategy.EXPERIMENT_ITEM,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
            FilterStrategy.DATASET_ITEM);

    private static final List<FilterQueryBuilder.FilterStrategyParam> EXPERIMENT_ITEMS_STATS_FILTER_STRATEGY_PARAMS = List
            .of(
                    new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.EXPERIMENT_ITEM,
                            "experiment_item_filters"),
                    new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES,
                            "feedback_scores_filters"),
                    new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
                            "feedback_scores_empty_filters"),
                    new FilterQueryBuilder.FilterStrategyParam(FilterStrategy.DATASET_ITEM, "dataset_item_filters"));

    private static final List<FilterStrategy> EXPERIMENT_ITEMS_STATS_BIND_STRATEGIES = List.of(
            FilterStrategy.EXPERIMENT_ITEM,
            FilterStrategy.FEEDBACK_SCORES,
            FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY,
            FilterStrategy.DATASET_ITEM);

    public static final String SELECT_EXPERIMENT_BY_ID = """
            SELECT
                id,
                dataset_id,
                project_id,
                name,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                metadata,
                tags,
                type,
                evaluation_method,
                status,
                optimization_id,
                dataset_version_id,
                prompt_versions,
                experiment_scores,
                trace_count,
                duration_percentiles,
                feedback_scores_percentiles,
                feedback_scores_avg,
                total_estimated_cost_sum,
                total_estimated_cost_avg,
                usage_avg,
                pass_rate,
                passed_count,
                total_count,
                comments_array_agg,
                assertion_scores_avg
            FROM experiment_aggregates FINAL
            WHERE workspace_id = :workspace_id
            AND id = :experiment_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取实验基础数据
     */
    private static final String GET_EXPERIMENT_DATA = """
            SELECT
                workspace_id,
                id,
                dataset_id,
                project_id,
                name,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                metadata,
                prompt_versions,
                optimization_id,
                dataset_version_id,
                tags,
                type,
                evaluation_method,
                status,
                experiment_scores
            FROM experiments
            WHERE workspace_id = :workspace_id
            AND id = :experiment_id
            ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 该实验条目所引用的去重 project_ids，从 {@code traces} 这一唯一事实来源读取。用于驱动每个下游聚合查询中的
     * {@code project_id IN :project_ids} 过滤，使总计覆盖所有被引用的项目，同时保留分区裁剪。
     */
    private static final String GET_PROJECT_IDS = """
            SELECT groupUniqArrayIf(toString(project_id), project_id != '') AS project_ids
            FROM traces
            WHERE workspace_id = :workspace_id
            AND id IN (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            )
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取实验的 trace 聚合。{@code project_id IN :project_ids} 在覆盖实验条目所引用的每个项目的同时保留分区裁剪；
     * {@code :project_id} 是写入聚合行的单一标签。
     */
    private static final String GET_TRACE_AGGREGATIONS = """
            WITH experiment_trace_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            ), traces_data AS (
                SELECT
                    id,
                    duration
                FROM traces
                INNER JOIN experiment_trace_items ON traces.id = experiment_trace_items.trace_id
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 by id
            )
            SELECT
                :experiment_id as experiment_id,
                :project_id as project_id,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                        v -> toDecimal64(
                            greatest(
                                least(if(isFinite(toFloat64(v)), v, 0), 999999999.999999999),
                                -999999999.999999999
                            ),
                            9
                        ),
                        quantiles(0.5, 0.9, 0.99)(duration)
                    )
                ) AS duration_percentiles,
                count(DISTINCT id) as trace_count
            FROM traces_data
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取实验的 span 聚合
     */
    private static final String GET_SPAN_AGGREGATIONS = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            ), spans_data AS (
                SELECT
                    trace_id,
                    usage,
                    total_estimated_cost
                FROM spans
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND trace_id IN (SELECT trace_id FROM experiment_items)
                ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 by id
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost
                FROM spans_data
                GROUP BY trace_id
            ), usage_total_tokens_data AS (
                SELECT
                    usage['total_tokens'] AS total_tokens
                FROM spans_agg
                WHERE usage['total_tokens'] IS NOT NULL AND usage['total_tokens'] > 0
            )
            SELECT
                :experiment_id as experiment_id,
                avgMap(usage) as usage_avg,
                coalesce(sum(total_estimated_cost), 0.0) as total_estimated_cost_sum,
                coalesce(avg(total_estimated_cost), 0.0) as total_estimated_cost_avg,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                        v -> toDecimal128(
                            greatest(
                                least(if(isFinite(toFloat64(v)), v, 0), 999999999999.999999999999),
                                -999999999999.999999999999
                            ),
                            12
                        ),
                        quantiles(0.5, 0.9, 0.99)(total_estimated_cost)
                    )
                ) AS total_estimated_cost_percentiles,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                        v -> toInt64(greatest(least(if(isFinite(toFloat64(v)), v, 0), 999999999.999999999), -999999999.999999999)),
                        (SELECT quantiles(0.5, 0.9, 0.99)(total_tokens) FROM usage_total_tokens_data)
                    )
                ) AS usage_total_tokens_percentiles
            FROM spans_agg
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取实验的反馈分数聚合
     */
    private static final String GET_FEEDBACK_SCORE_AGGREGATIONS = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            ), feedback_scores_combined AS (
                SELECT
                    entity_id,
                    name,
                    value
                FROM feedback_scores
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id IN :project_ids
                UNION ALL
                SELECT
                    entity_id,
                    name,
                    value
                FROM authored_feedback_scores
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                AND project_id IN :project_ids
            ), feedback_scores_final AS (
                SELECT
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value
                FROM feedback_scores_combined
                INNER JOIN experiment_items ON feedback_scores_combined.entity_id = experiment_items.trace_id
                GROUP BY entity_id, name
            ), feedback_percentiles AS (
                SELECT
                    name,
                    mapFromArrays(
                        ['p50', 'p90', 'p99'],
                        arrayMap(
                            v -> toDecimal64(
                                greatest(
                                    least(if(isFinite(toFloat64(v)), v, 0), 999999999.999999999),
                                    -999999999.999999999
                                ),
                                9
                            ),
                            quantiles(0.5, 0.9, 0.99)(value)
                        )
                    ) AS percentiles
                FROM feedback_scores_final
                WHERE length(name) > 0
                GROUP BY name
            ), feedback_avg AS (
                SELECT
                    name,
                    toDecimal64(
                        greatest(
                            least(if(isFinite(avg(value)), avg(value), 0), 999999999.999999999),
                            -999999999.999999999
                        ),
                        9
                    ) AS avg_value
                FROM feedback_scores_final
                WHERE length(name) > 0
                GROUP BY name
            )
            SELECT
                :experiment_id as experiment_id,
                (SELECT mapFromArrays(groupArray(name), groupArray(percentiles)) FROM feedback_percentiles) AS feedback_scores_percentiles,
                (SELECT mapFromArrays(groupArray(name), groupArray(avg_value)) FROM feedback_avg) AS feedback_scores_avg
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String GET_PASS_RATE_AGGREGATION = """
            WITH experiment_items_scope AS (
                SELECT DISTINCT dataset_item_id, trace_id, execution_policy, id, experiment_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
                ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_data AS (
                SELECT execution_policy, id
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND id = :experiment_id
                AND evaluation_method = 'evaluation_suite'
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), assertion_results_final AS (
                SELECT
                    entity_id,
                    name,
                    if(count() = 1, any(toFloat64(passed = 'passed')), avg(toFloat64(passed = 'passed'))) AS value
                FROM (
                    SELECT
                        entity_id,
                        name,
                        passed
                    FROM assertion_results
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, author, name) ASC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, entity_type, entity_id, author, name
                )
                GROUP BY entity_id, name
            ), runs AS (
                SELECT
                    ei.dataset_item_id,
                    ei.trace_id,
                    JSONExtractUInt(ei.execution_policy, 'pass_threshold') AS item_pass_threshold,
                    JSONExtractUInt(ed.execution_policy, 'pass_threshold') AS suite_pass_threshold,
                    countIf(ar.name != '') > 0 AS has_assertions,
                    if(
                        countIf(ar.name != '') = 0,
                        0,
                        if(minIf(ar.value, ar.name != '') >= 1.0, 1, 0)
                    ) AS run_passed
                FROM experiment_items_scope ei
                INNER JOIN experiment_data ed ON ei.experiment_id = ed.id
                LEFT JOIN assertion_results_final ar ON ar.entity_id = ei.trace_id
                GROUP BY ei.dataset_item_id, ei.trace_id,
                         item_pass_threshold, suite_pass_threshold
            ), items AS (
                SELECT
                    dataset_item_id,
                    max(has_assertions) AS has_assertions,
                    if(sum(run_passed) >=
                       if(item_pass_threshold > 0, item_pass_threshold,
                          if(suite_pass_threshold > 0, suite_pass_threshold, 1)),
                       1, 0) AS item_passed
                FROM runs
                GROUP BY dataset_item_id, item_pass_threshold, suite_pass_threshold
            )
            SELECT
                :experiment_id as experiment_id,
                toDecimal64(ifNull(sumIf(item_passed, has_assertions) / nullIf(toFloat64(countIf(has_assertions)), 0), 0), 9) AS pass_rate,
                sumIf(item_passed, has_assertions) AS passed_count,
                countIf(has_assertions) AS total_count
            FROM items
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String GET_ASSERTION_SCORE_AGGREGATIONS = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            ), assertion_avg AS (
                SELECT
                    name,
                    toDecimal64(
                        greatest(
                            least(if(isFinite(avg(toFloat64(passed = 'passed'))), avg(toFloat64(passed = 'passed')), 0), 1.0),
                            0.0
                        ),
                        9
                    ) AS avg_value
                FROM (
                    SELECT *
                    FROM assertion_results
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    AND entity_id IN (SELECT trace_id FROM experiment_items)
                    AND length(name) > 0
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, author, name) ASC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, entity_type, entity_id, author, name
                )
                GROUP BY name
            )
            SELECT
                :experiment_id as experiment_id,
                (SELECT mapFromArrays(groupArray(name), groupArray(avg_value)) FROM assertion_avg) AS assertion_scores_avg
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String DELETE_EXPERIMENT_AGGREGATES_BY_IDS = """
            DELETE FROM experiment_aggregates
            WHERE id IN :experiment_ids
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String DELETE_EXPERIMENT_ITEM_AGGREGATES_BY_EXPERIMENT_IDS = """
            DELETE FROM experiment_item_aggregates
            WHERE experiment_id IN :experiment_ids
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String DELETE_EXPERIMENT_ITEM_AGGREGATES_BY_ITEM_IDS = """
            DELETE FROM experiment_item_aggregates
            WHERE id IN :item_ids
            AND experiment_id = :experiment_id
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 插入实验聚合
     */
    private static final String INSERT_EXPERIMENT_AGGREGATE = """
            INSERT INTO experiment_aggregates
            (
                workspace_id,
                id,
                dataset_id,
                project_id,
                name,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                metadata,
                prompt_versions,
                optimization_id,
                dataset_version_id,
                tags,
                type,
                evaluation_method,
                status,
                experiment_scores,
                trace_count,
                experiment_items_count,
                duration_percentiles,
                feedback_scores_percentiles,
                feedback_scores_avg,
                total_estimated_cost_sum,
                total_estimated_cost_avg,
                total_estimated_cost_percentiles,
                usage_avg,
                usage_total_tokens_percentiles,
                pass_rate,
                passed_count,
                total_count,
                comments_array_agg,
                assertion_scores_avg
            )
            SETTINGS log_comment = '<log_comment>'
            VALUES (
                :workspace_id,
                :id,
                :dataset_id,
                :project_id,
                :name,
                :created_at,
                :last_updated_at,
                :created_by,
                :last_updated_by,
                :metadata,
                :prompt_versions,
                :optimization_id,
                :dataset_version_id,
                :tags,
                :type,
                :evaluation_method,
                :status,
                mapFromArrays(:experiment_scores_keys, :experiment_scores_values),
                :trace_count,
                :experiment_items_count,
                mapFromArrays(:duration_percentiles_keys, :duration_percentiles_values),
                :feedback_scores_percentiles,
                mapFromArrays(:feedback_scores_avg_keys, :feedback_scores_avg_values),
                :total_estimated_cost_sum,
                :total_estimated_cost_avg,
                mapFromArrays(:total_estimated_cost_percentiles_keys, :total_estimated_cost_percentiles_values),
                mapFromArrays(:usage_avg_keys, :usage_avg_values),
                mapFromArrays(:usage_total_tokens_percentiles_keys, :usage_total_tokens_percentiles_values),
                :pass_rate,
                :passed_count,
                :total_count,
                :comments_array_agg,
                mapFromArrays(:assertion_scores_avg_keys, :assertion_scores_avg_values)
            )
            ;
            """;

    /**
     * 使用游标分页获取实验条目。
     *
     * <p>OPIK-6177：在聚合时把 {@code ei.dataset_item_id}（旧版按版本 DIV 行 id 或现代的稳定 id）解析为
     * 稳定的 {@code dataset_item_id}。比较读取在读取时也会解析（参见 {@code DatasetItemVersionDAO}），
     * 所以这只是尽力而为的清洗——随着时间推移，它会在无需回填迁移的情况下逐步淘汰 EIA 中的旧值。
     */
    private static final String GET_EXPERIMENT_ITEMS = """
            SELECT
                ei.id AS id,
                ei.experiment_id AS experiment_id,
                ei.trace_id AS trace_id,
                if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, ei.dataset_item_id) AS dataset_item_id,
                ei.created_at AS created_at,
                ei.last_updated_at AS last_updated_at,
                ei.created_by AS created_by,
                ei.last_updated_by AS last_updated_by,
                ei.execution_policy AS execution_policy
            FROM experiment_items AS ei
            LEFT JOIN dataset_item_versions AS lookup_div FINAL
                ON lookup_div.workspace_id = ei.workspace_id
                AND lookup_div.id = ei.dataset_item_id
            WHERE ei.workspace_id = :workspace_id
            AND ei.experiment_id = :experiment_id
            <if(cursor)>AND ei.id > :cursor<endif>
            ORDER BY (ei.workspace_id, ei.experiment_id, ei.id) ASC, ei.last_updated_at DESC
            LIMIT 1 BY ei.id
            LIMIT :limit
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取一批实验条目的 trace 数据，范围限定为实验所引用的项目，以便在多项目实验中保留分区裁剪。
     */
    private static final String GET_TRACES_DATA = """
            SELECT
                id as trace_id,
                project_id,
                if(isNaN(duration), NULL, duration) AS duration,
                metadata,
                input,
                output,
                input_slim,
                output_slim,
                visibility_mode
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id IN :project_ids
            AND id IN :trace_ids
            ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取一批实验条目的 span 数据。多项目范围说明参见 {@link #GET_TRACES_DATA}。
     */
    private static final String GET_SPANS_DATA = """
            SELECT
                trace_id,
                sumMap(usage) as usage,
                sum(total_estimated_cost) as total_estimated_cost
            FROM (
                SELECT
                    trace_id, usage, total_estimated_cost
                FROM spans
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND trace_id IN :trace_ids
                ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, id
            )
            GROUP BY trace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取由 trace_ids 标识的一批实验条目的反馈分数
     */
    private static final String GET_FEEDBACK_SCORES_DATA = """
            WITH feedback_scores_deduped AS (
                SELECT
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
                    AND project_id IN :project_ids
                    AND entity_id IN :trace_ids
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
                    AND project_id IN :project_ids
                    AND entity_id IN :trace_ids
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_grouped_agg AS (
                SELECT
                    entity_id,
                    name,
                    count(*) AS n,
                    avg(value) AS avg_value,
                    any(value) AS any_value,
                    any(reason) AS any_reason,
                    arrayStringConcat(groupArray(if(reason = '', '\\<no reason>', reason)), ', ') AS reason_concat,
                    arrayStringConcat(groupArray(category_name), ', ') AS category_name_concat,
                    any(source) AS source_any,
                    arrayStringConcat(groupArray(created_by), ', ') AS created_by_concat,
                    arrayStringConcat(groupArray(last_updated_by), ', ') AS last_updated_by_concat,
                    min(created_at) AS created_at_min,
                    max(last_updated_at) AS last_updated_at_max,
                    mapFromArrays(
                        groupArray(if(source_queue_id = '', author, concat(author, '_', toString(source_queue_id)))),
                        groupArray(tuple(value, reason, category_name, source, last_updated_at, '', '', source_queue_id, author))
                    ) AS value_by_author
                FROM feedback_scores_deduped
                GROUP BY entity_id, name
            ), feedback_scores_per_name AS (
                SELECT
                    entity_id,
                    name,
                    IF(n = 1, any_value, toDecimal64(avg_value, 9)) AS value,
                    IF(n = 1, any_reason, reason_concat) AS reason,
                    category_name_concat AS category_name,
                    source_any AS source,
                    created_by_concat AS created_by,
                    last_updated_by_concat AS last_updated_by,
                    created_at_min AS created_at,
                    last_updated_at_max AS last_updated_at,
                    value_by_author
                FROM feedback_scores_grouped_agg
            )
            SELECT
                entity_id as trace_id,
                mapFromArrays(
                    groupArray(name),
                    groupArray(value)
                ) AS feedback_scores,
                groupUniqArray(tuple(
                    entity_id,
                    name,
                    category_name,
                    value,
                    reason,
                    source,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    value_by_author
                )) AS feedback_scores_array
            FROM feedback_scores_per_name
            GROUP BY entity_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取由 trace_ids 标识的一批实验条目的评论
     */
    private static final String GET_COMMENTS_DATA = """
            SELECT
                entity_id AS trace_id,
                toJSONString(groupUniqArray(CAST(tuple(
                    id,
                    text,
                    concat(replaceOne(toString(created_at), ' ', 'T'), 'Z'),
                    concat(replaceOne(toString(last_updated_at), ' ', 'T'), 'Z'),
                    created_by,
                    last_updated_by,
                    entity_id
                ), 'Tuple(
                    id FixedString(36),
                    text String,
                    created_at String,
                    last_updated_at String,
                    created_by String,
                    last_updated_by String,
                    entity_id FixedString(36)
                )'))) AS comments_array_agg
            FROM (
                SELECT
                    id,
                    text,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    entity_id
                FROM comments
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND entity_id IN :trace_ids
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY entity_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取由 trace_ids 标识的一批实验条目中每个 trace 的断言数据
     */
    private static final String GET_ASSERTIONS_DATA = """
            SELECT
                entity_id AS trace_id,
                toJSONString(
                    groupArray(
                        CAST(
                            (name, toString(passed), reason),
                            'Tuple(value String, passed String, reason String)'
                        )
                    )
                ) AS assertions_array
            FROM (
                SELECT
                    entity_id,
                    name,
                    passed,
                    reason
                FROM assertion_results
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  AND project_id IN :project_ids
                  AND entity_id IN :trace_ids
                ORDER BY (workspace_id, project_id, entity_type, entity_id, author, name) ASC, last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_type, entity_id, author, name
            )
            GROUP BY entity_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取实验级别的评论聚合
     */
    private static final String GET_COMMENTS_AGGREGATION = """
            WITH experiment_items AS (
                SELECT DISTINCT trace_id
                FROM experiment_items FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id = :experiment_id
            )
            SELECT
                :experiment_id AS experiment_id,
                toJSONString(groupUniqArrayArray(comments_array)) AS comments_array_agg
            FROM (
                SELECT
                    entity_id,
                    groupArray(CAST(tuple(
                        id,
                        text,
                        concat(replaceOne(toString(created_at), ' ', 'T'), 'Z'),
                        concat(replaceOne(toString(last_updated_at), ' ', 'T'), 'Z'),
                        created_by,
                        last_updated_by,
                        entity_id
                    ), 'Tuple(
                        id FixedString(36),
                        text String,
                        created_at String,
                        last_updated_at String,
                        created_by String,
                        last_updated_by String,
                        entity_id FixedString(36)
                    )')) AS comments_array
                FROM (
                    SELECT
                        id,
                        text,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by,
                        entity_id
                    FROM comments
                    WHERE workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    AND entity_id IN (SELECT trace_id FROM experiment_items)
                    ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                GROUP BY entity_id
            )
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 获取实验条目数量
     */
    private static final String GET_EXPERIMENT_ITEMS_COUNT = """
            SELECT count(DISTINCT id) as count
            FROM experiment_items FINAL
            WHERE workspace_id = :workspace_id
            AND experiment_id = :experiment_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_COUNT_FROM_AGGREGATES = """
            SELECT count(id) as count
            FROM experiment_aggregates FINAL
            <if(project_deleted)>
            LEFT JOIN (
                SELECT
                    experiment_id,
                    groupUniqArray(project_id) AS project_ids
                FROM experiment_items FINAL
                INNER JOIN traces FINAL ON experiment_items.trace_id = traces.id
                WHERE experiment_items.workspace_id = :workspace_id
                AND traces.workspace_id = :workspace_id
                <if(has_target_projects)>
                AND traces.project_id IN :target_project_ids
                <endif>
                GROUP BY experiment_id
            ) ep ON experiment_aggregates.id = ep.experiment_id
            <endif>
            WHERE workspace_id = :workspace_id
            <if(dataset_id)> AND dataset_id = :dataset_id <endif>
            <if(optimization_id)> AND optimization_id = :optimization_id <endif>
            <if(types)> AND type IN :types <endif>
            <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
            <if(dataset_ids)> AND dataset_id IN :dataset_ids <endif>
            <if(experiment_ids)> AND id IN :experiment_ids <endif>
            <if(prompt_ids)> AND hasAny(mapKeys(prompt_versions), :prompt_ids) <endif>
            <if(filters)> AND <filters> <endif>
            <if(feedback_scores_aggregated_filters)> AND <feedback_scores_aggregated_filters> <endif>
            <if(feedback_scores_aggregated_empty_filters)> AND <feedback_scores_aggregated_empty_filters> <endif>
            <if(experiment_scores_filters)> AND <experiment_scores_filters> <endif>
            <if(experiment_scores_empty_filters)> AND <experiment_scores_empty_filters> <endif>
            <if(project_id)> AND project_id = :project_id <endif>
            <if(has_target_projects)> AND project_id IN :target_project_ids <endif>
            <if(project_deleted)> AND (has(ep.project_ids, '') OR empty(ep.project_ids)) <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 从 experiment_aggregates 表中查找实验分组。
     * 该查询经过优化，使用聚合后的 project_id 而不是与 traces 进行连接。
     */
    private static final String FIND_GROUPS_FROM_AGGREGATES = """
            SELECT <groupSelects>, max(created_at) AS last_created_experiment_at
            FROM experiment_aggregates FINAL
            WHERE workspace_id = :workspace_id
            <if(types)> AND type IN :types <endif>
            <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
            <if(filters)> AND <filters> <endif>
            <if(project_id)> AND project_id = :project_id <endif>
            <if(project_deleted)> AND project_id = :zero_uuid <endif>
            GROUP BY <groupBy>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 从 experiment_aggregates 表中查找实验分组聚合。
     * 该查询使用预聚合指标，而不是从原始表中计算。
     */
    private static final String FIND_GROUPS_AGGREGATIONS_FROM_AGGREGATES = """
            SELECT
                count(DISTINCT id) as experiment_count,
                sum(trace_count) as trace_count,
                sum(total_estimated_cost_sum) as total_estimated_cost,
                avg(total_estimated_cost_avg) as total_estimated_cost_avg,
                avgMap(feedback_scores_avg) as feedback_scores,
                avgMap(experiment_scores) as experiment_scores,
                avgMap(duration_percentiles) as duration,
                <groupSelects>
            FROM experiment_aggregates FINAL
            WHERE workspace_id = :workspace_id
            <if(types)> AND type IN :types <endif>
            <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
            <if(filters)> AND <filters> <endif>
            <if(project_id)> AND project_id = :project_id <endif>
            <if(project_deleted)> AND project_id = :zero_uuid <endif>
            GROUP BY <groupBy>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 开发/测试对等测试框架（{@link #countDatasetItemsWithExperimentItemsFromAggregates}）的计数查询。
     * 在读取时通过 {@code lookup_div FINAL} LEFT JOIN 把旧版或稳定的 {@code eia.dataset_item_id} 解析为
     * 规范的稳定 id，然后用 {@code COUNT(DISTINCT if(notEmpty(lookup_div.dataset_item_id),
     * lookup_div.dataset_item_id, eia.dataset_item_id))} 去重。生产计数使用不同的稳定 id 预收窄 CTE
     * （{@code lookup_for_count}）以实现跳数索引下推——参见 {@code DatasetItemVersionDAO}；此测试框架路径
     * 不需要它，因为调用方仅针对测试夹具使用它。
     */
    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT = """
            WITH dataset_item_versions_resolved AS (
                SELECT
                    div_dedup.dataset_item_id AS id,
                    div_dedup.id AS row_id,
                    div_dedup.dataset_id AS dataset_id,
                    div_dedup.data AS data,
                    div_dedup.source AS source,
                    div_dedup.trace_id AS trace_id,
                    div_dedup.span_id AS span_id,
                    div_dedup.tags AS tags,
                    div_dedup.evaluators AS evaluators,
                    div_dedup.execution_policy AS execution_policy,
                    div_dedup.created_at AS item_created_at,
                    div_dedup.last_updated_at AS item_last_updated_at,
                    div_dedup.created_by AS item_created_by,
                    div_dedup.last_updated_by AS item_last_updated_by,
                    div_dedup.dataset_version_id AS dataset_version_id
                FROM (
                    SELECT
                        div.id,
                        div.dataset_item_id,
                        div.dataset_id,
                        div.data,
                        div.source,
                        div.trace_id,
                        div.span_id,
                        div.tags,
                        div.evaluators,
                        div.execution_policy,
                        div.created_at,
                        div.last_updated_at,
                        div.created_by,
                        div.last_updated_by,
                        div.dataset_version_id,
                        div.workspace_id
                    FROM dataset_item_versions div
                    INNER JOIN experiment_aggregates ea FINAL ON
                        ea.workspace_id = div.workspace_id
                        AND ea.dataset_id = div.dataset_id
                        AND div.dataset_version_id = COALESCE(nullIf(ea.dataset_version_id, ''), :version_id)
                    WHERE div.workspace_id = :workspace_id
                    AND div.dataset_id = :dataset_id
                    <if(experiment_ids)>AND ea.id IN :experiment_ids<endif>
                    ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                    LIMIT 1 BY div.id
                ) AS div_dedup
            )
            SELECT COUNT(DISTINCT if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, eia.dataset_item_id)) as count
            FROM experiment_item_aggregates eia FINAL
            LEFT JOIN dataset_item_versions AS lookup_div FINAL
                ON lookup_div.workspace_id = eia.workspace_id
                AND lookup_div.id = eia.dataset_item_id
            LEFT JOIN dataset_item_versions_resolved AS di
                ON di.id = if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, eia.dataset_item_id)
            WHERE eia.workspace_id = :workspace_id
            <if(experiment_ids)>AND eia.experiment_id IN :experiment_ids<endif>
            <if(has_target_projects)>AND eia.project_id IN :target_project_ids<endif>
            <if(experiment_item_filters)>AND <experiment_item_filters><endif>
            <if(feedback_scores_filters)>AND <feedback_scores_filters><endif>
            <if(feedback_scores_empty_filters)>AND <feedback_scores_empty_filters><endif>
            <if(dataset_item_filters)>
            AND <dataset_item_filters>
            <endif>
            <if(search)>
            AND (
                multiSearchAnyCaseInsensitive(toString(eia.input), :searchTerms)
                OR multiSearchAnyCaseInsensitive(toString(eia.output), :searchTerms)
                OR multiSearchAnyCaseInsensitive(toString(COALESCE(di.data, map())), :searchTerms)
            )
            <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 开发/测试对等测试框架（{@link #getDatasetItemsWithExperimentItemsFromAggregates}）的行查询。
     * 稳定 id 解析方式与 {@link #SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT} 相同——
     * 理由参见该常量的 Javadoc。
     */
    private static final String SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS = """
            WITH dataset_item_versions_resolved AS (
                SELECT
                    div_dedup.dataset_item_id AS id,
                    div_dedup.id AS row_id,
                    div_dedup.dataset_id AS dataset_id,
                    div_dedup.data AS data,
                    div_dedup.source AS source,
                    div_dedup.trace_id AS trace_id,
                    div_dedup.span_id AS span_id,
                    div_dedup.tags AS tags,
                    div_dedup.evaluators AS evaluators,
                    div_dedup.execution_policy AS execution_policy,
                    div_dedup.created_at AS item_created_at,
                    div_dedup.last_updated_at AS item_last_updated_at,
                    div_dedup.created_by AS item_created_by,
                    div_dedup.last_updated_by AS item_last_updated_by,
                    div_dedup.dataset_version_id AS dataset_version_id,
                    div_dedup.description AS description
                FROM (
                    SELECT
                        div.id,
                        div.dataset_item_id,
                        div.dataset_id,
                        div.data,
                        div.source,
                        div.trace_id,
                        div.span_id,
                        div.tags,
                        div.evaluators,
                        div.execution_policy,
                        div.created_at,
                        div.last_updated_at,
                        div.created_by,
                        div.last_updated_by,
                        div.dataset_version_id,
                        div.description,
                        div.workspace_id
                    FROM dataset_item_versions div
                    INNER JOIN experiment_aggregates ea FINAL ON
                        ea.workspace_id = div.workspace_id
                        AND ea.dataset_id = div.dataset_id
                        AND div.dataset_version_id = COALESCE(nullIf(ea.dataset_version_id, ''), :version_id)
                    WHERE div.workspace_id = :workspace_id
                    AND div.dataset_id = :dataset_id
                    <if(experiment_ids)>AND ea.id IN :experiment_ids<endif>
                    ORDER BY (div.workspace_id, div.dataset_id, div.dataset_version_id, div.id) DESC, div.last_updated_at DESC
                    LIMIT 1 BY div.id
                ) AS div_dedup
            )
            SELECT
                di.id AS id,
                di.id AS dataset_item_id,
                di.dataset_id AS dataset_id,
                di.data AS data,
                di.description AS description,
                di.source AS source,
                di.trace_id AS trace_id,
                di.span_id AS span_id,
                di.tags AS tags,
                di.evaluators AS evaluators,
                di.execution_policy AS execution_policy,
                di.item_created_at AS created_at,
                di.item_last_updated_at AS last_updated_at,
                di.item_created_by AS created_by,
                di.item_last_updated_by AS last_updated_by,
                groupArray((eia.id, eia.experiment_id, eia.dataset_item_id, eia.trace_id,
                           <if(truncate)> eia.input_slim <else> eia.input <endif>,
                           <if(truncate)> eia.output_slim <else> eia.output <endif>,
                           eia.feedback_scores_array,
                           eia.created_at, eia.last_updated_at, eia.created_by, eia.last_updated_by,
                           eia.comments_array_agg,
                           toFloat64(eia.duration),
                           eia.total_estimated_cost,
                           eia.usage,
                           eia.visibility_mode,
                           eia.metadata,
                           di.description,
                           eia.execution_policy
                )) AS experiment_items_array
            FROM (
                SELECT eia.*,
                       if(notEmpty(lookup_div.dataset_item_id), lookup_div.dataset_item_id, eia.dataset_item_id) AS stable_dataset_item_id
                FROM experiment_item_aggregates eia FINAL
                LEFT JOIN dataset_item_versions AS lookup_div FINAL
                    ON lookup_div.workspace_id = eia.workspace_id
                    AND lookup_div.id = eia.dataset_item_id
                WHERE eia.workspace_id = :workspace_id
                <if(experiment_ids)>AND eia.experiment_id IN :experiment_ids<endif>
                <if(has_target_projects)>AND eia.project_id IN :target_project_ids<endif>
                <if(experiment_item_filters)>AND <experiment_item_filters><endif>
                <if(feedback_scores_filters)>AND <feedback_scores_filters><endif>
                <if(feedback_scores_empty_filters)>AND <feedback_scores_empty_filters><endif>
                -- all duplicated rows share the same stable_dataset_item_id, so arbitrary pick is safe
                LIMIT 1 BY eia.id
            ) eia
            LEFT JOIN dataset_item_versions_resolved AS di
                ON di.id = eia.stable_dataset_item_id
            WHERE 1 = 1
            <if(dataset_item_filters)>
            AND <dataset_item_filters>
            <endif>
            <if(search)>
            AND (
                multiSearchAnyCaseInsensitive(toString(eia.input), :searchTerms)
                OR multiSearchAnyCaseInsensitive(toString(eia.output), :searchTerms)
                OR multiSearchAnyCaseInsensitive(toString(COALESCE(di.data, map())), :searchTerms)
            )
            <endif>
            GROUP BY di.id, di.dataset_id, di.data, di.description, di.source, di.trace_id, di.span_id, di.tags,
                     di.evaluators, di.execution_policy, di.item_created_at, di.item_last_updated_at,
                     di.item_created_by, di.item_last_updated_by
            ORDER BY di.id DESC
            <if(limit)>LIMIT :limit<endif>
            <if(offset)>OFFSET :offset<endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_EXPERIMENT_ITEMS_STATS_FROM_AGGREGATES = """
            WITH valid_dataset_items AS (
                SELECT id, dataset_item_id
                FROM dataset_item_versions FINAL
                WHERE workspace_id = :workspace_id
                AND dataset_id = :dataset_id
                AND dataset_version_id = :version_id
                <if(dataset_item_filters)>
                AND (<dataset_item_filters>)
                <endif>
            ), feedback_scores_ungrouped AS (
                SELECT
                    workspace_id,
                    id,
                    experiment_id,
                    feedback_scores,
                    arrayMap(k -> (k, feedback_scores[k]), mapKeys(feedback_scores)) AS score_pairs
                FROM experiment_item_aggregates FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experiment_ids
                AND dataset_item_id IN (SELECT arrayJoin([id, dataset_item_id]) FROM valid_dataset_items)
                <if(experiment_item_filters)>
                AND (<experiment_item_filters>)
                <endif>
            ), feedback_scores_flattened AS (
                SELECT
                    workspace_id,
                    id,
                    experiment_id,
                    tupleElement(score_pair, 1) AS name,
                    tupleElement(score_pair, 2) AS value
                FROM feedback_scores_ungrouped
                ARRAY JOIN score_pairs AS score_pair
            ), feedback_scores_percentiles AS (
                SELECT
                    name AS score_name,
                    quantiles(0.5, 0.9, 0.99)(toFloat64(value)) AS percentiles
                FROM feedback_scores_flattened
                GROUP BY name
            ), usage_total_tokens_data AS (
                SELECT
                    toFloat64(usage['total_tokens']) AS total_tokens
                FROM experiment_item_aggregates FINAL
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experiment_ids
                AND dataset_item_id IN (SELECT arrayJoin([id, dataset_item_id]) FROM valid_dataset_items)
                <if(experiment_item_filters)>
                AND (<experiment_item_filters>)
                <endif>
                AND usage['total_tokens'] IS NOT NULL
                AND usage['total_tokens'] > 0
            )
            SELECT
                count(DISTINCT id) as experiment_items_count,
                count(DISTINCT trace_id) as trace_count,
                avgIf(total_estimated_cost, total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
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
                      quantilesIf(0.5, 0.9, 0.99)(total_estimated_cost, total_estimated_cost > 0)
                    )
                ) AS total_estimated_cost_percentiles,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toDecimal64(
                             greatest(
                               least(if(isFinite(toFloat64(v)), toFloat64(v), 0), 999999999.999999999),
                               -999999999.999999999
                             ),
                             9
                           ),
                      quantiles(0.5, 0.9, 0.99)(duration)
                    )
                ) AS duration_percentiles,
                avgMap(feedback_scores) AS feedback_scores_avg,
                (SELECT mapFromArrays(
                    groupArray(score_name),
                    groupArray(mapFromArrays(
                        ['p50', 'p90', 'p99'],
                        arrayMap(v -> toDecimal64(if(isFinite(v), v, 0), 9), percentiles)
                    ))
                ) FROM feedback_scores_percentiles) AS feedback_scores_percentiles,
                avgMap(usage) AS usage,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toInt64(greatest(least(if(isFinite(v), v, 0), 999999999.999999999), -999999999.999999999)),
                      (SELECT quantiles(0.5, 0.9, 0.99)(total_tokens) FROM usage_total_tokens_data)
                    )
                ) AS usage_total_tokens_percentiles
            FROM experiment_item_aggregates FINAL
            WHERE workspace_id = :workspace_id
            AND experiment_id IN :experiment_ids
            AND dataset_item_id IN (SELECT arrayJoin([id, dataset_item_id]) FROM valid_dataset_items)
            <if(experiment_item_filters)>
            AND (<experiment_item_filters>)
            <endif>
            <if(feedback_scores_filters)>
            AND id IN (
                SELECT id
                FROM feedback_scores_flattened
                GROUP BY id, name
                HAVING (<feedback_scores_filters>)
            )
            <endif>
            <if(feedback_scores_empty_filters)>
            AND (<feedback_scores_empty_filters>)
            <endif>
            ;
            """;

    private static final String SELECT_EXPERIMENT_AGGREGATION_COUNTS = """
            SELECT
                countIf(has_aggregated) AS aggregated,
                countIf(NOT has_aggregated AND in_project_scope) AS not_aggregated
            FROM (
                SELECT
                    e.id,
                    notEmpty(agg.id) AS has_aggregated,
                    <if(project_id)>
                    (e.project_id = :project_id
                        OR e.id IN (
                            SELECT experiment_id
                            FROM experiment_items
                            WHERE workspace_id = :workspace_id
                              AND trace_id IN (
                                  SELECT id
                                  FROM traces
                                  WHERE workspace_id = :workspace_id
                                    AND project_id = :project_id
                                    AND id IN (
                                        SELECT trace_id
                                        FROM experiment_items
                                        WHERE workspace_id = :workspace_id
                                          AND experiment_id NOT IN (
                                              SELECT id
                                              FROM experiment_aggregates
                                              WHERE workspace_id = :workspace_id
                                              <if(experiment_ids)> AND id IN :experiment_ids <endif>
                                              <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                                              <if(id)> AND id = :id <endif>
                                              <if(ids_list)> AND id IN :ids_list <endif>
                                          )
                                    )
                              )
                        )) AS in_project_scope
                    <else>
                    true AS in_project_scope
                    <endif>
                FROM experiments e FINAL
                LEFT JOIN (
                    SELECT DISTINCT
                        toString(id) AS id
                    FROM experiment_aggregates
                    WHERE workspace_id = :workspace_id
                    <if(experiment_ids)> AND id IN :experiment_ids <endif>
                    <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                    <if(id)> AND id = :id <endif>
                    <if(ids_list)> AND id IN :ids_list <endif>
                ) agg ON e.id = agg.id
                WHERE e.workspace_id = :workspace_id
                <if(experiment_ids)> AND e.id IN :experiment_ids <endif>
                <if(dataset_id)> AND e.dataset_id = :dataset_id <endif>
                <if(id)> AND e.id = :id <endif>
                <if(ids_list)> AND e.id IN :ids_list <endif>
            )
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    @Override
    public Mono<Void> populateExperimentAggregate(UUID experimentId) {

        return getExperimentData(experimentId)
                .flatMap(experimentData ->
                // 首先检查实验是否有任何条目
                getExperimentItemsCount(experimentId)
                        .flatMap(itemsCount -> {
                            if (itemsCount == 0) {
                                return insertExperimentAggregate(
                                        experimentData,
                                        createEmptyTraceAggregations(experimentId),
                                        createEmptySpanAggregations(experimentId),
                                        createEmptyFeedbackScoreAggregations(experimentId),
                                        createEmptyPassRateAggregation(experimentId),
                                        "[]",
                                        0L,
                                        createEmptyAssertionScoreAggregations(experimentId));
                            }

                            return getProjectIds(experimentId)
                                    .flatMap(projectIds -> {
                                        if (CollectionUtils.isEmpty(projectIds)) {
                                            return insertExperimentAggregate(
                                                    experimentData,
                                                    createEmptyTraceAggregations(experimentId),
                                                    createEmptySpanAggregations(experimentId),
                                                    createEmptyFeedbackScoreAggregations(experimentId),
                                                    createEmptyPassRateAggregation(experimentId),
                                                    EMPTY_ARRAY_STR,
                                                    itemsCount,
                                                    createEmptyAssertionScoreAggregations(experimentId));
                                        }

                                        var labelProjectId = resolveLabelProjectId(
                                                experimentData.projectId(), projectIds);
                                        return Mono.zip(
                                                getTraceAggregations(experimentId, projectIds, labelProjectId),
                                                getSpanAggregations(experimentId, projectIds),
                                                getFeedbackScoreAggregations(experimentId, projectIds),
                                                getPassRateAggregation(experimentId, projectIds)
                                                        .defaultIfEmpty(createEmptyPassRateAggregation(experimentId)),
                                                getCommentsAggregation(experimentId, projectIds)
                                                        .defaultIfEmpty(EMPTY_ARRAY_STR),
                                                getAssertionScoreAggregations(experimentId, projectIds)
                                                        .defaultIfEmpty(
                                                                createEmptyAssertionScoreAggregations(experimentId)))
                                                .flatMap(tuple -> {
                                                    var traceAgg = tuple.getT1();
                                                    var spanAgg = tuple.getT2();
                                                    var feedbackAgg = tuple.getT3();
                                                    var passRateAgg = tuple.getT4();
                                                    var commentsAgg = tuple.getT5();
                                                    var assertionAgg = tuple.getT6();

                                                    return insertExperimentAggregate(
                                                            experimentData,
                                                            traceAgg,
                                                            spanAgg,
                                                            feedbackAgg,
                                                            passRateAgg,
                                                            commentsAgg,
                                                            itemsCount,
                                                            assertionAgg);
                                                });
                                    });
                        }));
    }

    @Override
    public Mono<BatchResult> populateExperimentItemAggregates(
            UUID experimentId, Set<UUID> projectIds, UUID labelProjectId, UUID cursorId, int limit) {

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return getExperimentItems(experimentId, cursorId, limit)
                    .collectList()
                    .flatMap(items -> {
                        if (items.isEmpty()) {
                            return Mono.just(BatchResult.builder().processedCount(0L).build());
                        }

                        var lastCursor = items.getLast().id();
                        var traceIds = items.stream().map(ExperimentItemData::traceId).toList();

                        return Mono.zip(
                                getTracesData(workspaceId, experimentId, projectIds, traceIds).collectList(),
                                getSpansData(workspaceId, experimentId, projectIds, traceIds).collectList(),
                                getFeedbackScoresData(workspaceId, experimentId, projectIds, traceIds).collectList(),
                                getCommentsData(workspaceId, experimentId, projectIds, traceIds).collectList(),
                                getAssertionsData(workspaceId, experimentId, projectIds, traceIds).collectList())
                                .flatMap(tuple -> {
                                    var tracesData = tuple.getT1();
                                    var spansData = tuple.getT2();
                                    var feedbackData = tuple.getT3();
                                    var commentsData = tuple.getT4();
                                    var assertionsData = tuple.getT5();

                                    return insertExperimentItemAggregates(
                                            labelProjectId,
                                            items,
                                            tracesData,
                                            spansData,
                                            feedbackData,
                                            commentsData,
                                            assertionsData)
                                            .map(ignored -> BatchResult.builder()
                                                    .processedCount(items.size())
                                                    .lastCursor(lastCursor)
                                                    .build());
                                });
                    });
        });
    }

    private Mono<ExperimentData> getExperimentData(UUID experimentId) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_EXPERIMENT_DATA,
                    "getExperimentData", workspaceId, userName, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> mapExperimentData(row)));
        }).singleOrEmpty());
    }

    /**
     * 实验存储的 {@code project_id}；当未设置或实验不存在时发出 {@link Mono#empty()}，
     * 以便调用方可以通过 {@code .switchIfEmpty(...)} 进行回退。
     */
    @Override
    public Mono<UUID> getExperimentProjectId(@NonNull UUID experimentId) {
        return getExperimentData(experimentId).mapNotNull(ExperimentData::projectId);
    }

    /**
     * 该实验条目所引用的去重 project_ids；始终恰好发出一个 Set（当未找到带有 project_id 的 trace 时可能为空）。
     * 参见 {@link #GET_PROJECT_IDS} 和 {@link #unionProjectIdChunks(Flux)}。
     */
    @Override
    public Mono<Set<UUID>> getProjectIds(UUID experimentId) {
        return asyncTemplate.nonTransaction(connection -> unionProjectIdChunks(makeFluxContextAware((userName,
                workspaceId) -> {
            var template = getSTWithLogComment(GET_PROJECT_IDS,
                    "getProjectIds", workspaceId, userName, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> Arrays
                            .stream(row.get("project_ids", String[].class))
                            .map(UUID::fromString)
                            .collect(Collectors.toUnmodifiableSet())));
        })));
    }

    /**
     * 将结果流发出的 project_id 分块折叠为一个不可变 Set。使用 {@code reduceWith}（而不是
     * {@code single()}/{@code singleOrEmpty()}）是有意为之：R2DBC 结果发布者可能发出多个元素，
     * 这会使 {@code single()} 抛出 {@code IndexOutOfBoundsException}。求并集始终恰好得到一个 Set
     * （流为空时为空集），并且对于正常的单行结果而言是幂等的。
     */
    @VisibleForTesting
    static Mono<Set<UUID>> unionProjectIdChunks(Flux<Set<UUID>> projectIdChunks) {
        return projectIdChunks.reduceWith(() -> new HashSet<UUID>(), (allProjectIds, projectIds) -> {
            allProjectIds.addAll(projectIds);
            return allProjectIds;
        }).map(Set::copyOf);
    }

    private Mono<TraceAggregations> getTraceAggregations(UUID experimentId, Set<UUID> projectIds, UUID labelProjectId) {
        return queryExperimentAggregation(
                GET_TRACE_AGGREGATIONS, "getTraceAggregations", experimentId, projectIds, labelProjectId,
                this::mapTraceAggregations);
    }

    private Mono<SpanAggregations> getSpanAggregations(UUID experimentId, Set<UUID> projectIds) {
        return queryExperimentAggregation(
                GET_SPAN_AGGREGATIONS, "getSpanAggregations", experimentId, projectIds,
                this::mapSpanAggregations);
    }

    private Mono<FeedbackScoreAggregations> getFeedbackScoreAggregations(UUID experimentId, Set<UUID> projectIds) {
        return queryExperimentAggregation(
                GET_FEEDBACK_SCORE_AGGREGATIONS, "getFeedbackScoreAggregations", experimentId, projectIds,
                this::mapFeedbackScoreAggregations);
    }

    private Mono<PassRateAggregation> getPassRateAggregation(UUID experimentId, Set<UUID> projectIds) {
        return queryExperimentAggregation(
                GET_PASS_RATE_AGGREGATION, "getPassRateAggregation", experimentId, projectIds,
                this::mapPassRateAggregation);
    }

    /**
     * 限定为一个实验及其条目所引用的项目集合的单行聚合查询。对于不需要标签绑定的查询，
     * 委托给感知 {@code labelProjectId} 的重载，并传入 {@code null}。
     */
    private <T> Mono<T> queryExperimentAggregation(
            String query,
            String logName,
            UUID experimentId,
            Set<UUID> projectIds,
            Function<Row, T> rowMapper) {
        return queryExperimentAggregation(query, logName, experimentId, projectIds, null, rowMapper);
    }

    /**
     * 用于额外绑定 {@code :project_id}（聚合行的单项目标签）的查询的重载。目前只有
     * {@link #GET_TRACE_AGGREGATIONS} 需要它。
     */
    private <T> Mono<T> queryExperimentAggregation(
            String query,
            String logName,
            UUID experimentId,
            Set<UUID> projectIds,
            UUID labelProjectId,
            Function<Row, T> rowMapper) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(query, logName, workspaceId, userName, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId)
                    .bind("project_ids", projectIds);
            if (labelProjectId != null) {
                statement.bind("project_id", labelProjectId);
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> rowMapper.apply(row)));
        }).singleOrEmpty());
    }

    private Mono<Long> getExperimentItemsCount(UUID experimentId) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_EXPERIMENT_ITEMS_COUNT,
                    "getExperimentItemsCount", workspaceId, userName, experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)));
        }).singleOrEmpty()
                .defaultIfEmpty(0L));
    }

    private Mono<Void> insertExperimentAggregate(
            ExperimentData experimentData,
            TraceAggregations traceAgg,
            SpanAggregations spanAgg,
            FeedbackScoreAggregations feedbackAgg,
            PassRateAggregation passRateAgg,
            String commentsArrayAgg,
            long itemsCount,
            AssertionScoreAggregations assertionAgg) {

        return asyncTemplate.nonTransaction(connection -> {
            var template = getSTWithLogComment(INSERT_EXPERIMENT_AGGREGATE,
                    "insertExperimentAggregate", experimentData.workspaceId(), "", experimentData.id().toString());

            // 将 Map 转换为 ClickHouse mapFromArrays 所需的键/值数组
            var experimentScoresArrays = mapToArrays(
                    ObjectUtils.getIfNull(experimentData.experimentScores(), Map.of()),
                    String[]::new, Double[]::new,
                    BigDecimal::doubleValue);
            var durationPercentilesArrays = mapToArrays(
                    ObjectUtils.getIfNull(traceAgg.durationPercentiles(), Map.of()),
                    String[]::new, Double[]::new,
                    v -> v);
            var totalEstimatedCostPercentilesArrays = mapToArrays(
                    ObjectUtils.getIfNull(spanAgg.totalEstimatedCostPercentiles(), Map.of()),
                    String[]::new, Double[]::new,
                    v -> v);
            var usageAvgArrays = mapToArrays(
                    ObjectUtils.getIfNull(spanAgg.usageAvg(), Map.of()),
                    String[]::new, Double[]::new,
                    Double::doubleValue);
            Map<String, Double> usageTotalTokensPercentiles = ObjectUtils.getIfNull(
                    spanAgg.usageTotalTokensPercentiles(),
                    Map.of());
            var usageTotalTokensPercentilesArrays = mapToArrays(
                    usageTotalTokensPercentiles,
                    String[]::new, Double[]::new,
                    v -> v);
            var feedbackScoresAvgArrays = mapToArrays(
                    ObjectUtils.getIfNull(feedbackAgg.feedbackScoresAvg(), Map.of()),
                    String[]::new, Double[]::new,
                    Double::doubleValue);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", experimentData.workspaceId())
                    .bind("id", experimentData.id())
                    .bind("dataset_id", experimentData.datasetId())
                    .bind("project_id", traceAgg.projectId())
                    .bind("name", experimentData.name())
                    .bind("created_at", experimentData.createdAt())
                    .bind("last_updated_at", experimentData.lastUpdatedAt())
                    .bind("created_by", experimentData.createdBy())
                    .bind("last_updated_by", experimentData.lastUpdatedBy())
                    .bind("metadata", ObjectUtils.getIfNull(experimentData.metadata(), ""))
                    .bind("prompt_versions", ObjectUtils.getIfNull(experimentData.promptVersions(), Map.of()))
                    .bind("optimization_id", ObjectUtils.getIfNull(experimentData.optimizationId(), ""))
                    .bind("dataset_version_id", ObjectUtils.getIfNull(experimentData.datasetVersionId(), ""))
                    .bind("tags", ObjectUtils.getIfNull(experimentData.tags(), List.of()).toArray(new String[0]))
                    .bind("type", experimentData.type())
                    .bind("evaluation_method",
                            ObjectUtils.getIfNull(experimentData.evaluationMethod(),
                                    EvaluationMethod.UNKNOWN_VALUE))
                    .bind("status", experimentData.status())
                    .bind("experiment_scores_keys", experimentScoresArrays.keys())
                    .bind("experiment_scores_values", experimentScoresArrays.values())
                    .bind("trace_count", traceAgg.traceCount())
                    .bind("experiment_items_count", itemsCount)
                    .bind("duration_percentiles_keys", durationPercentilesArrays.keys())
                    .bind("duration_percentiles_values", durationPercentilesArrays.values())
                    .bind("feedback_scores_percentiles",
                            ObjectUtils.getIfNull(feedbackAgg.feedbackScoresPercentiles(), Map.of()))
                    .bind("total_estimated_cost_sum", spanAgg.totalEstimatedCostSum())
                    .bind("total_estimated_cost_avg", spanAgg.totalEstimatedCostAvg())
                    .bind("total_estimated_cost_percentiles_keys", totalEstimatedCostPercentilesArrays.keys())
                    .bind("total_estimated_cost_percentiles_values", totalEstimatedCostPercentilesArrays.values())
                    .bind("usage_avg_keys", usageAvgArrays.keys())
                    .bind("usage_avg_values", usageAvgArrays.values())
                    .bind("usage_total_tokens_percentiles_keys", usageTotalTokensPercentilesArrays.keys())
                    .bind("usage_total_tokens_percentiles_values", usageTotalTokensPercentilesArrays.values())
                    .bind("feedback_scores_avg_keys", feedbackScoresAvgArrays.keys())
                    .bind("feedback_scores_avg_values", feedbackScoresAvgArrays.values())
                    .bind("pass_rate", passRateAgg.passRate())
                    .bind("passed_count", passRateAgg.passedCount())
                    .bind("total_count", passRateAgg.totalCount())
                    .bind("comments_array_agg",
                            StringUtils.isNotBlank(commentsArrayAgg) ? commentsArrayAgg : EMPTY_ARRAY_STR);

            var assertionScoresAvgArrays = mapToArrays(
                    ObjectUtils.getIfNull(assertionAgg.assertionScoresAvg(), Map.of()),
                    String[]::new, Double[]::new,
                    Double::doubleValue);
            statement.bind("assertion_scores_avg_keys", assertionScoresAvgArrays.keys())
                    .bind("assertion_scores_avg_values", assertionScoresAvgArrays.values());

            return makeMonoContextAware((userName, workspaceId) -> Mono.from(statement.execute()).then());
        });
    }

    private Flux<ExperimentItemData> getExperimentItems(UUID experimentId,
            UUID cursor, int limit) {
        return asyncTemplate.stream(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(GET_EXPERIMENT_ITEMS,
                    "getExperimentItems", workspaceId, userName, experimentId.toString())
                    .add("cursor", cursor != null);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("experiment_id", experimentId)
                    .bind("limit", limit);

            if (cursor != null) {
                statement.bind("cursor", cursor);
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> mapExperimentItemData(row)));
        }));
    }

    private <T> Flux<T> streamWithTraceIds(
            String sqlTemplate,
            String methodName,
            String workspaceId,
            UUID experimentId,
            Set<UUID> projectIds,
            List<UUID> traceIds,
            Function<Row, T> rowMapper) {

        return asyncTemplate.stream(connection -> {
            var template = getSTWithLogComment(sqlTemplate, methodName, workspaceId, "", experimentId.toString());

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("project_ids", projectIds)
                    .bind("trace_ids", traceIds.toArray(UUID[]::new));

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> rowMapper.apply(row)));
        });
    }

    private Flux<TraceData> getTracesData(String workspaceId, UUID experimentId, Set<UUID> projectIds,
            List<UUID> traceIds) {
        return streamWithTraceIds(GET_TRACES_DATA, "getTracesData", workspaceId, experimentId, projectIds, traceIds,
                this::mapTraceData);
    }

    private Flux<SpanData> getSpansData(String workspaceId, UUID experimentId, Set<UUID> projectIds,
            List<UUID> traceIds) {
        return streamWithTraceIds(GET_SPANS_DATA, "getSpansData", workspaceId, experimentId, projectIds, traceIds,
                this::mapSpanData);
    }

    private Flux<FeedbackScoreData> getFeedbackScoresData(String workspaceId, UUID experimentId, Set<UUID> projectIds,
            List<UUID> traceIds) {
        return streamWithTraceIds(GET_FEEDBACK_SCORES_DATA, "getFeedbackScoresData", workspaceId, experimentId,
                projectIds, traceIds, this::mapFeedbackScoreData);
    }

    private Flux<CommentsData> getCommentsData(String workspaceId, UUID experimentId, Set<UUID> projectIds,
            List<UUID> traceIds) {
        return streamWithTraceIds(GET_COMMENTS_DATA, "getCommentsData", workspaceId, experimentId, projectIds, traceIds,
                this::mapCommentsData);
    }

    private Flux<AssertionData> getAssertionsData(String workspaceId, UUID experimentId, Set<UUID> projectIds,
            List<UUID> traceIds) {
        return streamWithTraceIds(GET_ASSERTIONS_DATA, "getAssertionsData", workspaceId, experimentId, projectIds,
                traceIds, this::mapAssertionData);
    }

    private Mono<String> getCommentsAggregation(UUID experimentId, Set<UUID> projectIds) {
        return queryExperimentAggregation(
                GET_COMMENTS_AGGREGATION, "getCommentsAggregation", experimentId, projectIds,
                row -> {
                    var value = row.get("comments_array_agg", String.class);
                    return StringUtils.isNotBlank(value) ? value : EMPTY_ARRAY_STR;
                });
    }

    private Mono<AssertionScoreAggregations> getAssertionScoreAggregations(UUID experimentId, Set<UUID> projectIds) {
        return queryExperimentAggregation(
                GET_ASSERTION_SCORE_AGGREGATIONS, "getAssertionScoreAggregations", experimentId, projectIds,
                this::mapAssertionScoreAggregations);
    }

    private AssertionScoreAggregations mapAssertionScoreAggregations(Row row) {
        Map<String, Object> raw = row.get("assertion_scores_avg", Map.class);
        Map<String, Double> scoresAvg = Optional.ofNullable(raw)
                .map(m -> m.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> ((Number) e.getValue()).doubleValue())))
                .orElse(null);
        return AssertionScoreAggregations.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .assertionScoresAvg(scoresAvg)
                .build();
    }

    private AssertionScoreAggregations createEmptyAssertionScoreAggregations(UUID experimentId) {
        return AssertionScoreAggregations.builder()
                .experimentId(experimentId)
                .assertionScoresAvg(Map.of())
                .build();
    }

    /**
     * 将单个实验条目行序列化为一个 JSONEachRow 对象，并追加到 {@code out}，以 {@code '\n'} 结尾——
     * 这是 ClickHouse v2 批量插入所期望的格式（参见
     * {@link #insertExperimentItemAggregates(UUID, List, List, List, List, List, List)}）。
     *
     * <p>为何共享一个 {@link StringBuilder}：v2 的 {@link com.clickhouse.client.api.Client#insert} 调用
     * 接收覆盖整个批次的 {@link java.io.InputStream}；将行连接到一个缓冲区，然后交出单个
     * {@link java.io.ByteArrayInputStream}，可以避免逐行的流管道，并让 ClickHouse 的 HTTP 层一次性压缩并
     * 发送负载。
     *
     * <p>为何使用 {@link com.comet.opik.utils.JsonUtils#createObjectNode()}：使 JSON 序列化保持在后端其余部分
     * 使用的同一个 Jackson {@code ObjectMapper} 上（snake_case 命名、BigDecimal 处理、自定义反序列化器）。
     * 使用本地 {@code new ObjectMapper()} 会与 REST 侧的编码悄然产生分歧，并可能重新引入 JSONEachRow 路径
     * 设计之初就要避免的 NaN / 精度 / 日期格式不匹配问题。
     *
     * <p>字段契约：以下键必须与目标表的列名和类型匹配（参见迁移 {@code 000030_*} / {@code 000054_*} 中的
     * {@code experiment_item_aggregates}）。新增列需要在此处有对应的 {@code node.put(...)}，并且需要与
     * {@code FORMAT JSONEachRow} 兼容的 ClickHouse 类型（字符串、数字，以及通过 {@code putObject} 的
     * {@code Map(String, ...)}）。
     *
     * <p>可为空的上游数据（{@code trace}、{@code span}、{@code feedback}）会被合并为安全的默认值
     * （{@code ""}、{@code 0}、{@code EMPTY_ARRAY_STR}），而不是发出 JSON {@code null}，因为 ClickHouse 的
     * 非空列会拒绝 null 并导致整个批次失败。
     */
    private void appendJsonRow(StringBuilder out,
            String workspaceId,
            UUID projectId,
            ExperimentItemData item,
            Map<UUID, TraceData> tracesMap,
            Map<UUID, SpanData> spansMap,
            Map<UUID, FeedbackScoreData> feedbackMap,
            Map<UUID, CommentsData> commentsMap,
            Map<UUID, AssertionData> assertionsMap) {

        TraceData trace = tracesMap.get(item.traceId());
        SpanData span = spansMap.get(item.traceId());
        FeedbackScoreData feedback = feedbackMap.get(item.traceId());

        Map<String, Long> usageMap = Optional.ofNullable(span).map(SpanData::usage).orElse(Map.of());
        Map<String, BigDecimal> feedbackScoresMap = Optional.ofNullable(feedback)
                .map(FeedbackScoreData::feedbackScores).orElse(Map.of());

        var node = JsonUtils.createObjectNode();
        node.put("workspace_id", workspaceId);
        node.put("id", item.id().toString());
        node.put("project_id", projectId.toString());
        node.put("experiment_id", item.experimentId().toString());
        node.put("dataset_item_id", item.datasetItemId().toString());
        node.put("trace_id", item.traceId().toString());
        node.put("input", Optional.ofNullable(trace).map(TraceData::input).orElse(""));
        node.put("output", Optional.ofNullable(trace).map(TraceData::output).orElse(""));
        node.put("input_slim", Optional.ofNullable(trace).map(TraceData::inputSlim).orElse(""));
        node.put("output_slim", Optional.ofNullable(trace).map(TraceData::outputSlim).orElse(""));
        node.put("metadata", Optional.ofNullable(trace).map(TraceData::metadata).orElse(""));
        node.put("duration",
                Optional.ofNullable(trace).map(TraceData::duration).orElse(BigDecimal.ZERO).doubleValue());
        node.put("total_estimated_cost",
                Optional.ofNullable(span).map(SpanData::totalEstimatedCost).orElse(BigDecimal.ZERO)
                        .toPlainString());

        var usageNode = node.putObject("usage");
        usageMap.forEach(usageNode::put);

        var feedbackNode = node.putObject("feedback_scores");
        feedbackScoresMap.forEach((k, v) -> feedbackNode.put(k, v.toPlainString()));

        node.put("feedback_scores_array",
                Optional.ofNullable(feedback).map(FeedbackScoreData::feedbackScoresArray).orElse(EMPTY_ARRAY_STR));
        node.put("comments_array_agg",
                Optional.ofNullable(commentsMap.get(item.traceId())).map(CommentsData::commentsArrayAgg)
                        .orElse(EMPTY_ARRAY_STR));
        node.put("visibility_mode",
                Optional.ofNullable(trace).map(TraceData::visibilityMode).map(VisibilityMode::getValue)
                        .orElse(VisibilityMode.DEFAULT.getValue()));
        node.put("created_at", item.createdAt().toString());
        node.put("last_updated_at", item.lastUpdatedAt().toString());
        node.put("created_by", item.createdBy());
        node.put("last_updated_by", item.lastUpdatedBy());
        node.put("execution_policy",
                Optional.ofNullable(item.executionPolicy()).filter(StringUtils::isNotBlank).orElse(""));
        node.put("assertions_array",
                Optional.ofNullable(assertionsMap.get(item.traceId())).map(AssertionData::assertionsArray)
                        .orElse(EMPTY_ARRAY_STR));

        out.append(node).append('\n');
    }

    private Mono<Long> insertExperimentItemAggregates(
            UUID projectId,
            List<ExperimentItemData> items,
            List<TraceData> tracesData,
            List<SpanData> spansData,
            List<FeedbackScoreData> feedbackData,
            List<CommentsData> commentsData,
            List<AssertionData> assertionsData) {

        // 创建查找映射
        Map<UUID, TraceData> tracesMap = tracesData.stream()
                .collect(Collectors.toMap(TraceData::traceId, Function.identity()));
        Map<UUID, SpanData> spansMap = spansData.stream()
                .collect(Collectors.toMap(SpanData::traceId, Function.identity()));
        Map<UUID, FeedbackScoreData> feedbackMap = feedbackData.stream()
                .collect(Collectors.toMap(FeedbackScoreData::traceId, Function.identity()));
        Map<UUID, CommentsData> commentsMap = commentsData.stream()
                .collect(Collectors.toMap(CommentsData::traceId, Function.identity()));
        Map<UUID, AssertionData> assertionsMap = assertionsData.stream()
                .collect(Collectors.toMap(AssertionData::traceId, Function.identity()));

        return insertExperimentItems(projectId, items, tracesMap, spansMap, feedbackMap, commentsMap, assertionsMap);
    }

    /**
     * 通过 ClickHouse v2 HTTP 客户端使用 {@link ClickHouseFormat#JSONEachRow} 将 {@code items} 批量插入
     * {@code experiment_item_aggregates}。
     *
     * <p>为何使用 v2 客户端 + JSONEachRow（对比其他位置使用的 R2DBC 路径）：这是后端中唯一使用 v2 客户端的路径——
     * 我们特意为这个批量插入流程选择它，因为 {@code EXPERIMENT_AGGREGATES_BATCH_SIZE} 可以配置到 1k 以上
     * （例如对于实验条目超过 100 万条的工作空间配置为 {@code 10000}）。一旦单个语句携带超过约 1k 行，R2DBC 的
     * 绑定参数序列化开销就会呈超线性增长（逐行的参数映射、驱动侧转义、逐行往返），因此在该批量大小下它会成为
     * 聚合任务的主要开销。JSONEachRow 批量路径在这些批次上端到端快约 500 倍，因为 (1) 负载是单个 HTTP 请求体，
     * (2) 压缩由 v2 客户端一次性应用，以及 (3) 解析在服务端的 ClickHouse 快速路径中完成。对于代码库中其他位置
     * 的较小批量插入，R2DBC 仍然是正确的选择并且保持不变。v2 客户端被共享并作为 Dropwizard 的 {@code Managed}
     * 进行管理（参见 {@code DatabaseAnalyticsModule.getClickHouseClient}）；本方法不会关闭它。
     *
     * <p>流程：
     * <ol>
     *   <li>通过 {@link #appendJsonRow} 将每个条目物化到一个共享的 {@link StringBuilder}（每行一个 JSON 对象
     *       加一个换行符）。</li>
     *   <li>转换为 UTF-8 字节并包装到一个 {@link ByteArrayInputStream} 中。</li>
     *   <li>附加一个标识工作空间 / 用户 / 批量大小的 {@code log_comment}，以便在 {@code system.query_log} /
     *       ClickHouse trace 中关联该查询。</li>
     *   <li>按请求设置 {@code date_time_input_format=best_effort}（而不是在全局 {@code Client.Builder} 上）。
     *       将其限定在插入范围内可以让全局客户端配置免受会影响无关查询的格式特定容差的影响——如果未来的调用方
     *       需要不同的格式，它传入自己的 {@link InsertSettings} 即可。</li>
     *   <li>在 {@link Schedulers#boundedElastic()} 上运行阻塞的 HTTP 调用，以免响应式链被钉在事件循环上。</li>
     *   <li>从服务器响应返回权威的 {@code NUM_ROWS_WRITTEN} 指标，而不是 {@code items.size()}，这样调用方看到的
     *       是 ClickHouse 实际接受的数量（否则截断、去重或引擎特定的合并将是不可见的）。</li>
     * </ol>
     *
     * <p>错误处理：{@code try-with-resources} 确保响应总是被释放，即使在部分读取失败时也是如此。异常会沿响应式
     * 链向上传播；我们只记录条目数量（不记录负载），以避免将 PII 转储到日志中。
     */
    private Mono<Long> insertExperimentItems(UUID projectId,
            List<ExperimentItemData> items,
            Map<UUID, TraceData> tracesMap,
            Map<UUID, SpanData> spansMap,
            Map<UUID, FeedbackScoreData> feedbackMap,
            Map<UUID, CommentsData> commentsMap,
            Map<UUID, AssertionData> assertionsMap) {

        return makeMonoContextAware((userName, workspaceId) -> Mono.fromCallable(() -> {
            StringBuilder body = new StringBuilder();
            items.forEach(item -> appendJsonRow(body, workspaceId, projectId, item,
                    tracesMap, spansMap, feedbackMap, commentsMap, assertionsMap));
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            String logComment = "insert_experiment_item_aggregate:%s:%s:%d".formatted(
                    workspaceId, userName == null ? "" : userName, items.size());

            var settings = new InsertSettings()
                    .logComment(logComment)
                    .serverSetting("date_time_input_format", "best_effort");

            try (InsertResponse response = clickHouseClient.insert(
                    "experiment_item_aggregates",
                    new ByteArrayInputStream(payload),
                    ClickHouseFormat.JSONEachRow,
                    settings).get()) {
                return response.getMetrics().getMetric(ServerMetrics.NUM_ROWS_WRITTEN).getLong();
            }
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(err -> log.error(
                        "插入实验条目聚合失败: items='{}'",
                        items.size(), err)));
    }

    // 行映射方法
    private ExperimentData mapExperimentData(Row row) {
        return ExperimentData.builder()
                .workspaceId(row.get("workspace_id", String.class))
                .id(getUUID(row, "id"))
                .datasetId(getUUID(row, "dataset_id"))
                .projectId(getUUIDOrNull(row, "project_id"))
                .name(row.get("name", String.class))
                .createdAt(row.get("created_at", String.class))
                .lastUpdatedAt(row.get("last_updated_at", String.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .metadata(row.get("metadata", String.class))
                .promptVersions(row.get("prompt_versions", Map.class))
                .optimizationId(row.get("optimization_id", String.class))
                .datasetVersionId(row.get("dataset_version_id", String.class))
                .tags(Optional.ofNullable(row.get("tags", String[].class))
                        .map(tags -> Arrays.stream(tags).toList())
                        .filter(CollectionUtils::isNotEmpty)
                        .orElse(null))
                .type(row.get("type", String.class))
                .evaluationMethod(row.get("evaluation_method", String.class))
                .status(row.get("status", String.class))
                .experimentScores(parseExperimentScoresFromString(row.get("experiment_scores", String.class)))
                .build();
    }

    /**
     * 从 JSON 字符串解析 experiment_scores（experiments 表以 String 存储）。
     * 如果输入为 null/空或解析失败，返回空映射。
     */
    private Map<String, BigDecimal> parseExperimentScoresFromString(String experimentScores) {
        if (StringUtils.isBlank(experimentScores)) {
            return Map.of();
        }

        return JsonUtils.readValue(experimentScores, TYPE_REFERENCE)
                .stream()
                .collect(Collectors.toMap(ExperimentScore::name, ExperimentScore::value));
    }

    private TraceAggregations mapTraceAggregations(Row row) {
        return TraceAggregations.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .projectId(getUUID(row, "project_id"))
                .durationPercentiles(mapNumberMap(row, "duration_percentiles"))
                .traceCount(row.get("trace_count", Long.class))
                .build();
    }

    private static Map<String, Double> mapNumberMap(Row row, String columnName) {
        return Optional.ofNullable((Map<String, ? extends Number>) row.get(columnName, Map.class))
                .orElse(Map.of())
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().doubleValue()));

    }

    private SpanAggregations mapSpanAggregations(Row row) {
        return SpanAggregations.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .usageAvg(row.get("usage_avg", Map.class))
                .totalEstimatedCostSum(row.get("total_estimated_cost_sum", Double.class))
                .totalEstimatedCostAvg(row.get("total_estimated_cost_avg", Double.class))
                .totalEstimatedCostPercentiles(mapNumberMap(row, "total_estimated_cost_percentiles"))
                .usageTotalTokensPercentiles(mapNumberMap(row, "usage_total_tokens_percentiles"))
                .build();
    }

    private FeedbackScoreAggregations mapFeedbackScoreAggregations(Row row) {
        // 将 feedbackScoresAvg 映射值从 BigDecimal 转换为 Double
        Map<String, Object> feedbackScoresAvgRaw = row.get("feedback_scores_avg", Map.class);
        Map<String, Double> feedbackScoresAvg = Optional.ofNullable(feedbackScoresAvgRaw)
                .map(raw -> raw.entrySet().stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey,
                                e -> ((Number) e.getValue()).doubleValue())))
                .orElse(null);

        return FeedbackScoreAggregations.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .feedbackScoresPercentiles(row.get("feedback_scores_percentiles", Map.class))
                .feedbackScoresAvg(feedbackScoresAvg)
                .build();
    }

    private PassRateAggregation mapPassRateAggregation(Row row) {
        return PassRateAggregation.builder()
                .experimentId(getUUID(row, "experiment_id"))
                .passRate(row.get("pass_rate", BigDecimal.class))
                .passedCount(row.get("passed_count", Long.class))
                .totalCount(row.get("total_count", Long.class))
                .build();
    }

    private ExperimentItemData mapExperimentItemData(Row row) {
        return ExperimentItemData.builder()
                .id(getUUID(row, "id"))
                .experimentId(getUUID(row, "experiment_id"))
                .traceId(getUUID(row, "trace_id"))
                .datasetItemId(getUUID(row, "dataset_item_id"))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .executionPolicy(row.get("execution_policy", String.class))
                .build();
    }

    /**
     * 直接查询 experiment_aggregates 表，并根据存储的聚合值构造 Experiment。
     * 用于测试和验证聚合数据与预期值匹配。
     *
     * @param experimentId 要查询的实验 ID
     * @return 包含由聚合表构造的 Experiment 的 Mono，如果未找到则为空
     */
    public Mono<Experiment> getExperimentFromAggregates(@NonNull UUID experimentId) {

        return Mono.deferContextual(context -> {
            String workspaceId = context.get(RequestContext.WORKSPACE_ID);

            return asyncTemplate.nonTransaction(connection -> {

                var template = getSTWithLogComment(SELECT_EXPERIMENT_BY_ID,
                        "getExperimentFromAggregates", workspaceId, "", experimentId.toString());

                var statement = connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("experiment_id", experimentId.toString());

                return Flux.from(statement.execute())
                        .flatMap(result -> result.map((row, rowMetadata) -> mapExperimentFromAggregates(row)))
                        .singleOrEmpty();
            });
        });
    }

    /**
     * 将 experiment_aggregates 表中的一行映射为一个 Experiment 对象。
     */
    private Experiment mapExperimentFromAggregates(Row row) {
        // id 在 ClickHouse 中是 FixedString(36)，以 String 读取
        UUID id = getUUID(row, "id");

        // dataset_id 是 FixedString(36)，以 String 读取
        UUID datasetId = getUUID(row, "dataset_id");

        // project_id 是 FixedString(36)，以 String 读取
        UUID projectId = getUUID(row, "project_id");

        // name 是 String
        String name = row.get("name", String.class);

        // created_at 是 DateTime64(9, 'UTC')，以 Instant 读取
        Instant createdAt = row.get("created_at", Instant.class);

        // last_updated_at 是 DateTime64(9, 'UTC')，以 Instant 读取
        Instant lastUpdatedAt = row.get("last_updated_at", Instant.class);

        // created_by 是 String
        String createdBy = row.get("created_by", String.class);

        // last_updated_by 是 String
        String lastUpdatedBy = row.get("last_updated_by", String.class);

        // metadata 是 String (JSON)，解析为 JsonNode
        JsonNode metadata = getJsonNodeOrNull(row, "metadata");

        // tags 是 Array(String)，以 List<String> 读取并转换为 Set<String>
        Set<String> tags = Set.copyOf(Optional.ofNullable(row.get("tags", List.class)).orElse(List.of()));

        // type 是 Enum8，以 String 读取并转换为 ExperimentType
        ExperimentType type = ExperimentType.fromString(row.get("type", String.class));

        // evaluation_method 是 Enum，以 String 读取并转换为 EvaluationMethod
        EvaluationMethod evaluationMethod = EvaluationMethod.fromString(row.get("evaluation_method", String.class))
                .orElse(null);

        // status 是 Enum8，以 String 读取并转换为 ExperimentStatus
        ExperimentStatus status = ExperimentStatus.fromString(row.get("status", String.class));

        // optimization_id 是 String，转换为 UUID
        UUID optimizationId = getUUIDOrNull(row, "optimization_id");

        // dataset_version_id 是 String，转换为 UUID
        UUID datasetVersionId = getUUIDOrNull(row, "dataset_version_id");

        // prompt_versions - 比较中忽略，设为 null
        // 数据库中是 Map(FixedString(36), Array(FixedString(36)))
        // Java 期望 List<PromptVersionLink> - 由于被忽略，无需复杂转换
        List<Experiment.PromptVersionLink> promptVersions = null;

        // experiment_scores 是 Map(String, Float64)，转换为 List<ExperimentScore>
        Map<String, Double> experimentScoresRaw = row.get("experiment_scores", Map.class);

        List<ExperimentScore> experimentScores = Optional.ofNullable(experimentScoresRaw)
                .map(raw -> raw.entrySet().stream()
                        .map(e -> new ExperimentScore(e.getKey(), BigDecimal.valueOf(e.getValue())))
                        .toList())
                .orElse(null);

        // trace_count 在 ClickHouse 中是 UInt64，以 Long 读取
        Long traceCount = row.get("trace_count", Long.class);

        // duration_percentiles 是 Map(String, Float64)，以 Map<String, Double> 读取
        Map<String, Double> durationMap = row.get("duration_percentiles", Map.class);

        PercentageValues duration = Optional.ofNullable(durationMap)
                .filter(map -> !map.isEmpty())
                .map(map -> new PercentageValues(
                        BigDecimal.valueOf(map.getOrDefault("p50", 0.0)),
                        BigDecimal.valueOf(map.getOrDefault("p90", 0.0)),
                        BigDecimal.valueOf(map.getOrDefault("p99", 0.0))))
                .orElse(null);

        // feedback_scores_avg 是 Map(String, Float64)，转换为 List<FeedbackScoreAverage>
        Map<String, Double> feedbackScoresAvgRaw = row.get("feedback_scores_avg", Map.class);
        List<FeedbackScoreAverage> feedbackScores = Optional.ofNullable(feedbackScoresAvgRaw)
                .map(raw -> raw.entrySet().stream()
                        .map(e -> new FeedbackScoreAverage(e.getKey(),
                                BigDecimal.valueOf(e.getValue())))
                        .toList())
                .orElse(null);

        // total_estimated_cost_sum 是 Float64，以 Double 读取
        BigDecimal totalEstimatedCost = getBigDecimal(row, "total_estimated_cost_sum");

        // total_estimated_cost_avg 是 Float64，以 Double 读取
        BigDecimal totalEstimatedCostAvg = getBigDecimal(row, "total_estimated_cost_avg");

        // usage_avg 是 Map(String, Float64)，以 Map<String, Double> 读取
        Map<String, Double> usageAvg = row.get("usage_avg", Map.class);

        // pass_rate 字段在 experiment_aggregates 中不可为空（DEFAULT 0）
        // 对于非测试套件实验，将 0 默认值转换为 null
        Long totalCount = row.get("total_count", Long.class);
        boolean hasPassRate = totalCount != null && totalCount > 0;

        Map<String, Object> assertionScoresAvgRaw = row.get("assertion_scores_avg", Map.class);
        List<AssertionScoreAverage> assertionScores = Optional.ofNullable(assertionScoresAvgRaw)
                .filter(raw -> !raw.isEmpty())
                .map(raw -> raw.entrySet().stream()
                        .map(e -> new AssertionScoreAverage(e.getKey(),
                                BigDecimal.valueOf(((Number) e.getValue()).doubleValue())))
                        .toList())
                .orElse(null);

        // 使用 experiment_aggregates 表中的所有字段构建 Experiment
        return new Experiment(
                id,
                null, // datasetName - 不在数据库中
                datasetId,
                projectId,
                null, // projectName - 不在数据库中
                name,
                metadata,
                tags,
                type,
                evaluationMethod,
                optimizationId,
                feedbackScores,
                CommentResultMapper.parseCommentsFromJson(row.get("comments_array_agg", String.class)),
                traceCount,
                null, // datasetItemCount - 不在聚合表中
                createdAt,
                duration,
                totalEstimatedCost, // total_estimated_cost_sum
                totalEstimatedCostAvg,
                usageAvg,
                lastUpdatedAt,
                createdBy,
                lastUpdatedBy,
                status,
                experimentScores,
                null, // promptVersion（单数）- 不在数据库中
                promptVersions,
                datasetVersionId,
                null, // datasetVersionSummary - 不在数据库中
                hasPassRate ? row.get("pass_rate", BigDecimal.class) : null,
                hasPassRate ? row.get("passed_count", Long.class) : null,
                hasPassRate ? totalCount : null,
                assertionScores);
    }

    /**
     * 用于从 ClickHouse 的 FixedString(36) 列读取 UUID 的辅助方法。
     */
    private UUID getUUID(Row row, String columnName) {
        return UUID.fromString(row.get(columnName, String.class));
    }

    /**
     * 用于从 ClickHouse 的 FixedString(36) 列读取 UUID 并处理 null 的辅助方法。
     */
    private UUID getUUIDOrNull(Row row, String columnName) {
        String value = row.get(columnName, String.class);
        return StringUtils.isNotBlank(value) ? UUID.fromString(value) : null;
    }

    private BigDecimal getBigDecimal(Row row, String columnName) {
        return row.get(columnName, BigDecimal.class);
    }

    /**
     * 用于从 String 解析 JsonNode 并处理 null 的辅助方法。
     */
    private JsonNode getJsonNodeOrNull(Row row, String columnName) {
        String value = row.get(columnName, String.class);
        return StringUtils.isNotBlank(value) ? JsonUtils.getJsonNodeFromString(value) : null;
    }

    private <K, V, R> MapArrays<K, R> mapToArrays(Map<K, V> map, IntFunction<K[]> keysGenerator,
            IntFunction<R[]> valuesGenerator,
            Function<V, R> valueConverter) {
        if (map == null || map.isEmpty()) {
            return new MapArrays<>(keysGenerator.apply(0), valuesGenerator.apply(0));
        }

        var keys = map.keySet().toArray(keysGenerator.apply(map.size()));
        var values = valuesGenerator.apply(keys.length);
        for (int i = 0; i < keys.length; i++) {
            values[i] = valueConverter.apply(map.get(keys[i]));
        }
        return new MapArrays<>(keys, values);
    }

    private TraceData mapTraceData(Row row) {
        // visibility_mode 是 Enum8，以 String 读取并转换为 VisibilityMode
        VisibilityMode visibilityMode = VisibilityMode.fromString(row.get("visibility_mode", String.class))
                .orElse(VisibilityMode.DEFAULT);

        return TraceData.builder()
                .traceId(getUUID(row, "trace_id"))
                .projectId(getUUID(row, "project_id"))
                .duration(row.get("duration", BigDecimal.class))
                .metadata(row.get("metadata", String.class))
                .input(row.get("input", String.class))
                .output(row.get("output", String.class))
                .inputSlim(row.get("input_slim", String.class))
                .outputSlim(row.get("output_slim", String.class))
                .visibilityMode(visibilityMode)
                .build();
    }

    private ExperimentSourceData.SpanData mapSpanData(Row row) {
        return ExperimentSourceData.SpanData.builder()
                .traceId(getUUID(row, "trace_id"))
                .usage(row.get("usage", Map.class))
                .totalEstimatedCost(row.get("total_estimated_cost", BigDecimal.class))
                .build();
    }

    private FeedbackScoreData mapFeedbackScoreData(Row row) {
        List[] feedbackScoresArray = row.get("feedback_scores_array", List[].class);

        // 在序列化为 JSON 之前先映射为 FeedbackScore 对象
        List<FeedbackScore> feedbackScores = FeedbackScoreMapper.getFeedbackScores(feedbackScoresArray);
        String feedbackScoresArrayJson = Optional.ofNullable(feedbackScores)
                .map(JsonUtils::writeValueAsString)
                .orElse(EMPTY_ARRAY_STR);

        return FeedbackScoreData.builder()
                .traceId(getUUID(row, "trace_id"))
                .feedbackScores(row.get("feedback_scores", Map.class))
                .feedbackScoresArray(feedbackScoresArrayJson)
                .build();
    }

    private CommentsData mapCommentsData(Row row) {
        var commentsArrayAgg = row.get("comments_array_agg", String.class);
        return CommentsData.builder()
                .traceId(getUUID(row, "trace_id"))
                .commentsArrayAgg(StringUtils.isNotBlank(commentsArrayAgg) ? commentsArrayAgg : EMPTY_ARRAY_STR)
                .build();
    }

    private AssertionData mapAssertionData(Row row) {
        var assertionsArray = row.get("assertions_array", String.class);
        return AssertionData.builder()
                .traceId(getUUID(row, "trace_id"))
                .assertionsArray(StringUtils.isNotBlank(assertionsArray) ? assertionsArray : EMPTY_ARRAY_STR)
                .build();
    }

    @Override
    public Mono<Long> countTotal(ExperimentSearchCriteria experimentSearchCriteria) {
        return asyncTemplate.nonTransaction(connection -> countTotalFromAggregates(experimentSearchCriteria, connection)
                .flatMap(result -> Flux.from(result.map((row, rowMetadata) -> row.get("count", Long.class))))
                .reduce(0L, Long::sum));
    }

    private Flux<? extends Result> countTotalFromAggregates(
            ExperimentSearchCriteria experimentSearchCriteria, Connection connection) {

        return makeFluxContextAware((userName, workspaceId) -> {
            var template = buildCountTemplate(experimentSearchCriteria, workspaceId);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            bindSearchCriteria(statement, experimentSearchCriteria);
            return Flux.from(statement.execute());
        });
    }

    private ST buildCountTemplate(ExperimentSearchCriteria criteria, String workspaceId) {
        var template = getSTWithLogComment(FIND_COUNT_FROM_AGGREGATES, "count_experiments_from_aggregates",
                workspaceId, "", "");
        Optional.ofNullable(criteria.datasetId())
                .ifPresent(datasetId -> template.add("dataset_id", datasetId));
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> template.add("name", name));
        Optional.ofNullable(criteria.datasetIds())
                .ifPresent(datasetIds -> template.add("dataset_ids", datasetIds));
        Optional.ofNullable(criteria.promptId())
                .ifPresent(promptId -> template.add("prompt_ids", promptId));
        Optional.ofNullable(criteria.projectId())
                .ifPresent(projectId -> template.add("project_id", projectId));
        if (criteria.projectDeleted()) {
            template.add("project_deleted", true);
        }
        Optional.ofNullable(criteria.optimizationId())
                .ifPresent(optimizationId -> template.add("optimization_id", optimizationId));
        Optional.ofNullable(criteria.types())
                .filter(types -> !types.isEmpty())
                .ifPresent(types -> template.add("types", types));
        Optional.ofNullable(criteria.experimentIds())
                .filter(experimentIds -> !experimentIds.isEmpty())
                .ifPresent(experimentIds -> template.add("experiment_ids", experimentIds));

        // 添加常规实验过滤条件
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.EXPERIMENT))
                .ifPresent(experimentFilters -> template.add("filters", experimentFilters));

        // 添加聚合反馈分数过滤条件（仅在 ExperimentAggregatesDAO 此处被引用）
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> FilterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.FEEDBACK_SCORES_AGGREGATED))
                .ifPresent(feedbackScoresAggregatedFilters -> template.add("feedback_scores_aggregated_filters",
                        feedbackScoresAggregatedFilters));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> FilterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY))
                .ifPresent(feedbackScoresAggregatedEmptyFilters -> template.add(
                        "feedback_scores_aggregated_empty_filters",
                        feedbackScoresAggregatedEmptyFilters));

        // 添加实验分数过滤条件
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> FilterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.EXPERIMENT_SCORES))
                .ifPresent(
                        experimentScoresFilters -> template.add("experiment_scores_filters", experimentScoresFilters));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> FilterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY))
                .ifPresent(experimentScoresEmptyFilters -> template.add("experiment_scores_empty_filters",
                        experimentScoresEmptyFilters));

        return template;
    }

    private void bindSearchCriteria(Statement statement, ExperimentSearchCriteria criteria) {
        ExperimentSearchCriteriaBinder.bindSearchCriteria(
                statement,
                criteria,
                filterQueryBuilder,
                FILTER_STRATEGIES,
                false // 不为聚合绑定 entity_type
        );
    }

    private TraceAggregations createEmptyTraceAggregations(UUID experimentId) {
        return TraceAggregations.builder()
                .experimentId(experimentId)
                .projectId(ExperimentGroupMappers.ZERO_UUID)
                .durationPercentiles(Map.of())
                .traceCount(0L)
                .build();
    }

    private SpanAggregations createEmptySpanAggregations(UUID experimentId) {
        return SpanAggregations.builder()
                .experimentId(experimentId)
                .usageAvg(Map.of())
                .totalEstimatedCostSum(0.0)
                .totalEstimatedCostAvg(0.0)
                .totalEstimatedCostPercentiles(Map.of())
                .usageTotalTokensPercentiles(Map.of())
                .build();
    }

    private FeedbackScoreAggregations createEmptyFeedbackScoreAggregations(UUID experimentId) {
        return FeedbackScoreAggregations.builder()
                .experimentId(experimentId)
                .feedbackScoresPercentiles(Map.of())
                .feedbackScoresAvg(Map.of())
                .build();
    }

    private PassRateAggregation createEmptyPassRateAggregation(UUID experimentId) {
        return PassRateAggregation.builder()
                .experimentId(experimentId)
                .passRate(BigDecimal.ZERO)
                .passedCount(0L)
                .totalCount(0L)
                .build();
    }

    @Override
    public Flux<ExperimentGroupItem> findGroups(ExperimentGroupCriteria criteria) {
        return streamGroupQuery(FIND_GROUPS_FROM_AGGREGATES, criteria,
                ExperimentGroupMappers::toExperimentGroupItem);
    }

    @Override
    public Flux<ExperimentGroupAggregationItem> findGroupsAggregations(ExperimentGroupCriteria criteria) {
        return streamGroupQuery(FIND_GROUPS_AGGREGATIONS_FROM_AGGREGATES, criteria,
                ExperimentGroupMappers::toExperimentGroupAggregationItem);
    }

    private <T> Flux<T> streamGroupQuery(String queryTemplate, ExperimentGroupCriteria criteria,
            BiFunction<Row, Integer, T> rowMapper) {
        return asyncTemplate.stream(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = newGroupTemplate(queryTemplate, criteria, workspaceId);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            bindGroupCriteria(statement, criteria, filterQueryBuilder);
            if (Boolean.TRUE.equals(criteria.projectDeleted())) {
                statement.bind("zero_uuid", ExperimentGroupMappers.ZERO_UUID.toString());
            }

            int groupsCount = criteria.groups().size();

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> rowMapper.apply(row, groupsCount)));
        }));
    }

    @Override
    public Mono<Long> countDatasetItemsWithExperimentItemsFromAggregates(
            @NonNull DatasetItemSearchCriteria criteria,
            @NonNull UUID versionId) {

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(
                    SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS_COUNT,
                    "count_dataset_items_from_aggregates",
                    workspaceId,
                    userName,
                    criteria.datasetId().toString());

            applyDatasetItemFiltersToTemplate(template, criteria);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("dataset_id", criteria.datasetId().toString())
                    .bind("version_id", versionId.toString());

            bindDatasetItemSearchParams(statement, criteria);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                    .next();
        }));
    }

    @Override
    public Mono<DatasetItemPage> getDatasetItemsWithExperimentItemsFromAggregates(
            @NonNull DatasetItemSearchCriteria criteria,
            @NonNull UUID versionId,
            int page,
            int size) {

        var countMono = countDatasetItemsWithExperimentItemsFromAggregates(criteria, versionId);

        var itemsMono = asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(
                    SELECT_DATASET_ITEM_VERSIONS_WITH_EXPERIMENT_ITEMS,
                    "get_dataset_items_from_aggregates",
                    workspaceId,
                    userName,
                    criteria.datasetId().toString());

            applyDatasetItemFiltersToTemplate(template, criteria);

            template.add("truncate", criteria.truncate());
            template.add("limit", true);
            template.add("offset", true);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("dataset_id", criteria.datasetId().toString())
                    .bind("version_id", versionId.toString())
                    .bind("limit", size)
                    .bind("offset", (page - 1) * size);

            bindDatasetItemSearchParams(statement, criteria);

            return Flux.from(statement.execute())
                    .flatMap(result -> result
                            .map(DatasetItemResultMapper::buildItemFromRow))
                    .collectList();
        }));

        return countMono.flatMap(total -> {
            if (total == 0) {
                return Mono.just(DatasetItemPage.empty(page, List.of()));
            }
            return itemsMono.map(items -> new DatasetItemPage(items, page, items.size(), total, Set.of(), List.of()));
        });
    }

    private void applyDatasetItemFiltersToTemplate(ST template, DatasetItemSearchCriteria criteria) {
        if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
            template.add("experiment_ids", true);
        }

        DatasetItemSearchCriteriaMapper.applyToTemplate(template, criteria, DATASET_ITEM_FILTER_STRATEGY_PARAMS);
    }

    private void bindDatasetItemSearchParams(Statement statement, DatasetItemSearchCriteria criteria) {
        if (CollectionUtils.isNotEmpty(criteria.experimentIds())) {
            statement.bind("experiment_ids", criteria.experimentIds().toArray(UUID[]::new));
        }

        DatasetItemSearchCriteriaMapper.bindSearchCriteria(statement, criteria, DATASET_ITEM_BIND_STRATEGIES,
                filterQueryBuilder);
    }

    private ST newGroupTemplate(String query, ExperimentGroupCriteria criteria, String workspaceId) {
        var template = getSTWithLogComment(query, "find_groups_from_aggregates", workspaceId, "", "");
        ExperimentGroupMappers.applyGroupCriteriaToTemplate(template, criteria, filterQueryBuilder);
        groupingQueryBuilder.addGroupingTemplateParams(criteria.groups(), template);
        return template;
    }

    @Override
    public Mono<ProjectStats> getExperimentItemsStatsFromAggregates(
            @NonNull UUID datasetId,
            @NonNull UUID versionId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_EXPERIMENT_ITEMS_STATS_FROM_AGGREGATES,
                    "getExperimentItemsStatsFromAggregates", workspaceId, userName, datasetId);

            // 将过滤条件应用到模板
            FilterQueryBuilder.applyFiltersToTemplate(template, filters,
                    EXPERIMENT_ITEMS_STATS_FILTER_STRATEGY_PARAMS);

            String sql = template.render();

            Statement statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("dataset_id", datasetId.toString())
                    .bind("version_id", versionId.toString())
                    .bind("experiment_ids", experimentIds.toArray(UUID[]::new));

            // 绑定过滤参数
            FilterQueryBuilder.bindFilters(statement, filters, EXPERIMENT_ITEMS_STATS_BIND_STRATEGIES);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map(
                            (row, rowMetadata) -> StatsMapper.mapExperimentItemsStats(row)));
        }).singleOrEmpty())
                .doOnError(error -> log.error("获取聚合实验条目统计信息失败", error));
    }

    @Override
    public Mono<AggregatedExperimentCounts> getAggregationBranchCounts(
            @NonNull AggregationBranchCountsCriteria criteria) {
        return asyncTemplate.nonTransaction(connection -> Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            var template = getSTWithLogComment(SELECT_EXPERIMENT_AGGREGATION_COUNTS,
                    "get_aggregation_branch_counts", workspaceId, "", criteria.datasetId());

            Optional.ofNullable(criteria.experimentIds())
                    .filter(CollectionUtils::isNotEmpty)
                    .ifPresent(experimentIds -> template.add("experiment_ids", experimentIds));
            Optional.ofNullable(criteria.datasetId())
                    .ifPresent(datasetId -> template.add("dataset_id", datasetId));
            Optional.ofNullable(criteria.id())
                    .ifPresent(id -> template.add("id", id));
            Optional.ofNullable(criteria.idsList())
                    .filter(CollectionUtils::isNotEmpty)
                    .ifPresent(idsList -> template.add("ids_list", idsList));
            Optional.ofNullable(criteria.projectId())
                    .ifPresent(projectId -> template.add("project_id", projectId));

            var statement = connection.createStatement(template.render());

            Optional.ofNullable(criteria.experimentIds())
                    .filter(CollectionUtils::isNotEmpty)
                    .ifPresent(experimentIds -> statement.bind("experiment_ids",
                            experimentIds.toArray(UUID[]::new)));
            Optional.ofNullable(criteria.datasetId())
                    .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));
            Optional.ofNullable(criteria.id())
                    .ifPresent(id -> statement.bind("id", id));
            Optional.ofNullable(criteria.idsList())
                    .filter(CollectionUtils::isNotEmpty)
                    .ifPresent(idsList -> statement.bind("ids_list", idsList.toArray(UUID[]::new)));
            Optional.ofNullable(criteria.projectId())
                    .ifPresent(projectId -> statement.bind("project_id", projectId));

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result
                            .map((row, metadata) -> AggregatedExperimentCounts.builder()
                                    .aggregated(row.get("aggregated", Long.class))
                                    .notAggregated(row.get("not_aggregated", Long.class))
                                    .build()))
                    .next()
                    .defaultIfEmpty(AggregatedExperimentCounts.BOTH_BRANCHES);
        }));
    }

    @Override
    public Mono<Long> deleteByExperimentIds(Set<UUID> experimentIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(experimentIds),
                "Argument 'experimentIds' must not be empty");

        return executeAggregatesDelete(DELETE_EXPERIMENT_AGGREGATES_BY_IDS,
                "deleteExperimentAggregatesByIds", experimentIds)
                .flatMap(aggregatesDeleted -> executeAggregatesDelete(
                        DELETE_EXPERIMENT_ITEM_AGGREGATES_BY_EXPERIMENT_IDS,
                        "deleteExperimentItemAggregatesByExperimentIds", experimentIds)
                        .map(itemAggregatesDeleted -> aggregatesDeleted + itemAggregatesDeleted));
    }

    private Mono<Long> executeAggregatesDelete(String query, String queryName, Set<UUID> experimentIds) {
        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var details = experimentIds.stream().map(UUID::toString).collect(Collectors.joining(","));
            var template = getSTWithLogComment(query, queryName, workspaceId, userName, details);
            var statement = connection.createStatement(template.render())
                    .bind("experiment_ids", experimentIds.toArray(UUID[]::new))
                    .bind("workspace_id", workspaceId);

            return Flux.from(statement.execute());
        }).flatMap(Result::getRowsUpdated).reduce(0L, Long::sum));
    }

    @Override
    public Mono<Long> deleteItemAggregatesByItemIds(@NonNull UUID experimentId, Set<UUID> itemIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(itemIds),
                "Argument 'itemIds' must not be empty");

        return asyncTemplate.nonTransaction(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var details = experimentId.toString();
            var template = getSTWithLogComment(DELETE_EXPERIMENT_ITEM_AGGREGATES_BY_ITEM_IDS,
                    "deleteExperimentItemAggregatesByItemIds", workspaceId, userName, details);
            var statement = connection.createStatement(template.render())
                    .bind("item_ids", itemIds.toArray(UUID[]::new))
                    .bind("experiment_id", experimentId)
                    .bind("workspace_id", workspaceId);

            return Flux.from(statement.execute());
        }).flatMap(Result::getRowsUpdated).reduce(0L, Long::sum));
    }
}
