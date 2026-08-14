// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../../../../../api/index.js";
import * as core from "../../../../../core/index.js";
import type * as serializers from "../../../../index.js";

export const BatchDeleteByProject: core.serialization.Schema<
    serializers.BatchDeleteByProject.Raw,
    OpikApi.BatchDeleteByProject
> = core.serialization.object({
    ids: core.serialization.list(core.serialization.string()),
    projectId: core.serialization.property("project_id", core.serialization.string().optional()),
});

export declare namespace BatchDeleteByProject {
    export interface Raw {
        ids: string[];
        project_id?: string | null;
    }
}
