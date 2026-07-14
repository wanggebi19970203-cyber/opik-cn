import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v1/layout/NoAccessPageGuard/NoAccessPageGuard";

const OptimizationsPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canViewOptimizationRuns },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceName={t("noAccess.resourceOptimizationRuns")}
      canViewPage={canViewOptimizationRuns}
    />
  );
};

export default OptimizationsPageGuard;
