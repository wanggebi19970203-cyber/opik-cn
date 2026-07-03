import React, { useCallback, useRef, useState } from "react";
import { CellContext } from "@tanstack/react-table";
import { MoreHorizontal, Pencil, Copy, Trash } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import { Dashboard } from "@/types/dashboard";
import useDashboardBatchDeleteMutation from "@/api/dashboards/useDashboardBatchDeleteMutation";
import AddEditCloneDashboardDialog from "@/v2/pages-shared/dashboards/AddEditCloneDashboardDialog/AddEditCloneDashboardDialog";
import { usePermissions } from "@/contexts/PermissionsContext";

export const DashboardRowActionsCell: React.FunctionComponent<
  CellContext<Dashboard, unknown>
> = (context) => {
  const { t } = useTranslation("pages/dashboards");
  const resetKeyRef = useRef(0);
  const dashboard = context.row.original;

  const {
    permissions: {
      canCreateDashboards,
      canEditDashboards,
      canDeleteDashboards,
    },
  } = usePermissions();

  const { mutate: deleteDashboardMutate } = useDashboardBatchDeleteMutation();

  const [open, setOpen] = useState<boolean>(false);
  const [openEdit, setOpenEdit] = useState<boolean>(false);
  const [openClone, setOpenClone] = useState<boolean>(false);
  const [openConfirmDialog, setOpenConfirmDialog] = useState<boolean>(false);

  const deleteDashboard = useCallback(() => {
    deleteDashboardMutate({
      ids: [dashboard.id],
    });
  }, [dashboard, deleteDashboardMutate]);

  const handleEdit = useCallback(() => {
    setOpenEdit(true);
    setOpen(false);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const handleClone = useCallback(() => {
    setOpenClone(true);
    setOpen(false);
    resetKeyRef.current = resetKeyRef.current + 1;
  }, []);

  const handleDelete = useCallback(() => {
    setOpenConfirmDialog(true);
    setOpen(false);
  }, []);

  if (!canEditDashboards && !canCreateDashboards && !canDeleteDashboards) {
    return null;
  }

  return (
    <div
      className="flex items-center justify-end gap-2"
      onClick={(e) => e.stopPropagation()}
    >
      <DropdownMenu open={open} onOpenChange={setOpen}>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon">
            <span className="sr-only">{t("dashboards.actions.menuLabel")}</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          {canEditDashboards && (
            <DropdownMenuItem onClick={handleEdit}>
              <Pencil className="mr-2 size-4" />
              {t("dashboards.actions.edit")}
            </DropdownMenuItem>
          )}
          {canCreateDashboards && (
            <DropdownMenuItem onClick={handleClone}>
              <Copy className="mr-2 size-4" />
              {t("dashboards.actions.clone")}
            </DropdownMenuItem>
          )}
          {(canEditDashboards || canCreateDashboards) &&
            canDeleteDashboards && <DropdownMenuSeparator />}
          {canDeleteDashboards && (
            <DropdownMenuItem onClick={handleDelete} variant="destructive">
              <Trash className="mr-2 size-4" />
              {t("dashboards.actions.delete")}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      <AddEditCloneDashboardDialog
        mode="edit"
        key={`edit-${resetKeyRef.current}`}
        dashboard={dashboard}
        open={openEdit}
        setOpen={setOpenEdit}
      />
      <AddEditCloneDashboardDialog
        mode="clone"
        key={`clone-${resetKeyRef.current}`}
        dashboard={dashboard}
        open={openClone}
        setOpen={setOpenClone}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={openConfirmDialog}
        setOpen={setOpenConfirmDialog}
        onConfirm={deleteDashboard}
        title={t("dashboards.confirmDialog.deleteSingle.title")}
        description={t("dashboards.confirmDialog.deleteSingle.description")}
        confirmText={t("dashboards.confirmDialog.deleteSingle.confirmText")}
        confirmButtonVariant="destructive"
      />
    </div>
  );
};
