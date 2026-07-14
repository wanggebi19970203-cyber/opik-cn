import React from "react";
import { ArrowRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

type ViewDetailsButtonProps = {
  projectsIds: string[];
};

const ViewDetailsButton: React.FC<ViewDetailsButtonProps> = ({
  projectsIds,
}) => {
  const { t } = useTranslation("home");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const projectId = projectsIds.length === 1 ? projectsIds[0] : undefined;

  return (
    <Link
      to={
        projectId
          ? "/$workspaceName/projects/$projectId/traces"
          : "/$workspaceName/projects"
      }
      params={{
        workspaceName,
        ...(projectId && { projectId }),
      }}
    >
      <Button variant="ghost" className="flex shrink-0 items-center gap-1 pr-0">
        {t("home.viewDetails")}
        <ArrowRight className="size-4" />
      </Button>
    </Link>
  );
};

export default ViewDetailsButton;
