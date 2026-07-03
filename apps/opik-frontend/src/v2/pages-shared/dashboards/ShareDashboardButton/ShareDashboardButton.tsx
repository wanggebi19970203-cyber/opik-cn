import React, { useCallback } from "react";
import { useTranslation } from "react-i18next";
import copy from "clipboard-copy";
import { Button } from "@/ui/button";
import { useToast } from "@/ui/use-toast";
import { Copy } from "lucide-react";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

const ShareDashboardButton: React.FC = () => {
  const { t } = useTranslation("dashboards");
  const { toast } = useToast();

  const handleCopyLink = useCallback(() => {
    copy(window.location.href);
    toast({
      title: t("share.copiedTitle"),
      description: t("share.copiedDescription"),
    });
  }, [toast, t]);

  return (
    <TooltipWrapper content={t("share.tooltip")}>
      <Button size="sm" variant="outline" onClick={handleCopyLink}>
        <Copy className="mr-1.5 size-3.5" />
        {t("share.button")}
      </Button>
    </TooltipWrapper>
  );
};

export default ShareDashboardButton;
