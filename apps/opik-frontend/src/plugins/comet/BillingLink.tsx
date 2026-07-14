import { useTranslation } from "react-i18next";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useAllWorkspaces from "@/plugins/comet/useAllWorkspaces";
import useUser from "@/plugins/comet/useUser";
import { buildUrl } from "@/plugins/comet/utils";

const BillingLink = () => {
  const { t } = useTranslation("signals");
  const activeWorkspaceName = useActiveWorkspaceName();
  const { data: user } = useUser();
  const { data: allWorkspaces } = useAllWorkspaces({
    enabled: !!user?.loggedIn,
  });

  const workspace = allWorkspaces?.find(
    (w) => w.workspaceName === activeWorkspaceName,
  );

  if (!workspace?.organizationId) return null;

  const href = buildUrl(
    `organizations/${workspace.organizationId}/ollie-credits`,
    activeWorkspaceName,
  );

  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className="underline underline-offset-4 hover:text-primary"
    >
      {t("diagnosticsSettings.viewBilling")}
    </a>
  );
};

export default BillingLink;
