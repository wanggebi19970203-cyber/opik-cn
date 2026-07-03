import React, { useCallback, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Trash } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { DatasetItem } from "@/types/datasets";
import { useDeleteItem } from "@/store/TestSuiteDraftStore";
import RemoveDatasetItemsDialog from "./RemoveDatasetItemsDialog";
import { useDatasetItemDeletePreference } from "./hooks/useDatasetItemDeletePreference";

export const DatasetItemRowActionsCell: React.FC<
  CellContext<DatasetItem, unknown>
> = (context) => {
  const { t } = useTranslation("datasets");
  const datasetItem = context.row.original;
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [dontAskAgain] = useDatasetItemDeletePreference();
  const deleteItem = useDeleteItem();

  const performDelete = useCallback(() => {
    deleteItem(datasetItem.id);
  }, [datasetItem.id, deleteItem]);

  const handleDeleteClick = useCallback(() => {
    if (dontAskAgain) {
      performDelete();
    } else {
      setConfirmOpen(true);
    }
  }, [dontAskAgain, performDelete]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <RemoveDatasetItemsDialog
        open={confirmOpen}
        setOpen={setConfirmOpen}
        onConfirm={performDelete}
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">{t('actionsMenu')}</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={handleDeleteClick} variant="destructive">
            <Trash className="mr-2 size-4" />
            {t('delete')}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};
