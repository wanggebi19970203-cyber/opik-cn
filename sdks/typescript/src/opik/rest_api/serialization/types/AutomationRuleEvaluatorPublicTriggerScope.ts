// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../api/index.js";
import * as core from "../../core/index.js";
import type * as serializers from "../index.js";

export const AutomationRuleEvaluatorPublicTriggerScope: core.serialization.Schema<
    serializers.AutomationRuleEvaluatorPublicTriggerScope.Raw,
    OpikApi.AutomationRuleEvaluatorPublicTriggerScope
> = core.serialization.enum_(["production", "experiment", "both"]);

export declare namespace AutomationRuleEvaluatorPublicTriggerScope {
    export type Raw = "production" | "experiment" | "both";
}
