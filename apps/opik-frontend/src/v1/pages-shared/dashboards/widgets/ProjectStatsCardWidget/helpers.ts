import i18next from "i18next";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { ProjectStatsCardWidget } from "@/types/dashboard";

const DEFAULT_TITLE = i18next.t("projectStats.projectStatistics");
const FEEDBACK_SCORE_PREFIX = "feedback_scores.";

const getMetricTitleMap = (source: TRACE_DATA_TYPE): Record<string, string> => {
  const isSpans = source === TRACE_DATA_TYPE.spans;
  const sourceLabel = isSpans ? "span" : "trace";

  return {
    trace_count: i18next.t("projectStats.totalTraceCount"),
    thread_count: i18next.t("projectStats.totalThreadCount"),
    span_count: isSpans ? i18next.t("projectStats.totalSpanCount") : i18next.t("projectStats.averageSpanCount"),
    llm_span_count: i18next.t("projectStats.averageLlmSpanCount"),
    error_count: i18next.t("projectStats.totalErrorCount", { source: sourceLabel }),
    "duration.p50": i18next.t("projectStats.p50Duration", { source: sourceLabel }),
    "duration.p90": i18next.t("projectStats.p90Duration", { source: sourceLabel }),
    "duration.p99": i18next.t("projectStats.p99Duration", { source: sourceLabel }),
    total_estimated_cost: isSpans ? i18next.t("projectStats.avgCostPerSpan") : i18next.t("projectStats.avgCostPerTrace"),
    total_estimated_cost_sum: i18next.t("projectStats.totalCostSum", { source: sourceLabel }),
    "usage.completion_tokens": i18next.t("projectStats.avgOutputTokens", { source: sourceLabel }),
    "usage.prompt_tokens": i18next.t("projectStats.avgInputTokens", { source: sourceLabel }),
    "usage.total_tokens": i18next.t("projectStats.avgTotalTokens", { source: sourceLabel }),
    input: i18next.t("projectStats.totalInputCount", { source: sourceLabel }),
    output: i18next.t("projectStats.totalOutputCount", { source: sourceLabel }),
    metadata: i18next.t("projectStats.totalMetadataCount", { source: sourceLabel }),
    tags: i18next.t("projectStats.avgTagsCount", { source: sourceLabel }),
    guardrails_failed_count: i18next.t("projectStats.totalGuardrailsFailed", { source: sourceLabel }),
  };
};

const calculateProjectStatsCardTitle = (
  config: Record<string, unknown>,
): string => {
  const widgetConfig = config as ProjectStatsCardWidget["config"];
  const source = widgetConfig.source;
  const metric = widgetConfig.metric;

  if (!metric) {
    return DEFAULT_TITLE;
  }

  if (metric.startsWith(FEEDBACK_SCORE_PREFIX)) {
    const scoreName = metric.replace(FEEDBACK_SCORE_PREFIX, "");
    const sourceLabel = source === TRACE_DATA_TYPE.spans ? "span" : "trace";
    return i18next.t("projectStats.averageFeedbackScore", { source: sourceLabel, scoreName });
  }

  const metricTitleMap = getMetricTitleMap(source);
  return metricTitleMap[metric] || DEFAULT_TITLE;
};

export const widgetHelpers = {
  getDefaultConfig: () => ({
    source: TRACE_DATA_TYPE.traces,
    metric: "trace_count",
  }),
  calculateTitle: calculateProjectStatsCardTitle,
};
