import React from "react";
import { useTranslation } from "react-i18next";

import { cn } from "@/lib/utils";
import FeedbackScoresChartContainer from "@/v1/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContainer";
import { ChartData } from "@/v1/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContent";

type FeedbackScoresChartsWrapperProps = {
  chartsData: ChartData[];
  areAggregatedScores?: boolean;
  noDataComponent?: React.ReactNode;
};

const FeedbackScoresChartsWrapper = ({
  chartsData,
  areAggregatedScores = false,
  noDataComponent,
}: FeedbackScoresChartsWrapperProps) => {
  const { t } = useTranslation("experiments");
  const chartClassName =
    chartsData.length === 1
      ? "w-full"
      : chartsData.length === 2
        ? "basis-1/2"
        : "basis-[520px]";

  return (
    <div className={cn("flex items-center gap-4 overflow-y-auto mb-4")}>
      {chartsData.length === 0 && noDataComponent
        ? noDataComponent
        : chartsData.map((data, index) => (
            <FeedbackScoresChartContainer
              key={data.id}
              className={chartClassName}
              chartData={chartsData[index]}
              chartId={data.id}
              chartName={data.name}
              subtitle={areAggregatedScores ? t("aggregatedScores") : undefined}
            />
          ))}
    </div>
  );
};

export default FeedbackScoresChartsWrapper;
