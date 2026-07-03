import i18next from "i18next";
import {
  STATISTIC_AGGREGATION_TYPE,
  COLUMN_FEEDBACK_SCORES_ID,
} from "@/types/shared";
import { formatNumericData } from "@/lib/utils";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import { TRACE_DATA_TYPE } from "@/constants/traces";

export type MetricDefinition = {
  value: string;
  label: string;
  type: STATISTIC_AGGREGATION_TYPE;
  statName: string;
  formatter: (value: number) => string;
  tooltipFormatter?: (value: number) => string;
};

export type MetricOption = {
  value: string;
  label: string;
};

const SHARED_METRICS: MetricDefinition[] = [
  {
    value: "duration.p50",
    label: i18next.t("dashboards.statsCard.metrics.p50Duration"),
    type: STATISTIC_AGGREGATION_TYPE.PERCENTAGE,
    statName: "duration",
    formatter: formatDuration,
  },
  {
    value: "duration.p90",
    label: i18next.t("dashboards.statsCard.metrics.p90Duration"),
    type: STATISTIC_AGGREGATION_TYPE.PERCENTAGE,
    statName: "duration",
    formatter: formatDuration,
  },
  {
    value: "duration.p99",
    label: i18next.t("dashboards.statsCard.metrics.p99Duration"),
    type: STATISTIC_AGGREGATION_TYPE.PERCENTAGE,
    statName: "duration",
    formatter: formatDuration,
  },
  {
    value: "input",
    label: i18next.t("dashboards.statsCard.metrics.totalInputCount"),
    type: STATISTIC_AGGREGATION_TYPE.COUNT,
    statName: "input",
    formatter: (value: number) => value.toLocaleString(),
  },
  {
    value: "output",
    label: i18next.t("dashboards.statsCard.metrics.totalOutputCount"),
    type: STATISTIC_AGGREGATION_TYPE.COUNT,
    statName: "output",
    formatter: (value: number) => value.toLocaleString(),
  },
  {
    value: "metadata",
    label: i18next.t("dashboards.statsCard.metrics.totalMetadataCount"),
    type: STATISTIC_AGGREGATION_TYPE.COUNT,
    statName: "metadata",
    formatter: (value: number) => value.toLocaleString(),
  },
  {
    value: "tags",
    label: i18next.t("dashboards.statsCard.metrics.averageNumberOfTags"),
    type: STATISTIC_AGGREGATION_TYPE.AVG,
    statName: "tags",
    formatter: formatNumericData,
    tooltipFormatter: String,
  },
  {
    value: "total_estimated_cost_sum",
    label: i18next.t("dashboards.statsCard.metrics.totalEstimatedCostSum"),
    type: STATISTIC_AGGREGATION_TYPE.AVG,
    statName: "total_estimated_cost_sum",
    formatter: formatCost,
    tooltipFormatter: (value: number) =>
      formatCost(value, { modifier: "full" }),
  },
  {
    value: "usage.completion_tokens",
    label: i18next.t("dashboards.statsCard.metrics.avgOutputTokens"),
    type: STATISTIC_AGGREGATION_TYPE.AVG,
    statName: "usage.completion_tokens",
    formatter: formatNumericData,
    tooltipFormatter: String,
  },
  {
    value: "usage.prompt_tokens",
    label: i18next.t("dashboards.statsCard.metrics.avgInputTokens"),
    type: STATISTIC_AGGREGATION_TYPE.AVG,
    statName: "usage.prompt_tokens",
    formatter: formatNumericData,
    tooltipFormatter: String,
  },
  {
    value: "usage.total_tokens",
    label: i18next.t("dashboards.statsCard.metrics.avgTotalTokens"),
    type: STATISTIC_AGGREGATION_TYPE.AVG,
    statName: "usage.total_tokens",
    formatter: formatNumericData,
    tooltipFormatter: String,
  },
  {
    value: "error_count",
    label: i18next.t("dashboards.statsCard.metrics.totalErrorCount"),
    type: STATISTIC_AGGREGATION_TYPE.COUNT,
    statName: "error_count",
    formatter: (value: number) => value.toLocaleString(),
  },
];

const TRACE_SPECIFIC_METRICS: MetricDefinition[] = [
  {
    value: "trace_count",
    label: i18next.t("dashboards.statsCard.metrics.totalTraceCount"),
    type: STATISTIC_AGGREGATION_TYPE.COUNT,
    statName: "trace_count",
    formatter: (value: number) => value.toLocaleString(),
  },
  {
    value: "thread_count",
    label: i18next.t("dashboards.statsCard.metrics.totalThreadCount"),
    type: STATISTIC_AGGREGATION_TYPE.COUNT,
    statName: "thread_count",
    formatter: (value: number) => value.toLocaleString(),
  },
  {
    value: "llm_span_count",
    label: i18next.t("dashboards.statsCard.metrics.averageLlmSpanCount"),
    type: STATISTIC_AGGREGATION_TYPE.AVG,
    statName: "llm_span_count",
    formatter: formatNumericData,
    tooltipFormatter: String,
  },
  {
    value: "span_count",
    label: i18next.t("dashboards.statsCard.metrics.averageSpanCount"),
    type: STATISTIC_AGGREGATION_TYPE.AVG,
    statName: "span_count",
    formatter: formatNumericData,
    tooltipFormatter: String,
  },
  {
    value: "total_estimated_cost",
    label: i18next.t("dashboards.statsCard.metrics.averageEstimatedCostPerTrace"),
    type: STATISTIC_AGGREGATION_TYPE.AVG,
    statName: "total_estimated_cost",
    formatter: formatCost,
    tooltipFormatter: (value: number) =>
      formatCost(value, { modifier: "full" }),
  },
  {
    value: "guardrails_failed_count",
    label: i18next.t("dashboards.statsCard.metrics.totalGuardrailsFailedCount"),
    type: STATISTIC_AGGREGATION_TYPE.COUNT,
    statName: "guardrails_failed_count",
    formatter: (value: number) => value.toLocaleString(),
  },
];

const SPAN_SPECIFIC_METRICS: MetricDefinition[] = [
  {
    value: "span_count",
    label: i18next.t("dashboards.statsCard.metrics.totalSpanCount"),
    type: STATISTIC_AGGREGATION_TYPE.COUNT,
    statName: "span_count",
    formatter: (value: number) => value.toLocaleString(),
  },
  {
    value: "total_estimated_cost",
    label: i18next.t("dashboards.statsCard.metrics.averageEstimatedCostPerSpan"),
    type: STATISTIC_AGGREGATION_TYPE.AVG,
    statName: "total_estimated_cost",
    formatter: formatCost,
    tooltipFormatter: (value: number) =>
      formatCost(value, { modifier: "full" }),
  },
];

export const getStaticMetrics = (
  source: TRACE_DATA_TYPE,
): MetricDefinition[] => {
  const specificMetrics =
    source === TRACE_DATA_TYPE.traces
      ? TRACE_SPECIFIC_METRICS
      : SPAN_SPECIFIC_METRICS;
  return [...specificMetrics, ...SHARED_METRICS];
};

export const getFeedbackScoreMetricOptions = (
  scoreNames: string[],
): MetricOption[] => {
  return scoreNames.map((scoreName) => ({
    value: `${COLUMN_FEEDBACK_SCORES_ID}.${scoreName}`,
    label: i18next.t("dashboards.statsCard.averageScore", { name: scoreName }),
  }));
};

export const getAllMetricOptions = (
  source: TRACE_DATA_TYPE,
  feedbackScoreNames: string[] = [],
): MetricOption[] => {
  const staticOptions: MetricOption[] = getStaticMetrics(source).map((m) => ({
    value: m.value,
    label: m.label,
  }));

  const feedbackOptions = getFeedbackScoreMetricOptions(feedbackScoreNames);

  return [...staticOptions, ...feedbackOptions];
};

export const getMetricDefinition = (
  metricValue: string,
  source: TRACE_DATA_TYPE,
): MetricDefinition | null => {
  const staticMetrics = getStaticMetrics(source);
  return staticMetrics.find((m) => m.value === metricValue) || null;
};

const formatWithFormatter = (
  value: number | string | object,
  metricDefinition: MetricDefinition,
  formatter: (value: number) => string,
): string => {
  const { type } = metricDefinition;

  if (type === STATISTIC_AGGREGATION_TYPE.PERCENTAGE) {
    const percentageValue = value as {
      p50?: number;
      p90?: number;
      p99?: number;
    };

    if (metricDefinition.value.includes("p50")) {
      return formatter(percentageValue.p50 || 0);
    }
    if (metricDefinition.value.includes("p90")) {
      return formatter(percentageValue.p90 || 0);
    }
    if (metricDefinition.value.includes("p99")) {
      return formatter(percentageValue.p99 || 0);
    }

    return formatter(percentageValue.p50 || 0);
  }

  const numValue = Number(value);
  return formatter(numValue);
};

export const formatMetricValue = (
  value: number | string | object,
  metricDefinition: MetricDefinition,
): string => {
  return formatWithFormatter(
    value,
    metricDefinition,
    metricDefinition.formatter,
  );
};

export const formatMetricTooltipValue = (
  value: number | string | object,
  metricDefinition: MetricDefinition,
): string | undefined => {
  if (!metricDefinition.tooltipFormatter) return undefined;
  return formatWithFormatter(
    value,
    metricDefinition,
    metricDefinition.tooltipFormatter,
  );
};

export const isFeedbackScoreMetric = (metricValue: string): boolean => {
  return metricValue.startsWith(`${COLUMN_FEEDBACK_SCORES_ID}.`);
};

export const extractFeedbackScoreName = (metricValue: string): string => {
  return metricValue.replace(`${COLUMN_FEEDBACK_SCORES_ID}.`, "");
};
