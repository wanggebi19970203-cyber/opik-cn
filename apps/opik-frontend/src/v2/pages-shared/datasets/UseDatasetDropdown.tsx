import { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Blocks, Code2, Play } from "lucide-react";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import AddExperimentDialog from "@/v2/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import useLoadPlayground from "@/v2/pages-shared/playground/useLoadPlayground";
import { usePermissions } from "@/contexts/PermissionsContext";
import { DATASET_TYPE } from "@/types/datasets";

export interface UseDatasetDropdownProps {
  datasetName?: string;
  datasetId?: string;
  datasetVersionId?: string;
  disabled?: boolean;
  entityName?: string;
  projectId?: string | null;
  isEmpty?: boolean;
  isTestSuite: boolean;
}

function UseDatasetDropdown({
  datasetName = "",
  datasetId = "",
  datasetVersionId,
  disabled = false,
  entityName = "dataset",
  projectId,
  isEmpty = false,
  isTestSuite,
}: UseDatasetDropdownProps) {
  const { t } = useTranslation("datasets");
  const resetKeyRef = useRef(0);
  const resetDialogKeyRef = useRef(0);
  const [openExperimentDialog, setOpenExperimentDialog] = useState(false);
  const [openConfirmDialog, setOpenConfirmDialog] = useState(false);

  const {
    permissions: { canViewExperiments, canCreateExperiments, canUsePlayground },
  } = usePermissions();

  const hasAnyAction = canUsePlayground || canCreateExperiments;

  const { loadPlayground, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPlayground();

  const handleLoadPlayground = useCallback(() => {
    loadPlayground({
      datasetId,
      datasetVersionId,
      datasetType: isTestSuite ? DATASET_TYPE.TEST_SUITE : DATASET_TYPE.DATASET,
    });
  }, [loadPlayground, datasetId, datasetVersionId, isTestSuite]);

  const handleOpenPlaygroundClick = () => {
    if (isPlaygroundEmpty) {
      handleLoadPlayground();
    } else {
      resetKeyRef.current += 1;
      setOpenConfirmDialog(true);
    }
  };

  if (!hasAnyAction) return null;

  return (
    <>
      {canViewExperiments && (
        <AddExperimentDialog
          key={`experiment-dialog-${resetDialogKeyRef.current}`}
          open={openExperimentDialog}
          setOpen={setOpenExperimentDialog}
          datasetName={datasetName}
          projectId={projectId}
        />
      )}
      {canUsePlayground && (
        <ConfirmDialog
          key={`confirm-dialog-${resetKeyRef.current}`}
          open={openConfirmDialog}
          setOpen={setOpenConfirmDialog}
          onConfirm={handleLoadPlayground}
          title={t("useDataset.loadIntoPlayground.title", { entityName })}
          description={t("useDataset.loadIntoPlayground.description", {
            entityName,
          })}
          confirmText={t("useDataset.loadIntoPlayground.confirmText", {
            entityName,
          })}
        />
      )}
      {disabled || isEmpty ? (
        <TooltipWrapper
          content={
            isEmpty
              ? t("useDataset.emptyTooltip", { entityName })
              : t("useDataset.runIn")
          }
        >
          <span className="inline-flex">
            <Button variant="outline" size="icon-sm" disabled>
              <Play />
            </Button>
          </span>
        </TooltipWrapper>
      ) : (
        <DropdownMenu>
          <TooltipWrapper content={t("useDataset.runIn")}>
            <DropdownMenuTrigger asChild>
              <Button
                variant="outline"
                size="icon-sm"
                data-testid="dataset-header-run-in-trigger"
              >
                <Play />
              </Button>
            </DropdownMenuTrigger>
          </TooltipWrapper>
          <DropdownMenuContent
            align="end"
            className="w-80"
            onCloseAutoFocus={(e) => e.preventDefault()}
          >
            {canUsePlayground && (
              <DropdownMenuItem
                onClick={handleOpenPlaygroundClick}
                disabled={disabled || isPendingProviderKeys}
              >
                <Blocks className="mr-2 mt-0.5 size-4 shrink-0 self-start" />
                <div className="comet-body-s flex flex-col">
                  <span>{t("useDataset.openInPlayground")}</span>
                  <span className="text-light-slate">
                    {t("useDataset.openInPlaygroundDescription", {
                      entityName,
                    })}
                  </span>
                </div>
              </DropdownMenuItem>
            )}
            {canCreateExperiments && (
              <DropdownMenuItem
                onClick={() => {
                  resetDialogKeyRef.current += 1;
                  setOpenExperimentDialog(true);
                }}
                disabled={disabled}
              >
                <Code2 className="mr-2 mt-0.5 size-4 shrink-0 self-start" />
                <div className="comet-body-s flex flex-col">
                  <span>{t("useDataset.runExperiment")}</span>
                  <span className="text-light-slate">
                    {t("useDataset.runExperimentDescription", { entityName })}
                  </span>
                </div>
              </DropdownMenuItem>
            )}
          </DropdownMenuContent>
        </DropdownMenu>
      )}
    </>
  );
}

export default UseDatasetDropdown;
