import React from "react";
import { CellContext } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";

import { Tag } from "@/ui/tag";
import CustomSquare from "@/icons/custom-square.svg?react";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";

const StatusCell = (context: CellContext<unknown, unknown>) => {
  const { t } = useTranslation();
  const { column, table } = context;
  const value = context.getValue() as boolean;
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
      className="gap-1"
    >
      <Tag variant={value ? "green" : "gray"} size={tagSize}>
        <div className="flex items-center gap-1">
          <CustomSquare className="size-3 shrink-0" />
          <span className="truncate">
            {value ? t("common:labels.enabled") : t("common:labels.disabled")}
          </span>
        </div>
      </Tag>
    </CellWrapper>
  );
};

export default StatusCell;
