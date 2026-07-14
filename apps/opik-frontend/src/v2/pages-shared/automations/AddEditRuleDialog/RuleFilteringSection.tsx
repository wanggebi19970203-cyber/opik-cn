import React, { useCallback, useEffect, useMemo } from "react";
import { UseFormReturn } from "react-hook-form";
import { Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
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
import TracesOrSpansPathsAutocomplete from "@/v2/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/v2/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import { getTagsFilterConfig } from "@/v2/pages-shared/TagsAutocomplete/tagsFilterConfig";
import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import { EvaluationRuleFormType } from "./schema";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { Description } from "@/ui/description";
import { getSpanTypeFilterConfig } from "@/v2/pages-shared/traces/spanTypeFilter";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import { DropdownOption } from "@/types/shared";
import { FilterOperator } from "@/types/filters";
import type { TFunction } from "i18next";

// Trace-specific columns for automation rule filtering
export const getTraceFilterColumns = (
  t: TFunction,
): ColumnData<TRACE_DATA_TYPE>[] => [
  {
    id: "id",
    label: t("ruleFiltering.filterColumns.id"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "name",
    label: t("ruleFiltering.filterColumns.name"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "input",
    label: t("ruleFiltering.filterColumns.input"),
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "output",
    label: t("ruleFiltering.filterColumns.output"),
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "duration",
    label: t("ruleFiltering.filterColumns.duration"),
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_METADATA_ID,
    label: t("ruleFiltering.filterColumns.metadata"),
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: t("ruleFiltering.filterColumns.tags"),
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "thread_id",
    label: t("ruleFiltering.filterColumns.threadId"),
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: t("ruleFiltering.filterColumns.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
  },
];

// Backward-compatible static export (uses English defaults)
export const TRACE_FILTER_COLUMNS = getTraceFilterColumns(
  ((key: string) => key.split(".").pop() ?? key) as TFunction,
);

// Thread-specific columns for automation rule filtering
export const getThreadFilterColumns = (
  t: TFunction,
): ColumnData<TRACE_DATA_TYPE>[] => [
  {
    id: "status",
    label: t("ruleFiltering.filterColumns.status"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "created_at",
    label: t("ruleFiltering.filterColumns.createdAt"),
    type: COLUMN_TYPE.time,
  },
  {
    id: "last_updated_at",
    label: t("ruleFiltering.filterColumns.lastUpdatedAt"),
    type: COLUMN_TYPE.time,
  },
  {
    id: "duration",
    label: t("ruleFiltering.filterColumns.duration"),
    type: COLUMN_TYPE.duration,
  },
  {
    id: "tags",
    label: t("ruleFiltering.filterColumns.tags"),
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: t("ruleFiltering.filterColumns.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
  },
];

export const THREAD_FILTER_COLUMNS = getThreadFilterColumns(
  ((key: string) => key.split(".").pop() ?? key) as TFunction,
);

// Span-specific columns for automation rule filtering
export const getSpanFilterColumns = (
  t: TFunction,
): ColumnData<TRACE_DATA_TYPE>[] => [
  {
    id: "id",
    label: t("ruleFiltering.filterColumns.id"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "name",
    label: t("ruleFiltering.filterColumns.name"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "input",
    label: t("ruleFiltering.filterColumns.input"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "output",
    label: t("ruleFiltering.filterColumns.output"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "duration",
    label: t("ruleFiltering.filterColumns.duration"),
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_METADATA_ID,
    label: t("ruleFiltering.filterColumns.metadata"),
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: "tags",
    label: t("ruleFiltering.filterColumns.tags"),
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: "type",
    label: t("ruleFiltering.filterColumns.type"),
    type: COLUMN_TYPE.category,
  },
  {
    id: "model",
    label: t("ruleFiltering.filterColumns.model"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "provider",
    label: t("ruleFiltering.filterColumns.provider"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "usage.total_tokens",
    label: t("ruleFiltering.filterColumns.totalTokens"),
    type: COLUMN_TYPE.number,
  },
  {
    id: "usage.prompt_tokens",
    label: t("ruleFiltering.filterColumns.totalInputTokens"),
    type: COLUMN_TYPE.number,
  },
  {
    id: "usage.completion_tokens",
    label: t("ruleFiltering.filterColumns.totalOutputTokens"),
    type: COLUMN_TYPE.number,
  },
  {
    id: "total_estimated_cost",
    label: t("ruleFiltering.filterColumns.estimatedCost"),
    type: COLUMN_TYPE.cost,
  },
  {
    id: "error_info",
    label: t("ruleFiltering.filterColumns.errors"),
    type: COLUMN_TYPE.errors,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: t("ruleFiltering.filterColumns.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: t("ruleFiltering.filterColumns.customFilter"),
    type: COLUMN_TYPE.dictionary,
  },
];

export const SPAN_FILTER_COLUMNS = getSpanFilterColumns(
  ((key: string) => key.split(".").pop() ?? key) as TFunction,
);

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
  const { t } = useTranslation("online-evaluation");
  const scope = form.watch("scope");
  const isTraceScope = scope === EVALUATORS_RULE_SCOPE.trace;
  const isThreadScope = scope === EVALUATORS_RULE_SCOPE.thread;
  const isSpanScope = scope === EVALUATORS_RULE_SCOPE.span;
  const filters = form.watch("filters");
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const currentFilterColumns = useMemo(() => {
    if (isThreadScope) return getThreadFilterColumns(t);
    if (isSpanScope) return getSpanFilterColumns(t);
    return getTraceFilterColumns(t);
  }, [isThreadScope, isSpanScope, t]);

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
        label: t("ruleFiltering.isEmpty"),
        value: "is_empty",
      },
      {
        label: t("ruleFiltering.isNotEmpty"),
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
            placeholder: t("ruleFiltering.filterPlaceholders.key"),
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
                    "ruleFiltering.filterPlaceholders.keyOptional",
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
                    "ruleFiltering.filterPlaceholders.keyOptional",
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
            placeholder: t("ruleFiltering.filterPlaceholders.key"),
            excludeRoot: false,
          },
          operators: ruleDictionaryOperators,
          validateFilter: (filter: Filter) => {
            if (
              filter.key &&
              filter.value &&
              !CUSTOM_FILTER_VALIDATION_REGEXP.test(filter.key)
            ) {
              return t("ruleFiltering.filterValidation.customKeyInvalid");
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
            placeholder: t("ruleFiltering.filterPlaceholders.selectScore"),
          },
        },
        ...getTagsFilterConfig({
          projectId,
          entityType: isThreadScope
            ? "threads"
            : isSpanScope
              ? "spans"
              : "traces",
        }),
        ...(isSpanScope ? getSpanTypeFilterConfig(isGuardrailsEnabled) : {}),
      },
    }),
    [
      projectId,
      isTraceScope,
      isSpanScope,
      isThreadScope,
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
              {t("ruleFiltering.filteringAndSampling")}
            </Label>
            <ExplainerIcon
              className="mt-0.5"
              description={
                isTraceScope
                  ? t("ruleFiltering.traceExplainer")
                  : isThreadScope
                    ? t("ruleFiltering.threadExplainer")
                    : t("ruleFiltering.spanExplainer")
              }
            />
          </div>
        </AccordionTrigger>
        <AccordionContent className="px-3 pb-3">
          <div className="mb-8 space-y-4">
            <Description>
              {t("ruleFiltering.samplingDescription", {
                entityType:
                  scope === EVALUATORS_RULE_SCOPE.trace
                    ? t("ruleFiltering.entityTypeTraces")
                    : scope === EVALUATORS_RULE_SCOPE.thread
                      ? t("ruleFiltering.entityTypeThreads")
                      : t("ruleFiltering.entityTypeSpans"),
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
                        {t("ruleFiltering.filtersLabel")}
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
                                {t("ruleFiltering.filterErrorPrefix", {
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
                          {t("ruleFiltering.addFilter")}
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
                  label={t("ruleFiltering.samplingRateLabel")}
                  tooltip={t("ruleFiltering.samplingRateTooltip")}
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
