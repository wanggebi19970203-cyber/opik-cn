import type { OpikClient } from "@/client/Client";
import { PromptType, PromptVariables, PromptTemplateStructure } from "./types";
import { PromptValidationError } from "./errors";
import type * as OpikApi from "@/rest_api/api";
import { formatPromptTemplate } from "./formatting";
import { PromptVersion } from "./PromptVersion";
import { BasePrompt, type BasePromptData } from "./BasePrompt";
import { logger } from "@/utils/logger";

export interface PromptData extends BasePromptData {
  prompt: string;
}

/**
 * 表示版本化文本提示词模板的领域对象。
 * 提供对提示词属性和模板格式化的不可变访问。
 * 与后端集成以实现持久化和版本管理。
 */
export class Prompt extends BasePrompt {
  public readonly prompt: string;

  /**
   * 创建新的 Prompt 实例。
   * 所有操作无需手动配置即可无缝运行。
   */
  constructor(data: PromptData);
  /** @deprecated Passing an opik client is deprecated. */
  constructor(data: PromptData, opik: OpikClient);
  constructor(data: PromptData, opik?: OpikClient) {
    super(
      {
        ...data,
        templateStructure: PromptTemplateStructure.Text,
      },
      opik,
    );
    this.prompt = data.prompt;

    if (!data.synced && !data.promptId) {
      logger.warn(
        "new Prompt() is deprecated. Use client.createPrompt() to create or " +
          "client.getPrompt() to retrieve text prompts instead."
      );
    }

    if (opik === undefined && !data.synced) {
      this._pendingSync = this._performSync();
    }
  }

  private _performSync(): Promise<void> {
    return this._syncViaCreate(() =>
      this.opik.createPrompt({
        name: this._name,
        prompt: this.prompt,
        metadata: this._metadata,
        type: this.type,
        description: this._description,
        tags: this._tags.length ? Array.from(this._tags) : undefined,
      }),
    );
  }

  /**
   * 返回此文本提示词的模板字符串。
   * 与 ChatPrompt 保持一致的 `prompt` 属性别名。
   */
  get template(): string {
    return this.prompt;
  }

  /**
   * 通过替换变量来格式化提示词模板。
   * 验证是否提供了所有模板占位符（适用于 Mustache 模板）。
   *
   * @param variables - 包含要替换到模板中的值的对象
   * @returns 替换变量后的已格式化提示词文本
   * @throws 若模板处理或验证失败则抛出 PromptValidationError
   *
   * @example
   * ```typescript
   * const prompt = new Prompt({
   *   name: "greeting",
   *   prompt: "Hello {{name}}, your score is {{score}}",
   *   type: "mustache"
   * }, client);
   *
   * // 有效 - 提供了所有占位符
   * prompt.format({ name: "Alice", score: 95 });
   * // 返回: "Hello Alice, your score is 95"
   *
   * // 无效 - 缺少 'score' 占位符
   * prompt.format({ name: "Alice" });
   * // 抛出: PromptValidationError
   * ```
   */
  format(variables: PromptVariables): string {
    return formatPromptTemplate(this.prompt, variables, this.type);
  }

  /**
   * 从后端 API 响应创建 Prompt 的静态工厂方法。
   *
   * @param name - 提示词的名称
   * @param apiResponse - REST API PromptVersionDetail 响应
   * @param opik - OpikClient 实例
   * @param promptPublicData - 可选的 PromptPublic 数据，包含描述和标签
   * @returns 从响应数据构造的 Prompt 实例
   * @throws 若响应结构无效则抛出 PromptValidationError
   */
  static fromApiResponse(
    promptData: OpikApi.PromptPublic,
    apiResponse: OpikApi.PromptVersionDetail,
    opik: OpikClient,
    projectName?: string,
  ): Prompt {
    // Validate required fields
    if (!apiResponse.template) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'template'",
      );
    }

    if (!apiResponse.commit) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'commit'",
      );
    }

    if (!apiResponse.promptId) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'promptId'",
      );
    }

    if (!apiResponse.id) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'id' (version ID)",
      );
    }

    // Validate type if present
    const promptType = apiResponse.type ?? PromptType.MUSTACHE;
    if (promptType !== "mustache" && promptType !== "jinja2") {
      throw new PromptValidationError(
        `Invalid API response: unknown prompt type '${promptType}'`,
      );
    }

    // Create Prompt instance (no enqueueing - already persisted)
    // Type assertion safe due to validation above
    return new Prompt(
      {
        promptId: apiResponse.promptId,
        versionId: apiResponse.id,
        name: promptData.name,
        prompt: apiResponse.template,
        commit: apiResponse.commit,
        version: apiResponse.versionNumber,
        metadata: apiResponse.metadata,
        type: promptType,
        changeDescription: apiResponse.changeDescription,
        description: promptData.description,
        tags: promptData.tags,
        synced: true,
        projectName,
        environments: apiResponse.environments,
      },
      opik,
    );
  }

  /**
   * 通过从指定版本创建新版本来恢复特定版本。
   * 版本必须从后端获取（例如通过 getVersions()）。
   * 返回一个新的 Prompt 实例，其内容为恢复的最新版本。
   *
   * @param version - 要恢复的 PromptVersion 对象（必须来自后端）
   * @returns 解析为包含恢复版本的新 Prompt 实例的 Promise
   * @throws 若 REST API 调用失败则抛出 OpikApiError
   *
   * @example
   * ```typescript
   * const prompt = await client.getPrompt({ name: "my-prompt" });
   *
   * // 获取所有版本
   * const versions = await prompt.getVersions();
   *
   * // 恢复特定版本
   * const targetVersion = versions.find(v => v.commit === "abc123de");
   * if (targetVersion) {
   *   const restoredPrompt = await prompt.useVersion(targetVersion);
   *   console.log(`Restored to commit: ${restoredPrompt.commit}`);
   *   console.log(`New template: ${restoredPrompt.prompt}`);
   *
   *   // 继续使用恢复的提示词
   *   const formatted = restoredPrompt.format({ name: "World" });
   * }
   * ```
   */
  async useVersion(version: PromptVersion): Promise<Prompt> {
    const restoredVersionResponse = await this.restoreVersion(version);

    // Return a new Prompt instance with the restored version
    return Prompt.fromApiResponse(
      {
        name: this.name,
        description: this.description,
        tags: Array.from(this.tags ?? []),
      },
      restoredVersionResponse,
      this.opik,
    );
  }

  /**
   * 将提示词与后端同步。
   *
   * 在 Opik 服务器上创建或更新提示词。如果同步失败，
   * 会记录警告并返回相同的（未同步的）实例。
   *
   * @returns 解析为新的已同步 Prompt 实例的 Promise，如果同步失败则返回此实例
   */
  async syncWithBackend(): Promise<Prompt> {
    try {
      return await this.opik.createPrompt({
        name: this.name,
        prompt: this.prompt,
        metadata: this.metadata,
        type: this.type,
        description: this.description,
        tags: this.tags ? Array.from(this.tags) : undefined,
      });
    } catch (error) {
      logger.warn(
        `Failed to sync prompt '${this.name}' with the backend. ` +
          "The prompt will work locally but is not persisted on the server. " +
          "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        { error },
      );
      return this;
    }
  }

  /**
   * 获取特定版本的 Prompt。
   *
   * 接受顺序版本标识符（如 `"v3"`）（推荐）或用于向后兼容的提交哈希。
   * 匹配 `/^v\d+$/` 的输入被视为版本号；其他内容被视为提交。
   *
   * @param version - 顺序版本（`"v<N>"`）或提交哈希
   *   （提交输入已**弃用** — 请改用 `"v<N>"` 标识符）。
   * @returns 表示该版本的 Prompt 实例，如果未找到则返回 null
   *
   * @example
   * ```typescript
   * // 推荐
   * const v3 = await prompt.getVersion("v3");
   *
   * // @deprecated — 提交格式输入
   * const byCommit = await prompt.getVersion("abc123de");
   * ```
   */
  async getVersion(version: string): Promise<Prompt | null> {
    const response = await this.retrieveVersion(version);
    if (!response) {
      return null;
    }

    // Return a Prompt instance representing this version
    return Prompt.fromApiResponse(
      {
        name: this.name,
        description: this.description,
        tags: Array.from(this.tags ?? []),
      },
      response,
      this.opik,
    );
  }
}
