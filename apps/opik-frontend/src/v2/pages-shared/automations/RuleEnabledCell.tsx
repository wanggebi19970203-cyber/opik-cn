import React from "react";
import { CellContext } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";
import { EvaluatorsRule } from "@/types/automations";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";

const RuleEnabledCell = (
  context: CellContext<EvaluatorsRule, unknown>,
): React.ReactElement => {
  const { t } = useTranslation("online-evaluation");
  const rule = context.row.original;

  // Default to true if enabled property doesn't exist yet
  const isEnabled = rule?.enabled ?? true;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="text-center text-sm">
        {isEnabled
          ? t("ruleEnabledCell.enabled")
          : t("ruleEnabledCell.disabled")}
      </span>
    </CellWrapper>
  );
};

export default RuleEnabledCell;
