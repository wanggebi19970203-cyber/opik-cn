import i18next from "i18next";
import { INTERVAL_TYPE } from "@/api/projects/useProjectMetric";
import { ChartTooltipRenderValueArguments } from "@/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import { formatNumberInK } from "@/lib/utils";

/**
 * Duration labels mapping for percentile charts
 */
export const DURATION_LABELS_MAP = {
  "duration.p50": i18next.t("dashboards.chartUtils.percentile50"),
  "duration.p90": i18next.t("dashboards.chartUtils.percentile90"),
  "duration.p99": i18next.t("dashboards.chartUtils.percentile99"),
} as const;

/**
 * Interval descriptions for different chart types and intervals
 */
export const INTERVAL_DESCRIPTIONS = {
  TOTALS: {
    [INTERVAL_TYPE.HOURLY]: i18next.t("dashboards.chartUtils.hourlyTotals"),
    [INTERVAL_TYPE.DAILY]: i18next.t("dashboards.chartUtils.dailyTotals"),
    [INTERVAL_TYPE.WEEKLY]: i18next.t("dashboards.chartUtils.weeklyTotals"),
    [INTERVAL_TYPE.TOTAL]: i18next.t("dashboards.chartUtils.wholePeriodTotals"),
  },
  AVERAGES: {
    [INTERVAL_TYPE.HOURLY]: i18next.t("dashboards.chartUtils.hourlyAverages"),
    [INTERVAL_TYPE.DAILY]: i18next.t("dashboards.chartUtils.dailyAverages"),
    [INTERVAL_TYPE.WEEKLY]: i18next.t("dashboards.chartUtils.weeklyAverages"),
    [INTERVAL_TYPE.TOTAL]: i18next.t("dashboards.chartUtils.wholePeriodAverages"),
  },
  QUANTILES: {
    [INTERVAL_TYPE.HOURLY]: i18next.t("dashboards.chartUtils.hourlyQuantilesInSeconds"),
    [INTERVAL_TYPE.DAILY]: i18next.t("dashboards.chartUtils.dailyQuantilesInSeconds"),
    [INTERVAL_TYPE.WEEKLY]: i18next.t("dashboards.chartUtils.weeklyQuantilesInSeconds"),
    [INTERVAL_TYPE.TOTAL]: i18next.t("dashboards.chartUtils.wholePeriodQuantilesInSeconds"),
  },
  COST: {
    [INTERVAL_TYPE.HOURLY]: i18next.t("dashboards.chartUtils.totalHourlyCostInUsd"),
    [INTERVAL_TYPE.DAILY]: i18next.t("dashboards.chartUtils.totalDailyCostInUsd"),
    [INTERVAL_TYPE.WEEKLY]: i18next.t("dashboards.chartUtils.totalWeeklyCostInUsd"),
    [INTERVAL_TYPE.TOTAL]: i18next.t("dashboards.chartUtils.totalCostInUsd"),
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
  formatCost(value, { modifier: "kFormat" });

/**
 * Formats Y-axis tick values for token usage charts
 */
export const tokenYTickFormatter = (value: number) => formatNumberInK(value);
