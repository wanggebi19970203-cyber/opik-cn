package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse.BiInformation;
import com.comet.opik.api.ExperimentItemReference;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.GuardrailsValidation;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceDetails;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.domain.stats.StatsMerger;
import com.comet.opik.domain.utils.DemoDataExclusionUtils;
import com.comet.opik.domain.workspaces.WorkspacesService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.ErrorUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TruncationUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.api.ErrorInfo.ERROR_INFO_TYPE;
import static com.comet.opik.api.Trace.TracePage;
import static com.comet.opik.api.TraceCountResponse.WorkspaceTraceCount;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspace;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.domain.stats.StatsMapper.mapProjectScoresStats;
import static com.comet.opik.infrastructure.FilterUtils.ANALYTICS_DELETE_BATCH_SIZE;
import static com.comet.opik.infrastructure.FilterUtils.addSortNeedsWideFlag;
import static com.comet.opik.infrastructure.FilterUtils.bindTraceThreadSearchCriteria;
import static com.comet.opik.infrastructure.FilterUtils.getLogComment;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.infrastructure.FilterUtils.newTraceThreadFindTemplate;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.SentinelTranslation.epochToNull;
import static com.comet.opik.utils.SentinelTranslation.nanToNull;
import static com.comet.opik.utils.SentinelTranslation.nullToEpoch;
import static com.comet.opik.utils.SentinelTranslation.nullToNaN;
import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;
import static java.util.function.Predicate.not;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

@ImplementedBy(TraceDAOImpl.class)
public interface TraceDAO {

    Mono<UUID> insert(Trace trace, Connection connection);

    Mono<Void> update(TraceUpdate traceUpdate, UUID id, Connection connection);

    Mono<Void> delete(Set<Pair<UUID, UUID>> projectIdTraceIdPairs, Connection connection);

    Mono<Trace> findById(UUID id, Connection connection);

    Flux<Trace> findByIds(List<UUID> ids, Connection connection);

    Mono<TraceDetails> getTraceDetailsById(UUID id, Connection connection);

    Mono<TracePage> find(int size, int page, TraceSearchCriteria traceSearchCriteria, Connection connection);

    Mono<Boolean> existsByProjectId(TraceSearchCriteria traceSearchCriteria, boolean threadScoped,
            Connection connection);

    Mono<Void> partialInsert(UUID projectId, TraceUpdate traceUpdate, UUID traceId, Connection connection);

    Mono<List<WorkspaceAndResourceId>> getTraceWorkspace(Set<UUID> traceIds, Connection connection);

    Mono<Long> batchInsert(List<Trace> traces, Connection connection);

    Flux<WorkspaceTraceCount> countTracesPerWorkspace(Map<UUID, Instant> excludedProjectIds);

    Mono<Set<UUID>> getProjectsWithTracesInRange(Collection<Pair<String, UUID>> workspaceProjectPairs, Instant from,
            Instant to, Connection connection);

    Mono<UUID> getProjectIdFromTrace(UUID traceId);

    Mono<Map<UUID, UUID>> getProjectIdsByTraceIds(List<UUID> traceIds);

    Mono<Map<UUID, Set<UUID>>> getAllProjectIdsByTraceIds(Set<UUID> traceIds);

    Mono<Map<UUID, Set<UUID>>> getAllProjectIdsByTraceIdsBounded(Set<UUID> traceIds);

    Mono<Map<UUID, Instant>> getStartTimesByTraceIds(Set<UUID> traceIds, String workspaceId);

    Flux<BiInformation> getTraceBIInformation(Map<UUID, Instant> excludedProjectIds);

    Mono<ProjectStats> getStats(TraceSearchCriteria criteria);

    Mono<Long> getDailyTraces(Map<UUID, Instant> excludedProjectIds);

    Mono<Map<UUID, ProjectStats>> getStatsByProjectIds(List<UUID> projectIds, String workspaceId,
            List<? extends Filter> filters, Instant fromTime, Instant toTime);

    Mono<Set<UUID>> getTraceIdsByThreadIds(UUID projectId, List<String> threadIds, Connection connection);

    Mono<Trace> getPartialById(UUID id);

    Flux<Trace> search(int limit, TraceSearchCriteria criteria);

    Mono<Long> countTraces(Set<UUID> projectIds);

    Mono<List<TraceThread>> getMinimalThreadInfoByIds(UUID projectId, Set<String> threadId);

    Mono<Void> bulkUpdate(@NonNull Set<UUID> ids, @NonNull TraceUpdate update, boolean mergeTags);

    /**
     * 批量删除 traces 以执行数据保留策略（applyToPast=true）。
     * 删除 [lowerBound, cutoffId) 范围内未关联实验的 traces。
     *
     * @param workspaceIds 需要清除 traces 的工作空间列表
     * @param cutoffId     UUID v7 上界（不包含）
     * @param lowerBound   UUID v7 下界（包含）——通常为 cutoff 减去缓冲天数
     */
    Mono<Long> deleteForRetention(List<String> workspaceIds, UUID cutoffId, UUID lowerBound);

    /**
     * 批量删除 traces 以执行数据保留策略（applyToPast=false）。
     * 每个工作空间有独立的下界（规则 minId 与 cutoff-buffer 的较大值）。
     *
     * @param workspaceMinIds workspace_id 到其有效下界的映射
     * @param cutoffId        UUID v7 上界（不包含）
     * @param lowerBound      experiment_items 子查询的全局下界
     */
    Mono<Long> deleteForRetentionBounded(Map<String, UUID> workspaceMinIds, UUID cutoffId, UUID lowerBound);

    /**
     * 轻量级的删除前计数，用于可观测性。
     * 统计 [lowerBound, cutoffId) 范围内的 traces 数量，不包含 experiment_items 排除子查询
     * 以避免 join 开销。这是一个精度 >99% 的上界估计（实际中极少 traces 关联到实验）。
     */
    Mono<Long> countForRetention(List<String> workspaceIds, UUID cutoffId, UUID lowerBound);

    /**
     * 在一个月大小的范围内探测有 trace 数据的第一天。
     * 用于在完整估算查询失败的大型工作空间中找到数据的实际起始位置。
     *
     * @return 有数据的第一天的 Instant（UTC 当天开始），如果范围内无数据则返回空。
     *         如果月范围也超出行限制，则抛出错误码 158。
     */
    Mono<Instant> scoutFirstDayWithData(String workspaceId, UUID rangeStart, UUID rangeEnd);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
// TODO: v1 下线后，移除 annotation_queue_filters 条件，仅保留 annotation_queue_id
class TraceDAOImpl implements TraceDAO {

    private static final String TRACE_SEARCH_CLAUSE = """
            (ilike(id, :search_text)
            OR ilike(name, :search_text)
            OR ilike(input, :search_text)
            OR ilike(output, :search_text)
            OR ilike(metadata, :search_text)
            OR ilike(error_info, :search_text)
            OR arrayExists(element -> ilike(element, :search_text), tags)
            OR ilike(thread_id, :search_text))""";

    private static final String BATCH_INSERT = """
            INSERT INTO traces(
                id,
                project_id,
                workspace_id,
                name,
                start_time,
                end_time,
                input,
                output,
                metadata,
                tags,
                last_updated_at,
                error_info,
                created_by,
                last_updated_by,
                thread_id,
                visibility_mode,
                truncation_threshold,
                input_slim,
                output_slim,
                ttft,
                source,
                environment
            )
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (
                        :id<item.index>,
                        :project_id<item.index>,
                        :workspace_id,
                        :name<item.index>,
                        :start_time<item.index>,
                        :end_time<item.index>,
                        :input<item.index>,
                        :output<item.index>,
                        :metadata<item.index>,
                        :tags<item.index>,
                        :last_updated_at<item.index>,
                        :error_info<item.index>,
                        :user_name,
                        :user_name,
                        :thread_id<item.index>,
                        :visibility_mode<item.index>,
                        :truncation_threshold<item.index>,
                        :input_slim<item.index>,
                        :output_slim<item.index>,
                        :ttft<item.index>,
                        :source<item.index>,
                        :environment<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    /**
     * 此查询处理将新 trace 插入数据库的两种情况：
     * 1. 当 trace 在数据库中不存在时。
     * 2. 当 trace 在数据库中已存在，但提供的 trace 在 end_time、input、output、metadata 和 tags 等字段上有不同值时。
     **/
    //TODO: 重构以实现正确的冲突解决
    private static final String INSERT = """
            INSERT INTO traces (
                id,
                project_id,
                workspace_id,
                name,
                start_time,
                end_time,
                input,
                output,
                metadata,
                tags,
                error_info,
                created_at,
                created_by,
                last_updated_by,
                thread_id,
                visibility_mode,
                truncation_threshold,
                input_slim,
                output_slim,
                ttft,
                source,
                environment
            )
            SELECT
                new_trace.id as id,
                multiIf(
                    LENGTH(CAST(old_trace.project_id AS Nullable(String))) > 0 AND notEquals(old_trace.project_id, new_trace.project_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_trace.project_id AS Nullable(String))) > 0, old_trace.project_id,
                    new_trace.project_id
                ) as project_id,
                new_trace.workspace_id as workspace_id,
                multiIf(
                    LENGTH(old_trace.name) > 0, old_trace.name,
                    new_trace.name
                ) as name,
                multiIf(
                    notEquals(old_trace.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.start_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.start_time,
                    new_trace.start_time
                ) as start_time,
                multiIf(
                    notEquals(old_trace.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.end_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.end_time,
                    new_trace.end_time
                ) as end_time,
                multiIf(
                    LENGTH(old_trace.input) > 0, old_trace.input,
                    new_trace.input
                ) as input,
                multiIf(
                    LENGTH(old_trace.output) > 0, old_trace.output,
                    new_trace.output
                ) as output,
                multiIf(
                    LENGTH(old_trace.metadata) > 0, old_trace.metadata,
                    new_trace.metadata
                ) as metadata,
                multiIf(
                    notEmpty(old_trace.tags), old_trace.tags,
                    new_trace.tags
                ) as tags,
                multiIf(
                    LENGTH(old_trace.error_info) > 0, old_trace.error_info,
                    new_trace.error_info
                ) as error_info,
                multiIf(
                    notEquals(old_trace.created_at, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.created_at >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.created_at,
                    new_trace.created_at
                ) as created_at,
                multiIf(
                    LENGTH(old_trace.created_by) > 0, old_trace.created_by,
                    new_trace.created_by
                ) as created_by,
                new_trace.last_updated_by as last_updated_by,
                multiIf(
                    LENGTH(old_trace.thread_id) > 0, old_trace.thread_id,
                    new_trace.thread_id
                ) as thread_id,
                multiIf(
                    notEquals(old_trace.visibility_mode, 'unknown'), old_trace.visibility_mode,
                    new_trace.visibility_mode
                ) as visibility_mode,
                new_trace.truncation_threshold as truncation_threshold,
                multiIf(
                    notEmpty(old_trace.input) AND notEmpty(old_trace.input_slim), old_trace.input_slim,
                    new_trace.input_slim
                ) as input_slim,
                multiIf(
                    notEmpty(old_trace.output) AND notEmpty(old_trace.output_slim), old_trace.output_slim,
                    new_trace.output_slim
                ) as output_slim,
                multiIf(
                    old_trace.id != '' AND NOT isNaN(old_trace.ttft), old_trace.ttft,
                    new_trace.ttft
                ) as ttft,
                multiIf(
                    notEquals(old_trace.source, 'unknown'), old_trace.source,
                    new_trace.source
                ) as source,
                multiIf(
                    notEmpty(old_trace.environment), old_trace.environment,
                    new_trace.environment
                ) as environment
            FROM (
                SELECT
                    :id as id,
                    :project_id as project_id,
                    :workspace_id as workspace_id,
                    :name as name,
                    parseDateTime64BestEffort(:start_time, 9) as start_time,
                    parseDateTime64BestEffort(:end_time, 9) as end_time,
                    :input as input,
                    :output as output,
                    :metadata as metadata,
                    :tags as tags,
                    :error_info as error_info,
                    now64(9) as created_at,
                    :user_name as created_by,
                    :user_name as last_updated_by,
                    :thread_id as thread_id,
                    if(:visibility_mode IS NULL, 'default', :visibility_mode) as visibility_mode,
                    :truncation_threshold as truncation_threshold,
                    :input_slim as input_slim,
                    :output_slim as output_slim,
                    :ttft as ttft,
                    :source as source,
                    :environment as environment
            ) as new_trace
            LEFT JOIN (
                SELECT
                    *, truncated_input, truncated_output
                FROM traces
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) as old_trace
            ON new_trace.id = old_trace.id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /***
     * 处理当 trace 已存在于数据库中时的更新操作。
     ***/
    private static final String UPDATE = """
            INSERT INTO traces (
            	id, project_id, workspace_id, name, start_time, end_time, input, output, metadata, tags, error_info, created_at, created_by, last_updated_by, thread_id, visibility_mode, truncation_threshold, input_slim, output_slim, ttft, source, environment
            )
            SELECT
            	id,
            	project_id,
            	workspace_id,
            	<if(name)> :name <else> name <endif> as name,
            	start_time,
            	<if(end_time)> parseDateTime64BestEffort(:end_time, 9) <else> end_time <endif> as end_time,
            	<if(input)> :input <else> input <endif> as input,
            	<if(output)> :output <else> output <endif> as output,
            	<if(metadata)> :metadata <else> metadata <endif> as metadata,
            	<if(tags)> :tags <else> tags <endif> as tags,
            	<if(error_info)> :error_info <else> error_info <endif> as error_info,
            	created_at,
            	created_by,
                :user_name as last_updated_by,
                <if(thread_id)> :thread_id <else> thread_id <endif> as thread_id,
                visibility_mode,
                :truncation_threshold as truncation_threshold,
                <if(input)> :input_slim <else> input_slim <endif> as input_slim,
                <if(output)> :output_slim <else> output_slim <endif> as output_slim,
                <if(ttft)> :ttft <else> ttft <endif> as ttft,
                <if(source)> :source <else> source <endif> as source,
                <if(environment)> :environment <else> environment <endif> as environment
            FROM traces
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    // 从 traces 中获取目标 project_ids 的查询（单独执行以减少表扫描）
    private static final String SELECT_TARGET_PROJECTS_FOR_TRACES = """
            SELECT DISTINCT project_id
            FROM traces
            WHERE workspace_id = :workspace_id
            AND id IN :ids
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 构建用于反馈评分的 {@code value_by_author} 映射，使用复合键。
     * 键格式：{@code author} + 可选的 {@code _queueId} + 可选的 {@code _spanId}（确保跨队列/span 的唯一性）。
     * 值元组：(value, reason, category_name, source, last_updated_at, span_type, span_id, source_queue_id, author)。
     *
     * <p>experiments_agg 会折叠为每个 trace_id 的唯一规范行（按 UUIDv7 排序的 id 取最近的实验），
     * 因此按 trace 查找的 LEFT JOIN 不会把属于多个实验的 trace 展开为多行——这曾表现为按 id 执行 GET 时的
     * IndexOutOfBoundsException 500 以及不确定的实验元数据（OPIK-7396）。
     */
    private static final String SELECT_BY_IDS = """
            WITH target_spans AS (
                SELECT id, trace_id, type
                FROM spans
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND trace_id IN :ids
                ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, id
            ),
            feedback_scores_deduped AS (
                SELECT workspace_id,
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
                    SELECT workspace_id,
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
                    AND entity_id IN :ids
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
                     AND entity_id IN :ids
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
             ), feedback_scores_final AS (
                 SELECT
                     workspace_id,
                     project_id,
                     entity_id,
                     name,
                     arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                     IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                     IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                     entries[1].4 AS source,
                     mapFromArrays(
                             arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                             arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                     ) AS value_by_author,
                     arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                     arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                     arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                     arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                 FROM feedback_scores_grouped
            ), span_feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       trace_id,
                       span_id,
                       span_type,
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
                    SELECT fs.workspace_id,
                           fs.project_id,
                           s.trace_id,
                           s.id AS span_id,
                           s.type AS span_type,
                           fs.name,
                           fs.category_name,
                           fs.value,
                           fs.reason,
                           fs.source,
                           fs.created_by,
                           fs.last_updated_by,
                           fs.created_at,
                           fs.last_updated_at,
                           fs.last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores AS fs
                    INNER JOIN target_spans s ON fs.entity_id = s.id
                    WHERE fs.entity_type = 'span'
                    AND fs.workspace_id = :workspace_id
                    <if(has_target_projects)>AND fs.project_id IN :target_project_ids<endif>
                    UNION ALL
                    SELECT afs.workspace_id,
                           afs.project_id,
                           s.trace_id,
                           s.id AS span_id,
                           s.type AS span_type,
                           afs.name,
                           afs.category_name,
                           afs.value,
                           afs.reason,
                           afs.source,
                           afs.created_by,
                           afs.last_updated_by,
                           afs.created_at,
                           afs.last_updated_at,
                           afs.author,
                           afs.source_queue_id
                    FROM authored_feedback_scores AS afs
                    INNER JOIN target_spans s ON afs.entity_id = s.id
                    WHERE afs.entity_type = 'span'
                    AND afs.workspace_id = :workspace_id
                    <if(has_target_projects)>AND afs.project_id IN :target_project_ids<endif>
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, span_id, name, author, source_queue_id
            ), span_feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, span_type, span_id, source_queue_id)) AS entries
                FROM span_feedback_scores_deduped
                GROUP BY workspace_id, project_id, trace_id, name
            ), span_feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1,
                        entries[1].2,
                        arrayStringConcat(
                            arrayFilter(x -> x != '' AND x != '\\<no reason>', arrayMap(e -> e.2, entries)),
                            ', '
                        )
                    ) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                            arrayMap(e -> concat(e.5, if(e.12 = '', '', concat('_', toString(e.12))), if(e.11 IS NULL OR e.11 = '', '', concat('_', toString(e.11)))), entries),
                            arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, e.10, e.11, e.12, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM span_feedback_scores_grouped
            ), spans_deduped AS (
                SELECT
                    trace_id,
                    id,
                    type,
                    usage,
                    total_estimated_cost,
                    provider
                FROM spans
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND trace_id IN :ids
                ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, id
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    count(id) AS span_count,
                    toInt64(countIf(type = 'llm')) AS llm_span_count,
                    countIf(type = 'tool') > 0 AS has_tool_spans,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans_deduped
                GROUP BY trace_id
            ), experiments_agg AS (
                SELECT
                    ei.trace_id,
                    if(div.id != '', div.dataset_item_id, ei.dataset_item_id) AS experiment_dataset_item_id,
                    e.id AS experiment_id,
                    e.name AS experiment_name,
                    e.dataset_id AS experiment_dataset_id
                FROM (
                    SELECT DISTINCT experiment_id, trace_id, dataset_item_id
                    FROM experiment_items
                    WHERE workspace_id = :workspace_id
                    AND trace_id IN :ids
                ) ei
                LEFT JOIN (
                    SELECT id, dataset_item_id
                    FROM dataset_item_versions
                    WHERE workspace_id = :workspace_id
                    AND id IN (
                        SELECT dataset_item_id FROM experiment_items
                        WHERE workspace_id = :workspace_id AND trace_id IN :ids
                    )
                ) div ON div.id = ei.dataset_item_id
                INNER JOIN (
                    SELECT id, name, dataset_id
                    FROM experiments
                    WHERE workspace_id = :workspace_id
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) e ON ei.experiment_id = e.id
                ORDER BY trace_id, experiment_id DESC
                LIMIT 1 BY trace_id
            )
            SELECT
                t.*,
                t.id as id,
                t.project_id as project_id,
                s.usage as usage,
                s.total_estimated_cost as total_estimated_cost,
                s.span_count AS span_count,
                s.llm_span_count AS llm_span_count,
                s.has_tool_spans AS has_tool_spans,
                s.providers as providers,
                c.comments as comments,
                fs.feedback_scores_list as feedback_scores_list,
                sfs.span_feedback_scores_list as span_feedback_scores_list,
                gr.guardrails as guardrails_validations,
                eaag.experiment_id as experiment_id,
                eaag.experiment_name as experiment_name,
                eaag.experiment_dataset_id as experiment_dataset_id,
                eaag.experiment_dataset_item_id as experiment_dataset_item_id
            FROM (
                SELECT
                    *,
                    duration
                FROM traces
                WHERE workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                AND id IN :ids
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS t
            LEFT JOIN spans_agg s ON t.id = s.trace_id
            LEFT JOIN experiments_agg eaag ON eaag.trace_id = t.id
            LEFT JOIN (
                SELECT
                    entity_id,
                    groupUniqArrayArray(comments_array) as comments
                FROM (
                    SELECT
                        entity_id,
                        groupArray(tuple(*)) AS comments_array
                    FROM (
                        SELECT
                            id,
                            text,
                            created_at,
                            last_updated_at,
                            created_by,
                            last_updated_by,
                            source_queue_id,
                            entity_id
                        FROM comments
                        WHERE workspace_id = :workspace_id
                        <if(has_target_projects)>AND project_id IN :target_project_ids<endif>
                        AND entity_id IN :ids
                        ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
                    GROUP BY entity_id
                )
                GROUP BY entity_id
            ) AS c ON t.id = c.entity_id
            LEFT JOIN (
                SELECT
                    entity_id,
                    mapFromArrays(
                            groupArray(name),
                            groupArray(value)
                    ) AS feedback_scores,
                    groupArray(tuple(
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        value_by_author,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    )) AS feedback_scores_list
                FROM feedback_scores_final
                GROUP BY workspace_id, project_id, entity_id
            ) AS fs ON t.id = fs.entity_id
            LEFT JOIN (
                SELECT
                    trace_id,
                    groupArray(tuple(
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        value_by_author,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    )) AS span_feedback_scores_list
                FROM span_feedback_scores_final
                GROUP BY workspace_id, project_id, trace_id
            ) AS sfs ON t.id = sfs.trace_id
            LEFT JOIN (
                SELECT
                    entity_id,
                    groupArray(tuple(
                         entity_id,
                         secondary_entity_id,
                         project_id,
                         name,
                         result
                    )) as guardrails
                FROM (
                    SELECT
                        *
                    FROM guardrails
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND entity_id IN :ids
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ) AS gr ON t.id = gr.entity_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * {@code toMonday(id_at) = ...} 将扫描固定到能容纳 {@code :id} 的单一周：这是 {@code id = :id} 的严格推论
     * （永远不会隐藏行），一旦 {@code traces} 进行分区，就能触发分区裁剪，
     * 这是查询规划器仅从 id 过滤器无法推断的。
     */
    private static final String SELECT_DETAILS_BY_ID = """
            SELECT DISTINCT
                workspace_id,
                project_id
            FROM traces
            WHERE id = :id
            AND toMonday(id_at) = toMonday(UUIDv7ToDateTime(toUUID(:id), 'UTC'))
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 两阶段、宽列延迟加载的 trace 分页查询。
     * <p>
     * 第一阶段（{@code page_ids}）仅在轻量的、去重后的 id + 排序键集合上分页——宽文本列
     * （input/output/metadata）从扫描的 {@code traces_deduped} CTE 中移除，除非排序目标包含它们
     * （{@code sort_needs_wide}）。第二阶段（{@code page_wide}）仅为当前页的 id 重新读取完整行（包含宽列）。
     * 自定义 {@code sort_fields} 同时渲染到 {@code page_ids} 的 ORDER BY（使分页选择正确的页）
     * 和最终的 ORDER BY（使页面按序返回）；{@code page_wide} 自身的顺序无关紧要，因为它受 id 约束且使用
     * {@code LIMIT 1 BY id}。字段排除（{@code exclude_fields}）和截断在不丢弃排序键的情况下叠加在上层。
     * <p>
     * 每个 {@code traces} 的 id 范围边界都带有并行的 {@code toMonday(id_at)} 边界：这是 id 范围的严格推论——
     * 并且与 {@code created_at} 谓词不同，它对迟到的行是安全的，因为它源自 {@code id}——一旦 {@code traces}
     * 完成分区，即可让规划器裁剪分区。
     * <p>
     * 当聚合仅用于富化（{@code page_keyed_aggregates}，见
     * {@code shouldPageKeyAggregates}）时，聚合 CTE 以
     * {@code IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))} 而不是
     * {@code trace_id_prefilter} 为键：内部标量子查询只求值一次并对整个查询缓存，
     * 因此每个聚合只扫描当前页的 traces，而不是整个过滤后的项目。聚合 CTE
     * 在其定义之前就引用 {@code page_ids} 没有问题——CTE 名称的解析与声明顺序无关。
     * <p>
     * ClickHouse 升级后必须重新验证此模式所依赖的三个行为（在 25.3 和 25.8 上验证过）：
     * 前向 CTE 解析、标量子查询缓存（一次求值在所有引用处复用——如果
     * 缓存不再生效，结果仍然正确，但每个聚合都会悄然退化回全项目扫描），
     * 以及物化 IN 集合的主键裁剪。
     */
    private static final String SELECT_BY_PROJECT_ID = """
            WITH <if(trace_id_prefilter)>trace_id_prefilter AS (
                SELECT DISTINCT id
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(last_received_id)> AND id \\< :last_received_id
                    AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:last_received_id), 'UTC')) <endif>
                <if(uuid_from_time)> AND id >= :uuid_from_time
                    AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                <if(uuid_to_time)> AND id \\<= :uuid_to_time
                    AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
            ), <endif><if(!exclude_feedback_scores)>feedback_scores_deduped AS (
                SELECT workspace_id,
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
                    SELECT workspace_id,
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
                      AND project_id = :project_id
                      <if(page_keyed_aggregates)> AND entity_id IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))
                      <elseif(trace_id_prefilter)> AND entity_id IN (SELECT id FROM trace_id_prefilter)
                      <else>
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                      <endif>
                    UNION ALL
                    SELECT workspace_id,
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
                      AND project_id = :project_id
                      <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                      <if(page_keyed_aggregates)> AND entity_id IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))
                      <elseif(trace_id_prefilter)> AND entity_id IN (SELECT id FROM trace_id_prefilter)
                      <else>
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                      <endif>
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
             ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                            arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                            arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM feedback_scores_grouped
            )
            , feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                ) AS feedback_scores,
                groupArray(tuple(
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        value_by_author,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    )) AS feedback_scores_list
                FROM feedback_scores_final
                GROUP BY workspace_id, project_id, entity_id
            ),<endif> guardrails_agg AS (
                SELECT
                    entity_id,
                    groupArray(tuple(
                         entity_id,
                         secondary_entity_id,
                         project_id,
                         name,
                         result
                    )) as guardrails_list,
                    if(has(groupArray(result), 'failed'), 'failed', 'passed') as guardrails_result
                FROM (
                    SELECT
                        *
                    FROM guardrails
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id = :project_id
                    <if(page_keyed_aggregates)> AND entity_id IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))
                    <elseif(trace_id_prefilter)> AND entity_id IN (SELECT id FROM trace_id_prefilter)
                    <else>
                    <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                    <endif>
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ), <if(!exclude_feedback_scores)>target_spans AS (
                SELECT DISTINCT id, trace_id
                FROM spans
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(page_keyed_aggregates)>AND trace_id IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))
                <elseif(trace_id_prefilter)>AND trace_id IN (SELECT id FROM trace_id_prefilter)
                <else>
                <if(uuid_from_time)>AND trace_id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND trace_id \\<= :uuid_to_time<endif>
                <endif>
            ),
            span_feedback_scores_deduped AS (
                SELECT workspace_id,
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
                    SELECT workspace_id,
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
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      <if(uuid_from_time || uuid_to_time)>
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                      <else>
                      AND entity_id IN (SELECT id FROM target_spans)
                      <endif>
                    UNION ALL
                    SELECT workspace_id,
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
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      <if(uuid_from_time || uuid_to_time)>
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                      <else>
                      AND entity_id IN (SELECT id FROM target_spans)
                      <endif>
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), span_feedback_scores_with_trace_id AS (
                SELECT workspace_id,
                       project_id,
                       s.trace_id,
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
                FROM span_feedback_scores_deduped sfs
                INNER JOIN target_spans s ON sfs.entity_id = s.id
            ), span_feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM span_feedback_scores_with_trace_id
                GROUP BY workspace_id, project_id, trace_id, name
            ), span_feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                            arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                            arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM span_feedback_scores_grouped
            ), span_feedback_scores_agg AS (
                SELECT
                    trace_id,
                    groupArray(tuple(
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        value_by_author,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    )) AS span_feedback_scores_list
                FROM span_feedback_scores_final
                GROUP BY workspace_id, project_id, trace_id
            ),<endif> spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    COUNT(DISTINCT id) as span_count,
                    toInt64(countIf(type = 'llm')) as llm_span_count,
                    countIf(type = 'tool') > 0 as has_tool_spans,
                    arraySort(groupUniqArrayIf(provider, provider != '')) as providers
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(page_keyed_aggregates)>AND trace_id IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))
                <elseif(trace_id_prefilter)>AND trace_id IN (SELECT id FROM trace_id_prefilter)
                <else>
                <if(uuid_from_time)>AND trace_id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND trace_id \\<= :uuid_to_time<endif>
                <endif>
                GROUP BY workspace_id, project_id, trace_id
            ), comments_agg AS (
                SELECT
                    entity_id,
                    groupArray(tuple(id, text, created_at, last_updated_at, created_by, last_updated_by, source_queue_id)) AS comments_array
                FROM (
                    SELECT
                        id,
                        text,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by,
                        source_queue_id,
                        entity_id,
                        workspace_id,
                        project_id
                    FROM comments
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                    <if(page_keyed_aggregates)> AND entity_id IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))
                    <elseif(trace_id_prefilter)> AND entity_id IN (SELECT id FROM trace_id_prefilter)
                    <else>
                    <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                    <endif>
                    ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                GROUP BY workspace_id, project_id, entity_id
            ), trace_annotation_queue_ids AS (
                 SELECT trace_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as trace_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      <if(page_keyed_aggregates)> AND aqi.item_id IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))
                      <elseif(trace_id_prefilter)> AND aqi.item_id IN (SELECT id FROM trace_id_prefilter)
                      <else>
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                      <endif>
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
            )
            <if(sort_has_experiment || !exclude_experiment)>
            , experiments_agg AS (
                SELECT DISTINCT
                    ei.trace_id,
                    if(div.id != '', div.dataset_item_id, ei.dataset_item_id) AS experiment_dataset_item_id,
                    e.id AS experiment_id,
                    e.name AS experiment_name,
                    e.dataset_id AS experiment_dataset_id
                FROM (
                    SELECT DISTINCT experiment_id, trace_id, dataset_item_id
                    FROM experiment_items
                    WHERE workspace_id = :workspace_id
                    <if(page_keyed_aggregates)> AND trace_id IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))
                    <elseif(trace_id_prefilter)> AND trace_id IN (SELECT id FROM trace_id_prefilter)
                    <else>
                    <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
                    <endif>
                ) ei
                LEFT JOIN (
                    SELECT id, dataset_item_id
                    FROM dataset_item_versions
                    WHERE workspace_id = :workspace_id
                    AND id IN (
                        SELECT dataset_item_id FROM experiment_items
                        WHERE workspace_id = :workspace_id
                        <if(page_keyed_aggregates)> AND trace_id IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))
                        <elseif(trace_id_prefilter)> AND trace_id IN (SELECT id FROM trace_id_prefilter)
                        <else>
                        <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                        <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
                        <endif>
                    )
                ) div ON div.id = ei.dataset_item_id
                INNER JOIN (
                    SELECT id, name, dataset_id
                    FROM experiments
                    WHERE workspace_id = :workspace_id
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) e ON ei.experiment_id = e.id
            )
            <endif>
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM feedback_scores_final
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
             )
            <endif>
            <if(span_feedback_scores_empty_filters)>
             , sfsc AS (SELECT trace_id, COUNT(trace_id) AS span_feedback_scores_count
                 FROM span_feedback_scores_final
                 GROUP BY trace_id
                 HAVING <span_feedback_scores_empty_filters>
             )
            <endif>
            , traces_deduped AS (
                SELECT
                    t.* EXCEPT (input_slim, output_slim<if(!sort_needs_wide)><if(!exclude_input)>, input<endif><if(!exclude_output)>, output<endif><if(!exclude_metadata)>, metadata<endif><endif>) <if(exclude_fields)>EXCEPT (<exclude_fields>) <endif>,
                    input_length,
                    output_length,
                    duration
                FROM traces t
                <if(guardrails_filters)>
                    LEFT JOIN guardrails_agg gagg ON gagg.entity_id = t.id
                <endif>
                <if(feedback_scores_empty_filters)>
                LEFT JOIN fsc ON fsc.entity_id = t.id
                <endif>
                <if(span_feedback_scores_empty_filters)>
                LEFT JOIN sfsc ON sfsc.trace_id = t.id
                <endif>
                <if(annotation_queue_filters || annotation_queue_id)>
                LEFT JOIN trace_annotation_queue_ids as taqi ON taqi.trace_id = t.id
                <endif>
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)> AND id >= :uuid_from_time
                    AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                <if(uuid_to_time)> AND id \\<= :uuid_to_time
                    AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                <if(last_received_id)> AND id \\< :last_received_id
                    AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:last_received_id), 'UTC')) <endif>
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
                <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
                <if(annotation_queue_id)> AND has(taqi.annotation_queue_ids, :annotation_queue_id) <endif>
                <if(feedback_scores_filters)>
                 AND id IN (
                    SELECT entity_id
                    FROM feedback_scores_final
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                 )
                 <endif>
                 <if(span_feedback_scores_filters)>
                 AND id IN (
                    SELECT
                        trace_id
                    FROM span_feedback_scores_final
                    GROUP BY trace_id
                    HAVING <span_feedback_scores_filters>
                 )
                 <endif>
                 <if(trace_aggregation_filters)>
                 AND id IN (
                    SELECT
                        trace_id
                    FROM spans_agg
                    WHERE <trace_aggregation_filters>
                 )
                 <endif>
                 <if(experiment_filters)>
                 AND id IN (
                    SELECT
                        trace_id
                    FROM experiment_items
                    WHERE workspace_id = :workspace_id
                    AND <experiment_filters>
                    ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                 )
                 <endif>
                 <if(feedback_scores_empty_filters)>
                 AND (
                    id IN (SELECT entity_id FROM fsc WHERE fsc.feedback_scores_count = 0)
                        OR
                    id NOT IN (SELECT entity_id FROM fsc)
                 )
                 <endif>
                 <if(span_feedback_scores_empty_filters)>
                 AND (
                    id IN (SELECT trace_id FROM sfsc WHERE sfsc.span_feedback_scores_count = 0)
                        OR
                    id NOT IN (SELECT trace_id FROM sfsc)
                 )
                 <endif>
                 ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                 LIMIT 1 BY id
            ), page_ids AS (
                SELECT td.id
                FROM traces_deduped td
                <if(sort_has_feedback_scores)>
                LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = td.id
                <endif>
                <if(sort_has_span_statistics)>
                LEFT JOIN spans_agg s ON td.id = s.trace_id
                <endif>
                <if(sort_has_experiment)>
                LEFT JOIN experiments_agg eaag ON eaag.trace_id = td.id
                <endif>
                ORDER BY <if(sort_fields)> <sort_fields>, <endif>(workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT :limit <if(offset)>OFFSET :offset <endif>
            ), page_wide AS (
                SELECT
                    t.* EXCEPT (input_slim, output_slim)<if(exclude_fields)> EXCEPT (<exclude_fields>)<endif>,
                    <if(truncate)><if(!exclude_input)>truncated_input,<endif><if(!exclude_output)>truncated_output,<endif><endif>
                    input_length,
                    output_length,
                    duration
                FROM traces t
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id IN (SELECT id FROM page_ids)
                <if(uuid_from_time)> AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                <if(uuid_to_time)> AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                <if(last_received_id)> AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:last_received_id), 'UTC')) <endif>
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            SELECT
                  t.* <if(exclude_fields)>EXCEPT (<exclude_fields><if(!exclude_input)>, input<endif><if(!exclude_output)>, output<endif><if(!exclude_metadata)>, metadata<endif><if(truncate)><if(!exclude_input)>, truncated_input<endif><if(!exclude_output)>, truncated_output<endif><endif>) <else> EXCEPT (input, output, metadata<if(truncate)>, truncated_input, truncated_output<endif>)<endif>
                  <if(!exclude_input)>, <if(truncate)> replaceRegexpAll(t.truncated_input, '<truncate>', '"[image]"') as input <else> t.input as input <endif><endif>
                  <if(!exclude_output)>, <if(truncate)> replaceRegexpAll(t.truncated_output, '<truncate>', '"[image]"') as output <else> t.output as output <endif><endif>
                  <if(!exclude_metadata)>, <if(truncate)> replaceRegexpAll(t.metadata, '<truncate>', '"[image]"') as metadata <else> t.metadata as metadata <endif><endif>
                  <if(truncate)>, input_length >= truncation_threshold as input_truncated<endif>
                  <if(truncate)>, output_length >= truncation_threshold as output_truncated<endif>
                  <if(!exclude_feedback_scores)>
                  , fsagg.feedback_scores_list as feedback_scores_list
                  , fsagg.feedback_scores as feedback_scores
                  , sfsagg.span_feedback_scores_list as span_feedback_scores_list
                  <endif>
                  <if(!exclude_usage)>, s.usage as usage<endif>
                  <if(!exclude_total_estimated_cost)>, s.total_estimated_cost as total_estimated_cost<endif>
                  <if(!exclude_comments)>, c.comments_array as comments <endif>
                  <if(!exclude_guardrails_validations)>, gagg.guardrails_list as guardrails_validations<endif>
                  <if(!exclude_span_count)>, s.span_count AS span_count<endif>
                  <if(!exclude_llm_span_count)>, s.llm_span_count AS llm_span_count<endif>
                  <if(!exclude_has_tool_spans)>, s.has_tool_spans AS has_tool_spans<endif>
                  , s.providers AS providers
                  <if(!exclude_experiment)>, eaag.experiment_id, eaag.experiment_name, eaag.experiment_dataset_id, eaag.experiment_dataset_item_id<endif>
             FROM page_wide t
             <if(!exclude_feedback_scores)>
             LEFT JOIN feedback_scores_agg fsagg ON fsagg.entity_id = t.id
             LEFT JOIN span_feedback_scores_agg sfsagg ON sfsagg.trace_id = t.id
             <endif>
             LEFT JOIN spans_agg s ON t.id = s.trace_id
             LEFT JOIN comments_agg c ON t.id = c.entity_id
             LEFT JOIN guardrails_agg gagg ON gagg.entity_id = t.id
             <if(sort_has_experiment || !exclude_experiment)>LEFT JOIN experiments_agg eaag ON eaag.trace_id = t.id<endif>
             ORDER BY <if(sort_fields)> <sort_fields>, <endif>(workspace_id, project_id, id) DESC, last_updated_at DESC
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String TRACE_COUNT_BY_WORKSPACE_ID = """
            SELECT
                 workspace_id,
                 COUNT(DISTINCT id) as trace_count
             FROM traces
             WHERE created_at BETWEEN toStartOfDay(yesterday()) AND toStartOfDay(today())
             <if(excluded_project_ids)> AND (project_id NOT IN :excluded_project_ids
                <if(demo_data_created_at)>OR created_at > parseDateTime64BestEffort(:demo_data_created_at, 9)<endif>)
             <endif>
             GROUP BY workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String TRACE_DAILY_BI_INFORMATION = """
            SELECT
                 workspace_id,
                 created_by AS user,
                 COUNT(DISTINCT id) AS trace_count
            FROM traces
            WHERE created_at BETWEEN toStartOfDay(yesterday()) AND toStartOfDay(today())
            <if(excluded_project_ids)> AND (project_id NOT IN :excluded_project_ids
                <if(demo_data_created_at)>OR created_at > parseDateTime64BestEffort(:demo_data_created_at, 9)<endif>)
            <endif>
            GROUP BY workspace_id, created_by
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 用于 Logs 空状态的轻量级“项目是否存在任何 trace？”探测。刻意保持最小化——
     * 仅项目范围——因此它始终是可主键裁剪的 {@code LIMIT 1}（traces 的排序键为
     * {@code (workspace_id, project_id, id)}）。它有意不支持任意过滤、搜索或
     * 时间范围：没有消费者需要它们，而加回这些会重新引入全项目 COUNT 回退。
     */
    private static final String EXISTS_BY_PROJECT_ID = """
            SELECT 1 AS exist
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            <if(source)> AND source IN (:source<if(source_legacy)>, :source_legacy<endif>) <endif>
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * 支撑 Threads 标签页空状态的线程范围变体。探测 {@code trace_threads}，而不是
     * {@code traces WHERE thread_id != ''}：{@code thread_id} 不在 {@code traces} 的排序键中，它在
     * 那里唯一的索引是布隆过滤器，只服务于等值匹配而非 {@code != ''} 不等值——因此该
     * 谓词无法裁剪，且会在它所要针对的“无线程”空状态下扫描整个项目。
     * {@code trace_threads} 按 {@code (workspace_id, project_id, thread_id, id)} 排序，因此项目
     * 范围是可主键裁剪的，且它与 Threads 列表读取的是同一张表（保持一致的空状态）。
     */
    private static final String THREADS_EXISTS_BY_PROJECT_ID = """
            SELECT 1 AS exist
            FROM trace_threads
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            <if(source)> AND source IN (:source<if(source_legacy)>, :source_legacy<endif>) <endif>
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * traces 列表页背后的总行数。数据见 OPIK-7836；在改动任一门槛前请重新测量。
     *
     * <p>当没有任何消费方时，此模板有两件事绝对不能做：
     *
     * <ul>
     * <li>{@code guardrails_agg} 仅在 {@code guardrails_filters} 下被 join，与本类中其他过滤侧的
     * join 保持一致。计数中没有别的地方读取 {@code gagg.guardrails_result}，而这个 join 也正是让
     * {@code trace_id_prefilter} 被引用的原因——因此保留它会在每次页面加载时带来对项目的第二次全量扫描
     * （在 790 万行的项目上测得读取 1590 万行）。该门槛不会静默返回错误的
     * 计数：{@code FilterUtils} 恰好会在将 {@code gagg} 别名渲染进 {@code filters} 的情况下设置
     * {@code guardrails_filters}，因此不匹配会导致解析失败（见 {@link #canDedupByArgMax}）。</li>
     * <li>{@code ReplacingMergeTree} 版本的 {@code ORDER BY} + {@code LIMIT 1 BY id} 去重仅在
     * {@code trace_aggregation_filters} 下保留，该分支按 {@code t.id} 分组，因此每个版本需要一行。
     * 否则计数使用 {@code count(DISTINCT id)}：无论哪种方式 {@code WHERE} 都在去重前应用，因此
     * 两种形式都统计任意版本匹配的 id，但只有其中一种会对每个匹配行进行排序。</li>
     * </ul>
     */
    private static final String COUNT_BY_PROJECT_ID = """
            WITH <if(trace_id_prefilter)>trace_id_prefilter AS (
                SELECT DISTINCT id
                FROM traces
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(uuid_from_time)> AND id >= :uuid_from_time
                    AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                <if(uuid_to_time)> AND id \\<= :uuid_to_time
                    AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
            ), <endif>feedback_scores_deduped AS (
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
                      AND project_id = :project_id
                      <if(trace_id_prefilter)> AND entity_id IN (SELECT id FROM trace_id_prefilter)
                      <else>
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                      <endif>
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
                       AND project_id = :project_id
                       <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                       <if(trace_id_prefilter)> AND entity_id IN (SELECT id FROM trace_id_prefilter)
                       <else>
                       <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                       <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                       <endif>
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
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), guardrails_agg AS (
                SELECT
                    entity_id,
                    if(has(groupArray(result), 'failed'), 'failed', 'passed') as guardrails_result
                FROM (
                    SELECT
                        *
                    FROM guardrails
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id = :project_id
                    <if(trace_id_prefilter)> AND entity_id IN (SELECT id FROM trace_id_prefilter)
                    <else>
                    <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                    <endif>
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ), trace_annotation_queue_ids AS (
                 SELECT trace_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as trace_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      <if(trace_id_prefilter)> AND aqi.item_id IN (SELECT id FROM trace_id_prefilter)
                      <else>
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                      <endif>
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
            ), target_spans AS (
                SELECT DISTINCT id, trace_id
                FROM spans
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(trace_id_prefilter)>AND trace_id IN (SELECT id FROM trace_id_prefilter)
                <else>
                <if(uuid_from_time)>AND trace_id >= :uuid_from_time<endif>
                <if(uuid_to_time)>AND trace_id \\<= :uuid_to_time<endif>
                <endif>
            ),
            span_feedback_scores_deduped AS (
                SELECT workspace_id,
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
                    SELECT workspace_id,
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
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND entity_id IN (SELECT id FROM target_spans)
                    UNION ALL
                    SELECT workspace_id,
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
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND entity_id IN (SELECT id FROM target_spans)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), span_feedback_scores_with_trace_id AS (
                SELECT workspace_id,
                       project_id,
                       s.trace_id,
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
                FROM span_feedback_scores_deduped sfs
                INNER JOIN target_spans s ON sfs.entity_id = s.id
            ), span_feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM span_feedback_scores_with_trace_id
                GROUP BY workspace_id, project_id, trace_id, name
            ), span_feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                            arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                            arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM span_feedback_scores_grouped
            ), span_feedback_scores_agg AS (
                SELECT
                    trace_id,
                    groupArray(tuple(
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        value_by_author,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    )) AS span_feedback_scores_list
                FROM span_feedback_scores_final
                GROUP BY workspace_id, project_id, trace_id
            )
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM feedback_scores_final
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            <if(span_feedback_scores_empty_filters)>
             , sfsc AS (SELECT trace_id, COUNT(trace_id) AS span_feedback_scores_count
                 FROM span_feedback_scores_final
                 GROUP BY trace_id
                 HAVING <span_feedback_scores_empty_filters>
            )
            <endif>
            SELECT
                <if(trace_aggregation_filters)>count(id)<else>count(DISTINCT id)<endif> as count
            FROM (
                SELECT
                    t.id
                    <if(trace_aggregation_filters)>
                    ,sumMap(s.usage) as usage
                    ,sum(s.total_estimated_cost) as total_estimated_cost
                    ,toInt64(countIf(s.type = 'llm')) as llm_span_count
                    <endif>
                FROM (
                    SELECT
                        id
                    FROM traces
                        <if(guardrails_filters)>LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id<endif>
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = traces.id
                    <endif>
                    <if(span_feedback_scores_empty_filters)>
                    LEFT JOIN sfsc ON sfsc.trace_id = traces.id
                    <endif>
                    <if(annotation_queue_filters || annotation_queue_id)>
                    LEFT JOIN trace_annotation_queue_ids as taqi ON taqi.trace_id = traces.id
                    <endif>
                    WHERE project_id = :project_id
                    AND workspace_id = :workspace_id
                    <if(uuid_from_time)> AND id >= :uuid_from_time
                        AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC')) <endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time
                        AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC')) <endif>
                    <if(filters)> AND <filters> <endif>
                    <if(search_text)> AND <search_text> <endif>
                    <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
                    <if(annotation_queue_id)> AND has(taqi.annotation_queue_ids, :annotation_queue_id) <endif>
                    <if(feedback_scores_filters)>
                    AND id IN (
                        SELECT entity_id
                        FROM feedback_scores_final
                        GROUP BY entity_id
                        HAVING <feedback_scores_filters>
                    )
                    <endif>
                    <if(span_feedback_scores_filters)>
                    AND id IN (
                        SELECT
                            trace_id
                        FROM span_feedback_scores_final
                        GROUP BY trace_id
                        HAVING <span_feedback_scores_filters>
                    )
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    AND fsc.feedback_scores_count = 0
                    <endif>
                    <if(span_feedback_scores_empty_filters)>
                    AND (
                        id IN (SELECT trace_id FROM sfsc WHERE sfsc.span_feedback_scores_count = 0)
                            OR
                        id NOT IN (SELECT trace_id FROM sfsc)
                    )
                    <endif>
                    <if(experiment_filters)>
                    AND id IN (
                        SELECT
                            trace_id
                        FROM experiment_items
                        WHERE workspace_id = :workspace_id
                        AND <experiment_filters>
                        ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
                    <endif>
                    <if(trace_aggregation_filters)>
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                    <endif>
                ) AS t
                <if(trace_aggregation_filters)>
                LEFT JOIN (
                    SELECT
                        trace_id,
                        usage,
                        total_estimated_cost,
                        type
                    FROM spans
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND trace_id IN (SELECT trace_id FROM target_spans)
                    ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS s ON t.id = s.trace_id
                GROUP BY
                    t.id
                HAVING <trace_aggregation_filters>
                <endif>
            ) AS latest_rows
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 按完整的 {@code (workspace_id, project_id, id)} 排序键删除，匹配 {@code (project_id, id)} 元组，
     * 使单条语句可以跨多个项目（例如复用的 id 解析到其所有所属项目，或
     * 跨项目批量删除），而不是每个项目一条删除语句（OPIK-7483）。每一行被删除的数据都带有其 {@code
     * project_id}，因此没有任何删除是无项目的——一旦 {@code traces} 成为 Distributed 表，这也是必需的
     * （OPIK-7455）。有意不加 {@code id_at}/时间谓词，因此仍会删除 {@code id_at}
     * 不可信的行（例如被回绕的时间戳）；这里的正确性不依赖 {@code id_at}。
     * <p>
     * 这些对以两个位置字符串数组的形式绑定（从不内联），并用 {@code arrayZip} 重新压缩为 {@code (project_id, id)}
     * 元组，因此查询文本与批量大小无关，也不会有任何值以字面量形式进入 SQL。
     * {@code arrayZip} 是确定性函数而非子查询——ClickHouse 在删除 mutation 中拒绝子查询。
     * 调用方分批处理以保持每个数组在驱动程序的可靠绑定大小内（{@link
     * com.comet.opik.infrastructure.FilterUtils#ANALYTICS_DELETE_BATCH_SIZE}）。
     */
    private static final String DELETE_BY_PROJECT_ID_TRACE_ID_PAIRS = """
            DELETE FROM <if(distributed_wrap)>traces_local<else>traces<endif>
            WHERE workspace_id = :workspace_id
            AND (project_id, id) IN arrayZip(:project_ids, :trace_ids)
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * applyToPast=true 窗口 {@code [lower_bound, cutoff_id)} 的保留清理。
     * <p>
     * {@code toMonday(id_at)} 是未来的周分区表达式（{@code id_at} 从 UUIDv7 id 物化为 UTC）。
     * 将其限定到 cutoff 的周范围不会排除 id 范围会删除的行，因此不会改变删除的行；
     * 一旦 {@code traces} 进行分区（OPIK-6900），清理操作可以裁剪到范围内的分区。
     * 边界使用 UTC 以匹配 {@code id_at}，上界向前推进一周以确保与 cutoff 同一周的行仍在范围内。
     */
    private static final String DELETE_FOR_RETENTION = """
            DELETE FROM <if(distributed_wrap)>traces_local<else>traces<endif>
            WHERE workspace_id IN :workspace_ids
            AND id >= :lower_bound
            AND id \\< :cutoff_id
            AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:lower_bound), 'UTC'))
            AND toMonday(id_at) \\< addWeeks(toMonday(UUIDv7ToDateTime(toUUID(:cutoff_id), 'UTC')), 1)
            AND id NOT IN (
                SELECT trace_id FROM experiment_items
                WHERE workspace_id IN :workspace_ids
                AND trace_id >= :lower_bound
                AND trace_id \\< :cutoff_id
            )
            SETTINGS log_comment = '<log_comment>', lightweight_deletes_sync = 1, allow_nondeterministic_mutations = 1
            ;
            """;

    /**
     * 轻量级的删除前计数，用于可观测性。省略 {@code experiment_items} 排除子查询
     * 以避免 join 开销，使其成为精度 &gt;99% 的上界估计（实际中极少 traces 关联到实验）。
     * 携带与 {@code DELETE_FOR_RETENTION} 相同的 {@code toMonday(id_at)} 周边界，使计数在
     * 切换后裁剪到相同的分区，而不是每个周期扫描（并加载冷层标记）所有分区。
     */
    private static final String COUNT_FOR_RETENTION = """
            SELECT count() FROM traces
            WHERE workspace_id IN :workspace_ids
            AND id >= :lower_bound
            AND id \\< :cutoff_id
            AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:lower_bound), 'UTC'))
            AND toMonday(id_at) \\< addWeeks(toMonday(UUIDv7ToDateTime(toUUID(:cutoff_id), 'UTC')), 1)
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * {@code toMonday(id_at)} 边界镜像 {@code [range_start, range_end)} id 范围：这是一个严格推论，
     * 不会改变扫描的行，但一旦 {@code traces} 进行分区，就能触发分区裁剪。
     */
    private static final String SCOUT_FIRST_DAY_WITH_DATA = """
            SELECT toDate(UUIDv7ToDateTime(toUUID(id))) AS day
            FROM traces
            WHERE workspace_id = :workspace_id
            AND id >= :range_start AND id \\< :range_end
            AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:range_start), 'UTC'))
            AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:range_end), 'UTC'))
            GROUP BY day
            ORDER BY day
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_TRACE_ID_AND_WORKSPACE = """
            SELECT
                DISTINCT id, workspace_id
            FROM traces
            WHERE id IN :traceIds
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 此查询用于当更新先于插入处理，且 trace 在数据库中不存在时的场景。
     * <p>
     * 查询将使用提供的值（如 end_time、input、output、metadata 和 tags）插入/更新新 trace。
     * 如果某些值未提供，查询将使用默认值，这些值在其他查询中会被解释为 null。
     * <p>
     * 这种情况发生是因为该查询用于 patch 端点（允许部分更新），因此查询仅更新提供的字段。
     * 剩余字段将在 POST 请求携带所有必填字段创建 trace 时被更新/插入。
     */
    //TODO: 重构以实现正确的冲突解决
    private static final String INSERT_UPDATE = """
            INSERT INTO traces (
                id, project_id, workspace_id, name, start_time, end_time, input, output, metadata, tags, error_info, created_at, created_by, last_updated_by, thread_id, visibility_mode, truncation_threshold, input_slim, output_slim, ttft, source, environment
            )
            SELECT
                new_trace.id as id,
                multiIf(
                    LENGTH(CAST(old_trace.project_id AS Nullable(String))) > 0 AND notEquals(old_trace.project_id, new_trace.project_id), leftPad('', 40, '*'),
                    LENGTH(CAST(old_trace.project_id AS Nullable(String))) > 0, old_trace.project_id,
                    new_trace.project_id
                ) as project_id,
                new_trace.workspace_id as workspace_id,
                multiIf(
                    LENGTH(new_trace.name) > 0, new_trace.name,
                    LENGTH(old_trace.name) > 0, old_trace.name,
                    new_trace.name
                ) as name,
                multiIf(
                    notEquals(old_trace.start_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.start_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.start_time,
                    new_trace.start_time
                ) as start_time,
                multiIf(
                    notEquals(new_trace.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND new_trace.end_time >= toDateTime64('1970-01-01 00:00:00.000', 9), new_trace.end_time,
                    notEquals(old_trace.end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.end_time >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.end_time,
                    new_trace.end_time
                ) as end_time,
                multiIf(
                    LENGTH(new_trace.input) > 0, new_trace.input,
                    LENGTH(old_trace.input) > 0, old_trace.input,
                    new_trace.input
                ) as input,
                multiIf(
                    LENGTH(new_trace.output) > 0, new_trace.output,
                    LENGTH(old_trace.output) > 0, old_trace.output,
                    new_trace.output
                ) as output,
                multiIf(
                    LENGTH(new_trace.metadata) > 0, new_trace.metadata,
                    LENGTH(old_trace.metadata) > 0, old_trace.metadata,
                    new_trace.metadata
                ) as metadata,
                multiIf(
                    notEmpty(new_trace.tags), new_trace.tags,
                    notEmpty(old_trace.tags), old_trace.tags,
                    new_trace.tags
                ) as tags,
                multiIf(
                    LENGTH(new_trace.error_info) > 0, new_trace.error_info,
                    LENGTH(old_trace.error_info) > 0, old_trace.error_info,
                    new_trace.error_info
                ) as error_info,
                multiIf(
                    notEquals(old_trace.created_at, toDateTime64('1970-01-01 00:00:00.000', 9)) AND old_trace.created_at >= toDateTime64('1970-01-01 00:00:00.000', 9), old_trace.created_at,
                    new_trace.created_at
                ) as created_at,
                multiIf(
                    LENGTH(old_trace.created_by) > 0, old_trace.created_by,
                    new_trace.created_by
                ) as created_by,
                new_trace.last_updated_by as last_updated_by,
                multiIf(
                    LENGTH(old_trace.thread_id) > 0, old_trace.thread_id,
                    new_trace.thread_id
                ) as thread_id,
                multiIf(
                    notEquals(old_trace.visibility_mode, 'unknown'), old_trace.visibility_mode,
                    new_trace.visibility_mode
                ) as visibility_mode,
                new_trace.truncation_threshold as truncation_threshold,
                multiIf(
                    notEmpty(new_trace.input_slim), new_trace.input_slim,
                    notEmpty(old_trace.input) AND notEmpty(old_trace.input_slim), old_trace.input_slim,
                    new_trace.input_slim
                ) as input_slim,
                multiIf(
                    notEmpty(new_trace.output_slim), new_trace.output_slim,
                    notEmpty(old_trace.output) AND notEmpty(old_trace.output_slim), old_trace.output_slim,
                    new_trace.output_slim
                ) as output_slim,
                multiIf(
                    NOT isNaN(new_trace.ttft), new_trace.ttft,
                    old_trace.id != '' AND NOT isNaN(old_trace.ttft), old_trace.ttft,
                    new_trace.ttft
                ) as ttft,
                multiIf(
                    notEquals(old_trace.source, 'unknown'), old_trace.source,
                    new_trace.source
                ) as source,
                multiIf(
                    notEmpty(new_trace.environment), new_trace.environment,
                    old_trace.environment
                ) as environment
            FROM (
                SELECT
                    :id as id,
                    :project_id as project_id,
                    :workspace_id as workspace_id,
                    <if(name)> :name <else> '' <endif> as name,
                    toDateTime64('1970-01-01 00:00:00.000', 9) as start_time,
                    parseDateTime64BestEffort(:end_time, 9) as end_time,
                    <if(input)> :input <else> '' <endif> as input,
                    <if(output)> :output <else> '' <endif> as output,
                    <if(metadata)> :metadata <else> '' <endif> as metadata,
                    <if(tags)> :tags <else> [] <endif> as tags,
                    <if(error_info)> :error_info <else> '' <endif> as error_info,
                    now64(9) as created_at,
                    :user_name as created_by,
                    :user_name as last_updated_by,
                    <if(thread_id)> :thread_id <else> '' <endif> as thread_id,
                    <if(visibility_mode)> :visibility_mode <else> 'unknown' <endif> as visibility_mode,
                    :truncation_threshold as truncation_threshold,
                    <if(input)> :input_slim <else> '' <endif> as input_slim,
                    <if(output)> :output_slim <else> '' <endif> as output_slim,
                    :ttft as ttft,
                    :source as source,
                    <if(environment)> :environment <else> '' <endif> as environment
            ) as new_trace
            LEFT JOIN (
                SELECT
                    *, truncated_input, truncated_output
                FROM traces
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1
            ) as old_trace
            ON new_trace.id = old_trace.id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_PARTIAL_BY_ID = """
            SELECT
                project_id,
                start_time
            FROM traces
            WHERE id = :id
            AND toMonday(id_at) = toMonday(UUIDv7ToDateTime(toUUID(:id), 'UTC'))
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_PROJECTS_WITH_TRACES_IN_RANGE = """
            SELECT DISTINCT project_id
            FROM traces
            WHERE (workspace_id, project_id) IN (<workspace_project_pairs>)
            AND created_at >= parseDateTime64BestEffort(:from_time, 9)
            AND created_at \\< parseDateTime64BestEffort(:to_time, 9)
            SETTINGS log_comment = '<log_comment>'
            ;
            """;
    private static final String SELECT_PROJECT_ID_FROM_TRACE = """
            SELECT
                DISTINCT project_id
            FROM traces
            WHERE id = :id
            AND toMonday(id_at) = toMonday(UUIDv7ToDateTime(toUUID(:id), 'UTC'))
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_PROJECT_IDS_BY_TRACE_IDS = """
            SELECT
                id,
                any(project_id) AS project_id
            FROM traces
            WHERE id IN :trace_ids
            AND workspace_id = :workspace_id
            GROUP BY id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 为一组 trace id 解析每个不同的 {@code (id, project_id)} 对。复用的 id 映射到<b>所有</b>其
     * 所属项目——不同于 {@code SELECT_PROJECT_IDS_BY_TRACE_IDS} 的 {@code any(project_id)}——因此删除路径
     * 会在完整排序键下将其从每个项目中移除（OPIK-7483）。{@code DISTINCT}（而非 FINAL）已足够：轻量删除
     * 掩码在读取时生效，因此已删除的对绝不会出现，而存活的对始终出现，与
     * 版本无关。没有时间谓词，因此无论 {@code id_at} 如何都会解析行；删除路径仅将其用作
     * 有界遍历无法解析的 id 的回退（格式错误的回绕 {@code id_at} 行）。在
     * {@code idx_traces_id_bf} 布隆过滤器索引（migration 000113）上裁剪颗粒：{@code id IN} 查找会跳过
     * 不含任何这些 id 的颗粒，而不是扫描整个工作区，从而让回退保持廉价。
     */
    private static final String SELECT_ALL_PROJECT_IDS_BY_TRACE_IDS = """
            SELECT DISTINCT id, project_id
            FROM traces
            WHERE id IN :trace_ids
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * {@code SELECT_ALL_PROJECT_IDS_BY_TRACE_IDS} 的分区裁剪快速路径。将 {@code toMonday(id_at)}
     * （{@code id_at} 由 UUIDv7 id 物化而来，migration 000091）约束到 id 集合自身的最小/最大周，并且与
     * 无界查询一样，在 {@code idx_traces_id_bf} 布隆索引上裁剪颗粒。周窗口在当前未分区的表上是空操作，
     * 但一旦 {@code traces} 完成分区即可裁剪分区。格式良好的 UUIDv7 id 的
     * {@code id_at} 与 id 单调一致，因此该窗口可以解析它们；{@code id_at} 发生回绕的格式错误 id
     * （OPIK-7456）可能落在窗口之外，并由无界回退重新解析，因此有界查询永远不会成为
     * 删除操作的唯一解析器。
     */
    private static final String SELECT_ALL_PROJECT_IDS_BY_TRACE_IDS_BOUNDED = """
            SELECT DISTINCT id, project_id
            FROM traces
            WHERE id IN :trace_ids
            AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:min_id), 'UTC'))
            AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:max_id), 'UTC'))
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_START_TIMES_BY_TRACE_IDS = """
            SELECT
                id,
                start_time
            FROM traces
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            ORDER BY id, last_updated_at DESC
            LIMIT 1 BY id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    // Split-A：traces + spans 聚合。保留所有反馈评分 CTE，使 trace_final 中现有的
    // feedback_scores_filters / span_feedback_scores_filters / *_empty_filters 槽位
    // 仍然可以解析。feedback_scores_agg 和 span_feedback_scores_agg CTE
    // 不再被最终 SELECT 引用，CH 会将其裁剪掉；每个 trace 的反馈评分
    // 聚合由 SELECT_FEEDBACK_SCORES_STATS 并行生成，并由 StatsMerger 合并。
    private static final String SELECT_TRACES_SPANS_STATS = """
             WITH spans_data AS (
                SELECT
                    id,
                    trace_id,
                    usage,
                    total_estimated_cost,
                    type,
                    workspace_id,
                    project_id
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
             ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    COUNT(id) as span_count,
                    toInt64(countIf(type = 'llm')) as llm_span_count
                FROM spans_data
                GROUP BY workspace_id, project_id, trace_id
            ), feedback_scores_deduped AS (
                SELECT workspace_id,
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
                    <if(has_legacy_scores)>
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
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                    UNION ALL
                    <endif>
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
                       <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                       <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                       <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
            ), feedback_scores_final AS (
               SELECT
                   workspace_id,
                   project_id,
                   entity_id,
                   name,
                   arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                   IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                   IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                   entries[1].4 AS source,
                   mapFromArrays(
                       arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                       arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                   ) AS value_by_author,
                   arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                   arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                   arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                   arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
               FROM feedback_scores_grouped
            ), feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                            groupArray(name),
                            groupArray(value)
                    ) AS feedback_scores
                FROM feedback_scores_final
                GROUP BY workspace_id, project_id, entity_id
            ),
            guardrails_agg AS (
                SELECT
                    entity_id,
                    countIf(DISTINCT id, result = 'failed') AS failed_count,
                    if(has(groupArray(result), 'failed'), 'failed', 'passed') as guardrails_result
                FROM (
                    SELECT
                        *
                    FROM guardrails
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ), trace_annotation_queue_ids AS (
                 SELECT trace_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as trace_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id IN :project_ids
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
            ),
            span_feedback_scores_deduped AS (
                SELECT workspace_id,
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
                    <if(has_legacy_scores)>
                    SELECT workspace_id,
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
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id IN :project_ids
                      AND entity_id IN (SELECT id FROM spans_data)
                    UNION ALL
                    <endif>
                    SELECT workspace_id,
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
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id IN :project_ids
                      AND entity_id IN (SELECT id FROM spans_data)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), span_feedback_scores_with_trace_id AS (
                SELECT workspace_id,
                       project_id,
                       s.trace_id,
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
                FROM span_feedback_scores_deduped sfs
                INNER JOIN spans_data s ON sfs.entity_id = s.id
            ), span_feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    groupArray(tuple(value, reason, category_name, source, author, created_by, last_updated_by, created_at, last_updated_at, source_queue_id)) AS entries
                FROM span_feedback_scores_with_trace_id
                GROUP BY workspace_id, project_id, trace_id, name
            ), span_feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    arrayStringConcat(arrayMap(e -> e.3, entries), ', ') AS category_name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value,
                    IF(length(entries) = 1, entries[1].2, arrayStringConcat(arrayMap(e -> if(e.2 = '', '\\<no reason>', e.2), entries), ', ')) AS reason,
                    entries[1].4 AS source,
                    mapFromArrays(
                            arrayMap(e -> if(e.10 = '', e.5, concat(e.5, '_', toString(e.10))), entries),
                            arrayMap(e -> tuple(e.1, e.2, e.3, e.4, e.9, '', '', e.10, e.5), entries)
                    ) AS value_by_author,
                    arrayStringConcat(arrayMap(e -> e.6, entries), ', ') AS created_by,
                    arrayStringConcat(arrayMap(e -> e.7, entries), ', ') AS last_updated_by,
                    arrayMin(arrayMap(e -> e.8, entries)) AS created_at,
                    arrayMax(arrayMap(e -> e.9, entries)) AS last_updated_at
                FROM span_feedback_scores_grouped
            ), span_feedback_scores_agg AS (
                SELECT
                    trace_id,
                    mapFromArrays(
                            groupArray(name),
                            groupArray(value)
                    ) AS span_feedback_scores,
                    groupArray(tuple(
                        name,
                        category_name,
                        value,
                        reason,
                        source,
                        value_by_author,
                        created_at,
                        last_updated_at,
                        created_by,
                        last_updated_by
                    )) AS span_feedback_scores_list
                FROM span_feedback_scores_final
                GROUP BY workspace_id, project_id, trace_id
            )
            <if(span_feedback_scores_empty_filters)>
             , sfsc AS (SELECT trace_id, COUNT(trace_id) AS span_feedback_scores_count
                 FROM span_feedback_scores_final
                 GROUP BY trace_id
                 HAVING <span_feedback_scores_empty_filters>
            )
            <endif>
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM feedback_scores_final
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , trace_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    id,
                    thread_id,
                    if(input_length > 0, 1, 0) as input_count,
                    if(output_length > 0, 1, 0) as output_count,
                    if(metadata_length > 0, 1, 0) as metadata_count,
                    length(tags) as tags_length,
                    duration,
                    error_info
                FROM traces final
                <if(guardrails_filters)>
                LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id
                <endif>
                <if(feedback_scores_empty_filters)>
                LEFT JOIN fsc ON fsc.entity_id = traces.id
                <endif>
                <if(span_feedback_scores_empty_filters)>
                LEFT JOIN sfsc ON sfsc.trace_id = traces.id
                <endif>
                <if(annotation_queue_filters || annotation_queue_id)>
                LEFT JOIN trace_annotation_queue_ids as taqi ON taqi.trace_id = traces.id
                <endif>
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                <if(uuid_from_time)>AND id >= :uuid_from_time
                AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'))<endif>
                <if(uuid_to_time)>AND id \\<= :uuid_to_time
                AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'))<endif>
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
                <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
                <if(annotation_queue_id)> AND has(taqi.annotation_queue_ids, :annotation_queue_id) <endif>
                <if(feedback_scores_filters)>
                AND id IN (
                    SELECT entity_id
                    FROM feedback_scores_final
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(span_feedback_scores_filters)>
                AND id IN (
                    SELECT
                        trace_id
                    FROM span_feedback_scores_final
                    GROUP BY trace_id
                    HAVING <span_feedback_scores_filters>
                )
                <endif>
                <if(trace_aggregation_filters)>
                AND id IN (
                    SELECT
                        trace_id
                    FROM spans_agg
                    WHERE <trace_aggregation_filters>
                )
                <endif>
                <if(experiment_filters)>
                AND id IN (
                    SELECT
                        trace_id
                    FROM experiment_items
                    WHERE workspace_id = :workspace_id
                    AND <experiment_filters>
                    ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND fsc.feedback_scores_count = 0
                <endif>
                <if(span_feedback_scores_empty_filters)>
                AND (
                    id IN (SELECT trace_id FROM sfsc WHERE sfsc.span_feedback_scores_count = 0)
                        OR
                    id NOT IN (SELECT trace_id FROM sfsc)
                )
                <endif>
            )
            SELECT
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                countDistinct(t.id) AS trace_count,
                countDistinctIf(t.thread_id, t.thread_id != '') AS thread_count,
                arrayMap(
                  v -> toDecimal64(
                         greatest(
                           least(if(isFinite(v), v, 0),  999999999.999999999),
                           -999999999.999999999
                         ),
                         9
                       ),
                  quantiles(0.5, 0.9, 0.99)(t.duration)
                ) AS duration,
                sum(input_count) AS input,
                sum(output_count) AS output,
                sum(metadata_count) AS metadata,
                avg(tags_length) AS tags,
                avgMap(s.usage) as usage,
                sumMap(s.usage) as usage_sum,
                avg(s.llm_span_count) AS llm_span_count_avg,
                avg(s.span_count) AS span_count_avg,
                avgIf(s.total_estimated_cost, s.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                sumIf(s.total_estimated_cost, s.total_estimated_cost > 0) AS total_estimated_cost_sum_,
                toDecimal128(total_estimated_cost_sum_, 12) AS total_estimated_cost_sum,
                sum(g.failed_count) AS guardrails_failed_count,
                <if(project_stats)>
                countIf(t.error_info != '' AND toDateTime(UUIDv7ToDateTime(toUUID(t.id))) BETWEEN toStartOfDay(subtractDays(now(), 7)) AND now64(9)) AS recent_error_count,
                countIf(t.error_info != '' AND toDateTime(UUIDv7ToDateTime(toUUID(t.id))) \\< toStartOfDay(subtractDays(now(), 7))) AS past_period_error_count
                <else>
                countIf(t.error_info, t.error_info != '') AS error_count
                <endif>
            FROM trace_final t
            LEFT JOIN spans_agg AS s ON t.id = s.trace_id
            LEFT JOIN guardrails_agg as g ON t.id = g.entity_id
            GROUP BY t.workspace_id, t.project_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    // 拆分 B：每个项目的反馈评分和 span 反馈评分聚合。
    private static final String SELECT_FEEDBACK_SCORES_STATS = """
            <if(filters_present)>
            WITH spans_data AS (
                SELECT
                    id,
                    trace_id,
                    usage,
                    total_estimated_cost,
                    type,
                    workspace_id,
                    project_id
                FROM spans FINAL
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
            ), spans_agg AS (
                SELECT
                    trace_id,
                    sumMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost,
                    COUNT(id) as span_count,
                    toInt64(countIf(type = 'llm')) as llm_span_count
                FROM spans_data
                GROUP BY workspace_id, project_id, trace_id
            ), feedback_scores_deduped AS (
                SELECT workspace_id,
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
                    <if(has_legacy_scores)>
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
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                    UNION ALL
                    <endif>
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
                       <if(annotation_queue_id)>AND source_queue_id = :annotation_queue_id<endif>
                       <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                       <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
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
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value
                FROM feedback_scores_grouped
            ),
            guardrails_agg AS (
                SELECT
                    entity_id,
                    countIf(DISTINCT id, result = 'failed') AS failed_count,
                    if(has(groupArray(result), 'failed'), 'failed', 'passed') as guardrails_result
                FROM (
                    SELECT
                        *
                    FROM guardrails
                    WHERE entity_type = 'trace'
                    AND workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                    <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                    ORDER BY (workspace_id, project_id, entity_type, entity_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, id
                )
                GROUP BY workspace_id, project_id, entity_type, entity_id
            ), trace_annotation_queue_ids AS (
                 SELECT trace_id,
                        groupArray(id) AS annotation_queue_ids
                 FROM (
                    SELECT DISTINCT aq.id as id, aqi.item_id as trace_id
                    FROM annotation_queue_items aqi
                    JOIN annotation_queues aq ON aq.id = aqi.queue_id
                    WHERE aq.scope = 'trace'
                      AND workspace_id = :workspace_id
                      AND project_id IN :project_ids
                      <if(uuid_from_time)> AND aqi.item_id >= :uuid_from_time <endif>
                      <if(uuid_to_time)> AND aqi.item_id \\<= :uuid_to_time <endif>
                 ) AS annotation_queue_ids_with_trace_id
                 GROUP BY trace_id
            ),
            span_feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       source_queue_id
                FROM (
                    <if(has_legacy_scores)>
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           feedback_scores.last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id IN :project_ids
                      AND entity_id IN (SELECT id FROM spans_data)
                    UNION ALL
                    <endif>
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           author,
                           source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND project_id IN :project_ids
                      AND entity_id IN (SELECT id FROM spans_data)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), span_feedback_scores_with_trace_id AS (
                SELECT workspace_id,
                       project_id,
                       s.trace_id AS trace_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       source_queue_id
                FROM span_feedback_scores_deduped sfs
                INNER JOIN spans_data s ON sfs.entity_id = s.id
            ), span_feedback_scores_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    groupArray(tuple(value, author, last_updated_at)) AS entries
                FROM span_feedback_scores_with_trace_id
                GROUP BY workspace_id, project_id, trace_id, name
            ), span_feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    trace_id,
                    name,
                    IF(length(entries) = 1, entries[1].1, toDecimal64(arrayAvg(arrayMap(e -> e.1, entries)), 9)) AS value
                FROM span_feedback_scores_grouped
            )
            <if(span_feedback_scores_empty_filters)>
            , sfsc AS (SELECT trace_id, COUNT(trace_id) AS span_feedback_scores_count
                 FROM span_feedback_scores_final
                 GROUP BY trace_id
                 HAVING <span_feedback_scores_empty_filters>
            )
            <endif>
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM feedback_scores_final
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            , trace_final AS (
                SELECT id, project_id
                FROM traces <if(!dedup_by_argmax)>final<endif>
                <if(guardrails_filters)>
                LEFT JOIN guardrails_agg gagg ON gagg.entity_id = traces.id
                <endif>
                <if(feedback_scores_empty_filters)>
                LEFT JOIN fsc ON fsc.entity_id = traces.id
                <endif>
                <if(span_feedback_scores_empty_filters)>
                LEFT JOIN sfsc ON sfsc.trace_id = traces.id
                <endif>
                <if(annotation_queue_filters || annotation_queue_id)>
                LEFT JOIN trace_annotation_queue_ids as taqi ON taqi.trace_id = traces.id
                <endif>
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                <if(uuid_from_time)>AND id >= :uuid_from_time
                AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'))<endif>
                <if(uuid_to_time)>AND id \\<= :uuid_to_time
                AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'))<endif>
                <if(!dedup_by_argmax)>
                <if(filters)> AND <filters> <endif>
                <if(search_text)> AND <search_text> <endif>
                <endif>
                <if(dedup_by_argmax && filters)>
                AND id IN (
                    SELECT id
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND project_id IN :project_ids
                    <if(uuid_from_time)>AND id >= :uuid_from_time<endif>
                    <if(uuid_to_time)>AND id \\<= :uuid_to_time<endif>
                    AND <filters>
                )
                <endif>
                <if(annotation_queue_filters)> AND <annotation_queue_filters> <endif>
                <if(annotation_queue_id)> AND has(taqi.annotation_queue_ids, :annotation_queue_id) <endif>
                <if(feedback_scores_filters)>
                AND id IN (
                    SELECT entity_id
                    FROM feedback_scores_final
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(span_feedback_scores_filters)>
                AND id IN (
                    SELECT
                        trace_id
                    FROM span_feedback_scores_final
                    GROUP BY trace_id
                    HAVING <span_feedback_scores_filters>
                )
                <endif>
                <if(trace_aggregation_filters)>
                AND id IN (
                    SELECT
                        trace_id
                    FROM spans_agg
                    WHERE <trace_aggregation_filters>
                )
                <endif>
                <if(experiment_filters)>
                AND id IN (
                    SELECT
                        trace_id
                    FROM experiment_items
                    WHERE workspace_id = :workspace_id
                    AND <experiment_filters>
                    ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND fsc.feedback_scores_count = 0
                <endif>
                <if(span_feedback_scores_empty_filters)>
                AND (
                    id IN (SELECT trace_id FROM sfsc WHERE sfsc.span_feedback_scores_count = 0)
                        OR
                    id NOT IN (SELECT trace_id FROM sfsc)
                )
                <endif>
                <if(dedup_by_argmax)>
                GROUP BY workspace_id, project_id, id
                <if(filters || search_text)>
                HAVING argMax(<if(filters)>(<filters>)<endif><if(filters && search_text)> AND <endif><if(search_text)>(<search_text>)<endif>, last_updated_at)
                <endif>
                <endif>
            ),
            <else>
            WITH
            <endif>
            span_scores AS (
                <if(has_legacy_scores)>
                SELECT project_id, entity_id, name, value,
                       feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'span'
                  AND workspace_id = :workspace_id
                  AND project_id IN :project_ids
                UNION ALL
                <endif>
                SELECT project_id, entity_id, name, value, author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'span'
                  AND workspace_id = :workspace_id
                  AND project_id IN :project_ids
            ), scored_span_ids AS (
                SELECT id
                FROM spans
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
                AND id IN (SELECT entity_id FROM span_scores)
                <if(uuid_from_time)> AND trace_id >= :uuid_from_time <endif>
                <if(uuid_to_time)> AND trace_id \\<= :uuid_to_time <endif>
                <if(filters_present)> AND trace_id IN (SELECT id FROM trace_final) <endif>
            ), trace_fs AS (
                <if(has_legacy_scores)>
                SELECT project_id, entity_id, name, value,
                       feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  AND project_id IN :project_ids
                  <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                  <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                  <if(filters_present)> AND entity_id IN (SELECT id FROM trace_final) <endif>
                UNION ALL
                <endif>
                SELECT project_id, entity_id, name, value, author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'trace'
                  AND workspace_id = :workspace_id
                  AND project_id IN :project_ids
                  <if(uuid_from_time)> AND entity_id >= :uuid_from_time <endif>
                  <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time <endif>
                  <if(filters_present)> AND entity_id IN (SELECT id FROM trace_final) <endif>
            ), trace_fs_per_name AS (
                SELECT project_id, entity_id, name, avg(value) AS value
                FROM trace_fs
                GROUP BY project_id, entity_id, name
            ), trace_fs_per_project AS (
                SELECT project_id, name, avg(value) AS value
                FROM trace_fs_per_name
                GROUP BY project_id, name
            ), trace_feedback_scores AS (
                SELECT project_id,
                       mapFromArrays(groupArray(name), groupArray(value)) AS feedback_scores
                FROM trace_fs_per_project
                GROUP BY project_id
            ), span_fs AS (
                SELECT project_id, entity_id, name, value, author
                FROM span_scores
                WHERE entity_id IN (SELECT id FROM scored_span_ids)
            ), span_fs_per_name AS (
                SELECT project_id, entity_id, name, avg(value) AS value
                FROM span_fs
                GROUP BY project_id, entity_id, name
            ), span_fs_per_project AS (
                SELECT project_id, name, avg(value) AS value
                FROM span_fs_per_name
                GROUP BY project_id, name
            ), span_feedback_scores AS (
                SELECT project_id,
                       mapFromArrays(groupArray(name), groupArray(value)) AS span_feedback_scores
                FROM span_fs_per_project
                GROUP BY project_id
            )
            SELECT
                coalesce(t.project_id, s.project_id) AS project_id,
                t.feedback_scores AS feedback_scores,
                s.span_feedback_scores AS span_feedback_scores
            FROM trace_feedback_scores t
            FULL OUTER JOIN span_feedback_scores s ON t.project_id = s.project_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_TRACE_IDS_BY_THREAD_IDS = """
            SELECT DISTINCT id
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND thread_id IN :thread_ids
            SETTINGS log_comment = '<log_comment>'
            """;

    public static final String SELECT_COUNT_TRACES_BY_PROJECT_IDS = """
            SELECT
                count(distinct id) as count
            FROM traces
            WHERE workspace_id = :workspace_id
            AND project_id IN :project_ids
            SETTINGS log_comment = '<log_comment>'
            """;

    private static final String SELECT_MINIMAL_THREAD_INFO_BY_IDS = """
            SELECT
                t.id as id,
                if(LENGTH(CAST(tt.id AS Nullable(String))) > 0, tt.id, '') as thread_model_id,
                t.workspace_id as workspace_id,
                t.project_id as project_id,
                t.created_by as created_by,
                t.created_at as created_at,
                tt.status as status
            FROM (
                SELECT
                    inner_t.thread_id as id,
                    inner_t.project_id as project_id,
                    inner_t.workspace_id as workspace_id,
                    argMin(inner_t.created_by, inner_t.created_at)  as created_by,
                    min(inner_t.created_at) as created_at
                FROM (
                    SELECT
                        thread_id,
                        workspace_id,
                        project_id,
                        created_by,
                        created_at
                    FROM traces
                    WHERE workspace_id = :workspace_id
                      AND project_id = :project_id
                      AND thread_id IN :thread_ids
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) inner_t
                GROUP BY inner_t.workspace_id, inner_t.project_id, inner_t.thread_id
            ) t
            LEFT JOIN (
                SELECT workspace_id, project_id, thread_id, id, status
                FROM trace_threads
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND thread_id IN :thread_ids
            ) tt ON t.workspace_id = tt.workspace_id
              AND t.project_id = tt.project_id
              AND t.id = tt.thread_id
            SETTINGS log_comment = '<log_comment>'
            """;

    private static final String BULK_UPDATE = """
            INSERT INTO traces (
                id,
                project_id,
                workspace_id,
                name,
                start_time,
                end_time,
                input,
                output,
                metadata,
                tags,
                error_info,
                created_at,
                created_by,
                last_updated_by,
                thread_id,
                visibility_mode,
                truncation_threshold,
                input_slim,
                output_slim,
                ttft,
                source,
                environment
            )
            SELECT
                t.id,
                t.project_id,
                t.workspace_id,
                <if(name)> :name <else> t.name <endif> as name,
                t.start_time,
                <if(end_time)> parseDateTime64BestEffort(:end_time, 9) <else> t.end_time <endif> as end_time,
                <if(input)> :input <else> t.input <endif> as input,
                <if(output)> :output <else> t.output <endif> as output,
                <if(metadata)> :metadata <else> t.metadata <endif> as metadata,
                """ + TagOperations.tagUpdateFragment("t.tags") + """
                as tags,
                <if(error_info)> :error_info <else> t.error_info <endif> as error_info,
                t.created_at,
                t.created_by,
                :user_name as last_updated_by,
                <if(thread_id)> :thread_id <else> t.thread_id <endif> as thread_id,
                t.visibility_mode,
                :truncation_threshold as truncation_threshold,
                <if(input)> :input_slim <else> t.input_slim <endif> as input_slim,
                <if(output)> :output_slim <else> t.output_slim <endif> as output_slim,
                <if(ttft)> :ttft <else> t.ttft <endif> as ttft,
                t.source,
                <if(environment)> :environment <else> t.environment <endif> as environment
            FROM traces t
            WHERE t.id IN :ids AND t.workspace_id = :workspace_id
            ORDER BY (t.workspace_id, t.project_id, t.id) DESC, t.last_updated_at DESC
            LIMIT 1 BY t.id
            SETTINGS log_comment = '<log_comment>', short_circuit_function_evaluation = 'force_enable';
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull TraceSortingFactory sortingFactory;
    private final @NonNull OpikConfiguration configuration;
    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull WorkspacesService workspacesService;
    private final @NonNull InstantToUUIDMapper instantToUUIDMapper;

    /**
     * 在 {@code traceColumnsNonNullable} 下应用的排序映射：{@code nullIf} 将缺失的（epoch）
     * {@code end_time} 恢复为 {@code NULL}，使其像 Nullable 列一样在 ASC 中排在最后。{@code duration} 无需
     * 条目——ClickHouse 会像 {@code NULL} 一样对 {@code NaN} 排序。与列表传入的 experiment-id 映射合并。
     */
    private static final Map<String, String> SORT_FIELD_MAPPING_END_TIME_SENTINEL = Stream
            .concat(TraceSortingFactory.EXPERIMENT_FIELD_MAPPING.entrySet().stream(),
                    Stream.of(Map.entry(SortableFields.END_TIME,
                            "nullIf(end_time, toDateTime64('1970-01-01 00:00:00.000', 9))")))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    @Override
    @WithSpan
    public Mono<UUID> insert(@NonNull Trace trace, @NonNull Connection connection) {

        return makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(INSERT, "insert_trace", workspaceId, userName, "");

            Statement statement = buildInsertStatement(trace, connection, template);
            bindUserNameAndWorkspace(statement, userName, workspaceId);

            Segment segment = startSegment("traces", "Clickhouse", "insert");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .thenReturn(trace.id());
        });

    }

    private Statement buildInsertStatement(Trace trace, Connection connection, ST template) {
        Statement statement = connection.createStatement(template.render())
                .bind("id", trace.id())
                .bind("project_id", trace.projectId())
                .bind("name", StringUtils.defaultIfBlank(trace.name(), ""))
                .bind("start_time", trace.startTime().toString())
                .bind("thread_id", StringUtils.defaultIfBlank(trace.threadId(), ""));

        bindInputOutputMetadataAndSlim(statement, trace, null);

        bindEpochSentinel(statement, "end_time", trace.endTime());

        if (trace.tags() != null) {
            statement.bind("tags", trace.tags().toArray(String[]::new));
        } else {
            statement.bind("tags", new String[]{});
        }

        if (trace.errorInfo() != null) {
            statement.bind("error_info", JsonUtils.readTree(trace.errorInfo()).toString());
        } else {
            statement.bind("error_info", "");
        }

        if (trace.visibilityMode() != null) {
            statement.bind("visibility_mode", trace.visibilityMode().getValue());
        } else {
            statement.bindNull("visibility_mode", String.class);
        }

        if (trace.source() != null) {
            statement.bind("source", trace.source().getValue());
        } else {
            statement.bindNull("source", String.class);
        }

        statement.bind("environment", StringUtils.defaultString(trace.environment()));

        TruncationUtils.bindTruncationThreshold(statement, "truncation_threshold", configuration);

        bindNanSentinel(statement, "ttft", trace.ttft());

        return statement;
    }

    /**
     * 绑定一个 {@code DateTime64} 写入参数，在列变为非空后对缺失值应用 epoch 哨兵值
     * （绑定 {@code null} 会被拒绝）；在列仍为 Nullable 时，缺失值绑定 {@code null}。
     */
    private void bindEpochSentinel(Statement statement, String parameter, Instant value) {
        if (traceColumnsNonNullable()) {
            statement.bind(parameter, ClickHouseDateTimeFormat.formatNanos(nullToEpoch(value)));
        } else if (value != null) {
            statement.bind(parameter, ClickHouseDateTimeFormat.formatNanos(value));
        } else {
            statement.bindNull(parameter, String.class);
        }
    }

    /**
     * 绑定一个 {@code Float64} 写入参数，在列变为非空后对缺失值应用 {@code NaN} 哨兵值；
     * 在列仍为 Nullable 时，缺失值绑定 {@code null}。
     */
    private void bindNanSentinel(Statement statement, String parameter, Double value) {
        if (traceColumnsNonNullable()) {
            statement.bind(parameter, nullToNaN(value));
        } else if (value != null) {
            statement.bind(parameter, value);
        } else {
            statement.bindNull(parameter, Double.class);
        }
    }

    private boolean traceColumnsNonNullable() {
        return configuration.getDatabaseAnalyticsDataModel().traceColumnsNonNullable();
    }

    /**
     * 分片就绪包装是否生效。生效时，{@code traces} 是一张会
     * 拒绝 mutation 的 {@code Distributed} 表（code 36 / 48），因此每个 trace <b>mutation</b>（{@code DELETE} /
     * {@code ALTER} / {@code OPTIMIZE}）都必须改为针对 {@code traces_local} 分片；关闭时 {@code traces} 仍是
     * 一张可直接删除的 {@code MergeTree}。读取和插入始终经由 {@code traces}。每条
     * mutation 查询都在 {@code <if(distributed_wrap)>traces_local<else>traces<endif>}
     * 分支中携带两个表名并传入此标志；新的 mutation 路径也必须这样做。Liquibase 迁移按类型拆分：
     * {@code DELETE} / {@code MATERIALIZE COLUMN} / {@code ADD INDEX} / {@code MODIFY TTL} 仅针对 {@code traces_local}
     * （Distributed {@code traces} 会拒绝它们），但 {@code ADD}/{@code DROP}/{@code MODIFY COLUMN} 必须同时针对
     * <b>两个</b>表 {@code traces_local} 和 {@code traces}——包装器将它们视为仅元数据操作，跳过它
     * 会导致读取无法看到该列（code 47）。
     * <p>
     * 目前集群是单分片的，且 {@code traces_local} 是一张 {@code ReplicatedMergeTree}，因此轻量
     * 删除会通过复制日志广播到每个副本并到达每一行匹配的数据——无需 {@code ON CLUSTER}
     * （没有 DAO 使用它）。启用分片是一项单独的、延后的工作，有自己的再平衡切换；只有
     * 到那时，在单个分片上发出的删除才会漏掉其他分片上的行，trace mutation 才需要跨分片广播。
     * 这属于启用分片的工作：现在加 {@code ON CLUSTER} 不会是空操作（它会在
     * 每个副本节点上运行 mutation，并在某个节点宕机时卡住），却没有任何单分片收益。
     */
    private boolean tracesDistributedWrapEnabled() {
        return configuration.getDatabaseAnalyticsDataModel().tracesDistributedWrapEnabled();
    }

    /**
     * 通过在该包装生效时添加 {@code distributed_wrap} 属性，在 mutation 模板上选择
     * {@code <if(distributed_wrap)>traces_local<else>traces<endif>} 分支。该标志的判定只存在于这里和
     * {@link #tracesDistributedWrapEnabled()}；每个基于 ST 的 trace mutation 都通过此方法路由其表，因此
     * 分支不会产生偏差。
     */
    private void selectTracesMutationTable(ST template) {
        if (tracesDistributedWrapEnabled()) {
            template.add("distributed_wrap", true);
        }
    }

    /**
     * 将 input、output、metadata 及其精简版本（input_slim、output_slim）绑定到语句。
     * 集中 JSON 转换和绑定逻辑，确保单条插入和批量插入的一致性。
     *
     * @param statement  要绑定的语句
     * @param trace      包含值的 trace
     * @param index      批量操作的可选索引后缀（如 0、1、2）；单条插入传 null
     */
    private void bindInputOutputMetadataAndSlim(Statement statement, Trace trace, Integer index) {
        String suffix = index != null ? String.valueOf(index) : "";

        String inputValue = TruncationUtils.toJsonString(trace.input());
        String outputValue = TruncationUtils.toJsonString(trace.output());
        String metadataValue = TruncationUtils.toJsonString(trace.metadata());

        statement.bind("input" + suffix, inputValue)
                .bind("output" + suffix, outputValue)
                .bind("metadata" + suffix, metadataValue)
                .bind("input_slim" + suffix, TruncationUtils.createSlimJsonString(inputValue))
                .bind("output_slim" + suffix, TruncationUtils.createSlimJsonString(outputValue));
    }

    @Override
    @WithSpan
    public Mono<Void> update(@NonNull TraceUpdate traceUpdate, @NonNull UUID id, @NonNull Connection connection) {
        return update(id, traceUpdate, connection).then();
    }

    private Mono<? extends Result> update(UUID id, TraceUpdate traceUpdate, Connection connection) {

        return makeMonoContextAware((userName, workspaceId) -> {
            var template = buildUpdateTemplate(traceUpdate, UPDATE, "update_trace", workspaceId, userName);

            String sql = template.render();

            Statement statement = createUpdateStatement(id, traceUpdate, connection, sql);
            bindUserNameAndWorkspace(statement, userName, workspaceId);

            Segment segment = startSegment("traces", "Clickhouse", "update");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private Statement createUpdateStatement(UUID id, TraceUpdate traceUpdate, Connection connection, String sql) {
        Statement statement = connection.createStatement(sql);

        bindUpdateParams(traceUpdate, statement);

        statement.bind("id", id);
        return statement;
    }

    private void bindUpdateParams(TraceUpdate traceUpdate, Statement statement) {
        if (StringUtils.isNotBlank(traceUpdate.name())) {
            statement.bind("name", traceUpdate.name());
        }

        Optional.ofNullable(traceUpdate.input())
                .ifPresent(input -> {
                    String inputValue = input.toString();
                    statement.bind("input", inputValue);
                    statement.bind("input_slim", TruncationUtils.createSlimJsonString(inputValue));
                });

        Optional.ofNullable(traceUpdate.output())
                .ifPresent(output -> {
                    String outputValue = output.toString();
                    statement.bind("output", outputValue);
                    statement.bind("output_slim", TruncationUtils.createSlimJsonString(outputValue));
                });

        Optional.ofNullable(traceUpdate.tags())
                .ifPresent(tags -> statement.bind("tags", tags.toArray(String[]::new)));

        Optional.ofNullable(traceUpdate.metadata())
                .ifPresent(metadata -> statement.bind("metadata", metadata.toString()));

        Optional.ofNullable(traceUpdate.errorInfo())
                .ifPresent(errorInfo -> statement.bind("error_info", JsonUtils.readTree(errorInfo).toString()));

        Optional.ofNullable(traceUpdate.endTime())
                .ifPresent(endTime -> statement.bind("end_time", endTime.toString()));

        if (StringUtils.isNotBlank(traceUpdate.threadId())) {
            statement.bind("thread_id", traceUpdate.threadId());
        }

        Optional.ofNullable(traceUpdate.ttft())
                .ifPresent(ttft -> statement.bind("ttft", ttft));

        Optional.ofNullable(traceUpdate.source())
                .ifPresent(source -> statement.bind("source", source.getValue()));

        Optional.ofNullable(traceUpdate.environment())
                .ifPresent(environment -> statement.bind("environment", environment));

        TruncationUtils.bindTruncationThreshold(statement, "truncation_threshold", configuration);
    }

    private ST buildUpdateTemplate(TraceUpdate traceUpdate, String update, String queryName, String workspaceId,
            String userName) {
        var template = getSTWithLogComment(update, queryName, workspaceId, userName, "");

        if (StringUtils.isNotBlank(traceUpdate.name())) {
            template.add("name", traceUpdate.name());
        }

        Optional.ofNullable(traceUpdate.input())
                .ifPresent(input -> template.add("input", input.toString()));

        Optional.ofNullable(traceUpdate.output())
                .ifPresent(output -> template.add("output", output.toString()));

        Optional.ofNullable(traceUpdate.tags())
                .ifPresent(tags -> template.add("tags", tags.toString()));

        Optional.ofNullable(traceUpdate.metadata())
                .ifPresent(metadata -> template.add("metadata", metadata.toString()));

        Optional.ofNullable(traceUpdate.endTime())
                .ifPresent(endTime -> template.add("end_time", endTime.toString()));

        Optional.ofNullable(traceUpdate.errorInfo())
                .ifPresent(errorInfo -> template.add("error_info", JsonUtils.readTree(errorInfo).toString()));

        if (StringUtils.isNotBlank(traceUpdate.threadId())) {
            template.add("thread_id", traceUpdate.threadId());
        }

        Optional.ofNullable(traceUpdate.ttft())
                .ifPresent(ttft -> template.add("ttft", ttft));

        Optional.ofNullable(traceUpdate.source())
                .ifPresent(source -> template.add("source", source.getValue()));

        Optional.ofNullable(traceUpdate.environment())
                .ifPresent(environment -> template.add("environment", environment));

        return template;
    }

    private Flux<? extends Result> getDetailsById(UUID id, Connection connection) {
        var template = getSTWithLogComment(SELECT_DETAILS_BY_ID, "get_trace_details_by_id", "", "", "");

        var statement = connection.createStatement(template.render())
                .bind("id", id);

        Segment segment = startSegment("traces", "Clickhouse", "getDetailsById");

        return Flux.from(statement.execute())
                .doFinally(signalType -> endSegment(segment));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(Set<Pair<UUID, UUID>> projectIdTraceIdPairs, @NonNull Connection connection) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(projectIdTraceIdPairs),
                "Argument 'projectIdTraceIdPairs' must not be empty");
        log.info("按 (project_id, id) 对删除 traces，数量 '{}'", projectIdTraceIdPairs.size());

        return makeMonoContextAware((userName, workspaceId) -> Flux
                .fromIterable(Lists.partition(List.copyOf(projectIdTraceIdPairs), ANALYTICS_DELETE_BATCH_SIZE))
                .concatMap(batch -> {
                    var template = getSTWithLogComment(DELETE_BY_PROJECT_ID_TRACE_ID_PAIRS, "delete_traces",
                            workspaceId,
                            userName, "pairs_size=%s".formatted(batch.size()));
                    selectTracesMutationTable(template);

                    var projectIds = batch.stream().map(pair -> pair.getLeft().toString()).toArray(String[]::new);
                    var traceIds = batch.stream().map(pair -> pair.getRight().toString()).toArray(String[]::new);

                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("project_ids", projectIds)
                            .bind("trace_ids", traceIds);

                    var segment = startSegment("traces", "Clickhouse", "delete");
                    return Mono.from(statement.execute())
                            .doFinally(_ -> endSegment(segment))
                            .then();
                })
                .then());
    }

    /**
     * 根据给定的 trace ID 获取目标 project ID。
     * 作为单独查询执行，以减少主查询中 traces 表的扫描次数。
     */
    private Mono<List<UUID>> getTargetProjectIdsForTraces(List<UUID> ids) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.from(connectionFactory.create())
                    .flatMap(connection -> {
                        var template = getSTWithLogComment(SELECT_TARGET_PROJECTS_FOR_TRACES,
                                "get_target_project_ids_for_traces", workspaceId, "", ids.size());

                        var statement = connection.createStatement(template.render())
                                .bind("ids", ids.toArray(UUID[]::new));

                        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
                    })
                    .flatMapMany(result -> result.map((row, metadata) -> row.get("project_id", UUID.class)))
                    .collectList();
        });
    }

    @Override
    @WithSpan
    public Mono<Trace> findById(@NonNull UUID id, @NonNull Connection connection) {
        return findByIds(List.of(id), connection)
                .collectList()
                .flatMap(traces -> Mono.deferContextual(ctx -> Mono.justOrEmpty(
                        firstOrLogFanOut(traces, id, ctx.getOrDefault(RequestContext.WORKSPACE_ID, "unknown")))));
    }

    /**
     * 将单个 trace id 返回的行减少到至多一个 {@link Trace}。按 id 查询
     * 可能会通过其 join CTE 扩展为多行（例如未合并/重复的行）；
     * 返回第一行可以避免严格单元素 reducer 抛出的 {@code IndexOutOfBoundsException}
     * （"Source emitted more than one item"），它表现为 500。空
     * 情况得以保留（-> 404）。扩展情况会被记录日志，使底层的重复保持可见。
     */
    @VisibleForTesting
    static Optional<Trace> firstOrLogFanOut(@NonNull List<Trace> traces, UUID id, String workspaceId) {
        if (traces.size() > 1) {
            log.warn("按 id 查询 trace 解析为多行；返回第一行。workspaceId '{}', traceId '{}'",
                    workspaceId, id);
        }
        return traces.stream().findFirst();
    }

    @Override
    @WithSpan
    public Flux<Trace> findByIds(@NonNull List<UUID> ids, @NonNull Connection connection) {
        Preconditions.checkArgument(!ids.isEmpty(), "ids must not be empty");
        log.info("按 IDs 批量查找 traces，数量 '{}'", ids.size());

        return getTargetProjectIdsForTraces(ids)
                .flatMapMany(targetProjectIds -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                    var template = getSTWithLogComment(SELECT_BY_IDS, "find_traces_by_ids", workspaceId, "",
                            ids.size());

                    if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                        template.add("has_target_projects", true);
                    }

                    var statement = connection.createStatement(template.render())
                            .bind("ids", ids.toArray(UUID[]::new));

                    if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                        statement.bind("target_project_ids", targetProjectIds.toArray(UUID[]::new));
                    }

                    Segment segment = startSegment("traces", "Clickhouse", "findByIds");

                    return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                            .doFinally(signalType -> endSegment(segment));
                }))
                .flatMap(result -> mapToDto(result, Set.of()));
    }

    @Override
    public Mono<TraceDetails> getTraceDetailsById(@NonNull UUID id, @NonNull Connection connection) {
        return getDetailsById(id, connection)
                .flatMap(this::mapToTraceDetails)
                .singleOrEmpty();
    }

    /**
     * 从数据库行中检索给定字段的值。
     * <p>
     * 此方法处理结果集中可能不存在某些列的情况。
     * 某些查询（例如带有字段排除的 trace 列表查询）可能不会包含所有可能的列以优化性能。
     * 当列不存在时，此方法返回 null 而不是抛出异常。
     * </p>
     *
     * @param exclude    要排除的字段集合（如果字段在此集合中则返回 null）
     * @param field      要检索的 trace 字段
     * @param row        要读取的数据库行
     * @param fieldName  数据库列名
     * @param clazz      值的期望类型
     * @param <T>        要检索的值类型
     * @return 字段值，如果字段被排除、列不存在或列值为 null 则返回 null
     */
    private <T> T getValue(Set<Trace.TraceField> exclude, Trace.TraceField field, Row row, String fieldName,
            Class<T> clazz) {
        if (exclude.contains(field)) {
            return null;
        }
        // 检查列是否存在于结果集中（某些查询不包含所有列）
        if (!row.getMetadata().contains(fieldName)) {
            return null;
        }
        return row.get(fieldName, clazz);
    }

    private Publisher<Trace> mapToDto(Result result, Set<Trace.TraceField> exclude) {

        return result.map((row, rowMetadata) -> mapRowToTrace(row, rowMetadata, exclude));
    }

    private Trace mapRowToTrace(Row row, RowMetadata rowMetadata, Set<Trace.TraceField> exclude) {
        @SuppressWarnings("unchecked")
        List<String> providers = (List<String>) row.get(Trace.TraceField.PROVIDERS.getValue(), List.class);

        JsonNode metadata = getMetadataWithProviders(row, exclude, providers);

        return Trace.builder()
                .id(row.get("id", UUID.class))
                .projectId(row.get("project_id", UUID.class))
                .name(StringUtils.defaultIfBlank(
                        getValue(exclude, Trace.TraceField.NAME, row, "name", String.class), null))
                .startTime(getValue(exclude, Trace.TraceField.START_TIME, row, "start_time", Instant.class))
                .endTime(readEpochSentinel(exclude, Trace.TraceField.END_TIME, row, "end_time"))
                .input(Optional.ofNullable(getValue(exclude, Trace.TraceField.INPUT, row, "input", String.class))
                        .filter(str -> !str.isBlank())
                        .map(value -> TruncationUtils.getJsonNodeOrTruncatedString(rowMetadata, "input_truncated",
                                row,
                                value))
                        .orElse(null))
                .output(Optional.ofNullable(getValue(exclude, Trace.TraceField.OUTPUT, row, "output", String.class))
                        .filter(str -> !str.isBlank())
                        .map(value -> TruncationUtils.getJsonNodeOrTruncatedString(rowMetadata, "output_truncated",
                                row,
                                value))
                        .orElse(null))
                .metadata(metadata)
                .tags(Optional.ofNullable(getValue(exclude, Trace.TraceField.TAGS, row, "tags", String[].class))
                        .map(tags -> Arrays.stream(tags).collect(toSet()))
                        .filter(set -> !set.isEmpty())
                        .orElse(null))
                .comments(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.COMMENTS, row, "comments", List[].class))
                        .map(CommentResultMapper::getComments)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .feedbackScores(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.FEEDBACK_SCORES, row, "feedback_scores_list",
                                List.class))
                        .filter(not(List::isEmpty))
                        .map(FeedbackScoreMapper::mapFeedbackScores)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .spanFeedbackScores(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.SPAN_FEEDBACK_SCORES, row,
                                "span_feedback_scores_list",
                                List.class))
                        .filter(not(List::isEmpty))
                        .map(FeedbackScoreMapper::mapFeedbackScores)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .guardrailsValidations(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.GUARDRAILS_VALIDATIONS, row,
                                "guardrails_validations", List.class))
                        .map(this::mapGuardrails)
                        .filter(not(List::isEmpty))
                        .orElse(null))
                .spanCount(Optional
                        .ofNullable(
                                getValue(exclude, Trace.TraceField.SPAN_COUNT, row, "span_count", Integer.class))
                        .orElse(0))
                .llmSpanCount(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.LLM_SPAN_COUNT, row, "llm_span_count",
                                Integer.class))
                        .orElse(0))
                .hasToolSpans(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.HAS_TOOL_SPANS, row, "has_tool_spans",
                                Boolean.class))
                        .orElse(false))
                .providers(providers)
                .usage(getValue(exclude, Trace.TraceField.USAGE, row, "usage", Map.class))
                .totalEstimatedCost(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.TOTAL_ESTIMATED_COST, row,
                                "total_estimated_cost", BigDecimal.class))
                        .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                        .orElse(null))
                .errorInfo(Optional
                        .ofNullable(getValue(exclude, Trace.TraceField.ERROR_INFO, row, "error_info", String.class))
                        .filter(str -> !str.isBlank())
                        .map(errorInfo -> JsonUtils.readValue(errorInfo, ERROR_INFO_TYPE))
                        .orElse(null))
                .createdAt(getValue(exclude, Trace.TraceField.CREATED_AT, row, "created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(getValue(exclude, Trace.TraceField.CREATED_BY, row, "created_by", String.class))
                .lastUpdatedBy(
                        getValue(exclude, Trace.TraceField.LAST_UPDATED_BY, row, "last_updated_by", String.class))
                .duration(readNanSentinel(exclude, Trace.TraceField.DURATION, row, "duration"))
                .ttft(readNanSentinel(exclude, Trace.TraceField.TTFT, row, "ttft"))
                .threadId(StringUtils.defaultIfBlank(
                        getValue(exclude, Trace.TraceField.THREAD_ID, row, "thread_id", String.class), null))
                .visibilityMode(Optional.ofNullable(
                        getValue(exclude, Trace.TraceField.VISIBILITY_MODE, row, "visibility_mode", String.class))
                        .flatMap(VisibilityMode::fromString)
                        .orElse(null))
                .source(Optional.ofNullable(
                        getValue(exclude, Trace.TraceField.SOURCE, row, "source", String.class))
                        .flatMap(Source::fromString)
                        .orElse(null))
                .environment(getValue(exclude, Trace.TraceField.ENVIRONMENT, row, "environment", String.class))
                .experiment(mapExperiment(exclude, row))
                .build();
    }

    /**
     * 读取 {@code DateTime64} 列，仅在列变为非空后才将 epoch 哨兵值转换为 {@code null}。
     * 在列仍为 {@code Nullable} 时，值按原样返回：{@code null} 保持 {@code null}，而
     * （合法的）epoch 时间戳得以保留——列能区分二者，因此无条件转换
     * 会破坏客户端传入的 {@code 1970-01-01} 值。与受标志门控的写入绑定对称。
     */
    private Instant readEpochSentinel(Set<Trace.TraceField> exclude, Trace.TraceField field, Row row,
            String fieldName) {
        var value = getValue(exclude, field, row, fieldName, Instant.class);
        return traceColumnsNonNullable() ? epochToNull(value) : value;
    }

    /**
     * 读取 {@code Float64} 列并将 {@code NaN} 哨兵值映射为 {@code null}。无需标志（不同于
     * {@code end_time}）：在列仍为 {@code Nullable} 时，{@code duration}（已物化，目前永不为 {@code NaN}）和
     * {@code ttft}（无法通过 JSON 以 {@code NaN} 到达）都不会是 {@code NaN}，因此
     * 该转换目前始终是空操作，且在列变为非空后是正确的。
     */
    private Double readNanSentinel(Set<Trace.TraceField> exclude, Trace.TraceField field, Row row, String fieldName) {
        return nanToNull(getValue(exclude, field, row, fieldName, Double.class));
    }

    private List<GuardrailsValidation> mapGuardrails(List<List<Object>> guardrails) {
        return GuardrailsMapper.INSTANCE.mapToValidations(Optional.ofNullable(guardrails)
                .orElse(List.of())
                .stream()
                .map(guardrail -> Guardrail.builder()
                        .entityId(UUID.fromString((String) guardrail.get(0)))
                        .secondaryId(UUID.fromString((String) guardrail.get(1)))
                        .projectId(UUID.fromString((String) guardrail.get(2)))
                        .name(GuardrailType.fromString((String) guardrail.get(3)))
                        .result(GuardrailResult.fromString((String) guardrail.get(4)))
                        .config(JsonNodeFactory.instance.objectNode())
                        .details(JsonNodeFactory.instance.objectNode())
                        .build())
                .toList());
    }

    private ExperimentItemReference mapExperiment(Set<Trace.TraceField> exclude, Row row) {
        String experimentIdStr = getValue(exclude, Trace.TraceField.EXPERIMENT, row, "experiment_id", String.class);
        String experimentDatasetIdStr = getValue(exclude, Trace.TraceField.EXPERIMENT, row, "experiment_dataset_id",
                String.class);
        String experimentDatasetItemIdStr = getValue(exclude, Trace.TraceField.EXPERIMENT, row,
                "experiment_dataset_item_id", String.class);

        // 仅检查关键字段 - experimentName 是可编辑的，其缺失不表示数据丢失
        if (StringUtils.isBlank(experimentIdStr) || StringUtils.isBlank(experimentDatasetIdStr)
                || StringUtils.isBlank(experimentDatasetItemIdStr)
                || CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(experimentIdStr)
                || CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(experimentDatasetIdStr)
                || CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE.equals(experimentDatasetItemIdStr)) {
            return null;
        }

        UUID experimentId = UUID.fromString(experimentIdStr);
        UUID experimentDatasetId = UUID.fromString(experimentDatasetIdStr);
        UUID experimentDatasetItemId = UUID.fromString(experimentDatasetItemIdStr);
        String experimentName = getValue(exclude, Trace.TraceField.EXPERIMENT, row, "experiment_name", String.class);

        return ExperimentItemReference.builder()
                .id(experimentId)
                .name(experimentName)
                .datasetId(experimentDatasetId)
                .datasetItemId(experimentDatasetItemId)
                .build();
    }

    private Publisher<TraceDetails> mapToTraceDetails(Result result) {
        return result.map((row, rowMetadata) -> TraceDetails.builder()
                .projectId(row.get("project_id", String.class))
                .workspaceId(row.get("workspace_id", String.class))
                .build());
    }

    @Override
    @WithSpan
    public Mono<TracePage> find(
            int size, int page, @NonNull TraceSearchCriteria traceSearchCriteria, @NonNull Connection connection) {
        return countTotal(traceSearchCriteria, connection)
                .flatMap(result -> Mono.from(result.map((row, rowMetadata) -> row.get("count", Long.class))))
                .flatMap(total -> getTracesByProjectId(size, page, traceSearchCriteria, connection) //先获取总数再分页
                        .flatMapMany(result1 -> mapToDto(result1, traceSearchCriteria.exclude()))
                        .collectList()
                        .map(traces -> new TracePage(page, traces.size(), total, traces,
                                sortingFactory.getSortableFields())))
                .onErrorResume(e -> ErrorUtils.handleMalformedJsonPath(e,
                        TracePage.empty(page, sortingFactory.getSortableFields())));
    }

    @Override
    @WithSpan
    public Mono<Boolean> existsByProjectId(@NonNull TraceSearchCriteria traceSearchCriteria, boolean threadScoped,
            @NonNull Connection connection) {
        return makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(
                    threadScoped ? THREADS_EXISTS_BY_PROJECT_ID : EXISTS_BY_PROJECT_ID,
                    threadScoped ? "exists_threads_by_project_id" : "exists_traces_by_project_id",
                    workspaceId, userName, "");

            var source = traceSearchCriteria.source();
            var sourceLegacy = source == null
                    ? Optional.<String>empty()
                    : Source.legacyFallbackDbValue(source.getValue());
            if (source != null) {
                template.add("source", true);
                if (sourceLegacy.isPresent()) {
                    template.add("source_legacy", true);
                }
            }

            var statement = connection.createStatement(template.render())
                    .bind("project_id", traceSearchCriteria.projectId())
                    .bind("workspace_id", workspaceId);

            if (source != null) {
                statement.bind("source", source.getValue());
                sourceLegacy.ifPresent(legacy -> statement.bind("source_legacy", legacy));
            }

            Segment segment = startSegment("traces", "Clickhouse",
                    threadScoped ? "existsThreadsByProjectId" : "existsByProjectId");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        })
                .flatMap(result -> Mono.from(result.map((row, metadata) -> true)))
                .defaultIfEmpty(false);
    }

    @Override
    @WithSpan
    public Mono<Void> partialInsert(
            @NonNull UUID projectId,
            @NonNull TraceUpdate traceUpdate,
            @NonNull UUID traceId,
            @NonNull Connection connection) {

        return makeMonoContextAware((userName, workspaceId) -> {
            var template = buildUpdateTemplate(traceUpdate, INSERT_UPDATE, "partial_insert_trace", workspaceId,
                    userName);

            var statement = connection.createStatement(template.render());

            statement.bind("id", traceId);
            statement.bind("project_id", projectId);

            bindUserNameAndWorkspace(statement, userName, workspaceId);
            bindUpdateParams(traceUpdate, statement);

            // INSERT_UPDATE 会构建完整的 new_trace 行，因此 end_time/ttft 被无条件引用，必须
            // 始终绑定（缺失值用哨兵值或 null）——不同于条件性的 UPDATE 保留列路径。
            bindEpochSentinel(statement, "end_time", traceUpdate.endTime());
            bindNanSentinel(statement, "ttft", traceUpdate.ttft());

            if (traceUpdate.source() != null) {
                statement.bind("source", traceUpdate.source().getValue());
            } else {
                statement.bindNull("source", String.class);
            }

            Segment segment = startSegment("traces", "Clickhouse", "insert_partial");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .then();
        });
    }

    private boolean hasSpanStatistics(String sortFields) {
        if (sortFields == null) {
            return false;
        }
        return sortFields.contains("usage")
                || sortFields.contains("span_count")
                || sortFields.contains("llm_span_count")
                || sortFields.contains("total_estimated_cost");
    }

    /**
     * 确定是否激活 trace_id_prefilter CTE 以缩小 feedback_scores、comments、spans、
     * guardrails、annotations 和 experiments 的扫描范围。使用 newTraceThreadFindTemplate
     * 已计算的模板属性，避免冗余的 toAnalyticsDbFilters 调用。
     *
     * <p>仅在过滤器能比单独的时间范围提供更多缩小效果时激活：
     * uuidFromTime/uuidToTime 被排除，因为 if/else 回退会将它们直接应用到每个 CTE；
     * lastReceivedId 被排除，因为它是分页游标而非语义过滤器。
     *
     * <p>Guardrails 过滤器会将 {@code gagg.guardrails_result} 注入到 {@code <filters>}
     * 模板变量中，该变量引用 guardrails_agg CTE 别名。由于 prefilter
     * CTE 只查询 traces 表，这些引用会失败。需要防范它们。
     *
     * <p>反馈评分过滤器使用独立的模板变量（{@code feedback_scores_filters}、
     * {@code span_feedback_scores_filters}），并且不会被注入到 {@code <filters>} 中，因此
     * prefilter CTE 可以安全地与它们一起使用。当 trace 列过滤器同时存在时，启用它可大幅减少
     * 反馈评分的扫描量（OPIK-7076）。
     */
    private boolean shouldUseTraceIdPrefilter(TraceSearchCriteria criteria, ST template) {
        boolean hasGuardrailsInFilters = template.getAttribute("guardrails_filters") != null;

        boolean hasNarrowingFilters = criteria.searchText() != null
                || template.getAttribute("filters") != null;

        return !hasGuardrailsInFilters && hasNarrowingFilters;
    }

    /**
     * 判断聚合 CTE（反馈评分、spans、comments、guardrails、annotation queues、
     * experiments）是否可以以 page id 为键，而不是使用完整的过滤后 trace id 集合。
     *
     * <p>这些 CTE 仅为了富化返回行而与 {@code page_wide} 相 join，因此只要过滤
     * 和排序都不读取它们，为每个候选 trace 计算它们就是随项目规模增长的浪费工作：
     * 最终的 LEFT JOIN 会丢弃页外的一切。以 page id 为键可将
     * 全项目扫描（spans、feedback_scores、……）变成页大小、可主键裁剪的查找。
     *
     * <p>page id 通过 {@code IN (SELECT arrayJoin((SELECT groupArray(id) FROM page_ids)))} 消费：
     * 内部标量子查询只求值一次并对整个查询缓存，因此 {@code page_ids} CTE 不会
     * 在每次引用时重新执行（ClickHouse 会内联普通的 CTE 引用），并且物化后的常量
     * 数组可用于主键索引分析。
     *
     * <p>当分页选择依赖某个聚合时必须保持禁用：反馈评分过滤器
     * （{@code traces_deduped} 在 feedback_scores_final/fsc/sfsc 上过滤）、guardrails 过滤器（在
     * guardrails_agg 上 join）、trace 聚合过滤器（spans_agg）、annotation queue 过滤器/id（在
     * trace_annotation_queue_ids 上 join），或按反馈评分、span 统计或实验
     * 排序（{@code page_ids} 会 join 那些聚合）。
     */
    /**
     * 应用由 {@code getTracesByProjectId} 和 {@code findTraceStream} 共享的聚合键决策：
     * 当聚合仅用于富化时以 page 为键，否则在过滤器允许时使用缩小范围的
     * trace id prefilter。
     */
    private void addAggregateKeyingFlags(ST template, TraceSearchCriteria criteria, boolean sortHasFeedbackScores,
            boolean sortHasSpanStatistics, boolean sortHasExperiment) {
        if (shouldPageKeyAggregates(template, sortHasFeedbackScores, sortHasSpanStatistics, sortHasExperiment)) {
            template.add("page_keyed_aggregates", true);
        } else if (shouldUseTraceIdPrefilter(criteria, template) && !sortHasFeedbackScores) {
            template.add("trace_id_prefilter", true);
        }
    }

    private boolean shouldPageKeyAggregates(ST template, boolean sortHasFeedbackScores,
            boolean sortHasSpanStatistics, boolean sortHasExperiment) {
        boolean aggregatesDrivePageSelection = hasFeedbackScoreFilters(template)
                || template.getAttribute("guardrails_filters") != null
                || template.getAttribute("trace_aggregation_filters") != null
                || template.getAttribute("annotation_queue_filters") != null
                || template.getAttribute("annotation_queue_id") != null
                || sortHasFeedbackScores
                || sortHasSpanStatistics
                || sortHasExperiment;
        return !aggregatesDrivePageSelection;
    }

    private Mono<? extends Result> getTracesByProjectId(
            int size, int page, TraceSearchCriteria traceSearchCriteria, Connection connection) {

        int offset = (page - 1) * size;

        return makeMonoContextAware((userName, workspaceId) -> {
            var logComment = getLogComment("find_traces_by_project_id", workspaceId, userName,
                    "page:" + page + ":size:" + size + ":" + traceSearchCriteria.toString());
            var template = newTraceThreadFindTemplate(
                    SELECT_BY_PROJECT_ID, traceSearchCriteria, TRACE_SEARCH_CLAUSE, traceColumnsNonNullable());

            bindTemplateExcludeFieldVariables(traceSearchCriteria, template);

            template.add("offset", offset);
            template.add("log_comment", logComment);

            addSortNeedsWideFlag(template, traceSearchCriteria.sortingFields());

            var orderBySql = sortingQueryBuilder.toOrderBySql(traceSearchCriteria.sortingFields(),
                    traceColumnsNonNullable()
                            ? SORT_FIELD_MAPPING_END_TIME_SENTINEL
                            : TraceSortingFactory.EXPERIMENT_FIELD_MAPPING);
            boolean sortHasFeedbackScores = Optional.ofNullable(orderBySql)
                    .map(sortFields -> sortFields.contains("feedback_scores"))
                    .orElse(false);
            boolean sortHasSpanStatistics = hasSpanStatistics(orderBySql);
            // 对请求的排序字段做结构化检查，而不是对渲染出的 ORDER BY SQL 做子串匹配：
            // 这现在门控着以 page 为键的聚合，因此必须精确跟踪实验排序，
            // 即使 EXPERIMENT_FIELD_MAPPING 改变了该字段渲染出的 SQL。
            boolean sortHasExperiment = Optional.ofNullable(traceSearchCriteria.sortingFields())
                    .map(sortingFields -> sortingFields.stream()
                            .anyMatch(sortingField -> SortableFields.EXPERIMENT_ID.equals(sortingField.field())))
                    .orElse(false);

            addAggregateKeyingFlags(template, traceSearchCriteria, sortHasFeedbackScores, sortHasSpanStatistics,
                    sortHasExperiment);

            var finalTemplate = template;
            Optional.ofNullable(orderBySql)
                    .ifPresent(sortFields -> {
                        if (sortHasFeedbackScores) {
                            finalTemplate.add("sort_has_feedback_scores", true);
                        }

                        if (sortHasSpanStatistics) {
                            finalTemplate.add("sort_has_span_statistics", true);
                        }

                        if (sortHasExperiment) {
                            finalTemplate.add("sort_has_experiment", true);
                        }

                        finalTemplate.add("sort_fields", sortFields);
                    });

            var hasDynamicKeys = sortingQueryBuilder.hasDynamicKeys(traceSearchCriteria.sortingFields());

            template = ImageUtils.addTruncateToTemplate(template, traceSearchCriteria.truncate());

            var statement = connection.createStatement(template.render())
                    .bind("project_id", traceSearchCriteria.projectId())
                    .bind("workspace_id", workspaceId)
                    .bind("limit", size)
                    .bind("offset", offset);

            if (hasDynamicKeys) {
                statement = sortingQueryBuilder.bindDynamicKeys(statement, traceSearchCriteria.sortingFields());
            }

            bindTraceThreadSearchCriteria(traceSearchCriteria, statement);

            Segment segment = startSegment("traces", "Clickhouse", "find");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private void bindTemplateExcludeFieldVariables(TraceSearchCriteria traceSearchCriteria, ST template) {
        Optional.ofNullable(traceSearchCriteria.exclude())
                .filter(Predicate.not(Set::isEmpty))
                .ifPresent(exclude -> {

                    // 需要在 select 子句中保留用于排序的列，以便在应用排序时可用。
                    Set<String> sortingFields = Optional.ofNullable(traceSearchCriteria.sortingFields())
                            .stream()
                            .flatMap(List::stream)
                            .map(SortingField::field)
                            .collect(toSet());

                    Set<String> fields = exclude.stream()
                            .map(Trace.TraceField::getValue)
                            .filter(field -> !sortingFields.contains(field))
                            .collect(toSet());

                    // 同时检查 feedback_scores，因为这是一个特殊情况：
                    // 当按反馈评分排序或过滤时跳过排除，
                    // 因为这些操作需要反馈评分 CTE
                    if (fields.contains(Trace.TraceField.FEEDBACK_SCORES.getValue())
                            && sortingFields.stream().noneMatch(this::isFeedBackScoresField)
                            && !hasFeedbackScoreFilters(template)) {

                        template.add("exclude_feedback_scores", true);
                    }

                    if (!fields.isEmpty()) {
                        template.add("exclude_fields", String.join(", ", fields));
                        template.add("exclude_input", fields.contains(Trace.TraceField.INPUT.getValue()));
                        template.add("exclude_output", fields.contains(Trace.TraceField.OUTPUT.getValue()));
                        template.add("exclude_metadata", fields.contains(Trace.TraceField.METADATA.getValue()));
                        template.add("exclude_comments", fields.contains(Trace.TraceField.COMMENTS.getValue()));

                        template.add("exclude_usage", fields.contains(Trace.TraceField.USAGE.getValue()));
                        template.add("exclude_total_estimated_cost",
                                fields.contains(Trace.TraceField.TOTAL_ESTIMATED_COST.getValue()));
                        template.add("exclude_guardrails_validations",
                                fields.contains(Trace.TraceField.GUARDRAILS_VALIDATIONS.getValue()));
                        template.add("exclude_span_count", fields.contains(Trace.TraceField.SPAN_COUNT.getValue()));
                        template.add("exclude_llm_span_count",
                                fields.contains(Trace.TraceField.LLM_SPAN_COUNT.getValue()));
                        template.add("exclude_has_tool_spans",
                                fields.contains(Trace.TraceField.HAS_TOOL_SPANS.getValue()));
                        template.add("exclude_experiment",
                                fields.contains(Trace.TraceField.EXPERIMENT.getValue()));
                    }
                });
    }

    private boolean isFeedBackScoresField(String field) {
        return field
                .startsWith(SortableFields.FEEDBACK_SCORES.substring(0, SortableFields.FEEDBACK_SCORES.length() - 1));
    }

    private boolean hasFeedbackScoreFilters(ST template) {
        return template.getAttribute("feedback_scores_filters") != null
                || template.getAttribute("feedback_scores_empty_filters") != null
                || template.getAttribute("span_feedback_scores_filters") != null
                || template.getAttribute("span_feedback_scores_empty_filters") != null;
    }

    private Mono<? extends Result> countTotal(TraceSearchCriteria traceSearchCriteria, Connection connection) {
        return makeMonoContextAware((userName, workspaceId) -> {
            var logComment = getLogComment("count_traces_by_project", workspaceId, userName,
                    traceSearchCriteria.toString());
            var template = newTraceThreadFindTemplate(
                    COUNT_BY_PROJECT_ID, traceSearchCriteria, TRACE_SEARCH_CLAUSE, traceColumnsNonNullable());
            template.add("log_comment", logComment);

            if (shouldUseTraceIdPrefilter(traceSearchCriteria, template)) {
                template.add("trace_id_prefilter", true);
            }

            var statement = connection.createStatement(template.render())
                    .bind("project_id", traceSearchCriteria.projectId())
                    .bind("workspace_id", workspaceId);

            bindTraceThreadSearchCriteria(traceSearchCriteria, statement);

            Segment segment = startSegment("traces", "Clickhouse", "findCount");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<List<WorkspaceAndResourceId>> getTraceWorkspace(
            @NonNull Set<UUID> traceIds, @NonNull Connection connection) {

        if (traceIds.isEmpty()) {
            return Mono.just(List.of());
        }

        var template = getSTWithLogComment(SELECT_TRACE_ID_AND_WORKSPACE, "get_trace_workspace", "", "",
                traceIds.size());

        var statement = connection.createStatement(template.render())
                .bind("traceIds", traceIds.toArray(UUID[]::new));

        return Mono.from(statement.execute())
                .flatMapMany(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
                        row.get("workspace_id", String.class),
                        row.get("id", UUID.class))))
                .collectList();
    }

    @Override
    @WithSpan
    public Mono<Long> batchInsert(@NonNull List<Trace> traces, @NonNull Connection connection) {

        Preconditions.checkArgument(!traces.isEmpty(), "traces must not be empty");

        return Mono.from(insert(traces, connection))
                .flatMapMany(Result::getRowsUpdated)
                .reduce(0L, Long::sum);

    }

    private Publisher<? extends Result> insert(List<Trace> traces, Connection connection) {

        return makeMonoContextAware((userName, workspaceId) -> {
            List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(traces.size());

            var logComment = getLogComment("batch_insert_traces", workspaceId, userName, traces.size());

            var template = TemplateUtils.newST(BATCH_INSERT)
                    .add("items", queryItems)
                    .add("log_comment", logComment);

            Statement statement = connection.createStatement(template.render());

            // 每批次捕获一次，使客户端未提供 lastUpdatedAt 的每行获得相同的时间戳
            // ——与之前的服务器端 now64(6) 语义匹配（CH 每次查询评估一次），
            // 并避免下游 MAX(...) 聚合中对时序敏感的排序问题。
            Instant nowForBatch = Instant.now();

            int i = 0;
            for (Trace trace : traces) {
                statement.bind("id" + i, trace.id())
                        .bind("project_id" + i, trace.projectId())
                        .bind("name" + i, StringUtils.defaultIfBlank(trace.name(), ""))
                        .bind("start_time" + i, ClickHouseDateTimeFormat.formatNanos(trace.startTime()))
                        .bind("tags" + i, trace.tags() != null ? trace.tags().toArray(String[]::new) : new String[]{})
                        .bind("error_info" + i,
                                trace.errorInfo() != null ? JsonUtils.readTree(trace.errorInfo()).toString() : "")
                        .bind("thread_id" + i, StringUtils.defaultIfBlank(trace.threadId(), ""));

                bindInputOutputMetadataAndSlim(statement, trace, i);

                bindEpochSentinel(statement, "end_time" + i, trace.endTime());

                // 在客户端格式化时间戳，使 SQL 在 last_updated_at 单元格中包含纯字符串字面量。
                // 当客户端未提供值时回退到 "now"——与列的 DEFAULT now64(6) 匹配，
                // 但避免了元组中会触发 FORMAT Values 快速路径的函数调用。参见 OPIK-5694。
                statement.bind("last_updated_at" + i, ClickHouseDateTimeFormat.formatMicros(
                        trace.lastUpdatedAt() != null ? trace.lastUpdatedAt() : nowForBatch));

                statement.bind("visibility_mode" + i, trace.visibilityMode() != null
                        ? trace.visibilityMode().getValue()
                        : VisibilityMode.DEFAULT.getValue());

                TruncationUtils.bindTruncationThreshold(statement, "truncation_threshold" + i, configuration);

                bindNanSentinel(statement, "ttft" + i, trace.ttft());

                if (trace.source() != null) {
                    statement.bind("source" + i, trace.source().getValue());
                } else {
                    statement.bindNull("source" + i, String.class);
                }

                statement.bind("environment" + i, StringUtils.defaultString(trace.environment()));

                i++;
            }

            bindUserNameAndWorkspace(statement, userName, workspaceId);

            Segment segment = startSegment("traces", "Clickhouse", "batch_insert");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Flux<WorkspaceTraceCount> countTracesPerWorkspace(@NonNull Map<UUID, Instant> excludedProjectIds) {

        Optional<Instant> demoDataCreatedAt = DemoDataExclusionUtils.calculateDemoDataCreatedAt(excludedProjectIds);

        var template = getSTWithLogComment(TRACE_COUNT_BY_WORKSPACE_ID, "count_traces_per_workspace", "", "", "");

        if (!excludedProjectIds.isEmpty()) {
            template.add("excluded_project_ids", excludedProjectIds.keySet().toArray(UUID[]::new));
        }

        if (demoDataCreatedAt.isPresent()) {
            template.add("demo_data_created_at", demoDataCreatedAt.get().toString());
        }

        return asyncTemplate
                .nonTransaction(
                        connection -> {
                            Statement statement = connection.createStatement(template.render());

                            if (!excludedProjectIds.isEmpty()) {
                                statement.bind("excluded_project_ids",
                                        excludedProjectIds.keySet().toArray(UUID[]::new));
                            }

                            if (demoDataCreatedAt.isPresent()) {
                                statement.bind("demo_data_created_at", demoDataCreatedAt.get().toString());
                            }

                            return Mono.from(statement.execute());
                        })
                .flatMapMany(result -> result.map((row, rowMetadata) -> WorkspaceTraceCount.builder()
                        .workspace(row.get("workspace_id", String.class))
                        .traceCount(row.get("trace_count", Integer.class))
                        .build()));
    }

    @Override
    @WithSpan
    public Flux<BiInformation> getTraceBIInformation(@NonNull Map<UUID, Instant> excludedProjectIds) {

        Optional<Instant> demoDataCreatedAt = DemoDataExclusionUtils.calculateDemoDataCreatedAt(excludedProjectIds);

        var template = getSTWithLogComment(TRACE_DAILY_BI_INFORMATION, "get_trace_bi_information", "", "", "");

        if (!excludedProjectIds.isEmpty()) {
            template.add("excluded_project_ids", excludedProjectIds.keySet().toArray(UUID[]::new));
        }

        if (demoDataCreatedAt.isPresent()) {
            template.add("demo_data_created_at", demoDataCreatedAt.get().toString());
        }

        return asyncTemplate.nonTransaction(connection -> {
            Statement statement = connection.createStatement(template.render());

            if (!excludedProjectIds.isEmpty()) {
                statement.bind("excluded_project_ids", excludedProjectIds.keySet().toArray(UUID[]::new));
            }

            if (demoDataCreatedAt.isPresent()) {
                statement.bind("demo_data_created_at", demoDataCreatedAt.get().toString());
            }

            return Mono.from(statement.execute());
        })
                .flatMapMany(result -> result.map((row, rowMetadata) -> BiInformation.builder()
                        .workspaceId(row.get("workspace_id", String.class))
                        .user(row.get("user", String.class))
                        .count(row.get("trace_count", Long.class)).build()));
    }

    @Override
    public Mono<ProjectStats> getStats(@NonNull TraceSearchCriteria criteria) {
        // 每个分支在池中各自的连接上运行——R2DBC 连接不允许
        // 在同一条连接上并发执行两条语句。legacy-scores 标志
        // 只查询一次（同步 JDBI）并贯穿两个分支，使它们在
        // legacy feedback_scores 表中没有数据时跳过该 UNION。
        return makeMonoContextAware((userName, workspaceId) -> workspacesService.hasLegacyScores(workspaceId)
                .flatMap(hasLegacyScores -> {

                    Mono<ProjectStats> tracesSpansMono = asyncTemplate.nonTransaction(connection -> {
                        var logComment = getLogComment("get_trace_stats_traces_spans", workspaceId, userName, "");
                        var template = newTraceThreadFindTemplate(
                                SELECT_TRACES_SPANS_STATS, criteria, TRACE_SEARCH_CLAUSE, traceColumnsNonNullable());
                        template.add("log_comment", logComment);
                        template.add("has_legacy_scores", hasLegacyScores);

                        var statement = connection.createStatement(template.render())
                                .bind("project_ids", List.of(criteria.projectId()))
                                .bind("workspace_id", workspaceId);
                        bindTraceThreadSearchCriteria(criteria, statement);

                        Segment segment = startSegment("traces", "Clickhouse", "stats_traces_spans");
                        return Flux.from(statement.execute())
                                .doFinally(signalType -> endSegment(segment))
                                .flatMap(result -> result.map(
                                        (row, rowMetadata) -> StatsMapper.mapProjectStats(row, "trace_count")))
                                .singleOrEmpty();
                    });

                    Mono<ProjectStats> feedbackMono = asyncTemplate.nonTransaction(connection -> {
                        var statement = buildFeedbackStatementForCriteria(connection, criteria, workspaceId,
                                userName, hasLegacyScores);

                        Segment segment = startSegment("traces", "Clickhouse", "stats_feedback");
                        return Flux.from(statement.execute())
                                .doFinally(signalType -> endSegment(segment))
                                .flatMap(result -> result.map((row, rowMetadata) -> mapProjectScoresStats(row)))
                                .singleOrEmpty();
                    });

                    return StatsMerger.zipAndMerge(tracesSpansMono, feedbackMono);
                }))
                .onErrorResume(e -> ErrorUtils.handleMalformedJsonPath(e, ProjectStats.empty()));
    }

    /**
     * 为单项目（criteria 驱动）入口构建 SELECT_FEEDBACK_SCORES_STATS。
     * 通过 {@link com.comet.opik.infrastructure.FilterUtils#newTraceThreadFindTemplate} 路由，
     * 使每个过滤器插槽（trace、aggregation、feedback-score、experiment、annotation-queue、
     * guardrails、search）都能到达嵌入的 trace_final CTE。当任何过滤器被填充时，
     * 设置 {@code filters_present} 使模板将反馈聚合限定到已过滤的 traces。
     */
    private Statement buildFeedbackStatementForCriteria(Connection connection, TraceSearchCriteria criteria,
            String workspaceId, String userName, boolean hasLegacyScores) {
        var logComment = getLogComment("get_trace_stats_feedback_scores", workspaceId, userName, "");
        var template = newTraceThreadFindTemplate(
                SELECT_FEEDBACK_SCORES_STATS, criteria, TRACE_SEARCH_CLAUSE, traceColumnsNonNullable());
        template.add("log_comment", logComment);
        if (hasAnyTraceFilter(template)) {
            template.add("filters_present", true);
        }
        template.add("has_legacy_scores", hasLegacyScores);
        if (canDedupByArgMax(template)) {
            template.add("dedup_by_argmax", true);
        }

        var statement = connection.createStatement(template.render())
                .bind("project_ids", List.of(criteria.projectId()))
                .bind("workspace_id", workspaceId);
        bindTraceThreadSearchCriteria(criteria, statement);
        return statement;
    }

    /**
     * 为多项目（projects-list）入口构建 SELECT_FEEDBACK_SCORES_STATS。
     * projects-list 路径仅携带普通 trace 过滤器，因此仅填充 {@code filters}
     * 插槽（FilterStrategy.TRACE）。当该插槽被设置时，标记 {@code filters_present}
     * 使模板将反馈聚合限定到已过滤的 traces。
     */
    private Statement buildFeedbackStatementForProjects(Connection connection, List<UUID> projectIds,
            String workspaceId, List<? extends Filter> filters, boolean hasLegacyScores, String uuidFromTime,
            String uuidToTime) {
        var logComment = getLogComment("get_trace_stats_feedback_scores", workspaceId, "", projectIds.size());
        var template = TemplateUtils.newST(SELECT_FEEDBACK_SCORES_STATS).add("log_comment", logComment);
        template.add("has_legacy_scores", hasLegacyScores);
        if (uuidFromTime != null) {
            template.add("uuid_from_time", true);
        }
        if (uuidToTime != null) {
            template.add("uuid_to_time", true);
        }
        if (!CollectionUtils.isEmpty(filters)) {
            FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.TRACE, traceColumnsNonNullable())
                    .ifPresent(traceFilters -> {
                        template.add("filters", traceFilters);
                        template.add("filters_present", true);
                    });
        }
        if (canDedupByArgMax(template)) {
            template.add("dedup_by_argmax", true);
        }

        var statement = connection.createStatement(template.render())
                .bind("project_ids", projectIds)
                .bind("workspace_id", workspaceId);
        if (uuidFromTime != null) {
            statement.bind("uuid_from_time", uuidFromTime);
        }
        if (uuidToTime != null) {
            statement.bind("uuid_to_time", uuidToTime);
        }
        if (!CollectionUtils.isEmpty(filters)) {
            FilterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE);
        }
        return statement;
    }

    /**
     * {@code SELECT_FEEDBACK_SCORES_STATS} 的 {@code trace_final} CTE 是否可以用 {@code GROUP BY} +
     * {@code argMax} 代替 {@code FINAL} 去重。{@code traces} 是一张按
     * {@code (workspace_id, project_id, id)} 排序的 {@code ReplacingMergeTree(last_updated_at)}，
     * 因此按该键分组并从最大的 {@code last_updated_at} 取谓词判定结果，正是 {@code FINAL} + 谓词所计算的内容。
     *
     * <p>有意只作用于此模板。它只投影分组键，因此聚合状态很小、峰值内存更优，
     * 并且它从三个作用域重新求值该 CTE，这正是节省的来源。
     * {@code SELECT_TRACES_SPANS_STATS} 投影七个逐版本列，会在内存上退化，因此它保留
     * {@code FINAL}。数据见 OPIK-7636；在扩展到更宽的投影前请重新测量。
     *
     * <p>两个门槛：
     *
     * <ul>
     * <li>任何过滤器槽位都不能引入 {@code LEFT JOIN}——join 会在组内倍增行版本，且
     * 针对被 join 别名的谓词无法通过 {@code argMax} 在 {@code traces} 版本上求值。</li>
     * <li>{@code search_text} 必须存在——没有重量级的逐行扫描来分摊，聚合状态
     * 就是纯粹的 CPU 和内存开销。</li>
     * </ul>
     *
     * <p>{@code filters} <em>不会</em>被否决，即使 {@code TraceField.GUARDRAILS} 会把被 join 的
     * {@code gagg} 别名渲染进其中：{@code FilterUtils} 总会同时设置 {@code guardrails_filters}，而这
     * 才是否决的判定。保持二者同步——{@code GUARDRAILS} 是唯一映射到被 join 别名的
     * {@code FilterStrategy.TRACE} 字段。
     *
     * <p>去重键谓词和 {@code id IN (...)} 槽位保留在 {@code WHERE} 中；它们是键，或者它们
     * 选择整个 id。基于值的谓词（{@code filters}、{@code search_text}）必须移入
     * {@code HAVING argMax(...)}——在 {@code WHERE} 中按行求值会从组中丢弃旧版本，
     * 使 {@code argMax} 报告最新的<em>存活</em>版本，并重新浮现当前版本
     * 已不再匹配的内容。
     *
     * <p>{@code filters} <em>还</em>会被注入为 {@code id IN (SELECT id FROM traces WHERE <filters>)}，因为
     * {@code HAVING} 中的谓词对表的跳数索引不可见，而失去该裁剪的代价远
     * 高于此重写所节省的。子查询无需去重：它选择<em>任意</em>版本匹配的 id，
     * 这是答案的超集，再由 {@code HAVING} 收窄到最新版本。保持二者一致。
     * 不要将其替换为“可裁剪”列列表——选择性取决于过滤器的值，而非其列。
     *
     * <p>等价性是局部的，而非精确的：对于唯一最新的 {@code last_updated_at}，这会精确返回
     * {@code FINAL} 的行；但对于并列的版本，它返回其中一行，且不保证是 {@code FINAL}
     * 所选的那行，因为 {@code argMax} 的并列行为未定义。二者始终返回实际存储的行，因此它只会
     * 改变所报告的版本。没有任何决胜规则可用——{@code ReplacingMergeTree} 按
     * 插入顺序打破并列，而插入顺序不是一列。
     *
     * <p>在未来切换到 {@code traces_local_v2} 时，该表是
     * {@code ReplacingMergeTree(last_updated_at, is_deleted)}，且 {@code FINAL} 也会丢弃软删除行，因此
     * {@code HAVING} 届时必须要求 {@code argMax(is_deleted, last_updated_at) = 0}，否则软删除的 traces
     * 会重新出现。目前未输出该条件，因为 {@code traces} 上不存在该列。
     */
    @VisibleForTesting
    static boolean canDedupByArgMax(ST template) {
        return template.getAttribute("search_text") != null
                && template.getAttribute("guardrails_filters") == null
                && template.getAttribute("feedback_scores_empty_filters") == null
                && template.getAttribute("span_feedback_scores_empty_filters") == null
                && template.getAttribute("annotation_queue_filters") == null
                && template.getAttribute("annotation_queue_id") == null;
    }

    private static boolean hasAnyTraceFilter(ST template) {
        return template.getAttribute("filters") != null
                || template.getAttribute("search_text") != null
                || template.getAttribute("annotation_queue_filters") != null
                || template.getAttribute("annotation_queue_id") != null
                || template.getAttribute("feedback_scores_filters") != null
                || template.getAttribute("span_feedback_scores_filters") != null
                || template.getAttribute("trace_aggregation_filters") != null
                || template.getAttribute("experiment_filters") != null
                || template.getAttribute("feedback_scores_empty_filters") != null
                || template.getAttribute("span_feedback_scores_empty_filters") != null
                || template.getAttribute("guardrails_filters") != null;
    }

    @Override
    public Mono<Long> getDailyTraces(@NonNull Map<UUID, Instant> excludedProjectIds) {

        Optional<Instant> demoDataCreatedAt = DemoDataExclusionUtils.calculateDemoDataCreatedAt(excludedProjectIds);

        var template = getSTWithLogComment(TRACE_COUNT_BY_WORKSPACE_ID, "get_daily_traces_count", "", "", "");

        if (!excludedProjectIds.isEmpty()) {
            template.add("excluded_project_ids", excludedProjectIds.keySet().toArray(UUID[]::new));
        }

        if (demoDataCreatedAt.isPresent()) {
            template.add("demo_data_created_at", demoDataCreatedAt.get().toString());
        }

        return asyncTemplate
                .nonTransaction(
                        connection -> {
                            Statement statement = connection.createStatement(template.render());

                            if (!excludedProjectIds.isEmpty()) {
                                statement.bind("excluded_project_ids",
                                        excludedProjectIds.keySet().toArray(UUID[]::new));
                            }

                            if (demoDataCreatedAt.isPresent()) {
                                statement.bind("demo_data_created_at", demoDataCreatedAt.get().toString());
                            }

                            return Mono.from(statement.execute());
                        })
                .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("trace_count", Long.class)))
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Map<UUID, ProjectStats>> getStatsByProjectIds(@NonNull List<UUID> projectIds,
            @NonNull String workspaceId, List<? extends Filter> filters, Instant fromTime, Instant toTime) {

        if (projectIds.isEmpty()) {
            return Mono.just(Map.of());
        }

        // 每个分支在池中各自的连接上运行——R2DBC 禁止在同一条连接上并发执行语句。
        // legacy-scores 标志只解析一次（同步 JDBI），使两个分支在
        // legacy feedback_scores UNION 为空时跳过它。
        // 可选时间窗口：当调用方选择启用时（fromTime/toTime，例如 Projects 表），每个指标
        // 都通过 UUIDv7 id 上的 uuid_from_time/uuid_to_time 限定到 [fromTime, toTime]，上界
        // 排除（容忍摄取时未来日期）的 id。上下界相互独立；同时省略则保留
        // 公共 getProjectStats API 的全时段语义。窗口针对 TRACE 时间：只有 traces
        // 扫描带有并行的 toMonday(id_at) 谓词，因此一旦 traces 完成分区，只有它会按分区裁剪；
        // spans 扫描受 trace_id 约束（这是正确的——span id 可能早于其 trace），且
        // 仍会读取所有分区。span 反馈评分通过 scored_span_ids 跟随 trace 窗口。
        String uuidFromTime = Objects.toString(instantToUUIDMapper.toLowerBound(fromTime), null);
        String uuidToTime = Objects.toString(instantToUUIDMapper.toUpperBound(toTime), null);

        return workspacesService.hasLegacyScores(workspaceId)
                .flatMap(hasLegacyScores -> {

                    Mono<Map<UUID, ProjectStats>> tracesSpansMono = asyncTemplate.nonTransaction(connection -> {
                        // 拆分 A：每个项目的 traces + spans 聚合。project_stats=true 启用
                        // 项目列表的近期/过去时间段错误计数拆分。
                        var template = getSTWithLogComment(SELECT_TRACES_SPANS_STATS,
                                "get_trace_stats_traces_spans_by_project_ids", workspaceId, "", projectIds.size());
                        template.add("project_stats", true);
                        template.add("has_legacy_scores", hasLegacyScores);
                        if (uuidFromTime != null) {
                            template.add("uuid_from_time", true);
                        }
                        if (uuidToTime != null) {
                            template.add("uuid_to_time", true);
                        }
                        if (!CollectionUtils.isEmpty(filters)) {
                            FilterQueryBuilder
                                    .toAnalyticsDbFilters(filters, FilterStrategy.TRACE, traceColumnsNonNullable())
                                    .ifPresent(traceFilters -> template.add("filters", traceFilters));
                        }
                        var statement = connection.createStatement(template.render())
                                .bind("project_ids", projectIds)
                                .bind("workspace_id", workspaceId);
                        if (uuidFromTime != null) {
                            statement.bind("uuid_from_time", uuidFromTime);
                        }
                        if (uuidToTime != null) {
                            statement.bind("uuid_to_time", uuidToTime);
                        }
                        if (!CollectionUtils.isEmpty(filters)) {
                            FilterQueryBuilder.bind(statement, filters, FilterStrategy.TRACE);
                        }

                        return Mono.from(statement.execute())
                                .flatMapMany(result -> result.map((row, rowMetadata) -> Map.entry(
                                        row.get("project_id", UUID.class),
                                        StatsMapper.mapProjectStats(row, "trace_count"))))
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
                    });

                    Mono<Map<UUID, ProjectStats>> feedbackMono = asyncTemplate.nonTransaction(connection -> {
                        // 拆分 B：每个项目的反馈评分聚合。当 projects-list 传递 trace 过滤器时，
                        // 我们将其传播，使反馈聚合限定到相同的已过滤 trace 集合
                        // （模板内的 filters_present 路径）。
                        var statement = buildFeedbackStatementForProjects(connection, projectIds, workspaceId,
                                filters, hasLegacyScores, uuidFromTime, uuidToTime);

                        return Mono.from(statement.execute())
                                .flatMapMany(result -> result.map(
                                        (row, rowMetadata) -> Map.entry(row.get("project_id", UUID.class),
                                                mapProjectScoresStats(row))))
                                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
                    });

                    return Mono.zip(tracesSpansMono, feedbackMono)
                            .map(tuple -> StatsMerger.merge(tuple.getT1(), tuple.getT2()));
                });
    }

    @Override
    public Mono<List<TraceThread>> getMinimalThreadInfoByIds(@NonNull UUID projectId, @NonNull Set<String> threadId) {
        if (threadId.isEmpty()) {
            return Mono.just(List.of());
        }

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_MINIMAL_THREAD_INFO_BY_IDS, "get_minimal_thread_info_by_ids",
                    workspaceId, userName, threadId.size());

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("workspace_id", workspaceId)
                    .bind("thread_ids", threadId.toArray(String[]::new));

            return Mono.from(statement.execute())
                    .flatMapMany(this::mapMinimalThreadToDto)
                    .collectList();
        }));

    }

    private Publisher<TraceThread> mapMinimalThreadToDto(Result result) {
        return result.map((row, rowMetadata) -> TraceThread.builder()
                .id(row.get("id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .threadModelId(Optional.ofNullable(row.get("thread_model_id", String.class))
                        .filter(StringUtils::isNotBlank)
                        .map(UUID::fromString)
                        .orElse(null))
                .workspaceId(row.get("workspace_id", String.class))
                .status(TraceThreadStatus.fromValue(row.get("status", String.class)).orElse(TraceThreadStatus.ACTIVE))
                .createdBy(row.get("created_by", String.class))
                .createdAt(row.get("created_at", Instant.class))
                .build());
    }

    @Override
    @WithSpan
    public Mono<Set<UUID>> getProjectsWithTracesInRange(@NonNull Collection<Pair<String, UUID>> workspaceProjectPairs,
            @NonNull Instant from, @NonNull Instant to, @NonNull Connection connection) {

        var template = getSTWithLogComment(SELECT_PROJECTS_WITH_TRACES_IN_RANGE, "projects_with_traces_in_range",
                "", "", workspaceProjectPairs.size());
        // 在单个查询中精确匹配 (workspace_id, project_id) 元组，用于整个清理操作。
        template.add("workspace_project_pairs", toPairsLiteral(workspaceProjectPairs));

        var statement = connection.createStatement(template.render())
                .bind("from_time", from.toString())
                .bind("to_time", to.toString());

        return Mono.from(statement.execute())
                .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("project_id", UUID.class)))
                .collect(Collectors.toSet());
    }

    // 将 (workspace_id, project_id) 元组渲染为 ClickHouse IN 列表，如 ('ws','proj'),('ws2','proj2')。
    // 单引号被转义（加倍），防止值改变字面量结构；project_id 为 UUID 类型。
    private static String toPairsLiteral(Collection<Pair<String, UUID>> pairs) {
        return pairs.stream()
                .map(pair -> "('%s','%s')".formatted(
                        pair.getLeft().replace("'", "''"), pair.getRight().toString().replace("'", "''")))
                .collect(Collectors.joining(","));
    }

    @Override
    public Mono<UUID> getProjectIdFromTrace(@NonNull UUID traceId) {

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_PROJECT_ID_FROM_TRACE, "get_project_id_from_trace", workspaceId,
                    userName, "");

            var statement = connection.createStatement(template.render())
                    .bind("id", traceId)
                    .bind("workspace_id", workspaceId);

            return Mono.from(statement.execute())
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("project_id", UUID.class)))
                    .singleOrEmpty();
        }));
    }

    @Override
    @WithSpan
    public Mono<Map<UUID, UUID>> getProjectIdsByTraceIds(@NonNull List<UUID> traceIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(traceIds), "Argument 'traceIds' must not be empty");

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_PROJECT_IDS_BY_TRACE_IDS, "get_project_ids_by_trace_ids",
                    workspaceId, userName, traceIds.size());

            var statement = connection.createStatement(template.render())
                    .bind("trace_ids", traceIds.toArray(UUID[]::new));

            return collectTraceIdToProjectId(statement);
        }));
    }

    private Mono<Map<UUID, UUID>> collectTraceIdToProjectId(Statement statement) {
        return traceIdProjectIdPairs(statement)
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Mono<Map<UUID, Set<UUID>>> collectTraceIdToProjectIds(Statement statement) {
        return traceIdProjectIdPairs(statement)
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, toSet())));
    }

    private Flux<Map.Entry<UUID, UUID>> traceIdProjectIdPairs(Statement statement) {
        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .flatMapMany(result -> result.map((row, _) -> Map.entry(row.get("id", UUID.class),
                        row.get("project_id", UUID.class))));
    }

    @Override
    @WithSpan
    public Mono<Map<UUID, Set<UUID>>> getAllProjectIdsByTraceIds(Set<UUID> traceIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(traceIds), "Argument 'traceIds' must not be empty");
        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_ALL_PROJECT_IDS_BY_TRACE_IDS, "get_all_project_ids_by_trace_ids",
                    workspaceId, userName, "trace_ids_size=%s".formatted(traceIds.size()));
            var statement = connection.createStatement(template.render())
                    .bind("trace_ids", traceIds.toArray(UUID[]::new));
            return collectTraceIdToProjectIds(statement);
        }));
    }

    @Override
    @WithSpan
    public Mono<Map<UUID, Set<UUID>>> getAllProjectIdsByTraceIdsBounded(Set<UUID> traceIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(traceIds), "Argument 'traceIds' must not be empty");

        var minId = Collections.min(traceIds);
        var maxId = Collections.max(traceIds);

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_ALL_PROJECT_IDS_BY_TRACE_IDS_BOUNDED,
                    "get_all_project_ids_by_trace_ids_bounded", workspaceId, userName,
                    "trace_ids_size=%s".formatted(traceIds.size()));

            var statement = connection.createStatement(template.render())
                    .bind("trace_ids", traceIds.toArray(UUID[]::new))
                    .bind("min_id", minId)
                    .bind("max_id", maxId);

            return collectTraceIdToProjectIds(statement);
        }));
    }

    // 解析 trace -> 已存储的 start_time，显式以 workspaceId 为键，使其可以从 Cost
    // Intelligence 订阅者运行（无请求作用域）。start_time 必须来自 trace（而非 UUIDv7 时间戳），
    // 这样 cipx 身份更新不会为回填/导入的 traces 重写它。用 LIMIT 1 BY id 去重
    // （最新 last_updated_at 胜出）而非 FINAL，使其在摄取路径上保持廉价。
    @Override
    @WithSpan
    public Mono<Map<UUID, Instant>> getStartTimesByTraceIds(@NonNull Set<UUID> traceIds, @NonNull String workspaceId) {
        if (traceIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        log.info("获取 '{}' 个 trace_ids 的 start_times", traceIds.size());
        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    var template = getSTWithLogComment(SELECT_START_TIMES_BY_TRACE_IDS, "get_start_times_by_trace_ids",
                            workspaceId, "", traceIds.size());
                    var statement = connection.createStatement(template.render())
                            .bind("ids", traceIds.toArray(UUID[]::new))
                            .bind("workspace_id", workspaceId);
                    return Flux.from(statement.execute())
                            .flatMap(result -> result.map((row, metadata) -> Map.entry(
                                    row.get("id", UUID.class), row.get("start_time", Instant.class))))
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
                });
    }

    @Override
    @WithSpan
    public Mono<Set<UUID>> getTraceIdsByThreadIds(@NonNull UUID projectId, @NonNull List<String> threadIds,
            @NonNull Connection connection) {
        Preconditions.checkArgument(!threadIds.isEmpty(), "threadIds must not be empty");
        log.info("按 thread IDs 获取 trace IDs，数量 '{}'", threadIds.size());

        return makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_TRACE_IDS_BY_THREAD_IDS, "get_trace_ids_by_thread_ids",
                    workspaceId, userName, threadIds.size());

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("workspace_id", workspaceId)
                    .bind("thread_ids", threadIds.toArray(String[]::new));

            Segment segment = startSegment("traces", "Clickhouse", "getTraceIdsByThreadIds");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("id", UUID.class)))
                    .collect(toSet());
        });
    }

    @WithSpan
    public Mono<Trace> getPartialById(@NonNull UUID id) {
        log.info("按 id '{}' 获取部分 trace", id);
        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_PARTIAL_BY_ID, "get_partial_trace_by_id", workspaceId, userName,
                    "");

            var statement = connection.createStatement(template.render())
                    .bind("id", id)
                    .bind("workspace_id", workspaceId);
            var segment = startSegment("traces", "Clickhouse", "get_partial_by_id");
            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        }))
                .flatMapMany(this::mapToPartialDto)
                .singleOrEmpty();
    }

    private Publisher<Trace> mapToPartialDto(Result result) {
        return result.map((row, rowMetadata) -> Trace.builder()
                .startTime(row.get("start_time", Instant.class))
                .projectId(row.get("project_id", UUID.class))
                .build());
    }

    @Override
    public Flux<Trace> search(int limit, @NonNull TraceSearchCriteria criteria) {
        return asyncTemplate.stream(connection -> findTraceStream(limit, criteria, connection))
                .flatMap(result -> mapToDto(result, Set.of()))
                .buffer(limit > 100 ? limit / 2 : limit)
                .concatWith(Mono.just(List.of()))
                .flatMap(Flux::fromIterable)
                .onErrorResume(ErrorUtils::isMalformedJsonPath, e -> Flux.empty());
    }

    @Override
    public Mono<Long> countTraces(Set<UUID> projectIds) {

        if (CollectionUtils.isEmpty(projectIds)) {
            return Mono.just(0L);
        }

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_COUNT_TRACES_BY_PROJECT_IDS, "count_traces_by_project_ids",
                    workspaceId, userName, projectIds.size());

            var statement = connection.createStatement(template.render())
                    .bind("project_ids", projectIds)
                    .bind("workspace_id", workspaceId);

            Segment segment = startSegment("traces", "Clickhouse", "countTraces");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                    .reduce(0L, Long::sum);
        }));
    }

    private Flux<? extends Result> findTraceStream(int limit, @NonNull TraceSearchCriteria criteria,
            Connection connection) {
        log.info("按 '{}' 搜索 traces", criteria);

        return makeFluxContextAware((userName, workspaceId) -> {
            var logComment = getLogComment("find_trace_stream", workspaceId, userName,
                    "limit:" + limit + ":" + criteria);
            var template = newTraceThreadFindTemplate(
                    SELECT_BY_PROJECT_ID, criteria, TRACE_SEARCH_CLAUSE, traceColumnsNonNullable());
            template.add("log_comment", logComment);

            bindTemplateExcludeFieldVariables(criteria, template);

            // 流没有自定义排序，因此只有过滤器能让聚合驱动分页选择。
            addAggregateKeyingFlags(template, criteria, false, false, false);

            addSortNeedsWideFlag(template, criteria.sortingFields());

            template = ImageUtils.addTruncateToTemplate(template, criteria.truncate());

            var statement = connection.createStatement(template.render())
                    .bind("project_id", criteria.projectId())
                    .bind("workspace_id", workspaceId)
                    .bind("limit", limit);

            bindTraceThreadSearchCriteria(criteria, statement);

            Segment segment = startSegment("traces", "Clickhouse", "findTraceStream");

            return Flux.from(statement.execute())
                    .doFinally(signalType -> {
                        log.info("关闭 trace 搜索流");
                        endSegment(segment);
                    });
        });
    }

    @Override
    @WithSpan
    public Mono<Void> bulkUpdate(@NonNull Set<UUID> ids, @NonNull TraceUpdate update, boolean mergeTags) {
        Preconditions.checkArgument(!ids.isEmpty(), "ids must not be empty");
        log.info("批量更新 '{}' 个 traces", ids.size());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = newBulkUpdateTemplate(update, BULK_UPDATE, mergeTags, workspaceId, userName);
                    var query = template.render();

                    var statement = connection.createStatement(query)
                            .bind("ids", ids)
                            .bind("workspace_id", workspaceId)
                            .bind("user_name", userName);

                    bindBulkUpdateParams(update, statement);
                    TruncationUtils.bindTruncationThreshold(statement, "truncation_threshold", configuration);

                    Segment segment = startSegment("traces", "Clickhouse", "bulk_update");

                    return Flux.from(statement.execute())
                            .doFinally(signalType -> endSegment(segment));
                }))
                .then()
                .doOnSuccess(__ -> log.info("完成 '{}' 个 traces 的批量更新", ids.size()));
    }

    private ST newBulkUpdateTemplate(TraceUpdate traceUpdate, String sql, boolean mergeTags, String workspaceId,
            String userName) {
        var template = getSTWithLogComment(sql, "bulk_update_traces", workspaceId, userName, "");

        if (StringUtils.isNotBlank(traceUpdate.name())) {
            template.add("name", traceUpdate.name());
        }
        Optional.ofNullable(traceUpdate.input())
                .ifPresent(input -> template.add("input", input.toString()));
        Optional.ofNullable(traceUpdate.output())
                .ifPresent(output -> template.add("output", output.toString()));

        TagOperations.configureTagTemplate(template, traceUpdate, mergeTags);
        Optional.ofNullable(traceUpdate.metadata())
                .ifPresent(metadata -> template.add("metadata", metadata.toString()));
        Optional.ofNullable(traceUpdate.endTime())
                .ifPresent(endTime -> template.add("end_time", endTime.toString()));
        Optional.ofNullable(traceUpdate.errorInfo())
                .ifPresent(errorInfo -> template.add("error_info", JsonUtils.readTree(errorInfo).toString()));
        if (StringUtils.isNotBlank(traceUpdate.threadId())) {
            template.add("thread_id", traceUpdate.threadId());
        }
        Optional.ofNullable(traceUpdate.ttft())
                .ifPresent(ttft -> template.add("ttft", ttft));

        Optional.ofNullable(traceUpdate.environment())
                .ifPresent(environment -> template.add("environment", environment));

        return template;
    }

    private void bindBulkUpdateParams(TraceUpdate traceUpdate, Statement statement) {
        if (StringUtils.isNotBlank(traceUpdate.name())) {
            statement.bind("name", traceUpdate.name());
        }
        Optional.ofNullable(traceUpdate.input())
                .ifPresent(input -> {
                    String inputValue = input.toString();
                    statement.bind("input", inputValue);
                    statement.bind("input_slim", TruncationUtils.createSlimJsonString(inputValue));
                });
        Optional.ofNullable(traceUpdate.output())
                .ifPresent(output -> {
                    String outputValue = output.toString();
                    statement.bind("output", outputValue);
                    statement.bind("output_slim", TruncationUtils.createSlimJsonString(outputValue));
                });

        TagOperations.bindTagParams(statement, traceUpdate);

        Optional.ofNullable(traceUpdate.endTime())
                .ifPresent(endTime -> statement.bind("end_time", endTime.toString()));
        Optional.ofNullable(traceUpdate.metadata())
                .ifPresent(metadata -> statement.bind("metadata", metadata.toString()));
        Optional.ofNullable(traceUpdate.errorInfo())
                .ifPresent(errorInfo -> statement.bind("error_info", JsonUtils.readTree(errorInfo).toString()));
        if (StringUtils.isNotBlank(traceUpdate.threadId())) {
            statement.bind("thread_id", traceUpdate.threadId());
        }
        Optional.ofNullable(traceUpdate.ttft())
                .ifPresent(ttft -> statement.bind("ttft", ttft));

        Optional.ofNullable(traceUpdate.environment())
                .ifPresent(environment -> statement.bind("environment", environment));
    }

    private JsonNode getMetadataWithProviders(Row row, Set<Trace.TraceField> exclude, List<String> providers) {
        // 从数据库解析基础 metadata
        JsonNode baseMetadata = Optional
                .ofNullable(getValue(exclude, Trace.TraceField.METADATA, row, "metadata", String.class))
                .filter(str -> !str.isBlank())
                .map(JsonUtils::getJsonNodeFromStringWithFallback)
                .orElse(null);

        // 将 providers 作为第一个字段注入 metadata
        return JsonUtils.prependField(
                baseMetadata, Trace.TraceField.PROVIDERS.getValue(), providers);
    }

    @Override
    public Mono<Long> deleteForRetention(@NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        Preconditions.checkArgument(
                CollectionUtils.isNotEmpty(workspaceIds), "Argument 'workspaceIds' must not be empty");

        log.info("保留策略删除 traces：workspaces='{}', cutoffId='{}', lowerBound='{}'",
                workspaceIds.size(), cutoffId, lowerBound);

        var template = getSTWithLogComment(DELETE_FOR_RETENTION, "retention_delete_traces", null, "",
                workspaceIds.size());
        selectTracesMutationTable(template);

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_ids", workspaceIds.toArray(String[]::new))
                            .bind("cutoff_id", cutoffId)
                            .bind("lower_bound", lowerBound);

                    return Mono.from(statement.execute())
                            .flatMap(result -> Mono.from(result.getRowsUpdated()));
                });
    }

    @Override
    public Mono<Long> countForRetention(@NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound) {
        if (workspaceIds.isEmpty()) {
            return Mono.just(0L);
        }

        var template = getSTWithLogComment(COUNT_FOR_RETENTION, "retention_count_traces", null, "",
                workspaceIds.size());

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_ids", workspaceIds.toArray(String[]::new))
                            .bind("cutoff_id", cutoffId)
                            .bind("lower_bound", lowerBound);

                    return Mono.from(statement.execute())
                            .flatMap(result -> Mono.from(result.map((row, meta) -> row.get(0, Long.class))));
                });
    }

    @Override
    public Mono<Long> deleteForRetentionBounded(@NonNull Map<String, UUID> workspaceMinIds,
            @NonNull UUID cutoffId, @NonNull UUID lowerBound) {
        Preconditions.checkArgument(!workspaceMinIds.isEmpty(), "Argument 'workspaceMinIds' must not be empty");

        log.info("保留策略删除 traces（有界）：workspaces='{}', cutoffId='{}'", workspaceMinIds.size(), cutoffId);

        var logComment = getLogComment("retention_delete_traces_bounded", null, "", workspaceMinIds.size());
        var entries = List.copyOf(workspaceMinIds.entrySet());

        var sb = new StringBuilder(
                tracesDistributedWrapEnabled() ? "DELETE FROM traces_local WHERE (" : "DELETE FROM traces WHERE (");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(" OR ");
            sb.append("(workspace_id = :ws_").append(i)
                    .append(" AND id >= :lb_").append(i)
                    .append(" AND id < :cutoff_id)");
        }
        // toMonday(id_at) 周边界，DELETE_FOR_RETENTION 的有界对应版本。单一底限
        // 使用全局 :min_lower_bound，它 <= 每个工作空间的 :lb_i，因此不会排除
        // 任何工作空间 id 范围会删除的行。UTC 与 id_at 匹配。
        sb.append(") AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:min_lower_bound), 'UTC'))")
                .append(" AND toMonday(id_at) < addWeeks(toMonday(UUIDv7ToDateTime(toUUID(:cutoff_id), 'UTC')), 1)")
                .append(" AND id NOT IN (")
                .append("SELECT trace_id FROM experiment_items")
                .append(" WHERE workspace_id IN :workspace_ids_flat")
                .append(" AND trace_id >= :min_lower_bound")
                .append(" AND trace_id < :cutoff_id")
                .append(") SETTINGS log_comment = '").append(logComment)
                .append("', lightweight_deletes_sync = 1, allow_nondeterministic_mutations = 1");

        var sql = sb.toString();

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    var statement = connection.createStatement(sql)
                            .bind("cutoff_id", cutoffId)
                            .bind("workspace_ids_flat", workspaceMinIds.keySet().toArray(String[]::new))
                            .bind("min_lower_bound", lowerBound);

                    for (int i = 0; i < entries.size(); i++) {
                        statement.bind("ws_" + i, entries.get(i).getKey());
                        statement.bind("lb_" + i, entries.get(i).getValue());
                    }

                    return Mono.from(statement.execute())
                            .flatMap(result -> Mono.from(result.getRowsUpdated()));
                });
    }

    @Override
    public Mono<Instant> scoutFirstDayWithData(@NonNull String workspaceId,
            @NonNull UUID rangeStart, @NonNull UUID rangeEnd) {
        log.debug("探测工作区 '{}' 中第一个有数据的天，范围=['{}', '{}')",
                workspaceId, rangeStart, rangeEnd);

        var template = getSTWithLogComment(SCOUT_FIRST_DAY_WITH_DATA,
                "retention_scout_first_day", workspaceId, "", "");

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("range_start", rangeStart)
                            .bind("range_end", rangeEnd);

                    return Mono.from(statement.execute())
                            .flatMap(result -> Mono.from(result.map((row, metadata) -> {
                                var day = row.get("day", java.time.LocalDate.class);
                                return day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                            })))
                            .defaultIfEmpty(Instant.MAX); // 哨兵值：范围内无数据
                });
    }
}
