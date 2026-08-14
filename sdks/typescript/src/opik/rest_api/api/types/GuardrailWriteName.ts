// 此文件由 Fern 根据我们的 API 定义自动生成。

export const GuardrailWriteName = {
    Topic: "TOPIC",
    Pii: "PII",
    LlmJudge: "LLM_JUDGE",
    PromptInjection: "PROMPT_INJECTION",
    CustomClassifier: "CUSTOM_CLASSIFIER",
} as const;
export type GuardrailWriteName = (typeof GuardrailWriteName)[keyof typeof GuardrailWriteName];
