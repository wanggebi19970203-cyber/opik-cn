import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";
import AddEditAlertPage from "@/v2/pages/AlertsPage/AddEditAlertPage/AddEditAlertPage";

const AlertEditPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canUpdateAlerts },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canUpdateAlerts}
      message={t("noAccess.noPermissionsAlerts")}
    >
      <AddEditAlertPage />
    </NoAccessPageGuard>
  );
};

export default AlertEditPageGuard;
