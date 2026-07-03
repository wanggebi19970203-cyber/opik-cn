import { getTrackContext } from "@/decorators/track";

/**
 * 在服务边界之间传递 Opik 分布式追踪上下文的 HTTP 头部键。
 * 它们故意使用小写形式，以匹配 `Headers#get` 返回的规范形式
 * 以及 `node:http` 暴露传入头部的方式。
 */
export const OPIK_TRACE_ID_HEADER = "opik_trace_id";
export const OPIK_PARENT_SPAN_ID_HEADER = "opik_parent_span_id";

export interface DistributedTraceHeaders {
  [OPIK_TRACE_ID_HEADER]: string;
  [OPIK_PARENT_SPAN_ID_HEADER]: string;
}

/**
 * 返回描述当前活动追踪和跨度的 Opik 分布式追踪 HTTP 头部。
 * 旨在从用 `track()` 包装的函数内部（或在 `trackStorage` 上下文中运行的任何代码）调用；
 * 在活动追踪上下文外部调用时返回 `null`。
 */
export function getDistributedTraceHeaders(): DistributedTraceHeaders | null {
  const ctx = getTrackContext();
  if (!ctx) {
    return null;
  }
  return {
    [OPIK_TRACE_ID_HEADER]: ctx.trace.data.id,
    [OPIK_PARENT_SPAN_ID_HEADER]: ctx.span.data.id,
  };
}
