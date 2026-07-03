import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const OptimizationsPageGuard = () => {
  const {
    permissions: { canViewOptimizationRuns },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceNameKey="navigation.noAccess.resourceOptimizationRuns"
      canViewPage={canViewOptimizationRuns}
    />
  );
};

export default OptimizationsPageGuard;
