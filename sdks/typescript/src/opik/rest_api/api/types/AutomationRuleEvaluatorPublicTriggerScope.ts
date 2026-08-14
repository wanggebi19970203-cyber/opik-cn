// 此文件由 Fern 根据我们的 API 定义自动生成。

/** 控制规则是否在生产 trace、实验 trace 或两者上触发。若省略则默认为 'production'。 */
export const AutomationRuleEvaluatorPublicTriggerScope = {
    Production: "production",
    Experiment: "experiment",
    Both: "both",
} as const;
export type AutomationRuleEvaluatorPublicTriggerScope =
    (typeof AutomationRuleEvaluatorPublicTriggerScope)[keyof typeof AutomationRuleEvaluatorPublicTriggerScope];
