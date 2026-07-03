import { Dataset } from "../dataset/Dataset";
import { DatasetVersion } from "../dataset/DatasetVersion";
import {
  EvaluationResult,
  EvaluationTask,
  ScoringKeyMappingType,
} from "./types";
import { BaseMetric } from "./metrics/BaseMetric";
import { logger } from "@/utils/logger";
import { EvaluationEngine } from "./engine/EvaluationEngine";
import { OpikSingleton } from "@/client/SingletonClient";
import { DatasetItemData } from "../dataset/DatasetItem";
import { OpikClient } from "@/client/Client";
import type { Prompt } from "@/prompt/Prompt";

type DatasetOrVersion<T extends DatasetItemData> =
  | Dataset<T>
  | DatasetVersion<T>;

export interface EvaluateOptions<T = Record<string, unknown>> {
  /** 要评估的数据集或数据集版本，包含输入和预期输出 */
  dataset: DatasetOrVersion<T extends DatasetItemData ? T : DatasetItemData & T>;

  /** 要执行的特定 LLM 任务（如分类、生成、问答） */
  task: EvaluationTask<T>;

  /** 可选的指标数组，用于评估模型性能（如准确率、F1 分数） */
  scoringMetrics?: BaseMetric[];

  /** 此评估实验的可选名称，用于跟踪和报告 */
  experimentName?: string;

  /** 可选的项目标识符，用于关联此实验 */
  projectName?: string;

  /** 实验的可选配置设置，以键值对形式 */
  experimentConfig?: Record<string, unknown>;

  /** 可选的 Prompt 对象数组，用于与实验关联进行跟踪 */
  prompts?: Prompt[];

  /** 可选的要从数据集中评估的样本数量（如果未指定则默认为全部） */
  nbSamples?: number;

  /**
   * 可选的 Opik 客户端实例，用于跟踪
   */
  client?: OpikClient;

  /**
   * 可选的数据集键与评分指标输入之间的映射
   * 允许从数据集或任务输出重命名键以匹配指标期望的格式
   */
  scoringKeyMapping?: ScoringKeyMappingType;

  /** 可选的标签列表，用于与实验关联 */
  tags?: string[];

  /** 并发任务执行数量（默认：16，与 Python SDK 匹配） */
  taskThreads?: number;

  /** 可选的代理配置蓝图 ID，用于与实验关联 */
  blueprintId?: string;
}

export async function evaluate<T = Record<string, unknown>>(
  options: EvaluateOptions<T>
): Promise<EvaluationResult> {
  // Validate required parameters
  if (!options.dataset) {
    throw new Error("Dataset is required for evaluation");
  }

  if (!options.task) {
    throw new Error("Task function is required for evaluation");
  }

  // Wait for all prompts to be ready
  if (options.prompts) {
    await Promise.all(options.prompts.map((prompt) => prompt.ready()));
  }

  // Get Opik client
  const client = options.client ?? OpikSingleton.getInstance();

  // Resolve agent config blueprint if provided
  let experimentConfig = options.experimentConfig;
  if (options.blueprintId) {
    const agentConfig: Record<string, string> = { _blueprint_id: options.blueprintId };
    try {
      const blueprint = await client.api.agentConfigs.getBlueprintById(options.blueprintId);
      if (blueprint.name) agentConfig.blueprint_version = blueprint.name;
    } catch (error) {
      logger.debug(`Failed to fetch blueprint ${options.blueprintId}: ${error}`);
    }
    experimentConfig = { ...experimentConfig, agent_configuration: agentConfig };
  }

  // Get version info for experiment linking
  const versionInfo = await options.dataset.getVersionInfo();

  // Create experiment for this evaluation run
  const experiment = await client.createExperiment({
    name: options.experimentName,
    datasetName: options.dataset.name,
    experimentConfig,
    prompts: options.prompts,
    datasetVersionId: versionInfo?.id,
    tags: options.tags,
    projectName: options.projectName,
  });

  try {
    // Create and run the evaluation engine
    const engine = new EvaluationEngine<T>(options, client, experiment);

    logger.info("Starting evaluation");
    return engine.execute();
  } catch (error) {
    logger.error(`Error during evaluation: ${error}`);
    throw error;
  }
}
