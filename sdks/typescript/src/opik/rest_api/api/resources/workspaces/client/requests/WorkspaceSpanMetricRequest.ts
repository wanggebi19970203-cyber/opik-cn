// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../../../index.js";

/**
 * @example
 *     {
 *         intervalStart: new Date("2024-01-15T09:30:00.000Z")
 *     }
 */
export interface WorkspaceSpanMetricRequest {
    projectIds?: string[];
    metricType?: OpikApi.WorkspaceSpanMetricRequestMetricType;
    interval?: OpikApi.WorkspaceSpanMetricRequestInterval;
    breakdown?: OpikApi.BreakdownConfig;
    filters?: OpikApi.SpanFilter[];
    intervalStart: Date;
    intervalEnd?: Date;
    startBeforeEnd?: boolean;
}
