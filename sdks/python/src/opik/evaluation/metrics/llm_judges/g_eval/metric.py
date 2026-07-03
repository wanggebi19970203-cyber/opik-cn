from collections import OrderedDict
from threading import Lock
from typing import Any, cast, Dict, List, Optional, Tuple, Union
import pydantic

from opik.evaluation.metrics import base_metric, score_result
from opik.evaluation.models import base_model, models_factory
from opik.evaluation import models
from . import template, parser
from .presets import GEVAL_PRESETS


class GEvalScoreFormat(pydantic.BaseModel):
    score: int
    reason: str


def _freeze_for_cache(value: Any) -> Any:
    """将嵌套结构转换为可哈希的表示形式以用于缓存。"""

    if isinstance(value, dict):
        return tuple(
            sorted((key, _freeze_for_cache(val)) for key, val in value.items())
        )
    if isinstance(value, (list, tuple)):
        return tuple(_freeze_for_cache(item) for item in value)
    if isinstance(value, set):
        return tuple(sorted(_freeze_for_cache(item) for item in value))
    return value


class GEval(base_metric.BaseMetric):
    """
    通用评估指标，通过提示 LLM 对另一个 LLM 的输出进行评分。

    GEval 使用提供的 ``task_introduction`` 和 ``evaluation_criteria`` 提示构建
    可复用的思维链，然后为每个被评估的输出请求最终分数和理由。

    Args:
        task_introduction: 描述评估者角色/目的的指令。
        evaluation_criteria: 呈现给评估者的详细评分标准。
        model: 可选的模型标识符或 ``OpikBaseModel`` 实例，用于评判。
        name: 指标结果的显示名称。默认为 ``"g_eval_metric"``。
        track: 是否自动追踪指标结果。默认为 ``True``。
        project_name: 可选的追踪项目名称。
        temperature: 传递给评判模型的采样温度。
        seed: 可选的随机种子，用于可复现的生成（如果模型支持）。
        reasoning_effort: 可选的模型推理努力级别。适用于暴露 reasoning_effort 参数的
            提供商/模型（如 OpenAI gpt-5 系列）。支持的值通常包括 "minimal"、"low"、
            "medium"、"high"。默认为 None（使用提供商默认值——OpenAI 推理模型通常为 "medium"）。
            如果需要减少推理 token 消耗，请显式传递此参数。

    Example:
        >>> from opik.evaluation.metrics.llm_judges.g_eval.metric import GEval
        >>> metric = GEval(
        ...     task_introduction="You evaluate politeness of responses.",
        ...     evaluation_criteria="Score from 1 (rude) to 5 (very polite).",
        ...     model="gpt-4",
        ... )
        >>> result = metric.score(output="Thanks so much for your help!")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.9
    """

    _CHAIN_OF_THOUGHT_CACHE: "OrderedDict[Tuple[str, str, str, Any], str]" = (
        OrderedDict()
    )
    _CHAIN_OF_THOUGHT_LOCK: Lock = Lock()
    _MAX_CHAIN_OF_THOUGHT_CACHE = 128

    def __init__(
        self,
        task_introduction: str,
        evaluation_criteria: str,
        model: Optional[Union[str, models.base_model.OpikBaseModel]] = None,
        name: str = "g_eval_metric",
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
        seed: Optional[int] = None,
        reasoning_effort: Optional[str] = None,
    ):
        super().__init__(
            name=name,
            track=track,
            project_name=project_name,
        )
        self.task_introduction = task_introduction
        self.evaluation_criteria = evaluation_criteria
        self._seed = seed
        self._reasoning_effort = reasoning_effort

        self._log_probs_supported = False

        self._init_model(model, temperature=temperature)

    def llm_chain_of_thought(self) -> str:
        cache_key = self._chain_of_thought_cache_key()
        cached = self._get_cached_chain_of_thought(cache_key)
        if cached is not None:
            return cached

        messages = template.build_chain_of_thought_messages(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
        )
        message = self._model.generate_chat_completion(messages=messages)
        generated = message["content"]
        self._store_chain_of_thought(cache_key, generated)
        return generated

    async def allm_chain_of_thought(self) -> str:
        cache_key = self._chain_of_thought_cache_key()
        cached = self._get_cached_chain_of_thought(cache_key)
        if cached is not None:
            return cached

        messages = template.build_chain_of_thought_messages(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
        )
        message = await self._model.agenerate_chat_completion(messages=messages)
        generated = message["content"]
        self._store_chain_of_thought(cache_key, generated)
        return generated

    def _init_model(
        self, model: Optional[Union[str, base_model.OpikBaseModel]], temperature: float
    ) -> None:
        if isinstance(model, base_model.OpikBaseModel):
            self._model = model
        else:
            model_kwargs: Dict[str, Any] = {"temperature": temperature}
            if self._seed is not None:
                model_kwargs["seed"] = self._seed
            if self._reasoning_effort is not None:
                model_kwargs["reasoning_effort"] = self._reasoning_effort

            self._model = models_factory.get(
                model_name=model, track=self.track, **model_kwargs
            )

        if (
            hasattr(self._model, "supported_params")
            and "logprobs" in self._model.supported_params
            and "top_logprobs" in self._model.supported_params
        ):
            self._log_probs_supported = True

    @classmethod
    def _get_cached_chain_of_thought(
        cls, cache_key: Tuple[str, str, str, Any]
    ) -> Optional[str]:
        with cls._CHAIN_OF_THOUGHT_LOCK:
            value = cls._CHAIN_OF_THOUGHT_CACHE.get(cache_key)
            if value is not None:
                cls._CHAIN_OF_THOUGHT_CACHE.move_to_end(cache_key)
            return value

    @classmethod
    def _store_chain_of_thought(
        cls, cache_key: Tuple[str, str, str, Any], value: str
    ) -> None:
        with cls._CHAIN_OF_THOUGHT_LOCK:
            existing = cls._CHAIN_OF_THOUGHT_CACHE.get(cache_key)
            if existing is not None:
                cls._CHAIN_OF_THOUGHT_CACHE.move_to_end(cache_key)
                return
            cls._CHAIN_OF_THOUGHT_CACHE[cache_key] = value
            cls._CHAIN_OF_THOUGHT_CACHE.move_to_end(cache_key)
            while len(cls._CHAIN_OF_THOUGHT_CACHE) > cls._MAX_CHAIN_OF_THOUGHT_CACHE:
                cls._CHAIN_OF_THOUGHT_CACHE.popitem(last=False)

    def _chain_of_thought_cache_key(self) -> Tuple[str, str, str, Any]:
        model_name = getattr(self._model, "model_name", "unknown")
        return (
            self.task_introduction,
            self.evaluation_criteria,
            model_name,
            self._model_cache_fingerprint(),
        )

    def _model_cache_fingerprint(self) -> Any:
        fingerprint_candidate = getattr(self._model, "cache_fingerprint", None)
        if callable(fingerprint_candidate):
            try:
                fingerprint = fingerprint_candidate()
            except Exception:
                fingerprint = None
            else:
                return _freeze_for_cache(fingerprint)

        completion_kwargs = getattr(self._model, "_completion_kwargs", None)
        if isinstance(completion_kwargs, dict):
            return _freeze_for_cache(completion_kwargs)

        return id(self._model)

    def score(
        self,
        output: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        计算给定 LLM 输出的 G-Eval 分数。

        Args:
            output: 待评估的 LLM 输出。
            **ignored_kwargs: 被忽略的额外关键字参数。

        Returns:
            score_result.ScoreResult: 包含 G-Eval 分数（0.0 到 1.0 之间）和分数原因的 ScoreResult 对象。
        """
        messages = template.build_query_messages(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
            chain_of_thought=self.llm_chain_of_thought(),
            input=output,
        )

        if isinstance(self._model, models.LiteLLMChatModel):
            provider_kwargs: Dict[str, Any] = {
                "response_format": GEvalScoreFormat,
            }
            if self._log_probs_supported:
                provider_kwargs["logprobs"] = True
                provider_kwargs["top_logprobs"] = 20

            with base_model.get_provider_response(
                model_provider=self._model,
                messages=cast(List[Dict[str, Any]], list(messages)),
                **provider_kwargs,
            ) as model_output:
                return parser.parse_litellm_model_output(
                    content=model_output,
                    name=self.name,
                    log_probs_supported=self._log_probs_supported,
                )

        message = self._model.generate_chat_completion(
            messages=messages, response_format=GEvalScoreFormat
        )

        return parser.parse_model_output_string(message["content"], self.name)

    async def ascore(
        self,
        output: str,
        **ignored_kwargs: Any,
    ) -> score_result.ScoreResult:
        """
        :meth:`score` 的异步版本，使用配置的评判模型评估提供的 LLM 输出并返回 ``ScoreResult``。
        """
        messages = template.build_query_messages(
            task_introduction=self.task_introduction,
            evaluation_criteria=self.evaluation_criteria,
            chain_of_thought=await self.allm_chain_of_thought(),
            input=output,
        )

        if isinstance(self._model, models.LiteLLMChatModel):
            provider_kwargs: Dict[str, Any] = {
                "response_format": GEvalScoreFormat,
            }
            if self._log_probs_supported:
                provider_kwargs["logprobs"] = True
                provider_kwargs["top_logprobs"] = 20

            async with base_model.aget_provider_response(
                model_provider=self._model,
                messages=cast(List[Dict[str, Any]], list(messages)),
                **provider_kwargs,
            ) as model_output:
                return parser.parse_litellm_model_output(
                    content=model_output,
                    name=self.name,
                    log_probs_supported=self._log_probs_supported,
                )

        message = await self._model.agenerate_chat_completion(
            messages=messages, response_format=GEvalScoreFormat
        )

        return parser.parse_model_output_string(message["content"], self.name)


class GEvalPreset(GEval):
    """
    预配置的 GEval 变体，带有作者提供的提示模板。

    Args:
        preset: 来自 ``GEVAL_PRESETS`` 的键名，描述评估标准。
        model: 可选的模型标识符或 ``OpikBaseModel`` 实例。
        track: 是否自动追踪指标结果。默认为 ``True``。
        project_name: 可选的追踪项目名称。
        temperature: 传递给评判模型的采样温度。
        name: 可选的指标名称覆盖（默认为预设名称）。

    Example:
        >>> from opik.evaluation.metrics.llm_judges.g_eval.metric import GEvalPreset
        >>> metric = GEvalPreset(preset="qa_relevance", model="gpt-4")
        >>> result = metric.score(output="Answer addresses the user's question.")  # doctest: +SKIP
        >>> result.value  # doctest: +SKIP
        0.85
    """

    def __init__(
        self,
        preset: str,
        model: Optional[Union[str, models.base_model.OpikBaseModel]] = None,
        track: bool = True,
        project_name: Optional[str] = None,
        temperature: float = 0.0,
        name: Optional[str] = None,
        reasoning_effort: Optional[str] = None,
    ):
        try:
            definition = GEVAL_PRESETS[preset]
        except KeyError as error:
            raise ValueError(
                f"Unknown GEval preset '{preset}'. Available presets: {list(GEVAL_PRESETS)}"
            ) from error

        super().__init__(
            task_introduction=definition.task_introduction,
            evaluation_criteria=definition.evaluation_criteria,
            model=model,
            name=name or definition.name,
            track=track,
            project_name=project_name,
            temperature=temperature,
            reasoning_effort=reasoning_effort,
        )
