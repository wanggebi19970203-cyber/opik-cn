import React, { useMemo } from "react";
import { ArrowLeft } from "lucide-react";
import { Link } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import useAppStore from "@/store/AppStore";
import { useSMEFlow } from "./SMEFlowContext";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";

const ReturnToAnnotationQueueButton: React.FC = () => {
  const { t } = useTranslation("sme");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { annotationQueue, hasAnyUnsavedChanges } = useSMEFlow();

  const queueId = annotationQueue?.id || "";

  const navigationBlockerConfig = useMemo(
    () => ({
      condition: hasAnyUnsavedChanges,
      title: t("returnToAnnotationQueue.unsavedChanges"),
      description: t("returnToAnnotationQueue.unsavedChangesDescription"),
      confirmText: t("returnToAnnotationQueue.leaveWithoutSaving"),
      cancelText: t("returnToAnnotationQueue.stayOnPage"),
    }),
    [hasAnyUnsavedChanges, t],
  );

  const { DialogComponent } = useNavigationBlocker(navigationBlockerConfig);

  return (
    <>
      <Link
        to="/$workspaceName/annotation-queues/$annotationQueueId"
        params={{ workspaceName, annotationQueueId: queueId }}
      >
        <Button
          variant="ghost"
          aria-label={t(
            "returnToAnnotationQueue.returnToAnnotationQueueAriaLabel",
          )}
        >
          <ArrowLeft className="mr-2 size-4" />
          {t("returnToAnnotationQueue.returnToAnnotationQueue")}
        </Button>
      </Link>

      {DialogComponent}
    </>
  );
};

export default ReturnToAnnotationQueueButton;
