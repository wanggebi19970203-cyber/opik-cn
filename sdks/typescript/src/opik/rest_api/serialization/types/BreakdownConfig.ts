// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../api/index.js";
import * as core from "../../core/index.js";
import type * as serializers from "../index.js";
import { BreakdownConfigField } from "./BreakdownConfigField.js";

export const BreakdownConfig: core.serialization.ObjectSchema<
    serializers.BreakdownConfig.Raw,
    OpikApi.BreakdownConfig
> = core.serialization.object({
    field: BreakdownConfigField.optional(),
    metadataKey: core.serialization.property("metadata_key", core.serialization.string().optional()),
    subMetric: core.serialization.property("sub_metric", core.serialization.string().optional()),
});

export declare namespace BreakdownConfig {
    export interface Raw {
        field?: BreakdownConfigField.Raw | null;
        metadata_key?: string | null;
        sub_metric?: string | null;
    }
}
