package com.comet.opik.api.metrics;

import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.api.metrics.BreakdownField.SPAN_METRICS;

public class BreakdownQueryBuilder {

    // 固定默认值 - 不可配置
    public static final int LIMIT = 10;
    public static final String OTHERS_GROUP_NAME = "__others__";
    public static final String UNKNOWN_GROUP_NAME = "Unknown";

    /**
     * 检查 breakdown 是否已启用（字段不是 NONE 且不为 null）。
     */
    public static boolean isEnabled(BreakdownConfig config) {
        return config.field() != null && config.field() != BreakdownField.NONE;
    }

    /**
     * 校验配置。
     *
     * @throws IllegalArgumentException 如果配置无效
     */
    public static void validate(BreakdownConfig config, MetricType metricType) {
        if (!isEnabled(config)) {
            return;
        }

        if (config.field() == BreakdownField.METADATA && StringUtils.isBlank(config.metadataKey())) {
            throw new IllegalArgumentException("metadata_key is required when group by field is 'metadata'");
        }

        if (!config.field().isCompatibleWith(metricType)) {
            throw new IllegalArgumentException(
                    "Group by field '%s' is not compatible with metric type '%s'. This field supports %s."
                            .formatted(config.field().getValue(), metricType.name(),
                                    config.field().getCompatibleMetricTypesDescription()));
        }
    }

    /**
     * 根据指标类型和 breakdown 字段获取 breakdown 分组的 SQL 表达式。
     * 该表达式用于 GROUP BY 子句，按指定维度对指标进行分组。
     * 表别名前缀取决于指标类型：
     * - Span 指标使用 's.' 前缀（spans_filtered）
     * - Trace/Thread 指标使用 't.' 前缀（traces_filtered/threads_filtered）
     */
    public static String getBreakdownGroupExpression(MetricType metricType, BreakdownConfig breakdown) {
        if (breakdown == null || !isEnabled(breakdown)) {
            return "''";
        }

        // Guardrail 名称位于联接的 guardrails 表（'g.'）中，独立于
        // 其他 breakdown 字段所使用的 span/trace/thread 别名。
        if (breakdown.field() == BreakdownField.GUARDRAIL_NAME) {
            return "ifNull(g.name, 'Unknown')";
        }

        // Span 指标使用 's.' 前缀，trace/thread 指标使用 't.' 前缀
        if (SPAN_METRICS.contains(metricType)) {
            return getSpanBreakdownExpression(breakdown);
        } else {
            return getTraceOrThreadBreakdownExpression(breakdown);
        }
    }

    /**
     * 将子指标名称（p50、p90、p99）映射到 ClickHouse 分位数值（0.5、0.9、0.99）。
     * 结果会作为数字字面量代入 SQL 中，因此该允许列表
     * 由前端输入枚举 {@code DURATION_METRIC_OPTIONS} 强制执行。
     */
    public static String mapQuantile(String subMetric) {
        return switch (subMetric.toLowerCase()) {
            case "p50" -> "0.5";
            case "p90" -> "0.9";
            case "p99" -> "0.99";
            default -> throw new BadRequestException("Invalid sub_metric: " + subMetric);
        };
    }

    /**
     * 使用 's.' 表别名获取 span 指标的 breakdown 表达式。
     */
    private static String getSpanBreakdownExpression(BreakdownConfig breakdown) {
        return switch (breakdown.field()) {
            case TAGS -> "arrayJoin(if(empty(s.tags), ['Unknown'], s.tags))";
            case METADATA -> "ifNull(JSONExtractString(s.metadata, :metadata_key), 'Unknown')";
            case NAME -> "ifNull(s.name, 'Unknown')";
            case ERROR_INFO -> "if(length(s.error_info) > 0, 'Has Error', 'No Error')";
            case ERROR_TYPE ->
                "if(length(s.error_info) > 0, ifNull(nullIf(simpleJSONExtractString(s.error_info, 'exception_type'), ''), 'Unknown Error'), 'No Error')";
            case MODEL -> "if(s.model = '', 'Unknown', s.model)";
            case PROVIDER -> "if(s.provider = '', 'Unknown', s.provider)";
            case TYPE -> "toString(s.type)";
            default -> "''";
        };
    }

    /**
     * 使用 't.' 表别名获取 trace/thread 指标的 breakdown 表达式。
     */
    private static String getTraceOrThreadBreakdownExpression(BreakdownConfig breakdown) {
        return switch (breakdown.field()) {
            case TAGS -> "arrayJoin(if(empty(t.tags), ['Unknown'], t.tags))";
            case METADATA -> "ifNull(JSONExtractString(t.metadata, :metadata_key), 'Unknown')";
            case NAME -> "ifNull(t.name, 'Unknown')";
            case ERROR_INFO -> "if(length(t.error_info) > 0, 'Has Error', 'No Error')";
            case ERROR_TYPE ->
                "if(length(t.error_info) > 0, ifNull(nullIf(simpleJSONExtractString(t.error_info, 'exception_type'), ''), 'Unknown Error'), 'No Error')";
            default -> "''";
        };
    }
}
