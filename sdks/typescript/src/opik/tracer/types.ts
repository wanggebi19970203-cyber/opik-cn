import type { BasePrompt } from "@/prompt/BasePrompt";
import type * as OpikApi from "@/rest_api/api";
import type { FeedbackScoreBatchItem } from "@/rest_api/api/types/FeedbackScoreBatchItem";

/**
 * 用于批量操作的反馈分数。
 *
 * 源自 API 的 FeedbackScoreBatchItem，排除了由 SDK 管理的内部字段（source、projectId、author）。
 * 与 `logTracesFeedbackScores` 和 `logSpansFeedbackScores` 配合使用。
 * 与 Python SDK 的 `BatchFeedbackScoreDict` 匹配。
 *
 * @property id - 要附加分数的追踪或跨度 ID
 * @property name - 反馈指标的名称（如 "accuracy"、"helpfulness"）
 * @property value - 数值分数值
 * @property categoryName - 分数的可选类别
 * @property reason - 分数的可选说明
 * @property projectName - 可选的项目名称（默认使用客户端的项目）
 */
export type FeedbackScoreData = Omit<
  FeedbackScoreBatchItem,
  "source" | "projectId" | "author"
>;

/**
 * 用于序列化的提示词信息字典格式。
 * 与 Python SDK 的提示词元数据存储格式匹配。
 */
export interface PromptInfoDict {
  name: string;
  id?: string;
  template_structure?: string;
  version: {
    id?: string;
    commit?: string;
    template: unknown;
  };
}

/**
 * 扩展的 TraceUpdate 类型，包含 prompts 字段。
 * 允许将提示词版本与追踪更新关联。
 */
export interface TraceUpdateData
  extends Omit<OpikApi.TraceUpdate, "projectId"> {
  prompts?: BasePrompt[];
  appendPrompts?: boolean;
}

/**
 * 扩展的 SpanUpdate 类型，包含 prompts 字段。
 * 允许将提示词版本与跨度更新关联。
 */
export interface SpanUpdateData
  extends Omit<
    OpikApi.SpanUpdate,
    "traceId" | "parentSpanId" | "projectId" | "projectName"
  > {
  prompts?: BasePrompt[];
  appendPrompts?: boolean;
}
