// 此文件由 Fern 根据我们的 API 定义自动生成。

export const WorkspaceSpanMetricRequestInterval = {
    Hourly: "HOURLY",
    Daily: "DAILY",
    Weekly: "WEEKLY",
    Total: "TOTAL",
} as const;
export type WorkspaceSpanMetricRequestInterval =
    (typeof WorkspaceSpanMetricRequestInterval)[keyof typeof WorkspaceSpanMetricRequestInterval];
