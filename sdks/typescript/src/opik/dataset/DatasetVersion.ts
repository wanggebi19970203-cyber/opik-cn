import { DatasetItemData } from "./DatasetItem";
import { getDatasetItems } from "./getDatasetItems";
import { OpikClient } from "@/client/Client";
import { DatasetVersionPublic } from "@/rest_api/api";
import stringify from "fast-json-stable-stringify";

/**
 * 特定数据集版本的只读视图。
 * 提供对特定版本时存在的数据集条目的访问。
 *
 * @template T 数据集条目中存储的自定义数据类型
 */
export class DatasetVersion<T extends DatasetItemData = DatasetItemData> {
  public readonly datasetName: string;
  public readonly datasetId: string;
  private readonly versionInfo: DatasetVersionPublic;
  private readonly opik: OpikClient;

  constructor(
    datasetName: string,
    datasetId: string,
    versionInfo: DatasetVersionPublic,
    opik: OpikClient
  ) {
    this.datasetName = datasetName;
    this.datasetId = datasetId;
    this.versionInfo = versionInfo;
    this.opik = opik;
  }

  /**
   * datasetName 的别名（与 Dataset 接口兼容）。
   */
  get name(): string {
    return this.datasetName;
  }

  /**
   * datasetId 的别名（与 Dataset 接口兼容）。
   */
  get id(): string {
    return this.datasetId;
  }

  /**
   * 获取版本 ID。
   */
  get versionId(): string | undefined {
    return this.versionInfo.id;
  }

  /**
   * 获取版本哈希。
   */
  get versionHash(): string | undefined {
    return this.versionInfo.versionHash;
  }

  /**
   * 获取版本名称（如 "v1"、"v2"）。
   */
  get versionName(): string | undefined {
    return this.versionInfo.versionName;
  }

  /**
   * 获取与此版本关联的标签。
   */
  get tags(): string[] | undefined {
    return this.versionInfo.tags;
  }

  /**
   * 指示是否为最新版本。
   */
  get isLatest(): boolean | undefined {
    return this.versionInfo.isLatest;
  }

  /**
   * 获取此版本中的条目总数。
   */
  get itemsTotal(): number | undefined {
    return this.versionInfo.itemsTotal;
  }

  /**
   * 获取自上一版本以来添加的条目数量。
   */
  get itemsAdded(): number | undefined {
    return this.versionInfo.itemsAdded;
  }

  /**
   * 获取自上一版本以来修改的条目数量。
   */
  get itemsModified(): number | undefined {
    return this.versionInfo.itemsModified;
  }

  /**
   * 获取自上一版本以来删除的条目数量。
   */
  get itemsDeleted(): number | undefined {
    return this.versionInfo.itemsDeleted;
  }

  /**
   * 获取此版本的变更描述。
   */
  get changeDescription(): string | undefined {
    return this.versionInfo.changeDescription;
  }

  /**
   * 获取创建时间戳。
   */
  get createdAt(): Date | undefined {
    return this.versionInfo.createdAt;
  }

  /**
   * 获取此版本的创建者。
   */
  get createdBy(): string | undefined {
    return this.versionInfo.createdBy;
  }

  /**
   * 返回完整的版本信息对象。
   */
  getVersionInfo(): DatasetVersionPublic {
    return this.versionInfo;
  }

  /**
   * 从此版本中检索固定数量的数据集条目。
   *
   * @param nbSamples 要检索的样本数量。如果未设置 - 返回所有条目
   * @param lastRetrievedId 可选的上次最后获取条目的 ID，用于分页
   * @returns 表示数据集条目的对象列表
   */
  public async getItems(
    nbSamples?: number,
    lastRetrievedId?: string
  ): Promise<(T & { id: string })[]> {
    const datasetItems = await getDatasetItems<T>(this.opik, {
      datasetName: this.datasetName,
      datasetVersion: this.versionInfo.versionHash,
      nbSamples,
      lastRetrievedId,
    });
    return datasetItems.map((item) => item.getContent(true));
  }

  /**
   * 将数据集版本条目转换为 JSON 字符串。
   *
   * @param keysMapping 可选的字典，用于将数据集条目字段名映射到输出 JSON 键
   * @returns 此版本中所有条目的 JSON 字符串表示
   */
  public async toJson(
    keysMapping: Record<string, string> = {}
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
}
