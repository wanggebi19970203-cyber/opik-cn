import React from "react";
import { UseFormReturn } from "react-hook-form";
import { useTranslation } from "react-i18next";

import KeyValueFieldArray from "@/shared/KeyValueFieldArray/KeyValueFieldArray";
import { AlertFormType } from "./schema";

type WebhookHeadersProps = {
  form: UseFormReturn<AlertFormType>;
};

const WebhookHeaders: React.FC<WebhookHeadersProps> = ({ form }) => {
  const { t } = useTranslation("pages/alerts");

  return (
    <KeyValueFieldArray<AlertFormType>
      form={form}
      name="headers"
      label={t("alerts.headers.label")}
      description={t("alerts.headers.description")}
      showColumnHeaders
      addButtonLabel={t("alerts.headers.addHeader")}
      newItem={() => ({ key: "", value: "" })}
    />
  );
};

export default WebhookHeaders;
