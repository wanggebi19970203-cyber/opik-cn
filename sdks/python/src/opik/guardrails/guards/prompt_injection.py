from typing import List, Dict, Any
from . import guard
from .. import schemas
import functools


class PromptInjection(guard.Guard):
    """
    检测提示注入和越狱（jailbreak）尝试的 guard。

    在 guardrails 后端上运行微调分类器，这要求 guardrails
    服务器（已配置 Hugging Face token）正在运行。
    """

    def __init__(
        self,
        threshold: float = 0.5,
    ) -> None:
        """
        初始化一个提示注入 guard。

        Args:
            threshold: 注入概率阈值，超过该阈值时 guard 判定失败
                （默认：0.5）。调低该值会更严格，调高则更宽松。
        """
        self._threshold = threshold

    @functools.lru_cache()
    def get_validation_configs(self) -> List[Dict[str, Any]]:
        """
        获取提示注入检测的校验配置。

        Returns:
            包含提示注入校验配置的列表
        """
        return [
            {
                "type": schemas.ValidationType.PROMPT_INJECTION,
                "config": {
                    "threshold": self._threshold,
                },
            }
        ]
