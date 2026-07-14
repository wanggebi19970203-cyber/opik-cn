import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v1/layout/NoAccessPageGuard/NoAccessPageGuard";

const DashboardsPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceName={t("noAccess.resourceDashboards")}
      canViewPage={canViewDashboards}
    />
  );
};

export default DashboardsPageGuard;
