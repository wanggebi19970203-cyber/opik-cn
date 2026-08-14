import i18next from "i18next";
import { useMemo } from "react";

import {
  CELL_HORIZONTAL_ALIGNMENT,
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import { Experiment } from "@/types/datasets";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import { convertColumnDataToColumn } from "@/lib/table";
import TrialStatusCell from "@/v2/pages/OptimizationPage/TrialStatusCell";
import {
  TrialNumberCell,
  TrialStepCell,
  TrialAccuracyCell,
  TrialCandidateCostCell,
  TrialCandidateLatencyCell,
} from "@/v2/pages/OptimizationPage/TrialMetricCells";
import { TrialPromptCell } from "@/v2/pages/OptimizationPage/TrialPromptCell";
import { getObjectiveLabel } from "@/lib/optimizations";
import { type TrialStatus } from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

type UseOptimizationColumnsParams = {
  experiments: Experiment[];
  baselineExperiment?: Experiment;
  columnsOrder: string[];
  selectedColumns: string[];
  sortableBy: string[];
  bestCandidateId?: string;
  baselineCandidate?: AggregatedCandidate;
  isTestSuite?: boolean;
  /** Whole-run status map (computed once on the page) keyed by candidate id. */
  statusMap: Map<string, TrialStatus>;
  objectiveName?: string;
};

export const useOptimizationColumns = ({
  experiments,
  baselineExperiment,
  columnsOrder,
  selectedColumns,
  sortableBy,
  bestCandidateId,
  baselineCandidate,
  isTestSuite,
  statusMap,
  objectiveName,
}: UseOptimizationColumnsParams) => {
  const experimentMap = useMemo(
    () => new Map(experiments.map((e) => [e.id, e])),
    [experiments],
  );

  const columnsDef: ColumnData<AggregatedCandidate>[] = useMemo(() => {
    return [
      {
        id: COLUMN_NAME_ID,
        label: i18next.t("common.optimization.trialNumber"),
        type: COLUMN_TYPE.string,
        size: 80,
        cell: TrialNumberCell,
      },
      {
        id: "step",
        label: i18next.t("common.optimization.step"),
        type: COLUMN_TYPE.string,
        size: 80,
        accessorFn: (row) => row.stepIndex,
        cell: TrialStepCell,
      },
      {
        id: COLUMN_ID_ID,
        label: i18next.t("common.labels.id"),
        type: COLUMN_TYPE.string,
      },
      {
        id: "prompt",
        label: i18next.t("common.labels.prompt"),
        type: COLUMN_TYPE.string,
        size: 322,
        accessorFn: (row) => row.experimentIds?.[0],
        cell: TrialPromptCell,
        customMeta: {
          experimentMap,
          baselineExperiment,
        },
      },
      {
        id: "objective_name",
        label: getObjectiveLabel(isTestSuite, objectiveName),
        type: COLUMN_TYPE.numberDictionary,
        size: 130,
        // numberDictionary defaults to start; all metric columns key to the
        // right edge.
        horizontalAlignment: CELL_HORIZONTAL_ALIGNMENT.end,
        accessorFn: (row) => row.score,
        cell: TrialAccuracyCell,
        // statusMap lets the metric cells drop the baseline delta while a trial
        // is still evaluating — a partial average is not comparable to the
        // fully evaluated baseline (OPIK-7460).
        customMeta: {
          baselineCandidate,
          isTestSuite,
          statusMap,
        },
      },
      {
        id: "runtime_cost",
        label: i18next.t("common.optimization.runtimeCost"),
        type: COLUMN_TYPE.cost,
        size: 130,
        accessorFn: (row) => row.runtimeCost,
        cell: TrialCandidateCostCell,
        customMeta: {
          baselineCandidate,
          statusMap,
        },
      },
      {
        id: "latency",
        label: i18next.t("common.optimization.latency"),
        type: COLUMN_TYPE.duration,
        size: 130,
        accessorFn: (row) => row.latencyP50,
        cell: TrialCandidateLatencyCell,
        customMeta: {
          baselineCandidate,
          statusMap,
        },
      },
      {
        id: "trace_count",
        label: i18next.t("common.optimization.trialItems"),
        type: COLUMN_TYPE.number,
        size: 80,
        accessorFn: (row) => row.totalDatasetItemCount,
      },
      {
        id: "trial_status",
        label: i18next.t("common.labels.status"),
        type: COLUMN_TYPE.string,
        size: 120,
        accessorFn: () => undefined,
        cell: TrialStatusCell,
        customMeta: {
          statusMap,
          bestCandidateId,
        },
      },
      {
        id: "created_at",
        label: i18next.t("common.labels.created"),
        type: COLUMN_TYPE.time,
        size: 140,
        // TimeCell is shared and typed for unknown rows; the one remaining cast.
        cell: TimeCell as never,
        customMeta: {
          timeMode: "absolute",
        },
      },
    ];
  }, [
    experimentMap,
    baselineExperiment,
    bestCandidateId,
    baselineCandidate,
    isTestSuite,
    statusMap,
    objectiveName,
  ]);

  const columns = useMemo(() => {
    return [
      ...convertColumnDataToColumn<AggregatedCandidate, AggregatedCandidate>(
        columnsDef,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
    ];
  }, [columnsDef, columnsOrder, selectedColumns, sortableBy]);

  return { columnsDef, columns };
};
