// 此文件由 Fern 根据我们的 API 定义自动生成。

import type * as OpikApi from "../index.js";

export interface OptimizationPublic {
    id?: string;
    name?: string;
    datasetName: string;
    /** 项目 ID。当同时提供两者时，优先于 project_name。 */
    projectId?: string;
    objectiveName: string;
    status: OpikApi.OptimizationPublicStatus;
    metadata?: OpikApi.JsonListStringPublic;
    studioConfig?: OpikApi.OptimizationStudioConfigPublic;
    errorInfo?: OpikApi.ErrorInfoPublic;
    datasetId?: string;
    numTrials?: number;
    feedbackScores?: OpikApi.FeedbackScoreAveragePublic[];
    experimentScores?: OpikApi.FeedbackScoreAveragePublic[];
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
