import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const DashboardsPageGuard = () => {
  const {
    permissions: { canViewDashboards },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceNameKey="navigation.noAccess.resourceDashboards"
      canViewPage={canViewDashboards}
    />
  );
};

export default DashboardsPageGuard;
