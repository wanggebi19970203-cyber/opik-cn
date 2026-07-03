import React from "react";
import { useTranslation } from "react-i18next";
import { Path, useFieldArray, UseFormReturn } from "react-hook-form";
import { Plus, X } from "lucide-react";
import get from "lodash/get";

import { Label } from "@/ui/label";
import { FormControl, FormField, FormItem } from "@/ui/form";
import { Button } from "@/ui/button";
import { Input } from "@/ui/input";
import SelectBox from "@/shared/SelectBox/SelectBox";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/v1/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import { DropdownOption } from "@/types/shared";
import { AlertFormType, FeedbackScoreConditionType } from "./schema";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { cn } from "@/lib/utils";

type FeedbackScoreConditionsProps = {
  form: UseFormReturn<AlertFormType>;
  triggerIndex: number;
  eventType: ALERT_EVENT_TYPE;
};

export const DEFAULT_FEEDBACK_SCORE_CONDITION: FeedbackScoreConditionType = {
  threshold: "",
  window: "1800",
  name: "",
  operator: ">",
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

const OPERATOR_OPTIONS: DropdownOption<string>[] = [
  { label: ">", value: ">" },
  { label: "<", value: "<" },
];

const FeedbackScoreConditions: React.FC<FeedbackScoreConditionsProps> = ({
  form,
  triggerIndex,
  eventType,
}) => {
  const { t } = useTranslation();
  const windowOptions = useWindowOptions();
  const conditionsFieldArray = useFieldArray({
    control: form.control,
    name: `triggers.${triggerIndex}.conditions` as "triggers.0.conditions",
  });

  const scoreSource =
    eventType === ALERT_EVENT_TYPE.trace_feedback_score
      ? ScoreSource.TRACES
      : eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score
        ? ScoreSource.THREADS
        : ScoreSource.TRACES;

  const addCondition = () => {
    conditionsFieldArray.append(DEFAULT_FEEDBACK_SCORE_CONDITION);
  };

  const removeCondition = (conditionIndex: number) => {
    conditionsFieldArray.remove(conditionIndex);
  };

  // Helper to get validation errors for a specific field in a condition
  const getConditionFieldErrors = (
    conditionIndex: number,
    fieldName: "name" | "operator" | "threshold" | "window",
  ) => {
    return get(form.formState.errors, [
      "triggers",
      triggerIndex,
      "conditions",
      conditionIndex,
      fieldName,
    ]);
  };

  return (
    <div className="flex flex-col gap-2">
      {conditionsFieldArray.fields.map((condition, conditionIndex) => {
        const isFirstCondition = conditionIndex === 0;
        const canDelete = conditionsFieldArray.fields.length > 1;

        const nameErrors = getConditionFieldErrors(conditionIndex, "name");
        const operatorErrors = getConditionFieldErrors(
          conditionIndex,
          "operator",
        );
        const thresholdErrors = getConditionFieldErrors(
          conditionIndex,
          "threshold",
        );
        const windowErrors = getConditionFieldErrors(conditionIndex, "window");

        const hasErrors =
          nameErrors?.message ||
          operatorErrors?.message ||
          thresholdErrors?.message ||
          windowErrors?.message;

        return (
          <div key={condition.id} className="flex flex-col gap-1">
            <div className="flex items-end gap-2.5">
              {/* Grouped inputs: feedback score name, operator, threshold */}
              <div className="flex flex-1 items-end">
                <FormField
                  control={form.control}
                  name={
                    `triggers.${triggerIndex}.conditions.${conditionIndex}.name` as Path<AlertFormType>
                  }
                  render={({ field }) => {
                    const validationErrors = getConditionFieldErrors(
                      conditionIndex,
                      "name",
                    );
                    return (
                      <FormItem className="min-w-[150px] flex-1">
                        {isFirstCondition && (
                          <Label className="comet-body-s-accented">
                            {t("alerts.feedbackConditions.whenAverage")}
                          </Label>
                        )}
                        {!isFirstCondition && (
                          <Label className="comet-body-s-accented">
                            {t("alerts.feedbackConditions.orWhenAverage")}
                          </Label>
                        )}
                        <FormControl>
                          <FeedbackDefinitionsAndScoresSelectBox
                            value={field.value as string}
                            onChange={field.onChange}
                            scoreSource={scoreSource}
                            multiselect={false}
                            className={cn("h-8 rounded-r-none", {
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                          />
                        </FormControl>
                      </FormItem>
                    );
                  }}
                />
                <FormField
                  control={form.control}
                  name={
                    `triggers.${triggerIndex}.conditions.${conditionIndex}.operator` as Path<AlertFormType>
                  }
                  render={({ field }) => {
                    const validationErrors = getConditionFieldErrors(
                      conditionIndex,
                      "operator",
                    );
                    return (
                      <FormItem className="-ml-px w-16">
                        <FormControl>
                          <SelectBox
                            value={field.value as string}
                            onChange={field.onChange}
                            options={OPERATOR_OPTIONS}
                            className={cn("h-8 rounded-none text-left", {
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                            placeholder=">"
                          />
                        </FormControl>
                      </FormItem>
                    );
                  }}
                />
                <FormField
                  control={form.control}
                  name={
                    `triggers.${triggerIndex}.conditions.${conditionIndex}.threshold` as Path<AlertFormType>
                  }
                  render={({ field }) => {
                    const validationErrors = getConditionFieldErrors(
                      conditionIndex,
                      "threshold",
                    );
                    return (
                      <FormItem className="-ml-px w-24">
                        <FormControl>
                          <Input
                            className={cn("h-8 rounded-l-none", {
                              "border-destructive": Boolean(
                                validationErrors?.message,
                              ),
                            })}
                            type="number"
                            step="0.01"
                            placeholder="0.7"
                            value={field.value as string}
                            onChange={field.onChange}
                            onBlur={field.onBlur}
                            name={field.name}
                          />
                        </FormControl>
                      </FormItem>
                    );
                  }}
                />
              </div>
              <FormField
                control={form.control}
                name={
                  `triggers.${triggerIndex}.conditions.${conditionIndex}.window` as Path<AlertFormType>
                }
                render={({ field }) => {
                  const validationErrors = getConditionFieldErrors(
                    conditionIndex,
                    "window",
                  );
                  return (
                    <FormItem className="min-w-[120px] flex-1">
                      <Label className="comet-body-s-accented">
                        {t("alerts.triggers.inTheLast")}
                      </Label>
                      <FormControl>
                        <SelectBox
                          value={field.value as string}
                          onChange={field.onChange}
                          options={windowOptions}
                          className={cn("h-8 text-left", {
                            "border-destructive": Boolean(
                              validationErrors?.message,
                            ),
                          })}
                          placeholder={t("alerts.triggers.selectTimeWindow")}
                        />
                      </FormControl>
                    </FormItem>
                  );
                }}
              />
              {canDelete && (
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  className="mb-0.5"
                  onClick={() => removeCondition(conditionIndex)}
                >
                  <X className="size-4" />
                </Button>
              )}
            </div>
            {/* Error messages row - displayed below the inputs */}
            {hasErrors && (
              <div className="flex gap-2.5">
                <div className="flex flex-1 gap-0">
                  <div className="min-w-[150px] flex-1">
                    {nameErrors?.message && (
                      <p className="text-[0.8rem] font-medium text-destructive">
                        {String(nameErrors.message)}
                      </p>
                    )}
                  </div>
                  <div className="-ml-px w-16">
                    {operatorErrors?.message && (
                      <p className="text-[0.8rem] font-medium text-destructive">
                        {String(operatorErrors.message)}
                      </p>
                    )}
                  </div>
                  <div className="-ml-px w-24">
                    {thresholdErrors?.message && (
                      <p className="text-[0.8rem] font-medium text-destructive">
                        {String(thresholdErrors.message)}
                      </p>
                    )}
                  </div>
                </div>
                <div className="min-w-[120px] flex-1">
                  {windowErrors?.message && (
                    <p className="text-[0.8rem] font-medium text-destructive">
                      {String(windowErrors.message)}
                    </p>
                  )}
                </div>
                {canDelete && <div className="w-8" />}
              </div>
            )}
          </div>
        );
      })}
      <div className="flex pt-1">
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={addCondition}
        >
          <Plus className="mr-1 size-3" />
          {t("alerts.feedbackConditions.addCondition")}
        </Button>
      </div>
    </div>
  );
};

export default FeedbackScoreConditions;
