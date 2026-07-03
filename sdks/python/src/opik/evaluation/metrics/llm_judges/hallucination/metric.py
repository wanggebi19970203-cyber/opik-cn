from typing import Union, Optional, List, Any
import pydantic

from opik.evaluation.models import base_model, models_factory
from opik.evaluation.metrics import score_result, base_metric

from . import template, parser


class HallucinationResponseFormat(pydantic.BaseModel):
    score: float
    reason: List[str]


class Hallucination(base_metric.BaseMetric):
    """
    评估 LLM 输出是否基于给定输入和上下文产生幻觉的指标。

    该指标使用另一个 LLM 来判断输出是否包含事实性错误或幻觉。
    如果检测到幻觉则返回 1.0 分，否则返回 0.0 分。

    Args:
        model: 用于评估的 LLM。可以是字符串（模型名称）或 `opik.evaluation.models.OpikBaseModel` 子类实例。
            默认使用 `opik.evaluation.models.LiteLLMChatModel`。
        name: 指标名称。
        few_shot_examples: 用于幻觉检测的少样本示例列表。如果为 None，将使用默认示例。
        track: 是否追踪该指标。默认为 True。
        project_name: 可选的项目名称，用于在没有父级 span/trace 可继承项目名称时追踪指标。
        seed: 可选的随机种子值，用于可复现的模型生成。如果提供，此种子将传递给模型以获得确定性输出。
        temperature: 可选的模型生成温度值。如果提供，此温度将传递给模型。如果未提供，将使用模型的默认温度。

    Example:
        >>> from opik.evaluation.metrics import Hallucination
        >>> hallucination_metric = Hallucination()
        >>> result = hallucination_metric.score(
        ...     input="What is the capital of France?",
        ...     output="The capital of France is London.",
        ...     context=["The capital of France is Paris."]
        ... )
        >>> print(result.value)
        1.0
        >>> print(result.reason)
        The answer provided states that the capital of France is London, which contradicts the fact stated in the context that the capital of France is Paris.
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "hallucination_metric",
        few_shot_examples: Optional[List[template.FewShotExampleHallucination]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        seed: Optional[int] = None,
        temperature: Optional[float] = None,
    ):
        super().__init__(name=name, track=track, project_name=project_name)
        self._seed = seed
        self._init_model(model, temperature=temperature)
        self.few_shot_examples = few_shot_examples

    def _init_model(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]],
        temperature: Optional[float],
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            model_kwargs = {}
            if temperature is not None:
                model_kwargs["temperature"] = temperature
            if self._seed is not None:
                model_kwargs["seed"] = self._seed

            self._model = models_factory.get(
                model_name=model, track=self.track, **model_kwargs
            )

    def score(
        self,
        input: str,
        output: str,
        context: Optional[List[str]] = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        计算给定输入、输出和可选上下文字段的幻觉分数。

        Args:
            input: 原始输入/问题。
            output: 待评估的 LLM 输出。
            context: 上下文字符串列表。如果未提供，将仅基于输出评估是否存在幻觉。
            **ignored_kwargs: 被忽略的额外关键字参数。

        Returns:
            score_result.ScoreResult: 如果检测到幻觉则返回值为 1.0 的 ScoreResult 对象，
                否则返回值为 0.0，同时附带判定原因。
        """
        messages = template.build_messages(
            input=input,
            output=output,
            context=context,
            few_shot_examples=self.few_shot_examples,
        )
        message = self._model.generate_chat_completion(
            messages=messages, response_format=HallucinationResponseFormat
        )

        return parser.parse_model_output(content=message["content"], name=self.name)

    async def ascore(
        self,
        input: str,
        output: str,
        context: Optional[List[str]] = None,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        异步计算给定输入、输出和可选上下文字段的幻觉分数。

        Args:
            input: 原始输入/问题。
            output: 待评估的 LLM 输出。
            context: 上下文字符串列表。如果未提供，将仅基于输出评估是否存在幻觉。
            **ignored_kwargs: 被忽略的额外关键字参数。

        Returns:
            score_result.ScoreResult: 如果检测到幻觉则返回值为 1.0 的 ScoreResult 对象，
                否则返回值为 0.0，同时附带判定原因。
        """
        messages = template.build_messages(
            input=input,
            output=output,
            context=context,
            few_shot_examples=self.few_shot_examples,
        )
        message = await self._model.agenerate_chat_completion(
            messages=messages, response_format=HallucinationResponseFormat
        )

        return parser.parse_model_output(content=message["content"], name=self.name)
