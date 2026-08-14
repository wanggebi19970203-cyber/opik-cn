import json
import re
import traceback
from typing import Any, Dict, List, Optional

import pydantic

import opik.exceptions as exceptions
from opik import datetime_helpers, opik_context
from opik.api_objects import opik_client

from . import guard
from .. import schemas


_SYSTEM_PROMPT = """You are a guardrail that decides whether a piece of text complies with a policy.

Policy:
{instructions}

A text passes when it complies with the policy and fails when it violates it.
Respond with a single JSON object and nothing else, using this exact schema:
{{"passed": <true|false>, "reason": "<short explanation>"}}"""

_JSON_OBJECT_PATTERN = re.compile(r"\{.*\}", re.DOTALL)


class _LLMJudgeDecision(pydantic.BaseModel):
    passed: bool
    reason: str


def _usage_dict(response: Any) -> Optional[Dict[str, Any]]:
    usage = getattr(response, "usage", None)
    if usage is None:
        return None

    values = {
        "prompt_tokens": usage.prompt_tokens,
        "completion_tokens": usage.completion_tokens,
        "total_tokens": usage.total_tokens,
    }
    if any(value is None for value in values.values()):
        return None

    return values


class LLMJudge(guard.Guard):
    """
    使用 LLM 作为判定器，依据自然语言策略校验文本的 guard。

    判定器在 SDK 中运行，并调用 Opik 的 chat completions 端点，该端点使用
    你在 Opik 工作区中配置的 LLM 提供商。它不需要 guardrails
    后端。判定器调用会作为 guardrail span 下的嵌套 LLM span 被记录。
    """

    local = True

    def __init__(
        self,
        name: str,
        instructions: str,
        model: str,
    ) -> None:
        """
        初始化一个 LLM 判定器 guard。

        Args:
            name: 检查的名称，用于标记 guardrail 结果。
            instructions: 描述文本必须遵守内容的自然语言策略。
            model: 用于判定的模型名称。必须能通过你在 Opik 工作区中配置的 LLM
                提供商访问到。
        """
        self._name = name
        self._instructions = instructions
        self._model = model

    def validate_local(
        self, text: str, client: opik_client.Opik
    ) -> List[schemas.ValidationResult]:
        messages = [
            {
                "role": "system",
                "content": _SYSTEM_PROMPT.format(instructions=self._instructions),
            },
            {"role": "user", "content": text},
        ]

        start_time = datetime_helpers.local_timestamp()

        # 运行或解析判定的任何失败都会故障关闭（抛出异常），
        # 以免受保护的代码路径在无法得出结论的检查上继续执行。
        try:
            raw_response = client.rest_client.chat_completions.with_raw_response.create_chat_completions(
                model=self._model,
                temperature=0.0,
                messages=messages,  # type: ignore[arg-type]
            )
            response = raw_response.data
            # 提供商和解析后的模型通过响应头返回，而不是在响应体中，
            # 因此必须从原始响应中读取。
            provider = raw_response.headers.get("x-opik-provider")
            actual_model = raw_response.headers.get("x-opik-actual-model")
            content = response.choices[0].message.content
            decision = self._parse_decision(content)
        except Exception as e:
            self._log_span(
                client,
                messages,
                start_time,
                model=self._model,
                error_info={
                    "exception_type": type(e).__name__,
                    "message": str(e),
                    "traceback": traceback.format_exc(),
                },
            )
            raise exceptions.GuardrailValidationError(
                f"LLM 判定器 '{self._name}' 无法被评估，故障关闭：{e}"
            ) from e

        self._log_span(
            client,
            messages,
            start_time,
            output={"content": content},
            usage=_usage_dict(response),
            model=actual_model or getattr(response, "model", None) or self._model,
            provider=provider,
        )

        return [
            schemas.ValidationResult(
                validation_passed=decision.passed,
                type=schemas.ValidationType.LLM_JUDGE,
                validation_config={
                    "name": self._name,
                    "instructions": self._instructions,
                    "model": self._model,
                },
                validation_details={
                    "name": self._name,
                    "passed": decision.passed,
                    "reason": decision.reason,
                },
            )
        ]

    def _log_span(
        self,
        client: opik_client.Opik,
        messages: List[Dict[str, str]],
        start_time: Any,
        **kwargs: Any,
    ) -> None:
        # 将判定器调用作为 guardrail span 下的嵌套 LLM span，在单次 create 调用中
        # 记录（同时带上开始和结束时间），以避免 create 后再 end 的更新方式
        # 在批处理下可能遇到的数据丢失问题。记录是尽力而为的，
        # 绝不能影响 guardrail 的结果。
        current_span = opik_context.get_current_span_data()
        if current_span is None:
            return

        try:
            client.span(
                trace_id=current_span.trace_id,
                parent_span_id=current_span.id,
                name="llm_judge",
                type="llm",
                input={"messages": messages},
                start_time=start_time,
                end_time=datetime_helpers.local_timestamp(),
                **kwargs,
            )
        except Exception:
            pass

    def _parse_decision(self, content: str) -> _LLMJudgeDecision:
        match = _JSON_OBJECT_PATTERN.search(content or "")
        if match is None:
            raise ValueError(f"LLM 判定器返回了非 JSON 响应：{content}")

        return _LLMJudgeDecision.model_validate(json.loads(match.group(0)))
