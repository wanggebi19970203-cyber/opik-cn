import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import { MoreHorizontal, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useOptimizationBatchDeleteMutation from "@/api/optimizations/useOptimizationBatchDeleteMutation";
import { Optimization } from "@/types/optimizations";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";

const OptimizationRowActionsCell: React.FunctionComponent<
  CellContext<Optimization, unknown>
> = (context) => {
  const { t } = useTranslation("pages/optimizations");
  const resetKeyRef = useRef(0);
  const organisation = context.row.original;
  const [open, setOpen] = useState<boolean>(false);

  const { mutate } = useOptimizationBatchDeleteMutation();

  const deleteOptimizationHandler = useCallback(() => {
    mutate({
      ids: [organisation.id],
    });
  }, [organisation, mutate]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteOptimizationHandler}
        title={t("optimizations.confirmDialog.deleteSingle.title")}
        description={t("optimizations.confirmDialog.deleteSingle.description")}
        confirmText={t("optimizations.confirmDialog.deleteSingle.confirmText")}
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">{t("optimizations.actionsMenu")}</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            variant="destructive"
          >
            <Trash className="mr-2 size-4" />
            {t("optimizations.actions.delete")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default OptimizationRowActionsCell;
