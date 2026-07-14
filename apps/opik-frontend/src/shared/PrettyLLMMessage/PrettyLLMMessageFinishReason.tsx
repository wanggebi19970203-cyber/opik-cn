import React from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { PrettyLLMMessageFinishReasonProps } from "./types";

const PrettyLLMMessageFinishReason: React.FC<
  PrettyLLMMessageFinishReasonProps
> = ({ finishReason, className }) => {
  const { t } = useTranslation();
  if (!finishReason) {
    return null;
  }

  return (
    <div
      className={cn(
        "flex items-center gap-2 text-xs text-muted-foreground",
        className,
      )}
    >
      <span className="font-medium">
        {t("common:llmMessages.finishReason")}
      </span>
      <span className="capitalize">{finishReason}</span>
    </div>
  );
};

export default PrettyLLMMessageFinishReason;
