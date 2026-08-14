# 此文件由 Fern 根据我们的 API 定义自动生成。

from __future__ import annotations

import datetime as dt
import typing

import pydantic
from ..core.pydantic_utilities import IS_PYDANTIC_V2, UniversalBaseModel
from .automation_rule_evaluator_action import AutomationRuleEvaluatorAction
from .automation_rule_evaluator_trigger_scope import AutomationRuleEvaluatorTriggerScope
from .llm_as_judge_code import LlmAsJudgeCode
from .project_reference import ProjectReference
from .span_filter import SpanFilter
from .span_llm_as_judge_code import SpanLlmAsJudgeCode
from .span_user_defined_metric_python_code import SpanUserDefinedMetricPythonCode
from .trace_filter import TraceFilter
from .trace_thread_filter import TraceThreadFilter
from .trace_thread_llm_as_judge_code import TraceThreadLlmAsJudgeCode
from .trace_thread_user_defined_metric_python_code import TraceThreadUserDefinedMetricPythonCode
from .user_defined_metric_python_code import UserDefinedMetricPythonCode


class Base(UniversalBaseModel):
    id: typing.Optional[str] = None
    project_id: typing.Optional[str] = pydantic.Field(default=None)
    """
    主项目 ID（用于向后兼容的旧字段）
    """

    project_name: typing.Optional[str] = pydantic.Field(default=None)
    """
    主项目名称（用于向后兼容的旧字段）
    """

    projects: typing.Optional[typing.List[ProjectReference]] = pydantic.Field(default=None)
    """
    分配给此规则的项目（唯一，按名称字母顺序排序）
    """

    project_ids: typing.Optional[typing.List[str]] = pydantic.Field(default=None)
    """
    用于写操作的项目 ID（在创建/更新规则时使用）
    """

    name: str
    sampling_rate: typing.Optional[float] = None
    enabled: typing.Optional[bool] = None
    trigger_scope: typing.Optional[AutomationRuleEvaluatorTriggerScope] = pydantic.Field(default=None)
    """
    控制规则是作用于生产 traces、实验 traces 还是两者。若省略，默认为 'production'。
    """

    created_at: typing.Optional[dt.datetime] = None
    created_by: typing.Optional[str] = None
    last_updated_at: typing.Optional[dt.datetime] = None
    last_updated_by: typing.Optional[str] = None
    action: AutomationRuleEvaluatorAction

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluator_LlmAsJudge(Base):
    type: typing.Literal["llm_as_judge"] = "llm_as_judge"
    filters: typing.Optional[typing.List[TraceFilter]] = None
    code: typing.Optional[LlmAsJudgeCode] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluator_UserDefinedMetricPython(Base):
    type: typing.Literal["user_defined_metric_python"] = "user_defined_metric_python"
    filters: typing.Optional[typing.List[TraceFilter]] = None
    code: typing.Optional[UserDefinedMetricPythonCode] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluator_TraceThreadLlmAsJudge(Base):
    type: typing.Literal["trace_thread_llm_as_judge"] = "trace_thread_llm_as_judge"
    filters: typing.Optional[typing.List[TraceThreadFilter]] = None
    code: typing.Optional[TraceThreadLlmAsJudgeCode] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluator_TraceThreadUserDefinedMetricPython(Base):
    type: typing.Literal["trace_thread_user_defined_metric_python"] = "trace_thread_user_defined_metric_python"
    filters: typing.Optional[typing.List[TraceThreadFilter]] = None
    code: typing.Optional[TraceThreadUserDefinedMetricPythonCode] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluator_SpanLlmAsJudge(Base):
    type: typing.Literal["span_llm_as_judge"] = "span_llm_as_judge"
    filters: typing.Optional[typing.List[SpanFilter]] = None
    code: typing.Optional[SpanLlmAsJudgeCode] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluator_SpanUserDefinedMetricPython(Base):
    type: typing.Literal["span_user_defined_metric_python"] = "span_user_defined_metric_python"
    filters: typing.Optional[typing.List[SpanFilter]] = None
    code: typing.Optional[SpanUserDefinedMetricPythonCode] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


AutomationRuleEvaluator = typing.Union[
    AutomationRuleEvaluator_LlmAsJudge,
    AutomationRuleEvaluator_UserDefinedMetricPython,
    AutomationRuleEvaluator_TraceThreadLlmAsJudge,
    AutomationRuleEvaluator_TraceThreadUserDefinedMetricPython,
    AutomationRuleEvaluator_SpanLlmAsJudge,
    AutomationRuleEvaluator_SpanUserDefinedMetricPython,
]
