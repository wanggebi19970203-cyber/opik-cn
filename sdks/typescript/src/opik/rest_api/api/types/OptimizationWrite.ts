// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../index.js";

export interface OptimizationWrite {
    id?: string;
    name?: string;
    datasetName: string;
    /** 项目名称。若项目不存在则创建项目。当提供 project_id 时将被忽略。 */
    projectName?: string;
    /** 项目 ID。当同时提供两者时，优先于 project_name。 */
    projectId?: string;
    objectiveName: string;
    status: OpikApi.OptimizationWriteStatus;
    metadata?: OpikApi.JsonListStringWrite;
    studioConfig?: OpikApi.OptimizationStudioConfigWrite;
    errorInfo?: OpikApi.ErrorInfoWrite;
    lastUpdatedAt?: Date;
}
