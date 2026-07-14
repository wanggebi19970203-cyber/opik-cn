import React from "react";
import { useTranslation } from "react-i18next";
import { Clock, Coins, LucideIcon, PenLine } from "lucide-react";

import MetricComparisonCell from "@/v2/pages-shared/experiments/MetricComparisonCell/MetricComparisonCell";
import { PercentageTrendType } from "@/shared/PercentageTrend/PercentageTrend";
import {
  formatAsPercentage,
  formatAsDuration,
  formatAsCurrency,
} from "@/lib/optimization-formatters";
import { getObjectiveLabel } from "@/lib/optimizations";

type KPICardProps = {
  icon: LucideIcon;
  label: string;
  children: React.ReactNode;
};

export const KPICard: React.FunctionComponent<KPICardProps> = ({
  icon: Icon,
  label,
  children,
}) => (
  <div className="rounded-lg border bg-muted/20 p-4">
    <div className="mb-2 flex items-center gap-2">
      <Icon className="size-4 text-muted-slate" />
      <span className="comet-body-s text-muted-slate">{label}</span>
    </div>
    {children}
  </div>
);

type MetricKPICardProps = {
  icon: LucideIcon;
  label: string;
  baseline?: number;
  current?: number;
  formatter: (value: number) => string;
  trend?: PercentageTrendType;
};

export const MetricKPICard: React.FunctionComponent<MetricKPICardProps> = ({
  icon,
  label,
  baseline,
  current,
  formatter,
  trend = "direct",
}) => (
  <KPICard icon={icon} label={label}>
    <MetricComparisonCell
      baseline={baseline}
      current={current}
      formatter={formatter}
      trend={trend}
    />
  </KPICard>
);

export type MetricKPICardConfig = {
  key: string;
  icon: LucideIcon;
  label: string;
  formatter: (value: number) => string;
  trend?: PercentageTrendType;
};

export const useMetricKPICardConfigs = (options?: {
  isTestSuite?: boolean;
  objectiveName?: string;
}): MetricKPICardConfig[] => {
  const { t } = useTranslation("experiments");
  return [
    {
      key: "score",
      icon: PenLine,
      label: getObjectiveLabel(options?.isTestSuite, options?.objectiveName),
      formatter: formatAsPercentage,
    },
    {
      key: "latency",
      icon: Clock,
      label: t("latency"),
      formatter: formatAsDuration,
      trend: "inverted",
    },
    {
      key: "cost",
      icon: Coins,
      label: t("runtimeCost"),
      formatter: formatAsCurrency,
      trend: "inverted",
    },
  ];
};

/** @deprecated Use useMetricKPICardConfigs instead */
export const getMetricKPICardConfigs = (options?: {
  isTestSuite?: boolean;
  objectiveName?: string;
  t?: (key: string) => string;
}): MetricKPICardConfig[] => [
  {
    key: "score",
    icon: PenLine,
    label: getObjectiveLabel(options?.isTestSuite, options?.objectiveName),
    formatter: formatAsPercentage,
  },
  {
    key: "latency",
    icon: Clock,
    label: options?.t?.("experiments:latency") ?? "Latency",
    formatter: formatAsDuration,
    trend: "inverted",
  },
  {
    key: "cost",
    icon: Coins,
    label: options?.t?.("experiments:runtimeCost") ?? "Runtime cost",
    formatter: formatAsCurrency,
    trend: "inverted",
  },
];
