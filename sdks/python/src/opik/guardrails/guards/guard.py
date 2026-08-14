import abc
from typing import List, Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from .. import schemas
    from opik.api_objects import opik_client


class Guard(abc.ABC):
    # 此 guard 是在 SDK 本地执行（True），还是在 guardrails 后端远程执行（False）。
    local: bool = False

    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        获取此 guard 的校验配置，用于发送到 guardrails 后端。
        本地 guard 不在后端运行，因此返回空列表。

        Returns:
            要发送到 API 的校验配置列表
        """
        return []

    def validate_local(
        self, text: str, client: "opik_client.Opik"
    ) -> List["schemas.ValidationResult"]:
        """
        在 SDK 本地运行此 guard。仅对 ``local = True`` 的 guard 调用。

        Args:
            text: 要校验的文本
            client: Opik 客户端，用于访问 Opik 后端端点

        Returns:
            此 guard 产生的校验结果列表
        """
        raise NotImplementedError
