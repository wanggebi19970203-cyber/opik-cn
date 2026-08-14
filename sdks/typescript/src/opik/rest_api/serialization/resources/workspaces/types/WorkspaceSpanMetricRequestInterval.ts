// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../../../api/index.js";
import * as core from "../../../../core/index.js";
import type * as serializers from "../../../index.js";

export const WorkspaceSpanMetricRequestInterval: core.serialization.Schema<
    serializers.WorkspaceSpanMetricRequestInterval.Raw,
    OpikApi.WorkspaceSpanMetricRequestInterval
> = core.serialization.enum_(["HOURLY", "DAILY", "WEEKLY", "TOTAL"]);

export declare namespace WorkspaceSpanMetricRequestInterval {
    export type Raw = "HOURLY" | "DAILY" | "WEEKLY" | "TOTAL";
}
