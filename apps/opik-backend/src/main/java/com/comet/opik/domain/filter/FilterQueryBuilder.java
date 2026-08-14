package com.comet.opik.domain.filter;

import com.comet.opik.api.filter.AlertField;
import com.comet.opik.api.filter.AnnotationQueueField;
import com.comet.opik.api.filter.AutomationRuleEvaluatorField;
import com.comet.opik.api.filter.DashboardField;
import com.comet.opik.api.filter.DatasetField;
import com.comet.opik.api.filter.DatasetItemField;
import com.comet.opik.api.filter.ExperimentField;
import com.comet.opik.api.filter.ExperimentsComparisonValidKnownField;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.OptimizationField;
import com.comet.opik.api.filter.PromptField;
import com.comet.opik.api.filter.PromptVersionField;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.sorting.SortingField;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.r2dbc.spi.Statement;
import lombok.NonNull;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;
import ru.yandex.clickhouse.ClickHouseUtil;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static com.comet.opik.api.filter.Operator.NO_VALUE_OPERATORS;
import static com.comet.opik.api.sorting.SortingFactoryPromptVersions.PROMPT_VERSIONS_FIELDS_PATTERN;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

public class FilterQueryBuilder {

    private static final String ANALYTICS_DB_AND_OPERATOR = "AND";

    public static final String JSONPATH_ROOT = "$";

    private static final String JSON_EXTRACT_RAW_TEMPLATE = "JSONExtractRaw(%s, '%s')";
    public static final String OUTPUT_FIELD_PREFIX = "output.";
    public static final String INPUT_FIELD_PREFIX = "input.";
    public static final String METADATA_FIELD_PREFIX = "metadata.";

    private static final String ID_DB = "id";
    private static final String NAME_DB = "name";
    private static final String DESCRIPTION_DB = "description";
    private static final String START_TIME_ANALYTICS_DB = "start_time";
    private static final String END_TIME_ANALYTICS_DB = "end_time";
    /**
     * 列变为非空后，用于 trace/thread/span 过滤的感知哨兵值的 {@code end_time}：{@code nullIf} 把纪元哨兵值折叠为
     * {@code NULL}，从而让范围/不等式比较排除缺失值。仅在 {@code columnsNonNullable} 下应用——当列还是 Nullable 时，
     * 客户端提供的纪元值是一个合法值，必须继续匹配。
     */
    private static final String END_TIME_NON_NULLABLE_ANALYTICS_DB = "nullIf(end_time, toDateTime64('1970-01-01 00:00:00.000', 9))";
    /**
     * 在调用方的切换标志下，其过滤解析为 {@link #END_TIME_NON_NULLABLE_ANALYTICS_DB} 的 {@code end_time} 字段——
     * 每个已迁移的实体各一个（trace/thread 的 {@code traceColumnsNonNullable}，span 的 {@code spanColumnsNonNullable}）。
     */
    private static final Set<Field> END_TIME_SENTINEL_FIELDS = Set.of(
            TraceField.END_TIME, TraceThreadField.END_TIME, SpanField.END_TIME);
    private static final String INPUT_ANALYTICS_DB = "input";
    private static final String OUTPUT_ANALYTICS_DB = "output";
    private static final String METADATA_ANALYTICS_DB = "metadata";
    private static final String MODEL_ANALYTICS_DB = "model";
    private static final String PROVIDER_ANALYTICS_DB = "provider";
    private static final String TOTAL_ESTIMATED_COST_ANALYTICS_DB = "total_estimated_cost";
    private static final String LLM_SPAN_COUNT_ANALYTICS_DB = "llm_span_count";
    private static final String TYPE_ANALYTICS_DB = "type";
    private static final String TAGS_DB = "tags";
    private static final String VERSION_COUNT_DB = "version_count";
    private static final String TEMPLATE_STRUCTURE_DB = "template_structure";
    private static final String USAGE_COMPLETION_TOKENS_ANALYTICS_DB = "usage['completion_tokens']";
    private static final String USAGE_PROMPT_TOKENS_ANALYTICS_DB = "usage['prompt_tokens']";
    private static final String USAGE_TOTAL_TOKENS_ANALYTICS_DB = "usage['total_tokens']";
    private static final String VALUE_ANALYTICS_DB = "value";
    /**
     * 由 {@code start_time}/{@code end_time} 推导的时长（毫秒）。{@code notEquals(end_time, epoch)} 守卫与其他每个
     * 时长计算一样，可防止缺失（纪元哨兵值）的 {@code end_time} 在列变为非空后产生无意义的负时长。列仍为
     * Nullable 时它是空操作（缺失值读作 {@code NULL}）。
     */
    private static final String DURATION_ANALYTICS_DB = "if(end_time IS NOT NULL AND notEquals(end_time, toDateTime64('1970-01-01 00:00:00.000', 9)) AND start_time IS NOT NULL AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)), (dateDiff('microsecond', start_time, end_time) / 1000.0), 0)";
    /**
     * 用于物化列（实验比较过滤）的感知哨兵值的 {@code duration}：{@code isNaN} 把 {@code NaN} 哨兵值折叠为
     * {@code NULL}，从而让比较排除缺失值——尤其是 {@code !=}，因为 {@code NaN != x} 为 true。无条件应用
     * （与 {@code TTFT_ANALYTICS_DB} 一致）；在列仍为 Nullable 时是空操作，因为此时 {@code NaN} 还不会出现。
     */
    private static final String NEW_DURATION_ANALYTICS_DB = "if(isNaN(duration), NULL, duration)";
    /**
     * 用于过滤的感知哨兵值的 {@code ttft}：{@code isNaN} 把 {@code NaN} 哨兵值折叠为 {@code NULL}，从而让比较
     * 排除缺失值——尤其是 {@code !=}，因为 {@code NaN != x} 为 true。无条件应用，不受标志门控：列仍为 Nullable
     * 时 {@code NaN} 不会出现，所以目前它是空操作。
     */
    private static final String TTFT_ANALYTICS_DB = "if(isNaN(ttft), NULL, ttft)";
    private static final String THREAD_ID_ANALYTICS_DB = "thread_id";
    private static final String DATASET_ID_ANALYTICS_DB = "dataset_id";
    private static final String PROMPT_IDS_ANALYTICS_DB = "prompt_ids";
    private static final String FIRST_MESSAGE_ANALYTICS_DB = "first_message";
    private static final String LAST_MESSAGE_ANALYTICS_DB = "last_message";
    private static final String CREATED_AT_DB = "created_at";
    private static final String LAST_UPDATED_AT_DB = "last_updated_at";
    private static final String CREATED_BY_DB = "created_by";
    private static final String LAST_UPDATED_BY_DB = "last_updated_by";
    private static final String LAST_CREATED_EXPERIMENT_AT_DB = "last_created_experiment_at";
    private static final String LAST_CREATED_OPTIMIZATION_AT_DB = "last_created_optimization_at";
    private static final String PROJECT_ID_DB = "project_id";
    private static final String INSTRUCTIONS_DB = "instructions";
    private static final String NUMBER_OF_MESSAGES_ANALYTICS_DB = "number_of_messages";
    private static final String FEEDBACK_SCORE_COUNT_DB = "fsc.feedback_scores_count";
    private static final String SPAN_FEEDBACK_SCORE_COUNT_DB = "sfsc.span_feedback_scores_count";
    private static final String EXPERIMENT_SCORE_COUNT_DB = "esc.experiment_scores_count";
    private static final String GUARDRAILS_RESULT_DB = "gagg.guardrails_result";
    private static final String VISIBILITY_MODE_DB = "visibility_mode";
    private static final String ERROR_INFO_DB = "error_info";
    private static final String ERROR_TYPE_DB = "simpleJSONExtractString(error_info, 'exception_type')";
    private static final String STATUS_DB = "status";
    public static final String FEEDBACK_DEFINITIONS_DB = "feedback_definitions";
    public static final String SCOPE_DB = "scope";
    private static final String DATA_ANALYTICS_DB = "data";
    private static final String FULL_DATA_ANALYTICS_DB = "toString(data)";
    private static final String SOURCE_DB = "source";
    private static final String ENVIRONMENT_DB = "environment";
    private static final String TRACE_ID_DB = "trace_id";
    private static final String SPAN_ID_DB = "span_id";
    public static final String ANNOTATION_QUEUE_IDS_ANALYTICS_DB = "taqi.annotation_queue_ids";
    public static final String THREAD_ANNOTATION_QUEUE_IDS_ANALYTICS_DB = "ttaqi.annotation_queue_ids";
    private static final String EXPERIMENT_ID_DB = "experiment_id";
    private static final String WEBHOOK_URL_DB = "webhook_url";
    private static final String ALERT_TYPE_DB = "alert_type";
    private static final String ENABLED_DB = "enabled";
    private static final String SAMPLING_RATE_DB = "sampling_rate";
    private static final String TYPE_DB = "type";
    private static final String COMMIT_DB = "commit";
    private static final String TEMPLATE_DB = "template";
    private static final String CHANGE_DESCRIPTION_DB = "change_description";
    private static final String VERSION_NUMBER_DB = "version_number";

    /**
     * 跨不同实体类型（Trace、Span、TraceThread、Experiment 等）的所有反馈分数字段的集合。
     * 用于识别需要在查询构建中进行特殊处理的反馈分数过滤条件。
     */
    private static final Set<Field> FEEDBACK_SCORE_FIELDS = Set.of(
            TraceField.FEEDBACK_SCORES,
            TraceField.SPAN_FEEDBACK_SCORES,
            SpanField.FEEDBACK_SCORES,
            TraceThreadField.FEEDBACK_SCORES,
            ExperimentsComparisonValidKnownField.FEEDBACK_SCORES,
            ExperimentField.FEEDBACK_SCORES,
            ExperimentField.EXPERIMENT_SCORES);

    // AutomationRuleEvaluator 查询的表别名前缀
    private static final String AUTOMATION_RULE_TABLE_ALIAS = "rule.%s";
    private static final String AUTOMATION_EVALUATOR_TABLE_ALIAS = "evaluator.%s";

    private static final Map<Operator, Map<FieldType, String>> ANALYTICS_DB_OPERATOR_MAP = new EnumMap<>(
            ImmutableMap.<Operator, Map<FieldType, String>>builder()
                    .put(Operator.CONTAINS, new EnumMap<>(Map.of(
                            FieldType.STRING, "ilike(%1$s, CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.STRING_EXACT, "%1$s LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.STRING_STATE_DB, "%1$s LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.LIST,
                            "arrayExists(element -> (ilike(element, CONCAT('%%', :filter%2$d ,'%%'))), %1$s) = 1",
                            FieldType.DICTIONARY,
                            "ilike(JSON_VALUE(%1$s, :filterKey%2$d), CONCAT('%%', :filter%2$d ,'%%'))",
                            // MAP 值以 JSON 字符串存储（例如带引号的 "hello"），因此我们使用原始值
                            // CONTAINS 之所以有效，是因为无论值周围是否有引号，模式都能在值内部找到
                            FieldType.DICTIONARY_STATE_DB,
                            "JSON_VALUE(%1$s, :filterKey%2$d) LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.MAP,
                            "ilike(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), CONCAT('%%', :filter%2$d ,'%%'))")))
                    .put(Operator.NOT_CONTAINS, new EnumMap<>(Map.of(
                            FieldType.STRING, "notILike(%1$s, CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.STRING_EXACT, "%1$s NOT LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.STRING_STATE_DB, "%1$s NOT LIKE CONCAT('%%', :filter%2$d ,'%%')",
                            FieldType.LIST,
                            "arrayExists(element -> (ilike(element, CONCAT('%%', :filter%2$d ,'%%'))), %1$s) = 0",
                            // MAP 值以 JSON 字符串存储，NOT_CONTAINS 使用原始值即可
                            FieldType.MAP,
                            "notILike(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.DICTIONARY,
                            "notILike(JSON_VALUE(%1$s, :filterKey%2$d), CONCAT('%%', :filter%2$d ,'%%'))",
                            FieldType.DICTIONARY_STATE_DB,
                            "JSON_VALUE(%1$s, :filterKey%2$d) NOT LIKE CONCAT('%%', :filter%2$d ,'%%')")))
                    .put(Operator.STARTS_WITH, new EnumMap<>(Map.of(
                            FieldType.STRING, "startsWith(lower(%1$s), lower(:filter%2$d))",
                            FieldType.STRING_EXACT, "startsWith(%1$s, :filter%2$d)",
                            FieldType.STRING_STATE_DB, "%1$s LIKE CONCAT(:filter%2$d ,'%%')",
                            // MAP 值以 JSON 字符串存储，可能包含转义引号（例如 "\"hello\""）
                            // 先用 replaceAll 移除转义引号，再用 trimBoth 去除剩余引号
                            FieldType.MAP,
                            "startsWith(lower(trimBoth(replaceAll(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), '\\\\\"', ''), '\"')), lower(:filter%2$d))",
                            FieldType.DICTIONARY,
                            "startsWith(lower(JSON_VALUE(%1$s, :filterKey%2$d)), lower(:filter%2$d))",
                            FieldType.DICTIONARY_STATE_DB,
                            "JSON_VALUE(%1$s, :filterKey%2$d) LIKE CONCAT(:filter%2$d ,'%%')")))
                    .put(Operator.ENDS_WITH, new EnumMap<>(Map.of(
                            FieldType.STRING, "endsWith(lower(%1$s), lower(:filter%2$d))",
                            FieldType.STRING_EXACT, "endsWith(%1$s, :filter%2$d)",
                            FieldType.STRING_STATE_DB, "%1$s LIKE CONCAT('%%', :filter%2$d)",
                            // MAP 值以 JSON 字符串存储，可能包含转义引号（例如 "\"hello\""）
                            // 先用 replaceAll 移除转义引号，再用 trimBoth 去除剩余引号
                            FieldType.MAP,
                            "endsWith(lower(trimBoth(replaceAll(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), '\\\\\"', ''), '\"')), lower(:filter%2$d))",
                            FieldType.DICTIONARY,
                            "endsWith(lower(JSON_VALUE(%1$s, :filterKey%2$d)), lower(:filter%2$d))",
                            FieldType.DICTIONARY_STATE_DB,
                            "JSON_VALUE(%1$s, :filterKey%2$d) LIKE CONCAT('%%', :filter%2$d)")))
                    .put(Operator.EQUAL, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.STRING, "lower(%1$s) = lower(:filter%2$d)"),
                            Map.entry(FieldType.STRING_EXACT, "%1$s = :filter%2$d"),
                            Map.entry(FieldType.STRING_STATE_DB, "lower(%1$s) = lower(:filter%2$d)"),
                            Map.entry(FieldType.DATE_TIME, "%1$s = parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s = :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s = :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s = :filter%2$d"),
                            Map.entry(FieldType.LIST, "has(%1$s, :filter%2$d)"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "has(groupArray(tuple(lower(name), %1$s)), tuple(lower(:filterKey%2$d), toDecimal64(:filter%2$d, 9))) = 1"),
                            Map.entry(FieldType.DICTIONARY,
                                    "lower(JSON_VALUE(%1$s, :filterKey%2$d)) = lower(:filter%2$d)"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "lower(JSON_VALUE(%1$s, :filterKey%2$d)) = lower(:filter%2$d)"),
                            // MAP 值以 JSON 字符串存储，可能包含转义引号（例如 "\"hello\""）
                            // 先用 replaceAll 移除转义引号，再用 trimBoth 去除剩余引号
                            Map.entry(FieldType.MAP,
                                    "lower(trimBoth(replaceAll(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), '\\\\\"', ''), '\"')) = lower(:filter%2$d)"),
                            Map.entry(FieldType.ENUM, "%1$s = :filter%2$d"),
                            Map.entry(FieldType.ENUM_LEGACY, "(%1$s = :filter%2$d OR %1$s = '%3$s')"))))
                    .put(Operator.NOT_EQUAL, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.STRING, "lower(%1$s) != lower(:filter%2$d)"),
                            Map.entry(FieldType.STRING_EXACT, "%1$s != :filter%2$d"),
                            Map.entry(FieldType.STRING_STATE_DB, "lower(%1$s) != lower(:filter%2$d)"),
                            Map.entry(FieldType.DATE_TIME, "%1$s != parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s != :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s != :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s != :filter%2$d"),
                            Map.entry(FieldType.LIST, "NOT has(%1$s, :filter%2$d)"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "has(groupArray(tuple(lower(name), %1$s)), tuple(lower(:filterKey%2$d), toDecimal64(:filter%2$d, 9))) = 0"),
                            Map.entry(FieldType.DICTIONARY,
                                    "lower(JSON_VALUE(%1$s, :filterKey%2$d)) != lower(:filter%2$d)"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "lower(JSON_VALUE(%1$s, :filterKey%2$d)) != lower(:filter%2$d)"),
                            // MAP 值以 JSON 字符串存储，可能包含转义引号（例如 "\"hello\""）
                            // 先用 replaceAll 移除转义引号，再用 trimBoth 去除剩余引号
                            Map.entry(FieldType.MAP,
                                    "lower(trimBoth(replaceAll(arrayElement(mapValues(%1$s),indexOf(mapKeys(%1$s), :filterKey%2$d)), '\\\\\"', ''), '\"')) != lower(:filter%2$d)"),
                            Map.entry(FieldType.ENUM, "%1$s != :filter%2$d"),
                            Map.entry(FieldType.ENUM_LEGACY, "(%1$s != :filter%2$d AND %1$s != '%3$s')"))))
                    .put(Operator.GREATER_THAN, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.STRING, "lower(%1$s) > lower(:filter%2$d)"),
                            Map.entry(FieldType.STRING_EXACT, "%1$s > :filter%2$d"),
                            Map.entry(FieldType.DATE_TIME, "%1$s > parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s > :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s > :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s > :filter%2$d"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 > toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1"),
                            Map.entry(FieldType.DICTIONARY,
                                    "toFloat64OrNull(JSON_VALUE(%1$s, :filterKey%2$d)) > toFloat64OrNull(:filter%2$d)"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "JSON_VALUE(%1$s, :filterKey%2$d RETURNING DOUBLE NULL ON EMPTY NULL ON ERROR) > CAST(:filter%2$d AS DOUBLE)"))))
                    .put(Operator.GREATER_THAN_EQUAL, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.DATE_TIME, "%1$s >= parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s >= :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s >= :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s >= :filter%2$d"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 >= toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "JSON_VALUE(%1$s, :filterKey%2$d RETURNING DOUBLE NULL ON EMPTY NULL ON ERROR) >= CAST(:filter%2$d AS DOUBLE)"))))
                    .put(Operator.LESS_THAN, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.STRING, "lower(%1$s) < lower(:filter%2$d)"),
                            Map.entry(FieldType.STRING_EXACT, "%1$s < :filter%2$d"),
                            Map.entry(FieldType.DATE_TIME, "%1$s < parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s < :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s < :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s < :filter%2$d"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 < toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1"),
                            Map.entry(FieldType.DICTIONARY,
                                    "toFloat64OrNull(JSON_VALUE(%1$s, :filterKey%2$d)) < toFloat64OrNull(:filter%2$d)"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "JSON_VALUE(%1$s, :filterKey%2$d RETURNING DOUBLE NULL ON EMPTY NULL ON ERROR) < CAST(:filter%2$d AS DOUBLE)"))))
                    .put(Operator.LESS_THAN_EQUAL, new EnumMap<>(Map.ofEntries(
                            Map.entry(FieldType.DATE_TIME, "%1$s <= parseDateTime64BestEffort(:filter%2$d, 9)"),
                            Map.entry(FieldType.DATE_TIME_STATE_DB, "%1$s <= :filter%2$d"),
                            Map.entry(FieldType.NUMBER, "%1$s <= :filter%2$d"),
                            Map.entry(FieldType.DURATION, "%1$s <= :filter%2$d"),
                            Map.entry(FieldType.FEEDBACK_SCORES_NUMBER,
                                    "arrayExists(element -> (element.1 = lower(:filterKey%2$d) AND element.2 <= toDecimal64(:filter%2$d, 9)), groupArray(tuple(lower(name), %1$s))) = 1"),
                            Map.entry(FieldType.DICTIONARY_STATE_DB,
                                    "JSON_VALUE(%1$s, :filterKey%2$d RETURNING DOUBLE NULL ON EMPTY NULL ON ERROR) <= CAST(:filter%2$d AS DOUBLE)"))))
                    .put(Operator.IS_EMPTY, new EnumMap<>(Map.of(
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "empty(arrayFilter(element -> (element = lower(:filterKey%2$d)), groupArray(lower(name)))) = 0",
                            FieldType.ERROR_CONTAINER,
                            "empty(%1$s)",
                            FieldType.LIST,
                            "empty(%1$s)",
                            FieldType.DICTIONARY,
                            "(JSON_EXISTS(%1$s, :filterKey%2$d) = false OR JSON_VALUE(%1$s, :filterKey%2$d) = '' OR JSON_VALUE(%1$s, :filterKey%2$d) = 'null')",
                            FieldType.DICTIONARY_STATE_DB,
                            "(JSON_EXISTS(%1$s, :filterKey%2$d) = false OR JSON_VALUE(%1$s, :filterKey%2$d) = '' OR JSON_VALUE(%1$s, :filterKey%2$d) = 'null')",
                            FieldType.ENUM,
                            "empty(%1$s)")))
                    .put(Operator.IS_NOT_EMPTY, new EnumMap<>(Map.of(
                            FieldType.FEEDBACK_SCORES_NUMBER,
                            "empty(arrayFilter(element -> (element = lower(:filterKey%2$d)), groupArray(lower(name)))) = 0",
                            FieldType.ERROR_CONTAINER,
                            "notEmpty(%1$s)",
                            FieldType.LIST,
                            "notEmpty(%1$s)",
                            FieldType.DICTIONARY,
                            "(JSON_EXISTS(%1$s, :filterKey%2$d) = true AND JSON_VALUE(%1$s, :filterKey%2$d) != '' AND JSON_VALUE(%1$s, :filterKey%2$d) != 'null')",
                            FieldType.DICTIONARY_STATE_DB,
                            "(JSON_EXISTS(%1$s, :filterKey%2$d) = true AND JSON_VALUE(%1$s, :filterKey%2$d) != '' AND JSON_VALUE(%1$s, :filterKey%2$d) != 'null')",
                            FieldType.ENUM,
                            "notEmpty(%1$s)")))
                    .put(Operator.IN, new EnumMap<>(Map.of(
                            FieldType.ENUM, "%1$s IN :filter%2$d",
                            FieldType.STRING_LIST, "%1$s IN :filter%2$d")))
                    .put(Operator.NOT_IN, new EnumMap<>(Map.of(
                            FieldType.ENUM, "%1$s NOT IN :filter%2$d",
                            FieldType.STRING_LIST, "%1$s NOT IN :filter%2$d")))
                    .build());

    private static final Map<TraceField, String> TRACE_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<TraceField, String>builder()
                    .put(TraceField.ID, ID_DB)
                    .put(TraceField.NAME, NAME_DB)
                    .put(TraceField.START_TIME, START_TIME_ANALYTICS_DB)
                    .put(TraceField.END_TIME, END_TIME_ANALYTICS_DB)
                    .put(TraceField.INPUT, INPUT_ANALYTICS_DB)
                    .put(TraceField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .put(TraceField.INPUT_JSON, INPUT_ANALYTICS_DB)
                    .put(TraceField.OUTPUT_JSON, OUTPUT_ANALYTICS_DB)
                    .put(TraceField.METADATA, METADATA_ANALYTICS_DB)
                    .put(TraceField.TOTAL_ESTIMATED_COST, TOTAL_ESTIMATED_COST_ANALYTICS_DB)
                    .put(TraceField.LLM_SPAN_COUNT, LLM_SPAN_COUNT_ANALYTICS_DB)
                    .put(TraceField.TAGS, TAGS_DB)
                    .put(TraceField.USAGE_COMPLETION_TOKENS, USAGE_COMPLETION_TOKENS_ANALYTICS_DB)
                    .put(TraceField.USAGE_PROMPT_TOKENS, USAGE_PROMPT_TOKENS_ANALYTICS_DB)
                    .put(TraceField.USAGE_TOTAL_TOKENS, USAGE_TOTAL_TOKENS_ANALYTICS_DB)
                    .put(TraceField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(TraceField.SPAN_FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(TraceField.DURATION, DURATION_ANALYTICS_DB)
                    .put(TraceField.TTFT, TTFT_ANALYTICS_DB)
                    .put(TraceField.THREAD_ID, THREAD_ID_ANALYTICS_DB)
                    .put(TraceField.GUARDRAILS, GUARDRAILS_RESULT_DB)
                    .put(TraceField.VISIBILITY_MODE, VISIBILITY_MODE_DB)
                    .put(TraceField.ERROR_INFO, ERROR_INFO_DB)
                    .put(TraceField.ERROR_TYPE, ERROR_TYPE_DB)
                    .put(TraceField.ANNOTATION_QUEUE_IDS, ANNOTATION_QUEUE_IDS_ANALYTICS_DB)
                    .put(TraceField.EXPERIMENT_ID, EXPERIMENT_ID_DB)
                    .put(TraceField.EXPERIMENT_IDS, EXPERIMENT_ID_DB)
                    .put(TraceField.CREATED_AT, CREATED_AT_DB)
                    .put(TraceField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(TraceField.SOURCE, SOURCE_DB)
                    .put(TraceField.ENVIRONMENT, ENVIRONMENT_DB)
                    .build());

    private static final Map<TraceThreadField, String> TRACE_THREAD_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<TraceThreadField, String>builder()
                    .put(TraceThreadField.ID, ID_DB)
                    .put(TraceThreadField.NUMBER_OF_MESSAGES, NUMBER_OF_MESSAGES_ANALYTICS_DB)
                    .put(TraceThreadField.FIRST_MESSAGE, FIRST_MESSAGE_ANALYTICS_DB)
                    .put(TraceThreadField.LAST_MESSAGE, LAST_MESSAGE_ANALYTICS_DB)
                    .put(TraceThreadField.DURATION, DURATION_ANALYTICS_DB)
                    .put(TraceThreadField.CREATED_AT, CREATED_AT_DB)
                    .put(TraceThreadField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(TraceThreadField.START_TIME, START_TIME_ANALYTICS_DB)
                    .put(TraceThreadField.END_TIME, END_TIME_ANALYTICS_DB)
                    .put(TraceThreadField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(TraceThreadField.STATUS, STATUS_DB)
                    .put(TraceThreadField.TAGS, TAGS_DB)
                    .put(TraceThreadField.ANNOTATION_QUEUE_IDS, THREAD_ANNOTATION_QUEUE_IDS_ANALYTICS_DB)
                    .put(TraceThreadField.SOURCE, SOURCE_DB)
                    .put(TraceThreadField.ENVIRONMENT, ENVIRONMENT_DB)
                    .build());

    private static final Map<SpanField, String> SPAN_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<SpanField, String>builder()
                    .put(SpanField.ID, ID_DB)
                    .put(SpanField.NAME, NAME_DB)
                    .put(SpanField.START_TIME, START_TIME_ANALYTICS_DB)
                    .put(SpanField.END_TIME, END_TIME_ANALYTICS_DB)
                    .put(SpanField.CREATED_AT, CREATED_AT_DB)
                    .put(SpanField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(SpanField.INPUT, INPUT_ANALYTICS_DB)
                    .put(SpanField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .put(SpanField.INPUT_JSON, INPUT_ANALYTICS_DB)
                    .put(SpanField.OUTPUT_JSON, OUTPUT_ANALYTICS_DB)
                    .put(SpanField.METADATA, METADATA_ANALYTICS_DB)
                    .put(SpanField.MODEL, MODEL_ANALYTICS_DB)
                    .put(SpanField.PROVIDER, PROVIDER_ANALYTICS_DB)
                    .put(SpanField.TOTAL_ESTIMATED_COST, TOTAL_ESTIMATED_COST_ANALYTICS_DB)
                    .put(SpanField.TAGS, TAGS_DB)
                    .put(SpanField.USAGE_COMPLETION_TOKENS, USAGE_COMPLETION_TOKENS_ANALYTICS_DB)
                    .put(SpanField.USAGE_PROMPT_TOKENS, USAGE_PROMPT_TOKENS_ANALYTICS_DB)
                    .put(SpanField.USAGE_TOTAL_TOKENS, USAGE_TOTAL_TOKENS_ANALYTICS_DB)
                    .put(SpanField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(SpanField.DURATION, DURATION_ANALYTICS_DB)
                    .put(SpanField.TTFT, TTFT_ANALYTICS_DB)
                    .put(SpanField.ERROR_INFO, ERROR_INFO_DB)
                    .put(SpanField.ERROR_TYPE, ERROR_TYPE_DB)
                    .put(SpanField.TYPE, TYPE_ANALYTICS_DB)
                    .put(SpanField.TRACE_ID, TRACE_ID_DB)
                    .put(SpanField.SOURCE, SOURCE_DB)
                    .put(SpanField.ENVIRONMENT, ENVIRONMENT_DB)
                    .build());

    private static final Map<ExperimentField, String> EXPERIMENT_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<ExperimentField, String>builder()
                    .put(ExperimentField.METADATA, METADATA_ANALYTICS_DB)
                    .put(ExperimentField.DATASET_ID, DATASET_ID_ANALYTICS_DB)
                    .put(ExperimentField.PROMPT_IDS, PROMPT_IDS_ANALYTICS_DB)
                    .put(ExperimentField.TAGS, TAGS_DB)
                    .put(ExperimentField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(ExperimentField.EXPERIMENT_SCORES, VALUE_ANALYTICS_DB)
                    .build());

    private static final Map<OptimizationField, String> OPTIMIZATION_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<OptimizationField, String>builder()
                    .put(OptimizationField.METADATA, METADATA_ANALYTICS_DB)
                    .put(OptimizationField.DATASET_ID, DATASET_ID_ANALYTICS_DB)
                    .put(OptimizationField.PROJECT_ID, PROJECT_ID_DB)
                    .put(OptimizationField.STATUS, STATUS_DB)
                    .build());

    private static final Map<PromptField, String> PROMPT_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<PromptField, String>builder()
                    .put(PromptField.ID, ID_DB)
                    .put(PromptField.NAME, NAME_DB)
                    .put(PromptField.DESCRIPTION, DESCRIPTION_DB)
                    .put(PromptField.CREATED_AT, CREATED_AT_DB)
                    .put(PromptField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(PromptField.CREATED_BY, CREATED_BY_DB)
                    .put(PromptField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .put(PromptField.TAGS, TAGS_DB)
                    .put(PromptField.VERSION_COUNT, VERSION_COUNT_DB)
                    .put(PromptField.TEMPLATE_STRUCTURE, TEMPLATE_STRUCTURE_DB)
                    .build());

    private static final Map<PromptVersionField, String> PROMPT_VERSION_FIELDS_MAP = Map.ofEntries(
            Map.entry(PromptVersionField.ID, ID_DB),
            Map.entry(PromptVersionField.COMMIT, COMMIT_DB),
            Map.entry(PromptVersionField.VERSION_NUMBER, VERSION_NUMBER_DB),
            Map.entry(PromptVersionField.TEMPLATE, TEMPLATE_DB),
            Map.entry(PromptVersionField.CHANGE_DESCRIPTION, CHANGE_DESCRIPTION_DB),
            Map.entry(PromptVersionField.METADATA, METADATA_ANALYTICS_DB),
            Map.entry(PromptVersionField.TYPE, TYPE_DB),
            Map.entry(PromptVersionField.TAGS, TAGS_DB),
            Map.entry(PromptVersionField.CREATED_AT, CREATED_AT_DB),
            Map.entry(PromptVersionField.CREATED_BY, CREATED_BY_DB)).entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    // 将表别名作为前缀添加到数据库字段名
                    entry -> PROMPT_VERSIONS_FIELDS_PATTERN.formatted(entry.getValue())));

    private static final Map<DatasetField, String> DATASET_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<DatasetField, String>builder()
                    .put(DatasetField.ID, ID_DB)
                    .put(DatasetField.NAME, NAME_DB)
                    .put(DatasetField.DESCRIPTION, DESCRIPTION_DB)
                    .put(DatasetField.TAGS, TAGS_DB)
                    .put(DatasetField.CREATED_AT, CREATED_AT_DB)
                    .put(DatasetField.CREATED_BY, CREATED_BY_DB)
                    .put(DatasetField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(DatasetField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .put(DatasetField.LAST_CREATED_EXPERIMENT_AT, LAST_CREATED_EXPERIMENT_AT_DB)
                    .put(DatasetField.LAST_CREATED_OPTIMIZATION_AT, LAST_CREATED_OPTIMIZATION_AT_DB)
                    .put(DatasetField.TYPE, TYPE_DB)
                    .build());

    private static final Map<DatasetItemField, String> DATASET_ITEM_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<DatasetItemField, String>builder()
                    .put(DatasetItemField.ID, ID_DB)
                    .put(DatasetItemField.DATA, DATA_ANALYTICS_DB)
                    .put(DatasetItemField.FULL_DATA, FULL_DATA_ANALYTICS_DB)
                    .put(DatasetItemField.SOURCE, SOURCE_DB)
                    .put(DatasetItemField.TRACE_ID, TRACE_ID_DB)
                    .put(DatasetItemField.SPAN_ID, SPAN_ID_DB)
                    .put(DatasetItemField.TAGS, TAGS_DB)
                    .put(DatasetItemField.CREATED_AT, CREATED_AT_DB)
                    .put(DatasetItemField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(DatasetItemField.CREATED_BY, CREATED_BY_DB)
                    .put(DatasetItemField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .build());

    private static final Map<AnnotationQueueField, String> ANNOTATION_QUEUE_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<AnnotationQueueField, String>builder()
                    .put(AnnotationQueueField.ID, ID_DB)
                    .put(AnnotationQueueField.PROJECT_ID, PROJECT_ID_DB)
                    .put(AnnotationQueueField.NAME, NAME_DB)
                    .put(AnnotationQueueField.DESCRIPTION, DESCRIPTION_DB)
                    .put(AnnotationQueueField.INSTRUCTIONS, INSTRUCTIONS_DB)
                    .put(AnnotationQueueField.FEEDBACK_DEFINITION_NAMES, FEEDBACK_DEFINITIONS_DB)
                    .put(AnnotationQueueField.SCOPE, SCOPE_DB)
                    .put(AnnotationQueueField.CREATED_AT, CREATED_AT_DB)
                    .put(AnnotationQueueField.CREATED_BY, CREATED_BY_DB)
                    .put(AnnotationQueueField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(AnnotationQueueField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .build());

    private static final Map<AlertField, String> ALERT_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<AlertField, String>builder()
                    .put(AlertField.ID, ID_DB)
                    .put(AlertField.NAME, NAME_DB)
                    .put(AlertField.ALERT_TYPE, ALERT_TYPE_DB)
                    .put(AlertField.WEBHOOK_URL, WEBHOOK_URL_DB)
                    .put(AlertField.CREATED_AT, CREATED_AT_DB)
                    .put(AlertField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(AlertField.CREATED_BY, CREATED_BY_DB)
                    .put(AlertField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .build());

    private static final Map<DashboardField, String> DASHBOARD_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<DashboardField, String>builder()
                    .put(DashboardField.ID, ID_DB)
                    .put(DashboardField.NAME, NAME_DB)
                    .put(DashboardField.TYPE, TYPE_DB)
                    .put(DashboardField.SCOPE, SCOPE_DB)
                    .put(DashboardField.CREATED_AT, CREATED_AT_DB)
                    .put(DashboardField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(DashboardField.CREATED_BY, CREATED_BY_DB)
                    .put(DashboardField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .build());

    private static final Map<AutomationRuleEvaluatorField, String> AUTOMATION_RULE_EVALUATOR_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<AutomationRuleEvaluatorField, String>builder()
                    .put(AutomationRuleEvaluatorField.ID, String.format(AUTOMATION_RULE_TABLE_ALIAS, ID_DB))
                    .put(AutomationRuleEvaluatorField.NAME, String.format(AUTOMATION_RULE_TABLE_ALIAS, NAME_DB))
                    .put(AutomationRuleEvaluatorField.TYPE, String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, TYPE_DB))
                    .put(AutomationRuleEvaluatorField.ENABLED, String.format(AUTOMATION_RULE_TABLE_ALIAS, ENABLED_DB))
                    .put(AutomationRuleEvaluatorField.SAMPLING_RATE,
                            String.format(AUTOMATION_RULE_TABLE_ALIAS, SAMPLING_RATE_DB))
                    .put(AutomationRuleEvaluatorField.CREATED_AT,
                            String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, CREATED_AT_DB))
                    .put(AutomationRuleEvaluatorField.LAST_UPDATED_AT,
                            String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, LAST_UPDATED_AT_DB))
                    .put(AutomationRuleEvaluatorField.CREATED_BY,
                            String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, CREATED_BY_DB))
                    .put(AutomationRuleEvaluatorField.LAST_UPDATED_BY,
                            String.format(AUTOMATION_EVALUATOR_TABLE_ALIAS, LAST_UPDATED_BY_DB))
                    .build());

    private static final Map<ExperimentsComparisonValidKnownField, String> EXPERIMENTS_COMPARISON_FIELDS_MAP = new EnumMap<>(
            ImmutableMap.<ExperimentsComparisonValidKnownField, String>builder()
                    .put(ExperimentsComparisonValidKnownField.ID, ID_DB)
                    .put(ExperimentsComparisonValidKnownField.SOURCE, SOURCE_DB)
                    .put(ExperimentsComparisonValidKnownField.TRACE_ID, TRACE_ID_DB)
                    .put(ExperimentsComparisonValidKnownField.SPAN_ID, SPAN_ID_DB)
                    .put(ExperimentsComparisonValidKnownField.CREATED_AT, CREATED_AT_DB)
                    .put(ExperimentsComparisonValidKnownField.LAST_UPDATED_AT, LAST_UPDATED_AT_DB)
                    .put(ExperimentsComparisonValidKnownField.CREATED_BY, CREATED_BY_DB)
                    .put(ExperimentsComparisonValidKnownField.LAST_UPDATED_BY, LAST_UPDATED_BY_DB)
                    .put(ExperimentsComparisonValidKnownField.DURATION, NEW_DURATION_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES, VALUE_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.OUTPUT, OUTPUT_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.TOTAL_ESTIMATED_COST, TOTAL_ESTIMATED_COST_ANALYTICS_DB)
                    .put(ExperimentsComparisonValidKnownField.USAGE_TOTAL_TOKENS, USAGE_TOTAL_TOKENS_ANALYTICS_DB)
                    .build());

    private static final Map<FilterStrategy, Set<? extends Field>> FILTER_STRATEGY_MAP = createFilterStrategyMap();

    private static Map<FilterStrategy, Set<? extends Field>> createFilterStrategyMap() {
        Map<FilterStrategy, Set<? extends Field>> map = new EnumMap<>(FilterStrategy.class);

        map.put(FilterStrategy.TRACE, Set.of(
                TraceField.ID,
                TraceField.NAME,
                TraceField.START_TIME,
                TraceField.END_TIME,
                TraceField.CREATED_AT,
                TraceField.LAST_UPDATED_AT,
                TraceField.INPUT,
                TraceField.OUTPUT,
                TraceField.INPUT_JSON,
                TraceField.OUTPUT_JSON,
                TraceField.METADATA,
                TraceField.TAGS,
                TraceField.DURATION,
                TraceField.TTFT,
                TraceField.THREAD_ID,
                TraceField.GUARDRAILS,
                TraceField.VISIBILITY_MODE,
                TraceField.ERROR_INFO,
                TraceField.ERROR_TYPE,
                TraceField.SOURCE,
                TraceField.ENVIRONMENT,
                TraceThreadField.SOURCE,
                TraceThreadField.ENVIRONMENT));

        map.put(FilterStrategy.EXPERIMENT_AGGREGATION, Set.of(
                TraceField.EXPERIMENT_ID,
                TraceField.EXPERIMENT_IDS));

        map.put(FilterStrategy.TRACE_AGGREGATION, Set.of(
                TraceField.USAGE_COMPLETION_TOKENS,
                TraceField.USAGE_PROMPT_TOKENS,
                TraceField.USAGE_TOTAL_TOKENS,
                TraceField.TOTAL_ESTIMATED_COST,
                TraceField.LLM_SPAN_COUNT));

        map.put(FilterStrategy.ANNOTATION_AGGREGATION, Set.of(
                TraceField.ANNOTATION_QUEUE_IDS,
                TraceThreadField.ANNOTATION_QUEUE_IDS));

        map.put(FilterStrategy.SPAN, Set.of(
                SpanField.ID,
                SpanField.NAME,
                SpanField.START_TIME,
                SpanField.END_TIME,
                SpanField.CREATED_AT,
                SpanField.LAST_UPDATED_AT,
                SpanField.INPUT,
                SpanField.OUTPUT,
                SpanField.INPUT_JSON,
                SpanField.OUTPUT_JSON,
                SpanField.METADATA,
                SpanField.MODEL,
                SpanField.PROVIDER,
                SpanField.TOTAL_ESTIMATED_COST,
                SpanField.TAGS,
                SpanField.USAGE_COMPLETION_TOKENS,
                SpanField.USAGE_PROMPT_TOKENS,
                SpanField.USAGE_TOTAL_TOKENS,
                SpanField.DURATION,
                SpanField.TTFT,
                SpanField.ERROR_INFO,
                SpanField.ERROR_TYPE,
                SpanField.TYPE,
                SpanField.TRACE_ID,
                SpanField.SOURCE,
                SpanField.ENVIRONMENT));

        map.put(FilterStrategy.FEEDBACK_SCORES, Set.of(
                TraceField.FEEDBACK_SCORES,
                SpanField.FEEDBACK_SCORES,
                ExperimentsComparisonValidKnownField.FEEDBACK_SCORES,
                TraceThreadField.FEEDBACK_SCORES,
                ExperimentField.FEEDBACK_SCORES));

        map.put(FilterStrategy.FEEDBACK_SCORES_AGGREGATED, Set.of(
                ExperimentField.FEEDBACK_SCORES,
                ExperimentsComparisonValidKnownField.FEEDBACK_SCORES));

        map.put(FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES, Set.of(TraceField.SPAN_FEEDBACK_SCORES));

        map.put(FilterStrategy.SPAN_FEEDBACK_SCORES, Set.of(SpanField.FEEDBACK_SCORES));

        map.put(FilterStrategy.EXPERIMENT_SCORES, Set.of(ExperimentField.EXPERIMENT_SCORES));

        map.put(FilterStrategy.EXPERIMENT_SCORES_AGGREGATED, Set.of(ExperimentField.EXPERIMENT_SCORES));

        map.put(FilterStrategy.EXPERIMENT_ITEM, Set.of(
                ExperimentsComparisonValidKnownField.OUTPUT,
                ExperimentsComparisonValidKnownField.DURATION));

        map.put(FilterStrategy.EXPERIMENT, Set.of(
                ExperimentField.METADATA,
                ExperimentField.DATASET_ID,
                ExperimentField.PROMPT_IDS,
                ExperimentField.TAGS));

        map.put(FilterStrategy.PROMPT, Set.of(
                PromptField.ID,
                PromptField.NAME,
                PromptField.DESCRIPTION,
                PromptField.CREATED_AT,
                PromptField.LAST_UPDATED_AT,
                PromptField.CREATED_BY,
                PromptField.LAST_UPDATED_BY,
                PromptField.TAGS,
                PromptField.VERSION_COUNT,
                PromptField.TEMPLATE_STRUCTURE));

        map.put(FilterStrategy.PROMPT_VERSION, Set.of(
                PromptVersionField.ID,
                PromptVersionField.COMMIT,
                PromptVersionField.VERSION_NUMBER,
                PromptVersionField.TEMPLATE,
                PromptVersionField.CHANGE_DESCRIPTION,
                PromptVersionField.TYPE,
                PromptVersionField.TAGS,
                PromptVersionField.CREATED_AT,
                PromptVersionField.CREATED_BY));

        map.put(FilterStrategy.DATASET, Set.of(
                DatasetField.ID,
                DatasetField.NAME,
                DatasetField.DESCRIPTION,
                DatasetField.TAGS,
                DatasetField.CREATED_AT,
                DatasetField.CREATED_BY,
                DatasetField.LAST_UPDATED_AT,
                DatasetField.LAST_UPDATED_BY,
                DatasetField.LAST_CREATED_EXPERIMENT_AT,
                DatasetField.LAST_CREATED_OPTIMIZATION_AT,
                DatasetField.TYPE));

        map.put(FilterStrategy.ANNOTATION_QUEUE, Set.of(
                AnnotationQueueField.ID,
                AnnotationQueueField.PROJECT_ID,
                AnnotationQueueField.NAME,
                AnnotationQueueField.DESCRIPTION,
                AnnotationQueueField.INSTRUCTIONS,
                AnnotationQueueField.FEEDBACK_DEFINITION_NAMES,
                AnnotationQueueField.SCOPE,
                AnnotationQueueField.CREATED_AT,
                AnnotationQueueField.CREATED_BY,
                AnnotationQueueField.LAST_UPDATED_AT,
                AnnotationQueueField.LAST_UPDATED_BY));

        map.put(FilterStrategy.TRACE_THREAD, Set.of(
                TraceThreadField.ID,
                TraceThreadField.NUMBER_OF_MESSAGES,
                TraceThreadField.FIRST_MESSAGE,
                TraceThreadField.LAST_MESSAGE,
                TraceThreadField.DURATION,
                TraceThreadField.CREATED_AT,
                TraceThreadField.LAST_UPDATED_AT,
                TraceThreadField.START_TIME,
                TraceThreadField.END_TIME,
                TraceThreadField.STATUS,
                TraceThreadField.TAGS));

        map.put(FilterStrategy.DATASET_ITEM, Set.of(
                DatasetItemField.ID,
                DatasetItemField.DATA,
                DatasetItemField.FULL_DATA,
                DatasetItemField.SOURCE,
                DatasetItemField.TRACE_ID,
                DatasetItemField.SPAN_ID,
                DatasetItemField.TAGS,
                DatasetItemField.CREATED_AT,
                DatasetItemField.LAST_UPDATED_AT,
                DatasetItemField.CREATED_BY,
                DatasetItemField.LAST_UPDATED_BY,
                // 同时包含实验条目的 ExperimentsComparisonValidKnownField 变体
                ExperimentsComparisonValidKnownField.ID,
                ExperimentsComparisonValidKnownField.SOURCE,
                ExperimentsComparisonValidKnownField.TRACE_ID,
                ExperimentsComparisonValidKnownField.SPAN_ID,
                ExperimentsComparisonValidKnownField.CREATED_AT,
                ExperimentsComparisonValidKnownField.LAST_UPDATED_AT,
                ExperimentsComparisonValidKnownField.CREATED_BY,
                ExperimentsComparisonValidKnownField.LAST_UPDATED_BY));

        map.put(FilterStrategy.ALERT, Set.of(
                AlertField.ID,
                AlertField.NAME,
                AlertField.ALERT_TYPE,
                AlertField.WEBHOOK_URL,
                AlertField.CREATED_AT,
                AlertField.LAST_UPDATED_AT,
                AlertField.CREATED_BY,
                AlertField.LAST_UPDATED_BY));

        map.put(FilterStrategy.AUTOMATION_RULE_EVALUATOR, Set.of(
                AutomationRuleEvaluatorField.ID,
                AutomationRuleEvaluatorField.NAME,
                AutomationRuleEvaluatorField.TYPE,
                AutomationRuleEvaluatorField.ENABLED,
                AutomationRuleEvaluatorField.SAMPLING_RATE,
                AutomationRuleEvaluatorField.CREATED_AT,
                AutomationRuleEvaluatorField.LAST_UPDATED_AT,
                AutomationRuleEvaluatorField.CREATED_BY,
                AutomationRuleEvaluatorField.LAST_UPDATED_BY));

        map.put(FilterStrategy.OPTIMIZATION, Set.of(
                OptimizationField.METADATA,
                OptimizationField.DATASET_ID,
                OptimizationField.PROJECT_ID,
                OptimizationField.STATUS));

        map.put(FilterStrategy.DASHBOARD, Set.of(
                DashboardField.ID,
                DashboardField.NAME,
                DashboardField.TYPE,
                DashboardField.SCOPE,
                DashboardField.CREATED_AT,
                DashboardField.LAST_UPDATED_AT,
                DashboardField.CREATED_BY,
                DashboardField.LAST_UPDATED_BY));

        return map;
    }

    private static final Set<FieldType> KEY_SUPPORTED_FIELDS_SET = EnumSet.of(
            FieldType.DICTIONARY,
            FieldType.DICTIONARY_STATE_DB,
            FieldType.MAP,
            FieldType.FEEDBACK_SCORES_NUMBER);

    public Map<Field, List<Operator>> getUnSupportedOperators(@NonNull Field... fields) {
        return Arrays.stream(fields)
                .flatMap(field -> ANALYTICS_DB_OPERATOR_MAP.entrySet().stream()
                        .filter(entry -> !entry.getValue().containsKey(field.getType()))
                        .map(entry -> Map.entry(field, entry.getKey())))
                .collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toList())));
    }

    public Map<Field, List<Operator>> getSupportedOperators(@NonNull Field... fields) {
        return Arrays.stream(fields)
                .flatMap(field -> ANALYTICS_DB_OPERATOR_MAP.entrySet().stream()
                        .filter(entry -> entry.getValue().containsKey(field.getType()))
                        .map(entry -> Map.entry(field, entry.getKey())))
                .collect(groupingBy(Map.Entry::getKey, mapping(Map.Entry::getValue, toList())));
    }

    public static String toAnalyticsDbOperator(@NonNull Filter filter) {
        return ANALYTICS_DB_OPERATOR_MAP.get(filter.operator()).get(filter.field().getType());
    }

    private static String toAnalyticsDbOperator(@NonNull Filter filter, @NonNull FilterStrategy filterStrategy) {
        // 对于聚合反馈分数，使用 map 访问模式而不是 groupArray 模式
        if ((filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED
                || filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY)
                && filter.field().getType() == FieldType.FEEDBACK_SCORES_NUMBER) {
            return getAggregatedFeedbackScoresTemplate(filter.operator());
        }

        // 对于聚合实验分数（Map(String, Float64)），使用基于 Float64 的 map 访问模式
        if ((filterStrategy == FilterStrategy.EXPERIMENT_SCORES_AGGREGATED
                || filterStrategy == FilterStrategy.EXPERIMENT_SCORES_AGGREGATED_IS_EMPTY)
                && filter.field().getType() == FieldType.FEEDBACK_SCORES_NUMBER) {
            return getAggregatedExperimentScoresTemplate(filter.operator());
        }

        return ANALYTICS_DB_OPERATOR_MAP.get(filter.operator()).get(filter.field().getType());
    }

    private static String getAggregatedMapScoresTemplate(Operator operator, String valueCast) {
        return switch (operator) {
            case EQUAL ->
                "arrayExists(k -> lower(k) = lower(:filterKey%2$d) AND %1$s[k] = " + valueCast + ", mapKeys(%1$s))";
            case NOT_EQUAL ->
                "arrayExists(k -> lower(k) = lower(:filterKey%2$d) AND %1$s[k] != " + valueCast + ", mapKeys(%1$s))";
            case GREATER_THAN ->
                "arrayExists(k -> lower(k) = lower(:filterKey%2$d) AND %1$s[k] > " + valueCast + ", mapKeys(%1$s))";
            case GREATER_THAN_EQUAL ->
                "arrayExists(k -> lower(k) = lower(:filterKey%2$d) AND %1$s[k] >= " + valueCast + ", mapKeys(%1$s))";
            case LESS_THAN ->
                "arrayExists(k -> lower(k) = lower(:filterKey%2$d) AND %1$s[k] < " + valueCast + ", mapKeys(%1$s))";
            case LESS_THAN_EQUAL ->
                "arrayExists(k -> lower(k) = lower(:filterKey%2$d) AND %1$s[k] <= " + valueCast + ", mapKeys(%1$s))";
            case IS_EMPTY -> "NOT arrayExists(k -> lower(k) = lower(:filterKey%2$d), mapKeys(%1$s))";
            case IS_NOT_EMPTY -> "arrayExists(k -> lower(k) = lower(:filterKey%2$d), mapKeys(%1$s))";
            default -> throw new IllegalArgumentException(
                    "Unsupported operator for aggregated map scores: '%s'".formatted(operator));
        };
    }

    private static String getAggregatedFeedbackScoresTemplate(Operator operator) {
        return getAggregatedMapScoresTemplate(operator, "toDecimal64(:filter%2$d, 9)");
    }

    private static String getAggregatedExperimentScoresTemplate(Operator operator) {
        return getAggregatedMapScoresTemplate(operator, "toFloat64(:filter%2$d)");
    }

    public static Optional<Boolean> hasGuardrailsFilter(@NonNull List<? extends Filter> filters) {
        return hasField(filters, TraceField.GUARDRAILS);
    }

    public static Optional<Boolean> hasField(@NonNull List<? extends Filter> filters, @NonNull Field field) {
        return filters.stream()
                .filter(filter -> filter.field() == field)
                .findFirst()
                .map(filter -> true);
    }

    public static Optional<String> toAnalyticsDbFilters(
            @NonNull List<? extends Filter> filters, @NonNull FilterStrategy filterStrategy) {
        return toAnalyticsDbFilters(filters, filterStrategy, false);
    }

    /**
     * @param columnsNonNullable 为 {@code true} 时，过滤条件使用感知哨兵值的表达式，从而像 {@code NULL} 一样排除
     *                               缺失（纪元）值；调用方传入目标实体的切换标志（trace/thread 的
     *                               traceColumnsNonNullable，span 的 spanColumnsNonNullable），其他位置传
     *                               {@code false}。
     */
    public static Optional<String> toAnalyticsDbFilters(
            @NonNull List<? extends Filter> filters,
            @NonNull FilterStrategy filterStrategy,
            boolean columnsNonNullable) {
        var stringJoiner = new StringJoiner(" %s ".formatted(ANALYTICS_DB_AND_OPERATOR));
        stringJoiner.setEmptyValue("");
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            if (getFieldsByStrategy(filterStrategy, filter).orElse(Set.of()).contains(filter.field())
                    || filter.field().isDynamic(filterStrategy)) {
                stringJoiner.add(toAnalyticsDbFilter(filter, i, filterStrategy, columnsNonNullable));
            }
        }
        var analyticsDbFilters = stringJoiner.toString();
        return StringUtils.isBlank(analyticsDbFilters)
                ? Optional.empty()
                : Optional.of("(%s)".formatted(analyticsDbFilters));
    }

    /**
     * V2 客户端入口，与 {@link #toAnalyticsDbFilters} 对应，但发出的是 v2 ClickHouse 客户端的
     * {@code {name:Type}} 形式占位符，而不是 r2dbc 的 {@code :name} 形式。与 {@link #populateV2ClientParams}
     * 配对使用以完成对应的参数绑定。
     */
    public static Optional<String> toAnalyticsDbFiltersV2Client(
            @NonNull List<? extends Filter> filters, @NonNull FilterStrategy filterStrategy) {
        return toAnalyticsDbFiltersV2Client(filters, filterStrategy, false);
    }

    /**
     * @param columnsNonNullable 透传该参数，让 v2 客户端调用方可以像 r2dbc 路径一样选择感知哨兵值的
     *                               {@code end_time}；没有它，此入口就永远无法启用该标志。
     */
    public static Optional<String> toAnalyticsDbFiltersV2Client(
            @NonNull List<? extends Filter> filters, @NonNull FilterStrategy filterStrategy,
            boolean columnsNonNullable) {
        return toAnalyticsDbFilters(filters, filterStrategy, columnsNonNullable)
                .map(sql -> rewritePlaceholdersForV2Client(sql, filters));
    }

    private static Optional<Set<? extends Field>> getFieldsByStrategy(FilterStrategy filterStrategy, Filter filter) {
        // 我们只想在下面这种情况下应用 is empty 过滤
        if (filter.operator() == Operator.IS_EMPTY && filterStrategy == FilterStrategy.FEEDBACK_SCORES_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.FEEDBACK_SCORES));
        }

        if (filter.operator() == Operator.IS_EMPTY
                && filterStrategy == FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES));
        }

        if (filter.operator() == Operator.IS_EMPTY && filterStrategy == FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.SPAN_FEEDBACK_SCORES));
        }

        if (filter.operator() == Operator.IS_EMPTY && filterStrategy == FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.EXPERIMENT_SCORES));
        }

        if (filter.operator() == Operator.IS_EMPTY
                && filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.FEEDBACK_SCORES_AGGREGATED));
        }

        if (filter.operator() == Operator.IS_NOT_EMPTY
                && filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.FEEDBACK_SCORES_AGGREGATED));
        }

        if (filter.operator() == Operator.IS_EMPTY
                && filterStrategy == FilterStrategy.EXPERIMENT_SCORES_AGGREGATED_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.EXPERIMENT_SCORES_AGGREGATED));
        }

        if (filter.operator() == Operator.IS_NOT_EMPTY
                && filterStrategy == FilterStrategy.EXPERIMENT_SCORES_AGGREGATED_IS_EMPTY) {
            return Optional.of(FILTER_STRATEGY_MAP.get(FilterStrategy.EXPERIMENT_SCORES_AGGREGATED));
        }

        // 跳过 FEEDBACK_SCORES_AGGREGATED 的 IS_NOT_EMPTY — 它由 FEEDBACK_SCORES_AGGREGATED_IS_EMPTY 处理
        if (filter.operator() == Operator.IS_NOT_EMPTY && isFeedbackScore(filter)
                && filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED) {
            return Optional.empty();
        }

        // 跳过 EXPERIMENT_SCORES_AGGREGATED 的 IS_NOT_EMPTY — 它由 EXPERIMENT_SCORES_AGGREGATED_IS_EMPTY 处理
        if (filter.operator() == Operator.IS_NOT_EMPTY && isFeedbackScore(filter)
                && filterStrategy == FilterStrategy.EXPERIMENT_SCORES_AGGREGATED) {
            return Optional.empty();
        }

        // 跳过 FEEDBACK_SCORES_AGGREGATED_IS_EMPTY 的数值运算符 — 那里只处理 IS_EMPTY/IS_NOT_EMPTY
        if (filter.operator() != Operator.IS_EMPTY && filter.operator() != Operator.IS_NOT_EMPTY
                && isFeedbackScore(filter)
                && filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY) {
            return Optional.empty();
        }

        // 跳过 EXPERIMENT_SCORES_AGGREGATED_IS_EMPTY 的数值运算符 — 那里只处理 IS_EMPTY/IS_NOT_EMPTY
        if (filter.operator() != Operator.IS_EMPTY && filter.operator() != Operator.IS_NOT_EMPTY
                && isFeedbackScore(filter)
                && filterStrategy == FilterStrategy.EXPERIMENT_SCORES_AGGREGATED_IS_EMPTY) {
            return Optional.empty();
        }

        if (isNotEmptyScoresFilter(filterStrategy, filter)) {
            return Optional.empty();
        }

        // 仅允许 _IS_EMPTY 策略使用 IS_EMPTY（不允许常规的 AGGREGATED 策略）
        if (filter.operator() == Operator.IS_EMPTY && isFeedbackScore(filter)
                && filterStrategy != FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY
                && filterStrategy != FilterStrategy.EXPERIMENT_SCORES_AGGREGATED_IS_EMPTY) {
            return Optional.empty();
        }

        return Optional.ofNullable(FILTER_STRATEGY_MAP.get(filterStrategy));
    }

    private static boolean isNotEmptyScoresFilter(FilterStrategy filterStrategy, Filter filter) {
        return filter.operator() == Operator.IS_NOT_EMPTY
                && Set.of(FilterStrategy.FEEDBACK_SCORES_IS_EMPTY, FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES_IS_EMPTY,
                        FilterStrategy.SPAN_FEEDBACK_SCORES_IS_EMPTY, FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY)
                        .contains(filterStrategy);
    }

    private static boolean isFeedbackScore(Filter filter) {
        return FEEDBACK_SCORE_FIELDS.contains(filter.field());
    }

    private static String toAnalyticsDbFilter(
            Filter filter, int i, FilterStrategy filterStrategy, boolean columnsNonNullable) {
        var template = toAnalyticsDbOperator(filter, filterStrategy);
        var dbField = getAnalyticsDbField(filter.field(), filterStrategy, i, columnsNonNullable);
        var enumFallbackTemplate = ANALYTICS_DB_OPERATOR_MAP.get(filter.operator()).get(FieldType.ENUM);
        return filter.field().getType().buildFilter(template, dbField, i, filter.value(), enumFallbackTemplate);
    }

    private static String getAnalyticsDbField(
            Field field, FilterStrategy filterStrategy, int i, boolean columnsNonNullable) {
        // 将 end_time 解析为感知哨兵值的表达式，从而像 NULL 一样排除缺失（纪元）值。受标志门控
        // （列仍为 Nullable 时纪元是合法值）；调用方传入其实体的切换标志（trace/thread 的
        // traceColumnsNonNullable，span 的 spanColumnsNonNullable）。
        if (columnsNonNullable && END_TIME_SENTINEL_FIELDS.contains(field)) {
            return END_TIME_NON_NULLABLE_ANALYTICS_DB;
        }

        // 这是一个特殊情况：数据库字段由过滤策略决定，而不是由过滤字段决定
        if (filterStrategy == FilterStrategy.FEEDBACK_SCORES_IS_EMPTY) {
            return FEEDBACK_SCORE_COUNT_DB;
        }

        if (filterStrategy == FilterStrategy.TRACE_SPAN_FEEDBACK_SCORES_IS_EMPTY) {
            return SPAN_FEEDBACK_SCORE_COUNT_DB;
        }

        if (filterStrategy == FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY) {
            return EXPERIMENT_SCORE_COUNT_DB;
        }

        // 对于聚合反馈分数，根据上下文使用合适的列
        // ExperimentField.FEEDBACK_SCORES -> experiment_aggregates 表使用 feedback_scores_avg
        if ((filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED
                || filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY)
                && field == ExperimentField.FEEDBACK_SCORES) {
            return "feedback_scores_avg";
        }

        // ExperimentsComparisonValidKnownField.FEEDBACK_SCORES -> experiment_item_aggregates 表使用 feedback_scores
        if ((filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED
                || filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY)
                && field == ExperimentsComparisonValidKnownField.FEEDBACK_SCORES) {
            return "feedback_scores";
        }

        if ((filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED
                || filterStrategy == FilterStrategy.FEEDBACK_SCORES_AGGREGATED_IS_EMPTY)
                && field == ExperimentField.EXPERIMENT_SCORES) {
            return "experiment_scores";
        }

        // experiment_aggregates.experiment_scores 是 Map(String, Float64)；使用别名限定，以避免与
        // 同一 FROM 子句中的 experiments.experiment_scores（JSON 二进制大对象）产生歧义
        if ((filterStrategy == FilterStrategy.EXPERIMENT_SCORES_AGGREGATED
                || filterStrategy == FilterStrategy.EXPERIMENT_SCORES_AGGREGATED_IS_EMPTY)
                && field == ExperimentField.EXPERIMENT_SCORES) {
            return "agg.experiment_scores";
        }

        return switch (field) {
            case TraceField traceField -> TRACE_FIELDS_MAP.get(traceField);
            case SpanField spanField -> SPAN_FIELDS_MAP.get(spanField);
            case ExperimentField experimentField -> EXPERIMENT_FIELDS_MAP.get(experimentField);
            case ExperimentsComparisonValidKnownField experimentsComparisonValidKnownField ->
                EXPERIMENTS_COMPARISON_FIELDS_MAP.get(experimentsComparisonValidKnownField);
            case TraceThreadField traceThreadField -> TRACE_THREAD_FIELDS_MAP.get(traceThreadField);
            case PromptField promptField -> PROMPT_FIELDS_MAP.get(promptField);
            case PromptVersionField promptVersionField -> PROMPT_VERSION_FIELDS_MAP.get(promptVersionField);
            case DatasetField datasetField -> DATASET_FIELDS_MAP.get(datasetField);
            case DatasetItemField datasetItemField -> DATASET_ITEM_FIELDS_MAP.get(datasetItemField);
            case AnnotationQueueField annotationQueueField -> ANNOTATION_QUEUE_FIELDS_MAP.get(annotationQueueField);
            case AlertField alertField -> ALERT_FIELDS_MAP.get(alertField);
            case AutomationRuleEvaluatorField automationRuleEvaluatorField ->
                AUTOMATION_RULE_EVALUATOR_FIELDS_MAP.get(automationRuleEvaluatorField);
            case OptimizationField optimizationField -> OPTIMIZATION_FIELDS_MAP.get(optimizationField);
            case DashboardField dashboardField -> DASHBOARD_FIELDS_MAP.get(dashboardField);
            default -> {

                if (field.isDynamic(filterStrategy)) {
                    yield filterStrategy.dbFormattedField(field).formatted(i);
                }

                throw new IllegalArgumentException(
                        "Unknown type for field '%s', type '%s'".formatted(field, field.getClass()));
            }
        };
    }

    public static Statement bind(
            @NonNull Statement statement,
            @NonNull List<? extends Filter> filters,
            @NonNull FilterStrategy filterStrategy) {
        bindUsing(statement::bind, filters, filterStrategy);
        return statement;
    }

    /**
     * V2 客户端入口：生成与 {@link #bind(Statement, List, FilterStrategy)} 相同的过滤参数，但为 v2 ClickHouse
     * 客户端的 {@code query(sql, params, settings)} API 填充 {@code Map<String, Object>}，而不是绑定到
     * {@link Statement}。
     *
     * <p>多值运算符的值被预渲染为 ClickHouse 数组字面量字符串（例如 {@code ['a','b']}），因为 v2 客户端通过
     * {@code String.valueOf} 序列化 Map 值，否则会发出一个未加引号的 Java 数组 {@code [a, b]}。
     *
     * <p>与 {@link #toAnalyticsDbFiltersV2Client} 配对使用以获取匹配的 SQL 片段。
     */
    public static void populateV2ClientParams(
            @NonNull Map<String, Object> params,
            @NonNull List<? extends Filter> filters,
            @NonNull FilterStrategy filterStrategy) {
        bindUsing((name, value) -> {
            if (value instanceof String[] arr) {
                params.put(name, formatStringArrayLiteral(Arrays.asList(arr)));
            } else {
                params.put(name, value);
            }
        }, filters, filterStrategy);
    }

    /**
     * 上述 r2dbc {@link Statement} 和 v2 客户端 {@code Map<String, Object>} 入口共用的核心绑定逻辑。
     * 遍历过滤条件，并通过提供的 {@code binder} 发出参数 (name, value) 对。
     */
    private static void bindUsing(
            @NonNull BiConsumer<String, Object> binder,
            @NonNull List<? extends Filter> filters,
            @NonNull FilterStrategy filterStrategy) {
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            if (getFieldsByStrategy(filterStrategy, filter).orElse(Set.of()).contains(filter.field())
                    || filter.field().isDynamic(filterStrategy)) {

                if (filter.field().isDynamic(filterStrategy)) {
                    String fieldName = filter.field().getQueryParamField();

                    // 对于 EXPERIMENT_ITEM，把 "output.some_field" 这类字段拆分为列名和 JSON 路径
                    // 只绑定 JSON 路径（列名已嵌入到 SQL 模板中）
                    if (filterStrategy == FilterStrategy.EXPERIMENT_ITEM && fieldName.contains(".")) {
                        int firstDot = fieldName.indexOf('.');
                        String jsonKey = fieldName.substring(firstDot + 1);
                        String jsonPath = JSONPATH_ROOT + "." + jsonKey;

                        binder.accept("dynamicJsonPath%d".formatted(i), jsonPath);
                    } else if (filterStrategy == FilterStrategy.DATASET_ITEM && fieldName.contains(".")) {
                        // 对于 DATASET_ITEM，"data.expected_answer" 这类字段映射到 data['expected_answer']
                        // 提取键名（第一个点之后的部分）并绑定它
                        int firstDot = fieldName.indexOf('.');
                        String keyName = fieldName.substring(firstDot + 1);

                        binder.accept("dynamicField%d".formatted(i), keyName);
                    } else if (filterStrategy == FilterStrategy.PROMPT_VERSION && fieldName.contains(".")) {
                        var jsonPath = getStateSQLJsonPath(fieldName);
                        binder.accept("dynamicJsonPath%d".formatted(i), jsonPath);
                    } else {
                        // 其他策略的默认动态字段绑定
                        binder.accept("dynamicField%d".formatted(i), fieldName);
                    }
                }

                if (!NO_VALUE_OPERATORS.contains(filter.operator())) {
                    if (Operator.MULTI_VALUE_OPERATORS.contains(filter.operator())) {
                        // 用于 IN/NOT_IN（ENUM、STRING_LIST）的逗号分隔值；以 String[] 绑定。
                        // 防御性地去除空白和空 token，以应对客户端输入中可能混入的空格或尾随逗号。
                        binder.accept("filter%d".formatted(i),
                                Arrays.stream(filter.value().split(","))
                                        .map(String::trim)
                                        .filter(StringUtils::isNotEmpty)
                                        .toArray(String[]::new));
                    } else {
                        binder.accept("filter%d".formatted(i), filter.value());
                    }
                }

                if (StringUtils.isNotBlank(filter.key())
                        && KEY_SUPPORTED_FIELDS_SET.contains(filter.field().getType())) {
                    var key = getKey(filter);
                    binder.accept("filterKey%d".formatted(i), key);
                }
            }
        }
    }

    /**
     * 将 {@link #toAnalyticsDbFilters} 发出的 r2dbc 风格 {@code :name} 占位符重写为 v2 ClickHouse 客户端的
     * {@code {name:Type}} 形式。多值运算符（{@code IN}、{@code NOT_IN}）类型化为 {@code Array(String)}；
     * 其他所有情况类型化为 {@code String}。
     *
     * <p>迭代顺序从最高的过滤索引到最低，这样像 {@code :filter12} 中的 {@code :filter1} 这类子串重叠会先解析为
     * 更长的名称。
     *
     * <p>{@link #toAnalyticsDbFiltersV2Client} 是把这个重写与 SQL 生成打包在一起的公开入口；本方法仅用于直接
     * 单元测试。
     *
     * @param sql     包含 {@code :name} 占位符的 SQL 片段
     * @param filters 用于构建 {@code sql} 的过滤条件；它们的顺序决定参数索引（{@code :filter0}、{@code :filter1}、…）
     */
    @VisibleForTesting
    static String rewritePlaceholdersForV2Client(@NonNull String sql, @NonNull List<? extends Filter> filters) {
        String out = sql;
        for (int i = filters.size() - 1; i >= 0; i--) {
            Filter filter = filters.get(i);
            String filterType = Operator.MULTI_VALUE_OPERATORS.contains(filter.operator())
                    ? "Array(String)"
                    : "String";
            out = out.replace(":dynamicJsonPath" + i, "{dynamicJsonPath" + i + ":String}");
            out = out.replace(":dynamicField" + i, "{dynamicField" + i + ":String}");
            out = out.replace(":filterKey" + i, "{filterKey" + i + ":String}");
            out = out.replace(":filter" + i, "{filter" + i + ":" + filterType + "}");
        }
        return out;
    }

    /**
     * 将字符串集合渲染为 ClickHouse 数组字面量，例如 {@code ['a','b']}。用于向 v2 ClickHouse 客户端绑定
     * {@code Array(String)} 参数时，因为该客户端通过 {@code String.valueOf} 序列化 Map 值，否则会发出一个
     * 服务器会拒绝的未加引号 Java 数组 {@code [a, b]}。
     *
     * <p>逐元素转义委托给 {@link ClickHouseUtil#escape(String)}，即上游 ClickHouse JDBC 辅助方法，
     * 它处理服务器接受的完整 C 风格转义集（反斜杠、单引号、反引号、换行、制表符等）。这通过发出
     * {@code \'} 和 {@code \\} 来封堵诸如 {@code x';DROP TABLE...} 和 {@code x\';...} 的注入向量。
     *
     * @throws NullPointerException 如果 {@code values} 为 null
     */
    public static String formatStringArrayLiteral(@NonNull Collection<@NonNull String> values) {
        return values.stream()
                .map(value -> "'" + ClickHouseUtil.escape(value) + "'")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /**
     * 将 {@link FilterStrategy} 映射到它所填充的 StringTemplate 参数名。
     */
    public record FilterStrategyParam(FilterStrategy strategy, String templateParam) {
    }

    /**
     * 将可配置的过滤策略列表应用到一个 StringTemplate。
     * 对于每个策略生成了非空 SQL 片段的条目，把该片段添加到对应的模板参数名下。
     *
     * @param template       要填充的 ST 模板
     * @param filters        调用方提供的过滤列表（可为 null 或空）
     * @param strategyParams 要评估的 (strategy, templateParam) 对的有序列表
     */
    public static void applyFiltersToTemplate(ST template, List<? extends Filter> filters,
            List<FilterStrategyParam> strategyParams) {
        if (CollectionUtils.isEmpty(filters)) {
            return;
        }
        for (var entry : strategyParams) {
            toAnalyticsDbFilters(filters, entry.strategy())
                    .ifPresent(sql -> template.add(entry.templateParam(), sql));
        }
    }

    /**
     * 为可配置的过滤策略列表把过滤参数绑定到一个 R2DBC 语句。
     *
     * @param statement  要绑定参数的语句
     * @param filters    调用方提供的过滤列表（可为 null 或空）
     * @param strategies 应当绑定参数的策略的有序列表
     * @return 已绑定所有参数的语句
     */
    public static Statement bindFilters(Statement statement, List<? extends Filter> filters,
            List<FilterStrategy> strategies) {
        if (CollectionUtils.isEmpty(filters)) {
            return statement;
        }
        for (var strategy : strategies) {
            statement = bind(statement, filters, strategy);
        }
        return statement;
    }

    public Map<String, Object> toStateSQLMapping(@NonNull List<? extends Filter> filters) {
        return toStateSQLMapping(filters, null);
    }

    public Map<String, Object> toStateSQLMapping(
            @NonNull List<? extends Filter> filters, FilterStrategy filterStrategy) {
        Map<String, Object> stateSQLMapping = new HashMap<>();
        for (var i = 0; i < filters.size(); i++) {
            var filter = filters.get(i);
            stateSQLMapping.put("filter%d".formatted(i), filter.value());

            // 处理动态字段
            if (filterStrategy != null && filter.field().isDynamic(filterStrategy)) {
                var fieldName = filter.field().getQueryParamField();
                if (filterStrategy == FilterStrategy.PROMPT_VERSION && fieldName.contains(".")) {
                    var jsonPath = getStateSQLJsonPath(fieldName);
                    stateSQLMapping.put("dynamicJsonPath%d".formatted(i), jsonPath);
                }
            }

            // 处理 DICTIONARY 字段的过滤键
            if (StringUtils.isNotBlank(filter.key())
                    && KEY_SUPPORTED_FIELDS_SET.contains(filter.field().getType())) {
                var key = getKey(filter);
                stateSQLMapping.put("filterKey%d".formatted(i), key);
            }
        }

        return stateSQLMapping;
    }

    /**
     * 为动态字段（通常是 metadata）生成用于状态数据库（MySQL）SQL 的 JSON 路径。
     * 把字段（例如 "metadata.environment"）拆分为 JSON 路径格式：$."environment"
     * 使用带引号的点号记法以处理包含空格和特殊字符的键。
     *
     * @param fieldName 完整字段名，如 "metadata.environment"
     * @return 格式为 $."key" 的 JSON 路径，例如 $."environment"
     */
    private static String getStateSQLJsonPath(String fieldName) {
        var jsonKey = fieldName.substring(fieldName.indexOf('.') + 1);
        return getSQLJsonPath(jsonKey);
    }

    private static String getSQLJsonPath(String jsonKey) {
        return "%s.\"%s\"".formatted(JSONPATH_ROOT, jsonKey);
    }

    /**
     * 解析作为 {@code :filterKey} 绑定的过滤键。
     * <p>
     * 分析数据库路径委托给 {@link JsonPathUtils#toAnalyticsDbJsonPath(String)}，这样持有未加引号点号记法无法
     * 表达的字符的键也能正常解析，而不会中止查询。状态数据库路径保持不变。
     */
    private static String getKey(Filter filter) {

        if (filter.field().getType() != FieldType.DICTIONARY
                && filter.field().getType() != FieldType.DICTIONARY_STATE_DB) {
            return filter.key();
        }

        if (filter.field().getType() == FieldType.DICTIONARY) {
            return JsonPathUtils.toAnalyticsDbJsonPath(filter.key());
        }

        if (filter.key().startsWith(JSONPATH_ROOT)) {
            return filter.key();
        }

        if (filter.key().startsWith("[") || filter.key().startsWith(".")) {
            return "%s%s".formatted(JSONPATH_ROOT, filter.key());
        }

        return getSQLJsonPath(filter.key());
    }

    /**
     * 为 DatasetItem JSON 字段（output、input、metadata）构建字段映射。
     * 这些字段在 ClickHouse 中以 JSON 字符串存储，因此我们需要使用 JSONExtractRaw 而不是方括号记法。
     * 我们使用字面量键而不是绑定参数，以避免动态字段的元组包装。
     * <p>
     * 这用于对 DatasetItem 字段进行排序。
     *
     * @param sortingFields 来自请求的排序字段
     * @return 从字段名到 ClickHouse SQL 表达式的映射
     */
    public Map<String, String> buildDatasetItemFieldMapping(@NonNull List<SortingField> sortingFields) {
        Map<String, String> fieldMapping = new HashMap<>();

        for (SortingField field : sortingFields) {
            String fieldName = field.field();

            // 检查这是否是 JSON 字段（output、input 或 metadata）
            // 使用字面量键而不是绑定参数，以避免动态字段处理
            if (fieldName.startsWith(OUTPUT_FIELD_PREFIX)) {
                String key = fieldName.substring(OUTPUT_FIELD_PREFIX.length());
                fieldMapping.put(fieldName,
                        JSON_EXTRACT_RAW_TEMPLATE.formatted("output", key));
            } else if (fieldName.startsWith(INPUT_FIELD_PREFIX)) {
                String key = fieldName.substring(INPUT_FIELD_PREFIX.length());
                fieldMapping.put(fieldName,
                        JSON_EXTRACT_RAW_TEMPLATE.formatted("input", key));
            } else if (fieldName.startsWith(METADATA_FIELD_PREFIX)) {
                String key = fieldName.substring(METADATA_FIELD_PREFIX.length());
                fieldMapping.put(fieldName,
                        JSON_EXTRACT_RAW_TEMPLATE.formatted("metadata", key));
            }
            // 对于其他字段（包括 feedback_scores、data 等），使用默认的 dbField()
        }

        return fieldMapping;
    }

    /**
     * 为 DatasetItem 搜索构建一个搜索过滤 SQL 条件。
     * 使用 multiSearchAnyCaseInsensitive 在 data 字段内进行搜索。
     *
     * @param searchText 搜索文本（非空白）
     * @return SQL 过滤条件字符串
     */
    public String buildDatasetItemSearchFilter(@NonNull String searchText) {
        return "multiSearchAnyCaseInsensitive(toString(data), :searchTerms) > 0";
    }

    /**
     * 把搜索词绑定到一个语句。
     * 按空白拆分搜索文本并绑定为数组。
     *
     * @param statement R2DBC 语句
     * @param searchText 要拆分并绑定的搜索文本
     * @return 已绑定搜索词的语句
     */
    public Statement bindSearchTerms(@NonNull Statement statement, @NonNull String searchText) {
        String[] searchTerms = searchText.split("\\s+");
        return statement.bind("searchTerms", searchTerms);
    }
}
