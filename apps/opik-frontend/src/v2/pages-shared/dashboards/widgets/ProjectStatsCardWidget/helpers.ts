import i18next from "i18next";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import { ProjectStatsCardWidget } from "@/types/dashboard";

const FEEDBACK_SCORE_PREFIX = "feedback_scores.";

const getMetricTitleMap = (source: TRACE_DATA_TYPE): Record<string, string> => {
  const t = i18next.getFixedT(null, "dashboards");
  const isSpans = source === TRACE_DATA_TYPE.spans;
  const sourceLabel = t(
    isSpans
      ? "projectStatsCard.metricTitles.sourceSpan"
      : "projectStatsCard.metricTitles.sourceTrace",
  );

  return {
    trace_count: t("projectStatsCard.metricTitles.traceCount"),
    thread_count: t("projectStatsCard.metricTitles.threadCount"),
    span_count: isSpans
      ? t("projectStatsCard.metricTitles.spanCount")
      : t("projectStatsCard.metricTitles.averageSpanCount"),
    llm_span_count: t("projectStatsCard.metricTitles.llmSpanCount"),
    error_count: t("projectStatsCard.metricTitles.errorCount", {
      source: sourceLabel,
    }),
    "duration.p50": t("projectStatsCard.metricTitles.p50Duration", {
      source: sourceLabel,
    }),
    "duration.p90": t("projectStatsCard.metricTitles.p90Duration", {
      source: sourceLabel,
    }),
    "duration.p99": t("projectStatsCard.metricTitles.p99Duration", {
      source: sourceLabel,
    }),
    total_estimated_cost: isSpans
      ? t("projectStatsCard.metricTitles.avgCostPerSpan")
      : t("projectStatsCard.metricTitles.avgCostPerTrace"),
    total_estimated_cost_sum: t("projectStatsCard.metricTitles.totalCostSum", {
      source: sourceLabel,
    }),
    "usage.completion_tokens": t(
      "projectStatsCard.metricTitles.avgOutputTokens",
      { source: sourceLabel },
    ),
    "usage.prompt_tokens": t("projectStatsCard.metricTitles.avgInputTokens", {
      source: sourceLabel,
    }),
    "usage.total_tokens": t("projectStatsCard.metricTitles.avgTotalTokens", {
      source: sourceLabel,
    }),
    input: t("projectStatsCard.metricTitles.inputCount", {
      source: sourceLabel,
    }),
    output: t("projectStatsCard.metricTitles.outputCount", {
      source: sourceLabel,
    }),
    metadata: t("projectStatsCard.metricTitles.metadataCount", {
      source: sourceLabel,
    }),
    tags: t("projectStatsCard.metricTitles.tagsCount", {
      source: sourceLabel,
    }),
    guardrails_failed_count: t(
      "projectStatsCard.metricTitles.guardrailsFailed",
      { source: sourceLabel },
    ),
  };
};

const calculateProjectStatsCardTitle = (
  config: Record<string, unknown>,
): string => {
  const t = i18next.getFixedT(null, "dashboards");
  const widgetConfig = config as ProjectStatsCardWidget["config"];
  const source = widgetConfig.source;
  const metric = widgetConfig.metric;

  if (!metric) {
    return t("projectStatsCard.projectStatistics");
  }

  if (metric.startsWith(FEEDBACK_SCORE_PREFIX)) {
    const scoreName = metric.replace(FEEDBACK_SCORE_PREFIX, "");
    const isSpans = source === TRACE_DATA_TYPE.spans;
    return t("projectStatsCard.averageScore", {
      source: isSpans
        ? t("projectStatsCard.metricTitles.sourceSpan")
        : t("projectStatsCard.metricTitles.sourceTrace"),
      name: scoreName,
    });
  }

  const metricTitleMap = getMetricTitleMap(source);
  return metricTitleMap[metric] || t("projectStatsCard.projectStatistics");
};

export const widgetHelpers = {
  getDefaultConfig: () => ({
    source: TRACE_DATA_TYPE.traces,
    metric: "trace_count",
  }),
  calculateTitle: calculateProjectStatsCardTitle,
};
