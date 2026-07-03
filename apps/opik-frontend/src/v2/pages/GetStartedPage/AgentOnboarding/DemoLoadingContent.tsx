import React, { useEffect, useMemo, useRef } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "@tanstack/react-router";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";
import useProgressSimulation from "@/hooks/useProgressSimulation";
import OwlArt from "@/shared/OwlArt";
import { Button } from "@/ui/button";

interface DemoLoadingContentProps {
  onRetry: () => void;
  retryLabel?: string;
  onComplete?: () => void;
}

const DemoLoadingContent: React.FC<DemoLoadingContentProps> = ({
  onRetry,
  retryLabel,
  onComplete,
}) => {
  const { t } = useTranslation("pages/get-started");
  const workspaceName = useActiveWorkspaceName();

  const loadingLabels = useMemo(
    () => [
      t("getStarted.demoLoading.creatingDemoProject"),
      t("getStarted.demoLoading.settingUpSampleTraces"),
      t("getStarted.demoLoading.preparingData"),
      t("getStarted.demoLoading.almostReady"),
    ],
    [t],
  );
  const navigate = useNavigate();
  const onCompleteRef = useRef(onComplete);
  onCompleteRef.current = onComplete;

  const {
    data: demoProject,
    isLoading,
    pollExpired,
  } = useDemoProject(
    { workspaceName, poll: true },
    { refetchOnMount: "always" },
  );

  const { message } = useProgressSimulation({
    messages: loadingLabels,
    isPending: !pollExpired,
    loop: true,
  });

  useEffect(() => {
    if (demoProject) {
      onCompleteRef.current?.();
      void navigate({
        to: "/$workspaceName/projects/$projectId/logs",
        params: { workspaceName, projectId: demoProject.id },
      });
    }
  }, [demoProject, navigate, workspaceName]);

  if (isLoading) {
    return null;
  }

  if (pollExpired && !demoProject) {
    return (
      <div className="flex min-h-full items-center justify-center">
        <div className="flex flex-col items-center gap-4">
          <OwlArt className="size-[72px]" />
          <p className="comet-body-s text-center text-muted-slate">
            {t("getStarted.demoLoading.takingLonger")}
          </p>
          <Button variant="outline" size="sm" onClick={onRetry}>
            {retryLabel ?? t("getStarted.demoLoading.tryAgain")}
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-full items-center justify-center">
      <div className="flex flex-col items-center gap-4">
        <OwlArt className="size-[72px]" />
        <p className="font-code text-sm text-muted-slate">
          {message || loadingLabels[0]}
        </p>
      </div>
    </div>
  );
};

export default DemoLoadingContent;
