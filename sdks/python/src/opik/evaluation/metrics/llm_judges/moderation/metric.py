from typing import Any, List, Optional, Union
from opik.evaluation.metrics.llm_judges.moderation import parser
import pydantic
from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory
from . import template


class ModerationResponseFormat(pydantic.BaseModel):
    score: float
    reason: str


class Moderation(base_metric.BaseMetric):
    """
    使用 LLM 评估输入-输出对内容审核级别的指标。

    该指标使用语言模型来评估给定输入和输出的内容审核级别。
    返回 0.0 到 1.0 之间的分数，数值越高表示内容越合规。

    Args:
        model: 用于内容审核的语言模型。可以是字符串（模型名称）或 `opik.evaluation.models.OpikBaseModel` 子类实例。
            默认使用 `opik.evaluation.models.LiteLLMChatModel`。
        name: 指标名称。默认为 "moderation_metric"。
        few_shot_examples: 用于查询的少样本示例列表。如果为 None，将使用默认示例。
        track: 是否追踪该指标。默认为 True。
        project_name: 可选的项目名称，用于在没有父级 span/trace 可继承项目名称时追踪指标。
        seed: 可选的随机种子值，用于可复现的模型生成。如果提供，此种子将传递给模型以获得确定性输出。
        temperature: 可选的模型生成温度值。如果提供，此温度将传递给模型。如果未提供，将使用模型的默认温度。

    Example:
        >>> from opik.evaluation.metrics import Moderation
        >>> moderation_metric = Moderation()
        >>> result = moderation_metric.score("Hello, how can I help you?")
        >>> print(result.value)  # 0.0 到 1.0 之间的浮点数
        >>> print(result.reason)  # 分数的解释说明
    """

    def __init__(
        self,
        model: Optional[Union[str, base_model.OpikBaseModel]] = None,
        name: str = "moderation_metric",
        few_shot_examples: Optional[List[template.FewShotExampleModeration]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        seed: Optional[int] = None,
        temperature: Optional[float] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )
        self._seed = seed
        self._init_model(model, temperature=temperature)
        self.few_shot_examples = [] if few_shot_examples is None else few_shot_examples

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

    def score(self, output: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        """
        计算给定输入-输出对的内容审核分数。

        Args:
            output: 待评估的输出文本。
            **ignored_kwargs (Any): 被忽略的额外关键字参数。

        Returns:
            score_result.ScoreResult: 包含内容审核分数（0.0 到 1.0 之间）和分数原因的 ScoreResult 对象。
        """
        messages = template.build_messages(
            output=output, few_shot_examples=self.few_shot_examples
        )
        message = self._model.generate_chat_completion(
            messages=messages, response_format=ModerationResponseFormat
        )

        return parser.parse_model_output(content=message["content"], name=self.name)

    async def ascore(
        self, output: str, **ignored_kwargs: Any
    ) -> score_result.ScoreResult:
        """
        异步计算给定输入-输出对的内容审核分数。

        此方法是 :meth:`score` 的异步版本。详细文档请参阅 :meth:`score` 方法。

        Args:
            output: 待评估的输出文本。
            **ignored_kwargs: 被忽略的额外关键字参数。

        Returns:
            score_result.ScoreResult: 包含内容审核分数和原因的 ScoreResult 对象。
        """

        messages = template.build_messages(
            output=output, few_shot_examples=self.few_shot_examples
        )
        message = await self._model.agenerate_chat_completion(
            messages=messages, response_format=ModerationResponseFormat
        )

        return parser.parse_model_output(content=message["content"], name=self.name)
