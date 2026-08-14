import functools
import logging
import time
from typing import List, Optional, Iterator, Callable

import opik
import opik.logging_messages as logging_messages
import opik.opik_context as opik_context
from opik.api_objects import opik_client, trace, local_recording
from opik.api_objects.dataset import dataset_item
from opik.api_objects.experiment import experiment
from opik.api_objects.dataset import execution_policy as dataset_execution_policy
from opik.evaluation import rest_operations, test_case, test_result
from opik.evaluation.suite_evaluators.agentic import context as agentic_context
from opik.evaluation.suite_evaluators.agentic.context import INTERNAL_SPAN_TAG
from opik.evaluation.types import ErrorTolerance, LLMTask, ScoringKeyMappingType
from opik.message_processing.emulation import models
from opik.message_processing.processors import message_processors_chain
from opik.types import TraceSource

from . import evaluation_tasks_executor, exception_analyzer, helpers, metrics_evaluator
from .types import EvaluationTask
from ..metrics import base_metric, score_result


LOGGER = logging.getLogger(__name__)

EVALUATION_TASK_NAME = "evaluation_task"

DEFAULT_STREAMER_DRAIN_TIMEOUT_SECONDS = 5.0


def get_item_execution_policy(
    item: dataset_item.DatasetItem,
    default_policy: dataset_execution_policy.ExecutionPolicy,
) -> dataset_execution_policy.ExecutionPolicy:
    """
    获取数据集项目的执行策略。

    如果项目有自己的执行策略，则将其与默认策略合并。
    项目级的值覆盖默认值。

    Args:
        item: 数据集项目。
        default_policy: 来自套件级别的默认执行策略。

    Returns:
        此项目合并后的执行策略。
    """
    if item.execution_policy is None:
        return default_policy

    return {
        "runs_per_item": (
            item.execution_policy.runs_per_item
            if item.execution_policy.runs_per_item is not None
            else default_policy.get("runs_per_item", 1)
        ),
        "pass_threshold": (
            item.execution_policy.pass_threshold
            if item.execution_policy.pass_threshold is not None
            else default_policy.get("pass_threshold", 1)
        ),
    }


class EvaluationEngine:
    """
    无状态的评估执行器。

    仅存储配置（客户端、工作线程数、详细级别）。
    所有流程相关的数据（指标、键映射）都作为方法参数传递。
    """

    def __init__(
        self,
        client: opik_client.Opik,
        project_name: Optional[str],
        workers: int,
        verbose: int,
        source: TraceSource,
        error_tolerance: ErrorTolerance,
        flush_timeout: Optional[float] = None,
    ) -> None:
        self._client = client
        self._project_name = project_name
        self._workers = workers
        self._verbose = verbose
        self._source = source
        self._error_tolerance = error_tolerance
        if flush_timeout is None:
            flush_timeout = DEFAULT_STREAMER_DRAIN_TIMEOUT_SECONDS
        self._flush_timeout = flush_timeout

    # --- 私有：指标与评分 ---

    @opik.track(  # type: ignore[attr-defined,has-type]
        name="metrics_calculation",
        tags=[INTERNAL_SPAN_TAG],
        ignore_arguments=[
            "regular_metrics",
            "scoring_key_mapping",
            "evaluator_model",
            # `trace_data` 是由 LLM 任务路径传入的内存中的 TraceData，
            # 使代理判断器能在其 CreateTraceMessage 发出之前看到该 trace。
            # 否则它会将包装 trace 自身的数据重复复制到此 span 的输入中。
            # 参见 `_build_trace_tool_context`。
            "trace_data",
        ],
    )
    def _compute_test_result_for_test_case(
        self,
        test_case_: test_case.TestCase,
        regular_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: ScoringKeyMappingType,
        evaluator_model: Optional[str],
        trial_id: int = 0,
        trace_data: Optional[trace.TraceData] = None,
    ) -> test_result.TestResult:
        item_evaluator = metrics_evaluator.build_metrics_evaluator(
            item=test_case_.dataset_item,
            regular_metrics=regular_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=evaluator_model,
            error_tolerance=self._error_tolerance,
        )
        # `trace_data` 是来自外围 `_compute_test_result_for_llm_task` 调用的
        # 内存中的 `TraceData`。当它存在时，代理上下文直接由它构建，
        # 因为此 trace 的 `CreateTraceMessage` 直到外围的
        # `evaluate_llm_task_context` 的 `__exit__` 运行时才会发送到模拟器
        # —— 也就是在此评分调用返回之后。
        # 完整理由参见 `build_trace_tool_context_from_trace_data`。
        #
        # 重新评分的代码路径（没有实时任务）会让 `trace_data=None`，
        # 并回退到基于查找的辅助函数，这在那种情况下是正确的，
        # 因为该 trace 是在之前的运行中记录的。
        trace_tool_context = self._build_trace_tool_context(
            trace_id=test_case_.trace_id, trace_data=trace_data
        )
        score_results, mapped_scoring_inputs = item_evaluator.compute_regular_scores(
            dataset_item_content=test_case_.dataset_item_content,
            task_output=test_case_.task_output,
            trace_tool_context=trace_tool_context,
        )
        test_case_.mapped_scoring_inputs = mapped_scoring_inputs

        test_result_ = test_result.TestResult(
            test_case=test_case_,
            score_results=score_results,
            trial_id=trial_id,
        )
        rest_operations.log_test_result_feedback_scores(
            client=self._client,
            score_results=score_results,
            trace_id=test_case_.trace_id,
            project_name=self._project_name,
        )
        return test_result_

    @opik.track(  # type: ignore[attr-defined,has-type]
        name="task_span_metrics_calculation",
        tags=[INTERNAL_SPAN_TAG],
        ignore_arguments=["test_case_", "task_span_evaluator"],
    )
    def _compute_scores_for_test_case_with_task_span(
        self,
        trace_id: str,
        task_span: models.SpanModel,
        test_case_: test_case.TestCase,
        task_span_evaluator: metrics_evaluator.MetricsEvaluator,
    ) -> List[score_result.ScoreResult]:
        score_results, mapped_scoring_inputs = (
            task_span_evaluator.compute_task_span_scores(
                dataset_item_content=test_case_.dataset_item_content,
                task_output=test_case_.task_output,
                task_span=task_span,
            )
        )
        test_case_.mapped_scoring_inputs = mapped_scoring_inputs

        # 记录反馈分数
        rest_operations.log_test_result_feedback_scores(
            client=self._client,
            score_results=score_results,
            trace_id=trace_id,
            project_name=self._project_name,
        )
        return score_results

    def _build_trace_tool_context(
        self,
        trace_id: str,
        trace_data: Optional[trace.TraceData] = None,
    ) -> Optional[agentic_context.TraceToolContext]:
        """如果本地模拟器处于活动状态，则返回一个 TraceToolContext。

        根据 trace 是否已经被记录，有两种构建路径：

        - **实时 LLM 任务评分**（提供了 `trace_data`）：评分在
          `evaluate_llm_task_context` *内部*运行，此时 trace 的
          `CreateTraceMessage` 尚未发送。模拟器已包含 span
          （由 `@opik.track` 内联发送），但不包含 trace 本身，
          因此我们从内存中的 `TraceData` 合成一个 `TraceModel`，
          并从模拟器中提取 span。这是在实时运行期间唯一真正
          参与代理循环的路径 —— 完整的顺序理由参见
          `build_trace_tool_context_from_trace_data`。

        - **重新评分**（`trace_data` 为 None）：trace 是在之前的运行中
          记录的，因此基于模拟器查找的路径是正确的。
          如果 trace 不在模拟器中，则返回 None（LLMJudge 会回退到
          其一次性路径 —— 参见 `LLMJudge.score`）。

        当模拟器不在处理器链中或处于非活动状态时，无论适用哪种构建路径，
        都会返回 None。
        """
        # 带默认值的 `getattr` 使其对 `test_evaluate_test_suite.py` 中的
        # 单元测试保持 MagicMock 友好：MagicMock 会自动拒绝看起来像
        # 双下划线方法的属性名（以 `__` 开头和结尾），因此对模拟客户端
        # 进行普通属性访问会引发 AttributeError。生产客户端始终具有
        # 此属性，因此默认分支在那里永远不会触发。
        chain = getattr(self._client, "__internal_api__message_processor__", None)
        if chain is None:
            return None
        emulator = message_processors_chain.get_local_emulator_message_processor(chain)
        if emulator is None or not emulator.is_active():
            return None
        # 排空待处理消息，使刚运行的任务体期间由 `@opik.track` 发出的
        # span（包括任何因函数抛出而携带 error_info 的 span）在读取之前
        # 已应用到模拟器。`client.__internal_api__span__` 只是向流队列
        # *提交* —— 消费者线程异步处理，如果没有这次排空，就会存在
        # 代理判断器看到陈旧视图的短暂窗口。使用有界超时，使卡住的
        # 消费者不会永远阻塞评分；超时后我们以模拟器中当前的任何状态
        # 继续执行（尽力而为）。
        drained = self._client.__internal_api__drain_to_processors__(
            timeout=self._flush_timeout
        )
        if not drained:
            LOGGER.debug(
                "[engine] 在构建代理上下文之前流排空超时；"
                "代理判断器可能只看到 trace %s 的部分 trace 状态",
                trace_id,
            )
        # 强制重建 trace_trees，使本进程中记录的 span 在我们将其复制到
        # 上下文之前已附加到 trace。无论构建路径如何都需要这样做 ——
        # span 是我们最终读取的内容。
        _ = emulator.trace_trees
        if trace_data is not None:
            return agentic_context.build_trace_tool_context_from_trace_data(
                trace_data=trace_data, emulator=emulator
            )
        return agentic_context.build_trace_tool_context(
            trace_id=trace_id, emulator=emulator
        )

    # --- 私有：任务执行 ---

    def _compute_test_result_for_llm_task(
        self,
        item: dataset_item.DatasetItem,
        task: LLMTask,
        trial_id: int,
        experiment_: Optional[experiment.Experiment],
        regular_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: ScoringKeyMappingType,
        evaluator_model: Optional[str],
    ) -> test_result.TestResult:
        if not hasattr(task, "opik_tracked"):
            name = task.__name__ if hasattr(task, "__name__") else "llm_task"
            task = opik.track(name=name, source=self._source)(task)  # type: ignore[attr-defined,has-type]

        item_content = item.get_content(include_id=True)
        trace_data = trace.TraceData(
            input=item_content,
            name=EVALUATION_TASK_NAME,
            created_by="evaluation",
            project_name=self._project_name,
            source=self._source,
        )

        execution_policy_dict = None
        if item.execution_policy is not None:
            execution_policy_dict = item.execution_policy.model_dump(exclude_none=True)

        with helpers.evaluate_llm_task_context(
            experiment=experiment_,
            dataset_item_id=item.id,
            trace_data=trace_data,
            client=self._client,
            execution_policy=execution_policy_dict or None,
        ) as eval_state:
            if experiment_ is not None and experiment_.prompts:
                for prompt_obj in experiment_.prompts:
                    opik_context.attach_prompt_to_current_trace(prompt_obj)

            LOGGER.debug("[engine] 项目 %s 的任务已开始", item.id)
            task_start = time.perf_counter()
            try:
                task_output_ = task(item_content)
            except Exception as exception:
                LOGGER.error("[engine] 项目 %s 的任务失败：%s", item.id, exception)
                if exception_analyzer.is_llm_provider_rate_limit_error(exception):
                    LOGGER.error(
                        logging_messages.LLM_PROVIDER_RATE_LIMIT_ERROR_DETECTED_IN_EVALUATE_FUNCTION
                    )

                raise
            task_execution_time = time.perf_counter() - task_start
            LOGGER.debug(
                "[engine] 项目 %s 的任务已完成，耗时 %.1fs", item.id, task_execution_time
            )
            LOGGER.debug(
                "[diag] trial_id=%s trace_id=%s task_output=%r",
                trial_id,
                trace_data.id,
                task_output_,
            )

            opik_context.update_current_trace(output=task_output_)

            test_case_ = test_case.TestCase(
                trace_id=trace_data.id,
                dataset_item_id=item.id,
                task_output=task_output_,
                dataset_item_content=item_content,
                dataset_item=item,
            )
            LOGGER.debug("[engine] 项目 %s 的评分已开始", item.id)
            scoring_start = time.perf_counter()
            test_result_ = self._compute_test_result_for_test_case(
                test_case_=test_case_,
                regular_metrics=regular_metrics,
                scoring_key_mapping=scoring_key_mapping,
                evaluator_model=evaluator_model,
                trial_id=trial_id,
                # 将内存中的 TraceData 交给评分，使代理判断器能在其
                # CreateTraceMessage 发送到模拟器之前看到该 trace
                # （发送发生在外围上下文管理器的 `__exit__` 中，
                # 在我们返回之后）。详情参见 `_build_trace_tool_context`。
                trace_data=trace_data,
            )
            test_result_.task_execution_time = task_execution_time
            test_result_.scoring_time = time.perf_counter() - scoring_start
            LOGGER.debug(
                "[engine] 项目 %s 的评分已完成，耗时 %.1fs",
                item.id,
                test_result_.scoring_time,
            )
            LOGGER.debug(
                "[diag] trial_id=%s trace_id=%s scores=%s",
                trial_id,
                trace_data.id,
                [
                    (s.name, s.value, getattr(s, "reason", None))
                    for s in test_result_.score_results
                ],
            )

            # 仅成功路径会执行到这一行：告知外围上下文管理器
            # 在持久化的 trace 上保留 ``trace_data.output``。
            # 在此之前任何失败都会使标志保持未设置，``finally``
            # 会将 ``output`` 重置为 ``None``，而 ``evaluate_resume``
            # 将 ``output is None`` 解读为“需要重放”。
            eval_state.evaluation_completed = True

        return test_result_

    # --- 私有：并行执行 ---

    def _compute_test_results_with_execution_policy(
        self,
        dataset_items: Iterator[dataset_item.DatasetItem],
        task: LLMTask,
        experiment_: Optional[experiment.Experiment],
        regular_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: ScoringKeyMappingType,
        evaluator_model: Optional[str],
        description: str,
        total_items: Optional[int],
        default_execution_policy: dataset_execution_policy.ExecutionPolicy,
        show_scores_in_progress_bar: bool,
    ) -> List[test_result.TestResult]:
        """
        以完全并行的方式执行任务，并按项目显示进度。

        使用带 group_id 的 StreamingExecutor 按项目（而非按运行）跟踪进度，
        因此当 runs_per_item > 1 时能显示正确的项目数量。
        """
        with evaluation_tasks_executor.StreamingExecutor[test_result.TestResult](
            workers=self._workers,
            verbose=self._verbose,
            client=self._client,
            desc=description,
            total=total_items,
            show_score_postfix=show_scores_in_progress_bar,
        ) as executor:
            for item in dataset_items:
                item_policy = get_item_execution_policy(item, default_execution_policy)
                item_runs = item_policy.get("runs_per_item", 1)

                # 存储解析后的执行策略
                item.execution_policy = dataset_item.ExecutionPolicyItem(
                    runs_per_item=item_runs,
                    pass_threshold=item_policy.get("pass_threshold", 1),
                )

                # 在提交前声明组大小，以避免与回调产生竞态
                executor.set_group_size(item.id, item_runs)

                # 提交此项目的所有运行
                for run_id in range(item_runs):
                    executor.submit(
                        functools.partial(
                            self._compute_test_result_for_llm_task,
                            item=item,
                            task=task,
                            trial_id=run_id,
                            experiment_=experiment_,
                            regular_metrics=regular_metrics,
                            scoring_key_mapping=scoring_key_mapping,
                            evaluator_model=evaluator_model,
                        ),
                        group_id=item.id,
                    )

            return executor.get_results()

    # --- 私有：任务 span 指标 ---

    def _update_test_result_with_task_span_metrics(
        self,
        evaluation_task_result: test_result.TestResult,
        trace_trees: List[models.TraceModel],
        task_span_evaluator: metrics_evaluator.MetricsEvaluator,
    ) -> test_result.TestResult:
        # 查找相关的 trace
        trace_id = evaluation_task_result.test_case.trace_id
        task_trace = None
        for trace_ in trace_trees:
            if trace_.id == trace_id:
                task_trace = trace_
                break

        if task_trace is None:
            raise ValueError(
                f"未找到测试结果对应的 trace：{evaluation_task_result}"
            )

        # 查找评估 span
        if len(task_trace.spans) == 0:
            raise ValueError(
                f"任务 trace 不包含任何 span。任务 span 指标要求执行 trace 中至少存在一个 span。测试结果：{evaluation_task_result}"
            )
        # 第一个 span 就是评估 span
        evaluation_span = task_trace.spans[0]

        with helpers.evaluate_llm_task_result_spans_context(
            trace_data=trace.TraceData(
                id=trace_id,
                name=task_trace.name,
                start_time=task_trace.start_time,
                metadata=task_trace.metadata,
                input=task_trace.input,
                output=task_trace.output,
                tags=task_trace.tags,
                project_name=self._project_name,
                created_by="evaluation",
                error_info=task_trace.error_info,
                thread_id=task_trace.thread_id,
                source=self._source,
            ),
            client=self._client,
        ):
            score_results = self._compute_scores_for_test_case_with_task_span(
                trace_id=trace_id,
                task_span=evaluation_span,
                test_case_=evaluation_task_result.test_case,
                task_span_evaluator=task_span_evaluator,
            )
            # 将分数追加到输入的测试结果中
            evaluation_task_result.score_results += score_results
            return evaluation_task_result

    def _update_test_results_with_task_span_metrics(
        self,
        test_results: List[test_result.TestResult],
        recording: local_recording._LocalRecordingHandle,
        task_span_evaluator: metrics_evaluator.MetricsEvaluator,
    ) -> None:
        """评估来自本地录制的任务 span。"""
        # 从录制中获取 trace 树（这会自动刷新）
        trace_trees = recording.trace_trees
        if len(trace_trees) == 0:
            LOGGER.warning("在本地录制中未找到 trace 树。")
            return

        # 根据 LLM 任务评估结果创建 span 评估任务，并并行评估它们
        span_evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
            functools.partial(
                self._update_test_result_with_task_span_metrics,
                evaluation_task_result=test_result_,
                trace_trees=trace_trees,
                task_span_evaluator=task_span_evaluator,
            )
            for test_result_ in test_results
        ]

        evaluation_tasks_executor.execute(
            evaluation_tasks=span_evaluation_tasks,
            workers=self._workers,
            verbose=self._verbose,
            desc="LLM task spans evaluation",
            client=self._client,
        )

        LOGGER.debug(
            "任务评估 span 处理已禁用 —— 评估已完成。"
        )

    # --- 私有：带可选任务 span 评分的共享执行 ---

    def _execute_evaluation(
        self,
        dataset_items: Iterator[dataset_item.DatasetItem],
        task: LLMTask,
        experiment_: Optional[experiment.Experiment],
        regular_metrics: List[base_metric.BaseMetric],
        task_span_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: ScoringKeyMappingType,
        evaluator_model: Optional[str],
        description: str,
        total_items: Optional[int],
        default_execution_policy: dataset_execution_policy.ExecutionPolicy,
        show_scores_in_progress_bar: bool,
    ) -> List[test_result.TestResult]:
        """
        共享的执行逻辑。运行任务，用常规指标评分，
        并可选用任务 span 指标评分。
        """
        compute: Callable[[], List[test_result.TestResult]] = functools.partial(
            self._compute_test_results_with_execution_policy,
            dataset_items=dataset_items,
            task=task,
            experiment_=experiment_,
            regular_metrics=regular_metrics,
            scoring_key_mapping=scoring_key_mapping,
            evaluator_model=evaluator_model,
            description=description,
            total_items=total_items,
            default_execution_policy=default_execution_policy,
            show_scores_in_progress_bar=show_scores_in_progress_bar,
        )

        if not task_span_metrics:
            return compute()

        LOGGER.debug(
            "检测到 %d 个 LLM 任务 span 评分指标 —— 启用对 LLM 任务评估 span 的处理。",
            len(task_span_metrics),
        )

        task_span_evaluator = metrics_evaluator.MetricsEvaluator(
            scoring_metrics=task_span_metrics,
            scoring_key_mapping=scoring_key_mapping,
            # 项目级评估器属于常规指标的范畴；在此评估器构建之前
            # 无法跳过任何内容。
            skipped_evaluator_scores=[],
            error_tolerance=self._error_tolerance,
        )

        with local_recording.record_traces_locally(client=self._client) as recording:
            test_results = compute()
            self._update_test_results_with_task_span_metrics(
                test_results=test_results,
                recording=recording,
                task_span_evaluator=task_span_evaluator,
            )

        return test_results

    # --- 公共 API ---

    def run_and_score(
        self,
        dataset_items: Iterator[dataset_item.DatasetItem],
        task: LLMTask,
        scoring_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
        evaluator_model: Optional[str],
        experiment_: Optional[experiment.Experiment],
        default_execution_policy: dataset_execution_policy.ExecutionPolicy,
        total_items: Optional[int],
        description: str = "Evaluation",
        show_scores_in_progress_bar: bool = True,
    ) -> List[test_result.TestResult]:
        """
        在数据集项目上并行运行任务，然后用指标对结果进行评分。

        这是所有涉及运行任务的评估流程的通用入口。调用方负责解析
        数据集项目并构建执行策略。
        """
        resolved_scoring_key_mapping: ScoringKeyMappingType = (
            scoring_key_mapping if scoring_key_mapping is not None else {}
        )

        regular_metrics, task_span_metrics = (
            metrics_evaluator.split_into_regular_and_task_span_metrics(scoring_metrics)
        )

        return self._execute_evaluation(
            dataset_items=dataset_items,
            task=task,
            experiment_=experiment_,
            regular_metrics=regular_metrics,
            task_span_metrics=task_span_metrics,
            scoring_key_mapping=resolved_scoring_key_mapping,
            evaluator_model=evaluator_model,
            description=description,
            total_items=total_items,
            default_execution_policy=default_execution_policy,
            show_scores_in_progress_bar=show_scores_in_progress_bar,
        )

    def score_test_cases(
        self,
        test_cases: List[test_case.TestCase],
        scoring_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: Optional[ScoringKeyMappingType],
    ) -> List[test_result.TestResult]:
        """
        用指标对现有测试用例进行评分（不执行任务）。

        这是用新指标对现有实验结果重新评分的通用入口。
        """
        resolved_scoring_key_mapping: ScoringKeyMappingType = (
            scoring_key_mapping if scoring_key_mapping is not None else {}
        )

        regular_metrics, _ = metrics_evaluator.split_into_regular_and_task_span_metrics(
            scoring_metrics
        )

        evaluation_tasks: List[EvaluationTask[test_result.TestResult]] = [
            functools.partial(
                self._compute_test_result_for_test_case,
                test_case_=test_case_,
                regular_metrics=regular_metrics,
                scoring_key_mapping=resolved_scoring_key_mapping,
                evaluator_model=None,
            )
            for test_case_ in test_cases
        ]

        test_results: List[test_result.TestResult] = evaluation_tasks_executor.execute(
            evaluation_tasks=evaluation_tasks,
            workers=self._workers,
            verbose=self._verbose,
            client=self._client,
        )

        return test_results
