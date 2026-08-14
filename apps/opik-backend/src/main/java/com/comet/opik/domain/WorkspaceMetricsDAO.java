package com.comet.opik.domain;

import com.comet.opik.api.DataPoint;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.metrics.BreakdownField;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.metrics.WorkspaceSpanMetricRequest;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.comet.opik.utils.SentinelTranslation;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.metrics.BreakdownQueryBuilder.getBreakdownGroupExpression;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(WorkspaceMetricsDAOImpl.class)
public interface WorkspaceMetricsDAO {

    Set<MetricType> SUPPORTED_SPAN_METRICS = EnumSet.of(MetricType.SPAN_TOKEN_USAGE);

    @Deprecated
    Mono<List<WorkspaceMetricsSummaryResponse.Result>> getFeedbackScoresSummary(WorkspaceMetricsSummaryRequest request);

    @Deprecated
    Mono<List<WorkspaceMetricResponse.Result>> getFeedbackScoresDaily(WorkspaceMetricRequest request);

    Mono<WorkspaceMetricsSummaryResponse.Result> getCostsSummary(WorkspaceMetricsSummaryRequest request);

    Mono<List<WorkspaceMetricResponse.Result>> getCostsDaily(WorkspaceMetricRequest request);

    Mono<List<WorkspaceMetricResponse.Result>> getSpanTokenUsage(WorkspaceSpanMetricRequest request);

    Mono<List<String>> getWorkspaceTokenUsageNames(Set<UUID> projectIds);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspaceMetricsDAOImpl implements WorkspaceMetricsDAO {

    private static final String GET_FEEDBACK_SCORES_SUMMARY = """
            SELECT
                AVGIf(fs.value, t.id >= :id_start AND t.id \\<= :id_end) AS current,
                AVGIf(fs.value, t.id >= :id_prior_start AND t.id \\< :id_start) AS previous,
                fs.name
            FROM feedback_scores fs final
            JOIN (
                SELECT
                    id
                FROM traces final
                WHERE workspace_id = :workspace_id
                  <if(project_ids)> AND project_id IN :project_ids <endif>
                  AND id BETWEEN :id_prior_start AND :id_end
                  AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_prior_start), 'UTC'))
                  AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                  AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_prior_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
            ) t ON t.id = fs.entity_id
            WHERE workspace_id = :workspace_id
                <if(project_ids)> AND project_id IN :project_ids <endif>
                AND entity_type = 'trace'
            GROUP BY fs.name;
            """;

    private static final String GET_COSTS_SUMMARY = """
            SELECT
                SUMIf(total_estimated_cost, id >= :id_start AND id \\<= :id_end) AS current,
                SUMIf(total_estimated_cost, id >= :id_prior_start AND id \\< :id_start) AS previous,
                'cost' AS name
            FROM spans final
            WHERE workspace_id = :workspace_id
                <if(project_ids)> AND project_id IN :project_ids <endif>
                AND id BETWEEN :id_prior_start AND :id_end
                AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_prior_start), 'UTC'))
                AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_prior_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9);
            """;

    private static final String GET_FEEDBACK_SCORES_DAILY_BY_PROJECT = """
            WITH feedback_scores_daily AS (
                SELECT fs.project_id AS project_id,
                       toStartOfInterval(t.start_time, toIntervalDay(1)) AS bucket,
                       if(COUNT(1) = 0, NULL, avg(fs.value)) AS value
                FROM feedback_scores fs final
                JOIN (
                    SELECT
                        id,
                        start_time
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                      AND project_id IN :project_ids
                      AND id BETWEEN :id_start AND :id_end
                      AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_start), 'UTC'))
                      AND toMonday(id_at) <= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                      AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                ) t ON t.id = fs.entity_id
                WHERE workspace_id = :workspace_id
                  AND project_id IN :project_ids
                  AND entity_type = 'trace'
                  AND name = :name
                GROUP BY fs.project_id, bucket
                ORDER BY fs.project_id, bucket
                WITH FILL
                FROM toStartOfInterval(parseDateTimeBestEffort(:timestamp_start), toIntervalDay(1))
                    TO parseDateTimeBestEffort(:timestamp_end)
                    STEP toIntervalDay(1)
            )
            SELECT
                project_id,
                :name AS name,
                groupArray(tuple(bucket, value)) AS data
            FROM feedback_scores_daily
            GROUP BY project_id
            ;
            """;

    private static final String GET_FEEDBACK_SCORES_DAILY = """
            WITH feedback_scores_daily AS (
                SELECT toStartOfInterval(t.start_time, toIntervalDay(1)) AS bucket,
                       if(COUNT(1) = 0, NULL, avg(fs.value)) AS value
                FROM feedback_scores fs final
                JOIN (
                    SELECT
                        id,
                        start_time
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                      AND id BETWEEN :id_start AND :id_end
                      AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_start), 'UTC'))
                      AND toMonday(id_at) <= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                      AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                ) t ON t.id = fs.entity_id
                WHERE workspace_id = :workspace_id
                  AND entity_type = 'trace'
                  AND name = :name
                GROUP BY bucket
                ORDER BY bucket
                WITH FILL
                FROM toStartOfInterval(parseDateTimeBestEffort(:timestamp_start), toIntervalDay(1))
                    TO parseDateTimeBestEffort(:timestamp_end)
                    STEP toIntervalDay(1)
            )
            SELECT
                NULL AS project_id,
                :name AS name,
                groupArray(tuple(bucket, value)) AS data
            FROM feedback_scores_daily
            ;
            """;

    private static final String GET_COSTS_DAILY_BY_PROJECT = """
            WITH costs_daily AS (
                SELECT toStartOfInterval(start_time, toIntervalDay(1)) AS bucket,
                       if(COUNT(1) = 0, NULL, sum(total_estimated_cost)) AS value,
                       project_id
                FROM spans final
                WHERE workspace_id = :workspace_id
                  AND project_id IN :project_ids
                  AND id BETWEEN :id_start AND :id_end
                  AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_start), 'UTC'))
                  AND toMonday(id_at) <= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                  AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                GROUP BY project_id, bucket
                ORDER BY project_id, bucket
                WITH FILL
                FROM toStartOfInterval(parseDateTimeBestEffort(:timestamp_start), toIntervalDay(1))
                    TO parseDateTimeBestEffort(:timestamp_end)
                    STEP toIntervalDay(1)
            )
            SELECT
                project_id,
                :name AS name,
                groupArray(tuple(bucket, value)) AS data
            FROM costs_daily
            GROUP BY project_id
            ;
            """;

    private static final String GET_COSTS_DAILY = """
            WITH costs_daily AS (
                SELECT toStartOfInterval(start_time, toIntervalDay(1)) AS bucket,
                       if(COUNT(1) = 0, NULL, sum(total_estimated_cost)) AS value
                FROM spans final
                WHERE workspace_id = :workspace_id
                  AND id BETWEEN :id_start AND :id_end
                  AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:id_start), 'UTC'))
                  AND toMonday(id_at) <= toMonday(UUIDv7ToDateTime(toUUID(:id_end), 'UTC'))
                  AND start_time BETWEEN parseDateTime64BestEffort(:timestamp_start, 9) AND parseDateTime64BestEffort(:timestamp_end, 9)
                GROUP BY bucket
                ORDER BY bucket
                WITH FILL
                FROM toStartOfInterval(parseDateTimeBestEffort(:timestamp_start), toIntervalDay(1))
                    TO parseDateTimeBestEffort(:timestamp_end)
                    STEP toIntervalDay(1)
            )
            SELECT
                NULL AS project_id,
                :name AS name,
                groupArray(tuple(bucket, value)) AS data
            FROM costs_daily
            ;
            """;

    // 通过 SpanMetricsQueries 与 ProjectMetricsDAO 共享。工作区聚合查询显式的项目集合：
    // WorkspaceMetricsService 会预先将 "all projects" 请求解析为每个项目 id，因此
    // 谓词始终是有界的 `project_id IN :project_ids` 列表，可在 spans 主键
    // (workspace_id, project_id, ...) 上裁剪——绝不会进行无约束的全工作区扫描。
    private static final String SPAN_FILTERED_PREFIX = SpanMetricsQueries
            .spanFilteredPrefix("project_id IN :project_ids");

    // Span 过滤复用了 ProjectMetricsDAO 的 SPAN_FILTERED_PREFIX（见上文），但输出按照
    // 与 GET_COSTS_DAILY 相同的工作区原生风格来组织：每一行是一个完整的序列 {project_id, name, data}，其中 data 是
    // groupArray(tuple(bucket, value))。无拆分时 => 每个 usage 键一个序列；带 provider/model 拆分时 =>
    // 每个分组一个序列，与 GET_COSTS_DAILY_BY_PROJECT 按项目分组的方式一致。
    private static final String GET_SPAN_TOKEN_USAGE = """
            %s, spans_usage AS (
                SELECT span_time,
                       name,
                       value
                FROM spans_filtered s
                ARRAY JOIN mapKeys(usage) AS name, mapValues(usage) AS value
                WHERE value > 0
            )
            , series AS (
                SELECT <bucket> AS bucket,
                       name,
                       sum(value) AS value
                FROM spans_usage
                GROUP BY name, bucket
                ORDER BY name, bucket
                <if(with_fill)>WITH FILL
                    FROM <fill_from>
                    TO toDateTime(UUIDv7ToDateTime(toUUID(:uuid_to_time)))
                    STEP <step><endif>
            )
            SELECT NULL AS project_id,
                   name,
                   groupArray(tuple(bucket, value)) AS data
            FROM series
            GROUP BY name
            SETTINGS log_comment = '<log_comment>';
            """.formatted(SPAN_FILTERED_PREFIX);

    private static final String GET_SPAN_TOKEN_USAGE_WITH_BREAKDOWN = """
            %s, spans_usage AS (
                SELECT span_time,
                       <group_expression> AS group_name,
                       value
                FROM spans_filtered s
                ARRAY JOIN mapKeys(usage) AS name, mapValues(usage) AS value
                WHERE value > 0
                AND name = :sub_metric
            )
            , series AS (
                SELECT <bucket> AS bucket,
                       group_name,
                       sum(value) AS value
                FROM spans_usage
                GROUP BY group_name, bucket
                ORDER BY group_name, bucket
            )
            SELECT NULL AS project_id,
                   group_name AS name,
                   groupArray(tuple(bucket, value)) AS data
            FROM series
            GROUP BY group_name
            SETTINGS log_comment = '<log_comment>';
            """.formatted(SPAN_FILTERED_PREFIX);

    // 在显式的项目集合上、全时段范围内去重 span token-usage 的键名。与 ProjectMetricsDAO 的按项目查询
    // 共享 SpanMetricsQueries；只有项目谓词不同（有界的 project_id IN (...) 列表）。
    private static final String GET_WORKSPACE_TOKEN_USAGE_NAMES = SpanMetricsQueries
            .tokenUsageNames("project_id IN :project_ids");

    private static final String WORKSPACE_METRIC_QUERY_NAME_PREFIX = "WorkspaceMetrics_";

    private static final Map<TimeInterval, String> INTERVAL_TO_SQL = Map.of(
            TimeInterval.WEEKLY, "toIntervalWeek(1)",
            TimeInterval.DAILY, "toIntervalDay(1)",
            TimeInterval.HOURLY, "toIntervalHour(1)");

    // 每个 Span 过滤策略所渲染到的 SPAN_FILTERED_PREFIX 模板占位符。同时驱动
    // 模板 `add` 阶段和语句 `bind` 阶段，使每个策略只声明一次，而无需在可能漂移的两处维护。
    private static final Map<FilterStrategy, String> SPAN_FILTER_TEMPLATE_PLACEHOLDERS = Map.of(
            FilterStrategy.SPAN, "span_filters",
            FilterStrategy.SPAN_FEEDBACK_SCORES, "span_feedback_scores_filters",
            FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY, "feedback_scores_empty_filters");

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull InstantToUUIDMapper instantToUUIDMapper;
    private final @NonNull OpikConfiguration configuration;

    private boolean spanColumnsNonNullable() {
        return configuration.getDatabaseAnalyticsDataModel().spanColumnsNonNullable();
    }

    @Override
    public Mono<List<WorkspaceMetricsSummaryResponse.Result>> getFeedbackScoresSummary(
            @NonNull WorkspaceMetricsSummaryRequest request) {
        return getMetricsSummary(request, GET_FEEDBACK_SCORES_SUMMARY);
    }

    @Override
    public Mono<List<WorkspaceMetricResponse.Result>> getFeedbackScoresDaily(@NonNull WorkspaceMetricRequest request) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.name()),
                "For metrics request, name must be provided");
        var query = CollectionUtils
                .isEmpty(request.projectIds())
                        ? GET_FEEDBACK_SCORES_DAILY
                        : GET_FEEDBACK_SCORES_DAILY_BY_PROJECT;
        return getMetricsDaily(request, query);
    }

    @Override
    public Mono<WorkspaceMetricsSummaryResponse.Result> getCostsSummary(
            @NonNull WorkspaceMetricsSummaryRequest request) {
        return getMetricsSummary(request, GET_COSTS_SUMMARY)
                .map(List::getFirst);
    }

    @Override
    public Mono<List<WorkspaceMetricResponse.Result>> getCostsDaily(@NonNull WorkspaceMetricRequest request) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.name()),
                "For metrics request, name must be provided");
        var query = CollectionUtils
                .isEmpty(request.projectIds())
                        ? GET_COSTS_DAILY
                        : GET_COSTS_DAILY_BY_PROJECT;
        return getMetricsDaily(request, query);
    }

    @Override
    public Mono<List<WorkspaceMetricResponse.Result>> getSpanTokenUsage(@NonNull WorkspaceSpanMetricRequest request) {
        return template.nonTransaction(connection -> getSpanMetric(request, connection,
                request.hasBreakdown() ? GET_SPAN_TOKEN_USAGE_WITH_BREAKDOWN : GET_SPAN_TOKEN_USAGE,
                "workspaceSpanTokenUsage")
                .flatMapMany(this::rowToDataPoint)
                .collectList());
    }

    @Override
    public Mono<List<String>> getWorkspaceTokenUsageNames(@NonNull Set<UUID> projectIds) {
        // 服务在调用 DAO 之前已将 "all projects" 解析为显式的项目集合，因此查询
        // 始终以 project_id IN (...) 为界，并在 spans 主键上裁剪，而不是扫描整个工作区。
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(projectIds),
                "projectIds must be resolved before querying workspace token usage names");
        return template.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var stTemplate = getSTWithLogComment(GET_WORKSPACE_TOKEN_USAGE_NAMES,
                    WORKSPACE_METRIC_QUERY_NAME_PREFIX + "tokenUsageNames", workspaceId, userName, projectIds.size());

            var statement = connection.createStatement(stTemplate.render())
                    .bind("workspace_id", workspaceId)
                    .bind("project_ids", projectIds.toArray(new UUID[0]));

            InstrumentAsyncUtils.Segment segment = startSegment("workspaceTokenUsageNames", "Clickhouse", "get");

            return Mono.from(statement.execute())
                    .flatMapMany(result -> result.map((row, metadata) -> row.get("name", String.class)))
                    .collectList()
                    .doFinally(signalType -> endSegment(segment));
        }));
    }

    private Mono<? extends Result> getSpanMetric(WorkspaceSpanMetricRequest request, Connection connection,
            String query, String segmentName) {
        // 服务在调用 DAO 之前已将 "all projects" 解析为显式的项目集合，因此查询
        // 始终以 project_id IN (...) 为界，并在 spans 主键上裁剪，而不是扫描整个工作区。
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(request.projectIds()),
                "projectIds must be resolved before querying workspace span metrics");
        return makeMonoContextAware((userName, workspaceId) -> {
            var interval = request.interval();
            var isTotal = interval == TimeInterval.TOTAL;

            var stTemplate = getSTWithLogComment(query, WORKSPACE_METRIC_QUERY_NAME_PREFIX + segmentName, workspaceId,
                    userName, request.projectIds().size());

            if (isTotal) {
                stTemplate.add("bucket", "toDateTime(UUIDv7ToDateTime(toUUID(:uuid_from_time)))");
            } else {
                stTemplate.add("step", intervalToSql(interval))
                        .add("bucket", wrapWeekly(interval,
                                "toStartOfInterval(span_time, %s)".formatted(intervalToSql(interval))))
                        .add("fill_from", wrapWeekly(interval,
                                "toStartOfInterval(UUIDv7ToDateTime(toUUID(:uuid_from_time)), %s)"
                                        .formatted(intervalToSql(interval))));
            }

            if (request.hasBreakdown()) {
                stTemplate.add("group_expression",
                        getBreakdownGroupExpression(request.metricType(), request.breakdown()));
            }

            Optional.ofNullable(request.filters())
                    .ifPresent(filters -> SPAN_FILTER_TEMPLATE_PLACEHOLDERS
                            .forEach((strategy, placeholder) -> FilterQueryBuilder
                                    .toAnalyticsDbFilters(filters, strategy,
                                            strategy == FilterStrategy.SPAN && spanColumnsNonNullable())
                                    .ifPresent(rendered -> stTemplate.add(placeholder, rendered))));

            stTemplate.add("uuid_from_time", true);
            stTemplate.add("uuid_to_time", true);
            if (!isTotal) {
                stTemplate.add("with_fill", true);
            }

            var intervalEnd = request.intervalEnd() != null ? request.intervalEnd() : Instant.now();
            var statement = connection.createStatement(stTemplate.render())
                    .bind("uuid_from_time", instantToUUIDMapper.toLowerBound(request.intervalStart()).toString())
                    .bind("uuid_to_time", instantToUUIDMapper.toUpperBound(intervalEnd).toString())
                    .bind("workspace_id", workspaceId)
                    .bind("project_ids", request.projectIds().toArray(new UUID[0]));

            if (request.hasBreakdown() && request.breakdown().field() == BreakdownField.METADATA) {
                statement.bind("metadata_key", request.breakdown().metadataKey());
            }

            // SPAN_TOKEN_USAGE 拆分按名称 (sub_metric) 选中并累加单个 token-usage 条目
            if (request.hasBreakdown() && request.metricType() == MetricType.SPAN_TOKEN_USAGE) {
                statement.bind("sub_metric", Optional.ofNullable(request.breakdown().subMetric()).orElse(""));
            }

            Optional.ofNullable(request.filters())
                    .ifPresent(filters -> SPAN_FILTER_TEMPLATE_PLACEHOLDERS.keySet()
                            .forEach(strategy -> FilterQueryBuilder.bind(statement, filters, strategy)));

            InstrumentAsyncUtils.Segment segment = startSegment(segmentName, "Clickhouse", "get");

            return Mono.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private String wrapWeekly(TimeInterval interval, String stmt) {
        if (interval == TimeInterval.WEEKLY) {
            return "toDateTime(%s)".formatted(stmt);
        }
        return stmt;
    }

    private String intervalToSql(TimeInterval interval) {
        return Optional.ofNullable(INTERVAL_TO_SQL.get(interval))
                .orElseThrow(() -> new IllegalArgumentException("Invalid interval: " + interval));
    }

    private Mono<List<WorkspaceMetricResponse.Result>> getMetricsDaily(WorkspaceMetricRequest request,
            String query) {
        return template.nonTransaction(connection -> getMetricsDaily(connection, request, query)
                .flatMapMany(this::rowToDataPoint)
                .collectList());
    }

    private Mono<? extends Result> getMetricsDaily(Connection connection, WorkspaceMetricRequest request,
            String query) {

        var statement = connection.createStatement(query)
                .bind("timestamp_start", request.intervalStart().toString())
                .bind("timestamp_end", request.intervalEnd().toString())
                .bind("id_start",
                        idGenerator.getTimeOrderedEpoch(request.intervalStart().toEpochMilli()))
                .bind("id_end", idGenerator.getTimeOrderedEpoch(request.intervalEnd().toEpochMilli()))
                .bind("name", request.name());

        if (CollectionUtils.isNotEmpty(request.projectIds())) {
            statement.bind("project_ids", request.projectIds());
        }

        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
    }

    private Mono<List<WorkspaceMetricsSummaryResponse.Result>> getMetricsSummary(
            WorkspaceMetricsSummaryRequest request,
            String query) {
        return template.nonTransaction(connection -> getMetricsSummary(connection, request, query)
                .flatMapMany(result -> result.map((row, rowMetadata) -> WorkspaceMetricsSummaryResponse.Result.builder()
                        .name(row.get("name", String.class))
                        .current(filterNan(row.get("current", Double.class)))
                        .previous(filterNan(row.get("previous", Double.class)))
                        .build()))
                .filter(result -> result.current() != null)
                .collectList());
    }

    private Mono<? extends Result> getMetricsSummary(Connection connection,
            WorkspaceMetricsSummaryRequest request,
            String query) {
        var template = TemplateUtils.newST(query);

        if (CollectionUtils.isNotEmpty(request.projectIds())) {
            template.add("project_ids", request.projectIds());
        }

        var statement = connection.createStatement(template.render())
                .bind("timestamp_prior_start", getPriorStart(request.intervalStart(), request.intervalEnd()).toString())
                .bind("timestamp_end", request.intervalEnd().toString())
                .bind("id_start",
                        idGenerator.getTimeOrderedEpoch(request.intervalStart().toEpochMilli()))
                .bind("id_end", idGenerator.getTimeOrderedEpoch(request.intervalEnd().toEpochMilli()))
                .bind("id_prior_start",
                        idGenerator.getTimeOrderedEpoch(
                                getPriorStart(request.intervalStart(), request.intervalEnd()).toEpochMilli()));

        if (CollectionUtils.isNotEmpty(request.projectIds())) {
            statement.bind("project_ids", request.projectIds());
        }

        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
    }

    private Instant getPriorStart(Instant intervalStart, Instant intervalEnd) {
        // 计算两个时间戳之间的时长
        Duration duration = Duration.between(intervalStart, intervalEnd);

        // 从 intervalStart 减去该时长以得到前一个起始时间戳
        return intervalStart.minus(duration);
    }

    private Publisher<WorkspaceMetricResponse.Result> rowToDataPoint(Result result) {
        return result.map(((row, rowMetadata) -> WorkspaceMetricResponse.Result.builder()
                .projectId(row.get("project_id", UUID.class))
                .name(row.get("name", String.class))
                .data(getDailyData(row.get("data", List[].class)))
                .build()));
    }

    private List<DataPoint<Double>> getDailyData(List[] dataArray) {
        if (ArrayUtils.isEmpty(dataArray)) {
            return null;
        }

        var dataItems = Arrays.stream(dataArray)
                .filter(CollectionUtils::isNotEmpty)
                .map(dataItem -> DataPoint.<Double>builder()
                        .time(toInstant(dataItem.get(0)))
                        .value(Optional.ofNullable(dataItem.get(1)).map(Object::toString)
                                .map(Double::parseDouble)
                                .orElse(null))
                        .build())
                .toList();

        return dataItems.isEmpty() ? null : dataItems;
    }

    // 对于 DateTime64 列（如 cost 查询的 start_time），bucket 时间戳以 OffsetDateTime 返回；而对于
    // 普通的 DateTime 表达式（如由 UUIDv7ToDateTime 派生的 span_time），则以 LocalDateTime 返回。二者都表示 UTC。
    private Instant toInstant(Object bucket) {
        return switch (bucket) {
            case OffsetDateTime offsetDateTime -> offsetDateTime.toInstant();
            case LocalDateTime localDateTime -> localDateTime.toInstant(ZoneOffset.UTC);
            default -> throw new IllegalStateException(
                    "Unexpected bucket time type: " + bucket.getClass().getName());
        };
    }

    Double filterNan(Double value) {
        return SentinelTranslation.nanToNull(value);
    }
}
