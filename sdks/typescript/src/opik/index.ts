export { OpikClient as Opik } from "@/client/Client";
export type { FeedbackScoreData } from "@/tracer/types";
export type { OpikConfig } from "@/config/Config";
export {
  isTracingActive,
  setTracingActive,
  resetTracingToConfigDefault,
} from "@/config/TracingRuntimeConfig";
export { getTrackContext, track } from "@/decorators/track";
export { generateId } from "@/utils/generateId";
export { flushAll } from "@/utils/flushAll";
export { disableLogger, logger, setLoggerLevel } from "@/utils/logger";

export type { Span } from "@/tracer/Span";
export type { Trace } from "@/tracer/Trace";
export type { ErrorInfo } from "@/rest_api/api/types/ErrorInfo";
export type { SpanType } from "@/rest_api/api/types/SpanType";
export { SpanType as OpikSpanType } from "@/rest_api/api/types/SpanType";
export type { DatasetPublic } from "@/rest_api/api/types/DatasetPublic";
export type { EnvironmentPublic as Environment } from "@/rest_api/api/types/EnvironmentPublic";
export * from "./evaluation";

// 数据集导出
export { Dataset } from "@/dataset/Dataset";
export { DatasetVersion } from "@/dataset/DatasetVersion";
export { DatasetVersionNotFoundError } from "@/errors/dataset/errors";
export type { DatasetVersionPublic } from "@/rest_api/api/types/DatasetVersionPublic";

export { Prompt, ChatPrompt, PromptType } from "@/prompt";
export { getGlobalClient, setGlobalClient, resetGlobalClient } from "@/client/globalClient";
export { OpikQueryLanguage } from "@/query";
export type { FilterExpression } from "@/query";

export { TracesAnnotationQueue, ThreadsAnnotationQueue } from "@/annotation-queue";
export type { AnnotationQueuePublicScope as AnnotationQueueScope } from "@/rest_api/api/types/AnnotationQueuePublicScope";

// 配置导出
export { agentConfigContext } from "@/agent-config";
export type { Config } from "@/agent-config";
export { ConfigNotFoundError, ConfigMismatchError } from "@/errors/agent-config/errors";

// 运行器导出
export { activateRunner } from "@/runner/activate";
export type { RegistryEntry, Param } from "@/runner/registry";

// 分布式追踪上下文辅助工具
export {
  OPIK_TRACE_ID_HEADER,
  OPIK_PARENT_SPAN_ID_HEADER,
  getDistributedTraceHeaders,
} from "@/context";
export type { DistributedTraceHeaders } from "@/context";


// 重新导出 Zod，确保使用者与 SDK 使用相同版本
export { z } from "zod";
