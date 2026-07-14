import { z } from "zod";
import i18next from "i18next";
import { ALERT_EVENT_TYPE, ALERT_TYPE } from "@/types/alerts";

export const HeaderSchema = z.object({
  key: z
    .string()
    .min(1, { message: i18next.t("common:validation.headerKeyRequired") }),
  value: z
    .string()
    .min(1, { message: i18next.t("common:validation.headerValueRequired") }),
});

export const FeedbackScoreConditionSchema = z.object({
  threshold: z.string(),
  window: z.string(),
  name: z.string(), // Feedback score name
  operator: z.string(), // Operator for comparison (>, <)
});

export const TriggerSchema = z
  .object({
    eventType: z.nativeEnum(ALERT_EVENT_TYPE),
    projectIds: z.array(z.string()).default([]),
    threshold: z.string().optional(),
    window: z.string().optional(),
    name: z.string().optional(), // Feedback score name (deprecated, use conditions)
    operator: z.string().optional(), // Operator for comparison (deprecated, use conditions)
    conditions: z.array(FeedbackScoreConditionSchema).optional(), // Multiple conditions for feedback scores
  })
  .superRefine((data, ctx) => {
    const isFeedbackScoreTrigger =
      data.eventType === ALERT_EVENT_TYPE.trace_feedback_score ||
      data.eventType === ALERT_EVENT_TYPE.trace_thread_feedback_score;

    // Validate feedback score triggers (use conditions array)
    if (isFeedbackScoreTrigger) {
      if (!data.conditions || data.conditions.length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: i18next.t("common:validation.atLeastOneConditionRequired"),
          path: ["conditions"],
        });
      } else {
        // Validate each condition
        data.conditions.forEach((condition, index) => {
          if (!condition.threshold || condition.threshold.trim() === "") {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: i18next.t("common:validation.thresholdIsRequired"),
              path: ["conditions", index, "threshold"],
            });
          } else {
            const thresholdNum = parseFloat(condition.threshold);
            if (isNaN(thresholdNum)) {
              ctx.addIssue({
                code: z.ZodIssueCode.custom,
                message: i18next.t(
                  "common:validation.thresholdMustBeValidNumber",
                ),
                path: ["conditions", index, "threshold"],
              });
            }
          }

          if (!condition.window || condition.window.trim() === "") {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: i18next.t("common:validation.windowIsRequired"),
              path: ["conditions", index, "window"],
            });
          }

          if (!condition.name || condition.name.trim() === "") {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: i18next.t("common:validation.feedbackScoreNameRequired"),
              path: ["conditions", index, "name"],
            });
          }

          if (!condition.operator || condition.operator.trim() === "") {
            ctx.addIssue({
              code: z.ZodIssueCode.custom,
              message: i18next.t("common:validation.operatorRequired"),
              path: ["conditions", index, "operator"],
            });
          }
        });
      }
    }

    // Validate threshold for cost, latency, and errors triggers (not feedback scores)
    const isSimpleThresholdTrigger =
      data.eventType === ALERT_EVENT_TYPE.trace_cost ||
      data.eventType === ALERT_EVENT_TYPE.trace_latency ||
      data.eventType === ALERT_EVENT_TYPE.trace_errors;

    if (isSimpleThresholdTrigger) {
      if (!data.threshold || data.threshold.trim() === "") {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: i18next.t("common:validation.thresholdIsRequired"),
          path: ["threshold"],
        });
      } else {
        const thresholdNum = parseFloat(data.threshold);
        if (isNaN(thresholdNum)) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: i18next.t("common:validation.thresholdMustBeValidNumber"),
            path: ["threshold"],
          });
        }
      }

      if (!data.window || data.window.trim() === "") {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: i18next.t("common:validation.windowIsRequired"),
          path: ["window"],
        });
      }
    }
  });

export const AlertFormSchema = z
  .object({
    name: z
      .string({
        required_error: i18next.t("common:validation.alertNameRequired"),
      })
      .min(1, { message: i18next.t("common:validation.alertNameRequired") }),
    enabled: z.boolean().default(true),
    alertType: z.nativeEnum(ALERT_TYPE).default(ALERT_TYPE.general),
    routingKey: z.string().optional(),
    url: z
      .string({
        required_error: i18next.t("common:validation.endpointUrlRequired"),
      })
      .min(1, { message: i18next.t("common:validation.endpointUrlRequired") })
      .url({ message: i18next.t("common:validation.pleaseEnterValidUrl") }),
    secretToken: z.string().optional(),
    headers: z.array(HeaderSchema).default([]),
    triggers: z.array(TriggerSchema).default([]),
  })
  .refine(
    (data) => {
      return data.triggers.length > 0;
    },
    {
      message: i18next.t("common:validation.atLeastOneTriggerMustBeSelected"),
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
      message: i18next.t("common:validation.routingKeyRequiredForPagerDuty"),
      path: ["routingKey"],
    },
  );

export type AlertFormType = z.infer<typeof AlertFormSchema>;
export type TriggerFormType = z.infer<typeof TriggerSchema>;
export type FeedbackScoreConditionType = z.infer<
  typeof FeedbackScoreConditionSchema
>;
