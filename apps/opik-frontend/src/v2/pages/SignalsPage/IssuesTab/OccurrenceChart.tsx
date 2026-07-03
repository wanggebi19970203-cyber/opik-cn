import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import LineChart from "@/shared/Charts/LineChart/LineChart";
import { ChartConfig } from "@/ui/chart";
import { formatDate } from "@/lib/date";
import { AgentInsightsIssueDetail } from "@/types/signals";

type OccurrenceChartProps = {
  data: AgentInsightsIssueDetail[];
};

const OccurrenceChart: React.FC<OccurrenceChartProps> = ({ data }) => {
  const { t } = useTranslation("pages/signals");
  const config: ChartConfig = {
    count: {
      label: t("signals.occurrenceChart.label"),
      color: "var(--color-primary)",
    },
  };
  const chartData = useMemo(
    () => data.map((point) => ({ time: point.report_day, count: point.count })),
    [data],
  );

  return (
    <LineChart
      chartId="signals-occurrence-over-time"
      config={config}
      data={chartData}
      xAxisKey="time"
      xTickFormatter={(value) => formatDate(value, { format: "D MMM" })}
      showLegend={false}
      showArea={false}
      className="h-[140px] w-full"
    />
  );
};

export default OccurrenceChart;
