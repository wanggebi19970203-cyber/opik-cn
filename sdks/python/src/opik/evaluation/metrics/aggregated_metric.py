from typing import List, Callable, Any, Dict, Optional

from .. import types as evaluation_types

from . import arguments_helpers, arguments_validator, base_metric, score_result


class AggregatedMetric(
    base_metric.BaseMetric, arguments_validator.ScoreArgumentsValidator
):
    """
    将多个指标的输出合并为单个聚合的 ``ScoreResult``。

    ``metrics`` 中的每个指标都会使用提供的评分 kwargs 执行，然后由
    ``aggregator`` 回调决定如何合并各个结果。这非常适合构建诸如最小/最大值、
    加权平均或自定义通过/失败检查之类的集成，而无需重新实现指标本身。

    Args:
        name: 聚合指标结果的显示名称。
        metrics: 应执行的有序指标实例列表。
        aggregator: 接收 ``ScoreResult`` 对象列表并返回最终聚合
            ``ScoreResult`` 的回调。
        track: 是否在 Opik 中自动跟踪该指标。默认为 ``True``。
        project_name: 在没有父级上下文时使用的可选跟踪项目。

    Example:
        >>> from opik.evaluation.metrics import AggregatedMetric, Contains, RegexMatch
        >>> metrics = [Contains(track=False), RegexMatch(pattern=r"\\d+", track=False)]
        >>> from opik.evaluation.metrics import score_result
        >>> def combine(results):
        ...     score = sum(result.value for result in results) / len(results)
        ...     return score_result.ScoreResult(
        ...         name="combined_contains_regex",
        ...         value=score,
        ...         reason="Average of contains and regex checks",
        ...     )
        >>> metric = AggregatedMetric(
        ...     name="combined_contains_regex",
        ...     metrics=metrics,
        ...     aggregator=combine,
        ... )
        >>> response = "Order number 12345 confirmed"
        >>> result = metric.score(output=response, reference="order")
        >>> float(result.value)  # doctest: +SKIP
        1.0
    """

    def __init__(
        self,
        name: str,
        metrics: List[base_metric.BaseMetric],
        aggregator: Callable[
            [List[score_result.ScoreResult]], score_result.ScoreResult
        ],
        track: bool = True,
        project_name: Optional[str] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self.metrics = metrics
        self.aggregator = aggregator

        if self.metrics is None or len(self.metrics) == 0:
            raise ValueError("未提供任何指标")

        if aggregator is None:
            raise ValueError("未提供聚合器")

    def score(self, **kwargs: Any) -> score_result.ScoreResult:
        score_results: List[score_result.ScoreResult] = []
        for metric in self.metrics:
            # 按子指标逐个收窄参数，与引擎直接分发时的行为一致：
            # 该指标接受 `**kwargs`，因此若不这样做，一个仅声明了自身所需
            # 参数的被包装指标会收到所有数据集键，并因
            # `unexpected keyword argument` TypeError 而失败。
            positional_arguments, keyword_arguments = (
                arguments_helpers.select_score_arguments(
                    score_function=metric.score,
                    kwargs=kwargs,
                    score_name=metric.name,
                )
            )
            metric_result = metric.score(*positional_arguments, **keyword_arguments)
            if isinstance(metric_result, list):
                score_results.extend(metric_result)
            else:
                score_results.append(metric_result)

        return self.aggregator(score_results)

    def validate_score_arguments(
        self,
        score_kwargs: Dict[str, Any],
        key_mapping: Optional[evaluation_types.ScoringKeyMappingType],
    ) -> None:
        for metric in self.metrics:
            arguments_helpers.raise_if_score_arguments_are_missing(
                score_function=metric.score,
                score_name=metric.name,
                kwargs=score_kwargs,
                scoring_key_mapping=key_mapping,
            )
