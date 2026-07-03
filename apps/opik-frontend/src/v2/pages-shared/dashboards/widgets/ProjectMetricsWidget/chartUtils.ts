import i18next from "i18next";
import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";
import { ChartTooltipRenderValueArguments } from "@/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import { formatNumberInK } from "@/lib/utils";

/**
 * Duration labels mapping for percentile charts
 */
export const getDurationLabelsMap = () => {
  const t = i18next.getFixedT(null, "dashboards");
  return {
    "duration.p50": t("chartUtils.percentile50"),
    "duration.p90": t("chartUtils.percentile90"),
    "duration.p99": t("chartUtils.percentile99"),
  } as const;
};

/** @deprecated Use getDurationLabelsMap() instead */
export const DURATION_LABELS_MAP = {
  "duration.p50": "Percentile 50",
  "duration.p90": "Percentile 90",
  "duration.p99": "Percentile 99",
} as const;

/**
 * Interval descriptions for different chart types and intervals
 */
export const getIntervalDescriptions = () => {
  const t = i18next.getFixedT(null, "dashboards");
  return {
    TOTALS: {
      [INTERVAL_TYPE.HOURLY]: t("chartUtils.hourlyTotals"),
      [INTERVAL_TYPE.DAILY]: t("chartUtils.dailyTotals"),
      [INTERVAL_TYPE.WEEKLY]: t("chartUtils.weeklyTotals"),
      [INTERVAL_TYPE.TOTAL]: t("chartUtils.wholePeriodTotals"),
    },
    AVERAGES: {
      [INTERVAL_TYPE.HOURLY]: t("chartUtils.hourlyAverages"),
      [INTERVAL_TYPE.DAILY]: t("chartUtils.dailyAverages"),
      [INTERVAL_TYPE.WEEKLY]: t("chartUtils.weeklyAverages"),
      [INTERVAL_TYPE.TOTAL]: t("chartUtils.wholePeriodAverages"),
    },
    QUANTILES: {
      [INTERVAL_TYPE.HOURLY]: t("chartUtils.hourlyQuantilesInSeconds"),
      [INTERVAL_TYPE.DAILY]: t("chartUtils.dailyQuantilesInSeconds"),
      [INTERVAL_TYPE.WEEKLY]: t("chartUtils.weeklyQuantilesInSeconds"),
      [INTERVAL_TYPE.TOTAL]: t("chartUtils.wholePeriodQuantilesInSeconds"),
    },
    COST: {
      [INTERVAL_TYPE.HOURLY]: t("chartUtils.totalHourlyCostInUsd"),
      [INTERVAL_TYPE.DAILY]: t("chartUtils.totalDailyCostInUsd"),
      [INTERVAL_TYPE.WEEKLY]: t("chartUtils.totalWeeklyCostInUsd"),
      [INTERVAL_TYPE.TOTAL]: t("chartUtils.totalCostInUsd"),
    },
  } as const;
};

/** @deprecated Use getIntervalDescriptions() instead */
export const INTERVAL_DESCRIPTIONS = {
  TOTALS: {
    [INTERVAL_TYPE.HOURLY]: "Hourly totals",
    [INTERVAL_TYPE.DAILY]: "Daily totals",
    [INTERVAL_TYPE.WEEKLY]: "Weekly totals",
    [INTERVAL_TYPE.TOTAL]: "Whole period totals",
  },
  AVERAGES: {
    [INTERVAL_TYPE.HOURLY]: "Hourly averages",
    [INTERVAL_TYPE.DAILY]: "Daily averages",
    [INTERVAL_TYPE.WEEKLY]: "Weekly averages",
    [INTERVAL_TYPE.TOTAL]: "Whole period averages",
  },
  QUANTILES: {
    [INTERVAL_TYPE.HOURLY]: "Hourly quantiles in seconds",
    [INTERVAL_TYPE.DAILY]: "Daily quantiles in seconds",
    [INTERVAL_TYPE.WEEKLY]: "Weekly quantiles in seconds",
    [INTERVAL_TYPE.TOTAL]: "Whole period quantiles in seconds",
  },
  COST: {
    [INTERVAL_TYPE.HOURLY]: "Total hourly cost in USD",
    [INTERVAL_TYPE.DAILY]: "Total daily cost in USD",
    [INTERVAL_TYPE.WEEKLY]: "Total weekly cost in USD",
    [INTERVAL_TYPE.TOTAL]: "Total cost in USD",
  },
} as const;

/**
 * Renders duration tooltip values in formatted duration string
 */
export const renderDurationTooltipValue = ({
  value,
}: ChartTooltipRenderValueArguments) => formatDuration(value as number, false);

/**
 * Formats Y-axis tick values for duration charts
 */
export const durationYTickFormatter = (value: number) =>
  formatDuration(value, false);

/**
 * Renders cost tooltip values in formatted cost string
 */
export const renderCostTooltipValue = ({
  value,
}: ChartTooltipRenderValueArguments) =>
  formatCost(value as number, { modifier: "full" });

/**
 * Formats Y-axis tick values for cost charts
 */
export const costYTickFormatter = (value: number) =>
  formatCost(value, { modifier: "kFormat", noValue: "$0" });

/**
 * Formats Y-axis tick values for token usage charts
 */
export const tokenYTickFormatter = (value: number) => formatNumberInK(value);
