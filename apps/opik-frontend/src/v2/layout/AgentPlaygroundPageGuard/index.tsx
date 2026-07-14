import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";
import AgentRunnerPage from "@/v2/pages/AgentRunnerPage/AgentRunnerPage";

const AgentPlaygroundPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canViewAgentPlayground },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canViewAgentPlayground}
      message={t("noAccess.noPermissionsUseAgentPlayground")}
    >
      <AgentRunnerPage />
    </NoAccessPageGuard>
  );
};

export default AgentPlaygroundPageGuard;
