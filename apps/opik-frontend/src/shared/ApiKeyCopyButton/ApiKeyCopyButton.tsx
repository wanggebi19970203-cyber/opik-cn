import React from "react";
import { useTranslation } from "react-i18next";
import { Button, ButtonProps } from "@/ui/button";
import { Copy } from "lucide-react";
import copy from "clipboard-copy";
import { useToast } from "@/ui/use-toast";
import { useUserApiKey } from "@/store/AppStore";

export type ApiKeyCopyButtonProps = {
  className?: string;
  label?: string;
} & Pick<ButtonProps, "variant" | "size" | "disabled">;

const ApiKeyCopyButton: React.FunctionComponent<ApiKeyCopyButtonProps> = ({
  className,
  label,
  variant = "outline",
  size = "sm",
  disabled,
}) => {
  const { t } = useTranslation();
  const { toast } = useToast();
  const apiKey = useUserApiKey();

  const handleCopy = () => {
    if (!apiKey) {
      return;
    }
    copy(apiKey);
    toast({ description: t("common:messages.apiKeyCopied"), duration: 2000 });
  };

  if (!apiKey) {
    return null;
  }

  return (
    <Button
      variant={variant}
      size={size}
      onClick={handleCopy}
      className={className}
      disabled={disabled}
      id="copy-api-key-button"
      data-fs-element="CopyApiKeyButton"
    >
      <Copy className="mr-1.5 size-3.5" />
      {label ?? t("common:buttons.copyApiKey")}
    </Button>
  );
};

export default ApiKeyCopyButton;
