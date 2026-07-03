import React, { useEffect, useState } from "react";
import { AlertCircle } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Skeleton } from "@/ui/skeleton";

const NO_AGENTS_GRACE_MS = 20_000;

type AgentRunnerLoadingProps = {
  runnerId: string;
};

const AgentRunnerLoading: React.FC<AgentRunnerLoadingProps> = ({
  runnerId,
}) => {
  const { t } = useTranslation();
  const [isGraceElapsed, setIsGraceElapsed] = useState(false);

  useEffect(() => {
    setIsGraceElapsed(false);
    const timer = window.setTimeout(
      () => setIsGraceElapsed(true),
      NO_AGENTS_GRACE_MS,
    );
    return () => window.clearTimeout(timer);
  }, [runnerId]);

  if (isGraceElapsed) {
    return (
      <div className="flex flex-col items-center gap-1 py-8 text-muted-slate">
        <AlertCircle className="mb-2 size-5 text-destructive" />
        <p className="comet-body-s font-medium text-foreground">
          {t("common.messages.noAgentsRegistered")}
        </p>
        <div className="comet-body-xs mt-1 max-w-sm text-center">
          <p>
            {t("common.messages.noAgentsRegisteredDescription")}
          </p>
          <ul className="mt-1 inline-block list-inside list-disc text-left">
            <li>
              {t("common.messages.runnerMissingDecorator", {
                1: (chunks: string) => <code>{chunks}</code>,
              })}
            </li>
            <li>{t("common.messages.runnerProcessExited")}</li>
            <li>{t("common.messages.runnerMissingEntrypoint")}</li>
          </ul>
          <p className="mt-2">
            {t("common.messages.checkOpikEndpointTerminal", {
              1: (chunks: string) => <code>{chunks}</code>,
            })}
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      <SkeletonField />
      <SkeletonField />
      <SkeletonField />
    </div>
  );
};

const SkeletonField: React.FC = () => (
  <div className="overflow-hidden rounded-md border border-border bg-soft-background">
    <div className="flex items-center gap-2 px-3 py-2">
      <Skeleton className="size-4 shrink-0" />
      <Skeleton className="h-3 w-20" />
      <Skeleton className="h-3 w-12" />
    </div>
    <div className="px-3 pb-3">
      <Skeleton className="h-10 w-full" />
    </div>
  </div>
);

export default AgentRunnerLoading;
