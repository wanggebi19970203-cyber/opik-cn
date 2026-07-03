import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const ExperimentsPageGuard = () => {
  const {
    permissions: { canViewExperiments },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceNameKey="navigation.noAccess.resourceExperiments"
      canViewPage={canViewExperiments}
    />
  );
};

export default ExperimentsPageGuard;
