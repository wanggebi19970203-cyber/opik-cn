import copy
import json
import logging
from typing import Any, Dict, Optional, Union, List

import httpx
from typing_extensions import override
from opik.rest_api import core as rest_api_core
from opik.rest_api import types as rest_api_types
from . import prompt_template
from .. import types as prompt_types
from .. import client as prompt_client
from .. import base_prompt

LOGGER = logging.getLogger(__name__)


class Prompt(base_prompt.BasePrompt):
    """
    Prompt 类表示一个包含名称、提示文本/模板和提交哈希的提示。

    Prompt class represents a prompt with a name, prompt text/template and commit hash.
    """

    def __init__(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]] = None,
        type: prompt_types.PromptType = prompt_types.PromptType.MUSTACHE,
        validate_placeholders: bool = True,
        id: Optional[str] = None,
        description: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
        project_name: Optional[str] = None,
    ) -> None:
        """
        使用给定参数初始化类的新实例。
        使用 opik 客户端创建新的文本提示，并根据创建的提示设置实例属性的初始状态。

        Initializes a new instance of the class with the given parameters.
        Creates a new text prompt using the opik client and sets the initial state of the instance attributes based on the created prompt.

        Args:
            name: 提示的名称。
            prompt: 提示的模板。
            metadata: 提示的可选元数据。
            type: 模板类型（MUSTACHE 或 JINJA2）。
            validate_placeholders: 是否验证模板占位符。
            id: 提示的可选唯一标识符（UUID）。
            description: 提示的可选描述（最多 255 个字符）。
            change_description: 此版本更改的可选描述。
            tags: 与提示关联的可选标签列表。
            project_name: 提示所属项目的可选名称。

        Raises:
            PromptTemplateStructureMismatch: 如果同名的聊天提示已存在（模板结构不可变）。
        """

        LOGGER.warning(
            "opik.Prompt() is deprecated. Use client.create_prompt() to create or "
            "client.get_prompt() to retrieve text prompts instead."
        )

        self._template = prompt_template.PromptTemplate(
            template=prompt, type=type, validate_placeholders=validate_placeholders
        )
        self._name = name
        self._metadata = metadata
        self._type = type
        self._id = id
        self._description = description
        self._change_description = change_description
        self._tags = copy.copy(tags) if tags else []
        self._project_name = project_name
        self._environments: Optional[List[str]] = None

        self._commit: Optional[str] = None
        self._version: Optional[str] = None
        self.__internal_api__prompt_id__: Optional[str] = None
        self.__internal_api__version_id__: Optional[str] = None
        self._synced: bool = False

        self.sync_with_backend()

    @property
    def synced(self) -> bool:
        """提示是否已成功与后端同步。"""
        return self._synced

    def sync_with_backend(self) -> bool:
        """将提示与后端同步。

        在 Opik 服务器上创建或更新提示。如果同步失败，
        会记录警告日志，提示将继续在本地工作。

        Synchronize the prompt with the backend.

        Creates or updates the prompt on the Opik server. If the sync fails,
        a warning is logged and the prompt continues to work locally.

        Returns:
            如果同步成功返回 True，否则返回 False。
        """
        try:
            from opik.api_objects import opik_client

            opik_client_ = opik_client.get_global_client()
            prompt_client_ = prompt_client.PromptClient(opik_client_.rest_client)
            prompt_version = prompt_client_.create_prompt(
                name=self._name,
                prompt=self._template.text,
                metadata=self._metadata,
                type=self._type,
                id=self._id,
                description=self._description,
                change_description=self._change_description,
                tags=self._tags,
                project_name=self._project_name,
            )

            self._commit = prompt_version.commit
            self._version = prompt_version.version_number
            self.__internal_api__prompt_id__ = prompt_version.prompt_id
            self.__internal_api__version_id__ = prompt_version.id
            # 从后端响应更新字段以确保一致性
            self._id = prompt_version.id
            self._change_description = prompt_version.change_description
            self._tags = prompt_version.tags
            self._environments = prompt_version.environments
            self._synced = True
            return True
        except (rest_api_core.ApiError, httpx.ConnectError, httpx.TimeoutException):
            LOGGER.warning(
                "Failed to sync prompt '%s' with the backend. "
                "The prompt will work locally but is not persisted on the server. "
                "You can retry by calling .sync_with_backend().",
                self._name,
                exc_info=True,
            )
            self._synced = False
            return False

    @property
    @override
    def name(self) -> str:
        """提示的名称。"""
        return self._name

    @property
    def prompt(self) -> str:
        """提示的最新模板。"""
        return str(self._template)

    @property
    @override
    def commit(self) -> Optional[str]:
        """提示版本的旧版提交哈希。

        已弃用 — 请改用 :attr:`version`（例如 ``"v3"``）。``commit``
        不再在 Opik UI 中显示，仅为向后兼容旧版 SDK 调用者而保留。

        Legacy commit hash of the prompt version.

        DEPRECATED — use :attr:`version` (e.g. ``"v3"``) instead. ``commit``
        is no longer surfaced in the Opik UI and is kept only for backwards
        compatibility with older SDK callers.
        """
        return self._commit

    @property
    @override
    def version(self) -> Optional[str]:
        """提示的顺序版本选择器（例如 ``"v3"``）。"""
        return self._version

    @property
    @override
    def version_id(self) -> Optional[str]:
        """提示版本的唯一标识符。"""
        return self.__internal_api__version_id__

    @property
    @override
    def metadata(self) -> Optional[Dict[str, Any]]:
        """与提示关联的元数据字典。"""
        return copy.deepcopy(self._metadata)

    @property
    @override
    def type(self) -> prompt_types.PromptType:
        """提示的类型。"""
        return self._type

    @property
    @override
    def id(self) -> Optional[str]:
        """提示的唯一标识符（UUID）。"""
        return self._id

    @property
    @override
    def description(self) -> Optional[str]:
        """提示的描述。"""
        return self._description

    @property
    @override
    def change_description(self) -> Optional[str]:
        """此版本更改的描述。"""
        return self._change_description

    @property
    @override
    def tags(self) -> Optional[List[str]]:
        """与提示关联的标签列表。"""
        return copy.copy(self._tags) if self._tags else []

    @property
    def project_name(self) -> Optional[str]:
        """提示所属项目的名称。"""
        return self._project_name

    @property
    @override
    def environments(self) -> Optional[List[str]]:
        """当前拥有此提示版本的环境，如果未被拥有则返回 ``None``。"""
        return copy.copy(self._environments) if self._environments is not None else None

    @override
    def format(self, **kwargs: Any) -> Union[str, List[Dict[str, Any]]]:
        """
        使用提供的关键字参数替换模板中的占位符。

        Replaces placeholders in the template with provided keyword arguments.

        Args:
            **kwargs: 任意关键字参数，其中键表示模板中的占位符，
                      值是要替换占位符的值。

        Returns:
            所有占位符被 kwargs 中对应值替换后的字符串。
        """
        is_playground_chat_prompt = (
            self._metadata is not None
            and self._metadata.get("created_from") == "opik_ui"
            and self._metadata.get("type") == "messages_json"
        )
        formatted_string = self._template.format(**kwargs)

        if is_playground_chat_prompt:
            try:
                return json.loads(formatted_string)
            except json.JSONDecodeError:
                LOGGER.error(
                    f"Failed to parse JSON string: {formatted_string}. Make sure chat prompt is valid JSON. Returning the raw string."
                )
                return formatted_string

        return formatted_string

    @override
    def __internal_api__to_info_dict__(self) -> Dict[str, Any]:
        """
        将提示转换为用于序列化的信息字典。

        Convert the prompt to an info dictionary for serialization.

        Returns:
            包含提示元数据和版本信息的字典。
        """
        info_dict: Dict[str, Any] = {
            "name": self.name,
            "template_structure": "text",
            "version": {
                "template": self.prompt,
            },
        }

        if self.__internal_api__prompt_id__ is not None:
            info_dict["id"] = self.__internal_api__prompt_id__

        if self.commit is not None:
            info_dict["version"]["commit"] = self.commit

        if self.version is not None:
            info_dict["version"]["version_number"] = self.version

        if self.__internal_api__version_id__ is not None:
            info_dict["version"]["id"] = self.__internal_api__version_id__

        if self._metadata is not None:
            info_dict["version"]["metadata"] = self._metadata

        return info_dict

    @classmethod
    def from_fern_prompt_version(
        cls,
        name: str,
        prompt_version: rest_api_types.PromptVersionDetail,
        project_name: Optional[str] = None,
    ) -> "Prompt":
        # 不调用 __init__ 以避免 API 调用，使用 __new__ 创建新实例
        prompt = cls.__new__(cls)

        prompt.__internal_api__version_id__ = prompt_version.id
        prompt.__internal_api__prompt_id__ = prompt_version.prompt_id

        prompt._name = name
        prompt._template = prompt_template.PromptTemplate(
            template=prompt_version.template,
            type=prompt_types.PromptType(prompt_version.type)
            or prompt_types.PromptType.MUSTACHE,
        )
        prompt._commit = prompt_version.commit
        prompt._version = prompt_version.version_number
        prompt._metadata = prompt_version.metadata
        prompt._type = prompt_version.type
        prompt._id = prompt_version.id
        prompt._description = (
            None  # 描述存储在提示级别，而非版本级别
        )
        prompt._change_description = prompt_version.change_description
        prompt._tags = copy.copy(prompt_version.tags) if prompt_version.tags else []
        prompt._project_name = project_name
        prompt._environments = prompt_version.environments
        prompt._synced = True
        return prompt
