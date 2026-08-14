// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../../../index.js";

/**
 * @example
 *     {}
 */
export interface OptimizationUpdate {
    name?: string;
    status?: OpikApi.OptimizationUpdateStatus;
    errorInfo?: OpikApi.ErrorInfo;
    metadata?: OpikApi.JsonListString;
}
