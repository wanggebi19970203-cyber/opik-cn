import { Outlet, useRouter } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import useAppStore from "@/store/AppStore";
import NoData from "@/shared/NoData/NoData";
import { Button } from "@/ui/button";

interface NoAccessPageGuardProps {
  canViewPage?: boolean | null;
  resourceNameKey?: string;
  message?: string;
  children?: React.ReactNode;
}

const NoAccessPageGuard: React.FC<NoAccessPageGuardProps> = ({
  canViewPage,
  resourceNameKey = "navigation.noAccess.thisResource",
  message,
  children,
}) => {
  const { t } = useTranslation("navigation");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const router = useRouter();

  if (!canViewPage) {
    const routerState = router.state;
    const canGoBack =
      routerState.location.state?.key !== undefined &&
      globalThis.history.length > 1;

    const handleGoHome = () => {
      router.navigate({
        to: "/$workspaceName/home",
        params: { workspaceName },
      });
    };

    const handleGoBack = () => {
      router.history.back();
    };

    return (
      <NoData
        icon={<div className="comet-title-m mb-1 text-foreground">403</div>}
        title={t("noAccess.accessDenied")}
        message={
          message ??
          t("noAccess.noPermissionsForResource", {
            resource: t(resourceNameKey),
          })
        }
      >
        <div className="flex gap-2 pt-5">
          <Button onClick={handleGoHome}>{t("noAccess.goToHome")}</Button>
          {canGoBack && (
            <Button variant="outline" onClick={handleGoBack}>
              {t("noAccess.goBack")}
            </Button>
          )}
        </div>
      </NoData>
    );
  }

  return children ?? <Outlet />;
};

export default NoAccessPageGuard;
