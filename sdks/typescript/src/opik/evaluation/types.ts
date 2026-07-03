/**
 * 评估任务函数类型
 * 接收数据集条目作为输入，返回结果对象
 */
export type EvaluationTask<T = Record<string, unknown>> = (
  datasetItem: T
) => Promise<Record<string, unknown>> | Record<string, unknown>;

/**
 * 数据集键与评分指标输入之间的映射类型
 */
export type ScoringKeyMappingType = Record<string, string>;

/**
 * 表示评估实验的结果
 */
export type EvaluationResult = {
  /** 实验的 ID */
  experimentId: string;

  /** 实验的名称 */
  experimentName?: string;

  /**
   * 所有已评估条目的测试结果，包括失败的条目。
   * 任务抛出异常的条目将有一个名为 {@link TASK_ERROR_SCORE_NAME} 的合成分数，
   * 且 `scoringFailed: true`。
   */
  testResults: EvaluationTestResult[];

  /** 可选的 URL，用于在 Opik 平台查看详细结果 */
  resultUrl?: string;

  /** 评估过程中遇到的错误（任务失败、API 错误等） */
  errors: EvaluationError[];
};

/**
 * 表示单个数据集条目运行评估过程中发生的错误。
 */
export type EvaluationError = {
  /** 失败的数据集条目的 ID */
  datasetItemId: string;

  /** 条目执行中的运行索引（从 0 开始） */
  runIndex: number;

  /** 人类可读的错误消息 */
  message: string;

  /** 原始错误对象（如果可用） */
  error?: Error;
};

/**
 * 注入到失败任务运行中的保留分数名称。
 *
 * 当任务抛出异常时，引擎会添加一个使用此名称的合成分数，
 * 并设置 `scoringFailed: true`，以便失败的条目在实验结果中保持可见。
 * 消费者可以按此名称筛选以区分任务级失败和真实的指标分数。
 *
 * 注意：重命名前请与 Python SDK 协调 — 选择一个稳定的、
 * 防冲突的名称（OPIK-6437）。
 */
export const TASK_ERROR_SCORE_NAME = "__opik_task_error__";

/**
 * 表示指标计算的结果。
 */
export type EvaluationScoreResult = {
  /** 指标的名称 */
  name: string;

  /** 分数值（通常在 0.0 到 1.0 之间） */
  value: number;

  /** 分数的可选原因 */
  reason?: string;

  /**
   * 评分是否因任务级错误（而非指标失败）而失败。
   * 当为 `true` 时，`name` 将等于 {@link TASK_ERROR_SCORE_NAME}，
   * 这是引擎注入的保留名称 — 用户定义的指标不应产生使用该名称的分数。
   */
  scoringFailed?: boolean;

  /** 用于分组分数的可选类别名称 */
  categoryName?: string;
};

/**
 * 表示评估中的单个测试用例。
 */
export type EvaluationTestCase = {
  /** 与此测试用例关联的追踪 ID */
  traceId: string;

  /** 用于此测试用例的数据集条目的 ID */
  datasetItemId: string;

  /** 评分指标的输入 */
  scoringInputs: Record<string, unknown>;

  /** 任务执行的输出 */
  taskOutput: Record<string, unknown>;
};

/**
 * 表示测试用例评估的结果。
 */
export type EvaluationTestResult = {
  /** 此结果所属的测试用例 */
  testCase: EvaluationTestCase;

  /** 此测试用例所有指标的结果 */
  scoreResults: EvaluationScoreResult[];

  /** 多次运行测试套件的运行索引（0、1、2...） */
  trialId?: number;

  /** 已解析的每条目执行策略（由引擎在套件模式下设置）。 */
  resolvedExecutionPolicy?: { runsPerItem: number; passThreshold: number };
};
