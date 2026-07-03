import React from "react";
import { useTranslation } from "react-i18next";
import {
  AlertTriangle,
  CircleCheck,
  CircleMinus,
  CircleX,
  Loader2,
} from "lucide-react";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import VerticallySplitCellWrapper, {
  CustomMeta,
} from "@/shared/DataTableCells/VerticallySplitCellWrapper";
import AssertionsBreakdownTooltip from "./AssertionsBreakdownTooltip";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/ui/tooltip";
import { cn } from "@/lib/utils";
import {
  AssertionResult,
  ExperimentItem,
  ExperimentsCompare,
} from "@/types/datasets";
import { isExperimentTerminal } from "@/lib/experiments";
import { EXPERIMENT_STATUS } from "@/types/datasets";
import { RunStatus } from "@/types/test-suites";
import { isAggregatedItem } from "@/lib/trials";

type StatusInfo = {
  status: RunStatus | undefined;
  evaluating: boolean;
  assertionsByRun: AssertionResult[][];
  reason: string | undefined;
  isError?: boolean;
  passThreshold: number | undefined;
  runsPerItem: number | undefined;
};

const NO_EXPERIMENT_ITEM_REASON = "noExperimentItemDefined";
const NO_ASSERTIONS_REASON = "noAssertionsDefined";
const SCORING_ERROR_REASON = "scoringFailed";

type DisplayState = "error" | "skipped" | "passed" | "failed";

const STATUS_DISPLAY = {
  error: {
    Icon: AlertTriangle,
    colorClass: "bg-[var(--tag-orange-bg)] text-[var(--tag-orange-text)]",
  },
  skipped: {
    Icon: CircleMinus,
    colorClass: "bg-muted text-muted-foreground",
  },
  passed: {
    Icon: CircleCheck,
    colorClass: "bg-[var(--tag-green-bg)] text-[var(--tag-green-text)]",
  },
  failed: {
    Icon: CircleX,
    colorClass: "bg-[var(--tag-red-bg)] text-[var(--tag-red-text)]",
  },
} as const;

const STATUS_LABEL_KEYS: Record<DisplayState, string> = {
  error: "scoringError",
  skipped: "skipped",
  passed: "passed",
  failed: "failed",
};

const SKIPPED_RESULT = (reason: string): StatusInfo => ({
  status: RunStatus.SKIPPED,
  evaluating: false,
  assertionsByRun: [],
  reason: reason,
  passThreshold: undefined,
  runsPerItem: undefined,
});

const ERROR_RESULT = (reason: string): StatusInfo => ({
  status: RunStatus.SKIPPED,
  evaluating: false,
  assertionsByRun: [],
  reason: reason,
  isError: true,
  passThreshold: undefined,
  runsPerItem: undefined,
});

function resolveSkippedStatus(
  status: RunStatus | undefined,
  row: ExperimentsCompare,
  experimentFinished?: boolean,
): StatusInfo | null {
  if (!status) {
    const hasEvaluators = (row.evaluators?.length ?? 0) > 0;
    if (!hasEvaluators) return SKIPPED_RESULT(NO_ASSERTIONS_REASON);
    if (experimentFinished) return ERROR_RESULT(SCORING_ERROR_REASON);
  }
  return null;
}

export function getStatusFromExperimentItems(
  row: ExperimentsCompare,
  experimentFinished?: boolean,
): StatusInfo {
  const items = row.experiment_items;
  if (!items?.length) return SKIPPED_RESULT(NO_EXPERIMENT_ITEM_REASON);

  const summaryValues = Object.values(row.run_summaries_by_experiment ?? {});
  let status: RunStatus | undefined;

  if (summaryValues.length > 0) {
    const allSkipped = summaryValues.every(
      (s) => s.status === RunStatus.SKIPPED,
    );
    if (allSkipped) {
      status = RunStatus.SKIPPED;
    } else {
      const allPassed = summaryValues.every(
        (s) => s.status === RunStatus.PASSED,
      );
      status = allPassed ? RunStatus.PASSED : RunStatus.FAILED;
    }
  } else {
    status = items[0].status;
  }

  const skipped = resolveSkippedStatus(status, row, experimentFinished);
  if (skipped) return skipped;

  // Item-level execution_policy overrides the dataset-level one
  const passThreshold =
    items[0]?.execution_policy?.pass_threshold ??
    row.execution_policy?.pass_threshold;
  const runsPerItem =
    items[0]?.execution_policy?.runs_per_item ??
    row.execution_policy?.runs_per_item;

  return {
    status,
    evaluating: !status,
    assertionsByRun: items.map((item) => item.assertion_results ?? []),
    reason: undefined,
    passThreshold,
    runsPerItem,
  };
}

export function getStatusInfoForExperiment(
  row: ExperimentsCompare,
  experimentId: string,
  item: ExperimentItem | undefined,
  experimentFinished?: boolean,
): StatusInfo {
  const expItems: ExperimentItem[] = item
    ? isAggregatedItem(item)
      ? item.trialItems
      : [item]
    : [];

  if (!expItems.length) return SKIPPED_RESULT(NO_EXPERIMENT_ITEM_REASON);

  const summary = row.run_summaries_by_experiment?.[experimentId];
  const status: RunStatus | undefined = summary
    ? summary.status
    : expItems[0].status;

  const skipped = resolveSkippedStatus(status, row, experimentFinished);
  if (skipped) return skipped;

  // Item-level execution_policy overrides the dataset-level one
  const passThreshold =
    expItems[0]?.execution_policy?.pass_threshold ??
    row.execution_policy?.pass_threshold;
  const runsPerItem =
    expItems[0]?.execution_policy?.runs_per_item ??
    row.execution_policy?.runs_per_item;

  return {
    status,
    evaluating: !status,
    assertionsByRun: expItems.map((item) => item.assertion_results ?? []),
    reason: undefined,
    passThreshold,
    runsPerItem,
  };
}

export const StatusTag: React.FC<StatusInfo & { className?: string }> = ({
  status,
  evaluating,
  assertionsByRun,
  reason,
  isError,
  passThreshold,
  runsPerItem,
  className,
}) => {
  const { t } = useTranslation("experiments");

  if (evaluating) {
    return (
      <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-xs text-muted-slate">
        <span className="inline-flex animate-spin">
          <Loader2 className="size-3" />
        </span>
        {t("evaluatingAssertions")}
      </span>
    );
  }

  if (!status) {
    return null;
  }

  const displayState: DisplayState = isError
    ? "error"
    : status === RunStatus.SKIPPED
      ? "skipped"
      : status === RunStatus.PASSED
        ? "passed"
        : "failed";

  const { Icon, colorClass } = STATUS_DISPLAY[displayState];
  const label = t(STATUS_LABEL_KEYS[displayState]);

  const tag = (
    <span
      className={cn(
        "inline-flex items-center gap-1 rounded-md border border-transparent px-1.5 py-0.5 text-sm font-medium transition-colors cursor-default",
        colorClass,
        className,
      )}
    >
      <Icon className="size-3 shrink-0" />
      {label}
    </span>
  );

  if (displayState === "error" || displayState === "skipped") {
    return (
      <Tooltip>
        <TooltipTrigger asChild>{tag}</TooltipTrigger>
        {reason && (
          <TooltipPortal>
            <TooltipContent
              side="bottom"
              collisionPadding={16}
              className="max-w-xs"
            >
              {t(reason)}
            </TooltipContent>
          </TooltipPortal>
        )}
      </Tooltip>
    );
  }

  return (
    <AssertionsBreakdownTooltip
      assertionsByRun={assertionsByRun}
      passThreshold={passThreshold}
      runsPerItem={runsPerItem}
    >
      {tag}
    </AssertionsBreakdownTooltip>
  );
};

const isExperimentFinished = (
  experiments: CustomMeta["experiments"],
  experimentId: string,
): boolean => {
  const exp = experiments?.find((e) => e.id === experimentId);
  return isExperimentTerminal(exp?.status as EXPERIMENT_STATUS | undefined);
};

const PassedCell: React.FC<CellContext<ExperimentsCompare, unknown>> = (
  context,
) => {
  const row = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const { experimentsIds, experiments } = (custom ?? {}) as Partial<CustomMeta>;
  if (experimentsIds) {
    const renderContent = (
      item: ExperimentItem | undefined,
      experimentId: string,
    ) => {
      const finished = isExperimentFinished(experiments, experimentId);
      const statusInfo = getStatusInfoForExperiment(
        row,
        experimentId,
        item,
        finished,
      );
      return (
        <div className="flex h-full items-center">
          <StatusTag {...statusInfo} />
        </div>
      );
    };

    return (
      <VerticallySplitCellWrapper
        renderContent={renderContent}
        experimentCompare={row}
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
        rowId={context.row.id}
      />
    );
  }

  const statusInfo = getStatusFromExperimentItems(row);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <StatusTag {...statusInfo} />
    </CellWrapper>
  );
};

export default PassedCell;
