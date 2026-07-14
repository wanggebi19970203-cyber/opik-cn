import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const PromptsPageGuard = () => {
  const { t } = useTranslation("navigation");
  const {
    permissions: { canViewPrompts },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      canViewPage={canViewPrompts}
      message={t("noAccess.noPermissionsViewPromptLibrary")}
    />
  );
};

export default PromptsPageGuard;
