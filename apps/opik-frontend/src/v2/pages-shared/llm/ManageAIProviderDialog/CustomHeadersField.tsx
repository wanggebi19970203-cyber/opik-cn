import React from "react";
import { UseFormReturn } from "react-hook-form";
import { v4 as uuidv4 } from "uuid";

import KeyValueFieldArray from "@/shared/KeyValueFieldArray/KeyValueFieldArray";
import { AIProviderFormType } from "@/v2/pages-shared/llm/ManageAIProviderDialog/schema";
import { useTranslation } from "react-i18next";

type KeyValueFieldName = "headers" | "queryParams";

type CustomHeadersFieldProps = {
  form: UseFormReturn<AIProviderFormType>;
  name?: KeyValueFieldName;
  label?: string;
  keyPlaceholder?: string;
  valuePlaceholder?: string;
  addButtonLabel?: string;
  description?: string;
};

/// Thin Custom-LLM-provider-specific wrapper over the shared
/// `KeyValueFieldArray`. Keeps the existing call sites (header + query-param
/// editors in `CustomProviderDetails`) stable while routing to the shared
/// component so any future tweaks — styling, accessibility, validation — land
/// in one place for every consumer in the app.
const CustomHeadersField: React.FC<CustomHeadersFieldProps> = ({
  form,
  name = "headers",
  label,
  keyPlaceholder,
  valuePlaceholder,
  addButtonLabel,
  description,
}) => {
  const { t } = useTranslation("llm");

  const effectiveLabel = label ?? t("llm:customHeadersField.customHeaders");
  const effectiveKeyPlaceholder =
    keyPlaceholder ?? t("llm:customHeadersField.headerName");
  const effectiveValuePlaceholder =
    valuePlaceholder ?? t("llm:customHeadersField.headerValue");
  const effectiveAddButtonLabel =
    addButtonLabel ?? t("llm:customHeadersField.addHeader");
  const effectiveDescription =
    description ?? t("llm:customHeadersField.customHeadersDescription");

  return (
    <KeyValueFieldArray<AIProviderFormType>
      form={form}
      name={name}
      label={effectiveLabel}
      description={effectiveDescription}
      keyPlaceholder={effectiveKeyPlaceholder}
      valuePlaceholder={effectiveValuePlaceholder}
      addButtonLabel={effectiveAddButtonLabel}
      // Custom LLM schema requires an `id` string on every row to satisfy
      // the Zod contract shared with the load/save serialization helpers.
      newItem={() => ({ key: "", value: "", id: uuidv4() })}
    />
  );
};

export default CustomHeadersField;
