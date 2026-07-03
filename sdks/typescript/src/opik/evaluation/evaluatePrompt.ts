import type { LanguageModel } from "ai";
import {
  OpikMessage,
  OpikBaseModel,
  resolveModel,
  SupportedModelId,
} from "./models";
import { EvaluationResult, EvaluationTask } from "./types";
import { evaluate, EvaluateOptions } from "./evaluate";
import { PromptType } from "@/prompt/types";
import {
  applyTemplateVariablesToMessage,
  formatMessagesAsString,
} from "./utils/formatMessages";

/**
 * 评估提示词模板的选项。
 * 扩展 EvaluateOptions，但用提示词特定字段替换 'task'。
 */
export interface EvaluatePromptOptions extends Omit<EvaluateOptions, "task"> {
  /** 包含 {{placeholders}} 的消息模板，将使用数据集变量进行格式化 */
  messages: OpikMessage[];

  /** 用于生成的模型。可以是模型 ID 字符串、LanguageModel 实例或 OpikBaseModel 实例。默认为 gpt-5-nano */
  model?: SupportedModelId | LanguageModel | OpikBaseModel;

  /** 用于变量替换的模板引擎类型。默认为 mustache */
  templateType?: PromptType;

  /** 模型生成的温度设置（0.0-2.0）。控制随机性。较低的值使输出更集中和确定性。 */
  temperature?: number;

  /** 用于可复现模型输出的随机种子。用于测试和确保一致的结果。 */
  seed?: number;
}

/**
 * 通过使用数据集变量格式化消息并生成 LLM 响应来评估提示词模板。
 *
 * 这是 `evaluate` 函数的便捷包装器，自动处理提示词模板格式化和模型调用。
 * 它使用指定的模板引擎（Mustache 或 Jinja2）使用数据集条目变量格式化消息模板，
 * 使用提供的模型生成响应，并使用指定的指标评估结果。
 *
 * @param options - 提示词评估的配置选项
 * @returns 解析为带有实验元数据的评估结果的 Promise
 *
 * @example
 * ```typescript
 * import { evaluatePrompt } from 'opik/evaluation';
 * import { Equals } from 'opik/evaluation/metrics';
 *
 * const dataset = await client.getDataset('my-dataset');
 *
 * // 使用模型 ID 字符串，设置温度和种子以确保可复现性
 * const result1 = await evaluatePrompt({
 *   dataset,
 *   messages: [
 *     { role: 'user', content: 'Translate to {{language}}: {{text}}' }
 *   ],
 *   model: 'gpt-5-nano', // 或省略以使用默认模型
 *   temperature: 0.7,
 *   seed: 42,
 *   scoringMetrics: [new Equals()],
 * });
 *
 * // 使用预配置的 LanguageModel 实例
 * import { openai } from '@ai-sdk/openai';
 * const customModel = openai('gpt-5-nano', { structuredOutputs: true });
 * const result2 = await evaluatePrompt({
 *   dataset,
 *   messages: [
 *     { role: 'user', content: 'Translate to {{language}}: {{text}}' }
 *   ],
 *   model: customModel,
 *   scoringMetrics: [new Equals()],
 * });
 * ```
 */
export async function evaluatePrompt(
  options: EvaluatePromptOptions
): Promise<EvaluationResult> {
  // Validate required parameters
  if (!options.dataset) {
    throw new Error("Dataset is required for prompt evaluation");
  }

  if (!options.messages || options.messages.length === 0) {
    throw new Error("Messages array is required and cannot be empty");
  }

  // Validate experimentConfig type
  if (
    options.experimentConfig !== undefined &&
    (typeof options.experimentConfig !== "object" ||
      options.experimentConfig === null ||
      Array.isArray(options.experimentConfig))
  ) {
    throw new Error(
      "experimentConfig must be a plain object, not an array or primitive value"
    );
  }

  // Resolve model (string → OpikBaseModel, undefined → default)
  const model = resolveModel(options.model);

  // Build experiment config with defaults
  const experimentConfig = {
    ...options.experimentConfig,
    prompt_template: options.messages,
    model: model.modelName,
    ...(options.temperature !== undefined && {
      temperature: options.temperature,
    }),
    ...(options.seed !== undefined && { seed: options.seed }),
  };

  // Build task function that wraps prompt formatting
  const task = _buildPromptEvaluationTask(
    model,
    options.messages,
    options.templateType ?? PromptType.MUSTACHE,
    {
      temperature: options.temperature,
      seed: options.seed,
    }
  );

  // Delegate to existing evaluate function
  return evaluate({
    dataset: options.dataset,
    task,
    scoringMetrics: options.scoringMetrics,
    experimentName: options.experimentName,
    projectName: options.projectName,
    experimentConfig,
    prompts: options.prompts,
    client: options.client,
    nbSamples: options.nbSamples,
    scoringKeyMapping: options.scoringKeyMapping,
  });
}

/**
 * 构建一个评估任务，用于格式化提示词模板并生成 LLM 响应。
 *
 * 此辅助函数创建一个任务函数，该函数：
 * 1. 使用数据集条目变量格式化消息模板
 * 2. 使用格式化的消息调用模型
 * 3. 提取并返回响应
 *
 * @param model - 用于生成的模型
 * @param messages - 包含占位符的消息模板
 * @param templateType - 模板引擎类型（mustache 或 jinja2）
 * @param modelOptions - 可选的模型生成参数（temperature、seed）
 * @returns 评估任务函数
 */
function _buildPromptEvaluationTask(
  model: OpikBaseModel,
  messages: OpikMessage[],
  templateType: PromptType,
  modelOptions?: { temperature?: number; seed?: number }
): EvaluationTask<Record<string, unknown>> {
  return async (datasetItem: Record<string, unknown>) => {
    // Apply template variables to each message
    const messagesWithVariables: OpikMessage[] = messages.map((message) =>
      applyTemplateVariablesToMessage(message, datasetItem, templateType)
    );

    // Generate response from model with optional temperature and seed
    const response = await model.generateProviderResponse(
      messagesWithVariables,
      modelOptions
    );

    // Extract text from provider response
    const outputText = extractResponseText(response);

    // Convert messages array to human-readable string for metrics
    // This ensures compatibility with metrics that expect string input
    const inputText = formatMessagesAsString(messagesWithVariables);

    return {
      input: inputText,
      output: outputText,
    };
  };
}

/**
 * 从提供商特定的响应对象中提取文本内容。
 *
 * 处理不同 LLM 提供商的各种响应格式：
 * - Vercel AI SDK: { text: string }
 * - 通用: { content: string }
 * - 对象: JSON 字符串化
 * - 原始值: 字符串转换
 *
 * @param response - 提供商特定的响应对象
 * @returns 提取的文本内容
 */
function extractResponseText(response: unknown): string {
  // Handle Vercel AI SDK response structure
  if (response && typeof response === "object") {
    if ("text" in response && typeof response.text === "string") {
      return response.text;
    }
    if ("content" in response && typeof response.content === "string") {
      return response.content;
    }
    return JSON.stringify(response);
  }

  return String(response);
}
