import { generateId } from "@/utils/generateId";
import { OpikClient } from "@/client/Client";
import { ExperimentItem } from "@/rest_api/api/types/ExperimentItem";
import {
  ExperimentItemContent,
  ExperimentItemReferences,
} from "./ExperimentItem";
import { parseNdjsonStreamToArray, splitIntoBatches } from "@/utils/stream";
import { ExperimentItemCompare } from "@/rest_api/api/types/ExperimentItemCompare";
import { logger } from "@/utils/logger";
import { serialization } from "@/rest_api";
import { getExperimentUrlById } from "@/utils/url";
import { DEFAULT_CONFIG } from "@/config/Config";
import type { Prompt } from "@/prompt/Prompt";

export interface ExperimentData {
  id?: string;
  name?: string;
  datasetName?: string;
  prompts?: Prompt[];
  tags?: string[];
  projectName?: string;
}

/**
 * 表示 Opik 中的实验，关联追踪和数据集条目
 */
export class Experiment {
  public readonly id: string;
  private _name?: string;
  public readonly datasetName?: string;
  public readonly prompts?: Prompt[];
  public readonly tags?: string[];
  public readonly projectName?: string;

  /**
   * 创建新的实验实例。
   * 不应直接构造，请使用静态工厂方法。
   */
  constructor(
    { id, name, datasetName, prompts, tags, projectName }: ExperimentData,
    private opik: OpikClient
  ) {
    this.id = id || generateId();
    this._name = name;
    this.datasetName = datasetName;
    this.prompts = prompts;
    this.tags = tags;
    this.projectName = projectName;
  }

  /**
   * 获取实验名称。如果在构造时未提供，
   * 则从后端 API 延迟加载。
   */
  get name(): string | undefined {
    return this._name;
  }

  /**
   * 异步方法，确保在需要时从后端加载名称。
   * 在访问名称之前调用此方法以确保已加载。
   */
  async ensureNameLoaded(): Promise<string> {
    if (this._name !== undefined) {
      return this._name;
    }

    const experimentData = await this.opik.api.experiments.getExperimentById(
      this.id
    );
    this._name = experimentData.name;

    if (!this._name) {
      throw new Error("Experiment name is not loaded");
    }

    return this._name;
  }

  /**
   * 通过关联现有追踪和数据集条目创建新的实验条目
   *
   * @param experimentItemReferences 关联追踪与数据集条目的引用列表
   */
  public async insert(
    experimentItemReferences: ExperimentItemReferences[]
  ): Promise<void> {
    if (experimentItemReferences.length === 0) {
      return;
    }

    const restExperimentItems: ExperimentItem[] = experimentItemReferences.map(
      (item) => ({
        id: generateId(),
        experimentId: this.id,
        datasetItemId: item.datasetItemId,
        traceId: item.traceId,
        projectName: item.projectName,
      })
    );

    const batches = splitIntoBatches(restExperimentItems, { maxBatchSize: 50 });

    try {
      for (const batch of batches) {
        await this.opik.api.experiments.createExperimentItems({
          experimentItems: batch,
        });
      }
      logger.debug(
        `Inserted ${experimentItemReferences.length} items into experiment ${this.id}`
      );
    } catch (error) {
      logger.error(
        `Error inserting items into experiment: ${error instanceof Error ? error.message : String(error)}`
      );
      throw error;
    }
  }

  /**
   * 检索实验条目，支持限制结果数量和截断数据的选项
   *
   * @param options 检索条目的选项
   * @returns 解析为实验条目列表的 Promise
   */
  public async getItems(options?: {
    maxResults?: number;
    truncate?: boolean;
  }): Promise<ExperimentItemContent[]> {
    const result: ExperimentItemContent[] = [];
    const maxEndpointBatchSize = 2000; // 每次 API 请求的最大批次大小
    const { maxResults, truncate = false } = options || {};

    let lastRetrievedId: string | undefined;
    let shouldContinuePagination = true;

    try {
      while (shouldContinuePagination) {
        if (maxResults !== undefined && result.length >= maxResults) {
          break;
        }

        const currentBatchSize = maxResults
          ? Math.min(maxResults - result.length, maxEndpointBatchSize)
          : maxEndpointBatchSize;

        const itemsStream =
          await this.opik.api.experiments.streamExperimentItems({
            experimentName: this.name!,
            limit: currentBatchSize,
            lastRetrievedId: lastRetrievedId,
            truncate,
          });

        try {
          const experimentItemsCurrentBatch =
            await parseNdjsonStreamToArray<ExperimentItemCompare>(
              itemsStream,
              serialization.ExperimentItemCompare,
              currentBatchSize
            );

          if (experimentItemsCurrentBatch.length === 0) {
            shouldContinuePagination = false;
            break;
          }

          for (const item of experimentItemsCurrentBatch) {
            const convertedItem =
              ExperimentItemContent.fromRestExperimentItemCompare(item);
            result.push(convertedItem);

            if (maxResults !== undefined && result.length >= maxResults) {
              shouldContinuePagination = false;
              break;
            }
          }

          lastRetrievedId =
            experimentItemsCurrentBatch[experimentItemsCurrentBatch.length - 1]
              .id;
        } catch (error) {
          logger.error(
            "Error parsing experiment item: " +
              (error instanceof Error ? error.message : String(error))
          );
          shouldContinuePagination = false;
          break;
        }
      }
    } catch (error) {
      logger.error(
        "Error retrieving experiment items: " +
          (error instanceof Error ? error.message : String(error))
      );
      throw error;
    }

    logger.info(
      `Retrieved ${result.length} items${maxResults ? ` (limited by maxResults=${maxResults})` : ``}`
    );

    return result;
  }

  async getUrl(): Promise<string> {
    if (!this.datasetName) {
      throw new Error(
        "Cannot get URL: the associated dataset has been deleted or is unavailable"
      );
    }

    const dataset = await this.opik.getDataset(this.datasetName, this.projectName);
    const baseUrl = this.opik.config.apiUrl || DEFAULT_CONFIG.apiUrl;

    return getExperimentUrlById({
      datasetId: dataset.id,
      experimentId: this.id,
      baseUrl,
    });
  }
}
