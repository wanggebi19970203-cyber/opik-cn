import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";
import AlertsRouteWrapper from "@/v2/pages/AlertsPage/AlertsRouteWrapper";

const AlertsPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canViewAlerts },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canViewAlerts}
      message={t("noAccess.noPermissionsViewAlerts")}
    >
      <AlertsRouteWrapper />
    </NoAccessPageGuard>
  );
};

export default AlertsPageGuard;
