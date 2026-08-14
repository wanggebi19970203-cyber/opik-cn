import logging
from typing import Any, Dict, List, Optional, Sequence

import pydantic

import opik.exceptions as exceptions
from opik.api_objects import opik_client
from opik.rest_api import core as rest_api_core

from . import guards, schemas

LOGGER = logging.getLogger(__name__)

RETRIEVE_POLICIES_PATH = "v1/private/guardrails/policies/retrieve"


class StoredGuard(pydantic.BaseModel):
    """单个已存储的检查项：一个类型以及匹配 guard 类的配置。"""

    type: str
    config: Dict[str, Any]


class StoredPolicy(pydantic.BaseModel):
    """存储在工作空间中的一组命名的 guard。"""

    name: str
    guards: List[StoredGuard]


def retrieve_policies(
    client: opik_client.Opik, names: Optional[Sequence[str]]
) -> List[StoredPolicy]:
    """
    获取指定的策略，以及工作空间无条件应用的所有策略。

    此请求通过手工构造而非使用生成的 REST 客户端：该端点不在
    客户端所依据的已发布 OpenAPI 定义中。
    """
    response = client.rest_client._client_wrapper.httpx_client.request(
        RETRIEVE_POLICIES_PATH,
        method="POST",
        json={"names": list(names) if names is not None else []},
    )

    if response.status_code != 200:
        raise rest_api_core.ApiError(
            status_code=response.status_code, body=response.text
        )

    return [StoredPolicy(**policy) for policy in response.json()["policies"]]


def build_guards(policies: Sequence[StoredPolicy]) -> List[guards.Guard]:
    """
    将存储的策略转换为运行时 guard。

    不保留 guard 来自哪个策略：所有策略的 guard 会被一起检查，
    作为一个扁平的集合。
    """
    return [
        _build_guard(policy_name=policy.name, stored_guard=stored_guard)
        for policy in policies
        for stored_guard in policy.guards
    ]


def _build_guard(policy_name: str, stored_guard: StoredGuard) -> guards.Guard:
    config = stored_guard.config

    if stored_guard.type == schemas.ValidationType.PII:
        return guards.PII(
            blocked_entities=config["blocked_entities"],
            threshold=config["threshold"],
        )

    if stored_guard.type == schemas.ValidationType.TOPIC:
        return guards.Topic(
            allowed_topics=config.get("allowed_topics"),
            restricted_topics=config.get("restricted_topics"),
            threshold=config["threshold"],
        )

    if stored_guard.type == schemas.ValidationType.PROMPT_INJECTION:
        return guards.PromptInjection(threshold=config["threshold"])

    if stored_guard.type == schemas.ValidationType.CUSTOM_CLASSIFIER:
        return guards.CustomGuardrail(
            model_name=config["model_name"],
            threshold=config["threshold"],
        )

    if stored_guard.type == schemas.ValidationType.LLM_JUDGE:
        # 一个策略最多持有一个 judge，因此其结果以策略名称进行标记。
        return guards.LLMJudge(
            name=policy_name,
            instructions=config["instructions"],
            model=config["model"],
        )

    # 不跳过：护栏采用失败关闭策略，无法运行的检查绝不能悄然
    # 削弱策略所承诺的防护。
    raise exceptions.GuardrailPolicyError(
        f"护栏策略 '{policy_name}' 包含类型为 '{stored_guard.type}' 的 guard，"
        "此版本的 Opik SDK 无法运行。请升级 SDK。"
    )
