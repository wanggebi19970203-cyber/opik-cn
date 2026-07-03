import { ConstructorOpikConfig, loadConfig, OpikConfig } from "@/config/Config";
import { isTracingActive } from "@/config/TracingRuntimeConfig";
import { OpikApiError, OpikApiTimeoutError, serialization } from "@/rest_api";
import type { ExperimentPublic, Trace as ITrace } from "@/rest_api/api";
import * as OpikApi from "@/rest_api/api";
import { FeedbackScoreBatchItemSource } from "@/rest_api/api/types/FeedbackScoreBatchItemSource";
import { Trace } from "@/tracer/Trace";
import type { FeedbackScoreData } from "@/tracer/types";
import { generateId } from "@/utils/generateId";
import { createLink, logger } from "@/utils/logger";
import { getProjectUrlByTraceId } from "@/utils/url";
import { AssertionResultsBatchQueue } from "./AssertionResultsBatchQueue";
import { SpanBatchQueue } from "./SpanBatchQueue";
import { SpanFeedbackScoresBatchQueue } from "./SpanFeedbackScoresBatchQueue";
import { TraceBatchQueue } from "./TraceBatchQueue";
import { TraceFeedbackScoresBatchQueue } from "./TraceFeedbackScoresBatchQueue";
import {
  OpikApiClientTemp,
  OpikApiClientTempOptions,
} from "@/client/OpikApiClientTemp";
import { DatasetBatchQueue } from "./DatasetBatchQueue";
import { Dataset, DatasetItemData, DatasetNotFoundError } from "@/dataset";
import type { TestSuite, CreateTestSuiteOptions } from "@/evaluation/suite";
import { Experiment } from "@/experiment/Experiment";
import { TestSuiteExperiment } from "@/experiment/TestSuiteExperiment";
import { buildMetadataAndPromptVersions } from "@/experiment/helpers";
import { ExperimentType } from "@/rest_api/api/types";
import { ExperimentNotFoundError } from "@/errors/experiment/errors";
import { parseNdjsonStreamToArray } from "@/utils/stream";
import {
  Prompt,
  CreatePromptOptions,
  GetPromptOptions,
  PromptType,
} from "@/prompt";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import { BasePrompt, PROMPT_SYNC_TIMEOUT_MS } from "@/prompt/BasePrompt";
import { PromptTemplateStructure, type CreateChatPromptOptions, type CommonPromptOptions } from "@/prompt/types";
import {
  EnvironmentNotFoundError,
  PromptNotFoundError,
  PromptTemplateStructureMismatch,
} from "@/prompt/errors";
import {
  fetchLatestPromptVersion,
  shouldCreateNewVersion,
} from "@/prompt/versionHelpers";
import { getOrFetch as promptCacheGetOrFetch, getGlobalCache } from "@/prompt/promptCache";
import { getActiveMaskForPrompt } from "@/prompt/maskContext";
import { OpikQueryLanguage } from "@/query";
import {
  searchTracesWithFilters,
  searchThreadsWithFilters,
  searchSpansWithFilters,
  searchAndWaitForDone,
  parseFilterString,
  parseThreadFilterString,
  parseSpanFilterString,
} from "@/utils/searchHelpers";
import { SearchTimeoutError } from "@/errors";
import {
  AnnotationQueueNotFoundError,
  TracesAnnotationQueue,
  ThreadsAnnotationQueue,
} from "@/annotation-queue";
import { ConfigManager } from "@/agent-config/ConfigManager";
import { Blueprint } from "@/agent-config/Blueprint";
import { serializeValuesRecord, deserializeFromBlueprint, type SupportedValue } from "@/typeHelpers";
import { createTypedConfig, type Config } from "@/agent-config/Config";
import { getActiveConfigMask, getActiveConfigBlueprintName } from "@/agent-config/configContext";
import {
  getCachedBlueprint,
  initBlueprintCacheEntry,
} from "@/agent-config/blueprintCache";
import { trackStorage, getTrackContext } from "@/decorators/track";
import { UpdateService } from "@/tracer/UpdateService";
import { ConfigNotFoundError, ConfigMismatchError } from "@/errors/agent-config/errors";
import {
  EnvironmentAlreadyExistsError,
  EnvironmentConfigurationError,
} from "@/errors/environment/errors";
import { DEFAULT_CONFIG } from "@/config/Config";

interface TraceData extends Omit<ITrace, "startTime"> {
  startTime?: Date;
}

interface AnnotationQueueOptions {
  name: string;
  projectName?: string;
  description?: string;
  instructions?: string;
  commentsEnabled?: boolean;
  feedbackDefinitionNames?: string[];
}

export const clients: OpikClient[] = [];

let defaultProjectWarningEmitted = false;

/** @internal 重置警告状态 - 仅用于测试。 */
export function resetDefaultProjectWarning() {
  defaultProjectWarningEmitted = false;
}

const AGENT_CONFIG_PROMPT_READY_TIMEOUT_MS = PROMPT_SYNC_TIMEOUT_MS + 500;

export class OpikClient {
  public api: OpikApiClientTemp;
  public config: OpikConfig;
  public spanBatchQueue: SpanBatchQueue;
  public traceBatchQueue: TraceBatchQueue;
  public spanFeedbackScoresBatchQueue: SpanFeedbackScoresBatchQueue;
  public traceFeedbackScoresBatchQueue: TraceFeedbackScoresBatchQueue;
  public traceAssertionResultsBatchQueue: AssertionResultsBatchQueue;
  public datasetBatchQueue: DatasetBatchQueue;

  private lastProjectNameLogged: string | undefined;

  constructor(explicitConfig?: Partial<ConstructorOpikConfig>) {
    logger.debug("Initializing OpikClient with config:", explicitConfig);

    this.config = loadConfig(explicitConfig);
    const apiConfig: OpikApiClientTempOptions = {
      apiKey: this.config.apiKey,
      environment: this.config.apiUrl,
      workspaceName: this.config.workspaceName,
    };

    if (explicitConfig?.headers) {
      logger.debug(
        "Initializing OpikClient with additional headers:",
        explicitConfig?.headers
      );

      apiConfig.requestOptions = {
        headers: explicitConfig?.headers,
      };
    }

    this.api = new OpikApiClientTemp(apiConfig);

    const delay = this.config.holdUntilFlush
      ? 24 * 60 * 60 * 1000
      : this.config.batchDelayMs;

    this.spanBatchQueue = new SpanBatchQueue(this.api, delay);
    this.traceBatchQueue = new TraceBatchQueue(this.api, delay);
    this.spanFeedbackScoresBatchQueue = new SpanFeedbackScoresBatchQueue(
      this.api,
      delay
    );
    this.traceFeedbackScoresBatchQueue = new TraceFeedbackScoresBatchQueue(
      this.api,
      delay
    );
    this.traceAssertionResultsBatchQueue = new AssertionResultsBatchQueue(
      this.api,
      delay,
      "TRACE"
    );
    this.datasetBatchQueue = new DatasetBatchQueue(this.api, delay);

    clients.push(this);
  }

  /**
   * 解析项目名称，若未提供则回退到客户端配置的项目名称。
   */
  public resolveProjectName(projectName?: string): string {
    if (projectName !== undefined) {
      return projectName;
    }

    if (
      !defaultProjectWarningEmitted &&
      this.config.projectName === DEFAULT_CONFIG.projectName
    ) {
      defaultProjectWarningEmitted = true;
      logger.warn(
        'No project name configured. Traces are being logged to "Default Project".\n' +
          "Set OPIK_PROJECT_NAME environment variable or pass projectName to the Opik client\n" +
          "to log to a specific project.\n" +
          "See https://www.comet.com/docs/opik/tracing/advanced/sdk_configuration"
      );
    }

    return this.config.projectName;
  }

  private displayTraceLog = (traceId: string, projectName: string) => {
    if (projectName === this.lastProjectNameLogged || !this.config.apiUrl) {
      return;
    }

    const projectUrl = getProjectUrlByTraceId(traceId, this.config.apiUrl);

    logger.info(
      `Started logging traces to the "${projectName}" project at ${createLink(projectUrl)}`
    );

    this.lastProjectNameLogged = projectName;
  };

  public trace = (traceData: TraceData) => {
    logger.debug("Creating new trace with data:", traceData);
    const projectName = this.resolveProjectName(traceData.projectName);
    const environment =
      traceData.environment !== undefined
        ? traceData.environment
        : this.config.environment;
    const trace = new Trace(
      {
        id: generateId(),
        startTime: new Date(),
        source: "sdk",
        ...traceData,
        projectName,
        ...(environment !== undefined ? { environment } : {}),
      },
      this
    );

    if (isTracingActive()) {
      this.traceBatchQueue.create(trace.data);
      logger.debug("Trace added to the queue with ID:", trace.data.id);
      this.displayTraceLog(trace.data.id, projectName);
    }

    return trace;
  };

  /**
   * 根据名称获取已有数据集
   *
   * @param name 要获取的数据集名称
   * @param projectName 可选的项目名称，用于限定数据集查找范围。若未提供，使用客户端配置的项目。
   * @returns 与指定名称关联的 Dataset 对象
   * @throws 若数据集不存在则抛出错误
   */
  public getDataset = async <T extends DatasetItemData = DatasetItemData>(
    name: string,
    projectName?: string
  ): Promise<Dataset<T>> => {
    const resolvedProjectName = this.resolveProjectName(projectName);
    logger.debug(`Getting dataset with name "${name}"`);
    try {
      // TODO 需要更新 Batch 类以支持使用名称代替 ID 并从中获取
      await this.datasetBatchQueue.flush();

      const response = await this.api.datasets.getDatasetByIdentifier({
        datasetName: name,
        projectName: resolvedProjectName,
      });

      return new Dataset<T>({ ...response, projectName: resolvedProjectName }, this);
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        throw new DatasetNotFoundError(name);
      }
      throw error;
    }
  };

  /**
   * 使用给定名称和可选描述创建新数据集
   *
   * @param name 数据集名称
   * @param description 数据集的可选描述
   * @param projectName 可选的项目名称，用于限定数据集范围。若未提供，使用客户端配置的项目。
   * @returns 创建的 Dataset 对象
   */
  public createDataset = async <T extends DatasetItemData = DatasetItemData>(
    name: string,
    description?: string,
    projectName?: string
  ): Promise<Dataset<T>> => {
    const resolvedProjectName = this.resolveProjectName(projectName);
    logger.debug(`Creating dataset with name "${name}"`);

    const entity = new Dataset<T>({ name, description, projectName: resolvedProjectName }, this);

    try {
      this.datasetBatchQueue.create({
        name: entity.name,
        description: entity.description,
        id: entity.id,
        projectName: resolvedProjectName,
      });

      logger.debug("Dataset added to the queue with name:", entity.name);

      return entity;
    } catch (error) {
      logger.error(`Failed to create dataset "${name}"`, { error });
      throw new Error(`Error creating dataset "${name}": ${error}`);
    }
  };

  /**
   * 根据名称获取已有数据集，若不存在则创建新数据集。
   *
   * @param name 数据集名称
   * @param description 数据集的可选描述（创建时使用）
   * @param projectName 可选的项目名称，用于限定数据集范围。若未提供，使用客户端配置的项目。
   * @returns 解析为已有或新创建的 Dataset 对象的 Promise
   */
  public getOrCreateDataset = async <
    T extends DatasetItemData = DatasetItemData,
  >(
    name: string,
    description?: string,
    projectName?: string
  ): Promise<Dataset<T>> => {
    logger.debug(
      `Attempting to retrieve or create dataset with name: "${name}"`
    );

    try {
      return await this.getDataset(name, projectName);
    } catch (error) {
      if (error instanceof DatasetNotFoundError) {
        logger.info(
          `Dataset "${name}" not found. Proceeding to create a new one.`
        );
        return this.createDataset(name, description, projectName);
      }
      logger.error(`Error retrieving dataset "${name}":`, error);
      throw error;
    }
  };

  /**
   * 返回指定数量限制内的所有数据集
   *
   * @param maxResults 返回数据集的最大数量（默认：100）
   * @param projectName 可选的项目名称，用于筛选数据集。若未提供，使用客户端配置的项目。
   * @returns Dataset 对象列表
   */
  public getDatasets = async <T extends DatasetItemData = DatasetItemData>(
    maxResults: number = 100,
    projectName?: string
  ): Promise<Dataset<T>[]> => {
    const resolvedProjectName = this.resolveProjectName(projectName);
    logger.debug(`Getting all datasets (limit: ${maxResults})`);

    try {
      // 先刷新队列以确保所有待处理的数据集已创建
      await this.datasetBatchQueue.flush();

      let projectId: string | undefined;
      try {
        projectId = await this.getProjectIdByName(resolvedProjectName);
      } catch {
        // 项目尚不存在 — 不使用项目过滤器进行列表查询
      }

      const response = await this.api.datasets.findDatasets({
        size: maxResults,
        ...(projectId && { projectId }),
      });

      const datasets: Dataset<T>[] = [];

      for (const datasetData of response.content || []) {
        datasets.push(new Dataset<T>({ ...datasetData, projectName: resolvedProjectName }, this));
      }

      logger.info(`Retrieved ${datasets.length} datasets`);
      return datasets;
    } catch (error) {
      logger.error("Failed to retrieve datasets", { error });
      throw new Error("Failed to retrieve datasets");
    }
  };

  /**
   * 根据名称删除数据集
   *
   * @param name 要删除的数据集名称
   * @param projectName 可选的项目名称，用于限定数据集查找范围。若未提供，使用客户端配置的项目。
   */
  public deleteDataset = async (name: string, projectName?: string): Promise<void> => {
    logger.debug(`Deleting dataset with name "${name}"`);

    try {
      const dataset = await this.getDataset(name, projectName);
      if (!dataset.id) {
        throw new Error(`Cannot delete dataset "${name}": ID not available`);
      }

      this.datasetBatchQueue.delete(dataset.id);
    } catch (error) {
      logger.error(`Failed to delete dataset "${name}"`, { error });
      throw new Error(`Failed to delete dataset "${name}": ${error}`);
    }
  };

  /**
   * 使用给定选项创建新的测试套件。
   *
   * @param options - 创建测试套件的选项
   * @returns 创建的 TestSuite 对象
   */
  public createTestSuite = async (
    options: CreateTestSuiteOptions
  ): Promise<TestSuite> => {
    logger.debug(`Creating test suite with name "${options.name}"`);
    const { TestSuite } = await import("@/evaluation/suite");
    return TestSuite.create(this, options);
  };

  /**
   * 根据名称获取已有测试套件。
   *
   * @param name 要获取的测试套件名称
   * @param projectName 可选的项目名称，用于限定查找范围。若未提供，使用客户端配置的项目。
   * @returns TestSuite 对象
   * @throws 若测试套件不存在则抛出 DatasetNotFoundError
   */
  public getTestSuite = async (
    name: string,
    projectName?: string
  ): Promise<TestSuite> => {
    const resolvedProjectName = this.resolveProjectName(projectName);
    logger.debug(`Getting test suite with name "${name}"`);
    const { TestSuite } = await import("@/evaluation/suite");
    return TestSuite.get(this, name, resolvedProjectName);
  };

  /**
   * 根据名称获取已有测试套件，若不存在则创建新的。
   *
   * @param options - 测试套件不存在时用于创建的选项
   * @returns TestSuite 对象（已有或新创建的）
   */
  public getOrCreateTestSuite = async (
    options: CreateTestSuiteOptions
  ): Promise<TestSuite> => {
    logger.debug(
      `Attempting to retrieve or create test suite with name: "${options.name}"`
    );
    const { TestSuite } = await import("@/evaluation/suite");
    return TestSuite.getOrCreate(this, options);
  };

  /**
   * 根据名称删除测试套件。
   *
   * @param name 要删除的测试套件名称
   * @param projectName 可选的项目名称，用于限定查找范围。若未提供，使用客户端配置的项目。
   */
  public deleteTestSuite = async (
    name: string,
    projectName?: string
  ): Promise<void> => {
    const resolvedProjectName = this.resolveProjectName(projectName);
    logger.debug(`Deleting test suite with name "${name}"`);
    const { TestSuite } = await import("@/evaluation/suite");
    await TestSuite.delete(this, name, resolvedProjectName);
  };

  /**
   * 返回指定数量限制内的所有测试套件。
   *
   * @param maxResults 返回测试套件的最大数量（默认：100）
   * @param projectName 可选的项目名称，用于筛选。若未提供，使用客户端配置的项目。
   * @returns TestSuite 对象列表
   */
  public getTestSuites = async (
    maxResults: number = 1000,
    projectName?: string
  ): Promise<TestSuite[]> => {
    const resolvedProjectName = this.resolveProjectName(projectName);
    logger.debug(`Getting all test suites (limit: ${maxResults})`);

    try {
      await this.datasetBatchQueue.flush();

      const projectId = await this.resolveProjectId(resolvedProjectName);
      const { TestSuite } = await import("@/evaluation/suite");

      const suites: TestSuite[] = [];
      let page = 1;
      const pageSize = 100;

      while (suites.length < maxResults) {
        const response = await this.api.datasets.findDatasets({
          page,
          size: pageSize,
          ...(projectId && { projectId }),
        });

        const content = response.content ?? [];
        if (content.length === 0) break;

        for (const datasetData of content) {
          if (suites.length >= maxResults) break;
          if (datasetData.type !== OpikApi.DatasetPublicType.EvaluationSuite) continue;
          suites.push(
            new TestSuite(
              new Dataset({ ...datasetData, projectName: resolvedProjectName }, this),
              this
            )
          );
        }

        page++;
      }

      logger.info(`Retrieved ${suites.length} test suites`);
      return suites;
    } catch (error) {
      logger.error("Failed to retrieve test suites", { error });
      throw new Error("Failed to retrieve test suites");
    }
  };

  private async getProjectIdByName(projectName: string): Promise<string> {
    const project = await this.api.projects.retrieveProject({
      name: projectName,
    });

    if (!project?.id) {
      throw new Error(`Project "${projectName}" not found`);
    }
    return project.id;
  }

  /**
   * 将项目名称解析为对应的 ID。
   * 若 projectName 为 undefined 则返回 undefined（不发起 API 调用）。
   * API 错误会向上抛出 — 与 Python 的 resolve_project_id_by_name_optional() 行为一致。
   */
  private async resolveProjectId(projectName: string | undefined): Promise<string | undefined> {
    if (projectName === undefined) {
      return undefined;
    }
    return this.getProjectIdByName(projectName);
  }

  private async createAnnotationQueueInternal<T extends TracesAnnotationQueue | ThreadsAnnotationQueue>(
    options: AnnotationQueueOptions,
    QueueClass: (new (data: OpikApi.AnnotationQueuePublic, opik: OpikClient) => T) & {
      readonly SCOPE: "trace" | "thread";
    }
  ): Promise<T> {
    const {
      name,
      projectName,
      description,
      instructions,
      commentsEnabled,
      feedbackDefinitionNames,
    } = options;

    const scope = QueueClass.SCOPE;

    logger.debug(`Creating ${scope} annotation queue "${name}"`);

    const targetProjectName = projectName ?? this.config.projectName;

    try {
      const projectId = await this.getProjectIdByName(targetProjectName);
      const queueId = generateId();

      await this.api.annotationQueues.createAnnotationQueue({
        id: queueId,
        projectId,
        name,
        scope,
        description,
        instructions,
        commentsEnabled,
        feedbackDefinitionNames,
      });

      logger.debug(`Created ${scope} annotation queue "${name}" with ID "${queueId}"`);

      return new QueueClass(
        {
          id: queueId,
          name,
          projectId,
          scope,
          description,
          instructions,
          commentsEnabled,
          feedbackDefinitionNames,
        },
        this
      );
    } catch (error) {
      logger.error(`Failed to create ${scope} annotation queue "${name}"`, { error });
      throw error;
    }
  }

  /**
   * 创建新的追踪标注队列，用于人工标注工作流。
   *
   * @param options - 标注队列的配置选项
   * @param options.name - 标注队列的名称
   * @param options.projectName - 可选的项目名称（默认使用客户端配置的项目）
   * @param options.description - 队列的可选描述
   * @param options.instructions - 审核者的可选说明
   * @param options.commentsEnabled - 启用/禁用评论的可选标志
   * @param options.feedbackDefinitionNames - 反馈定义名称的可选列表
   * @returns 创建的 TracesAnnotationQueue 对象
   */
  public createTracesAnnotationQueue = async (options: AnnotationQueueOptions): Promise<TracesAnnotationQueue> => {
    return this.createAnnotationQueueInternal(options, TracesAnnotationQueue);
  };

  /**
   * 创建新的线程标注队列，用于人工标注工作流。
   *
   * @param options - 标注队列的配置选项
   * @param options.name - 标注队列的名称
   * @param options.projectName - 可选的项目名称（默认使用客户端配置的项目）
   * @param options.description - 队列的可选描述
   * @param options.instructions - 审核者的可选说明
   * @param options.commentsEnabled - 启用/禁用评论的可选标志
   * @param options.feedbackDefinitionNames - 反馈定义名称的可选列表
   * @returns 创建的 ThreadsAnnotationQueue 对象
   */
  public createThreadsAnnotationQueue = async (options: AnnotationQueueOptions): Promise<ThreadsAnnotationQueue> => {
    return this.createAnnotationQueueInternal(options, ThreadsAnnotationQueue);
  };

  private async fetchAnnotationQueueById<T extends TracesAnnotationQueue | ThreadsAnnotationQueue>(
    id: string,
    expectedScope: "trace" | "thread",
    QueueClass: new (data: OpikApi.AnnotationQueuePublic, opik: OpikClient) => T
  ): Promise<T> {
    logger.debug(`Getting ${expectedScope} annotation queue with ID "${id}"`);

    try {
      const response = await this.api.annotationQueues.getAnnotationQueueById(id);

      if (response.scope !== expectedScope) {
        throw new Error(`Annotation queue "${id}" is not a ${expectedScope} queue (scope: ${response.scope})`);
      }

      return new QueueClass(response, this);
    } catch (error) {
      if (error instanceof OpikApiError) {
        if (error.statusCode === 404) {
          throw new AnnotationQueueNotFoundError(id);
        }
        logger.error(`Failed to get ${expectedScope} annotation queue with ID "${id}"`, { error });
      }
      throw error;
    }
  }

  /**
   * 根据 ID 获取追踪标注队列。
   *
   * @param id - 标注队列的唯一标识符
   * @returns TracesAnnotationQueue 对象
   * @throws 若队列不存在或不是追踪队列则抛出 AnnotationQueueNotFoundError
   */
  public getTracesAnnotationQueue = async (id: string): Promise<TracesAnnotationQueue> => {
    return this.fetchAnnotationQueueById(id, "trace", TracesAnnotationQueue);
  };

  /**
   * 根据 ID 获取线程标注队列。
   *
   * @param id - 标注队列的唯一标识符
   * @returns ThreadsAnnotationQueue 对象
   * @throws 若队列不存在或不是线程队列则抛出 AnnotationQueueNotFoundError
   */
  public getThreadsAnnotationQueue = async (id: string): Promise<ThreadsAnnotationQueue> => {
    return this.fetchAnnotationQueueById(id, "thread", ThreadsAnnotationQueue);
  };

  /**
   * 获取所有追踪标注队列，可按项目筛选。
   *
   * @param options - 可选配置
   * @param options.projectName - 可选的项目名称筛选条件
   * @param options.maxResults - 返回结果的最大数量（默认：1000）
   * @returns TracesAnnotationQueue 对象列表
   */
  public getTracesAnnotationQueues = async (options?: {
    projectName?: string;
    maxResults?: number;
  }): Promise<TracesAnnotationQueue[]> => {
    const queues = await this.getAnnotationQueuesByScope("trace", options);
    return queues.map(queueData => new TracesAnnotationQueue(queueData, this));
  };

  /**
   * 获取所有线程标注队列，可按项目筛选。
   *
   * @param options - 可选配置
   * @param options.projectName - 可选的项目名称筛选条件
   * @param options.maxResults - 返回结果的最大数量（默认：1000）
   * @returns ThreadsAnnotationQueue 对象列表
   */
  public getThreadsAnnotationQueues = async (options?: {
    projectName?: string;
    maxResults?: number;
  }): Promise<ThreadsAnnotationQueue[]> => {
    const queues = await this.getAnnotationQueuesByScope("thread", options);
    return queues.map(queueData => new ThreadsAnnotationQueue(queueData, this));
  };

  private async getAnnotationQueuesByScope(
    scope: "trace" | "thread",
    options?: {
      projectName?: string;
      maxResults?: number;
    }
  ): Promise<OpikApi.AnnotationQueuePublic[]> {
    const { projectName, maxResults = 1000 } = options ?? {};

    logger.debug(
      `Getting ${scope} annotation queues (project: ${projectName ?? "all"}, limit: ${maxResults})`
    );

    try {
      let filters: string | undefined;

      if (projectName) {
        const projectId = await this.getProjectIdByName(projectName);
        filters = JSON.stringify([
          { field: "project_id", operator: "=", value: projectId },
          { field: "scope", operator: "=", value: scope },
        ]);
      } else {
        filters = JSON.stringify([
          { field: "scope", operator: "=", value: scope },
        ]);
      }

      const response = await this.api.annotationQueues.findAnnotationQueues({
        size: maxResults,
        filters,
      });

      const queues = response.content || [];
      logger.info(`Retrieved ${queues.length} ${scope} annotation queues`);
      return queues;
    } catch (error) {
      logger.error(`Failed to retrieve ${scope} annotation queues`, { error });
      throw error;
    }
  }

  private async deleteAnnotationQueueById(id: string, scope: "traces" | "threads"): Promise<void> {
    logger.debug(`Deleting ${scope} annotation queue with ID "${id}"`);

    try {
      await this.api.annotationQueues.deleteAnnotationQueueBatch({
        ids: [id],
      });

      logger.debug(`Successfully deleted ${scope} annotation queue with ID "${id}"`);
    } catch (error) {
      logger.error(`Failed to delete ${scope} annotation queue with ID "${id}"`, {
        error,
      });
      throw error;
    }
  }

  /**
   * 根据 ID 删除追踪标注队列。
   *
   * @param id - 要删除的追踪标注队列的 ID
   */
  public deleteTracesAnnotationQueue = async (id: string): Promise<void> => {
    return this.deleteAnnotationQueueById(id, "traces");
  };

  /**
   * 根据 ID 删除线程标注队列。
   *
   * @param id - 要删除的线程标注队列的 ID
   */
  public deleteThreadsAnnotationQueue = async (id: string): Promise<void> => {
    return this.deleteAnnotationQueueById(id, "threads");
  };

  /**
   * 使用给定数据集名称和可选参数创建新实验
   *
   * @param datasetName 与实验关联的数据集名称
   * @param name 实验的可选名称（若未提供，将使用生成的名称）
   * @param experimentConfig 实验的可选配置参数
   * @param prompts 与实验关联的可选 Prompt 对象数组
   * @param type 可选的实验类型（默认为 "regular"）
   * @param optimizationId 与实验关联的优化的可选 ID
   * @param datasetVersionId 关联实验的数据集版本的可选 ID
   * @param evaluationMethod @internal 由测试套件使用 - 不属于公共 API
   * @returns 创建的 Experiment 对象
   */
  public createExperiment = async ({
    datasetName,
    name,
    experimentConfig,
    prompts,
    type = ExperimentType.Regular,
    optimizationId,
    datasetVersionId,
    evaluationMethod,
    tags,
    projectName,
  }: {
    datasetName: string;
    name?: string;
    experimentConfig?: Record<string, unknown>;
    prompts?: Prompt[];
    type?: ExperimentType;
    optimizationId?: string;
    datasetVersionId?: string;
    evaluationMethod?: OpikApi.ExperimentWriteEvaluationMethod;
    tags?: string[];
    projectName?: string;
  }): Promise<Experiment> => {
    logger.debug(`Creating experiment for dataset "${datasetName}"`);

    if (!datasetName) {
      throw new Error("Dataset name is required to create an experiment");
    }

    // 处理提示词并构建元数据
    const [metadata, promptVersions] = buildMetadataAndPromptVersions(
      experimentConfig,
      prompts
    );

    const resolvedProjectName = this.resolveProjectName(projectName);
    const id = generateId();
    const experiment = new Experiment({ id, name, datasetName, prompts, tags, projectName: resolvedProjectName }, this);

    try {
      await this.api.experiments.createExperiment({
        id,
        datasetName,
        name,
        metadata,
        promptVersions,
        type,
        optimizationId,
        datasetVersionId,
        tags,
        evaluationMethod,
        projectName: resolvedProjectName,
      });

      logger.debug("Experiment created with id:", id);
      return experiment;
    } catch (error) {
      logger.error(`Failed to create experiment for dataset "${datasetName}"`, {
        error,
      });
      throw new Error(`Error creating experiment: ${error}`);
    }
  };

    /**
     * 根据 ID 更新实验
     *
     * @param id 实验的 ID
     * @param experimentUpdate 包含要更新字段的对象
     * @param experimentUpdate.name 实验的可选新名称
     * @param experimentUpdate.experimentConfig 实验的可选新配置
     * @returns 实验更新完成时解析的 Promise
     * @throws {Error} 若未提供 id 或既未提供 name 也未提供 experimentConfig
     */
    public updateExperiment = async (
        id: string,
        experimentUpdate: {
            name?: string;
            experimentConfig?: Record<string, unknown>;
        }
    ): Promise<void> => {
        if (!id) {
            throw new Error("id is required to update an experiment");
        }

        const { name, experimentConfig } = experimentUpdate;

        if (!name && !experimentConfig) {
            throw new Error("At least one of 'name' or 'experimentConfig' must be provided to update an experiment");
        }

        logger.debug(`Updating experiment with ID "${id}"`);

        // 仅包含已提供的参数以避免清除字段
        const request: OpikApi.ExperimentUpdate = {};
        if (name !== undefined) {
            request.name = name;
        }
        if (experimentConfig !== undefined) {
            request.metadata = experimentConfig;
        }

        try {
            await this.api.experiments.updateExperiment(id, { body: request });
        } catch (error) {
            logger.error(`Failed to update experiment with ID "${id}"`, { error });
            throw error;
        }
    };

  /**
   * 根据唯一 ID 获取实验
   *
   * @param id 实验的唯一标识符
   * @returns Experiment 对象
   */
  public getExperimentById = async (id: string): Promise<Experiment> => {
    logger.debug(`Getting experiment with ID "${id}"`);

    try {
      const experimentData = await this.api.experiments.getExperimentById(id);

      return new Experiment(
        {
          id: experimentData.id,
          name: experimentData.name,
          datasetName: experimentData.datasetName ?? undefined,
          projectName: experimentData.projectName ?? undefined,
        },
        this
      );
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        throw new ExperimentNotFoundError(
          `No experiment found with ID '${id}'`
        );
      }
      logger.error(`Failed to get experiment with ID "${id}"`, { error });
      throw error;
    }
  };

  /**
   * 根据名称获取实验（可返回同名的多个实验）
   *
   * @param name 要获取的实验名称
   * @returns 具有给定名称的 Experiment 对象列表
   */
  public getExperimentsByName = async (name: string, projectName?: string): Promise<Experiment[]> => {
    const resolvedProjectName = this.resolveProjectName(projectName);
    logger.debug(`Getting experiments with name "${name}"`);

    try {
      const streamResponse = await this.api.experiments.streamExperiments({
        name,
        projectName: resolvedProjectName,
      });

      const rawItems = await parseNdjsonStreamToArray<ExperimentPublic>(
        streamResponse,
        serialization.ExperimentPublic
      );

      return rawItems.map(
        (exp) =>
          new Experiment(
            {
              id: exp.id,
              name: exp.name,
              datasetName: exp.datasetName ?? undefined,
              projectName: exp.projectName ?? undefined,
            },
            this
          )
      );
    } catch (error) {
      logger.error(`Failed to get experiments with name "${name}"`, { error });
      throw error;
    }
  };

  /**
   * 根据名称获取单个实验（若存在多个则返回第一个匹配项）
   *
   * @param name 要获取的实验名称
   * @returns Experiment 对象
   */
  public getExperiment = async (name: string, projectName?: string): Promise<Experiment> => {
    logger.debug(`Getting experiment with name "${name}"`);

    const experiments = await this.getExperimentsByName(name, projectName);

    if (experiments.length === 0) {
      throw new ExperimentNotFoundError(name);
    }

    return experiments[0];
  };

  /**
   * 获取与数据集关联的所有实验
   *
   * @param datasetName 数据集的名称
   * @param maxResults 返回实验的最大数量（默认：100）
   * @param projectName 可选的项目名称，用于限定数据集查找范围。若未提供，使用客户端配置的项目。
   * @returns 与数据集关联的 Experiment 对象列表
   * @throws {DatasetNotFoundError} 若数据集不存在
   */
  public getDatasetExperiments = async (
    datasetName: string,
    maxResults: number = 100,
    projectName?: string
  ): Promise<Experiment[]> => {
    logger.debug(`Getting experiments for dataset "${datasetName}"`);

    const dataset = await this.getDataset(datasetName, projectName);

    try {
      return await this.findExperimentsByDatasetId(
        dataset.id,
        maxResults,
        (exp) =>
          new Experiment(
            {
              id: exp.id,
              name: exp.name,
              datasetName: exp.datasetName ?? undefined,
            },
            this
          )
      );
    } catch (error) {
      logger.error(`Failed to get experiments for dataset "${datasetName}"`, {
        error,
      });
      throw error;
    }
  };

  /**
   * 获取与测试套件关联的所有实验。
   *
   * @param name 测试套件的名称
   * @param maxResults 返回实验的最大数量（默认：100）
   * @param projectName 可选的项目名称，用于限定套件查找范围。若未提供，使用客户端配置的项目。
   * @returns 与测试套件关联的 TestSuiteExperiment 对象列表，
   *   每个对象携带由后端填充的套件特定断言聚合数据（`passRate`、`passedCount`、
   *   `totalCount`、`assertionScores`）。
   * @throws {DatasetNotFoundError} 若测试套件不存在
   */
  public getTestSuiteExperiments = async (
    name: string,
    maxResults: number = 100,
    projectName?: string
  ): Promise<TestSuiteExperiment[]> => {
    logger.debug(`Getting experiments for test suite "${name}"`);

    const suiteDataset = await this.getDataset(name, projectName);

    try {
      return await this.findExperimentsByDatasetId(
        suiteDataset.id,
        maxResults,
        (exp) =>
          new TestSuiteExperiment(
            {
              id: exp.id,
              name: exp.name,
              datasetName: exp.datasetName ?? undefined,
              passRate: exp.passRate,
              passedCount: exp.passedCount,
              totalCount: exp.totalCount,
              assertionScores: exp.assertionScores,
            },
            this
          )
      );
    } catch (error) {
      logger.error(`Failed to get experiments for test suite "${name}"`, {
        error,
      });
      throw error;
    }
  };

  /**
   * 分页获取指定数据集 ID 的实验，将每个原始 `ExperimentPublic` 行映射为
   * 调用方选择的实体。由 `getDatasetExperiments` 和 `getTestSuiteExperiments`
   * 内部使用，共享相同的循环结构，仅在构造的类型上有所不同。
   */
  private findExperimentsByDatasetId = async <T>(
    datasetId: string,
    maxResults: number,
    factory: (exp: ExperimentPublic) => T
  ): Promise<T[]> => {
    const pageSize = Math.min(100, maxResults);
    const experiments: T[] = [];
    let page = 1;

    while (experiments.length < maxResults) {
      const pageExperiments = await this.api.experiments.findExperiments({
        page,
        size: pageSize,
        datasetId,
      });

      const content = pageExperiments?.content ?? [];

      if (content.length === 0) {
        break;
      }

      const remainingItems = maxResults - experiments.length;
      const itemsToProcess = Math.min(content.length, remainingItems);

      for (let i = 0; i < itemsToProcess; i++) {
        experiments.push(factory(content[i]));
      }

      if (itemsToProcess < content.length) {
        break;
      }

      page += 1;
    }

    return experiments;
  };

  /**
   * 根据 ID 删除实验
   *
   * @param id 要删除的实验的 ID
   */
  public deleteExperiment = async (id: string): Promise<void> => {
    logger.debug(`Deleting experiment with ID "${id}"`);

    try {
      await this.api.experiments.deleteExperimentsById({ ids: [id] });
    } catch (error) {
      logger.error(`Failed to delete experiment with ID "${id}"`, { error });
      throw error;
    }
  };

  /**
   * 创建提示词（文本或聊天）的内部辅助方法。
   * 处理通用逻辑：版本检查、创建和属性更新。
   *
   * @param name - 提示词名称
   * @param template - 模板字符串（原始文本或 JSON 序列化的消息）
   * @param templateStructure - Text 或 Chat 结构
   * @param options - 通用提示词选项（metadata、type、description、tags）
   * @param validateStructure - 用于验证模板结构与已有提示词是否匹配的回调
   * @param createInstance - 创建 Prompt 或 ChatPrompt 实例的工厂函数
   * @param logContext - 日志上下文字符串（如 "prompt" 或 "chat prompt"）
   * @returns 解析为 Prompt 或 ChatPrompt 实例的 Promise
   */
  private createPromptInternal = async <T extends Prompt | ChatPrompt>(
    name: string,
    template: string,
    templateStructure: PromptTemplateStructure,
    options: CommonPromptOptions,
    validateStructure: (latest: OpikApi.PromptVersionDetail | null) => void,
    createInstance: (
      promptData: OpikApi.PromptPublic,
      versionData: OpikApi.PromptVersionDetail
    ) => T,
    createUnsyncedInstance: () => T,
    logContext: string,
    projectName?: string
  ): Promise<T> => {
    logger.debug(`Creating ${logContext}`, { name });

    try {
      // 获取最新版本（若提示词不存在则返回 null）
      const latestVersion = await fetchLatestPromptVersion(
        this.api.prompts,
        name,
        this.api.requestOptions
      );

      // 验证模板结构与已有提示词是否匹配
      validateStructure(latestVersion);

      // 判断是否需要创建新版本
      const normalizedType = options.type ?? PromptType.MUSTACHE;
      const needsNewVersion = shouldCreateNewVersion(
        { prompt: template, metadata: options.metadata },
        latestVersion,
        normalizedType
      );

      let versionResponse: OpikApi.PromptVersionDetail;

      if (needsNewVersion) {
        // 创建新版本
        logger.debug(`Creating new ${logContext} version`, { name });
        versionResponse = await this.api.prompts.createPromptVersion(
          {
            name,
            version: {
              template,
              metadata: options.metadata,
              type: normalizedType,
            },
            templateStructure,
            projectName,
          },
          this.api.requestOptions
        );
      } else {
        // 返回已有版本（幂等操作）
        logger.debug(`Returning existing ${logContext} version`, { name });
        versionResponse = latestVersion!;
      }

      // 获取完整的提示词数据并创建实例
      if (!versionResponse.promptId) {
        throw new Error("Invalid API response: missing promptId");
      }

      const promptData = await this.api.prompts.getPromptById(
        versionResponse.promptId,
        {},
        this.api.requestOptions
      );

      const promptInstance = createInstance(promptData, versionResponse) as T;

      logger.debug(`${logContext} created`, { name });

      // 若提供了属性则更新
      if (options.description || options.tags) {
        return (await promptInstance.updateProperties({
          description: options.description,
          tags: options.tags,
        })) as T;
      }

      return promptInstance;
    } catch (error) {
      if (error instanceof OpikApiError || error instanceof OpikApiTimeoutError) {
        logger.warn(
          `Failed to sync ${logContext} '${name}' with the backend. ` +
            "The prompt will work locally but is not persisted on the server. " +
            "You can retry by calling .syncWithBackend().",
          { error }
        );
        return createUnsyncedInstance();
      }
      logger.error(`Failed to create ${logContext}`, { name, error });
      throw error;
    }
  };

  /**
   * 创建新提示词或在内容不同时创建新版本。
   *
   * 核心行为：
   * - 智能版本控制：仅在模板、元数据或类型与最新版本不同时创建新版本
   * - 幂等性：若内容相同则返回已有版本（不会创建重复版本）
   * - 404 处理：优雅处理首次提示词创建
   * - 使用 create_prompt_version 端点（而非用于容器的 create_prompt）
   * - 同步操作：立即返回创建/获取的版本
   *
   * @param options - 提示词配置
   * @returns 解析为 Prompt 实例的 Promise
   * @throws 若参数无效则抛出 PromptValidationError
   */
  public createPrompt = async (
    options: CreatePromptOptions
  ): Promise<Prompt> => {
    const resolvedProjectName = this.resolveProjectName(options.projectName);
    return this.createPromptInternal(
      options.name,
      options.prompt,
      PromptTemplateStructure.Text,
      options,
      () => {
        // 文本提示词无需结构验证
      },
      (promptData, versionData) =>
        Prompt.fromApiResponse(promptData, versionData, this, resolvedProjectName),
      () =>
        new Prompt(
          {
            name: options.name,
            prompt: options.prompt,
            metadata: options.metadata,
            type: options.type ?? PromptType.MUSTACHE,
            description: options.description,
            tags: options.tags,
            projectName: resolvedProjectName,
            synced: false,
          },
          this
        ),
      "prompt",
      resolvedProjectName
    );
  };

  /**
   * 创建新的聊天提示词，若内容相同则返回已有的。
   * 聊天提示词使用消息数组而非字符串模板。
   * 幂等性：若消息、元数据和类型匹配则返回已有版本。
   *
   * @param options - 包含消息数组的聊天提示词配置
   * @returns 解析为 ChatPrompt 实例的 Promise
   * @throws 若存在同名的文本提示词则抛出 PromptTemplateStructureMismatch
   *
   * @example
   * ```typescript
   * const chatPrompt = await client.createChatPrompt({
   *   name: "assistant-prompt",
   *   messages: [
   *     { role: "system", content: "You are a helpful assistant" },
   *     { role: "user", content: "Help me with {{task}}" }
   *   ],
   *   type: "mustache"
   * });
   * ```
   */
  public createChatPrompt = async (
    options: CreateChatPromptOptions
  ): Promise<ChatPrompt> => {
    const resolvedProjectName = this.resolveProjectName(options.projectName);
    // 将消息序列化为 JSON 用于后端存储
    const messagesJson = JSON.stringify(options.messages);

    return this.createPromptInternal(
      options.name,
      messagesJson,
      PromptTemplateStructure.Chat,
      options,
      (latestVersion) => {
        // 检查模板结构是否不匹配
        if (
          latestVersion &&
          latestVersion.templateStructure &&
          latestVersion.templateStructure !== PromptTemplateStructure.Chat
        ) {
          throw new PromptTemplateStructureMismatch(
            options.name,
            latestVersion.templateStructure,
            PromptTemplateStructure.Chat
          );
        }
      },
      (promptData, versionData) =>
        ChatPrompt.fromApiResponse(promptData, versionData, this, resolvedProjectName),
      () =>
        new ChatPrompt(
          {
            name: options.name,
            messages: structuredClone(options.messages),
            metadata: options.metadata,
            type: options.type ?? PromptType.MUSTACHE,
            description: options.description,
            tags: options.tags,
            projectName: resolvedProjectName,
            synced: false,
          },
          this
        ),
      "chat prompt",
      resolvedProjectName
    );
  };

  /**
   * 根据名称获取文本提示词，可选择指定特定 `version`。
   * 结果在客户端缓存（TTL 可通过 OPIK_PROMPT_CACHE_TTL_SECONDS 配置，
   * 默认 300 秒）。在 track() 上下文内调用时，提示词引用会被注入到
   * 当前活跃的 trace/span 元数据中。
   *
   * @param options - 提示词名称和可选的版本锁定或环境
   * @param options.name - 提示词的名称
   * @param options.version - 顺序版本标识符（如 `"v3"`）。若未提供，
   *   则返回最新版本。
   * @param options.commit - **已弃用。** 请使用 `version` 替代。
   * @param options.projectName - 可选的项目范围。
   * @param options.environment - 可选的环境名称。解析为该工作区环境当前
   *   拥有的版本。与 `commit` 和 `version` 互斥。
   * @returns 解析为 Prompt 或未找到时为 null 的 Promise
   * @throws 若同时提供了 `commit` 和 `version`，或 `environment` 与
   *   `commit` 或 `version` 组合使用则抛出错误
   * @throws 若提示词存在但为聊天提示词则抛出 PromptTemplateStructureMismatch
   */
  public getPrompt = async (
    options: GetPromptOptions
  ): Promise<Prompt | null> => {
    return this.getPromptWithCache<Prompt>(
      options,
      PromptTemplateStructure.Text,
      (promptData, versionData, projectName) =>
        Prompt.fromApiResponse(promptData, versionData, this, projectName),
      "prompt"
    );
  };

  /**
   * 根据名称获取聊天提示词，可选择指定特定 `version`。
   * 结果在客户端缓存（TTL 可通过 OPIK_PROMPT_CACHE_TTL_SECONDS 配置，
   * 默认 300 秒）。在 track() 上下文内调用时，提示词引用会被注入到
   * 当前活跃的 trace/span 元数据中。
   *
   * @param options - 提示词名称和可选的版本锁定或环境
   * @param options.name - 提示词的名称
   * @param options.version - 顺序版本标识符（如 `"v3"`）。若未提供，
   *   则返回最新版本。
   * @param options.commit - **已弃用。** 请使用 `version` 替代。
   * @param options.projectName - 可选的项目范围。
   * @param options.environment - 可选的环境名称。解析为该工作区环境当前
   *   拥有的版本。与 `commit` 和 `version` 互斥。
   * @returns 解析为 ChatPrompt 或未找到时为 null 的 Promise
   * @throws 若同时提供了 `commit` 和 `version`，或 `environment` 与
   *   `commit` 或 `version` 组合使用则抛出错误
   * @throws 若提示词存在但为文本提示词则抛出 PromptTemplateStructureMismatch
   *
   * @example
   * ```typescript
   * const chatPrompt = await client.getChatPrompt({ name: "assistant-prompt" });
   * if (chatPrompt) {
   *   const messages = chatPrompt.format({ task: "coding" });
   * }
   * ```
   */
  public getChatPrompt = async (
    options: GetPromptOptions
  ): Promise<ChatPrompt | null> => {
    return this.getPromptWithCache<ChatPrompt>(
      options,
      PromptTemplateStructure.Chat,
      (promptData, versionData, projectName) =>
        ChatPrompt.fromApiResponse(promptData, versionData, this, projectName),
      "chat prompt"
    );
  };

  private getPromptWithCache = async <T extends BasePrompt>(
    options: GetPromptOptions,
    expectedStructure: PromptTemplateStructure,
    createInstance: (
      promptData: OpikApi.PromptPublic,
      versionData: OpikApi.PromptVersionDetail,
      projectName: string
    ) => T,
    logContext: string
  ): Promise<T | null> => {
    // 在处理任何异步操作之前，同步验证互斥性。
    if (options.commit && options.version) {
      throw new Error(
        "Provide either `commit` or `version`, not both. " +
          "Prefer `version` — `commit` is deprecated."
      );
    }

    logger.debug(`Getting ${logContext}`, options);

    if (options.commit && options.environment) {
      throw new Error(
        "'commit' and 'environment' are mutually exclusive; pass at most one.",
      );
    }

    if (options.version && options.environment) {
      throw new Error(
        "'version' and 'environment' are mutually exclusive; pass at most one.",
      );
    }

    const resolvedProjectName = this.resolveProjectName(options.projectName);

    const fetchFn = async (maskId?: string | null): Promise<T | null> => {
      try {
        let promptData: OpikApi.PromptPublic | undefined;
        let versionData: OpikApi.PromptVersionDetail;

        if (maskId) {
          versionData = await this.api.prompts.getPromptVersionById(
            maskId,
            {},
            this.api.requestOptions
          );
          if (!versionData.promptId) {
            return null;
          }
          promptData = await this.api.prompts.getPromptById(
            versionData.promptId,
            {},
            this.api.requestOptions
          );
        } else {
          let projectId: string | undefined;
          try {
            projectId = await this.getProjectIdByName(resolvedProjectName);
          } catch {
            // 项目尚不存在 — 不使用项目过滤器进行搜索
          }

          const searchResponse = await this.api.prompts.getPrompts(
            {
              filters: JSON.stringify([
                { field: "name", operator: "=", value: options.name },
              ]),
              size: 1,
              ...(projectId && { projectId }),
            },
            this.api.requestOptions
          );

          promptData = searchResponse.content?.[0];
          if (!promptData) {
            logger.debug(`${logContext.charAt(0).toUpperCase() + logContext.slice(1)} not found`, { name: options.name });
            return null;
          }

          // 显式构建 REST 请求体，避免泄露仅限 SDK 的字段
          // 字段（如 `version` — 线路格式中称为 `versionNumber`）。
          const retrieveRequest: OpikApi.PromptVersionRetrieveDetail = {
            name: options.name,
            projectName: resolvedProjectName,
            ...(options.commit ? { commit: options.commit } : {}),
            ...(options.version ? { versionNumber: options.version } : {}),
            ...(options.environment ? { environment: options.environment } : {}),
          };

          versionData = await this.api.prompts.retrievePromptVersion(
            retrieveRequest,
            this.api.requestOptions
          );
        }


        const templateStructure = versionData.templateStructure;
        if (expectedStructure === PromptTemplateStructure.Text) {
          if (templateStructure && templateStructure !== PromptTemplateStructure.Text) {
            throw new PromptTemplateStructureMismatch(
              options.name,
              templateStructure,
              PromptTemplateStructure.Text
            );
          }
        } else {
          if (!templateStructure || templateStructure !== PromptTemplateStructure.Chat) {
            throw new PromptTemplateStructureMismatch(
              options.name,
              templateStructure ?? "undefined",
              PromptTemplateStructure.Chat
            );
          }
        }

        return createInstance(promptData, versionData, resolvedProjectName);
      } catch (error) {
        if (error instanceof OpikApiError && error.statusCode === 404) {
          return null;
        }
        logger.error(`Failed to get ${logContext}`, { name: options.name, error });
        throw error;
      }
    };

    const unmasked = await promptCacheGetOrFetch<T>(
      options.name,
      options.commit,
      resolvedProjectName,
      expectedStructure,
      () => fetchFn(),
      this.config.promptCacheTtlSeconds,
      undefined,
      options.version,
      options.environment
    );

    const activeMaskId = unmasked?.id
      ? getActiveMaskForPrompt(unmasked.id)
      : null;
    const result = activeMaskId
      ? await promptCacheGetOrFetch<T>(
          options.name,
          options.commit,
          resolvedProjectName,
          expectedStructure,
          () => fetchFn(activeMaskId),
          this.config.promptCacheTtlSeconds,
          activeMaskId,
          options.version
        )
      : unmasked;

    if (result !== null) {
      const ctx = getTrackContext();
      if (ctx) {
        if (!UpdateService.promptAlreadyInjected(ctx.trace.data.metadata, result.id, result.commit)) {
          ctx.trace.update({ prompts: [result], appendPrompts: true });
        }
        if (!UpdateService.promptAlreadyInjected(ctx.span.data.metadata, result.id, result.commit)) {
          ctx.span.update({ prompts: [result], appendPrompts: true });
        }
      }
    }

    return result;
  };

  /**
   * 使用可选的 OQL 过滤条件搜索提示词。
   *
   * @param filterString - 可选的 OQL 过滤字符串，用于缩小搜索范围
   *
   * 支持的 OQL 格式：`<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*`
   *
   * 支持的列：
   * - `id`、`name`、`description`：字符串字段
   * - `created_by`、`last_updated_by`：字符串字段
   * - `template_structure`：字符串字段（如 "text" 或 "chat"）
   * - `created_at`、`last_updated_at`：日期/时间字段（ISO 8601 格式）
   * - `tags`：列表字段（仅支持 "contains" 操作符）
   * - `version_count`：数字字段
   *
   * 各列支持的操作符：
   * - 字符串字段（`id`、`name`、`description`、`created_by`、`last_updated_by`、`template_structure`）：=、!=、contains、not_contains、starts_with、ends_with、>、<
   * - 日期/时间字段（`created_at`、`last_updated_at`）：=、>、<、>=、<=
   * - 数字字段（`version_count`）：=、!=、>、<、>=、<=
   * - 列表字段（`tags`）：contains
   *
   * @returns 解析为匹配的最新提示词版本数组的 Promise
   * @throws 若 OQL 过滤语法无效则抛出错误
   *
   * @example
   * ```typescript
   * // 获取所有提示词
   * const allPrompts = await client.searchPrompts();
   *
   * // 按标签筛选
   * const prompts = await client.searchPrompts('tags contains "alpha"');
   *
   * // 按多个条件筛选
   * const prompts = await client.searchPrompts(
   *   'tags contains "alpha" AND name contains "summary"'
   * );
   *
   * // 按创建者筛选
   * const prompts = await client.searchPrompts('created_by = "user@example.com"');
   *
   * // 按模板结构筛选
   * const chatPrompts = await client.searchPrompts('template_structure = "chat"');
   *
   * // 按日期范围筛选
   * const recentPrompts = await client.searchPrompts('created_at >= "2024-01-01T00:00:00Z"');
   *
   * // 按版本数量筛选
   * const multiVersion = await client.searchPrompts('version_count > 5');
   * ```
   */
  public searchPrompts = async (
    filterString?: string
  ): Promise<(Prompt | ChatPrompt)[]> => {
    logger.debug("Searching prompts", { filterString });

    try {
      // 将 OQL 过滤字符串解析为 JSON
      let filters: string | undefined;
      if (filterString) {
        const oql = OpikQueryLanguage.forPrompts(filterString);
        const filterExpressions = oql.getFilterExpressions();
        filters = filterExpressions
          ? JSON.stringify(filterExpressions)
          : undefined;
      }

      const response = await this.api.prompts.getPrompts(
        {
          filters,
          size: 1000,
        },
        this.api.requestOptions
      );

      const prompts = response.content ?? [];

      // 映射每个提示词以获取其最新版本并创建相应的实例
      const promptsWithVersions = await Promise.all(
        prompts.map(async (promptData: OpikApi.PromptPublic) => {
          if (!promptData.name) {
            return null;
          }

          try {
            const versionResponse =
              await this.api.prompts.retrievePromptVersion(
                { name: promptData.name },
                this.api.requestOptions
              );

            const templateStructure = versionResponse.templateStructure;

            const searchProjectName = this.resolveProjectName();
            // 为了向后兼容，默认为文本类型
            if (!templateStructure || templateStructure === PromptTemplateStructure.Text) {
              return Prompt.fromApiResponse(promptData, versionResponse, this, searchProjectName);
            } else if (templateStructure === PromptTemplateStructure.Chat) {
              return ChatPrompt.fromApiResponse(
                promptData,
                versionResponse,
                this,
                searchProjectName
              );
            }

            return null;
          } catch (error) {
            logger.debug("Failed to get version for prompt", {
              name: promptData.name,
              error,
            });
            return null;
          }
        })
      );

      return promptsWithVersions.filter(
        (p: Prompt | ChatPrompt | null): p is Prompt | ChatPrompt => p !== null
      );
    } catch (error) {
      logger.error("Failed to search prompts", { error });
      throw error;
    }
  };

  /**
   * 批量删除多个提示词及其所有版本。
   * 执行同步删除（不使用批处理）。
   *
   * @param ids - 要删除的提示词容器 ID 数组
   */
  public deletePrompts = async (ids: string[]): Promise<void> => {
    logger.debug("Deleting prompts in batch", { count: ids.length });

    try {
      await this.api.prompts.deletePromptsBatch(
        { ids },
        this.api.requestOptions
      );

      getGlobalCache().evictByIds(ids);
      logger.info("Successfully deleted prompts", { count: ids.length });
    } catch (error) {
      logger.error("Failed to delete prompts", { count: ids.length, error });
      throw error;
    }
  };

  /**
   * 将提示词版本分配到环境，或清除分配。
   *
   * 替换提示词版本拥有的完整环境集合。提供的列表将成为解析版本的
   * 完整环境集合。传入空数组以清除所有环境。同一提示词的其他版本
   * 如果之前拥有列表中的某个环境则会被清除。内存中已有的提示词
   * 对象不会被修改 — 需要使用 `client.getPrompt(...)` 重新获取以查看变更。
   *
   * @param options.promptName - 提示词的名称
   * @param options.environments - 要分配的环境。每个环境必须已在工作区中注册。传入 `[]` 以清除。
   * @param options.version - 线路格式中的顺序版本选择器 `"v<N>"`（如 `"v3"`）。默认为最新版本。
   * @param options.projectName - 提示词所属的项目。默认为客户端的项目。
   *
   * @throws {PromptNotFoundError} 提示词名称（或提供的 `version`）在解析的项目中不存在。
   * @throws {EnvironmentNotFoundError} `environments` 中的某个环境未在工作区中注册。
   */
  public setPromptEnvironments = async (
    options: {
      promptName: string;
      environments: string[];
      version?: string;
      projectName?: string;
    },
  ): Promise<void> => {
    let resolvedVersion;
    try {
      resolvedVersion = await this.api.prompts.retrievePromptVersion(
        {
          name: options.promptName,
          versionNumber: options.version,
          projectName: this.resolveProjectName(options.projectName),
        },
        this.api.requestOptions,
      );
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 404) {
        if (options.version !== undefined) {
          throw new PromptNotFoundError(
            `No version '${options.version}' found for prompt '${options.promptName}'.`,
          );
        }
        throw new PromptNotFoundError(
          `No prompt found with name '${options.promptName}'.`,
        );
      }
      throw error;
    }

    const target = Array.from(new Set(options.environments));
    try {
      await this.api.prompts.setPromptVersionEnvironment(
        resolvedVersion.id!,
        { environments: target },
        this.api.requestOptions,
      );
    } catch (error) {
      if (error instanceof OpikApiError) {
        // 后端将未知环境报告为 404（未找到）或 409
        // （冲突，当名称与工作区注册表检查冲突时）。
        if (error.statusCode === 404 || error.statusCode === 409) {
          throw new EnvironmentNotFoundError(
            `One or more environments in [${target.join(", ")}] are not registered in this workspace.`,
          );
        }
}
      throw error;
    }

    getGlobalCache().invalidateForPrompt(
      options.promptName,
      this.resolveProjectName(options.projectName),
    );
  };

  /**
   * 在给定项目中搜索追踪记录。可选择等待至少找到指定数量的追踪记录后
   * 再在指定超时时间内返回。
   *
   * @param projectName - 要搜索的项目名称。默认使用客户端配置的项目。
   * @param filterString - 使用 Opik 查询语言（OQL）进行过滤。格式：`<COLUMN> <OPERATOR> <VALUE> [AND ...]`
   *   常用列：`id`、`name`、`start_time`、`end_time`、`input`、`output`、`status`、`tags`、`metadata.*`、`feedback_scores.*`、`usage.*`
   *   常用操作符：`=`、`!=`、`>`、`<`、`>=`、`<=`、`contains`、`not_contains`、`starts_with`、`ends_with`
   *   日期使用 ISO 8601 格式（如 "2024-01-01T00:00:00Z"）
   * @param maxResults - 返回追踪记录的最大数量（默认：1000）
   * @param truncate - 是否截断输入、输出或元数据中的图像数据（默认：true）
   * @param waitForAtLeast - 返回前等待的最少追踪记录数量
   * @param waitForTimeout - 等待超时时间（秒）（默认：60）
   *
   * @returns 解析为匹配搜索条件的追踪记录数组的 Promise
   * @throws {SearchTimeoutError} 若在指定超时时间内未找到 waitForAtLeast 数量的追踪记录
   *
   * @example
   * ```typescript
   * // 获取项目中的所有追踪记录
   * const traces = await client.searchTraces({ projectName: "My Project" });
   *
   * // 按日期和元数据筛选
   * const filtered = await client.searchTraces({
   *   projectName: "My Project",
   *   filterString: 'start_time >= "2024-01-01T00:00:00Z" AND metadata.model = "gpt-4"'
   * });
   *
   * // 等待至少 10 条追踪记录
   * const traces = await client.searchTraces({
   *   projectName: "My Project",
   *   waitForAtLeast: 10,
   *   waitForTimeout: 30
   * });
   * ```
   */
  private async executeSearch<T, TFilter>(
    resourceType: "traces" | "threads" | "spans",
    options: {
      projectName?: string;
      filterString?: string;
      maxResults?: number;
      truncate?: boolean;
      waitForAtLeast?: number;
      waitForTimeout?: number;
    },
    parseFilters: (filterString?: string) => TFilter[] | null,
    searchWithFilters: (
      api: OpikApiClientTemp,
      projectName: string,
      filters: TFilter[] | null,
      maxResults: number,
      truncate: boolean
    ) => Promise<T[]>
  ): Promise<T[]> {
    const {
      projectName,
      filterString,
      maxResults = 1000,
      truncate = true,
      waitForAtLeast,
      waitForTimeout = 60,
    } = options;

    logger.debug(`Searching ${resourceType}`, {
      projectName,
      filterString,
      maxResults,
      truncate,
      waitForAtLeast,
      waitForTimeout,
    });

    const filters = parseFilters(filterString);
    const targetProject = projectName ?? this.config.projectName;

    const searchFn = () =>
      searchWithFilters(
        this.api,
        targetProject,
        filters,
        maxResults,
        truncate
      );

    if (waitForAtLeast === undefined) {
      return await searchFn();
    }

    const result = await searchAndWaitForDone(
      searchFn,
      waitForAtLeast,
      waitForTimeout * 1000,
      5000
    );

    if (result.length < waitForAtLeast) {
      throw new SearchTimeoutError(
        `Timeout after ${waitForTimeout} seconds: expected ${waitForAtLeast} ${resourceType}, but only ${result.length} were found.`
      );
    }

    return result;
  }

  public searchTraces = async (options?: {
    projectName?: string;
    filterString?: string;
    maxResults?: number;
    truncate?: boolean;
    exclude?: string[];
    waitForAtLeast?: number;
    waitForTimeout?: number;
  }): Promise<OpikApi.TracePublic[]> => {
    const { exclude, ...rest } = options ?? {};
    return this.executeSearch<OpikApi.TracePublic, OpikApi.TraceFilterPublic>(
      "traces",
      rest,
      parseFilterString,
      (api, projectName, filters, maxResults, truncate) =>
        searchTracesWithFilters(api, projectName, filters, maxResults, truncate,
          exclude as OpikApi.TraceSearchStreamRequestPublicExcludeItem[] | undefined)
    );
  };

  /**
   * 在项目中搜索线程，支持可选过滤。
   *
   * 线程代表将相关追踪记录分组的对话或会话。
   * 此方法允许使用 Opik 查询语言（OQL）搜索和筛选线程。
   *
   * @param options - 搜索选项
   * @param options.projectName - 要搜索的项目名称。默认使用客户端配置的项目。
   * @param options.filterString - 使用 Opik 查询语言（OQL）的过滤字符串。
   *   支持按以下字段筛选：id、status、feedback_scores、duration、number_of_messages、tags、metadata 等。
   *   示例：'status = "active"'、'feedback_scores.quality > 0.8'、'duration > 300'
   * @param options.maxResults - 返回线程的最大数量（默认：1000）
   * @param options.truncate - 是否截断响应中的大字段（默认：true）
   * @param options.waitForAtLeast - 若指定，则轮询直到找到至少此数量的线程
   * @param options.waitForTimeout - 使用 waitForAtLeast 时的超时时间（秒）（默认：60）
   * @returns 解析为线程数组的 Promise
   * @throws {SearchTimeoutError} 若指定了 waitForAtLeast 且达到超时
   *
   * @example
   * ```typescript
   * // 获取项目中的所有线程
   * const threads = await client.searchThreads({ projectName: "My Project" });
   *
   * // 按状态筛选
   * const activeThreads = await client.searchThreads({
   *   projectName: "My Project",
   *   filterString: 'status = "active"'
   * });
   *
   * // 按反馈分数筛选
   * const highQualityThreads = await client.searchThreads({
   *   projectName: "My Project",
   *   filterString: 'feedback_scores.quality > 0.8'
   * });
   *
   * // 等待至少 5 个线程
   * const threads = await client.searchThreads({
   *   projectName: "My Project",
   *   waitForAtLeast: 5,
   *   waitForTimeout: 30
   * });
   * ```
   */
  public searchThreads = async (options?: {
    projectName?: string;
    filterString?: string;
    maxResults?: number;
    truncate?: boolean;
    waitForAtLeast?: number;
    waitForTimeout?: number;
  }): Promise<OpikApi.TraceThread[]> => {
    return this.executeSearch<OpikApi.TraceThread, OpikApi.TraceThreadFilter>(
      "threads",
      options ?? {},
      parseThreadFilterString,
      searchThreadsWithFilters
    );
  };

  /**
   * 在项目中搜索跨度，支持可选过滤。
   *
   * 跨度代表追踪中的单个操作或步骤，如 LLM 调用或函数执行。
   * 此方法允许使用 Opik 查询语言（OQL）搜索和筛选跨度。
   *
   * @param options - 搜索选项
   * @param options.projectName - 要搜索的项目名称。默认使用客户端配置的项目。
   * @param options.filterString - 使用 Opik 查询语言（OQL）的过滤字符串。
   *   支持按以下字段筛选：model、provider、type、metadata、feedback_scores、usage、duration 等。
   *   示例：'model = "gpt-4"'、'provider = "openai"'、'type = "llm"'、'metadata.version = "1.0"'
   * @param options.maxResults - 返回跨度的最大数量（默认：1000）
   * @param options.truncate - 是否截断响应中的大字段（默认：true）
   * @param options.waitForAtLeast - 若指定，则轮询直到找到至少此数量的跨度
   * @param options.waitForTimeout - 使用 waitForAtLeast 时的超时时间（秒）（默认：60）
   * @returns 解析为跨度数组的 Promise
   * @throws {SearchTimeoutError} 若指定了 waitForAtLeast 且达到超时
   *
   * @example
   * ```typescript
   * // 获取项目中的所有跨度
   * const spans = await client.searchSpans({ projectName: "My Project" });
   *
   * // 按模型筛选
   * const gpt4Spans = await client.searchSpans({
   *   projectName: "My Project",
   *   filterString: 'model = "gpt-4"'
   * });
   *
   * // 按提供商和类型筛选
   * const openaiLLMSpans = await client.searchSpans({
   *   projectName: "My Project",
   *   filterString: 'provider = "openai" and type = "llm"'
   * });
   *
   * // 按元数据筛选
   * const prodSpans = await client.searchSpans({
   *   projectName: "My Project",
   *   filterString: 'metadata.environment = "production"'
   * });
   *
   * // 等待至少 5 个跨度
   * const spans = await client.searchSpans({
   *   projectName: "My Project",
   *   waitForAtLeast: 5,
   *   waitForTimeout: 30
   * });
   * ```
   */
  public searchSpans = async (options?: {
    projectName?: string;
    filterString?: string;
    maxResults?: number;
    truncate?: boolean;
    exclude?: string[];
    waitForAtLeast?: number;
    waitForTimeout?: number;
  }): Promise<OpikApi.SpanPublic[]> => {
    const { exclude, ...rest } = options ?? {};
    return this.executeSearch<OpikApi.SpanPublic, OpikApi.SpanFilterPublic>(
      "spans",
      rest,
      parseSpanFilterString,
      (api, projectName, filters, maxResults, truncate) =>
        searchSpansWithFilters(api, projectName, filters, maxResults, truncate,
          exclude as OpikApi.SpanSearchStreamRequestPublicExcludeItem[] | undefined)
    );
  };

  private logFeedbackScores(
    scores: FeedbackScoreData[],
    batchQueue: TraceFeedbackScoresBatchQueue | SpanFeedbackScoresBatchQueue
  ): void {
    for (const score of scores) {
      batchQueue.create({
        ...score,
        projectName: score.projectName ?? this.config.projectName,
        source: FeedbackScoreBatchItemSource.Sdk,
      });
    }
  }

  /**
   * 批量记录已有追踪的反馈分数。
   *
   * @param scores - 包含追踪 ID 的反馈分数数据数组
   *
   * @example
   * ```typescript
   * client.logTracesFeedbackScores([
   *   { id: "trace-id-1", name: "quality", value: 0.9, reason: "Good response" },
   *   { id: "trace-id-2", name: "relevance", value: 0.8 }
   * ]);
   * await client.flush();
   * ```
   */
  public logTracesFeedbackScores(scores: FeedbackScoreData[]): void {
    this.logFeedbackScores(scores, this.traceFeedbackScoresBatchQueue);
  }

  /**
   * 批量记录已有跨度的反馈分数。
   *
   * @param scores - 包含跨度 ID 的反馈分数数据数组
   *
   * @example
   * ```typescript
   * client.logSpansFeedbackScores([
   *   { id: "span-id-1", name: "accuracy", value: 0.95 },
   *   { id: "span-id-2", name: "completeness", value: 0.85, reason: "Missing details" }
   * ]);
   * await client.flush();
   * ```
   */
  public logSpansFeedbackScores(scores: FeedbackScoreData[]): void {
    this.logFeedbackScores(scores, this.spanFeedbackScoresBatchQueue);
  }

  public createEnvironment = async (
    name: string,
    options?: { description?: string; color?: string }
  ): Promise<OpikApi.EnvironmentPublic> => {
    const newId = generateId();
    try {
      await this.api.environments.createEnvironment({
        id: newId,
        name,
        description: options?.description,
        color: options?.color,
      });
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 409) {
        throw new EnvironmentAlreadyExistsError(name);
      }
      throw error;
    }
    return this.api.environments.getEnvironmentById(newId);
  };

  public getEnvironments = async (): Promise<OpikApi.EnvironmentPublic[]> => {
    const page = await this.api.environments.findEnvironments();
    return page.content ?? [];
  };

  private static readonly BUILTIN_ENVIRONMENT_NAMES = new Set([
    "production",
    "staging",
    "development",
  ]);

  public updateEnvironment = async (
    name: string,
    options?: { description?: string; color?: string }
  ): Promise<OpikApi.EnvironmentPublic> => {
    if (
      options?.color !== undefined &&
      OpikClient.BUILTIN_ENVIRONMENT_NAMES.has(name)
    ) {
      throw new EnvironmentConfigurationError(
        `Cannot change the colour of the built-in environment '${name}'. ` +
          "Colour updates are not allowed for 'production', 'staging', or 'development'."
      );
    }
    const existing = await this._findEnvironmentByName(name, true);
    await this.api.environments.updateEnvironment(existing!.id!, {
      description: options?.description,
      color: options?.color,
    });
    return this.api.environments.getEnvironmentById(existing!.id!);
  };

  public deleteEnvironment = async (name: string): Promise<void> => {
    const existing = await this._findEnvironmentByName(name, false);
    if (!existing) {
      return;
    }
    await this.api.environments.deleteEnvironmentsBatch({
      ids: [existing.id!],
    });
  };

  private _findEnvironmentByName = async (
    name: string,
    strict: boolean
  ): Promise<OpikApi.EnvironmentPublic | undefined> => {
    const envs = await this.getEnvironments();
    const match = envs.find((env) => env.name === name);
    if (!match && strict) {
      throw new Error(`No environment found with name "${name}".`);
    }
    return match;
  };

  public flush = async (options?: { silent?: boolean }) => {
    const silent = options?.silent ?? false;
    logger.debug("Starting flush operation");
    try {
      await this.traceBatchQueue.flush();
      await this.spanBatchQueue.flush();
      await this.traceFeedbackScoresBatchQueue.flush();
      await this.spanFeedbackScoresBatchQueue.flush();
      await this.traceAssertionResultsBatchQueue.flush();
      await this.datasetBatchQueue.flush();
      // 注意：提示词操作是同步的，不使用批处理
      if (!silent) logger.info("Successfully flushed all data to Opik");
    } catch (error) {
      logger.error("Error during flush operation:", {
        error: error instanceof Error ? error.message : error,
      });
    }
  };

  /**
   * 获取类型化配置并返回为 `Config<T>` 对象。
   * 必须在 `track()` 函数内调用。
   *
   * 选择器（互斥）：
   * - `options.version` — 精确获取指定名称的版本
   * - `options.env` — 获取固定到该环境的版本（默认：`"prod"`）
   * - 都不指定 — 等同于 `env="prod"`
   *
   * 使用 `fallback` 时：
   * - 后端错误返回带有 `isFallback: true` 的回退值
   * - 空项目从回退值自动创建
   * - T 从回退类型推断
   *
   * 不使用 `fallback` 时：
   * - 后端错误会被重新抛出
   * - 空项目抛出 ConfigNotFoundError
   * - T 默认为 `Record<string, unknown>`；使用显式类型参数来断言结构
   */
  public getOrCreateConfig<T extends Record<string, unknown>>(
    options: {
      fallback: T;
      projectName?: string;
      env?: string;
      version?: string;
    }
  ): Promise<Config<T>>;

  public getOrCreateConfig<T extends Record<string, unknown> = Record<string, unknown>>(
    options?: {
      projectName?: string;
      env?: string;
      version?: string;
    }
  ): Promise<Config<T>>;

  public getOrCreateConfig<T extends Record<string, unknown> = Record<string, unknown>>(
    options?: {
      fallback?: T;
      projectName?: string;
      env?: string;
      version?: string;
    }
  ): Promise<Config<T>> {
    return this._getOrCreateConfigImpl(options);
  }

  /** 从本地回退对象构建 Config（不涉及后端）。 */
  /**
   * 验证 `values` 中的每个 BasePrompt 值是否属于 `projectName`。
   * projectName 为 undefined 的提示词会被跳过（无法验证）。
   * 在发现第一个不匹配时抛出 ConfigMismatchError。
   */
  private _validatePromptProjects(
    values: Record<string, unknown>,
    projectName: string
  ): void {
    for (const [key, value] of Object.entries(values)) {
      if (
        value instanceof BasePrompt &&
        value.projectName !== undefined &&
        value.projectName !== projectName
      ) {
        throw new ConfigMismatchError(
          `Field "${key}": prompt project "${value.projectName}" does not match ` +
          `config project "${projectName}". All prompts referenced in a config must ` +
          `belong to the same project as the config.`
        );
      }
    }
  }

  /**
   * 等待 `values` 中所有未同步的 BasePrompt 值完成同步，带超时。
   * 仅当每个提示词都已同步时返回 true。
   */
  private async _allPromptsSynced(values: Record<string, unknown>): Promise<boolean> {
    const prompts = Object.values(values).filter(
      (v): v is BasePrompt => v instanceof BasePrompt && !v.synced
    );
    if (prompts.length === 0) return true;

    const TIMED_OUT = Symbol();
    let timerId: ReturnType<typeof setTimeout> | undefined;
    try {
      const result = await Promise.race([
        Promise.allSettled(prompts.map((v) => v.ready())).then(() => undefined),
        new Promise<typeof TIMED_OUT>((resolve) => {
          timerId = setTimeout(() => resolve(TIMED_OUT), AGENT_CONFIG_PROMPT_READY_TIMEOUT_MS);
        }),
      ]);
      if (result === TIMED_OUT) {
        logger.debug("Timed out waiting for prompt sync before creating config.");
        return false;
      }
    } finally {
      clearTimeout(timerId);
    }

    // ready() 已解析，但某些提示词可能同步失败。
    return prompts.every((v) => v.synced);
  }

  private _makeFallbackConfig<T extends Record<string, unknown>>(
    fallback: T,
    maskId: string | undefined
  ): Config<T> {
    return createTypedConfig<T>({
      values: fallback,
      fieldNames: new Set(Object.keys(fallback)),
      blueprintId: undefined,
      blueprintVersion: undefined,
      isFallback: true,
      maskId,
    });
  }

  /**
   * 从后端获取蓝图（或返回缓存的蓝图）。
   * 若后端未返回结果则返回 null（非错误路径）。
   * 网络错误时，若提供了回退配置则返回回退配置；否则重新抛出错误。
   */
  private async _fetchBlueprintFromBackend<T extends Record<string, unknown>>(
    manager: ConfigManager,
    opts: {
      blueprintName: string | undefined;
      isLatest: boolean;
      hasNamedVersion: boolean;
      namedVersion: string | undefined;
      effectiveEnv: string | null;
      maskId: string | undefined;
      projectName: string;
      effectiveVersion: string | null;
      fallback: T | undefined;
    }
  ): Promise<Blueprint | null | Config<T>> {
    const {
      blueprintName, isLatest, hasNamedVersion, namedVersion,
      effectiveEnv, maskId, projectName, effectiveVersion, fallback,
    } = opts;

    const cacheEntry = getCachedBlueprint(projectName, effectiveEnv, maskId ?? null, effectiveVersion);

    if (!cacheEntry.isStale()) {
      return cacheEntry.getBlueprint();
    }

    let blueprint: Blueprint | null = null;
    try {
      if (blueprintName) {
        blueprint = await manager.getBlueprint({ name: blueprintName, maskId });
      } else if (isLatest) {
        blueprint = await manager.getBlueprint({ maskId });
      } else if (hasNamedVersion) {
        blueprint = await manager.getBlueprint({ name: namedVersion!, maskId });
      } else {
        blueprint = await manager.getBlueprint({ env: effectiveEnv!, maskId });
      }
    } catch (error) {
      if (fallback !== undefined) {
        logger.debug("Failed to fetch config from backend, using fallback", { error });
        return this._makeFallbackConfig(fallback, maskId);
      }
      throw error;
    }

    // 为基于环境和"最新"的查找设置后台刷新（不适用于固定版本或掩码）
    const refreshCallback =
      maskId === undefined && !hasNamedVersion
        ? isLatest
          ? () => manager.getBlueprint({ maskId: undefined })
          : () => manager.getBlueprint({ env: effectiveEnv!, maskId: undefined })
        : null;

    initBlueprintCacheEntry(projectName, effectiveEnv, maskId ?? null, blueprint, refreshCallback, effectiveVersion);
    return blueprint;
  }

  /**
   * 处理蓝图查找返回 null 的情况。
   * - 显式选择器（命名版本 / 环境 / 运行器上下文）→ 抛出 ConfigNotFoundError。
   * - 默认路径：探测整个项目以区分"无 prod 标签"和"空项目"。
   *   当 version="latest" 时，初始获取已是项目范围 — 跳过探测。
   * - 空项目 + 回退值 → 自动创建并返回新蓝图。
   * - 空项目 + 无回退值 → 抛出 ConfigNotFoundError。
   */
  private async _resolveNullBlueprint<T extends Record<string, unknown>>(
    manager: ConfigManager,
    opts: {
      projectName: string;
      effectiveEnv: string | null;
      effectiveVersion: string | null;
      maskId: string | undefined;
      hasNamedVersion: boolean;
      hasExplicitEnv: boolean;
      isExplicitBlueprintFromContext: boolean;
      isLatest: boolean;
      fallback: T | undefined;
    }
  ): Promise<Blueprint | Config<T>> {
    const {
      projectName, effectiveEnv, effectiveVersion, maskId,
      hasNamedVersion, hasExplicitEnv, isExplicitBlueprintFromContext,
      isLatest, fallback,
    } = opts;

    if (hasNamedVersion || hasExplicitEnv || isExplicitBlueprintFromContext) {
      throw new ConfigNotFoundError(
        `No config found for project "${projectName}" with the specified selector`
      );
    }

    // 默认路径（env="prod"）：获取最新版本以区分空项目和缺少 prod 标签。
    // 当 version="latest" 时，初始获取已是项目范围 — 跳过冗余的往返请求。
    if (!isLatest) {
      let latestBlueprint: Blueprint | null = null;
      try {
        latestBlueprint = await manager.getBlueprint({ maskId: undefined });
      } catch (error) {
        if (fallback !== undefined) {
          logger.debug("Failed to probe project-wide config, using fallback", { error });
          return this._makeFallbackConfig(fallback, maskId);
        }
        throw error;
      }

      if (latestBlueprint !== null) {
        throw new ConfigNotFoundError(
          `No config tagged with env="prod" in project "${projectName}", but other configs exist. ` +
          `Use setConfigEnv() to tag a version, or pass an explicit env/version.`
        );
      }
    }

    if (fallback === undefined) {
      throw new ConfigNotFoundError(
        `No config found in project "${projectName}". Pass a fallback to auto-create one.`
      );
    }

    // 验证回退值中的所有 Prompt/ChatPrompt 值是否属于此项目。
    this._validatePromptProjects(fallback as Record<string, unknown>, projectName);

    // 在从回退值自动创建之前，等待所有未同步的提示词完成同步。
    // 未同步的提示词缺少 commit/id，会导致生成损坏的蓝图值。
    const allSynced = await this._allPromptsSynced(fallback as Record<string, unknown>);
    if (!allSynced) {
      return this._makeFallbackConfig(fallback, maskId);
    }

    // 从回退值自动创建（处理 409 竞争：另一个调用者并发创建了它）
    let blueprint: Blueprint;
    try {
      blueprint = await manager.createBlueprint({
        values: serializeValuesRecord(fallback as Record<string, unknown>),
      });
    } catch (error) {
      if (error instanceof OpikApiError && error.statusCode === 409) {
        const refetched = await manager.getBlueprint({ maskId: undefined });
        if (!refetched) {
          throw new ConfigNotFoundError(`Failed to create or fetch config in project "${projectName}".`);
        }
        blueprint = refetched;
      } else {
        throw error;
      }
    }

    initBlueprintCacheEntry(projectName, effectiveEnv, maskId ?? null, blueprint, null, effectiveVersion);
    return blueprint;
  }

  /**
   * 验证回退键与蓝图的匹配，反序列化值，并返回类型化的 Config。
   */
  private _buildConfigFromBlueprint<T extends Record<string, unknown>>(
    blueprint: Blueprint,
    fallback: T | undefined,
    maskId: string | undefined
  ): Config<T> {
    const rawValuesMap = Object.fromEntries(
      blueprint.keys().map((key) => [key, blueprint.getRawEntry(key)!])
    );

    if (fallback !== undefined) {
      const missingKeys = Object.keys(fallback).filter((k) => rawValuesMap[k] === undefined);
      if (missingKeys.length > 0) {
        const versionLabel = blueprint.name ?? blueprint.id;
        throw new ConfigMismatchError(
          `Config version "${versionLabel}" is missing expected field(s): ${missingKeys.join(", ")}. ` +
          `The retrieved version does not contain all fields declared in the fallback.`
        );
      }
    }

    const resolvedValues = deserializeFromBlueprint(
      rawValuesMap,
      blueprint.values,
      fallback !== undefined ? Object.keys(fallback) : undefined
    );

    return createTypedConfig<T>({
      values: resolvedValues as T,
      fieldNames: new Set(Object.keys(fallback ?? resolvedValues)),
      blueprintId: blueprint.id,
      blueprintVersion: blueprint.name,
      isFallback: false,
      maskId,
    });
  }

  private async _getOrCreateConfigImpl<T extends Record<string, unknown>>(
    options?: {
      fallback?: T;
      projectName?: string;
      env?: string;
      version?: string;
    }
  ): Promise<Config<T>> {
    if (!trackStorage.getStore()) {
      throw new Error("getOrCreateConfig() must be called inside a track() function");
    }
    if (options?.version !== undefined && options?.env !== undefined) {
      throw new Error("Only one of 'version' or 'env' may be specified in getOrCreateConfig().");
    }

    const fallback = options?.fallback;
    const projectName = options?.projectName ?? this.config.projectName;
    const maskId = getActiveConfigMask() ?? undefined;

    const blueprintName = getActiveConfigBlueprintName() ?? undefined;
    // 固定蓝图名称的运行器上下文是显式请求 — 不自动创建。
    const isExplicitBlueprintFromContext = blueprintName !== undefined;
    const manager = new ConfigManager(projectName, this);

    // "latest" fetches the most-recent blueprint (no name/env filter); allows auto-create.
    const isLatest = options?.version === "latest";
    const hasNamedVersion = options?.version !== undefined && !isLatest;
    const effectiveEnv = options?.version ? null : (options?.env ?? "prod");
    // 缓存键："latest" 和默认环境路径均为 null
    const effectiveVersion = blueprintName ?? (hasNamedVersion ? options!.version! : null);

    const fetchResult = await this._fetchBlueprintFromBackend<T>(manager, {
      blueprintName, isLatest, hasNamedVersion,
      namedVersion: options?.version,
      effectiveEnv, maskId, projectName, effectiveVersion, fallback,
    });

    // _fetchBlueprintFromBackend may return a ready-made fallback Config on network error
    if (fetchResult !== null && !(fetchResult instanceof Blueprint)) {
      return fetchResult as Config<T>;
    }

    let blueprint = fetchResult as Blueprint | null;

    if (!blueprint) {
      const resolved = await this._resolveNullBlueprint<T>(manager, {
        projectName, effectiveEnv, effectiveVersion, maskId,
        hasNamedVersion, hasExplicitEnv: options?.env !== undefined,
        isExplicitBlueprintFromContext, isLatest, fallback,
      });

      // _resolveNullBlueprint may return a ready-made fallback Config on network error
      if (!(resolved instanceof Blueprint)) {
        return resolved as Config<T>;
      }
      blueprint = resolved;
    }

    return this._buildConfigFromBlueprint<T>(blueprint, fallback, maskId);
  }

  /**
   * 无条件发布新的配置版本。
   * 不需要 `track()` 函数。
   *
   * @param values - 要发布的配置字段值
   * @param options.projectName - 发布到的项目（默认使用客户端配置的项目）
   * @param options.description - 此版本的可选人类可读描述
   * @returns 已发布配置的版本名称（或 ID）
   */
  public createConfig = async (
    values: Record<string, SupportedValue>,
    options?: { projectName?: string; description?: string }
  ): Promise<string> => {
    const projectName = options?.projectName ?? this.config.projectName;

    this._validatePromptProjects(values as Record<string, unknown>, projectName);

    const manager = new ConfigManager(projectName, this);
    const serialized = serializeValuesRecord(values as Record<string, unknown>);

    const latest = await manager.getBlueprint();
    let blueprint: Blueprint;

    if (latest) {
      blueprint = await manager.updateBlueprint({ values: serialized, description: options?.description });
    } else {
      try {
        blueprint = await manager.createBlueprint({ values: serialized, description: options?.description });
      } catch (error) {
        if (error instanceof OpikApiError && error.statusCode === 409) {
          blueprint = await manager.updateBlueprint({ values: serialized, description: options?.description });
        } else {
          throw error;
        }
      }
    }

    return blueprint.name ?? blueprint.id;
  };

  /**
   * 为特定配置版本添加环境标签。
   * 不需要 `track()` 函数。
   *
   * @param options.version - 要标记的版本名称
   * @param options.env - 环境标签（如 "prod"、"staging"）
   * @param options.projectName - 项目（默认使用客户端配置的项目）
   */
  public setConfigEnv = async (options: {
    version: string;
    env: string;
    projectName?: string;
  }): Promise<void> => {
    const projectName = options.projectName ?? this.config.projectName;
    const manager = new ConfigManager(projectName, this);
    const blueprint = await manager.getBlueprint({ name: options.version });
    if (!blueprint) {
      throw new ConfigNotFoundError(
        `No config version "${options.version}" found in project "${projectName}".`
      );
    }
    const projectResponse = await this.api.projects.retrieveProject({ name: projectName });
    if (!projectResponse?.id) {
      throw new Error(`Project "${projectName}" not found`);
    }
    await this.api.agentConfigs.createOrUpdateEnvs({
      projectId: projectResponse.id,
      envs: [{ envName: options.env, blueprintId: blueprint.id }],
    });
  };

  /**
   * 在单个批量操作中更新一个或多个提示词版本的标签。
   *
   * @param versionIds - 要更新的提示词版本 ID 数组
   * @param options - 更新选项
   * @param options.tags - 要设置或合并的标签：
   *   - `[]`：清除所有标签（当 mergeTags 为 false 或未指定时）
   *   - `['tag1', 'tag2']`：设置或合并标签（基于 mergeTags）
   * @param options.mergeTags - 若为 true，将新标签添加到已有标签（并集）。若为 false，替换所有已有标签（默认：false）
   * @returns 更新完成时解析的 Promise
   * @throws 若更新失败则抛出 OpikApiError
   *
   * @example
   * ```typescript
   * // 替换多个版本的标签（默认行为）
   * await client.updatePromptVersionTags(["version-id-1", "version-id-2"], {
   *   tags: ["production", "v2"]
   * });
   *
   * // 将新标签与已有标签合并
   * await client.updatePromptVersionTags(["version-id-1"], {
   *   tags: ["hotfix"],
   *   mergeTags: true
   * });
   *
   * // 清除所有标签
   * await client.updatePromptVersionTags(["version-id-1"], {
   *   tags: []
   * });
   * ```
   */
  public updatePromptVersionTags = async (
    versionIds: string[],
    options?: {
      tags?: string[] | null;
      mergeTags?: boolean;
    }
  ): Promise<void> => {
    logger.debug("Updating prompt version tags", {
      count: versionIds.length,
      options,
    });

    try {
      await this.api.prompts.updatePromptVersions(
        {
          ids: versionIds,
          update: { tags: options?.tags ?? undefined },
          mergeTags: options?.mergeTags,
        },
        this.api.requestOptions
      );

      logger.debug("Successfully updated prompt version tags", {
        count: versionIds.length,
      });
    } catch (error) {
      logger.error("Failed to update prompt version tags", {
        count: versionIds.length,
        error,
      });
      throw error;
    }
  };
}
