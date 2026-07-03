import React, { useCallback } from "react";
import { DropdownOption, ROW_HEIGHT } from "@/types/shared";
import { Check, Rows3, UnfoldVertical } from "lucide-react";
import { useTranslation } from "react-i18next";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button, ButtonProps } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type DataTableRowHeightSelectorProps = {
  type: string;
  setType: (type: ROW_HEIGHT) => void;
  layout?: "icon" | "labeled";
  size?: ButtonProps["size"];
};

const DataTableRowHeightSelector: React.FunctionComponent<
  DataTableRowHeightSelectorProps
> = ({ type, setType, layout = "icon", size = "icon-sm" }) => {
  const { t } = useTranslation();

  const options: DropdownOption<ROW_HEIGHT>[] = [
    { value: ROW_HEIGHT.small, label: t("common.table.compact") },
    { value: ROW_HEIGHT.medium, label: t("common.table.medium") },
    { value: ROW_HEIGHT.large, label: t("common.table.detailed") },
  ];

  const handleSelect = useCallback(
    (value: ROW_HEIGHT) => {
      setType(value);
    },
    [setType],
  );

  return (
    <DropdownMenu>
      <TooltipWrapper content={t("common.table.rowSize")}>
        <DropdownMenuTrigger asChild>
          {layout === "labeled" ? (
            <Button variant="outline" size={size}>
              <UnfoldVertical className="mr-1.5 size-3.5" />
              {t("common.table.rowSize")}
            </Button>
          ) : (
            <Button
              variant="outline"
              size={size}
              className="focus-visible:border-primary focus-visible:ring-0"
            >
              <Rows3 />
            </Button>
          )}
        </DropdownMenuTrigger>
      </TooltipWrapper>
      <DropdownMenuContent align="end">
        {options.map(({ value, label }) => (
          <DropdownMenuItem key={value} onClick={() => handleSelect(value)}>
            <div className="relative flex w-full items-center pl-4">
              {type === value && <Check className="absolute -left-2 size-4" />}
              <span>{label}</span>
            </div>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default DataTableRowHeightSelector;
