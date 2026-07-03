import { track } from "@/decorators/track";
import { EvaluationScoreResult } from "../types";
import { SpanType } from "@/rest_api/api";
import { z } from "zod";

// 使用 ZodObject 和 ZodRawShape 以兼容 Zod 3 和 Zod 4
// 在 Zod 4 中，AnyZodObject 已被 ZodObject 替代
export abstract class BaseMetric<
  T extends z.ZodObject<z.ZodRawShape> = z.ZodObject<z.ZodRawShape>
> {
  /**
   * 指标的名称
   * The name of the metric
   */
  public readonly name: string;

  /**
   * 是否应该追踪此指标
   * Whether this metric should be tracked
   */
  public readonly trackMetric: boolean;

  /**
   * 用于验证 score 方法输入参数的 Zod 模式
   * Zod schema for validating input parameters to the score method
   */
  public abstract readonly validationSchema: T;

  protected constructor(name: string, trackMetric = true) {
    this.name = name;
    this.trackMetric = trackMetric;

    if (trackMetric) {
      const originalScore = this.score.bind(this);
      this.score = track(
        { name: this.name, type: SpanType.General },
        originalScore
      );
    }
  }

  /**
   * 使用经过验证的输入计算分数
   * Compute the score using validated input
   * @param input - 从模式推断的经过验证的输入类型
   * @param input - The validated input of type inferred from the schema
   */
  abstract score(
    input: unknown
  ):
    | EvaluationScoreResult
    | EvaluationScoreResult[]
    | Promise<EvaluationScoreResult>
    | Promise<EvaluationScoreResult[]>;
}
