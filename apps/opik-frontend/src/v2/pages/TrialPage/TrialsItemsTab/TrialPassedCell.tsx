import React from "react";
import { useTranslation } from "react-i18next";
import { CellContext } from "@tanstack/react-table";
import { RunStatus } from "@/types/test-suites";

type FlattenedTrialItem = {
  experimentItem: {
    status?: RunStatus;
  };
  allRuns: {
    status?: RunStatus;
  }[];
  runSummary?: {
    passed_runs: number;
    total_runs: number;
    status: RunStatus;
  };
  executionPolicy?: {
    runs_per_item?: number;
    pass_threshold?: number;
  };
};

const TrialPassedCell: React.FC<CellContext<FlattenedTrialItem, unknown>> = (
  context,
) => {
  const { t } = useTranslation("pages/trial");
  const row = context.row.original;
  const { allRuns, runSummary, executionPolicy } = row;

  if (runSummary) {
    const { passed_runs, total_runs, status } = runSummary;
    const itemPassed = status === RunStatus.PASSED;

    if (total_runs === 1) {
      return (
        <span className={itemPassed ? "text-success" : "text-destructive"}>
          {itemPassed ? t("trialPassedCell.yes") : t("trialPassedCell.no")}
        </span>
      );
    }

    const passThreshold = executionPolicy?.pass_threshold ?? 1;
    return (
      <span className={itemPassed ? "text-success" : "text-destructive"}>
        {passed_runs}/{total_runs} ({t("trialPassedCell.threshold", { value: passThreshold })})
      </span>
    );
  }

  const firstRun = row.experimentItem;
  if (!firstRun.status) {
    return <span>-</span>;
  }

  if (allRuns.length === 1) {
    const itemPassed = firstRun.status === RunStatus.PASSED;
    return (
      <span className={itemPassed ? "text-success" : "text-destructive"}>
        {itemPassed ? t("trialPassedCell.yes") : t("trialPassedCell.no")}
      </span>
    );
  }

  const runsPassed = allRuns.filter(
    (r) => r.status === RunStatus.PASSED,
  ).length;
  const passThreshold = executionPolicy?.pass_threshold ?? 1;
  const itemPassed = runsPassed >= passThreshold;

  return (
    <span className={itemPassed ? "text-success" : "text-destructive"}>
      {runsPassed}/{allRuns.length} ({t("trialPassedCell.threshold", { value: passThreshold })})
    </span>
  );
};

export default TrialPassedCell;
