import React, { useCallback } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { Share } from "lucide-react";
import copy from "clipboard-copy";
import { useToast } from "@/ui/use-toast";

type ShareURLButtonProps = {
  message?: string;
  size?: "sm" | "2xs";
};

const ShareURLButton: React.FunctionComponent<ShareURLButtonProps> = ({
  message,
  size = "sm",
}) => {
  const { t } = useTranslation();
  const { toast } = useToast();
  const defaultMessage = t("common.shared.urlCopiedToClipboard");

  const shareClickHandler = useCallback(() => {
    toast({
      description: message || defaultMessage,
    });
    copy(window.location.href);
  }, [message, defaultMessage, toast]);

  return (
    <Button variant="outline" size={size} onClick={shareClickHandler}>
      <Share className={size === "2xs" ? "mr-1 size-3.5" : "mr-2 size-4"} />
      {t("common.shared.share")}
    </Button>
  );
};

export default ShareURLButton;
