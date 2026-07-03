import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v1/layout/NoAccessPageGuard/NoAccessPageGuard";
import AddEditAlertPage from "@/v1/pages/AlertsPage/AddEditAlertPage/AddEditAlertPage";

const AlertEditPageGuard = () => {
  const { t } = useTranslation();
  const {
    permissions: { canUpdateAlerts },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canUpdateAlerts}
      message={t("navigation.noAccess.noPermissionsAlerts")}
    >
      <AddEditAlertPage />
    </NoAccessPageGuard>
  );
};

export default AlertEditPageGuard;
