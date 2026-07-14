import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Trash, Tag } from "lucide-react";
import slugify from "slugify";

import { Button } from "@/ui/button";
import { Thread } from "@/types/traces";
import useThreadBatchDeleteMutation from "@/api/traces/useThreadBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import ExportToButton from "@/shared/ExportToButton/ExportToButton";
import AddToDropdown from "@/v1/pages-shared/traces/AddToDropdown/AddToDropdown";
import EvaluateButton from "@/v1/pages-shared/automations/EvaluateButton/EvaluateButton";
import RunEvaluationDialog from "@/v1/pages-shared/automations/RunEvaluationDialog/RunEvaluationDialog";
import useFilteredRulesList from "@/api/automations/useFilteredRulesList";
import AddTagDialog, {
  TAG_ENTITY_TYPE,
} from "@/v1/pages-shared/traces/AddTagDialog/AddTagDialog";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { mapRowDataForExport } from "@/lib/traces/exportUtils";
import { usePermissions } from "@/contexts/PermissionsContext";

type ThreadsActionsPanelProps = {
  getDataForExport: () => Promise<Thread[]>;
  selectedRows: Thread[];
  columnsToExport: string[];
  projectName: string;
  projectId: string;
};

const ThreadsActionsPanel: React.FunctionComponent<
  ThreadsActionsPanelProps
> = ({
  getDataForExport,
  selectedRows,
  columnsToExport,
  projectName,
  projectId,
}) => {
  const { t } = useTranslation();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useThreadBatchDeleteMutation();
  const disabled = !selectedRows?.length;
  const isExportEnabled = useIsFeatureEnabled(FeatureToggleKeys.EXPORT_ENABLED);

  const {
    permissions: { canLogTraceSpanThread },
  } = usePermissions();

  const { rules, isLoading: isRulesLoading } = useFilteredRulesList({
    projectId,
    entityType: "thread",
  });

  const deleteThreadsHandler = useCallback(() => {
    mutate({
      projectId,
      ids: selectedRows.map((row) => row.id),
    });
  }, [projectId, mutate, selectedRows]);

  const mapRowData = useCallback(async () => {
    const rows = await getDataForExport();
    return mapRowDataForExport(rows, columnsToExport);
  }, [getDataForExport, columnsToExport]);

  const generateFileName = useCallback(
    (extension = "csv") => {
      return `${slugify(projectName, { lower: true })}-threads.${extension}`;
    },
    [projectName],
  );

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 2}
        setOpen={setOpen}
        onConfirm={deleteThreadsHandler}
        title={t("tracing.actions.deleteThread")}
        description={t("tracing.actions.deleteThreadDescription")}
        confirmText={t("tracing.actions.deleteThread")}
        confirmButtonVariant="destructive"
      />
      {canLogTraceSpanThread && (
        <AddTagDialog
          key={`tag-${resetKeyRef.current}`}
          rows={selectedRows}
          open={open === 3}
          setOpen={setOpen}
          projectId={projectId}
          type={TAG_ENTITY_TYPE.threads}
        />
      )}
      <RunEvaluationDialog
        key={`evaluation-${resetKeyRef.current}`}
        open={open === 4}
        setOpen={setOpen}
        projectId={projectId}
        entityIds={selectedRows.map((row) => row.thread_model_id)}
        entityType="thread"
        rules={rules}
        isLoading={isRulesLoading}
      />
      <AddToDropdown
        getDataForExport={getDataForExport}
        selectedRows={selectedRows}
        disabled={disabled}
        dataType="threads"
      />
      {canLogTraceSpanThread && (
        <TooltipWrapper content={t("tracing.actions.manageTags")}>
          <Button
            variant="outline"
            size="icon-sm"
            onClick={() => {
              setOpen(3);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            disabled={disabled}
          >
            <Tag />
          </Button>
        </TooltipWrapper>
      )}
      <EvaluateButton
        isNoRules={!rules?.length}
        disabled={disabled}
        onClick={() => {
          setOpen(4);
          resetKeyRef.current = resetKeyRef.current + 1;
        }}
      />
      <ExportToButton
        disabled={disabled || columnsToExport.length === 0 || !isExportEnabled}
        getData={mapRowData}
        generateFileName={generateFileName}
        tooltipContent={
          !isExportEnabled ? t("tracing.actions.exportDisabled") : undefined
        }
      />
      <TooltipWrapper content={t("tracing.actions.delete")}>
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setOpen(2);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default ThreadsActionsPanel;
