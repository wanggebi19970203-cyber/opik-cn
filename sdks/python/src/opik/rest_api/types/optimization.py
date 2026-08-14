# 此文件由 Fern 根据我们的 API 定义自动生成。

import datetime as dt
import typing

import pydantic
from ..core.pydantic_utilities import IS_PYDANTIC_V2, UniversalBaseModel
from .error_info import ErrorInfo
from .feedback_score_average import FeedbackScoreAverage
from .json_list_string import JsonListString
from .optimization_status import OptimizationStatus
from .optimization_studio_config import OptimizationStudioConfig


class Optimization(UniversalBaseModel):
    id: typing.Optional[str] = None
    name: typing.Optional[str] = None
    dataset_name: str
    project_name: typing.Optional[str] = pydantic.Field(default=None)
    """
    项目名称。若项目不存在则创建它。提供 project_id 时此字段被忽略。
    """

    project_id: typing.Optional[str] = pydantic.Field(default=None)
    """
    项目 ID。当同时提供两者时，优先级高于 project_name。
    """

    objective_name: str
    status: OptimizationStatus
    metadata: typing.Optional[JsonListString] = None
    studio_config: typing.Optional[OptimizationStudioConfig] = None
    error_info: typing.Optional[ErrorInfo] = None
    dataset_id: typing.Optional[str] = None
    num_trials: typing.Optional[int] = None
    feedback_scores: typing.Optional[typing.List[FeedbackScoreAverage]] = None
    experiment_scores: typing.Optional[typing.List[FeedbackScoreAverage]] = None
    created_at: typing.Optional[dt.datetime] = None
    created_by: typing.Optional[str] = None
    last_updated_at: typing.Optional[dt.datetime] = None
    last_updated_by: typing.Optional[str] = None
    baseline_objective_score: typing.Optional[float] = None
    best_objective_score: typing.Optional[float] = None
    baseline_duration: typing.Optional[float] = None
    best_duration: typing.Optional[float] = None
    baseline_cost: typing.Optional[float] = None
    best_cost: typing.Optional[float] = None
    total_optimization_cost: typing.Optional[float] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow
