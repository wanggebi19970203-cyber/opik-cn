import { History } from "lucide-react";
import { useTranslation } from "react-i18next";
import { formatDate } from "@/lib/date";
import TooltipWrapper from "../TooltipWrapper/TooltipWrapper";
import capitalize from "lodash/capitalize";
import { RESOURCE_MAP, RESOURCE_TYPE } from "../ResourceLink/ResourceLink";

interface DateTagProps {
  date: string;
  resource: RESOURCE_TYPE;
}

const DateTag = ({ date, resource }: DateTagProps) => {
  const { t } = useTranslation("common");
  const label = t(`shared.${RESOURCE_MAP[resource].labelKey}`);

  if (!date) {
    return null;
  }

  return (
    <TooltipWrapper
      content={t("shared.resourceCreationTime", { label: capitalize(label) })}
    >
      <div className="comet-body-s flex h-6 shrink-0 items-center text-foreground">
        <History className="mx-1 size-3.5 shrink-0 text-muted-slate" />
        <span className="truncate">{formatDate(date)}</span>
      </div>
    </TooltipWrapper>
  );
};

DateTag.displayName = "DateTag";

export default DateTag;
