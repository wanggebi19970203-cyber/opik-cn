import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v1/layout/NoAccessPageGuard/NoAccessPageGuard";

const DatasetsPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceName={t("noAccess.resourceDatasets")}
      canViewPage={canViewDatasets}
    />
  );
};

export default DatasetsPageGuard;
