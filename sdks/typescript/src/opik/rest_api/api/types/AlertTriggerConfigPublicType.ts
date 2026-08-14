// 此文件由 Fern 根据我们的 API 定义自动生成。

export const AlertTriggerConfigPublicType = {
    ScopeProject: "scope:project",
    ThresholdFeedbackScore: "threshold:feedback_score",
    ThresholdCost: "threshold:cost",
    ThresholdLatency: "threshold:latency",
    ThresholdErrors: "threshold:errors",
    FilterGuardrailType: "filter:guardrail_type",
} as const;
export type AlertTriggerConfigPublicType =
    (typeof AlertTriggerConfigPublicType)[keyof typeof AlertTriggerConfigPublicType];
