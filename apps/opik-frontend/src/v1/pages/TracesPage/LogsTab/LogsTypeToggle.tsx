import React from "react";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import { LOGS_TYPE } from "@/constants/traces";
import { useTranslation } from "react-i18next";

type LogsTypeToggleProps = {
  value: LOGS_TYPE;
  onValueChange: (value: LOGS_TYPE) => void;
};

const LogsTypeToggle: React.FC<LogsTypeToggleProps> = ({
  value,
  onValueChange,
}) => {
  const { t } = useTranslation();

  return (
    <ToggleGroup
      type="single"
      value={value}
      onValueChange={(val) => val && onValueChange(val as LOGS_TYPE)}
      variant="secondary"
      className="w-fit"
    >
      <ToggleGroupItem value={LOGS_TYPE.threads} size="sm">
        {t("tracing.tabs.threads")}
      </ToggleGroupItem>
      <ToggleGroupItem value={LOGS_TYPE.traces} size="sm">
        {t("tracing.tabs.traces")}
      </ToggleGroupItem>
      <ToggleGroupItem value={LOGS_TYPE.spans} size="sm">
        {t("tracing.tabs.spans")}
      </ToggleGroupItem>
    </ToggleGroup>
  );
};

export default LogsTypeToggle;
