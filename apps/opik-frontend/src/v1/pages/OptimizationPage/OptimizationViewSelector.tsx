import React from "react";
import { useTranslation } from "react-i18next";
import { ScrollText, Settings } from "lucide-react";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";

export enum OPTIMIZATION_VIEW_TYPE {
  LOGS = "logs",
  TRIALS = "trials",
  CONFIGURATION = "configuration",
}

interface OptimizationViewSelectorProps {
  value: OPTIMIZATION_VIEW_TYPE;
  onChange: (value: OPTIMIZATION_VIEW_TYPE) => void;
}

const OptimizationViewSelector: React.FC<OptimizationViewSelectorProps> = ({
  value,
  onChange,
}) => {
  const { t } = useTranslation("optimization");

  return (
    <ToggleGroup
      type="single"
      value={value}
      onValueChange={(val) => val && onChange(val as OPTIMIZATION_VIEW_TYPE)}
      variant="ghost"
      className="w-fit"
    >
      <ToggleGroupItem
        value={OPTIMIZATION_VIEW_TYPE.LOGS}
        size="sm"
        className="gap-2"
      >
        <ScrollText className="size-3" />
        {t("optimization.view.logs")}
      </ToggleGroupItem>
      <ToggleGroupItem
        value={OPTIMIZATION_VIEW_TYPE.CONFIGURATION}
        size="sm"
        className="gap-2"
      >
        <Settings className="size-3" />
        {t("optimization.view.configuration")}
      </ToggleGroupItem>
    </ToggleGroup>
  );
};

export default OptimizationViewSelector;
