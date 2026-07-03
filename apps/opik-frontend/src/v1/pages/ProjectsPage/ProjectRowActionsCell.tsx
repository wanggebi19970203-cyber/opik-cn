import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import { MoreHorizontal, Pencil, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { Project } from "@/types/projects";
import { CellContext } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";
import AddEditProjectDialog from "@/v1/pages/ProjectsPage/AddEditProjectDialog";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useProjectDeleteMutation from "@/api/projects/useProjectDeleteMutation";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { usePermissions } from "@/contexts/PermissionsContext";

export const ProjectRowActionsCell: React.FC<CellContext<Project, unknown>> = (
  context,
) => {
  const { t } = useTranslation();
  const resetKeyRef = useRef(0);
  const project = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const {
    permissions: { canCreateProjects, canDeleteProjects },
  } = usePermissions();

  const { mutate } = useProjectDeleteMutation();

  const deleteProjectHandler = useCallback(() => {
    mutate({
      projectId: project.id,
    });
  }, [project.id, mutate]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <AddEditProjectDialog
        key={`add-${resetKeyRef.current}`}
        project={project}
        open={open === 2}
        setOpen={setOpen}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteProjectHandler}
        title={t("projects:projects.rowActions.deleteTitle")}
        description={t("projects:projects.rowActions.deleteDescription")}
        confirmText={t("projects:projects.rowActions.deleteConfirm")}
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5 ">
            <span className="sr-only">
              {t("projects:projects.rowActions.actionsMenu")}
            </span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          {canCreateProjects && (
            <DropdownMenuItem
              onClick={() => {
                setOpen(2);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
            >
              <Pencil className="mr-2 size-4" />
              {t("projects:projects.rowActions.edit")}
            </DropdownMenuItem>
          )}
          {(canCreateProjects || canDeleteProjects) && (
            <DropdownMenuSeparator />
          )}
          {canDeleteProjects && (
            <DropdownMenuItem
              onClick={() => {
                setOpen(1);
                resetKeyRef.current = resetKeyRef.current + 1;
              }}
              variant="destructive"
            >
              <Trash className="mr-2 size-4" />
              {t("projects:projects.rowActions.delete")}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};
