import inspect
import logging
from typing import Any, Callable, Dict, List, Optional, Tuple

import opik.exceptions as exceptions
from .. import types as evaluation_types

LOGGER = logging.getLogger(__name__)

MAX_REPORTED_AVAILABLE_KEYS = 20


def raise_if_score_arguments_are_missing(
    score_function: Callable,
    score_name: str,
    kwargs: Dict[str, Any],
    scoring_key_mapping: Optional[evaluation_types.ScoringKeyMappingType],
) -> None:
    signature = inspect.signature(score_function)

    parameters = signature.parameters

    missing_required_arguments: List[str] = []

    for name, param in parameters.items():
        if name == "self":
            continue

        if param.default == inspect.Parameter.empty and param.kind in (
            inspect.Parameter.POSITIONAL_ONLY,
            inspect.Parameter.POSITIONAL_OR_KEYWORD,
            inspect.Parameter.KEYWORD_ONLY,
        ):
            if name not in kwargs:
                missing_required_arguments.append(name)

    if len(missing_required_arguments) > 0:
        unused_mapping_arguments: List[str] = []
        if scoring_key_mapping:
            unused_mapping_arguments = list(
                set(key for key in scoring_key_mapping.values() if not callable(key))
                - set(kwargs.keys())
            )

        raise exceptions.ScoreMethodMissingArguments(
            score_name,
            missing_required_arguments,
            list(kwargs.keys()),
            unused_mapping_arguments,
        )


def select_score_arguments(
    score_function: Callable, kwargs: Dict[str, Any], score_name: str
) -> Tuple[List[Any], Dict[str, Any]]:
    """将评分输入拆分为评分函数签名能够接受的形式。

    每个数据集项键和任务输出键都会提供给每个指标，这会破坏两种签名：

    - 未声明 ``**kwargs`` 的指标过去会因每个项都报出
      ``unexpected keyword argument`` TypeError 而失败——这条报错读起来更像是
      SDK 的 bug，而非签名不匹配。现在这些键会被丢弃。
    - 仅限位置参数的参数根本无法通过关键字传递，因此这类指标此前永远无法
      被评分。这些参数会单独返回，并按照签名顺序以位置参数方式传递。

    缺失的参数仍会被报告：``validate_score_arguments`` 在此函数之前运行，
    并且只检查指标声明的参数，因此此处的过滤不会隐藏指标实际需要的键。
    """
    try:
        parameters = inspect.signature(score_function).parameters
    except (ValueError, TypeError):
        # 签名无法被内省——与之前一样，全部原样传递。
        return [], kwargs

    # 仅限位置参数的值按位置绑定，因此无法跳过一个空缺：
    # 丢弃缺失的参数会使后续每个值向左错位一格，导致指标基于错误的输入
    # 进行评分。仅传递前部连续的部分；空缺之后即使提供了任何内容，
    # 也意味着该指标无法被评分。
    positional_only_names = [
        name
        for name, parameter in parameters.items()
        if parameter.kind == inspect.Parameter.POSITIONAL_ONLY
    ]
    positional_arguments: List[Any] = []
    unbindable_names: List[str] = []
    for index, name in enumerate(positional_only_names):
        if name not in kwargs:
            unbindable_names = [
                later for later in positional_only_names[index:] if later not in kwargs
            ]
            if any(later in kwargs for later in positional_only_names[index + 1 :]):
                raise exceptions.ScoreMethodMissingArguments(
                    score_name,
                    unbindable_names,
                    list(kwargs.keys()),
                    None,
                )
            break
        positional_arguments.append(kwargs[name])

    accepts_any_keyword = any(
        parameter.kind == inspect.Parameter.VAR_KEYWORD
        for parameter in parameters.values()
    )

    keyword_arguments: Dict[str, Any] = {}
    for name, value in kwargs.items():
        parameter = parameters.get(name)
        if parameter is None:
            if accepts_any_keyword:
                keyword_arguments[name] = value
        elif parameter.kind != inspect.Parameter.POSITIONAL_ONLY:
            keyword_arguments[name] = value

    return positional_arguments, keyword_arguments


def create_scoring_inputs(
    dataset_item: Dict[str, Any],
    task_output: Dict[str, Any],
    scoring_key_mapping: Optional[evaluation_types.ScoringKeyMappingType],
) -> Dict[str, Any]:
    mapped_inputs = {**dataset_item, **task_output}

    if scoring_key_mapping is None:
        return mapped_inputs
    else:
        for key, value in scoring_key_mapping.items():
            if callable(value):
                mapped_inputs[key] = value(mapped_inputs)
            else:
                if value not in mapped_inputs:
                    # 一个匹配不到任何内容的映射始终是一个错误，只是它仅当
                    # 该指标恰好对该参数没有默认值时才在稍后暴露。若确有
                    # 默认值，指标会静默地对错误内容评分，因此这不能停留在
                    # debug 级别（OPIK-6925）。可用键才是可操作的部分，但它们
                    # 属于用户数据，可能数量众多，因此警告中只放入一部分样例。
                    available_keys = list(mapped_inputs.keys())
                    sample = available_keys[:MAX_REPORTED_AVAILABLE_KEYS]
                    remaining = len(available_keys) - len(sample)
                    LOGGER.warning(
                        "评分键映射值 '%s' 未在数据集项中找到。"
                        "可用键（%d）：%s%s",
                        value,
                        len(available_keys),
                        sample,
                        f" and {remaining} more" if remaining > 0 else "",
                    )
                else:
                    mapped_inputs[key] = mapped_inputs[value]

    return mapped_inputs
