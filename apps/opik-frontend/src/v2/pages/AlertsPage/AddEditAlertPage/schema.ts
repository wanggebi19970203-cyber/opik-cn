import i18next from "i18next";
import { z, RefinementCtx } from "zod";
import { ALERT_EVENT_TYPE, ALERT_TYPE } from "@/types/alerts";

export const HeaderSchema = z.object({
  key: z.string().min(1, { message: i18next.t("common.validation.headerKeyRequired") }),
  value: z.string().min(1, { message: i18next.t("common.validation.headerValueRequired") }),
});

export const FeedbackScoreConditionSchema = z.object({
  threshold: z.string(),
  window: z.string(),
  name: z.string(),
  operator: z.enum([">", "<"]),
});

export const FeedbackScoreConditionGroupSchema = z.object({
  conditions: z.array(FeedbackScoreConditionSchema),
});

const FEEDBACK_SCORE_TRIGGERS = new Set<ALERT_EVENT_TYPE>([
  ALERT_EVENT_TYPE.trace_feedback_score,
  ALERT_EVENT_TYPE.trace_thread_feedback_score,
]);
const SIMPLE_THRESHOLD_TRIGGERS = new Set<ALERT_EVENT_TYPE>([
  ALERT_EVENT_TYPE.trace_cost,
  ALERT_EVENT_TYPE.trace_latency,
  ALERT_EVENT_TYPE.trace_errors,
]);

const CONDITION_REQUIRED_FIELDS = [
  ["threshold", i18next.t("common.validation.thresholdIsRequired")],
  ["window", i18next.t("common.validation.windowIsRequired")],
  ["name", i18next.t("common.validation.feedbackScoreNameRequired")],
  ["operator", i18next.t("common.validation.operatorRequired")],
] as const;

const addRequired = (
  ctx: RefinementCtx,
  value: string | undefined,
  path: (string | number)[],
  message: string,
) => {
  if (!value || value.trim() === "") {
    ctx.addIssue({ code: z.ZodIssueCode.custom, message, path });
    return false;
  }
  return true;
};

const validateNumeric = (
  ctx: RefinementCtx,
  value: string,
  path: (string | number)[],
) => {
  if (isNaN(parseFloat(value))) {
    ctx.addIssue({
      code: z.ZodIssueCode.custom,
      message: i18next.t("common.validation.thresholdMustBeValidNumber"),
      path,
    });
  }
};

export const TriggerSchema = z
  .object({
    eventType: z.nativeEnum(ALERT_EVENT_TYPE),
    threshold: z.string().optional(),
    window: z.string().optional(),
    name: z.string().optional(), // Feedback score name (deprecated, use groups)
    operator: z.string().optional(), // Operator for comparison (deprecated, use groups)
    groups: z.array(FeedbackScoreConditionGroupSchema).optional(), // AND within a group, OR between groups
  })
  .superRefine((data, ctx) => {
    if (FEEDBACK_SCORE_TRIGGERS.has(data.eventType)) {
      if (!data.groups?.length) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: i18next.t("common.validation.atLeastOneConditionRequired"),
          path: ["groups"],
        });
        return;
      }
      data.groups.forEach((group, gi) => {
        if (!group.conditions?.length) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: i18next.t("common.validation.atLeastOneConditionRequired"),
            path: ["groups", gi, "conditions"],
          });
          return;
        }
        group.conditions.forEach((condition, ci) => {
          const base = ["groups", gi, "conditions", ci] as const;
          for (const [field, message] of CONDITION_REQUIRED_FIELDS) {
            const present = addRequired(
              ctx,
              condition[field],
              [...base, field],
              message,
            );
            if (field === "threshold" && present) {
              validateNumeric(ctx, condition.threshold, [...base, "threshold"]);
            }
          }
        });
      });
      return;
    }

    if (SIMPLE_THRESHOLD_TRIGGERS.has(data.eventType)) {
      const thresholdPresent = addRequired(
        ctx,
        data.threshold,
        ["threshold"],
        i18next.t("common.validation.thresholdIsRequired"),
      );
      if (thresholdPresent) {
        validateNumeric(ctx, data.threshold!, ["threshold"]);
      }
      addRequired(ctx, data.window, ["window"], i18next.t("common.validation.windowIsRequired"));
    }
  });

export const AlertFormSchema = z
  .object({
    name: z
      .string({ required_error: i18next.t("common.validation.alertNameRequired") })
      .min(1, { message: i18next.t("common.validation.alertNameRequired") }),
    enabled: z.boolean().default(true),
    alertType: z.nativeEnum(ALERT_TYPE).default(ALERT_TYPE.general),
    routingKey: z.string().optional(),
    url: z
      .string({ required_error: i18next.t("common.validation.endpointUrlRequired") })
      .min(1, { message: i18next.t("common.validation.endpointUrlRequired") })
      .url({ message: i18next.t("common.validation.pleaseEnterValidUrl") }),
    secretToken: z.string().optional(),
    headers: z.array(HeaderSchema).default([]),
    triggers: z.array(TriggerSchema).default([]),
  })
  .refine(
    (data) => {
      return data.triggers.length > 0;
    },
    {
      message: i18next.t("common.validation.atLeastOneTriggerMustBeSelected"),
      path: ["triggers"],
    },
  )
  .refine(
    (data) => {
      // If alert type is PagerDuty, routing_key is required
      if (data.alertType === ALERT_TYPE.pagerduty) {
        return data.routingKey && data.routingKey.trim().length > 0;
      }
      return true;
    },
    {
      message: i18next.t("common.validation.routingKeyRequiredForPagerDuty"),
      path: ["routingKey"],
    },
  );

export type AlertFormType = z.infer<typeof AlertFormSchema>;
export type TriggerFormType = z.infer<typeof TriggerSchema>;
export type FeedbackScoreConditionType = z.infer<
  typeof FeedbackScoreConditionSchema
>;
export type FeedbackScoreConditionGroupType = z.infer<
  typeof FeedbackScoreConditionGroupSchema
>;
