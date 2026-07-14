import React, {
  useMemo,
  useEffect,
  forwardRef,
  useImperativeHandle,
} from "react";
import { useTranslation } from "react-i18next";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";

import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import VisualizationCardSelector from "@/v2/pages-shared/dashboards/widgets/shared/VisualizationCardSelector/VisualizationCardSelector";
import { LoadableSelectBox } from "@/shared/LoadableSelectBox/LoadableSelectBox";
import ProjectsSelectBox from "@/v2/pages-shared/automations/ProjectsSelectBox";
import ProjectWidgetFiltersSection from "@/v2/pages-shared/dashboards/widgets/shared/ProjectWidgetFiltersSection/ProjectWidgetFiltersSection";
import FeedbackDefinitionsAndScoresSelectBox, {
  ScoreSource,
} from "@/v2/pages-shared/experiments/FeedbackDefinitionsAndScoresSelectBox/FeedbackDefinitionsAndScoresSelectBox";
import ProjectMetricsBreakdownSection from "./ProjectMetricsBreakdownSection";

import { cn } from "@/lib/utils";
import {
  useDashboardStore,
  selectRuntimeConfig,
  selectUpdatePreviewWidget,
} from "@/store/DashboardStore";

import get from "lodash/get";

import { METRIC_NAME_TYPE } from "@/api/projects/useProjectMetric";
import useProjectTokenUsageNames from "@/api/projects/useProjectTokenUsageNames";
import { DEFAULT_DATE_PRESET } from "@/v2/pages-shared/traces/MetricDateRangeSelect/constants";
import {
  DashboardWidget,
  ProjectMetricsWidget,
  WidgetEditorHandle,
  BreakdownConfig,
} from "@/types/dashboard";
import {
  ProjectMetricsWidgetSchema,
  ProjectMetricsWidgetFormData,
} from "./schema";
import { CHART_TYPE } from "@/constants/chart";
import { Filter } from "@/types/filters";
import { BREAKDOWN_FIELD } from "@/types/dashboard";

const METRIC_OPTIONS_KEYS = [
  {
    value: METRIC_NAME_TYPE.FEEDBACK_SCORES,
    labelKey: "metrics.traceMetrics",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.TRACE_COUNT,
    labelKey: "metrics.numberOfTraces",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.TRACE_DURATION,
    labelKey: "metrics.traceDuration",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.TOKEN_USAGE,
    labelKey: "metrics.tokenUsage",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.COST,
    labelKey: "metrics.estimatedCost",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.FAILED_GUARDRAILS,
    labelKey: "metrics.failedGuardrails",
    filterType: "trace" as const,
  },
  {
    value: METRIC_NAME_TYPE.THREAD_COUNT,
    labelKey: "metrics.numberOfThreads",
    filterType: "thread" as const,
  },
  {
    value: METRIC_NAME_TYPE.THREAD_DURATION,
    labelKey: "metrics.threadDuration",
    filterType: "thread" as const,
  },
  {
    value: METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES,
    labelKey: "metrics.threadMetrics",
    filterType: "thread" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_COUNT,
    labelKey: "metrics.numberOfSpans",
    filterType: "span" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_DURATION,
    labelKey: "metrics.spanDuration",
    filterType: "span" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES,
    labelKey: "metrics.spanMetrics",
    filterType: "span" as const,
  },
  {
    value: METRIC_NAME_TYPE.SPAN_TOKEN_USAGE,
    labelKey: "metrics.spanTokenUsage",
    filterType: "span" as const,
  },
];

const DURATION_METRIC_OPTIONS_KEYS = [
  { value: "p50", labelKey: "metrics.p50" },
  { value: "p90", labelKey: "metrics.p90" },
  { value: "p99", labelKey: "metrics.p99" },
];

const ProjectMetricsEditor = forwardRef<WidgetEditorHandle>((_, ref) => {
  const { t } = useTranslation("dashboards");
  const widgetData = useDashboardStore(
    (state) => state.previewWidget!,
  ) as DashboardWidget & ProjectMetricsWidget;
  const updatePreviewWidget = useDashboardStore(selectUpdatePreviewWidget);

  const { config } = widgetData;
  const metricType = config.metricType || "";
  const chartType = config.chartType || CHART_TYPE.line;
  const localProjectId = config.projectId;

  const traceFilters = useMemo<Filter[]>(
    () => (config.traceFilters as Filter[] | undefined) || [],
    [config.traceFilters],
  );
  const threadFilters = useMemo<Filter[]>(
    () => (config.threadFilters as Filter[] | undefined) || [],
    [config.threadFilters],
  );
  const spanFilters = useMemo<Filter[]>(
    () => (config.spanFilters as Filter[] | undefined) || [],
    [config.spanFilters],
  );
  const feedbackScores = useMemo<string[]>(
    () => (config.feedbackScores as string[] | undefined) || [],
    [config.feedbackScores],
  );
  const durationMetrics = useMemo<string[]>(
    () => (config.durationMetrics as string[] | undefined) || [],
    [config.durationMetrics],
  );
  const usageMetrics = useMemo<string[]>(
    () => (config.usageMetrics as string[] | undefined) || [],
    [config.usageMetrics],
  );

  const breakdown = useMemo(
    () => ({
      field: BREAKDOWN_FIELD.NONE,
      aggregateTotal: !config.breakdown,
      ...config.breakdown,
    }),
    [config.breakdown],
  );

  const runtimeContext = useDashboardStore((state) => {
    const rc = selectRuntimeConfig(state);
    return {
      projectId: rc?.projectIds?.[0],
      dateRange: rc?.dateRange ?? DEFAULT_DATE_PRESET,
    };
  });
  const hasRuntimeProjectId = !!runtimeContext.projectId;
  const projectId = runtimeContext.projectId || localProjectId || "";

  const metricOptions = useMemo(
    () => METRIC_OPTIONS_KEYS.map((o) => ({ ...o, label: t(o.labelKey) })),
    [t],
  );
  const durationMetricOptions = useMemo(
    () =>
      DURATION_METRIC_OPTIONS_KEYS.map((o) => ({ ...o, label: t(o.labelKey) })),
    [t],
  );

  const selectedMetric = METRIC_OPTIONS_KEYS.find(
    (m) => m.value === metricType,
  );
  const isTraceMetric = !metricType || selectedMetric?.filterType === "trace";
  const isThreadMetric = metricType && selectedMetric?.filterType === "thread";
  const isSpanMetric = metricType && selectedMetric?.filterType === "span";
  const isFeedbackScoreMetric =
    metricType === METRIC_NAME_TYPE.FEEDBACK_SCORES ||
    metricType === METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES ||
    metricType === METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES;
  const isDurationMetric =
    metricType === METRIC_NAME_TYPE.TRACE_DURATION ||
    metricType === METRIC_NAME_TYPE.THREAD_DURATION ||
    metricType === METRIC_NAME_TYPE.SPAN_DURATION;
  const isTokenUsageMetric =
    metricType === METRIC_NAME_TYPE.TOKEN_USAGE ||
    metricType === METRIC_NAME_TYPE.SPAN_TOKEN_USAGE;

  const { data: tokenUsageNamesData, isPending: isLoadingUsageKeys } =
    useProjectTokenUsageNames(
      {
        projectId,
      },
      {
        enabled: isTokenUsageMetric && !!projectId,
      },
    );

  // Map token usage names to select options
  const usageKeyOptions = useMemo(() => {
    if (!tokenUsageNamesData?.names) return [];

    return tokenUsageNamesData.names
      .sort((a, b) => a.localeCompare(b))
      .map((name) => ({
        value: name,
        label: name,
      }));
  }, [tokenUsageNamesData?.names]);

  // For feedback score metrics, group by is only allowed when exactly one metric is selected
  const hasExactlyOneFeedbackScoreSelected = feedbackScores.length === 1;
  const isGroupByDisabledForFeedbackScore =
    isFeedbackScoreMetric && !hasExactlyOneFeedbackScoreSelected;

  // For duration metrics, group by is only allowed when exactly one metric is selected
  const hasExactlyOneDurationMetricSelected = durationMetrics.length === 1;
  const isGroupByDisabledForDuration =
    isDurationMetric && !hasExactlyOneDurationMetricSelected;

  // For token usage metrics, group by is only allowed when exactly one metric is selected
  const hasExactlyOneUsageMetricSelected = usageMetrics.length === 1;
  const isGroupByDisabledForUsage =
    isTokenUsageMetric && !hasExactlyOneUsageMetricSelected;

  const hasBreakdownField = breakdown.field !== BREAKDOWN_FIELD.NONE;

  const form = useForm<ProjectMetricsWidgetFormData>({
    resolver: zodResolver(ProjectMetricsWidgetSchema),
    mode: "onTouched",
    defaultValues: {
      metricType,
      chartType,
      projectId,
      traceFilters,
      threadFilters,
      spanFilters,
      feedbackScores,
      durationMetrics,
      usageMetrics,
      breakdown,
    },
  });

  const currentFilters = isTraceMetric
    ? form.watch("traceFilters") || []
    : isThreadMetric
      ? form.watch("threadFilters") || []
      : form.watch("spanFilters") || [];

  useEffect(() => {
    if (isTraceMetric && form.formState.errors.traceFilters) {
      form.clearErrors("traceFilters");
    }
    if (isThreadMetric && form.formState.errors.threadFilters) {
      form.clearErrors("threadFilters");
    }
    if (isSpanMetric && form.formState.errors.spanFilters) {
      form.clearErrors("spanFilters");
    }
  }, [
    currentFilters.length,
    form,
    isTraceMetric,
    isThreadMetric,
    isSpanMetric,
  ]);

  useImperativeHandle(ref, () => ({
    submit: async () => {
      return await form.trigger();
    },
    isValid: form.formState.isValid,
  }));

  const handleMetricTypeChange = (value: string) => {
    // Determine if this is a duration or token usage metric for defaults
    const isDuration =
      value === METRIC_NAME_TYPE.TRACE_DURATION ||
      value === METRIC_NAME_TYPE.THREAD_DURATION ||
      value === METRIC_NAME_TYPE.SPAN_DURATION;
    const isUsage =
      value === METRIC_NAME_TYPE.TOKEN_USAGE ||
      value === METRIC_NAME_TYPE.SPAN_TOKEN_USAGE;

    // Clear all filters, group by, and sub-metric selections when metric type changes
    // Also set defaults for duration and token usage metrics
    form.setValue("breakdown.field", BREAKDOWN_FIELD.NONE);
    form.setValue("breakdown.metadataKey", undefined);
    form.setValue("traceFilters", []);
    form.setValue("threadFilters", []);
    form.setValue("spanFilters", []);
    form.setValue("feedbackScores", []);
    form.setValue("durationMetrics", isDuration ? ["p50"] : []);
    form.setValue("usageMetrics", isUsage ? ["total_tokens"] : []);

    updatePreviewWidget({
      config: {
        ...config,
        metricType: value,
        traceFilters: [],
        threadFilters: [],
        spanFilters: [],
        feedbackScores: [],
        durationMetrics: isDuration ? ["p50"] : [],
        usageMetrics: isUsage ? ["total_tokens"] : [],
        breakdown: { field: BREAKDOWN_FIELD.NONE },
      },
    });
  };

  const handleChartTypeChange = (value: string) => {
    updatePreviewWidget({
      config: {
        ...config,
        chartType: value as CHART_TYPE.line | CHART_TYPE.bar,
      },
    });
  };

  const handleProjectChange = (projectId: string) => {
    updatePreviewWidget({
      config: {
        ...config,
        projectId,
      },
    });
  };

  const handleFeedbackScoresChange = (newFeedbackScores: string[]) => {
    // If changing from exactly one metric to something else, clear the breakdown
    const wasExactlyOne = feedbackScores.length === 1;
    const isExactlyOne = newFeedbackScores.length === 1;
    const shouldClearBreakdown =
      wasExactlyOne && !isExactlyOne && hasBreakdownField;

    updatePreviewWidget({
      config: {
        ...config,
        feedbackScores: newFeedbackScores,
        ...(shouldClearBreakdown && {
          breakdown: { field: BREAKDOWN_FIELD.NONE },
        }),
      },
    });
  };

  const handleDurationMetricsChange = (newDurationMetrics: string[]) => {
    // If changing from exactly one metric to something else, clear the breakdown
    const wasExactlyOne = durationMetrics.length === 1;
    const isExactlyOne = newDurationMetrics.length === 1;
    const shouldClearBreakdown =
      wasExactlyOne && !isExactlyOne && hasBreakdownField;

    updatePreviewWidget({
      config: {
        ...config,
        durationMetrics: newDurationMetrics,
        ...(shouldClearBreakdown && {
          breakdown: { field: BREAKDOWN_FIELD.NONE },
        }),
      },
    });
  };

  const handleUsageMetricsChange = (newUsageMetrics: string[]) => {
    // If changing from exactly one metric to something else, clear the breakdown
    const wasExactlyOne = usageMetrics.length === 1;
    const isExactlyOne = newUsageMetrics.length === 1;
    const shouldClearBreakdown =
      wasExactlyOne && !isExactlyOne && hasBreakdownField;

    updatePreviewWidget({
      config: {
        ...config,
        usageMetrics: newUsageMetrics,
        ...(shouldClearBreakdown && {
          breakdown: { field: BREAKDOWN_FIELD.NONE },
        }),
      },
    });
  };

  const handleBreakdownChange = (newBreakdown: Partial<BreakdownConfig>) => {
    const updatedBreakdown = {
      ...breakdown,
      ...newBreakdown,
    };
    updatePreviewWidget({
      config: {
        ...config,
        breakdown: updatedBreakdown,
      },
    });
  };

  return (
    <Form {...form}>
      <div className="space-y-4">
        <FormField
          control={form.control}
          name="projectId"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["projectId"]);
            return (
              <FormItem>
                <FormLabel>{t("statsCard.projectLabel")}</FormLabel>
                <FormControl>
                  <ProjectsSelectBox
                    className={cn("flex-1", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    value={field.value || ""}
                    onValueChange={(value) => {
                      field.onChange(value);
                      handleProjectChange(value);
                    }}
                    disabled={hasRuntimeProjectId}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />

        <FormField
          control={form.control}
          name="metricType"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["metricType"]);
            return (
              <FormItem>
                <FormLabel>{t("statsCard.metricTypeLabel")}</FormLabel>
                <FormControl>
                  <LoadableSelectBox
                    buttonClassName={cn("w-full", {
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                    value={field.value}
                    onChange={(value) => {
                      field.onChange(value);
                      handleMetricTypeChange(value);
                    }}
                    options={metricOptions}
                    placeholder={t("statsCard.selectMetric")}
                  />
                </FormControl>
                <FormMessage />
              </FormItem>
            );
          }}
        />

        {isFeedbackScoreMetric && projectId && (
          <FormField
            control={form.control}
            name="feedbackScores"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, [
                "feedbackScores",
              ]);
              return (
                <FormItem>
                  <FormLabel>{t("feedbackScores.metricsLabel")}</FormLabel>
                  <FormControl>
                    <FeedbackDefinitionsAndScoresSelectBox
                      value={field.value || []}
                      onChange={(value) => {
                        field.onChange(value);
                        handleFeedbackScoresChange(value);
                      }}
                      scoreSource={
                        metricType === METRIC_NAME_TYPE.THREAD_FEEDBACK_SCORES
                          ? ScoreSource.THREADS
                          : metricType === METRIC_NAME_TYPE.SPAN_FEEDBACK_SCORES
                            ? ScoreSource.SPANS
                            : ScoreSource.TRACES
                      }
                      entityIds={[projectId]}
                      multiselect={true}
                      showSelectAll={true}
                      placeholder={t("feedbackScores.allMetricsPlaceholder")}
                      className={cn({
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              );
            }}
          />
        )}

        {isDurationMetric && (
          <FormField
            control={form.control}
            name="durationMetrics"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, [
                "durationMetrics",
              ]);
              return (
                <FormItem>
                  <FormLabel>{t("metrics.durationMetricsLabel")}</FormLabel>
                  <FormControl>
                    <LoadableSelectBox
                      buttonClassName={cn("w-full", {
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                      value={field.value || []}
                      onChange={(value) => {
                        field.onChange(value);
                        handleDurationMetricsChange(value);
                      }}
                      options={durationMetricOptions}
                      placeholder={t("metrics.allPercentiles")}
                      multiselect
                      showSelectAll
                      selectAllLabel={t("metrics.allPercentiles")}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              );
            }}
          />
        )}

        {isTokenUsageMetric && projectId && (
          <FormField
            control={form.control}
            name="usageMetrics"
            render={({ field, formState }) => {
              const validationErrors = get(formState.errors, ["usageMetrics"]);
              return (
                <FormItem>
                  <FormLabel>{t("metrics.usageMetricsLabel")}</FormLabel>
                  <FormControl>
                    <LoadableSelectBox
                      buttonClassName={cn("w-full", {
                        "border-destructive": Boolean(
                          validationErrors?.message,
                        ),
                      })}
                      value={field.value || []}
                      onChange={(value) => {
                        field.onChange(value);
                        handleUsageMetricsChange(value);
                      }}
                      options={usageKeyOptions}
                      isLoading={isLoadingUsageKeys}
                      placeholder={t("metrics.allUsageMetrics")}
                      multiselect
                      showSelectAll
                      selectAllLabel={t("metrics.allUsageMetrics")}
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              );
            }}
          />
        )}

        <ProjectWidgetFiltersSection
          control={form.control}
          fieldName={
            isTraceMetric
              ? "traceFilters"
              : isThreadMetric
                ? "threadFilters"
                : "spanFilters"
          }
          projectId={projectId}
          filterType={
            isTraceMetric ? "trace" : isThreadMetric ? "thread" : "span"
          }
          onFiltersChange={(filters) => {
            updatePreviewWidget({
              config: {
                ...config,
                ...(isTraceMetric
                  ? { traceFilters: filters }
                  : isThreadMetric
                    ? { threadFilters: filters }
                    : { spanFilters: filters }),
              },
            });
          }}
        />

        <ProjectMetricsBreakdownSection
          control={form.control}
          metricType={metricType}
          projectId={projectId}
          isSpanMetric={!!isSpanMetric}
          breakdown={breakdown}
          isGroupByDisabledForFeedbackScore={isGroupByDisabledForFeedbackScore}
          isGroupByDisabledForDuration={isGroupByDisabledForDuration}
          isGroupByDisabledForUsage={isGroupByDisabledForUsage}
          onBreakdownChange={handleBreakdownChange}
        />

        <FormField
          control={form.control}
          name="chartType"
          render={({ field }) => (
            <FormItem>
              <FormLabel>{t("feedbackScores.visualizationLabel")}</FormLabel>
              <FormControl>
                <VisualizationCardSelector
                  value={field.value || CHART_TYPE.line}
                  onChange={(value) => {
                    field.onChange(value);
                    handleChartTypeChange(value);
                  }}
                  types={[CHART_TYPE.line, CHART_TYPE.bar]}
                />
              </FormControl>
            </FormItem>
          )}
        />
      </div>
    </Form>
  );
});

ProjectMetricsEditor.displayName = "ProjectMetricsEditor";

export default ProjectMetricsEditor;
