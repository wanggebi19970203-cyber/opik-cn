import React from "react";
import { useTranslation } from "react-i18next";
import LoggedDataStatus from "@/v1/pages-shared/onboarding/IntegrationExplorer/components/LoggedDataStatus";

type WaitForDataPanelProps = {
  status: "waiting" | "logged";
  description?: string;
};

const WaitForDataPanel: React.FC<WaitForDataPanelProps> = ({
  status,
  description,
}) => {
  const { t } = useTranslation();
  const displayDescription =
    description ?? t("integrationExplorer.dataFlowDescription");
  return (
    <div>
      <div className="flex items-center gap-3 rounded-lg border bg-background p-4">
        <LoggedDataStatus status={status} />
      </div>
      <div className="comet-body-s mt-2 text-muted-slate">
        {displayDescription}
      </div>
    </div>
  );
};

export default WaitForDataPanel;
