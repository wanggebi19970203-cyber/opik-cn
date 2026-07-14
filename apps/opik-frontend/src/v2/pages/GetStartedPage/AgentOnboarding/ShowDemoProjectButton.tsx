import React from "react";
import { useTranslation } from "react-i18next";
import { ChevronsRight, Loader2 } from "lucide-react";
import { Link } from "@tanstack/react-router";

import { Button } from "@/ui/button";
import { useActiveWorkspaceName } from "@/store/AppStore";
import useDemoProject from "@/api/projects/useDemoProject";

const ShowDemoProjectButton: React.FC = () => {
  const { t } = useTranslation("pages/get-started");
  const workspaceName = useActiveWorkspaceName();
  const { data: demoProject, pollExpired } = useDemoProject({
    workspaceName,
    poll: true,
  });

  if (!demoProject) {
    if (pollExpired) {
      return null;
    }
    return (
      <Button
        variant="link"
        disabled
        className="comet-body-s ml-auto px-0 text-muted-slate"
        id="onboarding-step2-show-demo"
        data-fs-element="onboarding-step2-show-demo"
      >
        <Loader2 className="mr-1.5 size-3.5 animate-spin" />
        {t("getStarted.demoLoading.generatingDemoData")}
      </Button>
    );
  }

  return (
    <Button
      variant="link"
      asChild
      className="comet-body-s ml-auto px-0 text-muted-slate"
      id="onboarding-step2-show-demo"
      data-fs-element="onboarding-step2-show-demo"
    >
      <Link
        to="/$workspaceName/projects/$projectId/logs"
        params={{
          workspaceName,
          projectId: demoProject.id,
        }}
      >
        {t("getStarted.demoLoading.showMeDemoProject")}
        <ChevronsRight className="size-3.5" />
      </Link>
    </Button>
  );
};

export default ShowDemoProjectButton;
