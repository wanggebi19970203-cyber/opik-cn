import React from "react";
import { useTranslation } from "react-i18next";
import { ColumnData, ROW_HEIGHT } from "@/types/shared";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import RefreshButton from "@/shared/RefreshButton/RefreshButton";
import { AggregatedCandidate } from "@/types/optimizations";

interface OptimizationTrialsControlsProps {
  onRefresh: () => void;
  isFetching?: boolean;
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
  isFetching,
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
      <RefreshButton
        tooltip={t("optimization.trials.refreshTooltip")}
        isFetching={isFetching}
        onRefresh={onRefresh}
      />
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
