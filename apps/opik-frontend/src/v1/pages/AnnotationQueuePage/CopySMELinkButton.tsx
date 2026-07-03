import React, { useCallback } from "react";
import { useTranslation } from "react-i18next";
import copy from "clipboard-copy";
import { AnnotationQueue } from "@/types/annotation-queues";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import { Copy } from "lucide-react";
import { generateSMEURL } from "@/lib/annotation-queues";
import useAppStore from "@/store/AppStore";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface CopySMELinkButtonProps {
  annotationQueue: AnnotationQueue;
}

const CopySMELinkButton: React.FC<CopySMELinkButtonProps> = ({
  annotationQueue,
}) => {
  const { t } = useTranslation("pages/annotation-queue");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { toast } = useToast();

  const handleCopySMELink = useCallback(() => {
    copy(generateSMEURL(workspaceName, annotationQueue.id));
    toast({
      title: t("annotationQueue.copyLink.toastTitle"),
      description: t("annotationQueue.copyLink.toastDescription"),
    });
  }, [annotationQueue.id, toast, workspaceName, t]);

  return (
    <TooltipWrapper content={t("annotationQueue.copyLink.tooltip")}>
      <Button size="sm" variant="outline" onClick={handleCopySMELink}>
        <Copy className="mr-1.5 size-3.5" />
        {t("annotationQueue.copyLink.button")}
      </Button>
    </TooltipWrapper>
  );
};

export default CopySMELinkButton;
