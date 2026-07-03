import importlib.metadata
import logging
import warnings
from functools import cached_property
from typing import Any, cast, Dict, List, Optional, Set, TYPE_CHECKING, Type
import pydantic
import tenacity

if TYPE_CHECKING:
    from litellm.types.utils import ModelResponse

import opik.semantic_version as semantic_version
import opik.config as opik_config

from .. import base_model, model_name_helper
from . import warning_filters, response_parser, util
from opik import exceptions

LOGGER = logging.getLogger(__name__)


def _log_warning(message: str, *args: Any) -> None:
    """同时向本模块日志记录器和根日志记录器发出警告。

    pytest 的日志捕获挂接到根日志记录器，而生产环境使用模块级日志记录器。
    同时记录到两者可确保警告在测试和运行时均可见，且不会重复调用点。
    """

    LOGGER.warning(message, *args)
    root_logger = logging.getLogger()
    if root_logger is not LOGGER:
        root_logger.log(logging.WARNING, message, *args)


class LiteLLMChatModel(base_model.OpikBaseModel):
    def __init__(
        self,
        model_name: str = "gpt-5-nano",
        must_support_arguments: Optional[List[str]] = None,
        track: bool = True,
        **completion_kwargs: Any,
    ) -> None:
        import litellm

        """
        使用给定的模型名称初始化基础模型。
        所有可用的 completion_kwargs 参数详见：https://docs.litellm.ai/docs/completion/input

        Args:
            model_name: 要使用的 LLM 模型名称。
                此参数将传递给 `litellm.completion(model=model_name)`，因此无需在
                completion_kwargs 中单独传递 `model` 参数。
            must_support_arguments: 给定模型+提供商组合必须支持的 OpenAI 风格参数列表。
                使用 `litellm.get_supported_openai_params(model_name)` 获取支持的参数。
                如果缺少任何参数，将抛出 ValueError。
                可传入此表格中的参数：https://docs.litellm.ai/docs/completion/input#translated-openai-params
            track: 是否追踪模型调用。为 False 时禁用此模型实例的追踪。
                默认为 True。
            completion_kwargs: 始终额外传递给 `litellm.completion` 函数的键值参数。
        """
        super().__init__(model_name=model_name)

        self._check_model_name()
        self._check_must_support_arguments(must_support_arguments)

        self._unsupported_warned: Set[str] = set()

        self._completion_kwargs: Dict[str, Any] = (
            self._remove_unnecessary_not_supported_params(completion_kwargs)
        )

        with warnings.catch_warnings():
            # 这是 opik 导入时首次导入 litellm 的位置。
            # 过滤掉 pydantic 警告。
            # LiteLLM 已修复此问题但尚未发布，因此此过滤器应尽快移除。
            warnings.simplefilter("ignore")

        warning_filters.add_warning_filters()

        config = opik_config.OpikConfig()

        # 仅当 track 参数为 True 且配置允许时才启用追踪
        enable_tracking = track and config.enable_litellm_models_monitoring

        if enable_tracking:
            import opik.integrations.litellm as litellm_integration

            self._litellm_completion = litellm_integration.track_completion()(
                litellm.completion
            )
            self._litellm_acompletion = litellm_integration.track_completion()(
                litellm.acompletion
            )
        else:
            self._litellm_completion = litellm.completion
            self._litellm_acompletion = litellm.acompletion

    @cached_property
    def supported_params(self) -> Set[str]:
        import litellm

        supported_params = set(
            litellm.get_supported_openai_params(model=self.model_name)
        )
        self._ensure_supported_params(supported_params)

        return supported_params

    def _ensure_supported_params(self, params: Set[str]) -> None:
        """
        LiteLLM 对某些参数的支持可能存在问题。如果检测到，可添加自定义过滤
        以确保模型调用不会失败。
        """
        import litellm

        provider = litellm.get_llm_provider(self.model_name)[1]

        if provider not in ["groq", "ollama"]:
            return

        litellm_version = importlib.metadata.version("litellm")
        if semantic_version.SemanticVersion.parse(litellm_version) < "1.52.15":  # type: ignore
            params.discard("response_format")
            LOGGER.warning(
                "LiteLLM version %s does not support structured outputs for %s provider. We recomment updating to at least 1.52.15 for a more robust metrics calculation.",
                litellm_version,
                provider,
            )

    def _check_model_name(self) -> None:
        import litellm

        try:
            _ = litellm.get_llm_provider(self.model_name)
        except litellm.exceptions.BadRequestError:
            raise ValueError(f"Unsupported model: '{self.model_name}'!")

    def _check_must_support_arguments(self, args: Optional[List[str]]) -> None:
        if args is None:
            return

        for key in args:
            if key not in self.supported_params:
                raise ValueError(f"Unsupported parameter: '{key}'!")

    def _remove_unnecessary_not_supported_params(
        self, params: Dict[str, Any]
    ) -> Dict[str, Any]:
        filtered_params = {**params}

        # 修复受影响的提供商（如 Groq 和 OpenAI）的问题
        if (
            "response_format" in params
            and "response_format" not in self.supported_params
        ):
            filtered_params.pop("response_format")
            LOGGER.debug(
                "This model does not support the response_format parameter and it will be ignored."
            )
        if (
            "reasoning_effort" in params
            and "reasoning_effort" not in self.supported_params
        ):
            filtered_params.pop("reasoning_effort")
            LOGGER.debug(
                "Model %s does not support reasoning_effort, dropping.",
                self.model_name,
            )
        # `seed` 是 OpenAI 风格的确定性控制参数；Anthropic（及少数其他提供商）
        # 会直接通过 `UnsupportedParamsError` 拒绝而非忽略。原生
        # `AnthropicChatModel` 通过 `filter_unsupported_params` 静默过滤；
        # 在此匹配该行为，防止经 LiteLLM 路由的 Anthropic 模型在调用方
        # （如 agentic judge）传入 `seed` 以在支持该参数的提供商上实现可复现性时崩溃。
        if "seed" in params and "seed" not in self.supported_params:
            filtered_params.pop("seed")
            LOGGER.debug(
                "Model %s does not support seed, dropping.",
                self.model_name,
            )
        # 注意：基于 `supported_params` 的过滤已临时禁用，因为 LiteLLM 未通过
        # `get_supported_openai_params` 暴露提供商特定的连接字段。丢弃这些 kwargs
        # 会破坏依赖 `api_version` 和 `azure_endpoint` 等参数的 Azure/Groq 用户。
        # 旧逻辑保留注释以备将来恢复。
        #
        # for key in list(filtered_params.keys()):
        #     if (
        #         key not in self.supported_params
        #         and not util.should_preserve_provider_param(key)
        #     ):
        #         filtered_params.pop(key)
        #         if key not in self._unsupported_warned:
        #             _log_warning(
        #                 "Parameter '%s' is not supported by model %s and will be ignored.",
        #                 key,
        #                 self.model_name,
        #             )
        #             self._unsupported_warned.add(key)

        util.apply_model_specific_filters(
            model_name=self.model_name,
            params=filtered_params,
            already_warned=self._unsupported_warned,
            warn=self._warn_about_unsupported_param,
        )

        return filtered_params

    def _resolve_provider_conflicts(
        self, effective_kwargs: Dict[str, Any]
    ) -> Dict[str, Any]:
        """丢弃单独有效但合并后冲突的参数。

        某些冲突只能通过查看完整有效请求（构造函数默认值 *加上* 每次调用覆盖值）
        来诊断，因为两个来源可以各自提供冲突的一方。

        当前处理的冲突：

        - Anthropic + `reasoning_effort` + 显式非 1 的 `temperature`：
          LiteLLM 将 OpenAI 风格的 `reasoning_effort` 翻译为 Anthropic 特有的
          `thinking` 参数；启用 thinking 时，Anthropic 要求 `temperature == 1`。
          设置确定性温度的调用方（agentic judge 循环将其固定为 0，大多数对可复现性
          敏感的调用方也是如此）否则会收到提供商的 400 错误。仅在冲突确实存在时
          丢弃 `reasoning_effort`，以便明确同时选择两者的调用方（`temperature=1`
          *且* `reasoning_effort=...`）保留扩展思考能力。

        在合并字典上运行 —— 按来源的 `_remove_unnecessary_not_supported_params`
        无法捕获跨源冲突（即冲突的一半在 `self._completion_kwargs` 中，
        另一半在每次调用的 kwargs 中）。
        """
        resolved = {**effective_kwargs}
        if "reasoning_effort" in resolved and model_name_helper.is_anthropic_model(
            self.model_name
        ):
            raw_temperature = resolved.get("temperature")
            if raw_temperature is not None:
                # 匹配 LiteLLM 自身的强制转换语义 —— `temperature` 接受
                # int / float / 数字字符串。不进行强制转换的话，字符串形式的 `"1"`
                # 会与 int `1` 比较不等，导致在 *确实* 选择了 thinking 的调用方上
                # 丢弃 `reasoning_effort`。强制转换失败（非数字值）视为"未知"，
                # 不动 `reasoning_effort` —— 提供商会暴露真正的类型错误。
                numeric_temperature = util.coerce_temperature_to_float(raw_temperature)
                if (
                    numeric_temperature is not None
                    and abs(numeric_temperature - 1.0) > 1e-6
                ):
                    resolved.pop("reasoning_effort")
                    LOGGER.debug(
                        "Dropping reasoning_effort for Anthropic model %s: "
                        "explicit temperature=%s (non-1) in the merged call "
                        "kwargs conflicts with the thinking mode LiteLLM "
                        "would enable. Pass temperature=1 (or omit it) to "
                        "keep reasoning_effort.",
                        self.model_name,
                        raw_temperature,
                    )
        return resolved

    def _warn_about_unsupported_param(self, param: str, value: Any) -> None:
        if param in {"logprobs", "top_logprobs"}:
            # 当 gpt-5-nano 等模型不支持这些字段时，LiteLLM 会发出大量警告。
            # 我们已优雅地丢弃它们，因此跳过日志以避免向 GEval 用户发送重复警告。
            return
        if param == "temperature":
            _log_warning(
                "Model %s only supports temperature=1. Dropping temperature=%s.",
                self.model_name,
                value,
            )
        else:
            _log_warning(
                "Model %s does not support %s. Dropping the parameter.",
                self.model_name,
                param,
            )

    def generate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        """
        从模型生成字符串输出的简化接口。
        所有可用的 completion_kwargs 参数详见：https://docs.litellm.ai/docs/completion/input

        Args:
            input: 模型基于此输入字符串生成输出。
            response_format: 指定期望输出字符串格式的 pydantic 模型。
            kwargs: 模型生成字符串时可能使用的额外参数。

        Returns:
            str: 生成的字符串输出。
        """
        message = self.generate_chat_completion(
            messages=[{"role": "user", "content": input}],
            response_format=response_format,
            **kwargs,
        )
        return message["content"]

    def generate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        """
        从转发给提供商的聊天消息列表生成 assistant 轮次。

        当需要跨调用保持稳定的 ``system`` 前缀以便提供商端提示缓存生效时
        使用此方法（评判指标、套件评估器）。

        Args:
            messages: ``{"role": ..., "content": ...}`` 字典列表。
            response_format: 可选的 Pydantic 模型，指定期望的输出格式。
            kwargs: 转发给 ``litellm.completion`` 的额外参数。

        Returns:
            ``{"role": "assistant", "content": ...}``。
        """
        if response_format is not None:
            kwargs["response_format"] = response_format

        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)

        with base_model.get_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **valid_litellm_params,
        ) as response:
            return response_parser.parse_assistant_message(response)

    def generate_provider_response(
        self,
        messages: List[Dict[str, Any]],
        **kwargs: Any,
    ) -> "ModelResponse":
        """
        请勿直接调用此方法。此方法仅供 `base_model.get_provider_response()` 内部使用。

        生成提供商特定的响应。可用于与底层模型提供商（如 OpenAI、Anthropic）
        交互并获取原始输出。
        所有可用的输入参数详见：https://docs.litellm.ai/docs/completion/input

        Args:
            messages: 发送给模型的消息列表，应为包含 "content" 和 "role" 键的字典列表。
            kwargs: 提供商生成响应所需的参数。

        Returns:
            Any: 模型提供商返回的响应，类型取决于具体用例和 LLM。
        """

        # 在过滤参数前提取重试配置
        retries = kwargs.pop("__opik_retries", 3)
        try:
            max_attempts = max(1, int(retries))
        except (TypeError, ValueError):
            max_attempts = 1

        # 需要先弹出 messages，然后检查其余参数
        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)
        all_kwargs = {**self._completion_kwargs, **valid_litellm_params}
        # 只能在合并字典上诊断的冲突（构造函数 + 每次调用来源）在此处理
        # —— 详见方法文档中 Anthropic reasoning_effort/temperature 的案例。
        all_kwargs = self._resolve_provider_conflicts(all_kwargs)

        retrying = tenacity.Retrying(
            reraise=True,
            stop=tenacity.stop_after_attempt(max_attempts),
            wait=tenacity.wait_exponential(multiplier=0.5, min=0.5, max=8.0),
        )

        return retrying(
            self._litellm_completion,
            model=self.model_name,
            messages=messages,
            **all_kwargs,
        )

    async def agenerate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        """
        从模型生成字符串输出的简化接口（异步版本）。
        所有可用的输入参数详见：https://docs.litellm.ai/docs/completion/input

        Args:
            input: 模型基于此输入字符串生成输出。
            response_format: 指定期望输出字符串格式的 pydantic 模型。
            kwargs: 模型生成字符串时可能使用的额外参数。

        Returns:
            str: 生成的字符串输出。
        """
        message = await self.agenerate_chat_completion(
            messages=[{"role": "user", "content": input}],
            response_format=response_format,
            **kwargs,
        )
        return message["content"]

    async def agenerate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        """:meth:`generate_chat_completion` 的异步版本。"""
        if response_format is not None:
            kwargs["response_format"] = response_format

        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)

        async with base_model.aget_provider_response(
            model_provider=self,
            messages=cast(List[Dict[str, Any]], list(messages)),
            **valid_litellm_params,
        ) as response:
            return response_parser.parse_assistant_message(response)

    async def agenerate_provider_response(
        self, messages: List[Dict[str, Any]], **kwargs: Any
    ) -> "ModelResponse":
        """
        请勿直接调用此方法。此方法仅供 `base_model.aget_provider_response()` 内部使用。

        生成提供商特定的响应（异步版本）。可用于与底层模型提供商
        （如 OpenAI、Anthropic）交互并获取原始输出。
        所有可用的输入参数详见：https://docs.litellm.ai/docs/completion/input

        Args:
            messages: 发送给模型的消息列表，应为包含 "content" 和 "role" 键的字典列表。
            kwargs: 提供商生成响应所需的参数。

        Returns:
            Any: 模型提供商返回的响应，类型取决于具体用例和 LLM。
        """

        retries = kwargs.pop("__opik_retries", 3)
        try:
            max_attempts = max(1, int(retries))
        except (TypeError, ValueError):
            max_attempts = 1

        valid_litellm_params = self._remove_unnecessary_not_supported_params(kwargs)
        all_kwargs = {**self._completion_kwargs, **valid_litellm_params}
        # 参见同步版 `generate_provider_response` 了解为何合并字典
        # 需要单独的冲突解决处理。
        all_kwargs = self._resolve_provider_conflicts(all_kwargs)

        retrying = tenacity.AsyncRetrying(
            reraise=True,
            stop=tenacity.stop_after_attempt(max_attempts),
            wait=tenacity.wait_exponential(multiplier=0.5, min=0.5, max=8.0),
        )

        async for attempt in retrying:
            with attempt:
                return await self._litellm_acompletion(
                    model=self.model_name, messages=messages, **all_kwargs
                )

        raise exceptions.BaseLLMError(
            "Async LLM completion failed without raising an exception"
        )  # pragma: no cover
