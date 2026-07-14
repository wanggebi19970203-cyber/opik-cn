import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Path, useFieldArray, UseFormReturn } from "react-hook-form";
import { CircleHelp, ExternalLink, Plus, WebhookIcon, X } from "lucide-react";
import get from "lodash/get";

import { Label } from "@/ui/label";
import {
  FormControl,
  FormErrorSkeleton,
  FormField,
  FormItem,
  FormMessage,
} from "@/ui/form";
import { Checkbox } from "@/ui/checkbox";
import { Card, CardContent } from "@/ui/card";
import { Description } from "@/ui/description";
import { Button } from "@/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Separator } from "@/ui/separator";
import { Input } from "@/ui/input";
import SelectBox from "@/shared/SelectBox/SelectBox";
import ProjectsSelectBox from "@/v1/pages-shared/automations/ProjectsSelectBox";
import { DropdownOption } from "@/types/shared";
import { AlertFormType } from "./schema";
import { TRIGGER_CONFIG } from "./helpers";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { cn } from "@/lib/utils";
import FeedbackScoreConditions, {
  DEFAULT_FEEDBACK_SCORE_CONDITION,
} from "./FeedbackScoreConditions";

type EventTriggersProps = {
  form: UseFormReturn<AlertFormType>;
  projectsIds: string[];
};

const useWindowOptions = (): DropdownOption<string>[] => {
  const { t } = useTranslation();
  return [
    { label: t("alerts.windowOptions.300"), value: "300" },
    { label: t("alerts.windowOptions.900"), value: "900" },
    { label: t("alerts.windowOptions.1800"), value: "1800" },
    { label: t("alerts.windowOptions.3600"), value: "3600" },
    { label: t("alerts.windowOptions.21600"), value: "21600" },
    { label: t("alerts.windowOptions.43200"), value: "43200" },
    { label: t("alerts.windowOptions.86400"), value: "86400" },
    { label: t("alerts.windowOptions.604800"), value: "604800" },
    { label: t("alerts.windowOptions.1296000"), value: "1296000" },
    { label: t("alerts.windowOptions.2592000"), value: "2592000" },
  ];
};

function getThresholdLabel(
  t: (key: string) => string,
  eventType: ALERT_EVENT_TYPE,
): string {
  switch (eventType) {
    case ALERT_EVENT_TYPE.trace_cost:
      return t("alerts.thresholdLabels.trace_cost");
    case ALERT_EVENT_TYPE.trace_errors:
      return t("alerts.thresholdLabels.trace_errors");
    case ALERT_EVENT_TYPE.trace_latency:
      return t("alerts.thresholdLabels.trace_latency");
    default:
      return t("alerts.thresholdLabels.default");
  }
}

function getThresholdPlaceholder(eventType: ALERT_EVENT_TYPE): string {
  switch (eventType) {
    case ALERT_EVENT_TYPE.trace_cost:
      return "100";
    case ALERT_EVENT_TYPE.trace_errors:
      return "10";
    case ALERT_EVENT_TYPE.trace_latency:
      return "0.0";
    default:
      return "0";
  }
}

const EventTriggers: React.FunctionComponent<EventTriggersProps> = ({
  form,
  projectsIds,
}) => {
  const { t } = useTranslation();
  const windowOptions = useWindowOptions();
  const triggersError = form.formState.errors.triggers;
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const { fields, append, remove } = useFieldArray({
    control: form.control,
    name: "triggers",
  });

  const selectedEventTypes = useMemo(() => {
    return new Set(fields.map((f) => f.eventType));
  }, [fields]);

  const hasTriggers = fields.length > 0;

  const toggleTrigger = (eventType: ALERT_EVENT_TYPE, checked: boolean) => {
    if (checked) {
      const isFeedbackScoreTrigger =
        eventType === ALERT_EVENT_TYPE.trace_feedback_score ||
        eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score;

      append({
        eventType,
        projectIds: projectsIds,
        ...(isFeedbackScoreTrigger
          ? {
              conditions: [DEFAULT_FEEDBACK_SCORE_CONDITION],
            }
          : {}),
      });
    } else {
      const index = fields.findIndex((f) => f.eventType === eventType);
      if (index >= 0) {
        remove(index);
      }
    }
  };

  const removeTrigger = (index: number) => {
    remove(index);
  };

  const renderThresholdConfig = (
    index: number,
    eventType: ALERT_EVENT_TYPE,
  ) => {
    return (
      <div className="flex items-start gap-4">
        <FormField
          control={form.control}
          name={`triggers.${index}.threshold` as Path<AlertFormType>}
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, [
              "triggers",
              index,
              "threshold",
            ]);
            return (
              <FormItem className="flex-1">
                <Label className="comet-body-s">
                  {getThresholdLabel(t, eventType)}
                </Label>
                <FormControl>
                  <Input
                    className={cn("h-8", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    type="number"
                    step="any"
                    placeholder={getThresholdPlaceholder(eventType)}
                    value={field.value as string}
                    onChange={field.onChange}
                    onBlur={field.onBlur}
                    name={field.name}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
        <FormField
          control={form.control}
          name={`triggers.${index}.window` as Path<AlertFormType>}
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, [
              "triggers",
              index,
              "window",
            ]);
            return (
              <FormItem className="flex-1">
                <Label className="comet-body-s">
                  {t("alerts.triggers.inTheLast")}
                </Label>
                <FormControl>
                  <SelectBox
                    value={field.value as string}
                    onChange={field.onChange}
                    options={windowOptions}
                    className={cn("h-8", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    placeholder={t("alerts.triggers.selectTimeWindow")}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />
      </div>
    );
  };

  const renderFeedbackScoreThresholdConfig = (
    index: number,
    eventType: ALERT_EVENT_TYPE,
  ) => {
    return (
      <FeedbackScoreConditions
        form={form}
        triggerIndex={index}
        eventType={eventType}
      />
    );
  };

  const allEventTypes = useMemo(() => {
    const eventTypes = Object.values(ALERT_EVENT_TYPE) as ALERT_EVENT_TYPE[];
    return eventTypes.filter((t) =>
      t === ALERT_EVENT_TYPE.trace_guardrails_triggered
        ? isGuardrailsEnabled
        : true,
    );
  }, [isGuardrailsEnabled]);

  return (
    <div className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <div className="flex flex-col gap-1">
          <h3 className="comet-body-accented">{t("alerts.triggers.title")}</h3>
          <Description>{t("alerts.triggers.description")}</Description>
        </div>

        <Popover>
          <PopoverTrigger asChild>
            <Button type="button" variant="outline" size="sm">
              <Plus className="mr-1 size-3" />
              {t("alerts.triggers.addTrigger")}
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-[480px] p-0" align="end">
            <div className="flex flex-col">
              <div className="max-h-[400px] overflow-y-auto p-1">
                {allEventTypes.map((eventType) => {
                  const config = TRIGGER_CONFIG[eventType];
                  const isChecked = selectedEventTypes.has(eventType);

                  return (
                    <label
                      key={eventType}
                      className="flex min-w-[200px] cursor-pointer flex-col gap-0.5 rounded px-3 py-2.5 hover:bg-muted"
                    >
                      <div className="flex items-center gap-2">
                        <Checkbox
                          checked={isChecked}
                          onCheckedChange={(checked) =>
                            toggleTrigger(eventType, !!checked)
                          }
                        />
                        <span className="comet-body-s-accented flex-1">
                          {config.title}
                        </span>
                      </div>
                      <Description className="pl-7">
                        {config.description}
                      </Description>
                    </label>
                  );
                })}
              </div>

              <Separator />

              <div className="flex min-w-[200px] items-center gap-2 rounded px-4 py-2.5">
                <CircleHelp className="size-4 shrink-0 text-muted-foreground" />
                <div className="flex flex-wrap items-center gap-1 text-sm">
                  <span className="comet-body-s">
                    {t("alerts.triggers.missingTrigger")}
                  </span>
                  <a
                    href="https://github.com/comet-ml/opik/issues/new"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 text-primary hover:underline"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <span className="comet-body-s">
                      {t("alerts.triggers.githubTicket")}
                    </span>
                    <ExternalLink className="size-3.5" />
                  </a>
                  <span className="comet-body-s">
                    {t("alerts.triggers.toLetUsKnow")}
                  </span>
                </div>
              </div>
            </div>
          </PopoverContent>
        </Popover>
      </div>

      {!hasTriggers && (
        <Card>
          <CardContent className="p-4">
            <div className="flex flex-col items-center justify-center gap-2 py-8">
              <WebhookIcon className="size-4 text-muted-foreground" />
              <p className="comet-body-s-accented text-center">
                {t("alerts.triggers.emptyTitle")}
              </p>
              <p className="comet-body-s text-center text-muted-foreground">
                {t("alerts.triggers.emptyDescription")}
              </p>
            </div>
          </CardContent>
        </Card>
      )}

      {hasTriggers && (
        <Card>
          <CardContent className="p-4">
            <div className="flex flex-col gap-2">
              {fields.map((field, index) => {
                const config = TRIGGER_CONFIG[field.eventType];
                const isLastItem = index === fields.length - 1;
                const isThresholdTrigger =
                  field.eventType === ALERT_EVENT_TYPE.trace_cost ||
                  field.eventType === ALERT_EVENT_TYPE.trace_latency ||
                  field.eventType === ALERT_EVENT_TYPE.trace_errors;
                const isFeedbackScoreTrigger =
                  field.eventType === ALERT_EVENT_TYPE.trace_feedback_score ||
                  field.eventType ===
                    ALERT_EVENT_TYPE.trace_thread_feedback_score;

                return (
                  <div key={field.id}>
                    <div className="flex items-stretch gap-4">
                      <div className="flex flex-auto flex-col gap-3">
                        <div className="flex gap-4">
                          <div className="flex flex-1 flex-col gap-1">
                            <Label className="comet-body-s-accented">
                              {config.title}
                            </Label>
                            <Description>{config.description}</Description>
                          </div>

                          {config.hasScope && (
                            <FormField
                              control={form.control}
                              name={
                                `triggers.${index}.projectIds` as Path<AlertFormType>
                              }
                              render={({ field }) => (
                                <FormItem className="justify-center">
                                  <ProjectsSelectBox
                                    value={field.value as string[]}
                                    onValueChange={field.onChange}
                                    multiselect={true}
                                    className="h-8 w-40"
                                    showSelectAll={true}
                                    minWidth={204}
                                  />
                                </FormItem>
                              )}
                            />
                          )}
                        </div>
                        {isThresholdTrigger &&
                          renderThresholdConfig(index, field.eventType)}
                        {isFeedbackScoreTrigger &&
                          renderFeedbackScoreThresholdConfig(
                            index,
                            field.eventType,
                          )}
                      </div>
                      <div className="flex items-center">
                        <Button
                          type="button"
                          variant="minimal"
                          size="icon-xs"
                          onClick={() => removeTrigger(index)}
                        >
                          <X />
                        </Button>
                      </div>
                    </div>
                    {!isLastItem && <Separator className="my-2" />}
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>
      )}

      {triggersError && (
        <FormErrorSkeleton>
          {String(triggersError.message || triggersError.root?.message || "")}
        </FormErrorSkeleton>
      )}
    </div>
  );
};

export default EventTriggers;
