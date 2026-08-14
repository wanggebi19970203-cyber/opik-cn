import enum
from typing import Any, Callable, Dict, List, Union

from . import test_result
from .metrics import score_result

LLMTask = Callable[[Dict[str, Any]], Dict[str, Any]]

ScoringKeyMappingType = Dict[str, Union[str, Callable[[Dict[str, Any]], Any]]]

ExperimentScoreFunction = Callable[
    [List[test_result.TestResult]],
    Union[score_result.ScoreResult, List[score_result.ScoreResult]],
]


class ErrorTolerance(enum.IntEnum):
    """评估在中止前所容忍的失败类别。

    值越大，容忍的越多。仅接受此处定义的成员——或它们精确的整数值，因为
    这是 ``IntEnum``——其他任何值都会抛出 ``ValueError``。取值按十间隔，
    因此可以在现有级别之上、之下或之间插入新的级别，而无需重新编号。
    """

    METRIC_ERRORS = 10
    """默认值。指标计算其分数时抛出的错误会被记录为失败的评分结果，运行会
    继续。对于在进入 ``score`` 之前发生的失败，缺少必需的评分参数以及无法
    构建的项级评估器会使运行中止；而本 SDK 不支持的评估器类型始终被报告为
    失败的评分。"""

    ALL_SCORING_ERRORS = 20
    """同时容忍那些导致指标完全无法被评分的错误——数据集未提供的必需评分
    参数，或无法构建的项级评估器。评估任务自身的失败仍会使运行中止。"""
