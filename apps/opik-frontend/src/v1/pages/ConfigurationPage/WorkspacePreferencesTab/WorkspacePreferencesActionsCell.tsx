import { CellContext } from "@tanstack/react-table";
import { Button } from "@/ui/button";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { WorkspacePreference } from "@/constants/workspace-preferences";
import { useTranslation } from "react-i18next";

type CustomMeta = {
  onEdit: (row: WorkspacePreference) => void;
};

const WorkspacePreferencesActionsCell: React.FunctionComponent<
  CellContext<WorkspacePreference, unknown>
> = (context) => {
  const { t } = useTranslation();
  const { custom } = context.column.columnDef.meta ?? {};
  const { onEdit } = (custom ?? {}) as CustomMeta;
  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <Button
        size="sm"
        variant="ghost"
        onClick={() => onEdit(context.row.original)}
      >
        {t("common.buttons.edit")}
      </Button>
    </CellWrapper>
  );
};

export default WorkspacePreferencesActionsCell;
