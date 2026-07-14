import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";

import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import { formatBlueprintValue } from "@/utils/agent-configurations";
import BlueprintTypeIcon from "./BlueprintTypeIcon";
import BlueprintValuePromptCompact from "@/v2/pages-shared/agent-configuration/fields/BlueprintValuePromptCompact";
import FieldSection from "@/v2/pages-shared/agent-configuration/fields/FieldSection";
import {
  collectMultiLineKeys,
  isMultiLineField,
} from "@/v2/pages-shared/agent-configuration/fields/blueprintFieldLayout";
import {
  FieldsCollapseController,
  useFieldsCollapse,
} from "@/v2/pages-shared/agent-configuration/fields/useFieldsCollapse";

const renderScalarValue = (v: BlueprintValue, t: (key: string) => string) => {
  if (v.value === null || v.value === undefined) {
    return (
      <div className="comet-body-xs text-light-slate">
        {t("common.messages.noValue")}
      </div>
    );
  }
  return (
    <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
      {formatBlueprintValue(v)}
    </div>
  );
};

type BlueprintValuesListProps = {
  values: BlueprintValue[];
  controller?: FieldsCollapseController;
};

const BlueprintValuesList: React.FC<BlueprintValuesListProps> = ({
  values,
  controller: externalController,
}) => {
  const { t } = useTranslation();
  const collapsibleKeys = useMemo(() => collectMultiLineKeys(values), [values]);
  const internalController = useFieldsCollapse({ collapsibleKeys });
  const controller = externalController ?? internalController;

  return (
    <div className="flex flex-col gap-4">
      {values.map((v) => {
        const isPrompt = v.type === BlueprintValueType.PROMPT;
        const fieldExpandable = isMultiLineField(v);
        const fieldExpanded = fieldExpandable
          ? controller.isExpanded(v.key)
          : undefined;
        return (
          <FieldSection
            key={v.key}
            label={v.key}
            description={v.description}
            icon={<BlueprintTypeIcon type={v.type} />}
            expandable={fieldExpandable}
            expanded={fieldExpanded}
            onToggle={
              fieldExpandable ? () => controller.toggle(v.key) : undefined
            }
            testId={`field-section-${v.key}`}
          >
            {isPrompt ? (
              <BlueprintValuePromptCompact
                key={v.value}
                value={v}
                expanded={!!fieldExpanded}
              />
            ) : fieldExpandable && !fieldExpanded ? null : (
              <div className="rounded-md border bg-primary-foreground px-3 py-2">
                {renderScalarValue(v, t)}
              </div>
            )}
          </FieldSection>
        );
      })}
    </div>
  );
};

export default BlueprintValuesList;
