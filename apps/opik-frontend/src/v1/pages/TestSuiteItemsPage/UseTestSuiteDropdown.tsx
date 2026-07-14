import { useCallback, useRef, useState } from "react";
import { Blocks, ChevronDown, Code2 } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import AddExperimentDialog from "@/v1/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useLoadPlayground from "@/hooks/useLoadPlayground";
import { usePermissions } from "@/contexts/PermissionsContext";
import { DATASET_TYPE } from "@/types/datasets";

export interface UseTestSuiteDropdownProps {
  datasetName?: string;
  datasetId?: string;
  datasetVersionId?: string;
  disabled?: boolean;
  isTestSuite?: boolean;
}

function UseTestSuiteDropdown({
  datasetName = "",
  datasetId = "",
  datasetVersionId,
  disabled = false,
  isTestSuite = true,
}: UseTestSuiteDropdownProps) {
  const { t } = useTranslation("test-suite-items");
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
        />
      )}
      {canUsePlayground && (
        <ConfirmDialog
          key={`confirm-dialog-${resetKeyRef.current}`}
          open={openConfirmDialog}
          setOpen={setOpenConfirmDialog}
          onConfirm={handleLoadPlayground}
          title={t("useTestSuiteDropdown.loadIntoPlayground", {
            type: isTestSuite
              ? t("useTestSuiteDropdown.testSuite")
              : t("useTestSuiteDropdown.dataset"),
          })}
          description={t("useTestSuiteDropdown.loadDescription", {
            type: isTestSuite
              ? t("useTestSuiteDropdown.testSuite")
              : t("useTestSuiteDropdown.dataset"),
          })}
          confirmText={
            isTestSuite
              ? t("useTestSuiteDropdown.loadTestSuite")
              : t("useTestSuiteDropdown.loadDataset")
          }
        />
      )}
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="sm" disabled={disabled}>
            {isTestSuite
              ? t("useTestSuiteDropdown.useSuite")
              : t("useTestSuiteDropdown.useDataset")}
            <ChevronDown className="ml-2 size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-80">
          {canUsePlayground && (
            <DropdownMenuItem
              onClick={handleOpenPlaygroundClick}
              disabled={disabled || isPendingProviderKeys}
            >
              <Blocks className="mr-2 mt-0.5 size-4 shrink-0 self-start" />
              <div className="comet-body-s flex flex-col">
                <span>{t("useTestSuiteDropdown.openInPlayground")}</span>
                <span className="text-light-slate">
                  {t("useTestSuiteDropdown.testPromptsOver", {
                    type: isTestSuite
                      ? t("useTestSuiteDropdown.testSuite")
                      : t("useTestSuiteDropdown.dataset"),
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
                <span>{t("useTestSuiteDropdown.runAnExperiment")}</span>
                <span className="text-light-slate">
                  {t("useTestSuiteDropdown.runExperimentDescription", {
                    type: isTestSuite
                      ? t("useTestSuiteDropdown.testSuite")
                      : t("useTestSuiteDropdown.dataset"),
                  })}
                </span>
              </div>
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}

export default UseTestSuiteDropdown;
