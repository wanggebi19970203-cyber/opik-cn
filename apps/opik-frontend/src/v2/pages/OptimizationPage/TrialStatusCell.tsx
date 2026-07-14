import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Tag } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import {
  computeCandidateStatuses,
  STATUS_VARIANT_MAP,
  type InProgressInfo,
  type TrialStatus,
} from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

const STATUS_I18N_KEY: Record<TrialStatus, string> = {
  baseline: "optimization.status.baseline",
  passed: "optimization.status.passed",
  evaluating: "optimization.status.evaluating",
  pruned: "optimization.status.pruned",
  running: "optimization.status.running",
};

const TrialStatusCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
  const { t } = useTranslation("pages/optimization");
  const { custom } = context.column.columnDef.meta ?? {};
  const {
    candidates,
    bestCandidateId,
    isTestSuite,
    isInProgress,
    inProgressInfo,
  } = (custom ?? {}) as {
    candidates: AggregatedCandidate[];
    bestCandidateId?: string;
    isTestSuite?: boolean;
    isInProgress?: boolean;
    inProgressInfo?: InProgressInfo;
  };

  const isBest = bestCandidateId === row.candidateId;

  const statusMap = useMemo(
    () =>
      computeCandidateStatuses(
        candidates ?? [],
        isTestSuite,
        isInProgress,
        inProgressInfo,
      ),
    [candidates, isTestSuite, isInProgress, inProgressInfo],
  );
  const status = statusMap.get(row.candidateId) ?? "pruned";
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isBest ? (
        <Tag variant="green" size={tagSize}>
          {t("optimization.trials.best")}
        </Tag>
      ) : (
        <Tag variant={STATUS_VARIANT_MAP[status]} size={tagSize}>
          {t(STATUS_I18N_KEY[status])}
        </Tag>
      )}
    </CellWrapper>
  );
};

export default TrialStatusCell;
