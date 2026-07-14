import React from "react";
import { useTranslation } from "react-i18next";
import LoggedDataStatus from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/LoggedDataStatus";

type WaitForDataPanelProps = {
  status: "waiting" | "logged";
  description?: string;
  onExplore?: () => void;
};

const WaitForDataPanel: React.FC<WaitForDataPanelProps> = ({
  status,
  description: descriptionProp,
  onExplore,
}) => {
  const { t } = useTranslation();
  const description =
    descriptionProp ?? t("onboarding.integrationExplorer.dataFlowDescription");
  return (
    <div>
      <div className="flex items-center gap-3 rounded-lg border bg-background p-4">
        <LoggedDataStatus status={status} onExplore={onExplore} />
      </div>
      <div className="comet-body-s mt-2 text-muted-slate">{description}</div>
    </div>
  );
};

export default WaitForDataPanel;
