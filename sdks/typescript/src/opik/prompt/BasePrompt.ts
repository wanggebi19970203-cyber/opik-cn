import type { OpikClient } from "@/client/Client";
import { getGlobalClient } from "@/client/globalClient";
import type { PromptType, PromptTemplateStructure } from "./types";
import type * as OpikApi from "@/rest_api/api";
import { PromptVersion } from "./PromptVersion";
import { logger } from "@/utils/logger";

export const PROMPT_SYNC_TIMEOUT_MS = 5000;

/**
 * 所有提示词类型的基础数据接口
 */
export interface BasePromptData {
  promptId?: string;
  versionId?: string;
  name: string;
  commit?: string;
  version?: string;
  metadata?: OpikApi.JsonNode;
  type?: PromptType;
  changeDescription?: string;
  description?: string;
  tags?: string[];
  templateStructure?: PromptTemplateStructure;
  synced?: boolean;
  projectName?: string;
  /** 拥有此提示词版本的可选环境。 */
  environments?: string[];
}

/**
 * 所有提示词类型的抽象基类。
 * 提供版本控制、属性更新和删除的通用功能。
 */
export abstract class BasePrompt {
  private _id: string | undefined;
  private _versionId: string | undefined;
  private _commit: string | undefined;
  private _version: string | undefined;
  private _synced: boolean;
  private _changeDescription: string | undefined;

  private _projectName: string | undefined;
  private _environments: string[];

  public readonly type: PromptType;
  public readonly templateStructure: PromptTemplateStructure;

  // Mutable fields (can be updated via updateProperties)
  protected _name: string;
  protected _description: string | undefined;
  protected _tags: string[];

  protected readonly _metadata: OpikApi.JsonNode | undefined;
  protected readonly opik: OpikClient;

  /** 待处理的后台同步 Promise，在未设置 synced:true 时构造。 */
  protected _pendingSync: Promise<void> | null = null;

  get id(): string | undefined { return this._id; }
  get versionId(): string | undefined { return this._versionId; }
  get commit(): string | undefined { return this._commit; }
  get version(): string | undefined { return this._version; }
  /** 提示词是否已成功与后端同步。 */
  get synced(): boolean { return this._synced; }
  get changeDescription(): string | undefined { return this._changeDescription; }
  get projectName(): string | undefined { return this._projectName; }
  /**
   * 拥有此提示词版本的环境。如果版本未关联任何环境，则返回空数组。
   */
  get environments(): readonly string[] { return Object.freeze([...this._environments]); }


  constructor(data: BasePromptData, opik?: OpikClient) {
    this._id = data.promptId;
    this._versionId = data.versionId;
    this._commit = data.commit;
    this._version = data.version;
    this.type = data.type ?? "mustache";
    this._changeDescription = data.changeDescription;
    this.templateStructure = data.templateStructure ?? "text";
    this._synced = data.synced ?? false;
    this._name = data.name;
    this._description = data.description;
    this._tags = data.tags ? [...data.tags] : [];
    this._metadata = data.metadata;
    this.opik = opik ?? getGlobalClient();
    this._projectName = data.projectName;
    this._environments = data.environments ? [...data.environments] : [];
  }

  /**
   * 成功后台同步后更新内部状态。
   */
  protected updateSyncState(result: {
    promptId?: string;
    versionId?: string;
    commit?: string;
    version?: string;
    changeDescription?: string;
    tags?: string[];
    projectName?: string;
    environments?: string[];
  }): void {
    this._id = result.promptId;
    this._versionId = result.versionId;
    this._commit = result.commit;
    this._version = result.version;
    this._changeDescription = result.changeDescription;
    if (result.tags) {
      this._tags = result.tags;
    }
    if (result.projectName !== undefined) {
      this._projectName = result.projectName;
    }
    this._environments = result.environments ? [...result.environments] : [];
    this._synced = true;
  }

  /**
   * 子类构造函数共享的后台同步辅助方法。
   * 调用提供的创建函数，然后仅在返回的实例真正同步（填充了 id、versionId 和 commit）时更新同步状态。
   * 如果创建调用抛出异常或返回未同步的实例，_synced 保持为 false。
   */
  protected async _syncViaCreate<T extends BasePrompt>(
    create: () => Promise<T>,
  ): Promise<void> {
    const TIMED_OUT = Symbol();
    let timerId: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<typeof TIMED_OUT>((resolve) => {
      timerId = setTimeout(() => resolve(TIMED_OUT), PROMPT_SYNC_TIMEOUT_MS);
    });
    try {
      const createPromise = create().catch((error) => {
        // Swallow late rejection after timeout wins the race, so it does not become unhandled.
        // The catch block below already logs the real failure.
        logger.debug(`Prompt '${this._name}' sync rejected after timeout`, { error });
        return undefined;
      });
      const result = await Promise.race([createPromise, timeout]);
      if (result === TIMED_OUT) {
        logger.warn(
          `Prompt '${this._name}' sync timed out after ${PROMPT_SYNC_TIMEOUT_MS}ms. ` +
            "The prompt will work locally but is not persisted on the server. " +
            "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        );
        return;
      }
      if (!result) {
        logger.warn(
          `Prompt '${this._name}' sync failed (rejected after timeout). ` +
            "The prompt will work locally but is not persisted on the server. " +
            "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        );
        return;
      }
      if (result.synced && result.id && result.versionId && result.commit) {
        this.updateSyncState({
          promptId: result.id,
          versionId: result.versionId,
          commit: result.commit,
          version: result.version,
          changeDescription: result.changeDescription,
          tags: result.tags ? Array.from(result.tags) : undefined,
          projectName: result.projectName,
          environments: result.environments ? Array.from(result.environments) : undefined,
        });
      } else {
        logger.warn(
          `Prompt '${this._name}' was not persisted on the server. ` +
            "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        );
      }
    } catch (error) {
      logger.warn(
        `Failed to sync prompt '${this._name}' with the backend. ` +
          "The prompt will work locally but is not persisted on the server. " +
          "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        { error },
      );
    } finally {
      clearTimeout(timerId);
    }
  }

  /**
   * 初始化完成时解析。
   * 如果提示词已持久化（例如从后端检索），则立即返回。
   */
  ready(): Promise<void> {
    return this._pendingSync ?? Promise.resolve();
  }

  // Public getters for mutable fields
  get name(): string {
    return this._name;
  }

  get description(): string | undefined {
    return this._description;
  }

  get tags(): readonly string[] | undefined {
    return Object.freeze([...this._tags]);
  }

  /**
   * 只读元数据属性。
   * 返回深拷贝以防止外部修改。
   */
  get metadata(): OpikApi.JsonNode | undefined {
    if (!this._metadata) {
      return undefined;
    }
    return structuredClone(this._metadata);
  }

  /**
   * 更新提示词属性（名称、描述和/或标签）。
   * 执行立即更新（无批处理）。
   *
   * @param updates - 包含可选名称、描述和标签的部分更新
   * @returns 解析为此提示词实例的 Promise，用于方法链式调用
   */
  async updateProperties(updates: {
    name?: string;
    description?: string;
    tags?: string[];
  }): Promise<this> {
    await this.ready();
    this.ensureSynced("updateProperties");
    await this.opik.api.prompts.updatePrompt(
      this.id!,
      {
        name: updates.name ?? this._name,
        description: updates.description,
        tags: updates.tags,
      },
      this.opik.api.requestOptions,
    );

    // Update local state after successful backend update
    this._name = updates.name ?? this._name;
    this._description = updates.description ?? this._description;
    this._tags = updates.tags ?? this._tags;

    return this;
  }

  /**
   * 从后端删除此提示词。
   * 执行立即删除（无批处理）。
   */
  async delete(): Promise<void> {
    await this.ready();
    this.ensureSynced("delete");
    await this.opik.deletePrompts([this.id!]);
  }

  /**
   * 检索此提示词的所有版本历史。
   * 获取并返回完整的版本历史，按创建日期排序（最新的在前）。
   * 自动处理分页以获取所有版本。
   *
   * @param options - 可选的过滤、排序和搜索参数
   * @returns 解析为此提示词所有 PromptVersion 实例数组的 Promise
   */
  async getVersions(options?: {
    search?: string;
    sorting?: string;
    filters?: string;
  }): Promise<PromptVersion[]> {
    await this.ready();
    this.ensureSynced("getVersions");
    logger.debug("Getting versions for prompt", {
      promptId: this.id,
      name: this.name,
    });

    try {
      const allVersions: OpikApi.PromptVersionDetail[] = [];
      let page = 1;
      const pageSize = 100;

      while (true) {
        const versionsResponse = await this.opik.api.prompts.getPromptVersions(
          this.id!,
          {
            page,
            size: pageSize,
            search: options?.search,
            sorting: options?.sorting,
            filters: options?.filters,
          },
          this.opik.api.requestOptions,
        );

        const versions = versionsResponse.content ?? [];
        allVersions.push(...versions);

        if (versions.length < pageSize) {
          break;
        }
        page++;
      }

      logger.debug("Successfully retrieved prompt versions", {
        promptId: this.id,
        name: this.name,
        totalVersions: allVersions.length,
      });

      return allVersions.map((version: OpikApi.PromptVersionDetail) =>
        PromptVersion.fromApiResponse(this.name, version),
      );
    } catch (error) {
      logger.error("Failed to get prompt versions", {
        promptId: this.id,
        name: this.name,
        error,
      });
      throw error;
    }
  }

  /**
   * 恢复版本的辅助方法。
   * 由子类在其 useVersion 实现中使用。
   *
   * @param version - 要恢复的 PromptVersion 对象
   * @returns 解析为恢复版本的 API 响应的 Promise
   */
  protected async restoreVersion(
    version: PromptVersion,
  ): Promise<OpikApi.PromptVersionDetail> {
    await this.ready();
    this.ensureSynced("restoreVersion");
    logger.debug("Restoring prompt version", {
      promptId: this.id,
      name: this.name,
      versionId: version.id,
      versionCommit: version.commit,
    });

    try {
      const restoredVersionResponse =
        await this.opik.api.prompts.restorePromptVersion(
          this.id!,
          version.id,
          this.opik.api.requestOptions,
        );

      logger.debug("Successfully restored prompt version", {
        promptId: this.id,
        name: this.name,
        restoredVersionId: restoredVersionResponse.id,
        restoredCommit: restoredVersionResponse.commit,
      });

      return restoredVersionResponse;
    } catch (error) {
      logger.error("Failed to restore prompt version", {
        promptId: this.id,
        name: this.name,
        versionId: version.id,
        versionCommit: version.commit,
        error,
      });
      throw error;
    }
  }

  /**
   * 通过顺序版本标识符（如 `"v3"`）或为向后兼容通过提交哈希检索版本的辅助方法。
   *
   * 匹配 `/^v\d+$/` 的输入会被分派到 `versionNumber` 端点；
   * 其他内容被视为提交哈希。基于提交的获取已弃用 — 请改用 `"v<N>"` 标识符。
   *
   * @param version - 顺序版本（`"v3"`）或提交哈希（已弃用）
   * @returns 解析为 API 响应或未找到时为 null 的 Promise
   */
  protected async retrieveVersion(
    version: string,
  ): Promise<OpikApi.PromptVersionDetail | null> {
    const isVersionNumber = /^v\d+$/.test(version);
    const request = isVersionNumber
      ? { name: this.name, versionNumber: version }
      : { name: this.name, commit: version };

    try {
      const response = await this.opik.api.prompts.retrievePromptVersion(
        request,
        this.opik.api.requestOptions,
      );
      return response;
    } catch (error) {
      // If version not found (404), return null instead of throwing
      if (
        error &&
        typeof error === "object" &&
        "statusCode" in error &&
        error.statusCode === 404
      ) {
        return null;
      }

      logger.error("Failed to retrieve prompt version", {
        promptName: this.name,
        version,
        error,
      });
      throw error;
    }
  }

  /**
   * 如果提示词未成功与后端同步则抛出错误。
   * 在后端操作之前内部使用，以确保我们有有效的提示词 ID。
   */
  protected ensureSynced(operation: string): void {
    if (!this.synced) {
      throw new Error(
        `Cannot call ${operation}() on a prompt that failed to persist. ` +
          "Call .syncWithBackend() to retry persisting the prompt.",
      );
    }
  }

  /**
   * 通过顺序版本标识符（如 `"v3"`）获取特定版本，或为向后兼容通过提交哈希获取。
   * 返回适当提示词类型的新实例。
   *
   * @param version - 顺序版本（`"v<N>"`）（推荐）；或提交哈希（已弃用）
   * @returns 解析为提示词实例或未找到时为 null 的 Promise
   */
  abstract getVersion(version: string): Promise<BasePrompt | null>;

  /**
   * 通过从指定版本创建新版本来恢复特定版本。
   *
   * @param version - 要恢复的 PromptVersion 对象
   * @returns 解析为包含恢复版本的新提示词实例的 Promise
   */
  abstract useVersion(version: PromptVersion): Promise<BasePrompt>;

  /**
   * 将提示词与后端同步。
   *
   * 在 Opik 服务器上创建或更新提示词。如果同步失败，
   * 会记录警告，提示词继续在本地工作。
   *
   * @returns 解析为新的已同步实例的 Promise，如果同步失败则返回相同实例
   */
  abstract syncWithBackend(): Promise<BasePrompt>;
}
