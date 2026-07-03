import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v1/layout/NoAccessPageGuard/NoAccessPageGuard";

const OptimizationStudioPageGuard = () => {
  const { t } = useTranslation();
  const {
    permissions: { canUseOptimizationStudio },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canUseOptimizationStudio}
      message={t("navigation.noAccess.noPermissionsOptimization")}
    />
  );
};

export default OptimizationStudioPageGuard;
