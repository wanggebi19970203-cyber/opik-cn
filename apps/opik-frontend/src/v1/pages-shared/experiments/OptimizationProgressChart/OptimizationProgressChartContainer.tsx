import React, { useMemo } from "react";
import isNull from "lodash/isNull";
import { useTranslation } from "react-i18next";

import { AggregatedCandidate } from "@/types/optimizations";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { IN_PROGRESS_OPTIMIZATION_STATUSES } from "@/lib/optimizations";
import NoData from "@/shared/NoData/NoData";
import { Card, CardContent, CardHeader, CardTitle } from "@/ui/card";
import { Spinner } from "@/ui/spinner";
import OptimizationProgressChartContent from "./OptimizationProgressChartContent";
import {
  buildCandidateChartData,
  type InProgressInfo,
} from "./optimizationChartUtils";

const INITIALIZING_MESSAGE_KEY = "optimizationInitialized";
const CALCULATING_BASELINE_MESSAGE_KEY = "calculatingBaseline";

type OptimizationProgressChartContainerProps = {
  candidates: AggregatedCandidate[];
  bestCandidateId?: string;
  objectiveName?: string;
  status?: OPTIMIZATION_STATUS;
  selectedTrialId?: string;
  onTrialSelect?: (trialId: string) => void;
  onTrialClick?: (candidateId: string) => void;
  isTestSuite?: boolean;
  inProgressInfo?: InProgressInfo;
  isRunningMiniBatches?: boolean;
};

const OptimizationProgressChartContainer: React.FC<
  OptimizationProgressChartContainerProps
> = ({
  candidates,
  bestCandidateId,
  status,
  objectiveName = "",
  selectedTrialId,
  onTrialSelect,
  onTrialClick,
  isTestSuite,
  inProgressInfo,
  isRunningMiniBatches,
}) => {
  const { t } = useTranslation("experiments");
  const isInProgress =
    !!status && IN_PROGRESS_OPTIMIZATION_STATUSES.includes(status);

  const baselineMessage = candidates.some((c) => c.stepIndex === 0)
    ? t(CALCULATING_BASELINE_MESSAGE_KEY)
    : t(INITIALIZING_MESSAGE_KEY);

  const chartData = useMemo(
    () =>
      buildCandidateChartData(
        candidates,
        isTestSuite,
        isInProgress,
        inProgressInfo,
      ),
    [candidates, isTestSuite, isInProgress, inProgressInfo],
  );

  const noData = useMemo(
    () => chartData.every((d) => isNull(d.value)),
    [chartData],
  );

  const renderContent = () => {
    if (!chartData.length || noData) {
      if (isInProgress) {
        return (
          <div className="flex min-h-32 flex-col items-center justify-center gap-2">
            <Spinner size="small" />
            <div className="comet-body-s text-muted-slate transition-opacity duration-300">
              {baselineMessage}
            </div>
          </div>
        );
      }

      return (
        <NoData
          className="min-h-32 text-light-slate"
          message={t('noDataToShow')}
        />
      );
    }

    return (
      <OptimizationProgressChartContent
        chartData={chartData}
        candidates={candidates}
        bestCandidateId={bestCandidateId}
        objectiveName={objectiveName}
        selectedTrialId={selectedTrialId}
        onTrialSelect={onTrialSelect}
        onTrialClick={onTrialClick}
        isTestSuite={isTestSuite}
        isInProgress={isInProgress}
        inProgressInfo={inProgressInfo}
      />
    );
  };

  return (
    <Card className="h-[280px] min-w-[400px] flex-auto">
      <CardHeader className="space-y-0.5 px-4 pt-3">
        <CardTitle className="comet-body-s-accented flex items-center gap-2">
          {t('optimizationProgress')}
          {isInProgress && !noData && (
            <>
              <Spinner size="xs" />
              <span className="comet-body-xs font-normal text-muted-slate">
                {inProgressInfo
                  ? t('evaluatingNewCandidate')
                  : isRunningMiniBatches
                    ? t('lookingForFailingExamples')
                    : t('generatingNewCandidate')}
              </span>
            </>
          )}
        </CardTitle>
      </CardHeader>
      <CardContent className="px-4 pb-3">{renderContent()}</CardContent>
    </Card>
  );
};

export default OptimizationProgressChartContainer;
