import { OpikClient } from "@/client/Client";
import { isTracingActive } from "@/config/TracingRuntimeConfig";
import type { Span as ISpan, Trace as ITrace } from "@/rest_api/api";
import { generateId } from "@/utils/generateId";
import { logger } from "@/utils/logger";
import { SavedSpan, Span } from "./Span";
import type { TraceUpdateData } from "./types";
import { UpdateService } from "./UpdateService";

export interface SavedTrace extends ITrace {
  id: string;
}

interface SpanData extends Omit<ISpan, "startTime" | "traceId" | "environment"> {
  startTime?: Date;
}

/**
 * Trace 类用于管理分布式追踪的根节点
 * 负责创建和管理 Span、记录评分、更新追踪数据
 */
export class Trace {
  private spans: Span[] = [];

  /**
   * 构造函数
   * @param data - 已保存的追踪数据
   * @param opik - Opik 客户端实例
   */
  constructor(
    public data: SavedTrace,
    private opik: OpikClient
  ) {}

  /**
   * 结束当前追踪，设置结束时间
   * @returns 更新后的 Trace 实例
   */
  public end = () => {
    return this.update({ endTime: new Date() });
  };

  /**
   * 为当前追踪添加评分
   * @param score - 评分对象，包含名称、分类、值和原因
   */
  public score = (score: {
    name: string;
    categoryName?: string;
    value: number;
    reason?: string;
  }) => {
    if (!isTracingActive()) {
      return;
    }

    this.opik.traceFeedbackScoresBatchQueue.create({
      ...score,
      projectName: this.data.projectName ?? this.opik.config.projectName,
      id: this.data.id,
      source: "sdk",
    });
  };

  /**
   * 创建一个新的 Span（跨度）作为当前追踪的子级
   * @param spanData - Span 数据，包含名称、输入输出等信息
   * @returns 新创建的 Span 实例
   */
  public span = (spanData: SpanData) => {
    const projectName =
      this.data.projectName ??
      spanData.projectName ??
      this.opik.config.projectName;

    // 环境变量作用域限定在 Trace 级别；移除调用方提供的任何值，防止 JavaScript 或其他调用方覆盖它
    // 父级 Trace 的环境变量将在下方无条件应用
    const { environment: _env, ...spanDataWithoutEnv } = spanData as { environment?: string };
    if (_env !== undefined && _env !== this.data.environment) {
      logger.warn(
        `您正在尝试将数据记录到环境 "${_env}" 下的嵌套 Span 中。` +
          `但是，将使用父级 Trace 的环境 "${this.data.environment ?? ""}" 替代。`
      );
    }
    const spanWithId: SavedSpan = {
      id: generateId(),
      startTime: new Date(),
      source: this.data.source,
      ...spanDataWithoutEnv,
      projectName,
      traceId: this.data.id,
      ...(this.data.environment !== undefined
        ? { environment: this.data.environment }
        : {}),
    };

    if (isTracingActive()) {
      this.opik.spanBatchQueue.create(spanWithId);
    }

    const span = new Span(spanWithId, this.opik);
    this.spans.push(span);
    return span;
  };

  /**
   * 更新当前追踪的数据
   * @param updates - 要更新的追踪数据
   * @returns 更新后的 Trace 实例
   */
  public update = (updates: TraceUpdateData) => {
    const processedUpdates = UpdateService.processTraceUpdate(
      updates,
      this.data.metadata
    );

    const traceUpdates = {
      projectName: this.data.projectName ?? this.opik.config.projectName,
      ...processedUpdates,
    };

    if (isTracingActive()) {
      this.opik.traceBatchQueue.update(this.data.id, traceUpdates);
    }
    this.data = { ...this.data, ...traceUpdates };

    return this;
  };
}
