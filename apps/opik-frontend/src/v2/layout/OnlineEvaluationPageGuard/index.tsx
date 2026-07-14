import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";
import OnlineEvaluationPage from "@/v2/pages/OnlineEvaluationPage/OnlineEvaluationPage";

const OnlineEvaluationPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canViewOnlineEvaluationRules },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canViewOnlineEvaluationRules}
      message={t("noAccess.noPermissionsViewOnlineEvaluation")}
    >
      <OnlineEvaluationPage />
    </NoAccessPageGuard>
  );
};

export default OnlineEvaluationPageGuard;
