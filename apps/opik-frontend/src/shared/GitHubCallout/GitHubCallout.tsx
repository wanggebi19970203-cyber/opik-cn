import React from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink, Info } from "lucide-react";

import { Alert, AlertDescription } from "@/ui/alert";
import { Button } from "@/ui/button";

export interface GitHubCalloutProps {
  description?: string;
}

const GitHubCallout: React.FunctionComponent<GitHubCalloutProps> = ({
  description,
}) => {
  const { t } = useTranslation("common");
  return (
    <Alert variant="callout" size="sm">
      <Info />
      <AlertDescription size="sm">
        {description} {t("shared.openPrefix")}
        <Button variant="link" size="3xs" asChild>
          <a
            href="https://github.com/comet-ml/opik/issues"
            target="_blank"
            rel="noreferrer"
          >
            {t("shared.githubTicket")}
            <ExternalLink className="ml-0.5 size-3 shrink-0" />
          </a>
        </Button>
        {t("shared.toLetUsKnow")}
      </AlertDescription>
    </Alert>
  );
};

export default GitHubCallout;
