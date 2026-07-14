import { z } from "zod";
import i18next from "i18next";
import { FiltersArraySchema } from "@/shared/FiltersAccordionSection/schema";
import { CHART_TYPE } from "@/constants/chart";
import { BREAKDOWN_FIELD } from "@/types/dashboard";

export const BreakdownConfigSchema = z
  .object({
    field: z.nativeEnum(BREAKDOWN_FIELD).default(BREAKDOWN_FIELD.NONE),
    metadataKey: z.string().optional(),
    aggregateTotal: z.boolean().optional(),
  })
  .refine(
    (data) => {
      // If field is METADATA, metadataKey is required
      if (data.field === BREAKDOWN_FIELD.METADATA) {
        return data.metadataKey && data.metadataKey.trim().length > 0;
      }
      return true;
    },
    {
      message: i18next.t(
        "common:validation.metadataKeyRequiredWhenGroupByMetadata",
      ),
      path: ["metadataKey"],
    },
  );

export const ProjectMetricsWidgetSchema = z.object({
  metricType: z
    .string({
      required_error: i18next.t("common:validation.metricTypeRequired"),
    })
    .min(1, { message: i18next.t("common:validation.metricTypeRequired") }),
  chartType: z.nativeEnum(CHART_TYPE),
  projectId: z.string().optional(),
  traceFilters: FiltersArraySchema.optional(),
  threadFilters: FiltersArraySchema.optional(),
  spanFilters: FiltersArraySchema.optional(),
  feedbackScores: z.array(z.string()).optional(),
  durationMetrics: z.array(z.string()).optional(),
  usageMetrics: z.array(z.string()).optional(),
  breakdown: BreakdownConfigSchema.optional(),
});

export type BreakdownConfigFormData = z.infer<typeof BreakdownConfigSchema>;

export type ProjectMetricsWidgetFormData = z.infer<
  typeof ProjectMetricsWidgetSchema
>;
