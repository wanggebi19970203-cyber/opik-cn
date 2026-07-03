package com.comet.opik.domain;

import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.FilterUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.ExperimentDAO.getFeedbackScores;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.JsonUtils.getJsonNodeOrDefault;
import static com.comet.opik.utils.JsonUtils.getStringOrDefault;

@ImplementedBy(OptimizationDAOImpl.class)
public interface OptimizationDAO {

    record OptimizationSummary(UUID datasetId, long optimizationCount, Instant mostRecentOptimizationAt) {
        public static OptimizationSummary empty(UUID datasetId) {
            return new OptimizationSummary(datasetId, 0, null);
        }
    }

    Mono<Void> upsert(Optimization optimization);

    Mono<Optimization> getById(UUID id);

    Mono<List<DatasetEventInfoHolder>> getOptimizationDatasetIds(Set<UUID> ids);

    Mono<Long> delete(Set<UUID> ids);

    Flux<DatasetLastOptimizationCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds);

    Mono<Long> update(UUID id, OptimizationUpdate update);

    Mono<Long> updateDatasetDeleted(Set<UUID> datasetIds);

    Mono<Optimization.OptimizationPage> find(int page, int size, @NonNull OptimizationSearchCriteria searchCriteria);

    Flux<OptimizationSummary> findOptimizationSummaryByDatasetIds(Set<UUID> datasetIds);

    Mono<Boolean> hasVersion1Optimizations(String workspaceId, List<String> demoOptimizationNames);

    Flux<EligibleOptimizationWorkspace> findEligibleOptimizationWorkspaces(
            Set<String> excludedWorkspaceIds, int limit);

    Flux<OrphanOptimization> findOrphanOptimizationsInWorkspace(String workspaceId);

    Flux<OptimizationProjectMapping> computeOptimizationProjectMappingViaExperiments(Set<UUID> optimizationIds);

    Mono<Long> batchSetProjectId(Set<UUID> optimizationIds, UUID projectId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OptimizationDAOImpl implements OptimizationDAO {

    private static final String HAS_VERSION1_OPTIMIZATIONS = """
            SELECT 1 FROM optimizations
            WHERE workspace_id = :workspace_id AND project_id = ''
            AND name NOT IN :demo_optimization_names
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'""";

    /**
     * 返回至少有一个孤立优化的工作区，按数量从小到大排序。
     * 当优化的最新行的 {@code project_id = ''} 时，该优化被视为孤立——通过
     * {@code GROUP BY id + argMax(project_id, last_updated_at)} 对 {@code ReplacingMergeTree}
     * 版本进行去重。演示名称和环境排除的工作区在数据库层面过滤，因此服务层只遍历
     * 可以实际迁移的工作区。与 D1 的 {@code FIND_ELIGIBLE_EXPERIMENT_WORKSPACES} 结构一致。
     */
    private static final String FIND_ELIGIBLE_OPTIMIZATION_WORKSPACES = """
            SELECT
                workspace_id,
                count(DISTINCT id) AS optimizations_count
            FROM (
                SELECT
                    o.workspace_id AS workspace_id,
                    o.id AS id
                FROM optimizations o
                WHERE o.name NOT IN :demo_optimization_names
                <if(excluded_workspace_ids)>
                AND o.workspace_id NOT IN :excluded_workspace_ids
                <endif>
                GROUP BY o.workspace_id, o.id
                HAVING argMax(o.project_id, o.last_updated_at) = ''
            )
            GROUP BY workspace_id
            ORDER BY optimizations_count ASC
            LIMIT :limit
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * 工作区中的 V1 优化 (id, dataset_id) 对，排除演示数据。服务层需要 {@code dataset_id}
     * 以便对 Path A（实验）未分类的优化执行 Path B（跨数据库数据集查找），
     * 无需额外的 ClickHouse 往返。通过 {@code argMax} 对 ReplacingMergeTree 版本去重，
     * 防止进行中的写入被重复计数。
     */
    private static final String FIND_ORPHAN_OPTIMIZATIONS_IN_WORKSPACE = """
            SELECT
                id AS optimization_id,
                argMax(dataset_id, last_updated_at) AS dataset_id
            FROM optimizations
            WHERE workspace_id = :workspace_id
            AND name NOT IN :demo_optimization_names
            GROUP BY id
            HAVING argMax(project_id, last_updated_at) = ''
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * Path A 推断：对 {@code :optimization_ids} 中的每个孤立优化，返回推断的
     * {@code project_id}、引用项目的 {@code distinct_project_count}，以及包含在
     * 每次分配日志条目中的排序 {@code project_breakdown}（{@code projectId=count,...}）。
     *
     * <p>推断读取 {@code experiments.project_id}（由 D1 的实验-项目迁移设置）；
     * 仍然为 {@code project_id = ''} 的实验会被排除，因此实验全部未迁移的优化
     * 不会出现在结果中，服务层将其视为无推断（回退到 Path B，最终使用工作区的默认项目）。
     * 当只有一个引用项目时选择是明确的；有多个时，主导项目胜出，
     * 按 {@code (count DESC, last_activity DESC, project_id ASC)} 排序，因此重复运行
     * 会产生相同结果——与数据集迁移 (OPIK-6701) 一致。
     *
     * <p>内部的 {@code argMax(project_id, last_updated_at) GROUP BY id} 去除重复的
     * ReplacingMergeTree 行版本：迁移进行中时，表可能短暂同时保存实验的旧行和更新行，
     * 取最新版本可防止外层聚合计数两次。
     */
    private static final String COMPUTE_OPTIMIZATION_PROJECT_MAPPING_VIA_EXPERIMENTS = """
            WITH arraySort(proj -> (-proj.1, -proj.2, proj.3),
                    groupArray((per_proj_count, per_proj_last_activity_nanos, experiment_project_id))) AS ranked
            SELECT
                optimization_id AS optimization_id,
                length(ranked) AS distinct_project_count,
                ranked[1].3 AS project_id,
                arrayStringConcat(
                    arrayMap(proj -> concat(proj.3, '=', toString(proj.1)), ranked), ','
                ) AS project_breakdown
            FROM (
                SELECT
                    optimization_id,
                    experiment_project_id,
                    count() AS per_proj_count,
                    toUnixTimestamp64Nano(max(experiment_last_updated_at)) AS per_proj_last_activity_nanos
                FROM (
                    SELECT
                        optimization_id,
                        argMax(project_id, last_updated_at) AS experiment_project_id,
                        max(last_updated_at) AS experiment_last_updated_at
                    FROM experiments
                    WHERE workspace_id = :workspace_id
                    AND optimization_id IN :optimization_ids
                    AND name NOT IN :demo_experiment_names
                    GROUP BY id, optimization_id
                    HAVING experiment_project_id != ''
                )
                GROUP BY optimization_id, experiment_project_id
            )
            GROUP BY optimization_id
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * 将每个 id 的最新行重新 INSERT，覆盖 {@code project_id}、{@code last_updated_by}
     * 和 {@code last_updated_at}。使用 {@code SELECT * REPLACE}，因此未来添加到
     * {@code optimizations} 的任何列都会被自动复制，无需修改此查询的 schema——
     * 与 D1 的 {@code ExperimentDAO.BATCH_SET_PROJECT_ID} 一致。
     *
     * <p>外层的 {@code WHERE project_id = ''} 保证幂等性：已有非空 {@code project_id}
     * 的优化会被跳过，因此重复运行迁移是安全的。
     */
    private static final String BATCH_SET_PROJECT_ID = """
            INSERT INTO optimizations
            SELECT * REPLACE (
                :user_name AS last_updated_by,
                now64(9) AS last_updated_at,
                :project_id AS project_id
            )
            FROM (
                SELECT *
                FROM optimizations
                WHERE workspace_id = :workspace_id
                AND id IN :optimization_ids
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            WHERE project_id = ''
            SETTINGS log_comment = '<log_comment>'
            """;

    private static final String UPSERT = """
            INSERT INTO optimizations (
                id,
                dataset_id,
                name,
                workspace_id,
                project_id,
                objective_name,
                status,
                metadata,
                studio_config,
                created_by,
                last_updated_by,
                last_updated_at
            )
            VALUES (
                :id,
                :dataset_id,
                :name,
                :workspace_id,
                :project_id,
                :objective_name,
                :status,
                :metadata,
                :studio_config,
                :created_by,
                :last_updated_by,
                COALESCE(parseDateTime64BestEffortOrNull(:last_updated_at, 6), now64(6))
            )
            ;
            """;

    private static final String FIND = """
            WITH optimization_final AS (
                SELECT
                    *
                FROM (
                    SELECT *
                    FROM optimizations
                    WHERE workspace_id = :workspace_id
                    <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                    <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                    <if(id)>AND id = :id <endif>
                    <if(project_id)>AND project_id = :project_id <endif>
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, dataset_id, id
                )
                WHERE 1=1
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            ), experiments_final AS (
                SELECT
                    id,
                    optimization_id,
                    experiment_scores,
                    metadata AS experiment_metadata,
                    created_at AS experiment_created_at,
                    type AS experiment_type
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND optimization_id IN (SELECT id FROM optimization_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_items_final AS (
                SELECT
                    DISTINCT
                        experiment_id,
                        trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = :entity_type
                      AND workspace_id = :workspace_id
                      AND entity_id IN (SELECT trace_id FROM experiment_items_final)
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
                    WHERE entity_type = :entity_type
                      AND workspace_id = :workspace_id
                      AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(fs_avg.name),
                        groupArray(fs_avg.avg_value)
                    ) AS feedback_scores
                FROM (
                    SELECT
                        et.experiment_id,
                        fs.name,
                        avg(fs.value) AS avg_value
                    FROM experiment_items_final as et
                    LEFT JOIN (
                        SELECT
                            name,
                            entity_id AS trace_id,
                            value
                        FROM feedback_scores_final
                    ) fs ON fs.trace_id = et.trace_id
                    GROUP BY et.experiment_id, fs.name
                    HAVING length(fs.name) > 0
                ) as fs_avg
                GROUP BY experiment_id
            ), experiment_scores_parsed AS (
                SELECT
                    e.id AS experiment_id,
                    JSON_VALUE(score, '$.name') AS name,
                    CAST(JSON_VALUE(score, '$.value') AS Float64) AS value
                FROM experiments_final AS e
                ARRAY JOIN JSONExtractArrayRaw(e.experiment_scores) AS score
                WHERE e.experiment_scores != '' AND e.experiment_scores != '[]'
                  AND length(JSON_VALUE(score, '$.name')) > 0
            ), experiment_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) AS experiment_scores
                FROM experiment_scores_parsed
                GROUP BY experiment_id
            ), experiment_durations AS (
                SELECT
                    ei.experiment_id,
                    count(DISTINCT ei.trace_id) AS trace_count,
                    arrayElement(
                        quantiles(0.5)(t.duration), 1
                    ) AS duration_p50,
                    sum(s.total_estimated_cost) AS total_estimated_cost
                FROM experiment_items_final ei
                LEFT JOIN (
                    SELECT id, duration
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, id
                ) AS t ON ei.trace_id = t.id
                LEFT JOIN (
                    SELECT trace_id, sum(total_estimated_cost) AS total_estimated_cost
                    FROM (
                        SELECT workspace_id, project_id, trace_id, parent_span_id, id, total_estimated_cost, last_updated_at
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        AND trace_id IN (SELECT trace_id FROM experiment_items_final)
                        ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY workspace_id, project_id, trace_id, parent_span_id, id
                    )
                    GROUP BY trace_id
                ) AS s ON t.id = s.trace_id
                GROUP BY ei.experiment_id
            ), experiment_candidates AS (
                SELECT
                    ef.id AS experiment_id,
                    ef.optimization_id,
                    ef.experiment_created_at,
                    if(
                        JSONHas(ef.experiment_metadata, 'candidate_id') AND JSONExtractString(ef.experiment_metadata, 'candidate_id') != '',
                        JSONExtractString(ef.experiment_metadata, 'candidate_id'),
                        toString(ef.id)
                    ) AS candidate_id
                FROM experiments_final ef
                WHERE ef.experiment_type NOT IN ('mini-batch', 'mutation')
            ), objective_scores_per_experiment AS (
                SELECT
                    ef.optimization_id,
                    esp.experiment_id,
                    esp.value AS objective_score
                FROM experiment_scores_parsed esp
                INNER JOIN experiments_final ef ON esp.experiment_id = ef.id
                INNER JOIN optimization_final o ON ef.optimization_id = o.id
                WHERE esp.name = o.objective_name
            ), candidate_metrics AS (
                SELECT
                    ec.optimization_id AS optim_id,
                    ec.candidate_id,
                    sum(ospe.objective_score * ed.trace_count)
                        / nullIf(sumIf(ed.trace_count, isNotNull(ospe.objective_score)), 0)
                        AS weighted_score,
                    sum(ed.duration_p50 / 1000.0 * ed.trace_count)
                        / nullIf(sumIf(ed.trace_count, isNotNull(ed.duration_p50)), 0)
                        AS weighted_duration,
                    sum(ed.total_estimated_cost)
                        / nullIf(sum(ed.trace_count), 0)
                        AS per_trace_cost,
                    min(ec.experiment_created_at) AS earliest_created_at
                FROM experiment_candidates ec
                LEFT JOIN objective_scores_per_experiment ospe
                    ON ec.experiment_id = ospe.experiment_id
                    AND ec.optimization_id = ospe.optimization_id
                LEFT JOIN experiment_durations ed ON ec.experiment_id = ed.experiment_id
                GROUP BY ec.optimization_id, ec.candidate_id
            ), best_candidate AS (
                SELECT
                    optim_id AS optimization_id,
                    max(weighted_score) AS best_score,
                    argMax(weighted_duration, weighted_score) AS best_duration,
                    argMax(per_trace_cost, weighted_score) AS best_cost
                FROM candidate_metrics
                WHERE isNotNull(weighted_score)
                GROUP BY optim_id
            ), baseline_candidate AS (
                SELECT
                    optim_id AS optimization_id,
                    argMin(weighted_score, earliest_created_at) AS baseline_score,
                    argMin(weighted_duration, earliest_created_at) AS baseline_duration,
                    argMin(per_trace_cost, earliest_created_at) AS baseline_cost
                FROM candidate_metrics
                GROUP BY optim_id
            ), optimization_costs AS (
                SELECT
                    ef2.optimization_id AS optimization_id,
                    sum(ed2.total_estimated_cost) AS total_optimization_cost
                FROM experiments_final ef2
                LEFT JOIN experiment_durations ed2 ON ef2.id = ed2.experiment_id
                GROUP BY ef2.optimization_id
            )
            SELECT
                o.*,
                o.id as id,
                COUNT(DISTINCT e.id) FILTER (WHERE e.id != '') AS num_trials,
                maxMap(fs.feedback_scores) AS feedback_scores,
                maxMap(es.experiment_scores) AS experiment_scores,
                any(bc.best_score) AS best_objective_score,
                any(blc.baseline_score) AS baseline_objective_score,
                any(bc.best_duration) AS best_duration,
                any(bc.best_cost) AS best_cost,
                any(blc.baseline_duration) AS baseline_duration,
                any(blc.baseline_cost) AS baseline_cost,
                any(oc.total_optimization_cost) AS total_optimization_cost
            FROM optimization_final AS o
            LEFT JOIN experiments_final AS e ON o.id = e.optimization_id
            LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            LEFT JOIN experiment_scores_agg AS es ON e.id = es.experiment_id
            LEFT JOIN best_candidate AS bc ON o.id = bc.optimization_id
            LEFT JOIN baseline_candidate AS blc ON o.id = blc.optimization_id
            LEFT JOIN optimization_costs AS oc ON o.id = oc.optimization_id
            GROUP BY o.*
            ORDER BY o.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            ;
            """;

    private static final String COUNT = """
            SELECT
                COUNT(id) as count
            FROM (
                SELECT
                    id
                FROM (
                    SELECT *
                    FROM optimizations
                    WHERE workspace_id = :workspace_id
                    <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                    <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                    <if(id)>AND id = :id <endif>
                    <if(project_id)>AND project_id = :project_id <endif>
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, dataset_id, id
                )
                WHERE 1=1
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            )
            ;
            """;

    private static final String FIND_OPTIMIZATIONS_DATASET_IDS = """
            SELECT
                distinct dataset_id
            FROM optimizations
            WHERE workspace_id = :workspace_id
            <if(experiment_ids)> AND id IN :experiment_ids <endif>
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            ;
            """;

    private static final String DELETE_BY_IDS = """
            DELETE FROM optimizations
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String UPDATE_BY_ID = """
            INSERT INTO optimizations (
            	id, dataset_id, name, workspace_id, project_id, objective_name, status, metadata, created_at, created_by, last_updated_by, studio_config
            )
            SELECT
                id,
                dataset_id,
                <if(name)> :name <else> name <endif> as name,
                workspace_id,
                project_id,
                objective_name,
                <if(status)> :status <else> status <endif> as status,
                metadata,
                created_at,
                created_by,
                :user_name as last_updated_by,
                studio_config
            FROM optimizations
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SET_DATASET_DELETED_TO_TRUE_BY_DATASET_ID = """
            INSERT INTO optimizations (
            	id, dataset_id, name, workspace_id, project_id, objective_name, status, metadata, created_at, created_by, last_updated_at, last_updated_by, dataset_deleted, studio_config
            )
            SELECT
                id,
                dataset_id,
                name as name,
                workspace_id,
                project_id,
                objective_name,
                status as status,
                metadata,
                created_at,
                created_by,
                last_updated_at,
                last_updated_by,
                true as dataset_deleted,
                studio_config
            FROM optimizations
            WHERE workspace_id = :workspace_id
            AND dataset_id IN :dataset_ids
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 by id
            ;
            """;

    private static final String FIND_MOST_RECENT_CREATED_OPTIMIZATION_BY_DATASET_IDS = """
            SELECT
            	dataset_id,
            	max(created_at) as created_at
            FROM (
                SELECT
                    id,
                    dataset_id,
                    created_at
                FROM optimizations
                WHERE dataset_id IN :dataset_ids
            	AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY dataset_id
            ;
            """;

    private static final String FIND_OPTIMIZATION_SUMMARY_BY_DATASET_IDS = """
            SELECT
            	dataset_id,
            	count(distinct id) as optimization_count,
            	max(last_updated_at) as most_recent_optimization_at
            FROM (
                SELECT
                    id,
                    dataset_id,
                    last_updated_at
                FROM optimizations
                WHERE dataset_id IN :dataset_ids
            	AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY dataset_id
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;

    @Override
    public Mono<Void> upsert(@NonNull Optimization optimization) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> upsert(optimization, connection))
                .then();
    }

    @Override
    public Mono<Optimization> getById(@NonNull UUID id) {
        var template = TemplateUtils.newST(FIND);
        template.add("id", id.toString());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> get(
                        template.render(), connection,
                        statement -> statement.bind("id", id)))
                .flatMap(this::mapToDto)
                .singleOrEmpty();
    }

    @Override
    public Mono<List<DatasetEventInfoHolder>> getOptimizationDatasetIds(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = TemplateUtils.newST(FIND_OPTIMIZATIONS_DATASET_IDS);
                    template.add("experiment_ids", ids);
                    var statement = connection.createStatement(template.render());
                    statement.bind("experiment_ids", ids);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(this::mapDatasetId)
                .collectList();
    }

    @Override
    public Mono<Long> delete(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("Deleting optimizations by ids, size '{}'", ids.size());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> delete(ids, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Deleted optimizations by ids, size '{}'", ids.size());
                    }
                });
    }

    @Override
    public Flux<DatasetLastOptimizationCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetIds), "Argument 'datasetIds' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(FIND_MOST_RECENT_CREATED_OPTIMIZATION_BY_DATASET_IDS);
                    statement.bind("dataset_ids", datasetIds);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new DatasetLastOptimizationCreated(
                        row.get("dataset_id", UUID.class),
                        row.get("created_at", Instant.class))));
    }

    @Override
    public Mono<Long> update(@NonNull UUID id, @NonNull OptimizationUpdate update) {
        log.info("Update optimization by id '{}'", id);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> update(id, update, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Updated optimization by id '{}'", id);
                    }
                });
    }

    @Override
    public Mono<Long> updateDatasetDeleted(@NonNull Set<UUID> datasetIds) {
        log.info("Set to true optimization dataset_deleted for datasetIds '{}'", datasetIds);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> updateDatasetDeleted(datasetIds, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Set to true optimization dataset_deleted is done for datasetIds '{}'", datasetIds);
                    }
                });
    }

    @Override
    public Mono<Optimization.OptimizationPage> find(int page, int size,
            @NonNull OptimizationSearchCriteria searchCriteria) {
        return getCount(searchCriteria)
                .flatMap(totalCount -> find(page, size, totalCount, searchCriteria))
                .defaultIfEmpty(Optimization.OptimizationPage.empty(page, List.of()));
    }

    @Override
    public Flux<OptimizationSummary> findOptimizationSummaryByDatasetIds(@NonNull Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Flux.empty();
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(FIND_OPTIMIZATION_SUMMARY_BY_DATASET_IDS);

                    statement.bind("dataset_ids", datasetIds);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new OptimizationSummary(
                        row.get("dataset_id", UUID.class),
                        row.get("optimization_count", Long.class),
                        row.get("most_recent_optimization_at", Instant.class))));
    }

    private Mono<Long> getCount(OptimizationSearchCriteria searchCriteria) {
        var template = TemplateUtils.newST(COUNT);

        bindTemplateParams(template, searchCriteria);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(template.render());

                    bindQueryParams(searchCriteria, statement, false);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map(row -> row.get("count", Long.class)))
                .reduce(Long::sum);
    }

    private Mono<Optimization.OptimizationPage> find(int page, int size, long total,
            OptimizationSearchCriteria searchCriteria) {
        var template = TemplateUtils.newST(FIND);

        bindTemplateParams(template, searchCriteria);

        var offset = (page - 1) * size;

        template.add("limit", size);
        template.add("offset", offset);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(template.render())
                            .bind("limit", size)
                            .bind("offset", offset);

                    bindQueryParams(searchCriteria, statement, true);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(this::mapToDto)
                .collectList()
                .map(optimizations -> new Optimization.OptimizationPage(page, optimizations.size(), total,
                        optimizations, List.of()));
    }

    private void bindTemplateParams(ST template, OptimizationSearchCriteria searchCriteria) {

        Optional.ofNullable(searchCriteria.datasetDeleted())
                .ifPresent(datasetDeleted -> template.add("dataset_deleted", datasetDeleted.toString()));

        Optional.ofNullable(searchCriteria.datasetId())
                .ifPresent(datasetId -> template.add("dataset_id", datasetId));

        Optional.ofNullable(searchCriteria.datasetIds())
                .filter(ids -> !ids.isEmpty())
                .ifPresent(datasetIds -> template.add("dataset_ids", datasetIds));

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> template.add("name", name));

        Optional.ofNullable(searchCriteria.studioOnly())
                .filter(Boolean.TRUE::equals)
                .ifPresent(studioOnly -> template.add("studio_only", "true"));

        Optional.ofNullable(searchCriteria.projectId())
                .ifPresent(projectId -> template.add("project_id", projectId));

        Optional.ofNullable(searchCriteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.OPTIMIZATION))
                .ifPresent(optimizationFilters -> template.add("filters", optimizationFilters));

        Optional.ofNullable(searchCriteria.entityType())
                .ifPresent(entityType -> template.add("entity_type", EntityType.TRACE.getType()));
    }

    private void bindQueryParams(OptimizationSearchCriteria searchCriteria, Statement statement, boolean isFindQuery) {

        Optional.ofNullable(searchCriteria.datasetDeleted())
                .ifPresent(datasetDeleted -> statement.bind("dataset_deleted", datasetDeleted));

        Optional.ofNullable(searchCriteria.datasetId())
                .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));

        Optional.ofNullable(searchCriteria.datasetIds())
                .filter(ids -> !ids.isEmpty())
                .ifPresent(datasetIds -> statement.bind("dataset_ids", datasetIds));

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> statement.bind("name", name));

        Optional.ofNullable(searchCriteria.projectId())
                .ifPresent(projectId -> statement.bind("project_id", projectId.toString()));

        Optional.ofNullable(searchCriteria.filters())
                .ifPresent(filters -> filterQueryBuilder.bind(statement, filters, FilterStrategy.OPTIMIZATION));

        if (isFindQuery) {
            Optional.ofNullable(searchCriteria.entityType())
                    .ifPresent(entityType -> statement.bind("entity_type", EntityType.TRACE.getType()));
        }
    }

    private Publisher<? extends Result> upsert(Optimization optimization, Connection connection) {

        var statement = connection.createStatement(UPSERT)
                .bind("id", optimization.id())
                .bind("dataset_id", optimization.datasetId())
                .bind("name", optimization.name())
                .bind("project_id", optimization.projectId() != null ? optimization.projectId().toString() : "")
                .bind("objective_name", optimization.objectiveName())
                .bind("status", optimization.status().getValue())
                .bind("metadata", getStringOrDefault(optimization.metadata()));

        if (optimization.studioConfig() != null) {
            try {
                String studioConfigJson = JsonUtils.writeValueAsString(optimization.studioConfig());
                statement.bind("studio_config", studioConfigJson);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to serialize studio_config for optimization: '%s'".formatted(optimization.id()), e);
            }
        } else {
            statement.bindNull("studio_config", String.class);
        }

        if (optimization.lastUpdatedAt() != null) {
            statement.bind("last_updated_at", optimization.lastUpdatedAt().toString());
        } else {
            statement.bindNull("last_updated_at", String.class);
        }

        return makeFluxContextAware((userName, workspaceId) -> {
            log.info("Inserting optimization with id '{}', datasetId '{}', datasetName '{}', workspaceId '{}'",
                    optimization.id(), optimization.datasetId(), optimization.datasetName(), workspaceId);
            statement.bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        });
    }

    private Publisher<? extends Result> get(String query, Connection connection, Function<Statement, Statement> bind) {
        var statement = connection.createStatement(query)
                .bind("entity_type", EntityType.TRACE.getType());
        return makeFluxContextAware(bindWorkspaceIdToFlux(bind.apply(statement)));
    }

    private Publisher<Optimization> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> {
            OptimizationStudioConfig studioConfig = null;
            String studioConfigJson = row.get("studio_config", String.class);
            if (StringUtils.isNotEmpty(studioConfigJson)) {
                try {
                    studioConfig = JsonUtils.readValue(studioConfigJson, OptimizationStudioConfig.class);
                } catch (Exception e) {
                    log.error("Failed to deserialize studio_config for optimization: '{}'",
                            row.get("id", UUID.class), e);
                }
            }

            String projectIdStr = row.get("project_id", String.class);
            UUID projectId = StringUtils.isNotBlank(projectIdStr) ? UUID.fromString(projectIdStr) : null;

            return Optimization.builder()
                    .id(row.get("id", UUID.class))
                    .name(row.get("name", String.class))
                    .datasetId(row.get("dataset_id", UUID.class))
                    .projectId(projectId)
                    .objectiveName(row.get("objective_name", String.class))
                    .status(OptimizationStatus.fromString(row.get("status", String.class)))
                    .metadata(getJsonNodeOrDefault(row.get("metadata", String.class)))
                    .studioConfig(studioConfig)
                    .createdAt(row.get("created_at", Instant.class))
                    .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                    .createdBy(row.get("created_by", String.class))
                    .lastUpdatedBy(row.get("last_updated_by", String.class))
                    .feedbackScores(getFeedbackScores(row, "feedback_scores"))
                    .experimentScores(getFeedbackScores(row, "experiment_scores"))
                    .numTrials(row.get("num_trials", Long.class))
                    .baselineObjectiveScore(row.get("baseline_objective_score", BigDecimal.class))
                    .bestObjectiveScore(row.get("best_objective_score", BigDecimal.class))
                    .baselineDuration(row.get("baseline_duration", BigDecimal.class))
                    .bestDuration(row.get("best_duration", BigDecimal.class))
                    .baselineCost(row.get("baseline_cost", BigDecimal.class))
                    .bestCost(row.get("best_cost", BigDecimal.class))
                    .totalOptimizationCost(row.get("total_optimization_cost", BigDecimal.class))
                    .build();
        });
    }

    private Publisher<DatasetEventInfoHolder> mapDatasetId(Result result) {
        return result.map((row, rowMetadata) -> new DatasetEventInfoHolder(row.get("dataset_id", UUID.class), null));
    }

    private Flux<? extends Result> delete(Set<UUID> ids, Connection connection) {

        var statement = connection.createStatement(DELETE_BY_IDS)
                .bind("ids", ids.toArray(UUID[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Flux<? extends Result> update(UUID id, OptimizationUpdate update, Connection connection) {
        var template = buildUpdateTemplate(update);

        var statement = createUpdateStatement(id, update, connection, template.render());

        return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement));
    }

    private Flux<? extends Result> updateDatasetDeleted(Set<UUID> datasetIds, Connection connection) {
        Statement statement = connection.createStatement(SET_DATASET_DELETED_TO_TRUE_BY_DATASET_ID);
        statement.bind("dataset_ids", datasetIds);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private ST buildUpdateTemplate(OptimizationUpdate update) {
        var template = TemplateUtils.newST(UPDATE_BY_ID);

        Optional.ofNullable(update.name())
                .ifPresent(name -> template.add("name", name));

        Optional.ofNullable(update.status())
                .ifPresent(status -> template.add("status", status.getValue()));

        return template;
    }

    private Statement createUpdateStatement(UUID id, OptimizationUpdate update, Connection connection, String sql) {
        Statement statement = connection.createStatement(sql);

        Optional.ofNullable(update.name())
                .ifPresent(name -> statement.bind("name", name));

        Optional.ofNullable(update.status())
                .ifPresent(status -> statement.bind("status", status.getValue()));

        statement.bind("id", id);

        return statement;
    }

    /**
     * 检查是否存在 V1（工作区范围）的优化，排除已知的演示名称。
     * ClickHouse 字符串比较区分大小写——演示名称的每种已知大小写形式
     * 必须在 {@link DemoData#OPTIMIZATIONS} 中显式列出。
     */
    @Override
    public Mono<Boolean> hasVersion1Optimizations(
            @NonNull String workspaceId, @NonNull List<String> demoOptimizationNames) {
        var template = FilterUtils.getSTWithLogComment(HAS_VERSION1_OPTIMIZATIONS,
                "has_version1_optimizations", workspaceId, "", demoOptimizationNames);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> Flux.from(connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("demo_optimization_names", demoOptimizationNames.toArray(String[]::new))
                        .execute())
                        .flatMap(result -> Flux.from(result.map((row, metadata) -> true))))
                .hasElements();
    }

    @Override
    public Flux<EligibleOptimizationWorkspace> findEligibleOptimizationWorkspaces(
            Set<String> excludedWorkspaceIds, int limit) {
        var details = "excludedWorkspacesCount=%d, limit=%d"
                .formatted(CollectionUtils.size(excludedWorkspaceIds), limit);
        var template = FilterUtils.getSTWithLogComment(FIND_ELIGIBLE_OPTIMIZATION_WORKSPACES,
                "find_eligible_optimization_workspaces", "", "", details);
        if (CollectionUtils.isNotEmpty(excludedWorkspaceIds)) {
            template.add("excluded_workspace_ids", true);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("demo_optimization_names", DemoData.OPTIMIZATIONS.toArray(String[]::new))
                            .bind("limit", limit);
                    if (CollectionUtils.isNotEmpty(excludedWorkspaceIds)) {
                        statement.bind("excluded_workspace_ids", excludedWorkspaceIds.toArray(String[]::new));
                    }
                    return Flux.from(statement.execute());
                })
                .flatMap(result -> result.map((row, metadata) -> EligibleOptimizationWorkspace.builder()
                        .workspaceId(row.get("workspace_id", String.class))
                        .optimizationsCount(row.get("optimizations_count", Long.class))
                        .build()));
    }

    @Override
    public Flux<OrphanOptimization> findOrphanOptimizationsInWorkspace(@NonNull String workspaceId) {
        var template = FilterUtils.getSTWithLogComment(FIND_ORPHAN_OPTIMIZATIONS_IN_WORKSPACE,
                "find_orphan_optimizations_in_workspace", workspaceId, "", "");
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> Flux.from(connection.createStatement(template.render())
                        .bind("workspace_id", workspaceId)
                        .bind("demo_optimization_names", DemoData.OPTIMIZATIONS.toArray(String[]::new))
                        .execute()))
                .flatMap(result -> result.map((row, metadata) -> OrphanOptimization.builder()
                        .optimizationId(UUID.fromString(row.get("optimization_id", String.class)))
                        .datasetId(UUID.fromString(row.get("dataset_id", String.class)))
                        .build()));
    }

    /**
     * Path A 推断行映射器。SQL 通过 {@code HAVING} 过滤掉 {@code experiment_project_id = ''}
     * 的实验，因此正常情况下每行都有非空的 project_id。防御性的 {@code Optional.ofNullable(...).filter(...)}
     * 保护一个窄并发窗口：写入者在 {@code HAVING} 求值和行物化之间将唯一匹配实验的
     * {@code project_id} 翻转为 {@code ''} 可能导致结果中该列为空。在这种情况下我们
     * 丢弃该行（通过 {@code Mono::justOrEmpty}），服务层将该优化视为无推断——
     * 回退到 Path B（数据集查找），最终使用工作区的默认项目。因此调用方必须容忍
     * 优化不在 Flux 中，即使其 id 在输入集合中。
     */
    @Override
    public Flux<OptimizationProjectMapping> computeOptimizationProjectMappingViaExperiments(Set<UUID> optimizationIds) {
        if (CollectionUtils.isEmpty(optimizationIds)) {
            return Flux.empty();
        }
        var optimizationIdsAsStrings = optimizationIds.stream().map(UUID::toString).toArray(String[]::new);
        var details = "optimizationCount=%d".formatted(optimizationIds.size());
        var template = FilterUtils.getSTWithLogComment(COMPUTE_OPTIMIZATION_PROJECT_MAPPING_VIA_EXPERIMENTS,
                "compute_optimization_project_mapping_via_experiments", null, null, details);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("optimization_ids", optimizationIdsAsStrings)
                            .bind("demo_experiment_names", DemoData.EXPERIMENTS.toArray(new String[0]));
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, metadata) -> Optional
                        .ofNullable(row.get("project_id", String.class))
                        .filter(StringUtils::isNotBlank)
                        .map(projectId -> OptimizationProjectMapping.builder()
                                .optimizationId(UUID.fromString(row.get("optimization_id", String.class)))
                                .projectId(UUID.fromString(projectId))
                                .distinctProjectCount(row.get("distinct_project_count", Long.class))
                                .projectBreakdown(row.get("project_breakdown", String.class))
                                .build())))
                .flatMap(Mono::justOrEmpty);
    }

    @Override
    public Mono<Long> batchSetProjectId(Set<UUID> optimizationIds, @NonNull UUID projectId) {
        if (CollectionUtils.isEmpty(optimizationIds)) {
            return Mono.just(0L);
        }
        var details = "optimizationCount=%d, projectId=%s".formatted(optimizationIds.size(), projectId);
        var template = FilterUtils.getSTWithLogComment(BATCH_SET_PROJECT_ID,
                "batch_set_optimization_project_id", null, null, details);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("optimization_ids", optimizationIds.toArray(UUID[]::new))
                            .bind("project_id", projectId);
                    return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement));
                })
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }
}
