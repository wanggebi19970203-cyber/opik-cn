import React from "react";
import { useTranslation } from "react-i18next";
import { Hash } from "lucide-react";
import { cn, formatNumberInK } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface TokenCountProps {
  tokens: number;
  showLabel?: boolean;
  className?: string;
  iconClassName?: string;
}

const TokenCount: React.FC<TokenCountProps> = ({
  tokens,
  showLabel,
  className,
  iconClassName,
}) => {
  const { t } = useTranslation();

  return (
    <TooltipWrapper
      content={`${tokens.toLocaleString()} ${t("common.labels.tokens")}`}
    >
      <span className={cn("flex items-center gap-1", className)}>
        <Hash className={cn("size-3", iconClassName)} />
        {formatNumberInK(tokens)}
        {showLabel ? ` ${t("common.labels.tokens")}` : ""}
      </span>
    </TooltipWrapper>
  );
};

export default TokenCount;
