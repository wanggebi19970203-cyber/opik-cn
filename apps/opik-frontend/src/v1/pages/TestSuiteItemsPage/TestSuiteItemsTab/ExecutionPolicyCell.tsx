import React from "react";
import { useTranslation } from "react-i18next";
import { CellContext, ColumnMeta, TableMeta } from "@tanstack/react-table";
import { DatasetItem } from "@/types/datasets";
import { useEffectiveItemExecutionPolicy } from "@/hooks/useEffectiveItemExecutionPolicy";
import { useEffectiveExecutionPolicy } from "@/hooks/useEffectiveExecutionPolicy";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";

interface ExecutionPolicyCellInnerProps {
  itemId: string;
  item: DatasetItem;
  metadata: ColumnMeta<DatasetItem, unknown> | undefined;
  tableMetadata: TableMeta<DatasetItem> | undefined;
}

const ExecutionPolicyCellInner: React.FC<ExecutionPolicyCellInnerProps> = ({
  itemId,
  item,
  metadata,
  tableMetadata,
}) => {
  const { t } = useTranslation("test-suite-items");
  const suiteId = useSuiteIdFromURL();
  const globalPolicy = useEffectiveExecutionPolicy(suiteId);
  const localPolicy = useEffectiveItemExecutionPolicy(
    itemId,
    item.execution_policy,
  );

  if (localPolicy === null) {
    return (
      <CellWrapper metadata={metadata} tableMetadata={tableMetadata}>
        <span className="text-light-slate">
          {t("testSuiteItems.executionPolicy.mustPass", {
            threshold: globalPolicy.pass_threshold,
            runs: globalPolicy.runs_per_item,
          })}
        </span>
      </CellWrapper>
    );
  }

  return (
    <CellWrapper metadata={metadata} tableMetadata={tableMetadata}>
      <span>
        {t("testSuiteItems.executionPolicy.mustPass", {
          threshold: localPolicy.pass_threshold,
          runs: localPolicy.runs_per_item,
        })}
      </span>
    </CellWrapper>
  );
};

export const ExecutionPolicyCell: React.FC<
  CellContext<DatasetItem, unknown>
> = (context) => {
  const item = context.row.original;

  return (
    <ExecutionPolicyCellInner
      itemId={item.id}
      item={item}
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    />
  );
};
