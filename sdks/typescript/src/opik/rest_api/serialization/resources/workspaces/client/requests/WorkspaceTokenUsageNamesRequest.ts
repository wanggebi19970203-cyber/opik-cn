// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../../../../api/index.js";
import * as core from "../../../../../core/index.js";
import type * as serializers from "../../../../index.js";

export const WorkspaceTokenUsageNamesRequest: core.serialization.Schema<
    serializers.WorkspaceTokenUsageNamesRequest.Raw,
    OpikApi.WorkspaceTokenUsageNamesRequest
> = core.serialization.object({
    projectIds: core.serialization.property(
        "project_ids",
        core.serialization.list(core.serialization.string()).optional(),
    ),
});

export declare namespace WorkspaceTokenUsageNamesRequest {
    export interface Raw {
        project_ids?: string[] | null;
    }
}
