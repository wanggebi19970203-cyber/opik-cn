import React from "react";
import { useTranslation } from "react-i18next";
import { UseFormReturn } from "react-hook-form";
import get from "lodash/get";

import { cn } from "@/lib/utils";
import { Label } from "@/ui/label";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { Input } from "@/ui/input";
import EyeInput from "@/shared/EyeInput/EyeInput";
import { Description } from "@/ui/description";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import { ALERT_TYPE } from "@/types/alerts";
import DestinationSelector from "./DestinationSelector";
import WebhookHeaders from "./WebhookHeaders";
import { AlertFormType } from "./schema";

type WebhookSettingsProps = {
  form: UseFormReturn<AlertFormType>;
};

const WebhookSettings: React.FC<WebhookSettingsProps> = ({ form }) => {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1">
        <h3 className="comet-body-accented">{t("alerts.webhook.title")}</h3>
        <Description>{t("alerts.webhook.description")}</Description>
      </div>

      <FormField
        control={form.control}
        name="alertType"
        render={({ field }) => (
          <FormItem>
            <Label>{t("alerts.webhook.destination")}</Label>
            <FormControl>
              <DestinationSelector
                value={field.value}
                onChange={field.onChange}
              />
            </FormControl>
            <FormMessage />
          </FormItem>
        )}
      />

      {form.watch("alertType") === ALERT_TYPE.pagerduty && (
        <FormField
          control={form.control}
          name="routingKey"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["routingKey"]);
            return (
              <FormItem>
                <Label>{t("alerts.webhook.routingKey")}</Label>
                <Description>
                  {t("alerts.webhook.routingKeyDescription")}
                </Description>
                <FormControl>
                  <Input
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    placeholder={t("alerts.webhook.routingKeyPlaceholder")}
                    {...field}
                  />
                </FormControl>
                <FormMessage />
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
              <Label>{t("alerts.webhook.endpointUrl")}</Label>
              <FormControl>
                <Input
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                  placeholder="https://hooks.slack.com/services/..."
                  {...field}
                />
              </FormControl>
              <FormMessage />
              <Description>
                {t("alerts.webhook.endpointUrlDescription")}
              </Description>
            </FormItem>
          );
        }}
      />

      <Accordion type="single" collapsible defaultValue="">
        <AccordionItem value="advanced" className="border-t">
          <AccordionTrigger>
            {t("alerts.webhook.advancedSettings")}
          </AccordionTrigger>
          <AccordionContent>
            <div className="flex flex-col gap-4 px-3">
              <FormField
                control={form.control}
                name="secretToken"
                render={({ field, formState }) => {
                  const validationErrors = get(formState.errors, [
                    "secretToken",
                  ]);
                  return (
                    <FormItem>
                      <Label>{t("alerts.webhook.secretToken")}</Label>
                      <FormControl>
                        <EyeInput
                          className={cn({
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          placeholder=""
                          {...field}
                        />
                      </FormControl>
                      <FormMessage />
                      <Description>
                        {t("alerts.webhook.secretTokenDescription")}
                      </Description>
                    </FormItem>
                  );
                }}
              />

              <WebhookHeaders form={form} />
            </div>
          </AccordionContent>
        </AccordionItem>
      </Accordion>
    </div>
  );
};

export default WebhookSettings;
