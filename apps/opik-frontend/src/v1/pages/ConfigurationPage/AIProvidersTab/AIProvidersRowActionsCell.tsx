import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import ManageAIProviderDialog from "@/v1/pages-shared/llm/ManageAIProviderDialog/ManageAIProviderDialog";
import { ProviderObject } from "@/types/providers";
import useProviderKeysDeleteMutation from "@/api/provider-keys/useProviderKeysDeleteMutation";

const AIProvidersRowActionsCell: React.FunctionComponent<
  CellContext<ProviderObject, unknown>
> = (context) => {
  const { t } = useTranslation();
  const resetKeyRef = useRef(0);
  const providerKey = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  // Check if this provider is read-only (system-managed)
  const isReadOnly = providerKey.read_only === true;

  const { mutate: deleteProviderKey } = useProviderKeysDeleteMutation();

  const deleteProviderKeyHandler = useCallback(() => {
    deleteProviderKey({
      providerId: providerKey.id,
    });
  }, [providerKey.id, deleteProviderKey]);

  // Don't show actions menu for read-only providers
  if (isReadOnly) {
    return (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
        className="justify-end p-0"
        stopClickPropagation
      >
        <span className="pr-2 text-xs text-muted-foreground">
          {t("settings.providers.readOnlyLabel")}
        </span>
      </CellWrapper>
    );
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ManageAIProviderDialog
        key={`edit-${resetKeyRef.current}`}
        providerKey={providerKey}
        open={open === 2}
        setOpen={setOpen}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteProviderKeyHandler}
        title={t("settings.providers.confirmDialog.delete.title")}
        description={t("settings.providers.confirmDialog.delete.description")}
        confirmText={t("settings.providers.confirmDialog.delete.confirmText")}
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">{t("settings.actions.menuLabel")}</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(2);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            <Pencil className="mr-2 size-4" />
            {t("settings.actions.edit")}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            variant="destructive"
          >
            <Trash className="mr-2 size-4" />
            {t("settings.actions.delete")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default AIProvidersRowActionsCell;
