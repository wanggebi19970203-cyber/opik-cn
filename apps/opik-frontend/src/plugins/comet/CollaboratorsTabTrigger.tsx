import { useTranslation } from "react-i18next";
import { TabsTrigger } from "@/ui/tabs";
import useUserPermission from "./useUserPermission";

export interface CollaboratorsTabTriggerProps {
  value: string;
}

const CollaboratorsTabTrigger = ({ value }: CollaboratorsTabTriggerProps) => {
  const { t } = useTranslation("common");
  const { isWorkspaceOwner } = useUserPermission();

  if (!isWorkspaceOwner) {
    return null;
  }

  return (
    <TabsTrigger variant="underline" value={value}>
      {t("labels.members")}
    </TabsTrigger>
  );
};

export default CollaboratorsTabTrigger;
