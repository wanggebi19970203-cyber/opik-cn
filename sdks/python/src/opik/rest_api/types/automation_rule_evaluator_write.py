# 此文件由 Fern 根据我们的 API 定义自动生成。

from __future__ import annotations

import typing

import pydantic
from ..core.pydantic_utilities import IS_PYDANTIC_V2, UniversalBaseModel
from .automation_rule_evaluator_write_action import AutomationRuleEvaluatorWriteAction
from .automation_rule_evaluator_write_trigger_scope import AutomationRuleEvaluatorWriteTriggerScope
from .llm_as_judge_code_write import LlmAsJudgeCodeWrite
from .span_filter_write import SpanFilterWrite
from .span_llm_as_judge_code_write import SpanLlmAsJudgeCodeWrite
from .span_user_defined_metric_python_code_write import SpanUserDefinedMetricPythonCodeWrite
from .trace_filter_write import TraceFilterWrite
from .trace_thread_filter_write import TraceThreadFilterWrite
from .trace_thread_llm_as_judge_code_write import TraceThreadLlmAsJudgeCodeWrite
from .trace_thread_user_defined_metric_python_code_write import TraceThreadUserDefinedMetricPythonCodeWrite
from .user_defined_metric_python_code_write import UserDefinedMetricPythonCodeWrite


class Base(UniversalBaseModel):
    project_id: typing.Optional[str] = pydantic.Field(default=None)
    """
    主项目 ID（用于向后兼容的旧字段）
    """

    project_ids: typing.Optional[typing.List[str]] = pydantic.Field(default=None)
    """
    用于写操作的项目 ID（在创建/更新规则时使用）
    """

    name: str
    sampling_rate: typing.Optional[float] = None
    enabled: typing.Optional[bool] = None
    trigger_scope: typing.Optional[AutomationRuleEvaluatorWriteTriggerScope] = pydantic.Field(default=None)
    """
    控制规则是作用于生产 traces、实验 traces 还是两者。若省略，默认为 'production'。
    """

    action: AutomationRuleEvaluatorWriteAction

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluatorWrite_LlmAsJudge(Base):
    type: typing.Literal["llm_as_judge"] = "llm_as_judge"
    filters: typing.Optional[typing.List[TraceFilterWrite]] = None
    code: typing.Optional[LlmAsJudgeCodeWrite] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluatorWrite_UserDefinedMetricPython(Base):
    type: typing.Literal["user_defined_metric_python"] = "user_defined_metric_python"
    filters: typing.Optional[typing.List[TraceFilterWrite]] = None
    code: typing.Optional[UserDefinedMetricPythonCodeWrite] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluatorWrite_TraceThreadLlmAsJudge(Base):
    type: typing.Literal["trace_thread_llm_as_judge"] = "trace_thread_llm_as_judge"
    filters: typing.Optional[typing.List[TraceThreadFilterWrite]] = None
    code: typing.Optional[TraceThreadLlmAsJudgeCodeWrite] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluatorWrite_TraceThreadUserDefinedMetricPython(Base):
    type: typing.Literal["trace_thread_user_defined_metric_python"] = "trace_thread_user_defined_metric_python"
    filters: typing.Optional[typing.List[TraceThreadFilterWrite]] = None
    code: typing.Optional[TraceThreadUserDefinedMetricPythonCodeWrite] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluatorWrite_SpanLlmAsJudge(Base):
    type: typing.Literal["span_llm_as_judge"] = "span_llm_as_judge"
    filters: typing.Optional[typing.List[SpanFilterWrite]] = None
    code: typing.Optional[SpanLlmAsJudgeCodeWrite] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


class AutomationRuleEvaluatorWrite_SpanUserDefinedMetricPython(Base):
    type: typing.Literal["span_user_defined_metric_python"] = "span_user_defined_metric_python"
    filters: typing.Optional[typing.List[SpanFilterWrite]] = None
    code: typing.Optional[SpanUserDefinedMetricPythonCodeWrite] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow


AutomationRuleEvaluatorWrite = typing.Union[
    AutomationRuleEvaluatorWrite_LlmAsJudge,
    AutomationRuleEvaluatorWrite_UserDefinedMetricPython,
    AutomationRuleEvaluatorWrite_TraceThreadLlmAsJudge,
    AutomationRuleEvaluatorWrite_TraceThreadUserDefinedMetricPython,
    AutomationRuleEvaluatorWrite_SpanLlmAsJudge,
    AutomationRuleEvaluatorWrite_SpanUserDefinedMetricPython,
]
