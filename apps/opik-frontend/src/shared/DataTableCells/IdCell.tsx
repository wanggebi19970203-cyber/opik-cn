import React, { useCallback } from "react";
import { CellContext } from "@tanstack/react-table";
import { Copy } from "lucide-react";
import copy from "clipboard-copy";
import { useTranslation } from "react-i18next";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { useToast } from "@/ui/use-toast";
import { Button } from "@/ui/button";

const IdCell = (context: CellContext<unknown, string>) => {
  const { t } = useTranslation();
  const value = context.getValue();
  const { toast } = useToast();

  const copyClickHandler = useCallback(
    (event: React.MouseEvent<HTMLButtonElement>) => {
      event.stopPropagation();
      toast({
        description: t("common:messages.idCopiedToClipboard"),
      });
      copy(value);
    },
    [value, toast, t],
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="group"
    >
      <TooltipWrapper content={value} stopClickPropagation>
        <div className="flex max-w-full items-center">
          <div className="truncate">{value}</div>
          <Button
            size="icon-xs"
            variant="ghost"
            className="hidden group-hover:inline-flex"
            onClick={copyClickHandler}
          >
            <Copy />
          </Button>
        </div>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default IdCell;
