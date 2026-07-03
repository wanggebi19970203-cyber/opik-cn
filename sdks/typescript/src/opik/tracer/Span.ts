import { OpikClient } from "@/client/Client";
import { isTracingActive } from "@/config/TracingRuntimeConfig";
import type { Span as ISpan } from "@/rest_api/api";
import { generateId } from "@/utils/generateId";
import { logger } from "@/utils/logger";
import type { SpanUpdateData } from "./types";
import { UpdateService } from "./UpdateService";

export interface SavedSpan extends ISpan {
  id: string;
}

export class Span {
  private childSpans: Span[] = [];

  constructor(
    public data: SavedSpan,
    private opik: OpikClient
  ) {}

  public end = () => {
    return this.update({ endTime: new Date() });
  };

  public score = (score: {
    name: string;
    categoryName?: string;
    value: number;
    reason?: string;
  }) => {
    if (!isTracingActive()) {
      return;
    }

    this.opik.spanFeedbackScoresBatchQueue.create({
      ...score,
      projectName: this.data.projectName ?? this.opik.config.projectName,
      id: this.data.id,
      source: "sdk",
    });
  };

  public update = (updates: SpanUpdateData) => {
    const processedUpdates = UpdateService.processSpanUpdate(
      updates,
      this.data.metadata
    );

    const spanUpdates = {
      parentSpanId: this.data.parentSpanId,
      projectName: this.data.projectName ?? this.opik.config.projectName,
      traceId: this.data.traceId,
      ...processedUpdates,
    };

    if (isTracingActive()) {
      this.opik.spanBatchQueue.update(this.data.id, spanUpdates);
    }

    this.data = { ...this.data, ...spanUpdates };

    return this;
  };

  public span = (
    spanData: Omit<
      ISpan,
      | "startTime"
      | "traceId"
      | "parentSpanId"
      | "projectId"
      | "projectName"
      | "id"
      | "environment"
    > & {
      startTime?: Date;
    }
  ) => {
    const projectName = this.data.projectName ?? this.opik.config.projectName;

    // 环境变量(environment)的作用域限定在追踪(trace)级别；移除调用者提供的值，防止JS/任何调用者覆盖它
    // 父级span的环境变量将在下方被无条件应用。
    const { environment: _env, ...spanDataWithoutEnv } = spanData as { environment?: string };
    if (_env !== undefined && _env !== this.data.environment) {
      logger.warn(
        `您正在尝试将数据记录到环境 "${_env}" 下的嵌套跨度中。` +
          `但是，将使用父级跨度的环境 "${this.data.environment ?? ""}" 替代。`
      );
    }
    const spanWithId: SavedSpan = {
      id: generateId(),
      startTime: new Date(),
      source: this.data.source,
      ...spanDataWithoutEnv,
      projectName,
      traceId: this.data.traceId,
      parentSpanId: this.data.id,
      ...(this.data.environment !== undefined
        ? { environment: this.data.environment }
        : {}),
    };

    if (isTracingActive()) {
      this.opik.spanBatchQueue.create(spanWithId);
    }

    const span = new Span(spanWithId, this.opik);
    this.childSpans.push(span);

    return span;
  };
}
