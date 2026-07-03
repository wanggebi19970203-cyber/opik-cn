import type { BasePrompt } from "@/prompt/BasePrompt";
import type { Prompt } from "@/prompt/Prompt";
import type { ChatPrompt } from "@/prompt/ChatPrompt";
import type * as OpikApi from "@/rest_api/api";
import type { PromptInfoDict, TraceUpdateData, SpanUpdateData } from "./types";

function isTextPrompt(p: BasePrompt): p is Prompt {
  return p.templateStructure === "text";
}

function isChatPrompt(p: BasePrompt): p is ChatPrompt {
  return p.templateStructure === "chat";
}

/**
 * 用于处理带有提示词支持的追踪和跨度更新的服务。
 * 处理提示词的序列化和合并到元数据中。
 */
export class UpdateService {
  private static serializePromptToInfoDict(prompt: BasePrompt): PromptInfoDict {
    let template: unknown;
    if (isTextPrompt(prompt)) {
      template = prompt.prompt;
    } else if (isChatPrompt(prompt)) {
      template = prompt.messages;
    } else {
      template = "";
    }

    return {
      name: prompt.name,
      ...(prompt.id && { id: prompt.id }),
      template_structure: prompt.templateStructure,
      version: {
        ...(prompt.versionId && { id: prompt.versionId }),
        ...(prompt.commit && { commit: prompt.commit }),
        template,
      },
    };
  }

  /**
   * 将 JsonListString 转换为适合合并的对象，当值无法表示为普通对象（数组、非 JSON 字符串）时返回 null。
   * 返回 null 表示"不可合并"，调用者可以保留原始值。
   *
   * @param metadata - JsonListString 格式的元数据
   * @returns 元数据的对象表示，如果转换不可能（数组、无法解析的字符串）则返回 null
   */
  private static normalizeMetadata(
    metadata: OpikApi.JsonListString | undefined
  ): Record<string, unknown> | null {
    if (!metadata) {
      return {};
    }

    if (typeof metadata === "object" && !Array.isArray(metadata)) {
      return metadata;
    }

    if (typeof metadata === "string") {
      try {
        const parsed = JSON.parse(metadata);
        if (typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)) {
          return parsed as Record<string, unknown>;
        }
      } catch {
        // unparseable string — not mergeable
      }
    }

    return null;
  }

  /**
   * 当给定的提示词已记录在元数据中，或者元数据不是普通对象时返回 true
   * （此时应跳过注入以避免丢弃原始值）。
   */
  static promptAlreadyInjected(
    metadata: OpikApi.JsonListString | undefined,
    promptId: string | undefined,
    commit: string | undefined
  ): boolean {
    const obj = this.normalizeMetadata(metadata);
    if (obj === null) {
      // Non-object metadata — skip injection to preserve original value.
      return true;
    }
    const existing = Array.isArray(obj.opik_prompts) ? (obj.opik_prompts as PromptInfoDict[]) : [];
    return existing.some((p) => p.id === promptId && p.version?.commit === commit);
  }

  /**
   * 将提示词合并到元数据的 "opik_prompts" 键下。
   * 当它们是对象时，保留现有元数据和新元数据字段。
   * 非对象元数据（字符串/数组）将被替换为提示词元数据。
   *
   * @param existingMetadata - 来自追踪/跨度的当前元数据
   * @param newMetadata - 来自更新调用的新元数据
   * @param prompts - 要序列化的 Prompt 对象数组
   * @returns 包含提示词的已合并元数据
   */
  private static mergePromptsIntoMetadata(
    existingMetadata: OpikApi.JsonListString | undefined,
    newMetadata: OpikApi.JsonListString | undefined,
    prompts: BasePrompt[],
    append: boolean
  ): OpikApi.JsonListString {
    const serializedPrompts = prompts.map((p) =>
      this.serializePromptToInfoDict(p)
    );

    const existingObj = this.normalizeMetadata(existingMetadata) ?? {};
    const newObj = this.normalizeMetadata(newMetadata) ?? {};

    const existingPrompts = append && Array.isArray(existingObj.opik_prompts)
      ? existingObj.opik_prompts as PromptInfoDict[]
      : [];

    return {
      ...existingObj,
      ...newObj,
      opik_prompts: [...existingPrompts, ...serializedPrompts],
    };
  }

  private static processUpdate<T extends { metadata?: OpikApi.JsonListString; prompts?: BasePrompt[]; appendPrompts?: boolean }>(
    updates: T,
    existingMetadata?: OpikApi.JsonListString
  ): Omit<T, "prompts" | "appendPrompts"> {
    const { prompts, appendPrompts, ...restUpdates } = updates;

    if (!prompts || prompts.length === 0) {
      // Even without prompts, merge existing metadata with new metadata
      // so that update({ metadata: {...} }) preserves prior metadata
      if (restUpdates.metadata && existingMetadata) {
        const existingObj = this.normalizeMetadata(existingMetadata) ?? {};
        const newObj = this.normalizeMetadata(restUpdates.metadata) ?? {};
        return { ...restUpdates, metadata: { ...existingObj, ...newObj } };
      }
      return restUpdates as Omit<T, "prompts" | "appendPrompts">;
    }

    return {
      ...restUpdates,
      metadata: this.mergePromptsIntoMetadata(existingMetadata, restUpdates.metadata, prompts, appendPrompts ?? false),
    };
  }

  static processTraceUpdate(
    updates: TraceUpdateData,
    existingMetadata?: OpikApi.JsonListString
  ): Omit<OpikApi.TraceUpdate, "projectId"> {
    return this.processUpdate(updates, existingMetadata);
  }

  static processSpanUpdate(
    updates: SpanUpdateData,
    existingMetadata?: OpikApi.JsonListString
  ): Omit<OpikApi.SpanUpdate, "traceId" | "parentSpanId" | "projectId" | "projectName"> {
    return this.processUpdate(updates, existingMetadata);
  }
}
