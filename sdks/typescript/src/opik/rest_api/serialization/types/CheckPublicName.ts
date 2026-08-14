// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../api/index.js";
import * as core from "../../core/index.js";
import type * as serializers from "../index.js";

export const CheckPublicName: core.serialization.Schema<serializers.CheckPublicName.Raw, OpikApi.CheckPublicName> =
    core.serialization.enum_(["TOPIC", "PII", "LLM_JUDGE", "PROMPT_INJECTION", "CUSTOM_CLASSIFIER"]);

export declare namespace CheckPublicName {
    export type Raw = "TOPIC" | "PII" | "LLM_JUDGE" | "PROMPT_INJECTION" | "CUSTOM_CLASSIFIER";
}
