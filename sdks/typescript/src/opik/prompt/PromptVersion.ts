import type * as OpikApi from "@/rest_api/api";
import { formatDistanceToNow } from "date-fns";
import { diffStringsUnified } from "jest-diff";
import { logger } from "@/utils/logger";
import {
  PromptType,
  type PromptVariables,
  type PromptVersionData,
  type ChatMessage,
} from "./types";
import { PromptValidationError } from "./errors";
import { formatPromptTemplate } from "./formatting";
import { formatChatMessagesForComparison } from "./formatting/chatMessageFormatter";

/**
 * 表示提示词模板在某个时间点的特定不可变快照。
 * 具有格式化功能的纯数据对象。
 */
export class PromptVersion {
  // Public readonly properties
  public readonly id: string;
  public readonly name: string;
  public readonly prompt: string;
  /**
   * @deprecated 此提示词版本的旧式提交哈希。请使用 {@link version} 替代 — `commit` 不再在 Opik UI 中显示，仅为向后兼容旧版 SDK 调用者而保留。
   */
  public readonly commit: string;
  /**
   * 此提示词版本的顺序版本标识符（如 `"v3"`）。
   */
  public readonly version?: string;
  public readonly type: PromptType;
  public readonly metadata?: OpikApi.JsonNode;
  public readonly changeDescription?: string;
  public readonly tags?: string[];
  public readonly createdAt?: Date;
  public readonly createdBy?: string;

  constructor(data: PromptVersionData) {
    this.id = data.versionId;
    this.name = data.name;
    this.prompt = data.prompt;
    this.commit = data.commit;
    this.version = data.version;
    this.type = data.type;
    this.metadata = data.metadata;
    this.changeDescription = data.changeDescription;
    this.tags = data.tags;
    this.createdAt = data.createdAt;
    this.createdBy = data.createdBy;
  }

  /**
   * 使用提供的变量格式化提示词模板
   */
  format(variables: PromptVariables): string {
    return formatPromptTemplate(this.prompt, variables, this.type);
  }

  /**
   * 获取人类可读的版本年龄（如 "2 days ago"、"Today"）
   */
  getVersionAge(): string {
    if (!this.createdAt) {
      return "Unknown";
    }

    return formatDistanceToNow(new Date(this.createdAt), { addSuffix: true });
  }

  /**
   * 获取格式化的版本信息字符串。
   * 格式："[v3] YYYY-MM-DD by user@email.com - Change description"
   * （当没有版本号时回退到提交哈希）。
   */
  getVersionInfo(): string {
    const parts: string[] = [`[${this.version ?? this.commit}]`];

    if (this.createdAt) {
      const date = new Date(this.createdAt);
      parts.push(date.toISOString().split("T")[0]);
    }

    if (this.createdBy) {
      parts.push(`by ${this.createdBy}`);
    }

    if (this.changeDescription) {
      parts.push(`- ${this.changeDescription}`);
    }

    return parts.join(" ");
  }

  /**
   * 将此版本的模板与另一个版本进行比较并返回格式化的差异。
   * 显示 git 风格的统一差异，显示添加、删除和更改。
   * 对于聊天提示词，提供智能格式化和结构化消息显示。
   * 差异会自动记录到终端并作为字符串返回。
   * 输出经过着色和格式化以供终端显示。
   *
   * @param other - 要比较的版本
   * @returns 显示版本之间差异的格式化字符串
   *
   * @example
   * ```typescript
   * const versions = await prompt.getVersions();
   * const [current, previous] = versions;
   *
   * // 记录差异到终端并返回
   * const diff = current.compareTo(previous);
   * ```
   */
  compareTo(other: PromptVersion): string {
    // Prefer the sequential version identifier in labels (e.g. "v3"); fall back
    // to the legacy commit hash when version is absent (e.g. mask versions).
    const thisLabel = `Current version [${this.version ?? this.commit}]`;
    const otherLabel = `Other version [${other.version ?? other.commit}]`;

    // Check if this is a chat prompt (template structure is chat)
    let thisFormatted = this.prompt;
    let otherFormatted = other.prompt;

    // Try to detect and format chat prompts
    if (this.isChatPrompt(this.prompt)) {
      thisFormatted = this.formatChatPromptString(this.prompt);
    }
    if (this.isChatPrompt(other.prompt)) {
      otherFormatted = this.formatChatPromptString(other.prompt);
    }

    const diffOutput = diffStringsUnified(otherFormatted, thisFormatted, {
      aAnnotation: otherLabel,
      bAnnotation: thisLabel,
      includeChangeCounts: true,
      contextLines: 3,
      expand: false,
    });

    // Log the diff to terminal for immediate visibility
    logger.info(`\nPrompt version comparison:\n${diffOutput}`);

    return diffOutput;
  }

  /**
   * 检查提示词字符串是否为聊天提示词（消息的 JSON 数组）
   */
  private isChatPrompt(prompt: string): boolean {
    try {
      const parsed = JSON.parse(prompt);
      return (
        Array.isArray(parsed) &&
        parsed.length > 0 &&
        typeof parsed[0] === "object" &&
        "role" in parsed[0] &&
        "content" in parsed[0]
      );
    } catch {
      return false;
    }
  }

  /**
   * 格式化聊天提示词字符串（JSON）以便人类可读比较。
   */
  private formatChatPromptString(prompt: string): string {
    try {
      const messages: ChatMessage[] = JSON.parse(prompt);
      return formatChatMessagesForComparison(messages);
    } catch {
      // If parsing fails, return original prompt
      return prompt;
    }
  }

  /**
   * 从 API 响应创建 PromptVersion 的工厂方法
   */
  static fromApiResponse(
    name: string,
    apiResponse: OpikApi.PromptVersionDetail
  ): PromptVersion {
    // Validate required fields
    if (!apiResponse.template) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'template'"
      );
    }

    if (!apiResponse.commit) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'commit'"
      );
    }

    if (!apiResponse.promptId) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'promptId'"
      );
    }

    if (!apiResponse.id) {
      throw new PromptValidationError(
        "Invalid API response: missing required field 'id'"
      );
    }

    return new PromptVersion({
      name,
      prompt: apiResponse.template,
      commit: apiResponse.commit,
      version: apiResponse.versionNumber ?? undefined,
      promptId: apiResponse.promptId,
      versionId: apiResponse.id,
      type: apiResponse.type ?? PromptType.MUSTACHE,
      metadata: apiResponse.metadata,
      changeDescription: apiResponse.changeDescription,
      tags: apiResponse.tags,
      createdAt: apiResponse.createdAt
        ? new Date(apiResponse.createdAt)
        : undefined,
      createdBy: apiResponse.createdBy,
    });
  }
}
