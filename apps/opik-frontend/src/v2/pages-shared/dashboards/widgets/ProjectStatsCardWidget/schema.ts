import { z } from "zod";
import { TFunction } from "i18next";
import { FiltersArraySchema } from "@/shared/FiltersAccordionSection/schema";
import { TRACE_DATA_TYPE } from "@/constants/traces";

export const createProjectStatsCardWidgetSchema = (t: TFunction) =>
  z.object({
    source: z.nativeEnum(TRACE_DATA_TYPE),
    projectId: z.string().optional(),
    metric: z.string().min(1, t("dashboards:schema.metricRequired")),
    traceFilters: FiltersArraySchema.optional(),
    spanFilters: FiltersArraySchema.optional(),
  });

export type ProjectStatsCardWidgetFormData = z.infer<
  ReturnType<typeof createProjectStatsCardWidgetSchema>
>;
