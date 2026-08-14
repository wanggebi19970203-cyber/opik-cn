// 此文件由 Fern 根据我们的 API 定义自动生成。

export const BreakdownConfigField = {
    None: "none",
    Tags: "tags",
    Metadata: "metadata",
    Name: "name",
    ErrorInfo: "error_info",
    ErrorType: "error_type",
    Model: "model",
    Provider: "provider",
    Type: "type",
    GuardrailName: "guardrail_name",
} as const;
export type BreakdownConfigField = (typeof BreakdownConfigField)[keyof typeof BreakdownConfigField];
