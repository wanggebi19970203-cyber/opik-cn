import logging
from typing import Any, Dict, List, Optional

from . import litellm_response_cost_extractor, response_cost_extractor_protocol

LOGGER = logging.getLogger(__name__)

_REGISTERED_RESPONSE_COST_EXTRACTORS: List[
    response_cost_extractor_protocol.ResponseCostExtractorProtocol
] = [
    litellm_response_cost_extractor.LiteLLMResponseCostExtractor(),
]


def try_extract_response_cost(run_dict: Dict[str, Any]) -> Optional[float]:
    """
    尝试从 LLM 运行中提取提供商报告的成本（以美元计）。

    LiteLLM 等代理会在响应头中返回请求成本，LangChain 会将该响应头以
    ``response_metadata`` 的形式暴露出来。这是真实的、由提供商报告的成本，
    因此其优先级高于 Opik 根据 token 用量估算的成本。

    可通过向 ``_REGISTERED_RESPONSE_COST_EXTRACTORS`` 添加提取器来支持新的
    代理/集成。
    """
    response_metadata = _try_get_response_metadata(run_dict)
    if not response_metadata:
        return None

    for extractor in _REGISTERED_RESPONSE_COST_EXTRACTORS:
        try:
            cost = extractor.try_get_response_cost(response_metadata)
        except Exception:
            LOGGER.debug(
                "使用 %s 提取响应成本失败。",
                type(extractor).__name__,
                exc_info=True,
            )
            continue

        if cost is not None:
            return cost

    return None


def _try_get_response_metadata(run_dict: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    try:
        message = run_dict["outputs"]["generations"][-1][-1]["message"]
        return message["kwargs"].get("response_metadata")
    except (KeyError, IndexError, TypeError):
        return None
