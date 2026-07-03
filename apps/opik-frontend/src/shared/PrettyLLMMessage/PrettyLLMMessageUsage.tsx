import React from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageUsageProps } from "./types";

const PrettyLLMMessageUsage: React.FC<PrettyLLMMessageUsageProps> = ({
  usage,
  className,
}) => {
  const { t } = useTranslation();
  if (!usage) {
    return null;
  }

  return (
    <div
      className={cn(
        "flex items-center gap-4 text-xs text-muted-foreground",
        className,
      )}
    >
      {usage.completion_tokens !== undefined && (
        <>
          <span className="font-medium">{t("common:llmMessages.completionTokens")}</span>
          <span>{usage.completion_tokens}</span>
        </>
      )}
    </div>
  );
};

export default PrettyLLMMessageUsage;
