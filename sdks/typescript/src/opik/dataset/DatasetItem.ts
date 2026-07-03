import { DatasetItemWriteSource } from "@/rest_api/api";
import { JsonNode } from "@/rest_api/api/types/JsonNode";
import { DatasetItemWrite } from "@/rest_api/api/types/DatasetItemWrite";
import { EvaluatorItemWrite } from "@/rest_api/api/types/EvaluatorItemWrite";
import { ExecutionPolicyWrite } from "@/rest_api/api/types/ExecutionPolicyWrite";
import { generateId } from "@/utils/generateId";
import stringify from "fast-json-stable-stringify";
import { initHashApi } from "@/utils/hash";

export type DatasetItemData = JsonNode & {
  id?: string;
};

/**
 * 表示数据集中条目的 DatasetItem 对象。
 * 格式灵活，允许附加属性。
 *
 * @template T 数据集条目中存储的自定义数据类型
 */
export class DatasetItem<T extends DatasetItemData = DatasetItemData> {
  public readonly id: string;
  public readonly traceId?: string;
  public readonly spanId?: string;
  public readonly source: DatasetItemWriteSource;
  public readonly description?: string;
  public readonly evaluators?: EvaluatorItemWrite[];
  public readonly executionPolicy?: ExecutionPolicyWrite;
  private readonly data: T;

  constructor(
    params: {
      id?: string;
      traceId?: string;
      spanId?: string;
      source?: DatasetItemWriteSource;
      description?: string;
      evaluators?: EvaluatorItemWrite[];
      executionPolicy?: ExecutionPolicyWrite;
    } & T,
    metadataDescription?: string
  ) {
    const { id, traceId, spanId, source, description, evaluators, executionPolicy, ...rest } = params;

    this.id = id || generateId();
    this.traceId = traceId;
    this.spanId = spanId;
    this.source = source || DatasetItemWriteSource.Sdk;
    this.description = description ?? metadataDescription;
    this.evaluators = evaluators;
    this.executionPolicy = executionPolicy;
    this.data = {
      ...rest,
      ...(description !== undefined ? { description } : {}),
    } as T;
  }

  /**
   * 以 JSON 对象形式获取此数据集条目的内容。
   *
   * @param includeId 是否在内容中包含 ID
   * @returns JSON 对象形式的内容，如果为 true 则包含 ID
   */
  public getContent(includeId: true): T & { id: string };
  public getContent(includeId?: false): T;
  public getContent(includeId = false) {
    if (includeId) {
      return { ...this.data, id: this.id };
    }

    return { ...this.data };
  }

  /**
   * 计算条目内容的哈希值用于去重。
   * @returns 解析为内容哈希值的 Promise
   */
  async contentHash(): Promise<string> {
    const content: Record<string, unknown> = { ...this.getContent() };

    if (this.evaluators && this.evaluators.length > 0) {
      content["evaluators"] = this.evaluators;
    }

    if (
      this.executionPolicy &&
      Object.keys(this.executionPolicy).length > 0
    ) {
      content["executionPolicy"] = this.executionPolicy;
    }

    // Use fast-json-stable-stringify for deterministic JSON
    const json = stringify(content);

    // Use xxhash32 with a seed value for hashing
    const hashFn = await initHashApi();
    const hash = hashFn.h32(json, 0xabcd).toString(16);

    return hash;
  }

  /**
   * 将此 DatasetItem 转换为 API 模型格式。
   *
   * @returns 适用于 API 操作的 DatasetItemWrite 对象
   */
  public toApiModel(): DatasetItemWrite {
    return {
      id: this.id,
      traceId: this.traceId,
      spanId: this.spanId,
      source: this.source,
      data: this.getContent(),
      ...(this.description && { description: this.description }),
      ...(this.evaluators && { evaluators: this.evaluators }),
      ...(this.executionPolicy && { executionPolicy: this.executionPolicy }),
    };
  }

  /**
   * 从 API 模型创建 DatasetItem。
   *
   * @param model 要转换的 API 模型
   * @returns 新的 DatasetItem 实例
   */
  public static fromApiModel<T extends DatasetItemData = DatasetItemData>(
    model: DatasetItemWrite
  ): DatasetItem<T> {
    return new DatasetItem<T>(
      {
        id: model.id,
        traceId: model.traceId,
        spanId: model.spanId,
        source: model.source,
        ...(model.evaluators && { evaluators: model.evaluators }),
        ...(model.executionPolicy && { executionPolicy: model.executionPolicy }),
        ...(model.data as T),
      },
      model.description
    );
  }
}
