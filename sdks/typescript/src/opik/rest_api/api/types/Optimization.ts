// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../index.js";

export interface Optimization {
    id?: string;
    name?: string;
    datasetName: string;
    /** 项目名称。若项目不存在则创建项目。当提供 project_id 时将被忽略。 */
    projectName?: string;
    /** 项目 ID。当同时提供两者时，优先于 project_name。 */
    projectId?: string;
    objectiveName: string;
    status: OpikApi.OptimizationStatus;
    metadata?: OpikApi.JsonListString;
    studioConfig?: OpikApi.OptimizationStudioConfig;
    errorInfo?: OpikApi.ErrorInfo;
    datasetId?: string;
    numTrials?: number;
    feedbackScores?: OpikApi.FeedbackScoreAverage[];
    experimentScores?: OpikApi.FeedbackScoreAverage[];
    createdAt?: Date;
    createdBy?: string;
    lastUpdatedAt?: Date;
    lastUpdatedBy?: string;
    baselineObjectiveScore?: number;
    bestObjectiveScore?: number;
    baselineDuration?: number;
    bestDuration?: number;
    baselineCost?: number;
    bestCost?: number;
    totalOptimizationCost?: number;
}
