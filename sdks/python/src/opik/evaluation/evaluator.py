import logging
import time
from typing import (
    Any,
    Callable,
    Dict,
    Iterator,
    List,
    Optional,
    Tuple,
    TYPE_CHECKING,
    Union,
    cast,
)

from opik.types import TraceSource
from ..api_objects.prompt import base_prompt
from ..api_objects import opik_client
from ..api_objects import dataset, experiment
from ..api_objects.dataset import dataset_item
from ..api_objects.experiment import helpers as experiment_helpers
from ..api_objects.dataset import execution_policy as dataset_execution_policy
from ..api_objects.prompt.chat import chat_prompt_template
from ..api_objects.prompt import types as prompt_types
from ..api_objects.dataset import test_suite as test_suite_module
from . import (
    asyncio_support,
    engine,
    evaluation_result,
    helpers,
    report,
    rest_operations,
    samplers,
)
from . import resume as resume_module
from .resume import integration as resume_integration
from .resume import merge as resume_merge
from .metrics import base_metric
from .suite_evaluators.llm_judge import (
    metric as suite_evaluators_llm_judge_metric,
    strategy_selector as suite_evaluators_strategy,
)
from .models import ModelCapabilities, base_model, models_factory
from .scorers import scorer_function, scorer_wrapper_metric
from .types import ExperimentScoreFunction, LLMTask, ScoringKeyMappingType
from .. import url_helpers, exceptions
from ..api_objects.dataset.test_suite import suite_result_constructor

if TYPE_CHECKING:
    from ..api_objects.dataset.test_suite import types as suite_types

LOGGER = logging.getLogger(__name__)
MODALITY_SUPPORT_DOC_URL = (
    "https://www.comet.com/docs/opik/evaluation/evaluate_multimodal"
)


def _try_notifying_about_experiment_completion(
    experiment: experiment.Experiment,
) -> None:
    """尝试通知后端实验已完成。"""
    try:
        experiment.experiments_rest_client.finish_experiments(ids=[experiment.id])
    except Exception:
        LOGGER.debug(
            "无法通知后端实验已完成。实验 ID: %s",
            experiment.id,
            exc_info=True,
        )


def _materialize_for_checkpoint(
    *,
    items_iter: Iterator[dataset_item.DatasetItem],
    total_items: Optional[int],
    dataset_item_ids: Optional[List[str]],
    dataset_sampler: Optional[samplers.BaseDatasetSampler],
) -> Tuple[Iterator[dataset_item.DatasetItem], Optional[int], Optional[List[str]]]:
    """
    为引擎和恢复检查点解析 (iterator, total, resolved_ids) 元组，
    在可能的情况下不破坏惰性流式传输。

    三种情况：
      * 采样器（有或无显式 ids）→ 迭代器已在 ``resolve_dataset_items`` 内部
        从物化列表构建；我们先遍历一次以获取采样后的 ids 用于检查点，
        然后将同一列表的新迭代器交给引擎。采样器优先级确保检查点反映
        引擎实际迭代的内容，而不是原始输入 ids — 否则恢复操作会重放
        与原始评估不同的项目集。
      * 仅显式 ``dataset_item_ids`` → ids 提前已知；检查点直接获取它们，
        迭代器保持不变，引擎仍可惰性消费。
      * 两者都没有 → 流式传输。无需检查点；迭代器直接传递给引擎。
    """
    if dataset_sampler is not None:
        materialized = list(items_iter)
        return (
            iter(materialized),
            len(materialized),
            [item.id for item in materialized],
        )
    if dataset_item_ids is not None:
        return items_iter, total_items, list(dataset_item_ids)
    return items_iter, total_items, None


def evaluate(
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[base_prompt.BasePrompt] = None,
    prompts: Optional[List[base_prompt.BasePrompt]] = None,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    dataset_item_ids: Optional[List[str]] = None,
    dataset_sampler: Optional[samplers.BaseDatasetSampler] = None,
    trial_count: int = 1,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
    experiment_tags: Optional[List[str]] = None,
    dataset_filter_string: Optional[str] = None,
    blueprint_id: Optional[str] = None,
) -> evaluation_result.EvaluationResult:
    """
    对给定数据集执行任务评估。可以使用 `scoring_metrics` 或 `scorer_functions` 来计算评估指标。
    评分函数不需要 `scoring_key_mapping`，使用保留参数来接收任务的输入和输出。

    Args:
        dataset: Opik Dataset 或 DatasetVersion 实例

        task: 可调用对象，接受包含数据集项目内容的字典作为输入，
            返回稍后用于评分的字典。

        experiment_name_prefix: 添加到自动生成的实验名称前的前缀，使其唯一
            但分组在同一前缀下。例如，如果设置 `experiment_name_prefix="my-experiment"`，
            创建的第一个实验将命名为 `my-experiment-<unique-random-part>`。

        experiment_name: 与评估运行关联的实验名称。
            如果为 None，将使用生成的名称。

        project_name: 已弃用。如果数据集设置了 ``project_name``，将始终使用该值，
            此覆盖将被忽略（并显示警告）。如果数据集没有 ``project_name``，
            跟踪和跨度将记录到此项目（省略时记录到 ``Default Project``）。

        experiment_config: 描述实验参数的字典

        scoring_metrics: 评估期间要计算的指标列表。
            每个指标都有 `score(...)` 方法，该方法的参数取自 `task` 输出，
            检查所需指标的 `score` 方法签名以了解 `task` 返回字典中哪些键是必需的。
            如果未提供值，实验将没有任何评分指标。

        scoring_functions: 评估期间要执行的评分函数列表。
            每个评分函数包含一个评分方法，接受评估引擎提供的预定义参数：
                • dataset_item — 包含数据集项目内容的字典，
                • task_outputs — 包含 LLM 任务输出的字典。
                • task_span - LLM 任务执行期间收集的数据 [可选]。

        verbose: 控制评估输出日志（如摘要和 tqdm 进度条）的整数值。
            0 - 无输出，1 - 启用输出（默认），2 - 启用输出并显示详细统计信息。

        nb_samples: 要评估的样本数。如果未提供值，将评估数据集中的所有样本。

        task_threads: 运行任务的线程工作者数。如果设置为 1，不会创建额外的线程，
            所有任务在当前线程中顺序执行。
            如果您的任务对象支持跨线程共享，请使用多个工作者。

        prompt: 要与实验关联的 Prompt 对象。已弃用，请改用 `prompts` 参数。

        prompts: 要与实验关联的 Prompt 对象列表。

        scoring_key_mapping: 允许您重命名数据集项目或任务输出中存在的键的字典，
            以便它们与评分指标期望的键匹配。例如，如果您有以下内容的数据集项目：
            {"user_question": "What is Opik ?"} 和期望键 "input" 的评分指标，
            您可以使用 scoring_key_mapping `{"input": "user_question"}` 
            将 "user_question" 键映射到 "input"。

        dataset_item_ids: 要评估的数据集项目 ID 列表。如果未提供，将评估数据集中的所有样本。

        dataset_sampler: 用于采样数据集项目进行评估的数据集采样器实例。
            如果未提供，将评估数据集中的所有样本。

        trial_count: 对每个数据集项目运行任务和评估任务输出的次数。

        experiment_scoring_functions: 计算实验级别分数的可调用函数列表。
            每个函数接受 TestResult 对象列表并返回 ScoreResult 对象列表。
            这些分数在所有测试结果收集后计算，代表整个实验的聚合指标。

        experiment_tags: 要与实验关联的可选标签列表。

        dataset_filter_string: 可选的 OQL 过滤字符串，用于过滤数据集项目。
            支持按标签、数据字段、元数据等过滤。

            支持的列包括：
            - `id`、`source`、`trace_id`、`span_id`：字符串字段
            - `data`：字典字段（使用点表示法，例如 "data.category"）
            - `tags`：列表字段（使用 "contains" 运算符）
            - `created_at`、`last_updated_at`：日期时间字段（ISO 8601 格式）
            - `created_by`、`last_updated_by`：字符串字段

            示例：
            - `tags contains "failed"` - 带有 'failed' 标签的项目
            - `data.category = "test"` - 具有特定数据字段值的项目
            - `created_at >= "2024-01-01T00:00:00Z"` - 在日期之后创建的项目
    """
    if isinstance(dataset, test_suite_module.TestSuite):
        # 过渡期间的向后兼容性
        dataset = dataset.__internal_api__dataset__

    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )

    checked_prompts = experiment_helpers.handle_prompt_args(
        prompt=prompt,
        prompts=prompts,
    )

    client = opik_client.get_global_client()

    if blueprint_id:
        experiment_config = helpers.merge_blueprint_into_config(
            client,
            blueprint_id,
            experiment_config,
        )

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    project_name = helpers.resolve_project_name(
        value_from_dataset=dataset.project_name,
        value_from_user=project_name,
        caller_name="evaluate",
    )

    experiment_config = resume_integration.resume_state_for_evaluate(
        experiment_config=experiment_config,
        dataset_=dataset,
        trial_count=trial_count,
        dataset_filter_string=dataset_filter_string,
        nb_samples=nb_samples,
        dataset_sampler=dataset_sampler,
        dataset_item_ids=dataset_item_ids,
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=checked_prompts,
        tags=experiment_tags,
        dataset_version_id=getattr(dataset.get_version_info(), "id", None),
        project_name=project_name,
    )

    items_iter, total_items = helpers.resolve_dataset_items(
        dataset_=dataset,
        nb_samples=nb_samples,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
        dataset_filter_string=dataset_filter_string,
    )
    items_iter, total_items, resolved_ids = _materialize_for_checkpoint(
        items_iter=items_iter,
        total_items=total_items,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
    )
    resume_integration.write_checkpoint_if_needed(
        experiment_id=experiment.id,
        resolved_ids=resolved_ids,
    )

    # 如果有评分函数则包装
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    return _evaluate_task(
        client=client,
        experiment=experiment,
        dataset=dataset,
        items_iter=items_iter,
        total_items=total_items,
        task=task,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
        verbose=verbose,
        task_threads=task_threads,
        scoring_key_mapping=scoring_key_mapping,
        trial_count=trial_count,
        experiment_scoring_functions=experiment_scoring_functions,
        source="experiment",
    )


def __internal_api__run_test_suite__(
    suite_dataset: Union[dataset.Dataset, dataset.DatasetVersion],
    task: LLMTask,
    *,
    client: Optional[opik_client.Opik],
    dataset_item_ids: Optional[List[str]] = None,
    dataset_filter_string: Optional[str] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    prompts: Optional[List[base_prompt.BasePrompt]] = None,
    experiment_tags: Optional[List[str]] = None,
    verbose: int = 2,
    task_threads: int = 16,
    evaluator_model: Optional[str] = None,
    optimization_id: Optional[str] = None,
    experiment_type: Optional[str] = None,
    generate_report: bool = True,
    report_output_path: Optional[str] = None,
    blueprint_id: Optional[str] = None,
    scoring_tool_strategy: Optional[
        suite_evaluators_strategy.ScoringToolStrategyMode
    ] = None,
) -> "suite_types.TestSuiteResult":
    """
    运行完整测试套件评估管道的内部函数：
    任务验证、评估、报告生成和结果展示。

    被 ``run_tests()`` 和
    ``TestSuite.__internal_api__run_optimization_suite__()`` 使用。
    """
    from ..api_objects.dataset.test_suite.test_suite import validate_task_result
    from ..api_objects.dataset.test_suite.report_processors import (
        displayer,
        file_writer,
    )

    import functools

    if client is None:
        client = opik_client.get_global_client()

    if blueprint_id:
        experiment_config = helpers.merge_blueprint_into_config(
            client,
            blueprint_id,
            experiment_config,
        )

    @functools.wraps(task)
    def _validated_task(data: Dict[str, Any]) -> Any:
        return validate_task_result(task(data), input_data=data)

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    # 注意：测试套件实验目前不支持 evaluate_resume，
    # 因此我们故意不在其中嵌入恢复状态。opik.evaluation.resume.state 中的
    # 持久化原语设计为可以在不更改架构的情况下稍后添加测试套件入口点。

    create_experiment_kwargs: Dict[str, Any] = dict(
        name=experiment_name,
        dataset_name=suite_dataset.name,
        experiment_config=experiment_config,
        prompts=prompts,
        # TODO: OPIK-5795 - 将数据库值从 'evaluation_suite' 迁移到 'test_suite'
        evaluation_method="evaluation_suite",
        tags=experiment_tags,
        dataset_version_id=None,
        project_name=project_name,
    )
    source = "experiment"
    if optimization_id is not None:
        create_experiment_kwargs["type"] = experiment_type or "trial"
        create_experiment_kwargs["optimization_id"] = optimization_id
        source = "optimization"

    experiment_ = client.create_experiment(**create_experiment_kwargs)

    items_iter, total_items = helpers.resolve_dataset_items(
        dataset_=suite_dataset,
        nb_samples=None,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=None,
        dataset_filter_string=dataset_filter_string,
    )

    if verbose >= 1:
        experiment_url = url_helpers.get_experiment_url_by_id(
            experiment_id=experiment_.id,
            dataset_id=suite_dataset.id,
            url_override=client.config.url_override,
        )
        report.display_evaluation_in_progress(experiment_url)

    eval_result, total_time = _evaluate_test_suite_task(
        client=client,
        experiment=experiment_,
        dataset=suite_dataset,
        items_iter=items_iter,
        total_items=total_items,
        task=_validated_task,
        project_name=project_name,
        verbose=verbose,
        task_threads=task_threads,
        evaluator_model=evaluator_model,
        source=source,  # type: ignore[arg-type]
        scoring_tool_strategy=scoring_tool_strategy,
    )

    suite_result = suite_result_constructor.build_suite_result(
        eval_result,
        suite_name=suite_dataset.name,
        total_time=total_time,
    )

    report_path: Optional[str] = None
    if generate_report:
        try:
            report_path = file_writer.save_report(
                suite_result,
                output_path=report_output_path,
            )
        except Exception:
            logging.getLogger(__name__).warning(
                "Failed to save test suite report file.",
                exc_info=True,
            )

    if verbose >= 1:
        displayer.display_suite_results(
            suite_result,
            verbose=verbose,
            report_path=report_path,
        )

    return suite_result


def run_tests(
    test_suite: Union[test_suite_module.TestSuite, test_suite_module.TestSuiteVersion],
    task: LLMTask,
    *,
    experiment_name: Optional[str] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    prompts: Optional[List[base_prompt.BasePrompt]] = None,
    experiment_tags: Optional[List[str]] = None,
    verbose: int = 2,
    worker_threads: int = 16,
    model: Optional[str] = None,
    generate_report: bool = True,
    report_output_path: Optional[str] = None,
    blueprint_id: Optional[str] = None,
    scoring_tool_strategy: Optional[
        suite_evaluators_strategy.ScoringToolStrategyMode
    ] = None,
) -> "suite_types.TestSuiteResult":
    """
    对任务函数运行测试套件。

    接受 :class:`TestSuite`（针对最新版本运行）或
    :class:`TestSuiteVersion`（针对特定版本快照运行）。

    任务函数接收每个测试项目的数据字典，必须返回
    一个字典（包含 ``"input"`` 和 ``"output"`` 键）或任何其他值，
    该值将自动包装为 ``{"input": <item data>, "output": <returned value>}``。

    Args:
        test_suite: 要运行的测试套件或测试套件版本。
        task: 接受字典并返回结果的可调用对象。
        experiment_name: 实验的可选显式名称。
        experiment_name_prefix: 自动生成名称的可选前缀。
        experiment_config: 实验的可选配置字典。
        prompts: 要关联的可选 Prompt 对象列表。
        experiment_tags: 实验的可选标签列表。
        verbose: 详细级别。0=静默，1=摘要，2=详细（默认）。
        worker_threads: 并行任务执行的线程数。
        model: 用于检查断言的可选模型名称。
        generate_report: 是否生成 JSON 报告文件。
        report_output_path: 报告的可选文件路径。
        scoring_tool_strategy: 应用于套件中每个 LLMJudge 评估器的可选覆盖。
            选项包括 ``"auto"``（大小+能力启发式）、``"always"``（强制代理工具循环）
            或 ``"never"``（强制单次执行）。当为 ``None`` 时，使用每个评估器自身配置的策略。

    Returns:
        基于执行策略的 TestSuiteResult，包含通过/失败状态。

    Example:
        >>> import opik
        >>> result = opik.run_tests(
        ...     test_suite=suite,
        ...     task=my_llm_function,
        ...     experiment_name="v2-prompt-test",
        ... )
        >>> print(f"Pass rate: {result.pass_rate:.0%}")
    """
    suite_dataset: Union[dataset.Dataset, dataset.DatasetVersion]
    if isinstance(test_suite, test_suite_module.TestSuiteVersion):
        suite_dataset = test_suite.__internal_api__dataset_version__
    else:
        suite_dataset = test_suite.__internal_api__dataset__
    client = suite_dataset.client

    return __internal_api__run_test_suite__(
        suite_dataset=suite_dataset,
        task=task,
        client=client,
        experiment_name_prefix=experiment_name_prefix,
        experiment_name=experiment_name,
        project_name=test_suite.project_name,
        experiment_config=experiment_config,
        prompts=prompts,
        experiment_tags=experiment_tags,
        verbose=verbose,
        task_threads=worker_threads,
        evaluator_model=model,
        generate_report=generate_report,
        report_output_path=report_output_path,
        blueprint_id=blueprint_id,
        scoring_tool_strategy=scoring_tool_strategy,
    )


def _evaluate_task(
    *,
    client: opik_client.Opik,
    experiment: experiment.Experiment,
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
    items_iter: Iterator[dataset_item.DatasetItem],
    total_items: Optional[int],
    task: LLMTask,
    scoring_metrics: List[base_metric.BaseMetric],
    project_name: Optional[str],
    verbose: int,
    task_threads: int,
    scoring_key_mapping: Optional[ScoringKeyMappingType],
    trial_count: int,
    experiment_scoring_functions: List[ExperimentScoreFunction],
    source: TraceSource,
) -> evaluation_result.EvaluationResult:
    """执行任务评估的核心函数。"""
    start_time = time.time()

    with asyncio_support.async_http_connections_expire_immediately():
        policy = dataset_execution_policy.ExecutionPolicy(
            runs_per_item=trial_count,
            pass_threshold=trial_count,
        )

        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            workers=task_threads,
            verbose=verbose,
            source=source,
        )
        test_results = evaluation_engine.run_and_score(
            dataset_items=items_iter,
            task=task,
            scoring_metrics=scoring_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=None,
            experiment_=experiment,
            default_execution_policy=policy,
            total_items=total_items,
        )

    total_time = time.time() - start_time

    # 计算实验分数
    computed_experiment_scores = evaluation_result.compute_experiment_scores(
        experiment_scoring_functions=experiment_scoring_functions,
        test_results=test_results,
    )

    if verbose >= 1:
        report.display_experiment_results(
            dataset.name, total_time, test_results, computed_experiment_scores
        )

    experiment_url = url_helpers.get_experiment_url_by_id(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        url_override=client.config.url_override,
    )

    report.display_experiment_link(experiment_url=experiment_url)

    client.flush()

    _try_notifying_about_experiment_completion(experiment)

    # 将实验分数记录到后端
    if computed_experiment_scores:
        experiment.log_experiment_scores(score_results=computed_experiment_scores)

    evaluation_result_ = evaluation_result.EvaluationResult(
        dataset_id=dataset.id,
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
        experiment_url=experiment_url,
        trial_count=trial_count,
        experiment_scores=computed_experiment_scores,
    )

    if verbose >= 2:
        report.display_evaluation_scores_statistics(
            dataset_name=dataset.name,
            evaluation_results=evaluation_result_,
        )

    return evaluation_result_


def _apply_scoring_tool_strategy_override(
    scoring_metrics: List[base_metric.BaseMetric],
    scoring_tool_strategy: suite_evaluators_strategy.ScoringToolStrategyMode,
) -> None:
    """将每个 LLMJudge 的策略选择器替换为套件级别的覆盖。

    遍历已解析的评估器列表一次；非 LLMJudge 指标保持不变。
    在 `dataset.get_evaluators(...)` 返回后执行，因此用户保持优先级：
    显式 `run_tests(scoring_tool_strategy=...)` 优先于每个评估器的实例配置。
    """
    for metric in scoring_metrics:
        if isinstance(metric, suite_evaluators_llm_judge_metric.LLMJudge):
            metric.set_scoring_tool_strategy(scoring_tool_strategy)


def _evaluate_test_suite_task(
    *,
    client: opik_client.Opik,
    experiment: experiment.Experiment,
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
    items_iter: Iterator[dataset_item.DatasetItem],
    total_items: Optional[int],
    task: LLMTask,
    project_name: Optional[str],
    verbose: int,
    task_threads: int,
    source: TraceSource,
    evaluator_model: Optional[str],
    scoring_tool_strategy: Optional[
        suite_evaluators_strategy.ScoringToolStrategyMode
    ] = None,
) -> Tuple[evaluation_result.EvaluationResult, float]:
    from opik.message_processing.processors import message_processors_chain

    start_time = time.time()

    # Activate the local emulator so suite-level LLMJudge assertions get
    # access to the full trace tree via the agentic tool loop. The emulator
    # caches every trace/span logged in-process; it stays inactive at idle.
    # Activation is ref-counted (acquire here, release in `finally`), so when
    # this connection's processing chain is shared by several concurrent
    # evaluate() runs the emulator stays active until the last one finishes.
    # `getattr` with a default keeps this MagicMock-friendly:
    # MagicMock auto-rejects attribute names that look like dunders
    # (start and end with `__`), so plain attribute access raises
    # AttributeError on mocked clients used by unit tests. Production
    # clients always have this attribute, so the default never fires.
    chain = getattr(client, "__internal_api__message_processor__", None)
    emulator = (
        message_processors_chain.get_local_emulator_message_processor(chain)
        if chain is not None
        else None
    )
    if chain is not None and emulator is not None:
        # Ref-counted activation: concurrent evaluate() runs that share this
        # connection's processing chain each acquire/release, so the emulator
        # stays active until the last run finishes (instead of the first to
        # exit deactivating it and starving the others' agentic judges).
        message_processors_chain.toggle_local_emulator_message_processor(
            active=True, chain=chain, reset=True
        )

    try:
        with asyncio_support.async_http_connections_expire_immediately():
            scoring_metrics = dataset.get_evaluators(evaluator_model)
            if scoring_tool_strategy is not None:
                _apply_scoring_tool_strategy_override(
                    scoring_metrics, scoring_tool_strategy
                )
            execution_policy = dataset.get_execution_policy()

            evaluation_engine = engine.EvaluationEngine(
                client=client,
                project_name=project_name,
                workers=task_threads,
                verbose=verbose,
                source=source,
            )
            test_results = evaluation_engine.run_and_score(
                dataset_items=items_iter,
                task=task,
                scoring_metrics=scoring_metrics,
                scoring_key_mapping=None,
                evaluator_model=evaluator_model,
                experiment_=experiment,
                default_execution_policy=execution_policy,
                total_items=total_items,
                show_scores_in_progress_bar=False,
            )
    finally:
        if chain is not None and emulator is not None:
            message_processors_chain.toggle_local_emulator_message_processor(
                active=False, chain=chain, reset=True
            )

    total_time = time.time() - start_time

    experiment_url = url_helpers.get_experiment_url_by_id(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        url_override=client.config.url_override,
    )

    evaluation_result_ = evaluation_result.EvaluationResult(
        dataset_id=dataset.id,
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
        experiment_url=experiment_url,
        trial_count=1,
        experiment_scores=[],
    )

    client.flush()

    _try_notifying_about_experiment_completion(experiment)

    return evaluation_result_, total_time


def evaluate_experiment(
    experiment_name: str,
    scoring_metrics: List[base_metric.BaseMetric],
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    scoring_threads: int = 16,
    verbose: int = 1,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    experiment_id: Optional[str] = None,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
    project_name: Optional[str] = None,
) -> evaluation_result.EvaluationResult:
    """使用新的评估指标更新现有实验。可以使用 `scoring_metrics` 或 `scorer_functions` 来计算评估指标。
    评分函数不需要 `scoring_key_mapping`，使用保留参数来接收任务的输入和输出。
    实验至少需要一个测试用例。

    Args:
        experiment_name: 要更新的实验名称。

        scoring_metrics: 评估期间要计算的指标列表。
            每个指标都有 `score(...)` 方法，该方法的参数取自 `task` 输出，
            检查所需指标的 `score` 方法签名以了解 `task` 返回字典中哪些键是必需的。

        scoring_functions: 评估期间要执行的评分函数列表。
            每个评分函数包含一个评分方法，接受评估引擎提供的预定义参数：
                • dataset_item — 包含数据集项目内容的字典，
                • task_outputs — 包含 LLM 任务输出的字典。
                • task_span - LLM 任务执行期间收集的数据 [可选]。

        scoring_threads: 运行评分指标的线程工作者数量。

        verbose: 控制评估输出日志（如摘要和 tqdm 进度条）的整数值。

        scoring_key_mapping: 允许您重命名数据集项目或任务输出中存在的键的字典，
            以便它们与评分指标期望的键匹配。例如，如果您有以下内容的数据集项目：
            {"user_question": "What is Opik ?"} 和期望键 "input" 的评分指标，
            您可以使用 scoring_key_mapping `{"input": "user_question"}` 
            将 "user_question" 键映射到 "input"。

        experiment_id: 要评估的实验 ID。如果未提供，将根据实验名称评估实验。

        experiment_scoring_functions: 计算实验级别分数的可调用函数列表。
            每个函数接受 TestResult 对象列表并返回 ScoreResult 对象列表。
            这些分数在所有测试结果收集后计算，代表整个实验的聚合指标。

        project_name: 实验所属项目的名称。如果未提供，将使用默认项目。
    """
    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )
    start_time = time.time()

    client = opik_client.get_global_client()

    if experiment_id:
        LOGGER.info("Getting experiment by id. Experiment name is ignored.")
        experiment = client.get_experiment_by_id(id=experiment_id)
    else:
        experiment = rest_operations.get_experiment_with_unique_name(
            client=client, experiment_name=experiment_name, project_name=project_name
        )

    dataset_ = client.get_dataset(
        name=experiment.dataset_name, project_name=project_name
    )

    test_cases = rest_operations.get_experiment_test_cases(
        experiment_=experiment,
        dataset_=dataset_,
        scoring_key_mapping=scoring_key_mapping,
    )
    if not test_cases:
        raise exceptions.EmptyExperiment(
            f"Experiment {experiment.id} does not have any test traces to run an evaluation"
        )

    first_trace_id = test_cases[0].trace_id
    project_name = rest_operations.get_trace_project_name(
        client=client, trace_id=first_trace_id
    )

    # 如果有评分函数则包装
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    with asyncio_support.async_http_connections_expire_immediately():
        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            workers=scoring_threads,
            verbose=verbose,
            source="experiment",
        )
        test_results = evaluation_engine.score_test_cases(
            test_cases=test_cases,
            scoring_metrics=scoring_metrics,
            scoring_key_mapping=scoring_key_mapping,
        )

    total_time = time.time() - start_time

    client.flush()

    # 计算实验分数
    computed_experiment_scores = evaluation_result.compute_experiment_scores(
        experiment_scoring_functions=experiment_scoring_functions,
        test_results=test_results,
    )

    if verbose >= 1:
        report.display_experiment_results(
            dataset_.name,
            total_time,
            test_results,
            computed_experiment_scores,
        )

    experiment_url = url_helpers.get_experiment_url_by_id(
        experiment_id=experiment.id,
        dataset_id=dataset_.id,
        url_override=client.config.url_override,
    )

    report.display_experiment_link(experiment_url=experiment_url)

    _try_notifying_about_experiment_completion(experiment)

    # 将实验分数记录到后端
    if computed_experiment_scores:
        experiment.log_experiment_scores(score_results=computed_experiment_scores)

    evaluation_result_ = evaluation_result.EvaluationResult(
        dataset_id=dataset_.id,
        experiment_id=experiment.id,
        experiment_name=experiment.name,
        test_results=test_results,
        experiment_url=experiment_url,
        trial_count=1,
        experiment_scores=computed_experiment_scores,
    )

    if verbose >= 2:
        report.display_evaluation_scores_statistics(
            dataset_name=dataset_.name,
            evaluation_results=evaluation_result_,
        )

    return evaluation_result_


def _build_prompt_evaluation_task(
    model: base_model.OpikBaseModel, messages: List[Dict[str, Any]]
) -> Callable[[Dict[str, Any]], Dict[str, Any]]:
    supported_modalities = cast(
        prompt_types.SupportedModalities,
        {
            "vision": ModelCapabilities.supports_vision(
                getattr(model, "model_name", None)
            ),
            "video": ModelCapabilities.supports_video(
                getattr(model, "model_name", None)
            ),
        },
    )
    # 禁用占位符验证，因为我们将所有数据集项目字段传递给 format()
    chat_prompt_template_ = chat_prompt_template.ChatPromptTemplate(
        messages=messages, validate_placeholders=False
    )

    required_modalities = chat_prompt_template_.required_modalities()
    unsupported_modalities = {
        modality
        for modality in required_modalities
        if not supported_modalities.get(modality, False)
    }

    if unsupported_modalities:
        modalities_list = ", ".join(sorted(unsupported_modalities))
        LOGGER.warning(
            "Model '%s' does not support %s content. Multimedia parts will be flattened "
            "to text placeholders. See %s for supported models and customization options.",
            getattr(model, "model_name", "unknown"),
            modalities_list,
            MODALITY_SUPPORT_DOC_URL,
        )

    def _prompt_evaluation_task(prompt_variables: Dict[str, Any]) -> Dict[str, Any]:
        template_type_override = prompt_variables.get("type")
        processed_messages = chat_prompt_template_.format(
            variables=prompt_variables,
            supported_modalities=supported_modalities,
            template_type=template_type_override,
        )

        with base_model.get_provider_response(
            model_provider=model, messages=processed_messages
        ) as llm_output:
            return {
                "input": processed_messages,
                "output": llm_output.choices[0].message.content,
            }

    return _prompt_evaluation_task


def evaluate_prompt(
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
    messages: List[Dict[str, Any]],
    model: Optional[Union[str, base_model.OpikBaseModel]] = None,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[base_prompt.BasePrompt] = None,
    dataset_item_ids: Optional[List[str]] = None,
    dataset_sampler: Optional[samplers.BaseDatasetSampler] = None,
    trial_count: int = 1,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
    experiment_tags: Optional[List[str]] = None,
    dataset_filter_string: Optional[str] = None,
) -> evaluation_result.EvaluationResult:
    """
    对给定数据集执行提示词评估。

    Args:
        dataset: Opik Dataset 或 DatasetVersion 实例

        messages: 要评估的提示消息列表。

        model: 用于评估的模型名称。默认为 "gpt-3.5-turbo"。

        scoring_metrics: 评估期间要计算的指标列表。
            LLM 输入和输出将作为参数传递给每个指标的 `score(...)` 方法。

        scoring_functions: 评估期间要执行的评分函数列表。
            每个评分函数包含一个评分方法，接受评估引擎提供的预定义参数：
                • dataset_item — 包含数据集项目内容的字典，
                • task_outputs — 包含 LLM 任务输出的字典。
                • task_span - LLM 任务执行期间收集的数据 [可选]。

        experiment_name_prefix: 添加到自动生成的实验名称前的前缀，使其唯一
            但分组在同一前缀下。例如，如果设置 `experiment_name_prefix="my-experiment"`，
            创建的第一个实验将命名为 `my-experiment-<unique-random-part>`。

        experiment_name: 实验名称。

        project_name: 已弃用。如果数据集设置了 ``project_name``，将始终使用该值，
            此覆盖将被忽略（并显示警告）。如果数据集没有 ``project_name``，
            跟踪和跨度将记录到此项目（省略时记录到 ``Default Project``）。

        experiment_config: 实验配置。

        verbose: 控制评估输出日志（如摘要和 tqdm 进度条）的整数值。

        nb_samples: 要评估的样本数。

        task_threads: 运行评分指标的线程工作者数量。

        prompt: 要与实验关联的 Prompt 对象。

        dataset_item_ids: 要评估的数据集项目 ID 列表。如果未提供，将评估数据集中的所有样本。

        dataset_sampler: 用于采样数据集项目进行评估的数据集采样器实例。
            如果未提供，将评估数据集中的所有样本。

        trial_count: 对每个数据集项目执行提示和评估 LLM 输出的次数。

        experiment_scoring_functions: 计算实验级别分数的可调用函数列表。
            每个函数接受 TestResult 对象列表并返回 ScoreResult 对象列表。
            这些分数在所有测试结果收集后计算，代表整个实验的聚合指标。

        experiment_tags: 要与实验关联的标签列表。

        dataset_filter_string: 可选的 OQL 过滤字符串，用于过滤数据集项目。
            支持按标签、数据字段、元数据等过滤。

            支持的列包括：
            - `id`、`source`、`trace_id`、`span_id`：字符串字段
            - `data`：字典字段（使用点表示法，例如 "data.category"）
            - `tags`：列表字段（使用 "contains" 运算符）
            - `created_at`、`last_updated_at`：日期时间字段（ISO 8601 格式）
            - `created_by`、`last_updated_by`：字符串字段

            示例：
            - `tags contains "failed"` - 带有 'failed' 标签的项目
            - `data.category = "test"` - 具有特定数据字段值的项目
            - `created_at >= "2024-01-01T00:00:00Z"` - 在日期之后创建的项目
    """
    if isinstance(dataset, test_suite_module.TestSuite):
        # 过渡期间的向后兼容性
        dataset = dataset.__internal_api__dataset__

    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )
    if isinstance(model, str):
        opik_model = models_factory.get(model_name=model)
    elif not isinstance(model, base_model.OpikBaseModel):
        raise ValueError("`model` must be either a string or an OpikBaseModel instance")
    else:
        opik_model = model

    if experiment_config is None:
        experiment_config = {
            "prompt_template": messages,
            "model": opik_model.model_name,
        }
    else:
        if "prompt_template" not in experiment_config:
            experiment_config["prompt_template"] = messages

        if "model" not in experiment_config:
            experiment_config["model"] = opik_model.model_name

    client = opik_client.get_global_client()

    prompts = [prompt] if prompt else None

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    project_name = helpers.resolve_project_name(
        value_from_dataset=dataset.project_name,
        value_from_user=project_name,
        caller_name="evaluate_prompt",
    )

    experiment_config = resume_integration.resume_state_for_evaluate(
        experiment_config=experiment_config,
        dataset_=dataset,
        trial_count=trial_count,
        dataset_filter_string=dataset_filter_string,
        nb_samples=nb_samples,
        dataset_sampler=dataset_sampler,
        dataset_item_ids=dataset_item_ids,
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=prompts,
        tags=experiment_tags,
        dataset_version_id=getattr(dataset.get_version_info(), "id", None),
        project_name=project_name,
    )

    items_iter, total_items = helpers.resolve_dataset_items(
        dataset_=dataset,
        nb_samples=nb_samples,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
        dataset_filter_string=dataset_filter_string,
    )
    items_iter, total_items, resolved_ids = _materialize_for_checkpoint(
        items_iter=items_iter,
        total_items=total_items,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
    )
    resume_integration.write_checkpoint_if_needed(
        experiment_id=experiment.id,
        resolved_ids=resolved_ids,
    )

    # 如果有评分函数则包装
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    start_time = time.time()

    with asyncio_support.async_http_connections_expire_immediately():
        policy = dataset_execution_policy.ExecutionPolicy(
            runs_per_item=trial_count,
            pass_threshold=trial_count,
        )

        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            workers=task_threads,
            verbose=verbose,
            source="experiment",
        )
        test_results = evaluation_engine.run_and_score(
            dataset_items=items_iter,
            task=_build_prompt_evaluation_task(model=opik_model, messages=messages),
            scoring_metrics=scoring_metrics,
            scoring_key_mapping=None,
            evaluator_model=None,
            experiment_=experiment,
            default_execution_policy=policy,
            total_items=total_items,
        )

    total_time = time.time() - start_time

    # 计算实验分数
    computed_experiment_scores = evaluation_result.compute_experiment_scores(
        experiment_scoring_functions=experiment_scoring_functions,
        test_results=test_results,
    )

    if verbose >= 1:
        report.display_experiment_results(
            dataset.name, total_time, test_results, computed_experiment_scores
        )

    experiment_url = url_helpers.get_experiment_url_by_id(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        url_override=client.config.url_override,
    )

    report.display_experiment_link(experiment_url=experiment_url)

    client.flush()

    _try_notifying_about_experiment_completion(experiment)

    # 将实验分数记录到后端
    if computed_experiment_scores:
        experiment.log_experiment_scores(score_results=computed_experiment_scores)

    evaluation_result_ = evaluation_result.EvaluationResult(
        experiment_id=experiment.id,
        dataset_id=dataset.id,
        experiment_name=experiment.name,
        test_results=test_results,
        experiment_url=experiment_url,
        trial_count=trial_count,
        experiment_scores=computed_experiment_scores,
    )

    if verbose >= 2:
        report.display_evaluation_scores_statistics(
            dataset_name=dataset.name,
            evaluation_results=evaluation_result_,
        )

    return evaluation_result_


def evaluate_optimization_trial(
    optimization_id: str,
    dataset: Union[dataset.Dataset, dataset.DatasetVersion],
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    experiment_name_prefix: Optional[str] = None,
    experiment_name: Optional[str] = None,
    project_name: Optional[str] = None,
    experiment_config: Optional[Dict[str, Any]] = None,
    verbose: int = 1,
    nb_samples: Optional[int] = None,
    task_threads: int = 16,
    prompt: Optional[base_prompt.BasePrompt] = None,
    prompts: Optional[List[base_prompt.BasePrompt]] = None,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    dataset_item_ids: Optional[List[str]] = None,
    dataset_sampler: Optional[samplers.BaseDatasetSampler] = None,
    trial_count: int = 1,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
    experiment_tags: Optional[List[str]] = None,
    dataset_filter_string: Optional[str] = None,
) -> evaluation_result.EvaluationResult:
    """
    对给定数据集执行任务评估。

    Args:
        optimization_id: 与实验关联的优化 ID。

        dataset: Opik Dataset 或 DatasetVersion 实例

        task: 可调用对象，接受包含数据集项目内容的字典作为输入，
            返回稍后用于评分的字典。

        scoring_functions: 评估期间要执行的评分函数列表。
            每个评分函数包含一个评分方法，接受评估引擎提供的预定义参数：
                • dataset_item — 包含数据集项目内容的字典，
                • task_outputs — 包含 LLM 任务输出的字典。
                • task_span - LLM 任务执行期间收集的数据 [可选]。

        experiment_name_prefix: 添加到自动生成的实验名称前的前缀，使其唯一
            但分组在同一前缀下。例如，如果设置 `experiment_name_prefix="my-experiment"`，
            创建的第一个实验将命名为 `my-experiment-<unique-random-part>`。

        experiment_name: 与评估运行关联的实验名称。
            如果为 None，将使用生成的名称。

        project_name: 已弃用。如果数据集设置了 ``project_name``，将始终使用该值，
            此覆盖将被忽略（并显示警告）。如果数据集没有 ``project_name``，
            跟踪和跨度将记录到此项目（省略时记录到 ``Default Project``）。

        experiment_config: 描述实验参数的字典

        scoring_metrics: 评估期间要计算的指标列表。
            每个指标都有 `score(...)` 方法，该方法的参数取自 `task` 输出，
            检查所需指标的 `score` 方法签名以了解 `task` 返回字典中哪些键是必需的。
            如果未提供值，实验将没有任何评分指标。

        verbose: 控制评估输出日志（如摘要和 tqdm 进度条）的整数值。
            0 - 无输出，1 - 启用输出（默认）。

        nb_samples: 要评估的样本数。如果未提供值，将评估数据集中的所有样本。

        task_threads: 运行任务的线程工作者数。如果设置为 1，不会创建额外的线程，
            所有任务在当前线程中顺序执行。
            如果您的任务对象支持跨线程共享，请使用多个工作者。

        prompt: 要与实验关联的 Prompt 对象。已弃用，请改用 `prompts` 参数。

        prompts: 要与实验关联的 Prompt 对象列表。

        scoring_key_mapping: 允许您重命名数据集项目或任务输出中存在的键的字典，
            以便它们与评分指标期望的键匹配。例如，如果您有以下内容的数据集项目：
            {"user_question": "What is Opik ?"} 和期望键 "input" 的评分指标，
            您可以使用 scoring_key_mapping `{"input": "user_question"}` 
            将 "user_question" 键映射到 "input"。

        dataset_item_ids: 要评估的数据集项目 ID 列表。如果未提供，将评估数据集中的所有样本。

        dataset_sampler: 用于采样数据集项目进行评估的数据集采样器实例。
            如果未提供，将评估数据集中的所有样本。

        trial_count: 对每个数据集项目执行提示和评估 LLM 输出的次数。

        experiment_scoring_functions: 计算实验级别分数的可调用函数列表。
            每个函数接受 TestResult 对象列表并返回 ScoreResult 对象列表。
            这些分数在所有测试结果收集后计算，代表整个实验的聚合指标。

        experiment_tags: 要与实验关联的标签列表。

        dataset_filter_string: 可选的 OQL 过滤字符串，用于过滤数据集项目。
            支持按标签、数据字段、元数据等过滤。

            支持的列包括：
            - `id`、`source`、`trace_id`、`span_id`：字符串字段
            - `data`：字典字段（使用点表示法，例如 "data.category"）
            - `tags`：列表字段（使用 "contains" 运算符）
            - `created_at`、`last_updated_at`：日期时间字段（ISO 8601 格式）
            - `created_by`、`last_updated_by`：字符串字段

            示例：
            - `tags contains "failed"` - 带有 'failed' 标签的项目
            - `data.category = "test"` - 具有特定数据字段值的项目
            - `created_at >= "2024-01-01T00:00:00Z"` - 在日期之后创建的项目
    """
    if isinstance(dataset, test_suite_module.TestSuite):
        # 过渡期间的向后兼容性
        dataset = dataset.__internal_api__dataset__

    experiment_scoring_functions = (
        [] if experiment_scoring_functions is None else experiment_scoring_functions
    )

    if scoring_metrics is None:
        scoring_metrics = []

    checked_prompts = experiment_helpers.handle_prompt_args(
        prompt=prompt,
        prompts=prompts,
    )

    project_name = helpers.resolve_project_name(
        value_from_dataset=dataset.project_name,
        value_from_user=project_name,
        caller_name="evaluate_optimization_trial",
    )

    # 如果有评分函数则包装
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    client = opik_client.get_global_client()

    experiment_name = _use_or_create_experiment_name(
        experiment_name=experiment_name,
        experiment_name_prefix=experiment_name_prefix,
    )

    experiment_config = resume_integration.resume_state_for_evaluate(
        experiment_config=experiment_config,
        dataset_=dataset,
        trial_count=trial_count,
        dataset_filter_string=dataset_filter_string,
        nb_samples=nb_samples,
        dataset_sampler=dataset_sampler,
        dataset_item_ids=dataset_item_ids,
    )

    experiment = client.create_experiment(
        name=experiment_name,
        dataset_name=dataset.name,
        experiment_config=experiment_config,
        prompts=checked_prompts,
        type="trial",
        optimization_id=optimization_id,
        tags=experiment_tags,
        dataset_version_id=getattr(dataset.get_version_info(), "id", None),
        project_name=project_name,
    )

    items_iter, total_items = helpers.resolve_dataset_items(
        dataset_=dataset,
        nb_samples=nb_samples,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
        dataset_filter_string=dataset_filter_string,
    )
    items_iter, total_items, resolved_ids = _materialize_for_checkpoint(
        items_iter=items_iter,
        total_items=total_items,
        dataset_item_ids=dataset_item_ids,
        dataset_sampler=dataset_sampler,
    )
    resume_integration.write_checkpoint_if_needed(
        experiment_id=experiment.id,
        resolved_ids=resolved_ids,
    )

    return _evaluate_task(
        client=client,
        experiment=experiment,
        dataset=dataset,
        items_iter=items_iter,
        total_items=total_items,
        task=task,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
        verbose=verbose,
        task_threads=task_threads,
        scoring_key_mapping=scoring_key_mapping,
        trial_count=trial_count,
        experiment_scoring_functions=experiment_scoring_functions,
        source="optimization",
    )


def evaluate_resume(
    experiment_id: str,
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    experiment_scoring_functions: Optional[List[ExperimentScoreFunction]] = None,
    *,
    verbose: int = 1,
    task_threads: int = 16,
) -> evaluation_result.EvaluationResult:
    """
    恢复中断的 ``evaluate()`` 运行。

    读取现有实验中嵌入的恢复状态（数据集版本、默认试验次数、数据集过滤字符串、nb_samples）
    以及在写入时解析的项目 ID 的本地检查点（采样器或显式 ``dataset_item_ids`` 情况）。
    已完成预期运行次数的项目将被跳过；少于预期次数的项目将执行剩余试验。

    ``task`` 和所有评分可调用对象/映射必须重新提供 —
    Python 可调用对象无法在后端持久化，``scoring_key_mapping`` 是指标感知的
    （它命名特定指标期望的键），因此它与这些指标一起传递。

    返回的 ``EvaluationResult`` 描述**完整实验**，而不仅仅是本次调用的部分：
    ``test_results`` 是新执行的运行和先前运行完成的项目重建的 TestResults 的并集
    （使用它们已存储的反馈分数）。``experiment_scoring_functions`` 在该并集上运行，
    因此聚合分数与全新的完整 ``evaluate()`` 将产生的分数匹配。

    Args:
        experiment_id: 要恢复的实验 ID。
        task: 要为每个待处理数据集项目运行的可调用对象。必须与原始运行中使用的匹配；
            框架不强制一致性。
        scoring_metrics: 每个项目评分指标。应用于此恢复调用执行的运行。
            先前完成的项目保留其原始存储的分数（不重新评分）。
        scoring_functions: 每个项目评分函数，在内部包装为指标。
            与 ``scoring_metrics`` 语义相同。
        scoring_key_mapping: 重命名数据集/任务输出键的字典，使其与
            ``scoring_metrics`` 期望的键匹配。必须与传递的指标一致；
            框架不强制与原始运行的一致性。
        experiment_scoring_functions: 聚合评分可调用对象，接受 ``TestResult`` 对象列表
            并返回 ``ScoreResult``。在合并集（先前完成 + 新执行）上计算，
            因此聚合分数反映完整实验。
        verbose: 详细级别（0 静默，1 默认，2 详细统计）。
        task_threads: 任务执行的工作线程数。

    Returns:
        表示此恢复调用后完整实验的 ``EvaluationResult``。
        ``test_results`` 涵盖重建的先前项目和此调用执行的项目。

    Raises:
        opik.exceptions.ExperimentNotFound: 当实验不存在时。
        ExperimentNotResumable: 当实验使用阻止安全恢复的配置创建时
            （例如，未嵌入恢复状态的旧 SDK 版本）。
        LocalCheckpointMissing: 当实验需要解析 ID 的本地检查点
            且检查点文件不在此机器上时。从写入检查点的原始机器恢复，
            或通过新的 ``evaluate()`` 调用重新提供原始 ``dataset_item_ids``。
    """
    experiment_scoring_functions = experiment_scoring_functions or []

    client = opik_client.get_global_client()
    context = resume_module.prepare_resume_context(client, experiment_id)

    items = _resolve_resume_items(context)
    pending = list(resume_module.build_pending_items_iterator(iter(items), context))

    if not pending:
        LOGGER.info(
            "Experiment %s is already fully evaluated; nothing to do.",
            experiment_id,
        )

    project_name = context.experiment.project_name
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    # 在 ``_evaluate_task`` 开始写入新实验项目之前快照已完成的运行，
    # 否则恢复调用自身的新试验会在合并结果中被重复计算。
    previous_test_results = resume_merge.reconstruct_previous_test_results(
        experiment=context.experiment,
        dataset_=context.dataset,
    )

    new_result = _evaluate_task(
        client=client,
        experiment=context.experiment,
        dataset=context.dataset,
        items_iter=iter(pending),
        total_items=len(pending),
        task=task,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
        verbose=verbose,
        task_threads=task_threads,
        scoring_key_mapping=scoring_key_mapping,
        trial_count=context.default_runs_per_item,
        experiment_scoring_functions=experiment_scoring_functions,
        source="experiment",
    )

    merged = evaluation_result.merge_resume_results(
        new_result=new_result,
        previous_test_results=previous_test_results,
    )

    # ``_evaluate_task`` 已经记录了 ``experiment_scoring_functions``
    # 在新重放的切片上。在合并集上重新计算并覆盖 —
    # 最终写入反映整个实验，这是用户（和下游读者）实际想要的。
    # 我们可以接受后端在两次写入之间的短暂切片窗口；
    # 这里的速率限制/并发读取风险可以忽略不计。
    merged_scores = evaluation_result.compute_experiment_scores(
        experiment_scoring_functions=experiment_scoring_functions,
        test_results=merged.test_results,
    )
    if merged_scores:
        context.experiment.log_experiment_scores(score_results=merged_scores)
    merged.experiment_scores = merged_scores

    return merged


def _resolve_resume_items(
    context: "resume_module.ResumeContext",
) -> List[dataset_item.DatasetItem]:
    """
    解析恢复运行的候选项目集。

    当本地检查点固定了解析的 ID 时，按原样使用（采样器/显式 ID 决策
    已被原始调用锁定）。否则通过原始 ``dataset_filter_string`` 和
    ``nb_samples`` 迭代，针对版本固定的数据集。
    """
    if context.candidate_dataset_item_ids is not None:
        items_iter, _ = helpers.resolve_dataset_items(
            dataset_=context.dataset,
            nb_samples=None,
            dataset_item_ids=context.candidate_dataset_item_ids,
            dataset_sampler=None,
            dataset_filter_string=None,
        )
        return list(items_iter)
    items_iter, _ = helpers.resolve_dataset_items(
        dataset_=context.dataset,
        nb_samples=context.nb_samples,
        dataset_item_ids=None,
        dataset_sampler=None,
        dataset_filter_string=context.dataset_filter_string,
    )
    return list(items_iter)


def evaluate_on_dict_items(
    items: List[Dict[str, Any]],
    task: LLMTask,
    scoring_metrics: Optional[List[base_metric.BaseMetric]] = None,
    scoring_functions: Optional[List[scorer_function.ScorerFunction]] = None,
    project_name: Optional[str] = None,
    verbose: int = 0,
    scoring_key_mapping: Optional[ScoringKeyMappingType] = None,
    scoring_threads: int = 16,
) -> evaluation_result.EvaluationResultOnDictItems:
    """
    轻量级评估函数，在数据集项目（作为字典）上评估任务，
    无需 Dataset 对象或创建实验。

    此函数适用于需要使用 Opik 的指标基础设施快速评估多个候选解决方案的优化场景。
    它创建跟踪用于追踪，但不需要实验设置或数据集管理。

    Args:
        items: 数据集项目内容列表（包含要评估数据的字典）。

        task: 可调用对象，接受包含数据集项目内容的字典作为输入，
            返回稍后用于评分的字典。

        scoring_metrics: 评估期间要计算的指标列表。
            每个指标的 `score(...)` 方法将使用从数据集项目和任务输出中获取的参数调用。

        scoring_functions: 评估期间要执行的评分函数列表。
            每个评分函数接受预定义参数：
                • dataset_item — 包含数据集项目内容的字典，
                • task_outputs — 包含 LLM 任务输出的字典。

        project_name: 用于记录跟踪的项目名称。

        verbose: 控制评估输出日志和进度条。
            0 - 无输出（默认），1 - 启用输出。

        scoring_key_mapping: 允许您重命名数据集项目或任务输出中存在的键的字典，
            以便它们与评分指标期望的键匹配。

        scoring_threads: 运行评分指标的线程工作者数。

    Returns:
        EvaluationResultOnDictItems 对象，包含测试结果并提供聚合分数的方法，
        类似于常规评估结果。

    Example:
        ```python
        import opik
        from opik.evaluation.metrics import Equals

        items = [
            {"input": "What is 2+2?", "expected_output": "4"},
            {"input": "What is 3+3?", "expected_output": "6"},
        ]

        def my_task(item):
            # 您的 LLM 调用在此
            question = item["input"]
            # ... 调用模型 ...
            return {"output": model_output}

        result = opik.evaluate_on_dict_items(
            items=items,
            task=my_task,
            scoring_metrics=[Equals()],
            scoring_key_mapping={"reference": "expected_output"},
        )

        # 访问单个测试结果
        for test_result in result.test_results:
            print(f"Score: {test_result.score_results[0].value}")

        # 获取聚合统计信息
        aggregated = result.aggregate_evaluation_scores()
        print(f"Mean equals score: {aggregated['equals_metric'].mean}")
        ```
    """
    # 如果有评分函数则包装
    scoring_metrics = _wrap_scoring_functions(
        scoring_functions=scoring_functions,
        scoring_metrics=scoring_metrics,
        project_name=project_name,
    )

    if not scoring_metrics:
        LOGGER.warning("未提供用于项目评估的评分指标")
        return evaluation_result.EvaluationResultOnDictItems(test_results=[])

    client = opik_client.get_global_client()

    with asyncio_support.async_http_connections_expire_immediately():
        dataset_items = [
            dataset_item.DatasetItem(id=f"temp_item_{i}", **item)
            for i, item in enumerate(items)
        ]
        policy = dataset_execution_policy.ExecutionPolicy(
            runs_per_item=1, pass_threshold=1
        )

        evaluation_engine = engine.EvaluationEngine(
            client=client,
            project_name=project_name,
            workers=scoring_threads,
            verbose=verbose,
            source="experiment",
        )
        test_results = evaluation_engine.run_and_score(
            dataset_items=iter(dataset_items),
            task=task,
            scoring_metrics=scoring_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=None,
            experiment_=None,
            default_execution_policy=policy,
            total_items=len(dataset_items),
        )

    return evaluation_result.EvaluationResultOnDictItems(
        test_results=test_results,
    )


def _wrap_scoring_functions(
    scoring_functions: Optional[List[scorer_function.ScorerFunction]],
    scoring_metrics: Optional[List[base_metric.BaseMetric]],
    project_name: Optional[str],
) -> List[base_metric.BaseMetric]:
    if scoring_functions:
        function_metrics = scorer_wrapper_metric.wrap_scorer_functions(
            scoring_functions, project_name=project_name
        )
        if scoring_metrics:
            scoring_metrics.extend(function_metrics)
        else:
            scoring_metrics = function_metrics

    return scoring_metrics if scoring_metrics else []


def _use_or_create_experiment_name(
    experiment_name: Optional[str], experiment_name_prefix: Optional[str]
) -> Optional[str]:
    if experiment_name:
        return experiment_name

    if experiment_name_prefix:
        return experiment_helpers.generate_unique_experiment_name(
            experiment_name_prefix
        )
    else:
        return None
