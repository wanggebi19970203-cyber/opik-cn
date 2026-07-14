import React, { useCallback, useEffect, useMemo } from "react";
import { UseFormReturn } from "react-hook-form";
import { Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
import i18next from "i18next";
import uniqid from "uniqid";
import round from "lodash/round";
import isArray from "lodash/isArray";

import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import { FormErrorSkeleton, FormField, FormItem } from "@/ui/form";
import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import { Filter } from "@/types/filters";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
  COLUMN_METADATA_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_CUSTOM_ID,
} from "@/types/shared";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  CUSTOM_FILTER_VALIDATION_REGEXP,
  getOperatorsMap,
} from "@/constants/filters";
import { createFilter } from "@/lib/filters";
import FiltersContent from "@/shared/FiltersContent/FiltersContent";
import TracesOrSpansPathsAutocomplete from "@/v1/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/v1/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { EvaluationRuleFormType } from "./schema";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { Description } from "@/ui/description";
import { getSpanTypeFilterConfig } from "@/v1/pages-shared/traces/spanTypeFilter";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { DropdownOption } from "@/types/shared";
import { FilterOperator } from "@/types/filters";

// Trace-specific columns for automation rule filtering
export const TRACE_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  {
    id: "id",
    label: i18next.t("common.labels.id"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "name",
    label: i18next.t("common.labels.name"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "input",
    label: i18next.t("common.labels.input"),
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "output",
    label: i18next.t("common.labels.output"),
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "duration",
    label: i18next.t("common.labels.duration"),
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_METADATA_ID,
    label: i18next.t("common.labels.metadata"),
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: i18next.t("common.labels.tags"),
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "thread_id",
    label: i18next.t("common.automations.threadId"),
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: i18next.t("common.labels.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
  },
];

// Thread-specific columns for automation rule filtering
export const THREAD_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  // {
  //   id: "id",
  //   label: "ID",
  //   type: COLUMN_TYPE.string,
  // },
  {
    id: "status",
    label: i18next.t("common.labels.status"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "created_at",
    label: i18next.t("common.labels.created"),
    type: COLUMN_TYPE.time,
  },
  {
    id: "last_updated_at",
    label: i18next.t("common.labels.updated"),
    type: COLUMN_TYPE.time,
  },
  // {
  //   id: "start_time",
  //   label: "Start time",
  //   type: COLUMN_TYPE.time,
  // },
  // {
  //   id: "end_time",
  //   label: "End time",
  //   type: COLUMN_TYPE.time,
  // },
  {
    id: "duration",
    label: i18next.t("common.labels.duration"),
    type: COLUMN_TYPE.duration,
  },
  {
    id: "tags",
    label: i18next.t("common.labels.tags"),
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: i18next.t("common.labels.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
  },
];

// Span-specific columns for automation rule filtering
export const SPAN_FILTER_COLUMNS: ColumnData<TRACE_DATA_TYPE>[] = [
  {
    id: "id",
    label: i18next.t("common.labels.id"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "name",
    label: i18next.t("common.labels.name"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "input",
    label: i18next.t("common.labels.input"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "output",
    label: i18next.t("common.labels.output"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "duration",
    label: i18next.t("common.labels.duration"),
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_METADATA_ID,
    label: i18next.t("common.labels.metadata"),
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: i18next.t("common.labels.tags"),
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "type",
    label: i18next.t("common.labels.type"),
    type: COLUMN_TYPE.category,
  },
  {
    id: "model",
    label: i18next.t("common.labels.model"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "provider",
    label: i18next.t("common.automations.provider"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "usage.total_tokens",
    label: i18next.t("common.labels.totalTokens"),
    type: COLUMN_TYPE.number,
  },
  {
    id: "usage.prompt_tokens",
    label: i18next.t("common.labels.totalInputTokens"),
    type: COLUMN_TYPE.number,
  },
  {
    id: "usage.completion_tokens",
    label: i18next.t("common.labels.totalOutputTokens"),
    type: COLUMN_TYPE.number,
  },
  {
    id: "total_estimated_cost",
    label: i18next.t("common.labels.estimatedCost"),
    type: COLUMN_TYPE.cost,
  },
  {
    id: "error_info",
    label: i18next.t("common.labels.errors"),
    type: COLUMN_TYPE.errors,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: i18next.t("common.labels.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: i18next.t("common.labels.customFilter"),
    type: COLUMN_TYPE.dictionary,
  },
];

// Exported for backward compatibility
export const AUTOMATION_RULE_FILTER_COLUMNS = TRACE_FILTER_COLUMNS;

const DEFAULT_SAMPLING_RATE = 1;

interface RuleFilteringSectionProps {
  form: UseFormReturn<EvaluationRuleFormType>;
  projectId: string;
}

const RuleFilteringSection: React.FC<RuleFilteringSectionProps> = ({
  form,
  projectId,
}) => {
  const { t } = useTranslation();
  const scope = form.watch("scope");
  const isTraceScope = scope === EVALUATORS_RULE_SCOPE.trace;
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;
  const filters = form.watch("filters");
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const currentFilterColumns = useMemo(() => {
    if (isThreadScope) return THREAD_FILTER_COLUMNS;
    if (isSpanScope) return SPAN_FILTER_COLUMNS;
    return TRACE_FILTER_COLUMNS;
  }, [isThreadScope, isSpanScope]);

  useEffect(() => {
    if (form.formState.errors.filters) {
      form.clearErrors("filters");
    }
  }, [filters.length, form]);

  // Rule-specific operators for dictionary filters (includes is_empty and is_not_empty)
  const ruleDictionaryOperators: DropdownOption<FilterOperator>[] = useMemo(
    () => [
      ...(getOperatorsMap()[COLUMN_TYPE.dictionary] || []),
      {
        label: t("common.filters.operators.isEmpty"),
        value: "is_empty",
      },
      {
        label: t("common.filters.operators.isNotEmpty"),
        value: "is_not_empty",
      },
    ],
    [t],
  );

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_METADATA_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            rootKeys: ["metadata"],
            projectId,
            type: isSpanScope ? TRACE_DATA_TYPE.spans : TRACE_DATA_TYPE.traces,
            placeholder: t("common.automations.filterKeyPlaceholder"),
            excludeRoot: true,
          },
          operators: ruleDictionaryOperators,
        },
        ...(isTraceScope
          ? {
              input: {
                keyComponent:
                  TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
                    placeholder: string;
                    value: string;
                    onValueChange: (value: string) => void;
                  },
                keyComponentProps: {
                  rootKeys: ["input"],
                  projectId,
                  type: TRACE_DATA_TYPE.traces,
                  placeholder: t(
                    "common.automations.filterKeyOptionalPlaceholder",
                  ),
                  excludeRoot: true,
                },
                operators: ruleDictionaryOperators,
                defaultOperator: "contains" as FilterOperator,
              },
              output: {
                keyComponent:
                  TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
                    placeholder: string;
                    value: string;
                    onValueChange: (value: string) => void;
                  },
                keyComponentProps: {
                  rootKeys: ["output"],
                  projectId,
                  type: TRACE_DATA_TYPE.traces,
                  placeholder: t(
                    "common.automations.filterKeyOptionalPlaceholder",
                  ),
                  excludeRoot: true,
                },
                operators: ruleDictionaryOperators,
                defaultOperator: "contains" as FilterOperator,
              },
            }
          : {}),
        [COLUMN_CUSTOM_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete as React.FC<unknown> & {
            placeholder: string;
            value: string;
            onValueChange: (value: string) => void;
          },
          keyComponentProps: {
            rootKeys: ["input", "output"],
            projectId,
            type: isSpanScope ? TRACE_DATA_TYPE.spans : TRACE_DATA_TYPE.traces,
            placeholder: t("common.automations.filterKeyPlaceholder"),
            excludeRoot: false,
          },
          operators: ruleDictionaryOperators,
          validateFilter: (filter: Filter) => {
            if (
              filter.key &&
              filter.value &&
              !CUSTOM_FILTER_VALIDATION_REGEXP.test(filter.key)
            ) {
              return t("common.automations.invalidFilterKeyError");
            }
          },
        },
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent:
            TracesOrSpansFeedbackScoresSelect as React.FC<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            projectId,
            type: isSpanScope ? TRACE_DATA_TYPE.spans : TRACE_DATA_TYPE.traces,
            placeholder: t("common.automations.selectScorePlaceholder"),
          },
        },
        ...(isSpanScope ? getSpanTypeFilterConfig(isGuardrailsEnabled) : {}),
      },
    }),
    [
      projectId,
      isTraceScope,
      isSpanScope,
      isGuardrailsEnabled,
      ruleDictionaryOperators,
      t,
    ],
  );

  const handleAddFilter = useCallback(() => {
    const currentFilters = form.getValues("filters");
    const newFilter = {
      ...createFilter(),
      id: uniqid(),
    };
    form.setValue("filters", [...currentFilters, newFilter]);
  }, [form]);

  const setFilters = useCallback(
    (filtersOrUpdater: Filter[] | ((prev: Filter[]) => Filter[])) => {
      if (typeof filtersOrUpdater === "function") {
        const currentFilters = form.getValues("filters");
        const updatedFilters = filtersOrUpdater(currentFilters);
        form.setValue("filters", updatedFilters);
      } else {
        form.setValue("filters", filtersOrUpdater);
      }
    },
    [form],
  );

  return (
    <Accordion
      type="single"
      collapsible
      className="-mb-4 w-full border-t border-border"
    >
      <AccordionItem value="filtering-sampling" className="border-none">
        <AccordionTrigger className="px-3 py-2 hover:no-underline">
          <div className="flex items-center gap-1">
            <Label className="text-sm font-medium">
              {t("common.automations.filteringAndSampling")}
            </Label>
            <ExplainerIcon
              className="mt-0.5"
              description={
                isTraceScope
                  ? t("common.automations.filteringAndSamplingTraceDescription")
                  : isThreadScope
                    ? t(
                        "common.automations.filteringAndSamplingThreadDescription",
                      )
                    : t(
                        "common.automations.filteringAndSamplingSpanDescription",
                      )
              }
            />
          </div>
        </AccordionTrigger>
        <AccordionContent className="px-3 pb-3">
          <div className="mb-8 space-y-4">
            <Description>
              {t("common.automations.samplingRateDescription", {
                scope:
                  scope === EVALUATORS_RULE_SCOPE.trace
                    ? t("common.automations.traces")
                    : scope === EVALUATORS_RULE_SCOPE.thread
                      ? t("common.automations.threads")
                      : t("common.automations.spans"),
              })}
            </Description>

            <FormField
              control={form.control}
              name="filters"
              render={({ field, formState }) => {
                const filterErrors = formState.errors.filters;
                const hasErrors = filterErrors && isArray(filterErrors);

                return (
                  <FormItem>
                    <div className="space-y-3">
                      <Label className="text-sm font-medium">
                        {t("common.filters.label")}
                      </Label>

                      {field.value.length > 0 && (
                        <FiltersContent
                          filters={field.value}
                          setFilters={setFilters}
                          columns={currentFilterColumns}
                          config={filtersConfig}
                          className="py-0"
                        />
                      )}

                      {/* Display validation errors from form submission */}
                      {hasErrors && filterErrors.length > 0 && (
                        <div className="space-y-1">
                          {filterErrors.map((filterError, index) => {
                            if (!filterError) return null;

                            const errors: string[] = [];

                            // Collect all error messages for this filter
                            if (filterError.field?.message) {
                              errors.push(filterError.field.message);
                            }
                            if (filterError.operator?.message) {
                              errors.push(filterError.operator.message);
                            }
                            if (filterError.value?.message) {
                              errors.push(filterError.value.message);
                            }
                            if (filterError.key?.message) {
                              errors.push(filterError.key.message);
                            }

                            if (errors.length === 0) return null;

                            return (
                              <FormErrorSkeleton key={index}>
                                {t("common.automations.filterErrorLabel", {
                                  index: index + 1,
                                  errors: errors.join(", "),
                                })}
                              </FormErrorSkeleton>
                            );
                          })}
                        </div>
                      )}

                      <div className="pt-1">
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={handleAddFilter}
                          className="w-fit"
                        >
                          <Plus className="mr-1 size-3.5" />
                          {t("common.automations.addFilter")}
                        </Button>
                      </div>
                    </div>
                  </FormItem>
                );
              }}
            />

            {/* Sampling Rate */}
            <FormField
              control={form.control}
              name="samplingRate"
              render={({ field }) => (
                <SliderInputControl
                  min={0}
                  max={100}
                  step={1}
                  defaultValue={DEFAULT_SAMPLING_RATE * 100}
                  value={round((field.value ?? DEFAULT_SAMPLING_RATE) * 100, 1)}
                  onChange={(displayValue) =>
                    field.onChange(round(displayValue, 1) / 100)
                  }
                  id="sampling_rate"
                  label={t("common.automations.samplingRate")}
                  tooltip={t("common.automations.samplingRateTooltip")}
                  suffix="%"
                />
              )}
            />
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default RuleFilteringSection;
