import React from "react";
import { useTranslation } from "react-i18next";
import { RotateCw } from "lucide-react";
import { Button } from "@/ui/button";
import { ColumnData, ROW_HEIGHT } from "@/types/shared";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import { AggregatedCandidate } from "@/types/optimizations";

interface OptimizationTrialsControlsProps {
  onRefresh: () => void;
  rowHeight: ROW_HEIGHT;
  onRowHeightChange: (height: ROW_HEIGHT) => void;
  columnsDef: ColumnData<AggregatedCandidate>[];
  selectedColumns: string[];
  onSelectedColumnsChange: (columns: string[]) => void;
  columnsOrder: string[];
  onColumnsOrderChange: (order: string[]) => void;
}

const OptimizationTrialsControls: React.FC<OptimizationTrialsControlsProps> = ({
  onRefresh,
  rowHeight,
  onRowHeightChange,
  columnsDef,
  selectedColumns,
  onSelectedColumnsChange,
  columnsOrder,
  onColumnsOrderChange,
}) => {
  const { t } = useTranslation("pages/optimization");

  return (
    <div className="flex items-center gap-2">
      <TooltipWrapper content={t("optimization.trials.refreshTooltip")}>
        <Button
          variant="outline"
          size="icon-sm"
          className="shrink-0"
          onClick={onRefresh}
        >
          <RotateCw />
        </Button>
      </TooltipWrapper>
      <DataTableRowHeightSelector
        type={rowHeight}
        setType={onRowHeightChange}
      />
      <ColumnsButton
        columns={columnsDef}
        selectedColumns={selectedColumns}
        onSelectionChange={onSelectedColumnsChange}
        order={columnsOrder}
        onOrderChange={onColumnsOrderChange}
      />
    </div>
  );
};

export default OptimizationTrialsControls;
