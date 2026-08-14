// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../api/index.js";
import * as core from "../../core/index.js";
import type * as serializers from "../index.js";

export const AutomationRuleEvaluatorTriggerScope: core.serialization.Schema<
    serializers.AutomationRuleEvaluatorTriggerScope.Raw,
    OpikApi.AutomationRuleEvaluatorTriggerScope
> = core.serialization.enum_(["production", "experiment", "both"]);

export declare namespace AutomationRuleEvaluatorTriggerScope {
    export type Raw = "production" | "experiment" | "both";
}
