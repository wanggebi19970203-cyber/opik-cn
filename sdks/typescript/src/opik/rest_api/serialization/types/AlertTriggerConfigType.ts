// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../api/index.js";
import * as core from "../../core/index.js";
import type * as serializers from "../index.js";

export const AlertTriggerConfigType: core.serialization.Schema<
    serializers.AlertTriggerConfigType.Raw,
    OpikApi.AlertTriggerConfigType
> = core.serialization.enum_([
    "scope:project",
    "threshold:feedback_score",
    "threshold:cost",
    "threshold:latency",
    "threshold:errors",
    "filter:guardrail_type",
]);

export declare namespace AlertTriggerConfigType {
    export type Raw =
        | "scope:project"
        | "threshold:feedback_score"
        | "threshold:cost"
        | "threshold:latency"
        | "threshold:errors"
        | "filter:guardrail_type";
}
