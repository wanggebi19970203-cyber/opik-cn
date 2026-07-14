import React from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { ChevronsRight } from "lucide-react";
import { Link } from "@tanstack/react-router";
import useAppStore from "@/store/AppStore";

type IntegrationSkipProps = {
  className?: string;
  label?: string;
  onSkip?: () => void;
};

const IntegrationSkip: React.FunctionComponent<IntegrationSkipProps> = ({
  className,
  label,
}) => {
  const { t } = useTranslation();
  const displayLabel = label ?? t("integrationExplorer.skipAndExplore");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <Button
      variant="outline"
      size="sm"
      asChild
      className={className}
      id="quickstart-skip-explore-platform"
      data-fs-element="QuickstartSkipExplorePlatform"
    >
      <Link to="/$workspaceName/home" params={{ workspaceName }}>
        {displayLabel}
        <ChevronsRight className="ml-1.5 size-3.5" />
      </Link>
    </Button>
  );
};

export default IntegrationSkip;
