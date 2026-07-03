import { z } from "zod";
import i18next from "i18next";
import { COLUMN_TYPE } from "@/types/shared";
import { SORT_DIRECTION } from "@/types/sorting";
import { CHART_TYPE } from "@/constants/chart";
import { FiltersArraySchema } from "@/shared/FiltersAccordionSection/schema";
import {
  MIN_MAX_EXPERIMENTS,
  MAX_MAX_EXPERIMENTS,
  isValidIntegerInRange,
} from "@/lib/dashboard/utils";

const GroupSchema = z.object({
  id: z.string(),
  field: z.string(),
  direction: z.nativeEnum(SORT_DIRECTION).or(z.literal("")),
  type: z.nativeEnum(COLUMN_TYPE).or(z.literal("")),
  key: z.string().optional(),
  error: z.string().optional(),
});

export const ExperimentsFeedbackScoresWidgetSchema = z
  .object({
    filters: FiltersArraySchema.optional(),
    groups: z.array(GroupSchema).optional(),
    chartType: z.nativeEnum(CHART_TYPE).optional(),
    feedbackScores: z.array(z.string()).optional(),
    maxExperimentsCount: z.string().optional(),
  })
  .refine(
    (data) => {
      return isValidIntegerInRange(
        data.maxExperimentsCount || "",
        MIN_MAX_EXPERIMENTS,
        MAX_MAX_EXPERIMENTS,
      );
    },
    {
      message: i18next.t("common:validation.maxExperimentsCountRequired", { min: MIN_MAX_EXPERIMENTS, max: MAX_MAX_EXPERIMENTS }),
      path: ["maxExperimentsCount"],
    },
  );

export type ExperimentsFeedbackScoresWidgetFormData = z.infer<
  typeof ExperimentsFeedbackScoresWidgetSchema
>;
