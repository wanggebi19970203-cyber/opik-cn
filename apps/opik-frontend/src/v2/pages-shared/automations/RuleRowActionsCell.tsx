import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import { MoreHorizontal, Pencil, Copy, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { EvaluatorsRule } from "@/types/automations";
import { CellContext } from "@tanstack/react-table";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useRulesBatchDeleteMutation from "@/api/automations/useRulesBatchDeleteMutation";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";

interface RuleRowActionsCellProps {
  openEditDialog: (ruleId: string) => void;
  openCloneDialog: (ruleId: string) => void;
}

const RuleRowActionsCell: React.FC<
  RuleRowActionsCellProps & CellContext<EvaluatorsRule, unknown>
> = ({ openEditDialog, openCloneDialog, row, column, table }) => {
  const { t } = useTranslation("online-evaluation");
  const resetKeyRef = useRef(0);
  const rule = row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useRulesBatchDeleteMutation();

  const deleteRuleHandler = useCallback(() => {
    mutate({
      ids: [rule.id],
    });
  }, [rule.id, mutate]);

  return (
    <CellWrapper
      metadata={column.columnDef.meta}
      tableMetadata={table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteRuleHandler}
        title={t("ruleRowActions.deleteTitle")}
        description={t("ruleRowActions.deleteDescription")}
        confirmText={t("ruleRowActions.deleteConfirmText")}
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5 ">
            <span className="sr-only">{t("ruleRowActions.actionsMenu")}</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem onClick={() => openEditDialog(rule.id)}>
            <Pencil className="mr-2 size-4" />
            {t("ruleRowActions.edit")}
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => openCloneDialog(rule.id)}>
            <Copy className="mr-2 size-4" />
            {t("ruleRowActions.clone")}
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
            {t("ruleRowActions.delete")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default RuleRowActionsCell;
