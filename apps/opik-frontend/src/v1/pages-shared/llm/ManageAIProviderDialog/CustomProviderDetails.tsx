import React from "react";
import { useTranslation } from "react-i18next";
import { UseFormReturn } from "react-hook-form";
import { Label } from "@/ui/label";
import EyeInput from "@/shared/EyeInput/EyeInput";
import { AIProviderFormType } from "@/v1/pages-shared/llm/ManageAIProviderDialog/schema";
import get from "lodash/get";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { cn } from "@/lib/utils";
import { buildDocsUrl } from "@/v1/lib/utils";
import { Input } from "@/ui/input";
import { Description } from "@/ui/description";
import { Button } from "@/ui/button";
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";
import CustomHeadersField from "./CustomHeadersField";

type CustomProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
  isEdit?: boolean;
};

const CustomProviderDetails: React.FC<CustomProviderDetailsProps> = ({
  form,
  isEdit = false,
}) => {
  const { t } = useTranslation("prompt");
  return (
    <div className="flex flex-col gap-4 pb-4">
      <p className="comet-body-s text-muted-slate">
        {PROVIDERS[PROVIDER_TYPE.CUSTOM].description}
      </p>
      {!isEdit && (
        <FormField
          control={form.control}
          name="providerName"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["providerName"]);

            return (
              <FormItem>
                <Label htmlFor="providerName">{t("customProviderDetails.providerName")}</Label>
                <FormControl>
                  <Input
                    id="providerName"
                    placeholder="ollama"
                    value={field.value}
                    onChange={(e) => field.onChange(e.target.value)}
                    disabled={isEdit}
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                  />
                </FormControl>
                <FormMessage />
                <Description>
                  {t("customProviderDetails.providerNameDescription")}
                </Description>
              </FormItem>
            );
          }}
        />
      )}
      <FormField
        control={form.control}
        name="url"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["url"]);

          return (
            <FormItem>
              <Label htmlFor="url">{t("customProviderDetails.url")}</Label>
              <FormControl>
                <Input
                  id="url"
                  placeholder={"https://vllm.example.com/v1"}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
            </FormItem>
          );
        }}
      />

      <FormField
        control={form.control}
        name="apiKey"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["apiKey"]);

          return (
            <FormItem>
              <Label htmlFor="apiKey">{t("customProviderDetails.apiKey")}</Label>
              <FormControl>
                <EyeInput
                  id="apiKey"
                  placeholder={t("customProviderDetails.apiKeyPlaceholder")}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                {t("customProviderDetails.apiKeyDescription")}{" "}
                <Button
                  variant="link"
                  size="sm"
                  asChild
                  className="inline px-0"
                >
                  <a
                    href={buildDocsUrl("/prompt_engineering/playground")}
                    target="_blank"
                    rel="noreferrer"
                  >
                    {t("customProviderDetails.documentation")}
                  </a>
                </Button>
                .
              </Description>
            </FormItem>
          );
        }}
      />
      <FormField
        control={form.control}
        name="models"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["models"]);

          return (
            <FormItem>
              <Label htmlFor="models">{t("customProviderDetails.modelsList")}</Label>
              <FormControl>
                <Input
                  id="models"
                  placeholder={t("customProviderDetails.modelsListPlaceholder")}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                {t("customProviderDetails.modelsListDescription")}
                {`"meta-llama/Meta-Llama-3.1-70B,mistralai/Mistral-7B"`}
              </Description>
            </FormItem>
          );
        }}
      />

      <CustomHeadersField form={form} />
    </div>
  );
};

export default CustomProviderDetails;
