import i18next from "i18next";
import { CHART_TYPE } from "@/constants/chart";
import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import { ProjectMetricsWidget } from "@/types/dashboard";

const DEFAULT_TITLE_KEY = "metricsLabels.defaultTitle";

const getMetricLabels = (): Record<string, string> => {
  const t = i18next.getFixedT(null, "dashboards");
  return {
    [METRIC_NAME_TYPE.FEEDBACK_SCORES]: t("metrics.traceMetrics"),
    [METRIC_NAME_TYPE.TRACE_COUNT]: t("metrics.numberOfTraces"),
    [METRIC_NAME_TYPE.TRACE_DURATION]: t("metrics.traceDuration"),
    [METRIC_NAME_TYPE.TOKEN_USAGE]: t("metrics.tokenUsage"),
    [METRIC_NAME_TYPE.COST]: t("metrics.estimatedCost"),
    [METRIC_NAME_TYPE.FAILED_GUARDRAILS]: t("metrics.failedGuardrails"),
    [METRIC_NAME_TYPE.THREAD_COUNT]: t("metrics.numberOfThreads"),
    [METRIC_NAME_TYPE.THREAD_DURATION]: t("metrics.threadDuration"),
    [METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES]: t("metrics.threadMetrics"),
    [METRIC_NAME_TYPE.SPAN_COUNT]: t("metrics.numberOfSpans"),
    [METRIC_NAME_TYPE.SPAN_DURATION]: t("metrics.spanDuration"),
    [METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES]: t("metrics.spanMetrics"),
    [METRIC_NAME_TYPE.SPAN_TOKEN_USAGE]: t("metrics.spanTokenUsage"),
  };
};

const FEEDBACK_SCORE_METRIC_TYPES = [
  METRIC_NAME_TYPE.FEEDBACK_SCORES,
  METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
  METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES,
];

const DURATION_METRIC_TYPES = [
  METRIC_NAME_TYPE.TRACE_DURATION,
  METRIC_NAME_TYPE.THREAD_DURATION,
  METRIC_NAME_TYPE.SPAN_DURATION,
];

const DURATION_LABELS: Record<string, string> = {
  p50: "P50",
  p90: "P90",
  p99: "P99",
};

const TOKEN_USAGE_METRIC_TYPES = [
  METRIC_NAME_TYPE.TOKEN_USAGE,
  METRIC_NAME_TYPE.SPAN_TOKEN_USAGE,
];

const calculateProjectMetricsTitle = (
  config: Record<string, unknown>,
): string => {
  const t = i18next.getFixedT(null, "dashboards");
  const widgetConfig = config as ProjectMetricsWidget["config"];
  const metricType = widgetConfig.metricType;
  const defaultTitle = t(DEFAULT_TITLE_KEY);

  if (!metricType) {
    return defaultTitle;
  }

  const metricLabels = getMetricLabels();
  const baseTitle = metricLabels[metricType] || defaultTitle;

  // For feedback score metrics with exactly one score selected
  if (
    FEEDBACK_SCORE_METRIC_TYPES.includes(metricType as METRIC_NAME_TYPE) &&
    widgetConfig.feedbackScores?.length === 1
  ) {
    return `${baseTitle} - ${widgetConfig.feedbackScores[0]}`;
  }

  // For duration metrics with exactly one percentile selected
  if (
    DURATION_METRIC_TYPES.includes(metricType as METRIC_NAME_TYPE) &&
    widgetConfig.durationMetrics?.length === 1
  ) {
    const percentile = widgetConfig.durationMetrics[0];
    const percentileLabel =
      DURATION_LABELS[percentile] || percentile.toUpperCase();
    return `${baseTitle} - ${percentileLabel}`;
  }

  // For token usage metrics with exactly one usage key selected
  if (
    TOKEN_USAGE_METRIC_TYPES.includes(metricType as METRIC_NAME_TYPE) &&
    widgetConfig.usageMetrics?.length === 1
  ) {
    const usageKey = widgetConfig.usageMetrics[0];
    return `${baseTitle} - ${usageKey}`;
  }

  return baseTitle;
};

export const widgetHelpers = {
  getDefaultConfig: () => ({
    chartType: CHART_TYPE.line,
    metricType: METRIC_NAME_TYPE.TRACE_COUNT,
  }),
  calculateTitle: calculateProjectMetricsTitle,
};
