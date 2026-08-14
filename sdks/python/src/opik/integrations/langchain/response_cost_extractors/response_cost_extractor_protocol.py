from typing import Any, Dict, Optional, Protocol


class ResponseCostExtractorProtocol(Protocol):
    def try_get_response_cost(
        self, response_metadata: Dict[str, Any]
    ) -> Optional[float]:
        """
        返回在 LLM 运行的 ``response_metadata`` 中找到的提供商报告的成本
        （以美元计）；若该提取器无法识别，则返回 None。

        实现应只查找自身的指纹（例如特定的代理响应头），以便多个提取器可以
        共存。
        """
        ...
