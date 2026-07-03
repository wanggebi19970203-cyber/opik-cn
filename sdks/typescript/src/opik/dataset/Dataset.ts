import { generateId } from "@/utils/generateId";
import { DatasetItem, DatasetItemData } from "./DatasetItem";
import { DatasetVersion } from "./DatasetVersion";
import { getDatasetItems } from "./getDatasetItems";
import { OpikClient } from "@/client/Client";
import { DatasetItemWrite, DatasetVersionPublic } from "@/rest_api/api";
import { splitIntoBatches } from "@/utils/stream";
import { logger } from "@/utils/logger";
import {
  DatasetItemMissingIdError,
  DatasetVersionNotFoundError,
} from "@/errors";
import {
  JsonItemNotObjectError,
  JsonNotArrayError,
  JsonParseError,
} from "@/errors/common/errors";
import { OpikApiError } from "@/rest_api/errors";
import stringify from "fast-json-stable-stringify";

export interface DatasetData {
  name: string;
  description?: string;
  id?: string;
  projectName?: string;
}

export class Dataset<T extends DatasetItemData = DatasetItemData> {
  public readonly id: string;
  public readonly name: string;
  public readonly description?: string;
  public readonly projectName?: string;

  private idToHash: Map<string, string> = new Map();
  private hashes: Set<string> = new Set();
  private cachedItemsCount: number | undefined;

  /**
   * 创建新 Dataset 实例的配置对象。
   * 不应直接构造，请使用静态工厂方法。
   */
  constructor(
    { name, description, id, projectName }: DatasetData,
    private opik: OpikClient,
  ) {
    this.id = id || generateId();
    this.name = name;
    this.description = description;
    this.projectName = projectName;
  }

  /**
   * 向数据集中插入新条目。
   *
   * @param items 要添加到数据集的对象列表
   */
  public async insert(items: T[]): Promise<void> {
    if (!items || items.length === 0) {
      return;
    }

    await this.opik.datasetBatchQueue.flush();

    await this.syncHashes();

    const reqItems = await this.getDeduplicatedItems(items);

    const batches = splitIntoBatches(reqItems, { maxBatchSize: 1000 });

    const batchGroupId = generateId();

    try {
      let totalInserted = 0;
      for (const batch of batches) {
        await this.opik.api.datasets.createOrUpdateDatasetItems({
          datasetId: this.id,
          items: batch,
          batchGroupId,
          projectName: this.projectName,
        });
        totalInserted += batch.length;
        logger.info(
          `Inserted ${Math.min(totalInserted, reqItems.length)} of ${reqItems.length} items into dataset ${this.id}`,
        );
      }
    } catch (error) {
      logger.error(
        `Error inserting items into dataset: ${error instanceof Error ? error.message : String(error)}`,
      );
      throw error;
    }

    this.cachedItemsCount = undefined;
  }

  /**
   * 更新数据集中已有的条目。
   * 需要提供完整的条目对象，因为它将覆盖之前提供的内容。
   *
   * @param items 要在数据集中更新的对象列表
   */
  public async update(items: T[]): Promise<void> {
    if (!items || items.length === 0) {
      return;
    }

    for (let i = 0; i < items.length; i++) {
      const item = items[i];
      if (!item.id) {
        throw new DatasetItemMissingIdError(i);
      }
    }

    await this.insert(items);
  }

  /**
   * 从数据集中删除条目。
   *
   * @param itemIds 要删除的条目 ID 列表
   */
  public async delete(itemIds: string[]): Promise<void> {
    if (!itemIds || itemIds.length === 0) {
      logger.info("No item IDs provided for deletion");
      return;
    }

    const batches = splitIntoBatches(itemIds, { maxBatchSize: 100 });

    const batchGroupId = generateId();

    for await (const batch of batches) {
      logger.debug("Deleting dataset items batch", {
        batchSize: batch.length,
        datasetId: this.id,
      });
      await this.opik.api.datasets.deleteDatasetItems({
        itemIds: batch,
        batchGroupId,
      });

      for (const itemId of batch) {
        if (this.idToHash.has(itemId)) {
          const hash = this.idToHash.get(itemId)!;
          this.hashes.delete(hash);
          this.idToHash.delete(itemId);
        }
      }
    }

    this.cachedItemsCount = undefined;
  }

  /**
   * 删除数据集中的所有条目。
   */
  public async clear(): Promise<void> {
    const items = await this.getItems();
    const itemIds = items.map((item) => item.id).filter(Boolean) as string[];

    if (itemIds.length === 0) {
      return;
    }

    await this.delete(itemIds);
  }

  /**
   * 获取与该数据集关联的标签。
   *
   * @returns 标签字符串数组
   */
  public async getTags(): Promise<string[]> {
    const datasetInfo = await this.opik.api.datasets.getDatasetByIdentifier({
      datasetName: this.name,
      projectName: this.projectName,
    });
    return datasetInfo.tags ?? [];
  }

  /**
   * 获取该数据集中的条目总数。
   * 结果会被缓存，仅在首次调用或发生变更（插入、删除、清空）后从后端重新获取。
   *
   * @returns 条目数量，如果不可用则返回 undefined
   */
  public async getItemsCount(): Promise<number | undefined> {
    if (this.cachedItemsCount === undefined) {
      const datasetInfo = await this.opik.api.datasets.getDatasetByIdentifier({
        datasetName: this.name,
        projectName: this.projectName,
      });
      this.cachedItemsCount = datasetInfo.datasetItemsCount;
    }
    return this.cachedItemsCount;
  }

  /**
   * 获取指定数量的数据集条目。
   *
   * @param nbSamples 要获取的样本数量。如果未设置，则返回所有条目
   * @param lastRetrievedId 可选的上次最后获取条目的 ID，用于分页
   * @returns 表示数据集条目的对象列表
   */
  public async getItems(nbSamples?: number, lastRetrievedId?: string) {
    const datasetItems = await getDatasetItems<T>(this.opik, {
      datasetName: this.name,
      projectName: this.projectName,
      nbSamples,
      lastRetrievedId,
    });

    return datasetItems.map((item) => item.getContent(true));
  }

  /**
   * 获取保留完整元数据（评估器、执行策略）的原始 DatasetItem 对象。
   *
   * @param nbSamples 要获取的样本数量。如果未设置，则返回所有条目
   * @param lastRetrievedId 可选的上次最后获取条目的 ID，用于分页
   * @returns DatasetItem 对象列表
   */
  public async getRawItems(
    nbSamples?: number,
    lastRetrievedId?: string,
  ): Promise<DatasetItem<T>[]> {
    return getDatasetItems<T>(this.opik, {
      datasetName: this.name,
      projectName: this.projectName,
      nbSamples,
      lastRetrievedId,
    });
  }

  /**
   * 从 JSON 字符串数组中插入条目到数据集。
   *
   * @param jsonArray JSON 字符串，格式为: "[{...}, {...}, {...}]"，其中每个对象将被转换为数据集条目
   * @param keysMapping 可选的字典，用于将 JSON 键映射到数据集条目字段名（例如: {'Expected output': 'expected_output'}）
   * @param ignoreKeys 可选的键数组，在构建数据集条目时将被忽略
   */
  public async insertFromJson(
    jsonArray: string,
    keysMapping: Record<string, string> = {},
    ignoreKeys: string[] = [],
  ): Promise<void> {
    let parsedItems: unknown;

    try {
      parsedItems = JSON.parse(jsonArray);
    } catch (error) {
      throw new JsonParseError(error);
    }

    if (!Array.isArray(parsedItems)) {
      throw new JsonNotArrayError(typeof parsedItems);
    }

    if (parsedItems.length === 0) {
      return;
    }

    for (let i = 0; i < parsedItems.length; i++) {
      const item = parsedItems[i];
      if (typeof item !== "object" || item === null) {
        throw new JsonItemNotObjectError(i, typeof item);
      }
    }

    const transformedItems = parsedItems.map((item) => {
      const typedItem = item as Record<string, unknown>;
      const transformedItem: Record<string, unknown> = {};

      for (const [key, value] of Object.entries(typedItem)) {
        if (ignoreKeys.includes(key)) {
          continue;
        }

        const mappedKey = keysMapping[key] || key;
        transformedItem[mappedKey] = value;
      }

      return transformedItem as T;
    });

    await this.insert(transformedItems);
  }

  /**
   * 将数据集转换为 JSON 字符串。
   *
   * @param keysMapping 可选的字典，用于将数据集条目字段名映射到输出 JSON 键
   * @returns 数据集中所有条目的 JSON 字符串表示
   */
  public async toJson(
    keysMapping: Record<string, string> = {},
  ): Promise<string> {
    const items = await this.getItems();

    const mappedItems: Record<string, unknown>[] = items.map((item) => {
      const itemCopy = { ...item } as Record<string, unknown>;

      for (const [key, value] of Object.entries(keysMapping)) {
        if (key in itemCopy) {
          const content = itemCopy[key];
          delete itemCopy[key];
          itemCopy[value] = content;
        }
      }

      return itemCopy;
    });

    return stringify(mappedItems);
  }

  /**
   * 对传入的条目进行去重处理，返回去重后的数据集写入对象列表。
   *
   * @returns 去重后的数据集条目列表
   */
  private async getDeduplicatedItems(items: T[]): Promise<DatasetItemWrite[]> {
    const deduplicatedItems: DatasetItemWrite[] = [];

    for (const item of items) {
      const datasetItem = new DatasetItem<T>(item);
      const contentHash = await datasetItem.contentHash();

      if (this.hashes.has(contentHash)) {
        logger.debug("Duplicate item found - skipping", {
          contentHash,
          datasetId: this.id,
        });
        continue;
      }

      deduplicatedItems.push(datasetItem.toApiModel());
      this.hashes.add(contentHash);
      this.idToHash.set(datasetItem.id, contentHash);
    }

    return deduplicatedItems;
  }

  /**
   * 清除哈希跟踪的两个数据结构
   */
  private clearHashState(): void {
    this.idToHash.clear();
    this.hashes.clear();
  }

  public async syncHashes(): Promise<void> {
    logger.debug("Syncing dataset hashes with backend", { datasetId: this.id });

    try {
      const allItems = await getDatasetItems<T>(this.opik, {
        datasetName: this.name,
        projectName: this.projectName,
      });

      this.clearHashState();

      for (const item of allItems) {
        const itemHash = await item.contentHash();
        this.idToHash.set(item.id, itemHash);
        this.hashes.add(itemHash);
      }

      logger.debug("Dataset hash sync completed", {
        datasetId: this.id,
        itemCount: allItems.length,
      });
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        logger.debug("Dataset not found - starting with empty hash state", {
          datasetId: this.id,
        });
        this.clearHashState();
        return;
      }
      throw error;
    }
  }

  /**
   * 获取指定数据集版本的只读视图。
   *
   * @param versionName 要获取的版本名称（例如: "v1", "v2"）
   * @returns 指定版本的 DatasetVersion 对象
   * @throws DatasetVersionNotFoundError 如果版本不存在
   */
  public async getVersionView(versionName: string): Promise<DatasetVersion<T>> {
    const versionInfo = await this.findVersionByName(versionName);

    if (!versionInfo) {
      throw new DatasetVersionNotFoundError(versionName, this.name);
    }

    return new DatasetVersion<T>(this.name, this.id, versionInfo, this.opik);
  }

  /**
   * 获取当前（最新）版本名称。
   *
   * @returns 版本名称（例如: "v1"），如果不存在版本则返回 undefined
   */
  public async getCurrentVersionName(): Promise<string | undefined> {
    const versionInfo = await this.getVersionInfo();
    return versionInfo?.versionName;
  }

  /**
   * 获取当前（最新）版本信息。
   *
   * @returns DatasetVersionPublic 对象，如果不存在版本则返回 undefined
   */
  public async getVersionInfo(): Promise<DatasetVersionPublic | undefined> {
    try {
      const response = await this.opik.api.datasets.listDatasetVersions(
        this.id,
        { page: 1, size: 1 },
      );

      const versions = response.content ?? [];
      if (versions.length === 0) {
        return undefined;
      }

      return versions[0];
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        return undefined;
      }
      throw error;
    }
  }

  /**
   * 根据版本名称查找版本（例如: "v1", "v2"）。
   *
   * @param versionName 要查找的版本名称
   * @returns DatasetVersionPublic 对象，如果未找到则返回 undefined
   */
  private async findVersionByName(
    versionName: string,
  ): Promise<DatasetVersionPublic | undefined> {
    try {
      const response = await this.opik.api.datasets.retrieveDatasetVersion(
        this.id,
        {
          versionName,
        },
      );
      return response;
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        return undefined;
      }
      throw error;
    }
  }
}
