import inspect
import logging
from typing import Any, Callable, Dict, List, Optional, Tuple

import pydantic

import opik
import opik.opik_context as opik_context

import opik.exceptions as exceptions
import opik.logging_messages as logging_messages
from opik.api_objects.dataset import dataset_item
from opik.decorator import error_info_collector
from opik.evaluation.metrics import (
    arguments_helpers,
    base_metric,
    score_result,
    arguments_validator,
)
from opik.evaluation.scorers import scorer_wrapper_metric
from opik.evaluation.suite_evaluators import llm_judge
from opik.evaluation.suite_evaluators.agentic.context import INTERNAL_SPAN_TAG
from opik.evaluation.suite_evaluators.llm_judge import config as llm_judge_config
from opik.evaluation.types import ErrorTolerance, ScoringKeyMappingType
from opik.message_processing.emulation import models

from . import exception_analyzer


LOGGER = logging.getLogger(__name__)

EVALUATION_SPAN_PARAMETER_NAME = "task_span"
TRACE_TOOL_CONTEXT_PARAMETER_NAME = "trace_tool_context"
ARGUMENT_VALIDATION_SPAN_SUFFIX = "_arg_validation"


def _has_evaluation_span_parameter(func: Callable) -> bool:
    """检查评分函数是否期望 task_span 参数。"""
    try:
        sig = inspect.signature(func)
        return EVALUATION_SPAN_PARAMETER_NAME in sig.parameters
    except (ValueError, TypeError):
        return False


def _accepts_trace_tool_context(func: Callable) -> bool:
    """检查评分函数是否接受 trace_tool_context kwarg。

    当签名显式命名该参数，或接受 ``**kwargs``（它会吸收未知的
    kwarg）时返回 True。LLMJudge 属于第二种情况。
    """
    try:
        sig = inspect.signature(func)
    except (ValueError, TypeError):
        return False
    params = sig.parameters
    if TRACE_TOOL_CONTEXT_PARAMETER_NAME in params:
        return True
    return any(param.kind == inspect.Parameter.VAR_KEYWORD for param in params.values())


def split_into_regular_and_task_span_metrics(
    scoring_metrics: List[base_metric.BaseMetric],
) -> Tuple[List[base_metric.BaseMetric], List[base_metric.BaseMetric]]:
    """
    将指标分为常规指标和任务 span 指标两类。

    Args:
        scoring_metrics: 要分析的指标列表。

    Returns:
        (regular_metrics, task_span_metrics) 元组。
    """
    regular_metrics: List[base_metric.BaseMetric] = []
    task_span_metrics: List[base_metric.BaseMetric] = []

    for metric in scoring_metrics:
        if _has_evaluation_span_parameter(metric.score):
            task_span_metrics.append(metric)
        else:
            regular_metrics.append(metric)

    return regular_metrics, task_span_metrics


def _build_failed_score_result(
    metric_name: str, exception: Exception
) -> score_result.ScoreResult:
    """将指标体之外抛出的错误表示为失败的分数。

    ``reason`` 是异常消息，与在 ``score`` 内部抛出的失败所产生的内容一致。
    结构化载荷放入 ``metadata`` 中，使用与 span 和 trace 上相同的
    ``error_info`` 键。
    """
    return score_result.ScoreResult(
        name=metric_name,
        value=0.0,
        reason=str(exception),
        metadata={"error_info": error_info_collector.collect(exception)},
        scoring_failed=True,
    )


def _describe_evaluator_error(exception: Exception) -> str:
    """描述构建项目评估器的失败，但不回显其配置。

    ``evaluator_item.config`` 来自数据集，可能携带凭据。
    Pydantic 会把被拒绝的输入嵌入异常消息，因此也会嵌入 traceback，
    所以两者都不能进入日志；``include_input=False`` 保留字段路径，
    这才是值得保留的部分。没有丢失任何信息：完整的异常仍然会到达调用方，
    在默认容错级别重新抛出，并体现在其上的失败分数结果中。
    """
    if isinstance(exception, pydantic.ValidationError):
        errors = exception.errors(include_url=False, include_input=False)
        return f"{type(exception).__name__}: {errors}"
    return type(exception).__name__


def _extract_item_evaluators(
    item: dataset_item.DatasetItem,
    evaluator_model: Optional[str],
    error_tolerance: ErrorTolerance,
) -> Tuple[List[base_metric.BaseMetric], List[score_result.ScoreResult]]:
    """
    从数据集项目中提取评估器。

    如果项目带有评估器配置，则从中实例化 LLMJudge 评估器。

    Args:
        item: 数据集项目。
        evaluator_model: 供 LLMJudge 评估器使用的可选模型名称。

    Returns:
        (从项目提取的评估器实例，已配置但无法运行的评估器的失败分数结果) 元组。
    """
    if not item.evaluators:
        return [], []

    evaluators: List[base_metric.BaseMetric] = []
    skipped_evaluator_scores: List[score_result.ScoreResult] = []
    for evaluator_item in item.evaluators:
        try:
            if evaluator_item.type == "llm_judge":
                config = llm_judge_config.LLMJudgeConfig(**evaluator_item.config)
                evaluator = llm_judge.LLMJudge.from_config(
                    config, init_kwargs={"model": evaluator_model}
                )
                evaluators.append(evaluator)
            else:
                # 这不是调用方在运行中途能够处理的错误（较旧的 SDK
                # 面对较新的数据集），因此它不应中止评估。
                # 它也不应消失：没有结果的话，该项目看起来就像被
                # 欠评估了一样（OPIK-6925）。
                unsupported_type = exceptions.EvaluationError(
                    f"不支持的评估器类型：{evaluator_item.type}。"
                    "仅支持 'llm_judge'。"
                )
                LOGGER.warning(str(unsupported_type))
                skipped_evaluator_scores.append(
                    _build_failed_score_result(evaluator_item.name, unsupported_type)
                )
        except Exception as exception:
            LOGGER.error(
                "实例化评估器 %s 失败（配置键：%s）：%s。",
                evaluator_item.name,
                sorted(evaluator_item.config),
                _describe_evaluator_error(exception),
            )
            if error_tolerance < ErrorTolerance.ALL_SCORING_ERRORS:
                raise
            skipped_evaluator_scores.append(
                _build_failed_score_result(evaluator_item.name, exception)
            )

    return evaluators, skipped_evaluator_scores


def build_metrics_evaluator(
    item: Optional[dataset_item.DatasetItem],
    regular_metrics: List[base_metric.BaseMetric],
    scoring_key_mapping: ScoringKeyMappingType,
    evaluator_model: Optional[str],
    error_tolerance: ErrorTolerance,
) -> "MetricsEvaluator":
    """使用套件级 + 项目级指标构建 MetricsEvaluator。"""
    all_metrics: List[base_metric.BaseMetric] = list(regular_metrics)
    skipped_evaluator_scores: List[score_result.ScoreResult] = []
    if item is not None:
        item_evaluators, skipped_evaluator_scores = _extract_item_evaluators(
            item, evaluator_model=evaluator_model, error_tolerance=error_tolerance
        )
        all_metrics.extend(item_evaluators)

    judges = [m for m in all_metrics if isinstance(m, llm_judge.LLMJudge)]
    non_judges = [m for m in all_metrics if not isinstance(m, llm_judge.LLMJudge)]
    merged = llm_judge.LLMJudge.merged(judges)
    if merged is not None:
        all_metrics = [merged] + non_judges

    return MetricsEvaluator(
        scoring_metrics=all_metrics,
        scoring_key_mapping=scoring_key_mapping,
        skipped_evaluator_scores=skipped_evaluator_scores,
        error_tolerance=error_tolerance,
    )


@opik.track(  # type: ignore[attr-defined,has-type]
    # 每次调用时替换为指标自身的名称 —— `@track` 在装饰时固定名称，
    # 因此真正的名称是在函数体内设置的。
    name=f"score{ARGUMENT_VALIDATION_SPAN_SUFFIX}",
    tags=[INTERNAL_SPAN_TAG],
    # `metric_name` 是唯一值得记录的参数：其余参数要么是数据集项目
    # （已在父 span 上），要么是序列化时会把整个 LLM 客户端拖入
    # span 输入的对象。
    ignore_arguments=[
        "metric",
        "mapped_scoring_inputs",
        "scoring_key_mapping",
        "trace_tool_context",
    ],
)
def _prepare_score_arguments(
    metric: base_metric.BaseMetric,
    metric_name: str,
    mapped_scoring_inputs: Dict[str, Any],
    scoring_key_mapping: ScoringKeyMappingType,
    trace_tool_context: Any,
) -> Tuple[List[Any], Dict[str, Any]]:
    """在调用 ``score`` 之前必须发生的所有事情。

    此步骤被追踪，因此拥有自己的 span：``score`` 被 ``@track`` 包装并
    自行报告失败，但一个从未走到那一步的指标原本不会留下任何原因线索
    —— 失败的分数也不会被持久化。使用装饰器而不是手动创建 span，
    还意味着全局的 ``set_tracing_active`` 开关在这里与在其他地方一样
    得到遵循。
    """
    opik_context.update_current_span(
        name=f"{metric_name}{ARGUMENT_VALIDATION_SPAN_SUFFIX}"
    )

    arguments_validator.validate_score_arguments(
        metric=metric,
        kwargs=mapped_scoring_inputs,
        scoring_key_mapping=scoring_key_mapping,
    )

    # 仅将 trace_tool_context 注入到签名能够吸收它的指标中；
    # 否则对于窄指标的调用会因“意外的关键字参数”而失败。
    if trace_tool_context is not None and _accepts_trace_tool_context(metric.score):
        score_kwargs = {
            **mapped_scoring_inputs,
            TRACE_TOOL_CONTEXT_PARAMETER_NAME: trace_tool_context,
        }
    else:
        score_kwargs = mapped_scoring_inputs

    return arguments_helpers.select_score_arguments(
        score_function=metric.score,
        kwargs=score_kwargs,
        score_name=metric_name,
    )


def _compute_metric_scores(
    scoring_metrics: List[base_metric.BaseMetric],
    mapped_scoring_inputs: Dict[str, Any],
    scoring_key_mapping: ScoringKeyMappingType,
    dataset_item_content: Dict[str, Any],
    task_output: Dict[str, Any],
    trace_tool_context: Any,
    error_tolerance: ErrorTolerance,
) -> List[score_result.ScoreResult]:
    """
    使用给定指标计算分数。

    Args:
        scoring_metrics: 要计算的指标列表
        mapped_scoring_inputs: 键映射后的评分输入（将用于常规指标）
        scoring_key_mapping: 用于重命名评分参数的映射（无映射时为空字典）
        dataset_item_content: 数据集项目内容（将用于 ScorerWrapperMetric）
        task_output: 任务输出（将用于 ScorerWrapperMetric）

    Returns:
        计算出的分数结果列表
    """
    score_results: List[score_result.ScoreResult] = []

    for metric in scoring_metrics:
        try:
            LOGGER.debug("指标 %s 的评分已开始", metric.name)

            if isinstance(metric, scorer_wrapper_metric.ScorerWrapperMetric):
                # ScorerWrapperMetric 使用原始数据集项目和任务输出，不做映射
                if (
                    task_span := mapped_scoring_inputs.get(
                        EVALUATION_SPAN_PARAMETER_NAME
                    )
                ) is not None:
                    result = metric.score(
                        dataset_item=dataset_item_content,
                        task_outputs=task_output,
                        task_span=task_span,
                    )
                else:
                    result = metric.score(
                        dataset_item=dataset_item_content,
                        task_outputs=task_output,
                    )
            else:
                positional_arguments, keyword_arguments = _prepare_score_arguments(
                    metric=metric,
                    metric_name=metric.name,
                    mapped_scoring_inputs=mapped_scoring_inputs,
                    scoring_key_mapping=scoring_key_mapping,
                    trace_tool_context=trace_tool_context,
                )
                result = metric.score(*positional_arguments, **keyword_arguments)

            LOGGER.debug("指标 %s 的评分已结束", metric.name)

            if isinstance(result, list):
                score_results += result
            else:
                score_results.append(result)

        except exceptions.ScoreMethodMissingArguments as exception:
            if error_tolerance < ErrorTolerance.ALL_SCORING_ERRORS:
                raise
            LOGGER.error(
                "指标 %s 无法评分。其分数将被标记为失败。%s",
                metric.name,
                exception,
            )
            score_results.append(_build_failed_score_result(metric.name, exception))
        except Exception as exception:
            LOGGER.error(
                "计算指标 %s 失败。分数结果将被标记为失败。",
                metric.name,
                exc_info=True,
            )

            if exception_analyzer.is_llm_provider_rate_limit_error(exception):
                LOGGER.error(
                    logging_messages.LLM_PROVIDER_RATE_LIMIT_ERROR_DETECTED_IN_EVALUATE_FUNCTION
                )

            score_results.append(_build_failed_score_result(metric.name, exception))

    return score_results


class MetricsEvaluator:
    """
    处理指标计算和评分。

    将指标分为：
    - 常规指标：基于输入/输出评分
    - 任务 span 指标：基于 LLM 调用元数据（token、延迟等）评分
    """

    def __init__(
        self,
        scoring_metrics: List[base_metric.BaseMetric],
        scoring_key_mapping: ScoringKeyMappingType,
        skipped_evaluator_scores: List[score_result.ScoreResult],
        error_tolerance: ErrorTolerance,
    ):
        self._scoring_key_mapping = scoring_key_mapping
        self._regular_metrics: List[base_metric.BaseMetric] = []
        self._task_span_metrics: List[base_metric.BaseMetric] = []
        self._skipped_evaluator_scores = skipped_evaluator_scores
        self._error_tolerance = error_tolerance

        self._analyze_metrics(scoring_metrics)

    @property
    def has_task_span_metrics(self) -> bool:
        """检查是否配置了任何任务 span 评分指标。"""
        return len(self._task_span_metrics) > 0

    @property
    def task_span_metrics(self) -> List[base_metric.BaseMetric]:
        """获取任务 span 评分指标列表。"""
        return self._task_span_metrics

    @property
    def regular_metrics(self) -> List[base_metric.BaseMetric]:
        """获取常规评分指标列表。"""
        return self._regular_metrics

    @property
    def scoring_key_mapping(self) -> ScoringKeyMappingType:
        """获取评分键映射。"""
        return self._scoring_key_mapping

    def _analyze_metrics(
        self,
        scoring_metrics: List[base_metric.BaseMetric],
    ) -> None:
        """将指标分为常规指标和任务 span 指标两类。"""
        self._regular_metrics, self._task_span_metrics = (
            split_into_regular_and_task_span_metrics(scoring_metrics)
        )

        if self.has_task_span_metrics:
            LOGGER.debug(
                "检测到 %d 个 LLM 任务 span 评分指标。",
                len(self._task_span_metrics),
            )

    def compute_regular_scores(
        self,
        dataset_item_content: Dict[str, Any],
        task_output: Dict[str, Any],
        trace_tool_context: Any = None,
    ) -> Tuple[List[score_result.ScoreResult], Dict[str, Any]]:
        """
        使用常规指标计算分数。

        Args:
            dataset_item_content: 数据集项目内容
            task_output: 任务输出
            trace_tool_context: 可选的、由本地模拟器构建的代理判断器上下文。
                仅传递给 score 签名能够接受它的指标（尤其是 LLMJudge）。

        Returns:
            (分数结果，用于对常规非包装指标评分的映射评分输入) 元组
        """
        mapped_scoring_inputs = arguments_helpers.create_scoring_inputs(
            dataset_item=dataset_item_content,
            task_output=task_output,
            scoring_key_mapping=self._scoring_key_mapping,
        )

        score_results = _compute_metric_scores(
            scoring_metrics=self._regular_metrics,
            mapped_scoring_inputs=mapped_scoring_inputs,
            scoring_key_mapping=self._scoring_key_mapping,
            dataset_item_content=dataset_item_content,
            task_output=task_output,
            trace_tool_context=trace_tool_context,
            error_tolerance=self._error_tolerance,
        )
        # 追加而不是前置：消费者会按索引访问 `score_results`，因此被跳过的
        # 评估器不得挤占第一个已配置指标的位置。
        score_results += self._skipped_evaluator_scores

        return score_results, mapped_scoring_inputs

    def compute_task_span_scores(
        self,
        dataset_item_content: Dict[str, Any],
        task_output: Dict[str, Any],
        task_span: models.SpanModel,
    ) -> Tuple[List[score_result.ScoreResult], Dict[str, Any]]:
        """
        使用任务 span 指标计算分数。

        Args:
            dataset_item_content: 数据集项目内容
            task_output: 任务输出
            task_span: 包含任务执行元数据的 span 模型

        Returns:
            (分数结果，用于对常规非包装指标评分的映射评分输入) 元组
        """
        mapped_scoring_inputs = arguments_helpers.create_scoring_inputs(
            dataset_item=dataset_item_content,
            task_output=task_output,
            scoring_key_mapping=self._scoring_key_mapping,
        )

        mapped_scoring_inputs_with_span = {
            **mapped_scoring_inputs,
            EVALUATION_SPAN_PARAMETER_NAME: task_span,
        }

        score_results = _compute_metric_scores(
            scoring_metrics=self._task_span_metrics,
            mapped_scoring_inputs=mapped_scoring_inputs_with_span,
            scoring_key_mapping=self._scoring_key_mapping,
            dataset_item_content=dataset_item_content,
            task_output=task_output,
            trace_tool_context=None,
            error_tolerance=self._error_tolerance,
        )

        return score_results, mapped_scoring_inputs_with_span
