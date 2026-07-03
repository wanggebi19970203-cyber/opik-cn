import type * as OpikApi from "@/rest_api/api";

/**
 * 支持的提示词模板引擎类型
 * 从 REST API 重新导出，使用大写值以保持一致性
 */
export const PromptType = {
  /** Mustache 模板语法，使用 {{variable}} 占位符 */
  MUSTACHE: "mustache" as const,
  /** Jinja2 模板语法，使用 {% %} 块和 {{ }} 变量 */
  JINJA2: "jinja2" as const,
  /** Python 模板语法，使用 {variable} 占位符 */
  PYTHON: "python" as const,
} as const;

export type PromptType = (typeof PromptType)[keyof typeof PromptType];

/**
 * 提示词的模板结构类型
 */
export const PromptTemplateStructure = {
  /** 基于文本的提示词，包含单个模板字符串 */
  Text: "text" as const,
  /** 基于聊天的提示词，包含消息数组 */
  Chat: "chat" as const,
} as const;

export type PromptTemplateStructure =
  (typeof PromptTemplateStructure)[keyof typeof PromptTemplateStructure];

/**
 * 文本和聊天提示词之间共享的通用选项
 * 内部用于提示词创建逻辑
 */
export interface CommonPromptOptions {
  /** 可选的提示词 ID（如果未提供则生成） */
  promptId?: string;
  /** 提示词的可选描述 */
  description?: string;
  /** 用于跟踪和过滤的可选元数据 */
  metadata?: OpikApi.JsonNodeWrite;
  /** 版本跟踪的可选变更描述 */
  changeDescription?: string;
  /** 模板引擎类型，默认为 mustache */
  type?: PromptType;
  /** 用于分类的可选标签 */
  tags?: string[];
}

/**
 * 创建新提示词的配置选项
 * 扩展 REST API PromptWrite，重命名 'prompt' 字段
 */
export interface CreatePromptOptions extends CommonPromptOptions {
  /** 提示词的名称（唯一标识符） */
  name: string;
  /** 包含占位符的模板文本内容 */
  prompt: string;
  /** 可选的项目名称，用于限定提示词范围。如果未提供，使用客户端配置的项目。 */
  projectName?: string;
}

/**
 * 检索特定提示词版本的选项。
 *
 * `commit` 和 `version` 互斥。如果两者都未提供，则返回最新版本。
 */
export interface GetPromptOptions {
  /** 要检索的提示词名称。 */
  name: string;
  /** @deprecated 请使用 `version` 替代。 */
  commit?: string;
  /**
   * 顺序版本标识符，如 `"v3"`。与 `commit` 互斥。
   */
  version?: string;
  /** 可选的项目名称，用于限定查找范围。 */
  projectName?: string;
  /**
   * 可选的环境名称。解析为该工作区环境当前拥有的版本。与 `commit` 互斥。
   */
  environment?: string;
}

/**
 * 要替换到提示词模板中的变量。
 */
export type PromptVariables = Record<string, unknown>;

/**
 * 创建 PromptVersion 实例的数据结构
 */
export interface PromptVersionData {
  name: string;
  prompt: string;
  commit: string;
  version?: string;
  promptId: string;
  versionId: string;
  type: PromptType;
  metadata?: OpikApi.JsonNode;
  changeDescription?: string;
  tags?: string[];
  createdAt?: Date;
  createdBy?: string;
}

// Chat prompt types

/**
 * 多模态聊天消息的内容部分
 */
export interface ContentPart {
  type: string;
  [key: string]: unknown;
}

/**
 * 文本内容部分
 */
export interface TextContentPart extends ContentPart {
  type: "text";
  text: string;
}

/**
 * 图像 URL 内容部分
 */
export interface ImageUrlContentPart extends ContentPart {
  type: "image_url";
  image_url: {
    url: string;
    detail?: string;
    [key: string]: unknown;
  };
}

/**
 * 视频 URL 内容部分
 */
export interface VideoUrlContentPart extends ContentPart {
  type: "video_url";
  video_url: {
    url: string;
    mime_type?: string;
    duration?: number;
    format?: string;
    detail?: string;
    [key: string]: unknown;
  };
}

/**
 * 消息内容可以是字符串或内容部分数组
 */
export type MessageContent = string | ContentPart[];

/**
 * 包含角色和内容的聊天消息
 */
export interface ChatMessage {
  role: string;
  content: MessageContent;
}

/**
 * 支持的内容类型的模态名称
 */
export type ModalityName = "vision" | "video";

/**
 * 模态是否受支持的映射
 */
export type SupportedModalities = Partial<Record<ModalityName, boolean>>;

/**
 * 创建新聊天提示词的配置选项
 */
export interface CreateChatPromptOptions extends CommonPromptOptions {
  /** 提示词的名称（唯一标识符） */
  name: string;
  /** 包含角色和内容的聊天消息数组 */
  messages: ChatMessage[];
  /** 是否验证模板占位符 */
  validatePlaceholders?: boolean;
  /** 可选的项目名称，用于限定提示词范围。如果未提供，使用客户端配置的项目。 */
  projectName?: string;
}
