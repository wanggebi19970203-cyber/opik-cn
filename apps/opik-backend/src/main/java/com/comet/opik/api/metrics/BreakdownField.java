package com.comet.opik.api.metrics;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.Set;

/**
 * 仪表盘小部件指标支持的 group by 维度。
 * 每个字段代表一个可用于对指标进行分组的维度。
 * 与指标类型的兼容性基于实体类型：
 * - Trace 指标：DURATION, TRACE_COUNT, TOKEN_USAGE, COST, FEEDBACK_SCORES, GUARDRAILS_FAILED_COUNT
 * - Thread 指标：THREAD_COUNT, THREAD_DURATION, THREAD_FEEDBACK_SCORES
 * - Span 指标：SPAN_COUNT, SPAN_DURATION, SPAN_TOKEN_USAGE, SPAN_FEEDBACK_SCORES
 */
@RequiredArgsConstructor
@Getter
public enum BreakdownField {

    NONE("none", "No Grouping", false),
    TAGS("tags", "Tags", false),
    METADATA("metadata", "Metadata", true),
    NAME("name", "Name", false),
    ERROR_INFO("error_info", "Has Error", false),
    ERROR_TYPE("error_type", "Error Type", false),
    MODEL("model", "Model", false),
    PROVIDER("provider", "Provider", false),
    TYPE("type", "Span Type", false),
    GUARDRAIL_NAME("guardrail_name", "Guardrail name", false);

    @JsonValue
    private final String value;
    private final String displayName;
    private final boolean requiresKey;

    // 基于 Trace 的指标
    private static final Set<MetricType> TRACE_METRICS = EnumSet.of(
            MetricType.DURATION,
            MetricType.TRACE_COUNT,
            MetricType.TOKEN_USAGE,
            MetricType.COST,
            MetricType.FEEDBACK_SCORES,
            MetricType.GUARDRAILS_FAILED_COUNT);

    // 基于 Thread 的指标
    private static final Set<MetricType> THREAD_METRICS = EnumSet.of(
            MetricType.THREAD_COUNT,
            MetricType.THREAD_DURATION,
            MetricType.THREAD_FEEDBACK_SCORES);

    // 基于 Span 的指标
    public static final Set<MetricType> SPAN_METRICS = EnumSet.of(
            MetricType.SPAN_COUNT,
            MetricType.SPAN_DURATION,
            MetricType.SPAN_TOKEN_USAGE,
            MetricType.SPAN_FEEDBACK_SCORES);

    /**
     * 检查此 group by 字段是否与给定的指标类型兼容。
     * 基于 Jira 工单 OPIK-3790 "Supported Breakdown Fields" 表格：
     * - TAGS：Trace、Span、Thread
     * - METADATA：Trace、Span（不支持 Thread）
     * - NAME：Trace、Span（不支持 Thread）
     * - ERROR_INFO：Trace、Span（不支持 Thread）
     * - MODEL：仅 Span
     * - PROVIDER：仅 Span
     * - TYPE：仅 Span
     */
    public boolean isCompatibleWith(MetricType metricType) {
        if (this == NONE) {
            return true;
        }

        return switch (this) {
            case TAGS -> TRACE_METRICS.contains(metricType)
                    || SPAN_METRICS.contains(metricType)
                    || THREAD_METRICS.contains(metricType);
            case METADATA, NAME, ERROR_INFO, ERROR_TYPE -> TRACE_METRICS.contains(metricType)
                    || SPAN_METRICS.contains(metricType);
            case MODEL, PROVIDER, TYPE -> SPAN_METRICS.contains(metricType);
            case GUARDRAIL_NAME -> metricType == MetricType.GUARDRAILS_FAILED_COUNT;
            default -> false;
        };
    }

    /**
     * 获取关于哪些指标类型与此字段兼容的用户友好描述。
     */
    public String getCompatibleMetricTypesDescription() {
        return switch (this) {
            case NONE -> "all metrics";
            case TAGS -> "Trace, Span, and Thread metrics";
            case METADATA, NAME, ERROR_INFO, ERROR_TYPE -> "Trace and Span metrics";
            case MODEL, PROVIDER, TYPE -> "Span metrics only";
            case GUARDRAIL_NAME -> "the Failed guardrails metric only";
        };
    }
}
