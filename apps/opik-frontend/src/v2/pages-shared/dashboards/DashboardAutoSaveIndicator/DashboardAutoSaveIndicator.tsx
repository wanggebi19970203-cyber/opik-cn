import React from "react";
import { useTranslation } from "react-i18next";
import { Loader2, Check, AlertCircle } from "lucide-react";
import { DashboardSaveStatus } from "@/v2/pages-shared/dashboards/hooks/useDashboardPersistence";

interface DashboardAutoSaveIndicatorProps {
  saveStatus: DashboardSaveStatus;
}

const DashboardAutoSaveIndicator: React.FunctionComponent<
  DashboardAutoSaveIndicatorProps
> = ({ saveStatus }) => {
  const { t } = useTranslation("dashboards");
  if (saveStatus === "idle") return null;

  return (
    <div className="flex items-center gap-1 text-xs text-muted-slate">
      {saveStatus === "saving" && (
        <>
          <Loader2 className="size-3 animate-spin" />
          <span>{t("autoSave.saving")}</span>
        </>
      )}
      {saveStatus === "saved" && (
        <>
          <Check className="size-3" />
          <span>{t("autoSave.saved")}</span>
        </>
      )}
      {saveStatus === "error" && (
        <>
          <AlertCircle className="size-3 text-destructive" />
          <span className="text-destructive">{t("autoSave.saveFailed")}</span>
        </>
      )}
    </div>
  );
};

export default DashboardAutoSaveIndicator;
