import {
  FeedbackScore,
  JsonListStringCompare,
  ExperimentItemCompare,
} from "@/rest_api/api";

/**
 * 实验中数据集条目和追踪的引用。
 */
export class ExperimentItemReferences {
  public readonly datasetItemId: string;
  public readonly traceId: string;
  public readonly projectName?: string;

  constructor(params: {
    datasetItemId: string;
    traceId: string;
    projectName?: string;
  }) {
    if (!params.datasetItemId) {
      throw new Error("datasetItemId is required");
    }
    if (!params.traceId) {
      throw new Error("traceId is required");
    }
    this.datasetItemId = params.datasetItemId;
    this.traceId = params.traceId;
    this.projectName = params.projectName;
  }
}

/**
 * 实验条目的内容，包括评估数据和反馈分数。
 */
export class ExperimentItemContent {
  public readonly id?: string;
  public readonly datasetItemId: string;
  public readonly traceId: string;
  public readonly datasetItemData?: JsonListStringCompare;
  public readonly evaluationTaskOutput?: JsonListStringCompare;
  public readonly feedbackScores: FeedbackScore[];

  constructor(params: {
    id?: string;
    datasetItemId: string;
    traceId: string;
    datasetItemData?: JsonListStringCompare;
    evaluationTaskOutput?: JsonListStringCompare;
    feedbackScores: FeedbackScore[];
  }) {
    this.id = params.id;
    this.datasetItemId = params.datasetItemId;
    this.traceId = params.traceId;
    this.datasetItemData = params.datasetItemData;
    this.evaluationTaskOutput = params.evaluationTaskOutput;
    this.feedbackScores = [...params.feedbackScores];
  }

  /**
   * 从 REST API ExperimentItemCompare 对象创建 ExperimentItemContent。
   *
   * @param value REST API ExperimentItemCompare 对象
   * @returns 新的 ExperimentItemContent 实例
   */
  public static fromRestExperimentItemCompare(
    value: ExperimentItemCompare
  ): ExperimentItemContent {
    const feedbackScores: FeedbackScore[] =
      value.feedbackScores?.map((restFeedbackScore) => ({
        categoryName: restFeedbackScore.categoryName,
        name: restFeedbackScore.name,
        reason: restFeedbackScore.reason,
        value: restFeedbackScore.value,
        source: restFeedbackScore.source,
      })) ?? [];

    return new ExperimentItemContent({
      id: value.id,
      traceId: value.traceId,
      datasetItemId: value.datasetItemId,
      datasetItemData: value.input,
      evaluationTaskOutput: value.output,
      feedbackScores: feedbackScores,
    });
  }
}
