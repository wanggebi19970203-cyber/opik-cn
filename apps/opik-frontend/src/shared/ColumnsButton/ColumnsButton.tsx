import { Columns3 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button, ButtonProps } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { ColumnData } from "@/types/shared";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ColumnsContent from "@/shared/ColumnsContent/ColumnsContent";
import type { ColumnsContentExtraSection } from "@/shared/ColumnsContent/ColumnsContent";
import { useColumnsCount } from "@/shared/ColumnsContent/useColumnsCount";

export type ColumnsButtonProps<TColumnData> = {
  columns: ColumnData<TColumnData>[];
  selectedColumns: string[];
  onSelectionChange: (selectedColumns: string[]) => void;
  order: string[];
  onOrderChange: (order: string[]) => void;
  sections?: ColumnsContentExtraSection<TColumnData>[];
  excludeFromSelectAll?: string[];
  layout?: "icon" | "labeled";
  size?: ButtonProps["size"];
};

const ColumnsButton = <TColumnData,>({
  columns,
  selectedColumns,
  onSelectionChange,
  order,
  onOrderChange,
  sections,
  excludeFromSelectAll = [],
  layout = "icon",
  size = "sm",
}: ColumnsButtonProps<TColumnData>) => {
  const { t } = useTranslation("common");
  const { selectedCount, totalCount } = useColumnsCount({
    columns,
    selectedColumns,
    sections,
    excludeFromSelectAll,
  });

  return (
    <DropdownMenu>
      <TooltipWrapper content={t("labels.columns")}>
        <DropdownMenuTrigger asChild>
          {layout === "labeled" ? (
            <Button
              variant="outline"
              size={size}
              data-testid="columns-button"
              className="font-normal focus-visible:border-primary focus-visible:ring-0"
            >
              {t("labels.columns")}
              <div className="ml-1 rounded bg-muted-disabled px-1 text-muted-slate">
                {selectedCount}/{totalCount}
              </div>
            </Button>
          ) : (
            <Button
              variant="outline"
              size="icon-sm"
              data-testid="columns-button"
            >
              <Columns3 className="size-3.5" />
            </Button>
          )}
        </DropdownMenuTrigger>
      </TooltipWrapper>
      <DropdownMenuContent className="min-w-56 max-w-72 p-0" align="end">
        <ColumnsContent
          columns={columns}
          selectedColumns={selectedColumns}
          onSelectionChange={onSelectionChange}
          order={order}
          onOrderChange={onOrderChange}
          sections={sections}
          excludeFromSelectAll={excludeFromSelectAll}
          variant="menu"
        />
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default ColumnsButton;
