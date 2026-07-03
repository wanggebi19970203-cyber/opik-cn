import { CellContext } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";

import { Tag, TagProps } from "@/ui/tag";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { DATASET_TYPE } from "@/types/datasets";
import { ROW_HEIGHT } from "@/types/shared";
import { TAG_SIZE_MAP } from "@/constants/shared";
import { getTypeLabels } from "@/v2/pages/TestSuitesPage/columns";

const VARIANT_MAP: Record<string, TagProps["variant"]> = {
  [DATASET_TYPE.TEST_SUITE]: "purple",
  [DATASET_TYPE.DATASET]: "yellow",
};

const DatasetTypeCell = (context: CellContext<unknown, unknown>) => {
  const { t } = useTranslation("pages/test-suites");
  const { column, table } = context;
  const value = (context.getValue() as string) ?? DATASET_TYPE.DATASET;
  const rowHeight = table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const variant = VARIANT_MAP[value] ?? "yellow";
  const typeLabels = getTypeLabels(t);
  const label = typeLabels[value] ?? t("testSuites.columnsPage.dataset");
  const tagSize = TAG_SIZE_MAP[rowHeight];

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
      className="gap-1"
    >
      <Tag variant={variant} size={tagSize}>
        {label}
      </Tag>
    </CellWrapper>
  );
};

export default DatasetTypeCell;
