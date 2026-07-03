import React from "react";
import { ExternalLink } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { AnnotationQueue } from "@/types/annotation-queues";
import { Button } from "@/ui/button";
import useAppStore from "@/store/AppStore";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { usePermissions } from "@/contexts/PermissionsContext";

interface OpenSMELinkButtonProps {
  annotationQueue: AnnotationQueue;
}

const OpenSMELinkButton: React.FunctionComponent<OpenSMELinkButtonProps> = ({
  annotationQueue,
}) => {
  const { t } = useTranslation("pages/annotation-queue");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const {
    permissions: { canAnnotateTraceSpanThread },
  } = usePermissions();

  const hasFeedbackDefinitions =
    annotationQueue.feedback_definition_names.length;

  if (!canAnnotateTraceSpanThread && !hasFeedbackDefinitions) return null;

  return (
    <TooltipWrapper content={t("annotationQueue.openSME.tooltip")}>
      <Link
        to="/$workspaceName/sme"
        params={{ workspaceName }}
        search={{
          queueId: annotationQueue.id,
        }}
        target="_blank"
      >
        <Button size="sm">
          <ExternalLink className="mr-1.5 size-3.5" />
          {t("annotationQueue.openSME.button")}
        </Button>
      </Link>
    </TooltipWrapper>
  );
};

export default OpenSMELinkButton;
