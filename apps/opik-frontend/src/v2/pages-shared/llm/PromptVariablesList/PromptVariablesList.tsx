import React from "react";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useTranslation } from "react-i18next";

interface PromptVariablesListProps {
  variables: string[];
  onVariableClick: (variable: string) => void;
  tooltipContent?: string;
}

const PromptVariablesList: React.FC<PromptVariablesListProps> = ({
  variables,
  onVariableClick,
  tooltipContent: tooltipContentProp,
}) => {
  const { t } = useTranslation();
  const tooltipContent =
    tooltipContentProp ?? t("promptVariablesList.clickToInsert");
  if (variables.length === 0) return null;

  return (
    <>
      {variables.map((variable, index) => (
        <span key={variable}>
          <TooltipWrapper content={tooltipContent}>
            <button
              type="button"
              className="underline underline-offset-2 hover:text-[var(--color-green)]"
              onMouseDown={(e) => {
                e.preventDefault();
                e.stopPropagation();
              }}
              onClick={(e) => {
                e.stopPropagation();
                onVariableClick(variable);
              }}
            >
              {`{{${variable}}}`}
            </button>
          </TooltipWrapper>
          {index < variables.length - 1 && ", "}
        </span>
      ))}
    </>
  );
};

export default PromptVariablesList;
