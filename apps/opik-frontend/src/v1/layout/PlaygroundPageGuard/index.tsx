import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v1/layout/NoAccessPageGuard/NoAccessPageGuard";

const PlaygroundPageGuard = () => {
  const { t } = useTranslation();
  const {
    permissions: { canUsePlayground },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canUsePlayground}
      message={t("navigation.noAccess.noPermissionsPlayground")}
    />
  );
};

export default PlaygroundPageGuard;
