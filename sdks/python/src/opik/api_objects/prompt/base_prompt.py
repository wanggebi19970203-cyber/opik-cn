"""
提示词的基类。

定义了字符串和聊天提示词变体都必须实现的抽象接口。
"""

from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

from typing_extensions import Self

from . import types as prompt_types
from opik.rest_api import types as rest_api_types


class BasePrompt(ABC):
    """
    提示词（字符串和聊天）的抽象基类。

    所有提示词实现必须提供与后端 API 交互的通用属性和方法。
    """

    @property
    @abstractmethod
    def name(self) -> str:
        """提示词的名称。"""
        pass

    @property
    @abstractmethod
    def commit(self) -> Optional[str]:
        """提示词版本的旧式提交哈希。

        已弃用 — 请使用 :attr:`version`（例如 ``"v3"``）代替。``commit``
        不再在 Opik UI 中显示，仅保留用于与旧版 SDK 调用者的向后兼容性。
        """
        pass

    @property
    @abstractmethod
    def version(self) -> Optional[str]:
        """提示词版本的顺序版本选择器（例如 ``"v3"``）。"""
        pass

    @property
    @abstractmethod
    def version_id(self) -> Optional[str]:
        """提示词版本的唯一标识符。"""
        pass

    @property
    @abstractmethod
    def metadata(self) -> Optional[Dict[str, Any]]:
        """与提示词关联的元数据字典。"""
        pass

    @property
    @abstractmethod
    def type(self) -> prompt_types.PromptType:
        """提示词类型（MUSTACHE 或 JINJA2）。"""
        pass

    @property
    @abstractmethod
    def id(self) -> Optional[str]:
        """提示词的唯一标识符（UUID）。"""
        pass

    @property
    @abstractmethod
    def description(self) -> Optional[str]:
        """提示词的描述。"""
        pass

    @property
    @abstractmethod
    def change_description(self) -> Optional[str]:
        """此版本更改的描述。"""
        pass

    @property
    @abstractmethod
    def tags(self) -> Optional[List[str]]:
        """与提示词关联的标签列表。"""
        pass

    @property
    @abstractmethod
    def project_name(self) -> Optional[str]:
        """此提示词所属项目的名称。"""
        pass

    @property
    @abstractmethod
    def environments(self) -> Optional[List[str]]:
        """当前拥有此提示词版本的环境，如果未被拥有则为 ``None``。"""
        pass

    # 用于后端同步的内部 API 字段
    __internal_api__prompt_id__: Optional[str]
    __internal_api__version_id__: Optional[str]

    @abstractmethod
    def format(self, *args: Any, **kwargs: Any) -> Any:
        """
        使用提供的变量格式化提示词。

        Returns:
            格式化后的输出。类型取决于实现：
            - Prompt 返回 str
            - ChatPrompt 返回 List[Dict[str, MessageContent]]
        """
        pass

    @classmethod
    def from_fern_prompt_version(
        cls,
        name: str,
        prompt_version: rest_api_types.PromptVersionDetail,
        project_name: Optional[str] = None,
    ) -> Self:
        raise NotImplementedError(
            f"{cls.__name__} does not implement from_fern_prompt_version"
        )

    @abstractmethod
    def __internal_api__to_info_dict__(self) -> Dict[str, Any]:
        """
        将提示词转换为用于序列化的信息字典。

        Returns:
            包含提示词元数据和版本信息的字典。
        """
        pass
