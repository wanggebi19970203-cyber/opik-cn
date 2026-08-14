import logging
from typing import Any, Dict, Optional

LOGGER = logging.getLogger(__name__)


class LiteLLMResponseCostExtractor:
    """
    提取 LiteLLM 代理报告的成本。

    代理会在 ``x-litellm-response-cost`` 响应头中返回请求成本。LangChain 仅在
    创建 chat 模型时设置了 ``include_response_headers=True`` 才会暴露响应头，
    此时它们会落在 ``response_metadata["headers"]`` 之下。我们还会检查
    ``response_metadata`` 的顶层，以便在那些会扁平化响应头的 LangChain
    版本/配置下保持健壮性。
    """

    RESPONSE_COST_KEY = "x-litellm-response-cost"

    def try_get_response_cost(
        self, response_metadata: Dict[str, Any]
    ) -> Optional[float]:
        raw_cost = response_metadata.get(self.RESPONSE_COST_KEY)

        if raw_cost is None:
            headers = response_metadata.get("headers")
            if isinstance(headers, dict):
                raw_cost = headers.get(self.RESPONSE_COST_KEY)

        if raw_cost is None:
            return None

        try:
            return float(raw_cost)
        except (TypeError, ValueError):
            LOGGER.debug("无法从以下值解析 LiteLLM 响应成本：%r", raw_cost)
            return None
