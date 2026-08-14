// 此文件由 Fern 根据我们的 API 定义自动生成。

export const AutomationRuleEvaluatorUpdateTriggerScope = {
    Production: "production",
    Experiment: "experiment",
    Both: "both",
} as const;
export type AutomationRuleEvaluatorUpdateTriggerScope =
    (typeof AutomationRuleEvaluatorUpdateTriggerScope)[keyof typeof AutomationRuleEvaluatorUpdateTriggerScope];
