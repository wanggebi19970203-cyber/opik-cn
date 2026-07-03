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
import { useTranslation } from "react-i18next";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useExperimentBatchDeleteMutation from "@/api/datasets/useExperimentBatchDeleteMutation";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { UpdateExperimentDialog } from "@/shared/UpdateExperimentDialog/UpdateExperimentDialog";
import useExperimentUpdateMutation from "@/api/datasets/useExperimentUpdate";

const ExperimentRowActionsCell: React.FunctionComponent<
  CellContext<GroupedExperiment, unknown>
> = (context) => {
  const { t } = useTranslation("pages/experiments");
  const resetKeyRef = useRef(0);
  const experiment = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const experimentBatchDeleteMutation = useExperimentBatchDeleteMutation();
  const experimentUpdateMutation = useExperimentUpdateMutation();

  const deleteExperimentsHandler = useCallback(() => {
    experimentBatchDeleteMutation.mutate({
      ids: [experiment.id],
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [experiment]);

  const updateExperimentHandler = useCallback(
    (name: string, configuration: object) => {
      experimentUpdateMutation.mutate({
        experiment: {
          id: experiment.id,
          name: name,
          metadata: configuration,
        },
      });
    },
    [experiment, experimentUpdateMutation],
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <UpdateExperimentDialog
        key={`edit-${resetKeyRef.current}`}
        open={open === 2}
        setOpen={setOpen}
        onConfirm={updateExperimentHandler}
        latestName={experiment.name}
        latestConfiguration={experiment.metadata}
      />
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteExperimentsHandler}
        title={t("experiments.delete")}
        description={t("experiments.deleteDescription")}
        confirmText={t("experiments.delete")}
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5">
            <span className="sr-only">{t("experiments.actionsMenu")}</span>
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
            {t("experiments.editAction")}
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
            {t("experiments.deleteAction")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default ExperimentRowActionsCell;
