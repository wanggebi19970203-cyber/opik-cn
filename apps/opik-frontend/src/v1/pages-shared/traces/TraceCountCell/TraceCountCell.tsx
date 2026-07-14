import React from "react";
import { useTranslation } from "react-i18next";
import { CellContext } from "@tanstack/react-table";

import LinkCell from "@/shared/DataTableCells/LinkCell";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import { useExperimentsTraceCountNavigation } from "@/v1/pages-shared/experiments/useExperimentsTraceCountNavigation";

type CustomMeta<TData extends GroupedExperiment> = {
  tooltip?: string;
  getIsDisabled?: (row: TData) => boolean;
  disabledTooltip?: string;
};

/**
 * Cell component for displaying trace count with navigation functionality.
 * Reuses LinkCell internally and adds trace count specific logic:
 * - Uses navigation hook internally to handle navigation
 * - Disables navigation when project_id is missing
 * - Shows appropriate tooltip based on disabled state
 */
const TraceCountCell = <TData extends GroupedExperiment>(
  context: CellContext<TData, unknown>,
) => {
  const { t } = useTranslation("tracing");
  const { custom } = context.column.columnDef.meta ?? {};
  const { tooltip, getIsDisabled, disabledTooltip } = (custom ??
    {}) as CustomMeta<TData>;

  const resolvedTooltip = tooltip ?? t("traceCountCell.viewExperimentTraces");
  const resolvedDisabledTooltip =
    disabledTooltip ?? t("traceCountCell.noProjectAssociated");

  const navigateToExperimentTraces = useExperimentsTraceCountNavigation();
  const value = context.getValue() as number | string;
  const row = context.row.original;

  // Check if disabled: value is 0/falsy OR getIsDisabled returns true OR no project_id
  const isDisabled =
    !value || (getIsDisabled ? getIsDisabled(row) : !row.project_id);

  // Create modified context with enhanced customMeta for LinkCell
  const modifiedContext: CellContext<TData, unknown> = {
    ...context,
    column: {
      ...context.column,
      columnDef: {
        ...context.column.columnDef,
        meta: {
          ...context.column.columnDef.meta,
          custom: {
            ...custom,
            callback: navigateToExperimentTraces,
            tooltip: resolvedTooltip,
            getIsDisabled: () => isDisabled,
            disabledTooltip: resolvedDisabledTooltip,
          },
        },
      },
    },
  };

  return <LinkCell {...modifiedContext} />;
};

export default TraceCountCell;
