import React, { useCallback, useMemo } from "react";

import { DropdownOption } from "@/types/shared";
import { Alert, AlertTitle } from "@/ui/alert";
import LLMPromptMessagesVariable from "@/v2/pages-shared/llm/LLMPromptMessagesVariables/LLMPromptMessagesVariable";
import { Description } from "@/ui/description";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINERS_MAP, EXPLAINER_ID } from "@/v2/constants/explainers";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { useTranslation } from "react-i18next";

interface MessageVariablesValidationError {
  [key: string]: {
    message: string;
  };
}

interface LLMPromptMessagesVariablesProps {
  parsingError?: boolean;
  validationErrors?: MessageVariablesValidationError;
  variables: Record<string, string>;
  onChange: (variables: Record<string, string>) => void;
  projectId: string;
  description?: string;
  errorText?: string;
  datasetColumnNames?: string[];
  type?: TRACE_DATA_TYPE;
  includeIntermediateNodes?: boolean;
  /**
   * Variable-name → sentinel-value map. A row is hidden from the rendered list
   * only when the variable's *current value* equals the sentinel for that name
   * — so the default `spans → "spans"` auto-fill is hidden, but a custom
   * override (e.g. `spans → "input.spans"` set via the API) stays visible and
   * editable. Hiding purely by name would make user-set values write-only.
   */
  reservedSentinels?: Readonly<Record<string, string>>;
}

const LLMPromptMessagesVariables = ({
  parsingError,
  validationErrors,
  variables,
  onChange,
  projectId,
  description,
  errorText,
  datasetColumnNames,
  type = TRACE_DATA_TYPE.traces,
  includeIntermediateNodes = false,
  reservedSentinels,
}: LLMPromptMessagesVariablesProps) => {
  const { t } = useTranslation("llm");
  const variablesList: DropdownOption<string>[] = useMemo(() => {
    if (!variables || typeof variables !== "object") {
      return [];
    }
    return Object.entries(variables)
      .filter(([name, value]) => reservedSentinels?.[name] !== value)
      .map((e) => ({ label: e[0], value: e[1] }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }, [variables, reservedSentinels]);

  const handleChangeVariables = useCallback(
    (changes: DropdownOption<string>) => {
      const safeVariables =
        variables && typeof variables === "object" ? variables : {};
      onChange({ ...safeVariables, [changes.label]: changes.value });
    },
    [onChange, variables],
  );

  return (
    <div className="pt-4">
      <div className="comet-body-s-accented mb-1 flex items-center gap-1 text-muted-slate">
        <span>
          {t("llm:promptMessagesVariables.variableMapping", {
            count: variablesList.length,
          })}
        </span>
        <ExplainerIcon
          {...EXPLAINERS_MAP[EXPLAINER_ID.llm_judge_variable_mapping]}
        />
      </div>
      <Description className="mb-2 inline-block">
        {description ?? t("llm:promptMessagesVariables.defaultDescription")}
      </Description>
      {parsingError && (
        <Alert variant="destructive">
          <AlertTitle>
            {errorText ?? t("llm:promptMessagesVariables.defaultErrorText")}
          </AlertTitle>
        </Alert>
      )}
      <div className="flex flex-col gap-2 overflow-hidden">
        {variablesList.map((variable) => (
          <LLMPromptMessagesVariable
            key={variable.label}
            variable={variable}
            errorText={validationErrors?.[variable.label]?.message}
            onChange={(changes) => handleChangeVariables(changes)}
            projectId={projectId}
            datasetColumnNames={datasetColumnNames}
            type={type}
            includeIntermediateNodes={includeIntermediateNodes}
          />
        ))}
      </div>
    </div>
  );
};

export default LLMPromptMessagesVariables;
