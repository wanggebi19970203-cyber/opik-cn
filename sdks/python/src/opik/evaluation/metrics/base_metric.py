from typing import Any, List, Optional, Union

import opik
import opik.config as opik_config
import _opik._base_metric as _opik_base_metric
from ..metrics import score_result


class BaseMetric(_opik_base_metric.BaseMetric):
    """
    所有评估指标的抽象基类。创建新指标时，应继承此类并实现抽象方法。

    Args:
        name: 指标名称。如果未提供，则默认使用类名。
        track: 是否追踪该指标。默认为 True。
        project_name: 可选的项目名称，用于在没有父级 span/trace 可继承项目名称时追踪指标。

    Example:
        >>> from opik.evaluation.metrics import base_metric, score_result
        >>>
        >>> class MyCustomMetric(base_metric.BaseMetric):
        >>>     def __init__(self, name: str, track: bool = True):
        >>>         super().__init__(name=name, track=track)
        >>>
        >>>     def score(self, input: str, output: str, **ignored_kwargs: Any):
        >>>         # 在此处添加自定义逻辑
        >>>
        >>>         return score_result.ScoreResult(
        >>>             value=0,
        >>>             name=self.name,
        >>>             reason="评分的可选原因说明"
        >>>         )
    """

    def __init__(
        self,
        name: Optional[str] = None,
        track: bool = True,
        project_name: Optional[str] = None,
    ) -> None:
        super().__init__(name=name, track=track, project_name=project_name)

        config = opik_config.OpikConfig()

        if not track and project_name is not None:
            raise ValueError("project_name can be set only when `track` is set to True")

        if track and config.check_for_known_misconfigurations() is False:
            track_decorator = opik.track(name=self.name, project_name=project_name)
            self.score = track_decorator(self.score)  # type: ignore
            self.ascore = track_decorator(self.ascore)  # type: ignore

    def score(
        self, *args: Any, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        可独立调用的公共方法。子类必须实现此方法。
        """
        raise NotImplementedError()

    async def ascore(
        self, *args: Any, **kwargs: Any
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]:
        """
        可独立调用的异步公共方法。默认实现会同步调用 score 方法。
        """
        return self.score(*args, **kwargs)
