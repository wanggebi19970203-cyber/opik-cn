import type { OpikClient } from "@/client/Client";
import {
  PromptType,
  ChatMessage,
  SupportedModalities,
  PromptVariables,
  PromptTemplateStructure,
} from "./types";
import { PromptValidationError } from "./errors";
import type * as OpikApi from "@/rest_api/api";
import { ChatPromptTemplate } from "./chat/ChatPromptTemplate";
import { BasePrompt, type BasePromptData } from "./BasePrompt";
import { PromptVersion } from "./PromptVersion";
import { logger } from "@/utils/logger";

export interface ChatPromptData extends BasePromptData {
  messages: ChatMessage[];
}

/**
 * 表示版本化聊天提示词模板的领域对象。
 * 提供对聊天消息模板和格式化的不可变访问。
 * 与后端集成以实现持久化和版本管理。
 */
export class ChatPrompt extends BasePrompt {
  public readonly messages: ChatMessage[];
  private readonly chatTemplate: ChatPromptTemplate;

  /**
   * 创建新的 ChatPrompt 实例。
   * 所有操作无需手动配置即可无缝运行。
   */
  constructor(data: ChatPromptData);
  /** @deprecated Passing an opik client is deprecated. */
  constructor(data: ChatPromptData, opik: OpikClient);
  constructor(data: ChatPromptData, opik?: OpikClient) {
    super(
      {
        ...data,
        templateStructure: PromptTemplateStructure.Chat,
      },
      opik,
    );
    this.messages = data.messages;
    this.chatTemplate = new ChatPromptTemplate(data.messages, this.type);

    if (!data.synced && !data.promptId) {
      logger.warn(
        "new ChatPrompt() is deprecated. Use client.createChatPrompt() to create or " +
          "client.getChatPrompt() to retrieve chat prompts instead."
      );
    }

    if (opik === undefined && !data.synced) {
      this._pendingSync = this._performSync();
    }
  }

  private _performSync(): Promise<void> {
    return this._syncViaCreate(() =>
      this.opik.createChatPrompt({
        name: this._name,
        messages: structuredClone(this.messages),
        metadata: this._metadata,
        type: this.type,
        description: this._description,
        tags: this._tags.length ? Array.from(this._tags) : undefined,
      }),
    );
  }

  /**
   * 返回此聊天提示词的模板消息。
   * 与 Prompt 保持一致的 `messages` 属性别名。
   */
  get template(): ChatMessage[] {
    return structuredClone(this.messages);
  }

  /**
   * 通过替换消息中的变量来格式化聊天模板。
   *
   * @param variables - 包含要替换到模板中的值的对象
   * @param supportedModalities - 可选的支持模态规格。
   *   当模态不受支持（false 或未指定）时，结构化内容部分（如图像、视频）
   *   将被替换为文本占位符，如 "<<<image>>>" 或 "<<<video>>>"。
   *   当受支持（true）时，结构化内容将保持原样。默认支持所有模态。
   * @returns 替换变量后的已格式化聊天消息数组
   * @throws 若模板处理失败则抛出 PromptValidationError
   *
   * @example
   * ```typescript
   * const chatPrompt = new ChatPrompt({
   *   name: "assistant",
   *   messages: [
   *     { role: "system", content: "You are a {{role}}" },
   *     { role: "user", content: "Help me with {{task}}" }
   *   ],
   *   type: "mustache"
   * }, client);
   *
   * // 支持所有模态的格式化
   * const messages = chatPrompt.format({
   *   role: "helpful assistant",
   *   task: "coding"
   * });
   *
   * // 限制模态的格式化
   * const textOnly = chatPrompt.format(
   *   { role: "assistant", task: "coding" },
   *   { vision: false, video: false }
   * );
   * ```
   */
  format(
    variables: PromptVariables,
    supportedModalities?: SupportedModalities,
  ): ChatMessage[] {
    return this.chatTemplate.format(variables, supportedModalities);
  }

  /**
   * 从后端 API 响应创建 ChatPrompt 的静态工厂方法。
   *
   * @param promptData - 包含名称、描述、标签的 PromptPublic 数据
   * @param apiResponse - REST API PromptVersionDetail 响应
   * @param opik - OpikClient 实例
   * @returns 从响应数据构造的 ChatPrompt 实例
   * @throws 若响应结构无效则抛出 PromptValidationError
   */
  static fromApiResponse(
    promptData: OpikApi.PromptPublic,
    apiResponse: OpikApi.PromptVersionDetail,
    opik: OpikClient,
    projectName?: string,
  ): ChatPrompt {
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

    // Parse messages from JSON string
    let messages: ChatMessage[];
    try {
      messages = JSON.parse(apiResponse.template);
      if (!Array.isArray(messages)) {
        throw new PromptValidationError(
          "Invalid chat prompt template: expected array of messages",
        );
      }
    } catch (error) {
      if (error instanceof PromptValidationError) {
        throw error;
      }
      throw new PromptValidationError(
        `Failed to parse chat prompt template: ${error instanceof Error ? error.message : String(error)}`,
      );
    }

    // Validate type if present
    const promptType = apiResponse.type ?? PromptType.MUSTACHE;
    if (promptType !== "mustache" && promptType !== "jinja2") {
      throw new PromptValidationError(
        `Invalid API response: unknown prompt type '${promptType}'`,
      );
    }

    // Create ChatPrompt instance
    return new ChatPrompt(
      {
        promptId: apiResponse.promptId,
        versionId: apiResponse.id,
        name: promptData.name,
        messages,
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
   * 返回一个新的 ChatPrompt 实例，其内容为恢复的最新版本。
   *
   * @param version - 要恢复的 PromptVersion 对象（必须来自后端）
   * @returns 解析为包含恢复版本的新 ChatPrompt 实例的 Promise
   * @throws 若 REST API 调用失败则抛出 OpikApiError
   *
   * @example
   * ```typescript
   * const chatPrompt = await client.getChatPrompt({ name: "my-chat-prompt" });
   *
   * // 获取所有版本
   * const versions = await chatPrompt.getVersions();
   *
   * // 恢复特定版本
   * const targetVersion = versions.find(v => v.commit === "abc123de");
   * if (targetVersion) {
   *   const restoredPrompt = await chatPrompt.useVersion(targetVersion);
   *   console.log(`Restored to commit: ${restoredPrompt.commit}`);
   *
   *   // 继续使用恢复的提示词
   *   const formatted = restoredPrompt.format({ name: "World" });
   * }
   * ```
   */
  async useVersion(version: PromptVersion): Promise<ChatPrompt> {
    const restoredVersionResponse = await this.restoreVersion(version);

    // Return a new ChatPrompt instance with the restored version
    return ChatPrompt.fromApiResponse(
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
   * 将聊天提示词与后端同步。
   *
   * 在 Opik 服务器上创建或更新聊天提示词。如果同步失败，
   * 会记录警告并返回相同的（未同步的）实例。
   *
   * @returns 解析为新的已同步 ChatPrompt 实例的 Promise，如果同步失败则返回此实例
   */
  async syncWithBackend(): Promise<ChatPrompt> {
    try {
      return await this.opik.createChatPrompt({
        name: this.name,
        messages: structuredClone(this.messages),
        metadata: this.metadata,
        type: this.type,
        description: this.description,
        tags: this.tags ? Array.from(this.tags) : undefined,
      });
    } catch (error) {
      logger.warn(
        `Failed to sync chat prompt '${this.name}' with the backend. ` +
          "The prompt will work locally but is not persisted on the server. " +
          "Await prompt.ready(), then retry by calling .syncWithBackend() if prompt.synced is still false.",
        { error },
      );
      return this;
    }
  }

  /**
   * 获取特定版本的 ChatPrompt。
   *
   * 接受顺序版本标识符（如 `"v3"`）（推荐）或用于向后兼容的提交哈希。
   * 匹配 `/^v\d+$/` 的输入被视为版本号；其他内容被视为提交。
   *
   * @param version - 顺序版本（`"v<N>"`）或提交哈希
   *   （提交输入已**弃用** — 请改用 `"v<N>"` 标识符）。
   * @returns 表示该版本的 ChatPrompt 实例，如果未找到则返回 null
   *
   * @example
   * ```typescript
   * // 推荐
   * const v3 = await chatPrompt.getVersion("v3");
   *
   * // @deprecated — 提交格式输入
   * const byCommit = await chatPrompt.getVersion("abc123de");
   * ```
   */
  async getVersion(version: string): Promise<ChatPrompt | null> {
    const response = await this.retrieveVersion(version);
    if (!response) {
      return null;
    }

    // Return a ChatPrompt instance representing this version
    return ChatPrompt.fromApiResponse(
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
