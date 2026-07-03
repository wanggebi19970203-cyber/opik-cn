import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const OptimizationStudioPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canUseOptimizationStudio },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canUseOptimizationStudio}
      message={t("noAccess.noPermissionsOptimization")}
    />
  );
};

export default OptimizationStudioPageGuard;
