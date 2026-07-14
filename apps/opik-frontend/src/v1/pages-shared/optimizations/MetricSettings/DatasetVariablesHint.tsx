import React from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

interface DatasetVariablesHintProps {
  datasetVariables: string[];
  onSelect: (variable: string) => void;
}

const DatasetVariablesHint: React.FC<DatasetVariablesHintProps> = ({
  datasetVariables,
  onSelect,
}) => {
  const { t } = useTranslation();
  if (datasetVariables.length === 0) {
    return null;
  }

  return (
    <p className="text-xs text-light-slate">
      {t("optimizations.metricConfigs.datasetVariablesAvailable")}{" "}
      {datasetVariables.map((variable, index) => (
        <span key={variable}>
          <TooltipWrapper content={t("optimizations.metricConfigs.clickToUse")}>
            <Button
              variant="minimal"
              size="3xs"
              onClick={() => onSelect(variable)}
              className="px-0 underline"
            >
              {variable}
            </Button>
          </TooltipWrapper>
          {index < datasetVariables.length - 1 && ", "}
        </span>
      ))}
    </p>
  );
};

export default DatasetVariablesHint;
