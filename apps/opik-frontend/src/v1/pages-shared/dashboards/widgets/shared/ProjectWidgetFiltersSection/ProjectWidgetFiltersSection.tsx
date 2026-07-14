import React, { useCallback, useEffect, useMemo } from "react";
import {
  Control,
  FieldPath,
  FieldValues,
  useController,
  useFormState,
} from "react-hook-form";
import isArray from "lodash/isArray";
import { useTranslation } from "react-i18next";

import { Filter } from "@/types/filters";
import { ColumnData } from "@/types/shared";
import FiltersAccordionSection from "@/shared/FiltersAccordionSection/FiltersAccordionSection";
import TracesOrSpansPathsAutocomplete from "@/v1/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/v1/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import ErrorTypeAutocomplete from "@/v1/pages-shared/traces/ErrorTypeAutocomplete/ErrorTypeAutocomplete";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import {
  COLUMN_CUSTOM_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_METADATA_ID,
} from "@/types/shared";
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "@/constants/filters";
import { getSpanTypeFilterConfig } from "@/v1/pages-shared/traces/spanTypeFilter";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import {
  TRACE_FILTER_COLUMNS,
  THREAD_FILTER_COLUMNS,
  SPAN_FILTER_COLUMNS,
} from "./constants";

interface ProjectWidgetFiltersSectionProps<T extends FieldValues> {
  control: Control<T>;
  fieldName: FieldPath<T>;
  projectId: string;
  filterType: "trace" | "thread" | "span";
  onFiltersChange?: (filters: Filter[]) => void;
  label?: string;
  className?: string;
}

const ProjectWidgetFiltersSection = <T extends FieldValues>({
  control,
  fieldName,
  projectId,
  filterType,
  onFiltersChange,
  label,
  className = "",
}: ProjectWidgetFiltersSectionProps<T>) => {
  const { t } = useTranslation("dashboards");
  const defaultLabel = label || t("filters.title");
  const { field: controllerField } = useController({
    control,
    name: fieldName,
  });

  const filters = (controllerField.value as Filter[]) || [];
  const isSpanMetric = filterType === "span";

  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const filterColumns = useMemo(() => {
    if (filterType === "thread") return THREAD_FILTER_COLUMNS;
    if (filterType === "span") return SPAN_FILTER_COLUMNS;
    return TRACE_FILTER_COLUMNS;
  }, [filterType]);

  // Determine the data type for API calls based on filter type
  const dataType = isSpanMetric
    ? TRACE_DATA_TYPE.spans
    : TRACE_DATA_TYPE.traces;

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_METADATA_ID]: {
          keyComponent:
            TracesOrSpansPathsAutocomplete as React.FunctionComponent<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            rootKeys: ["metadata"],
            projectId,
            type: dataType,
            placeholder: t("filters.keyPlaceholder"),
            excludeRoot: true,
          },
        },
        [COLUMN_CUSTOM_ID]: {
          keyComponent:
            TracesOrSpansPathsAutocomplete as React.FunctionComponent<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            rootKeys: ["input", "output"],
            projectId,
            type: dataType,
            placeholder: t("filters.keyPlaceholder"),
            excludeRoot: false,
          },
          validateFilter: (filter: Filter) => {
            if (
              filter.key &&
              filter.value &&
              !CUSTOM_FILTER_VALIDATION_REGEXP.test(filter.key)
            ) {
              return t("filters.invalidCustomKey");
            }
          },
        },
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent:
            TracesOrSpansFeedbackScoresSelect as React.FunctionComponent<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            projectId,
            type: dataType,
            placeholder: t("filters.selectScore"),
          },
        },
        error_type: {
          keyComponent:
            ErrorTypeAutocomplete as React.FunctionComponent<unknown> & {
              placeholder: string;
              value: string;
              onValueChange: (value: string) => void;
            },
          keyComponentProps: {
            projectId,
            type: dataType,
          },
        },
        ...(isSpanMetric ? getSpanTypeFilterConfig(isGuardrailsEnabled) : {}),
      },
    }),
    [projectId, dataType, isGuardrailsEnabled, isSpanMetric, t],
  );

  useEffect(() => {
    const formState = control._formState;
    const fieldError = formState.errors[fieldName];
    if (fieldError && filters.length > 0) {
      control._subjects.state.next({
        ...formState,
      });
    }
  }, [filters.length, control, fieldName]);

  const setFilters = useCallback(
    (filtersOrUpdater: Filter[] | ((prev: Filter[]) => Filter[])) => {
      let updatedFilters: Filter[];

      if (typeof filtersOrUpdater === "function") {
        const currentFilters = (controllerField.value as Filter[]) || [];
        updatedFilters = filtersOrUpdater(currentFilters);
      } else {
        updatedFilters = filtersOrUpdater;
      }

      controllerField.onChange(updatedFilters);
      onFiltersChange?.(updatedFilters);
    },
    [controllerField, onFiltersChange],
  );

  const { errors: formErrors } = useFormState({ control, name: fieldName });
  const fieldErrors = formErrors[fieldName];
  const errors =
    fieldErrors && isArray(fieldErrors)
      ? (fieldErrors as unknown[]).map((e) =>
          e
            ? (e as {
                field?: { message?: string };
                operator?: { message?: string };
                value?: { message?: string };
                key?: { message?: string };
              })
            : undefined,
        )
      : undefined;

  return (
    <FiltersAccordionSection
      columns={filterColumns as ColumnData<unknown>[]}
      config={filtersConfig}
      filters={filters}
      onChange={setFilters}
      label={defaultLabel}
      description={t("filters.filterTracesDescription")}
      className={className}
      errors={errors}
    />
  );
};

export default ProjectWidgetFiltersSection;
