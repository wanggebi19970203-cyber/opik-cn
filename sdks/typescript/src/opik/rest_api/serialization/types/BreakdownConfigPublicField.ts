// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../api/index.js";
import * as core from "../../core/index.js";
import type * as serializers from "../index.js";

export const BreakdownConfigPublicField: core.serialization.Schema<
    serializers.BreakdownConfigPublicField.Raw,
    OpikApi.BreakdownConfigPublicField
> = core.serialization.enum_([
    "none",
    "tags",
    "metadata",
    "name",
    "error_info",
    "error_type",
    "model",
    "provider",
    "type",
    "guardrail_name",
]);

export declare namespace BreakdownConfigPublicField {
    export type Raw =
        | "none"
        | "tags"
        | "metadata"
        | "name"
        | "error_info"
        | "error_type"
        | "model"
        | "provider"
        | "type"
        | "guardrail_name";
}
