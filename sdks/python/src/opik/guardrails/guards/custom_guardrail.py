from typing import List, Dict, Any
from . import guard
from .. import schemas
import functools


class CustomGuardrail(guard.Guard):
    """
    由你自己在带标签示例上训练的自定义二分类器支持的 guard。

    该模型由 guardrails 后端提供服务，后端会按名称从其本地 adapters 目录加载模型，
    因此必须运行 guardrails 服务器（且该服务器能访问到训练好的模型）。
    """

    def __init__(
        self,
        model_name: str,
        threshold: float = 0.5,
    ) -> None:
        """
        初始化一个自定义 guardrail。

        Args:
            model_name: 要运行的已训练模型的名称，即训练返回的名称。
            threshold: 分数阈值，超过该阈值时 guard 判定失败（默认：0.5）。
                调低该值会更严格，调高则更宽松。
        """
        self._model_name = model_name
        self._threshold = threshold

    @functools.lru_cache()
    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        获取自定义 guardrail 的校验配置。

        Returns:
            包含自定义 guardrail 校验配置的列表
        """
        return [
            {
                "type": schemas.ValidationType.CUSTOM_CLASSIFIER,
                "config": {
                    "model_name": self._model_name,
                    "threshold": self._threshold,
                },
            }
        ]
