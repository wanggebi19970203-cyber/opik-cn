import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import { Alert } from "@/types/alerts";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import useAlertsBatchDeleteMutation from "@/api/alerts/useAlertsBatchDeleteMutation";
import useAppStore from "@/store/AppStore";

const AlertsRowActionsCell: React.FunctionComponent<
  CellContext<Alert, unknown>
> = (context) => {
  const { t } = useTranslation();
  const resetKeyRef = useRef(0);
  const alert = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const { mutate } = useAlertsBatchDeleteMutation();

  const deleteAlertHandler = useCallback(() => {
    if (!alert.id) return;

    mutate({ ids: [alert.id] });
  }, [alert.id, mutate]);

  const handleEditClick = useCallback(() => {
    if (!alert.id) return;

    navigate({
      to: "/$workspaceName/alerts/$alertId",
      params: { workspaceName, alertId: alert.id },
      search: (prev: Record<string, unknown>) => prev,
    });
  }, [navigate, workspaceName, alert.id]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteAlertHandler}
        title={t("alerts.confirmDialog.deleteSingle.title")}
        description={t("alerts.confirmDialog.deleteSingle.description")}
        confirmText={t("alerts.confirmDialog.deleteSingle.confirmText")}
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">{t("alerts.actions.menuLabel")}</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={handleEditClick}>
            <Pencil className="mr-2 size-4" />
            {t("alerts.actions.edit")}
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
            {t("alerts.actions.delete")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default AlertsRowActionsCell;
