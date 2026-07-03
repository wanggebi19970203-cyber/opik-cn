import { usePermissions } from "@/contexts/PermissionsContext";
import NoAccessPageGuard from "@/v2/layout/NoAccessPageGuard/NoAccessPageGuard";

const DatasetsPageGuard = () => {
  const {
    permissions: { canViewDatasets },
  } = usePermissions();

  return (
    <NoAccessPageGuard
      resourceNameKey="navigation.noAccess.resourceTestSuites"
      canViewPage={canViewDatasets}
    />
  );
};

export default DatasetsPageGuard;
