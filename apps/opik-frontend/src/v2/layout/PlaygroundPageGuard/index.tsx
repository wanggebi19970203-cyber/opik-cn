import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const PlaygroundPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canUsePlayground },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canUsePlayground}
      message={t("noAccess.noPermissionsPlayground")}
    />
  );
};

export default PlaygroundPageGuard;
