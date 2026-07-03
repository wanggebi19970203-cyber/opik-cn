import React from "react";
import { useTranslation } from "react-i18next";
import { HelpCircle } from "lucide-react";
import TruncationConfigPopover from "@/shared/TruncationConfigPopover/TruncationConfigPopover";
import { TRUNCATION_DISABLED_MAX_PAGE_SIZE } from "@/constants/shared";

const TruncationDisabledWarning: React.FC = () => {
  const { t } = useTranslation();

  return (
    <TruncationConfigPopover
      message={t("common.messages.paginationLimitedTo", { count: TRUNCATION_DISABLED_MAX_PAGE_SIZE })}
    >
      <div className="flex cursor-help items-center gap-1 text-xs text-muted-foreground">
        <span>{t("common.labels.paginationLimited")}</span>
        <HelpCircle className="size-3" />
      </div>
    </TruncationConfigPopover>
  );
};

export default TruncationDisabledWarning;
