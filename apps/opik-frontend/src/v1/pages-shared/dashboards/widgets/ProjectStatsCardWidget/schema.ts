import { z } from "zod";
import i18next from "i18next";
import { FiltersArraySchema } from "@/shared/FiltersAccordionSection/schema";
import { TRACE_DATA_TYPE } from "@/constants/traces";

export const ProjectStatsCardWidgetSchema = z.object({
  source: z.nativeEnum(TRACE_DATA_TYPE),
  projectId: z.string().optional(),
  projectIds: z.array(z.string()).optional(),
  allProjects: z.boolean().optional(),
  metric: z.string().min(1, i18next.t("common:validation.metricRequired")),
  usageMetric: z.string().optional(),
  traceFilters: FiltersArraySchema.optional(),
  spanFilters: FiltersArraySchema.optional(),
});

export type ProjectStatsCardWidgetFormData = z.infer<
  typeof ProjectStatsCardWidgetSchema
>;
