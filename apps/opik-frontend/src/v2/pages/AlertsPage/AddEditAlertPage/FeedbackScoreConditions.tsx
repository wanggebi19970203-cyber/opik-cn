import React, { useMemo } from "react";
import { Path, useFieldArray, UseFormReturn } from "react-hook-form";
import { LayoutGrid, Plus, Trash } from "lucide-react";
import get from "lodash/get";
import { useTranslation } from "react-i18next";

import { FormControl, FormField, FormItem } from "@/ui/form";
import { Input } from "@/ui/input";
import { Button } from "@/ui/button";
import { ToggleGroup, ToggleGroupItem } from "@/ui/toggle-group";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import SelectBox from "@/shared/SelectBox/SelectBox";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/v2/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import {
  AlertFormType,
  FeedbackScoreConditionGroupType,
  FeedbackScoreConditionType,
} from "./schema";
import { ALERT_EVENT_TYPE } from "@/types/alerts";
import { cn } from "@/lib/utils";
import { OPERATOR_VALUES, getWindowOptions } from "./constants";

type FeedbackScoreConditionsProps = {
  form: UseFormReturn<AlertFormType>;
  triggerIndex: number;
  eventType: ALERT_EVENT_TYPE;
  projectId: string;
};

export const DEFAULT_FEEDBACK_SCORE_CONDITION: FeedbackScoreConditionType = {
  threshold: "",
  window: "86400",
  name: "",
  operator: ">",
};

export const DEFAULT_FEEDBACK_SCORE_CONDITION_GROUP: FeedbackScoreConditionGroupType =
  {
    conditions: [DEFAULT_FEEDBACK_SCORE_CONDITION],
  };

const CONDITION_FIELDS = ["name", "operator", "threshold", "window"] as const;
type ConditionField = (typeof CONDITION_FIELDS)[number];

// Radix tooltips don't fire on elements with pointer-events: none (which
// disabled buttons get from the Button variants), so when `disabled` is true
// we wrap the child in a span that intercepts hover/focus for the tooltip.
const DisabledTooltip: React.FC<{
  message: string;
  disabled: boolean;
  children: React.ReactNode;
}> = ({ message, disabled, children }) => (
  <TooltipWrapper content={disabled ? message : null}>
    <span className={cn("inline-flex", disabled && "cursor-not-allowed")}>
      {children}
    </span>
  </TooltipWrapper>
);

const SeparatorBadge: React.FC<{ kind: "AND" | "OR" }> = ({ kind }) => (
  <div className="py-0.5">
    <span className="text-xs font-medium leading-4 text-violet-600">
      {kind}
    </span>
  </div>
);

const FeedbackScoreConditions: React.FC<FeedbackScoreConditionsProps> = ({
  form,
  triggerIndex,
  eventType,
  projectId,
}) => {
  const { t } = useTranslation("pages/alerts");
  const groupsFieldArray = useFieldArray({
    control: form.control,
    name: `triggers.${triggerIndex}.groups` as "triggers.0.groups",
  });

  const windowOptions = useMemo(() => getWindowOptions(t), [t]);

  const scoreSource =
    eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score
      ? ScoreSource.THREADS
      : ScoreSource.TRACES;

  const addGroup = () =>
    groupsFieldArray.append({
      conditions: [{ ...DEFAULT_FEEDBACK_SCORE_CONDITION }],
    });

  const removeGroup = (groupIndex: number) =>
    groupsFieldArray.remove(groupIndex);

  const canDeleteGroup = groupsFieldArray.fields.length > 1;

  return (
    <div className="flex flex-col gap-2">
      {groupsFieldArray.fields.map((group, groupIndex) => (
        <React.Fragment key={group.id}>
          {groupIndex > 0 && <SeparatorBadge kind="OR" />}
          <ConditionGroup
            form={form}
            triggerIndex={triggerIndex}
            groupIndex={groupIndex}
            scoreSource={scoreSource}
            projectId={projectId}
            label={t("alerts.feedbackConditions.group", {
              number: groupIndex + 1,
            })}
            onRemove={() => removeGroup(groupIndex)}
            canRemove={canDeleteGroup}
            windowOptions={windowOptions}
          />
        </React.Fragment>
      ))}
      <div className="flex h-8 items-center justify-center rounded-md border border-dashed border-border bg-soft-background">
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="text-foreground hover:text-primary-hover"
          onClick={addGroup}
        >
          <Plus className="mr-0.5 size-3" />
          {t("alerts.feedbackConditions.addOrGroup")}
        </Button>
      </div>
    </div>
  );
};

type ConditionGroupProps = {
  form: UseFormReturn<AlertFormType>;
  triggerIndex: number;
  groupIndex: number;
  scoreSource: ScoreSource;
  projectId: string;
  label: string;
  onRemove: () => void;
  canRemove: boolean;
  windowOptions: { label: string; value: string }[];
};

const ConditionGroup: React.FC<ConditionGroupProps> = ({
  form,
  triggerIndex,
  groupIndex,
  scoreSource,
  projectId,
  label,
  onRemove,
  canRemove,
  windowOptions,
}) => {
  const { t } = useTranslation("pages/alerts");
  const conditionsFieldArray = useFieldArray({
    control: form.control,
    name: `triggers.${triggerIndex}.groups.${groupIndex}.conditions` as "triggers.0.groups.0.conditions",
  });

  const addCondition = () =>
    conditionsFieldArray.append({ ...DEFAULT_FEEDBACK_SCORE_CONDITION });

  // Deleting the only condition in a group removes the whole group (so the
  // user doesn't end up with an empty group), unless this is the last group
  // — then the delete is disabled to keep at least one group around.
  const handleDeleteCondition = (conditionIndex: number) => {
    if (conditionsFieldArray.fields.length === 1) {
      onRemove();
    } else {
      conditionsFieldArray.remove(conditionIndex);
    }
  };

  const canDeleteCondition =
    conditionsFieldArray.fields.length > 1 || canRemove;

  return (
    <div className="overflow-hidden rounded-md border border-border bg-soft-background">
      <div className="flex h-8 items-center justify-between pl-2 pr-3">
        <div className="flex items-center gap-1.5">
          <span className="flex size-4 items-center justify-center rounded bg-violet-600 text-white">
            <LayoutGrid className="size-2.5" />
          </span>
          <span className="text-xs font-medium leading-4 text-muted-slate">
            {label}
          </span>
        </div>
        <DisabledTooltip
          disabled={!canRemove}
          message={t("alerts.feedbackConditions.cantRemove")}
        >
          <Button
            type="button"
            variant="minimal"
            size="icon-3xs"
            className="size-3 [&>svg]:size-3"
            onClick={onRemove}
            disabled={!canRemove}
            aria-label={t("alerts.feedbackConditions.removeGroup")}
          >
            <Trash />
          </Button>
        </DisabledTooltip>
      </div>
      <div className="flex flex-col gap-1.5 px-1.5 pb-1.5">
        {conditionsFieldArray.fields.map((condition, conditionIndex) => (
          <React.Fragment key={condition.id}>
            {conditionIndex > 0 && <SeparatorBadge kind="AND" />}
            <ConditionRow
              form={form}
              triggerIndex={triggerIndex}
              groupIndex={groupIndex}
              conditionIndex={conditionIndex}
              scoreSource={scoreSource}
              projectId={projectId}
              onDelete={() => handleDeleteCondition(conditionIndex)}
              canDelete={canDeleteCondition}
              windowOptions={windowOptions}
            />
          </React.Fragment>
        ))}
        <Button
          type="button"
          variant="ghost"
          size="xs"
          className="self-start pl-1 text-foreground hover:text-primary-hover"
          onClick={addCondition}
        >
          <Plus className="mr-0.5 size-3" />
          {t("alerts.feedbackConditions.addAndCondition")}
        </Button>
      </div>
    </div>
  );
};

type ConditionRowProps = {
  form: UseFormReturn<AlertFormType>;
  triggerIndex: number;
  groupIndex: number;
  conditionIndex: number;
  scoreSource: ScoreSource;
  projectId: string;
  onDelete: () => void;
  canDelete: boolean;
  windowOptions: { label: string; value: string }[];
};

const fieldPath = (
  triggerIndex: number,
  groupIndex: number,
  conditionIndex: number,
  field: ConditionField,
) =>
  `triggers.${triggerIndex}.groups.${groupIndex}.conditions.${conditionIndex}.${field}` as Path<AlertFormType>;

const ConditionRow: React.FC<ConditionRowProps> = ({
  form,
  triggerIndex,
  groupIndex,
  conditionIndex,
  scoreSource,
  projectId,
  onDelete,
  canDelete,
  windowOptions,
}) => {
  const { t } = useTranslation("pages/alerts");
  const errorBase = [
    "triggers",
    triggerIndex,
    "groups",
    groupIndex,
    "conditions",
    conditionIndex,
  ] as const;
  const errors = Object.fromEntries(
    CONDITION_FIELDS.map((f) => [
      f,
      (
        get(form.formState.errors, [...errorBase, f]) as
          | { message?: string }
          | undefined
      )?.message,
    ]),
  ) as Record<ConditionField, string | undefined>;
  const hasErrors = CONDITION_FIELDS.some((f) => errors[f]);

  return (
    <div className="flex flex-col gap-1">
      <div className="flex min-h-11 items-stretch overflow-hidden rounded-md border border-border bg-background">
        <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2 px-2 py-1.5">
          <FormField
            control={form.control}
            name={fieldPath(triggerIndex, groupIndex, conditionIndex, "name")}
            render={({ field }) => (
              <FormItem className="flex min-w-[160px] flex-1">
                <FormControl>
                  <FeedbackDefinitionsAndScoresSelectBox
                    value={field.value as string}
                    onChange={field.onChange}
                    scoreSource={scoreSource}
                    entityIds={[projectId]}
                    multiselect={false}
                    className={cn("h-8 w-full font-normal", {
                      "border-destructive": Boolean(errors.name),
                    })}
                  />
                </FormControl>
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name={fieldPath(
              triggerIndex,
              groupIndex,
              conditionIndex,
              "operator",
            )}
            render={({ field }) => (
              <FormItem className="shrink-0">
                <FormControl>
                  <ToggleGroup
                    type="single"
                    variant="secondary"
                    value={field.value as string}
                    onValueChange={(v) => v && field.onChange(v)}
                    className={cn("h-8", {
                      "border-destructive": Boolean(errors.operator),
                    })}
                  >
                    {OPERATOR_VALUES.map((op) => (
                      <ToggleGroupItem
                        key={op}
                        value={op}
                        size="sm"
                        aria-label={
                          op === ">"
                            ? t("alerts.feedbackConditions.greaterThan")
                            : t("alerts.feedbackConditions.lessThan")
                        }
                      >
                        {op}
                      </ToggleGroupItem>
                    ))}
                  </ToggleGroup>
                </FormControl>
              </FormItem>
            )}
          />
          <FormField
            control={form.control}
            name={fieldPath(
              triggerIndex,
              groupIndex,
              conditionIndex,
              "threshold",
            )}
            render={({ field }) => (
              <FormItem className="w-[87px] shrink-0">
                <FormControl>
                  <Input
                    className={cn("h-8 text-right", {
                      "border-destructive": Boolean(errors.threshold),
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
            )}
          />
          <FormField
            control={form.control}
            name={fieldPath(triggerIndex, groupIndex, conditionIndex, "window")}
            render={({ field }) => (
              <FormItem className="flex min-w-[160px] flex-1">
                <FormControl>
                  <SelectBox
                    value={field.value as string}
                    onChange={field.onChange}
                    options={windowOptions}
                    className={cn("h-8 w-full text-left font-normal", {
                      "border-destructive": Boolean(errors.window),
                    })}
                    placeholder={t("alerts.triggers.selectTimeWindow")}
                    renderTrigger={(value) => {
                      const option = windowOptions.find(
                        (o) => o.value === value,
                      );
                      if (!option) return null;
                      return (
                        <span className="truncate">
                          <span className="text-muted-slate">
                            {t("alerts.triggers.inTheLast")}
                          </span>{" "}
                          {option.label}
                        </span>
                      );
                    }}
                  />
                </FormControl>
              </FormItem>
            )}
          />
        </div>
        <DisabledTooltip
          disabled={!canDelete}
          message={t("alerts.feedbackConditions.cantRemove")}
        >
          <Button
            type="button"
            variant="minimal"
            size="icon-2xs"
            className="h-auto w-6 rounded-none border-l border-border opacity-50 hover:opacity-100"
            onClick={onDelete}
            disabled={!canDelete}
            aria-label={t("alerts.feedbackConditions.removeCondition")}
          >
            <Trash />
          </Button>
        </DisabledTooltip>
      </div>
      {hasErrors && (
        <div className="flex flex-wrap gap-x-2 px-2 text-[0.8rem] font-medium text-destructive">
          {CONDITION_FIELDS.map(
            (f) => errors[f] && <span key={f}>{errors[f]}</span>,
          )}
        </div>
      )}
    </div>
  );
};

export default FeedbackScoreConditions;
