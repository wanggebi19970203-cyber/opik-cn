import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v1/layout/NoAccessPageGuard/NoAccessPageGuard";

const ExperimentsPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canViewExperiments },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceName={t("noAccess.resourceExperiments")}
      canViewPage={canViewExperiments}
    />
  );
};

export default ExperimentsPageGuard;
