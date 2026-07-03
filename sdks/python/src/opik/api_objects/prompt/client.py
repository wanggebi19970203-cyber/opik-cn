from typing import Any, Dict, List, Literal, Optional, Tuple, Type, TypeVar
import json
import dataclasses
import logging

import opik.exceptions
from opik.rest_api import client as rest_client, PromptVersionUpdate
from opik.rest_api import core as rest_api_core
from opik.rest_api.types import prompt_version_detail
from opik.api_objects import opik_query_language, rest_helpers
from . import types as prompt_types
from . import prompt_cache
from . import mask_context as prompt_mask_context_module
from .base_prompt import BasePrompt

LOGGER = logging.getLogger(__name__)

_PromptT = TypeVar("_PromptT", bound=BasePrompt)


@dataclasses.dataclass
class PromptSearchResult:
    """搜索提示词的结果，包含名称、模板结构和最新版本详情。"""

    name: str
    template_structure: str
    prompt_version_detail: prompt_version_detail.PromptVersionDetail
    project_name: Optional[str]


def _validate_prompt_pin(
    commit: Optional[str],
    version: Optional[str],
    environment: Optional[str],
) -> None:
    """在调用 REST API 之前拒绝互斥的提示词选择器。

    底层 ``retrieve_prompt_version`` 端点接受 ``commit``、
    ``version_number`` 和 ``environment``，但最多只能设置一个；
    否则后端会静默选择其中一个。``commit`` 也已弃用，
    推荐使用 ``version``。在此处集中检查可以保持
    ``PromptClient.get_prompt`` 和 ``PromptClient.get_prompt_with_cache`` 的同步。
    """
    if commit is not None and version is not None:
        raise ValueError(
            "Provide either `commit` or `version`, not both. "
            "Prefer `version` — `commit` is deprecated."
        )
    if commit and environment:
        raise ValueError(
            "'commit' and 'environment' are mutually exclusive; pass at most one."
        )
    if version and environment:
        raise ValueError(
            "'version' and 'environment' are mutually exclusive; pass at most one."
        )


class PromptClient:
    def __init__(self, client: rest_client.OpikApi):
        self._rest_client = client

    def create_prompt(
        self,
        name: str,
        prompt: str,
        metadata: Optional[Dict[str, Any]],
        type: prompt_types.PromptType = prompt_types.PromptType.MUSTACHE,
        template_structure: str = "text",
        id: Optional[str] = None,
        description: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
        project_name: Optional[str] = None,
    ) -> prompt_version_detail.PromptVersionDetail:
        """
        为给定的提示词名称和模板创建提示词详情。

        Args:
            name: 提示词的名称。
            prompt: 提示词的模板内容。
            metadata: 可选的提示词元数据。
            type: 模板类型（MUSTACHE 或 JINJA2）。
            template_structure: "text"（默认）或 "chat"。
            id: 可选的提示词唯一标识符（UUID）。
            description: 可选的提示词描述（最多 255 个字符）。
            change_description: 可选的此版本更改描述。
            tags: 可选的要与提示词关联的标签列表。
            project_name: 可选的要与提示词关联的项目名称。如果未提供，将使用默认项目。

        Returns:
            为提供的提示词名称和模板创建的 Prompt 对象。

        Raises:
            PromptTemplateStructureMismatch: 如果同名提示词已存在但具有不同的
                template_structure（例如，当聊天提示词存在时尝试创建文本提示词，反之亦然）。
                模板结构在提示词创建后不可变。
        """
        prompt_version = self._get_latest_version(name, project_name=project_name)

        # 对于聊天提示词，比较解析后的 JSON 以避免格式差异
        templates_equal = False

        if prompt_version is not None:
            if prompt_version.template_structure != template_structure:
                raise opik.exceptions.PromptTemplateStructureMismatch(
                    prompt_name=name,
                    existing_structure=prompt_version.template_structure,
                    attempted_structure=template_structure,
                )

            if template_structure == "chat":
                try:
                    existing_messages = json.loads(prompt_version.template)
                    new_messages = json.loads(prompt)
                    templates_equal = existing_messages == new_messages
                except (json.JSONDecodeError, TypeError):
                    templates_equal = prompt_version.template == prompt
            else:
                templates_equal = prompt_version.template == prompt

        # 在以下情况下创建新版本：
        # - 尚不存在版本（新提示词）
        # - 模板内容已更改
        # - 元数据已更改
        # - 类型已更改
        # 注意：template_structure 是不可变的，仅在它是第一个提示词版本时由后端使用。
        if (
            prompt_version is None
            or not templates_equal
            or prompt_version.metadata != metadata
            or prompt_version.type != type.value
        ):
            prompt_version = self._create_new_version(
                name=name,
                prompt=prompt,
                type=type,
                metadata=metadata,
                project_name=project_name,
                is_new=prompt_version is None,
                template_structure=template_structure,
                id=id,
                description=description,
                change_description=change_description,
                tags=tags,
            )

        return prompt_version

    def _create_new_version(
        self,
        name: str,
        prompt: str,
        type: prompt_version_detail.PromptVersionDetailType,
        metadata: Optional[Dict[str, Any]],
        project_name: Optional[str],
        is_new: bool = True,
        template_structure: str = "text",
        id: Optional[str] = None,
        description: Optional[str] = None,
        change_description: Optional[str] = None,
        tags: Optional[List[str]] = None,
    ) -> prompt_version_detail.PromptVersionDetail:
        # 如果是新提示词且提供了容器级参数，使用 create_prompt 端点
        # 该端点在一次调用中同时创建容器和第一个版本
        if is_new and (id is not None or description is not None or tags is not None):
            self._rest_client.prompts.create_prompt(
                name=name,
                id=id,
                description=description,
                template=prompt,
                metadata=metadata,
                change_description=change_description,
                type=type,
                template_structure=template_structure,
                tags=tags,
                project_name=project_name,
            )
            # 创建后，检索已创建的版本
            new_prompt_version_detail = (
                self._rest_client.prompts.retrieve_prompt_version(
                    name=name, project_name=project_name
                )
            )
            # retrieve_prompt_version 可能不返回标签，因此我们需要手动设置它们
            # 从我们刚刚传递给 create_prompt 的标签中
            if tags is not None and new_prompt_version_detail.tags is None:
                # Pydantic 对象是冻结的，因此我们复制以注入标签；
                # model_copy 自动保留所有其他字段。
                new_prompt_version_detail = new_prompt_version_detail.model_copy(
                    update={"tags": tags}
                )
        else:
            # 对于现有提示词或没有容器级参数时，使用 create_prompt_version
            new_prompt_version_detail_data = prompt_version_detail.PromptVersionDetail(
                template=prompt,
                metadata=metadata,
                type=type,
            )
            new_prompt_version_detail = self._rest_client.prompts.create_prompt_version(
                name=name,
                version=new_prompt_version_detail_data,
                template_structure=template_structure,
                project_name=project_name,
            )
        return new_prompt_version_detail

    def _get_latest_version(
        self, name: str, project_name: Optional[str]
    ) -> Optional[prompt_version_detail.PromptVersionDetail]:
        return self.get_prompt(name=name, commit=None, project_name=project_name)

    def get_prompt(
        self,
        name: str,
        commit: Optional[str] = None,
        raise_if_not_template_structure: Optional[str] = None,
        project_name: Optional[str] = None,
        version: Optional[str] = None,
        environment: Optional[str] = None,
    ) -> Optional[prompt_version_detail.PromptVersionDetail]:
        """
        检索给定提示词名称的提示词详情，可选择固定到特定的 ``version``。

        Args:
            name: 提示词的名称。
            version: 可选的顺序版本选择器，格式为 ``"v<N>"``（例如 ``"v3"``）。
                如果未提供，将检索最新版本。
            commit: 已弃用，推荐使用 ``version``。与 ``version`` 互斥。
            raise_if_not_template_structure: 可选的模板结构验证。如果提供且不匹配，将引发 PromptTemplateStructureMismatch。
            project_name: 提示词所属项目的名称。如果未提供，将使用默认项目。
            environment: 可选的环境名称。当提供时，返回给定环境当前指向的版本。
                与 ``commit`` 互斥。

        Returns:
            Prompt: 指定提示词的详情。
        """
        _validate_prompt_pin(commit, version, environment)
        try:
            prompt_version = self._rest_client.prompts.retrieve_prompt_version(
                name=name,
                commit=commit,
                version_number=version,
                environment=environment,
                project_name=project_name,
            )

            should_skip_validation = (
                prompt_version.template_structure is None
                and raise_if_not_template_structure == "text"
            )
            if should_skip_validation:
                return prompt_version

            # 如果请求且未跳过，进行客户端 template_structure 验证
            if (
                raise_if_not_template_structure is not None
                and prompt_version.template_structure != raise_if_not_template_structure
            ):
                raise opik.exceptions.PromptTemplateStructureMismatch(
                    prompt_name=name,
                    existing_structure=prompt_version.template_structure,
                    attempted_structure=raise_if_not_template_structure,
                )

            return prompt_version
        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e
            # 400, 404 - 未找到
        return None

    def get_prompt_with_cache(
        self,
        name: str,
        commit: Optional[str],
        project_name: Optional[str],
        template_structure: str,
        prompt_cls: Type[_PromptT],
        no_cache: bool = False,
        version: Optional[str] = None,
        environment: Optional[str] = None,
    ) -> Optional[_PromptT]:
        _validate_prompt_pin(commit, version, environment)

        def _fetch(mask_id: Optional[str] = None) -> Optional[_PromptT]:
            if mask_id is not None:
                prompt_version = self._rest_client.prompts.get_prompt_version_by_id(
                    version_id=mask_id,
                )
            else:
                prompt_version = self.get_prompt(
                    name=name,
                    commit=commit,
                    raise_if_not_template_structure=template_structure,
                    project_name=project_name,
                    version=version,
                    environment=environment,
                )
            if prompt_version is None:
                return None
            return prompt_cls.from_fern_prompt_version(
                name, prompt_version, project_name=project_name
            )

        def _cached_fetch(
            mask_id: Optional[str] = None,
        ) -> Optional[_PromptT]:
            if no_cache:
                return _fetch(mask_id=mask_id)
            return prompt_cache.get_or_fetch(
                name=name,
                commit=commit,
                project_name=project_name,
                template_structure=template_structure,
                fetch_fn=lambda: _fetch(mask_id=mask_id),
                mask_id=mask_id,
                version=version,
                environment=environment,
            )

        unmasked = _cached_fetch()
        result = unmasked

        prompt_id = (
            unmasked.__internal_api__prompt_id__ if unmasked is not None else None
        )
        active_mask_id = (
            prompt_mask_context_module.get_mask_for_prompt(prompt_id)
            if prompt_id is not None
            else None
        )
        if active_mask_id is not None:
            result = _cached_fetch(mask_id=active_mask_id)

        if result is not None:
            from opik import opik_context

            opik_context.attach_prompt_to_current_trace(result)
            opik_context.attach_prompt_to_current_span(result)
        return result

    # TODO: 需要在后端添加对提示词名称的支持，这样我们就不需要检索提示词 id
    def get_all_prompt_versions(
        self,
        name: str,
        search: Optional[str] = None,
        filter_string: Optional[str] = None,
        project_name: Optional[str] = None,
    ) -> List[prompt_version_detail.PromptVersionDetail]:
        """
        检索给定提示词名称的所有提示词详情。

        Args:
            name: 提示词的名称。
            search: 可选的搜索文本，用于在模板或更改描述字段中查找。
            project_name: 用于过滤提示词的项目名称。如果为 None，将返回所有提示词。
            filter_string: 使用 Opik 查询语言（OQL）缩小搜索范围的过滤字符串。
                格式为："<COLUMN> <OPERATOR> <VALUE> [AND <COLUMN> <OPERATOR> <VALUE>]*"

                支持的列包括：
                - `id`、`commit`、`template`、`change_description`、`created_by`：字符串字段，完全支持运算符
                - `metadata`：字典字段（使用点表示法，例如 "metadata.environment"）
                - `type`：枚举字段（仅支持 =, !=）
                - `tags`：列表字段（仅支持 "contains" 运算符）
                - `created_at`：日期时间字段（使用 ISO 8601 格式，例如 "2024-01-01T00:00:00Z"）

                示例：
                - `tags contains "production"` - 按标签过滤
                - `tags contains "v1" AND tags contains "production"` - 按多个标签过滤
                - `template contains "customer"` - 按模板内容过滤
                - `created_by = "user@example.com"` - 按创建者过滤
                - `created_at >= "2024-01-01T00:00:00Z"` - 按创建日期过滤
                - `metadata.environment = "prod"` - 按元数据字段过滤

        Returns:
            List[PromptVersionDetail]: 给定名称的提示词版本列表。

        Example:
            # 获取提示词的所有版本
            versions = prompt_client.get_all_prompt_versions(name="my-prompt")

            # 按标签过滤（包含 "production" 标签的版本）
            versions = prompt_client.get_all_prompt_versions(
                name="my-prompt",
                filter_string='tags contains "production"')

            # 在模板或更改描述字段中搜索特定文本
            versions = prompt_client.get_all_prompt_versions(
                name="my-prompt",
                search="customer")

            # 组合搜索和过滤
            versions = prompt_client.get_all_prompt_versions(
                name="my-prompt",
                search="customer",
                filter_string='tags contains "production"')
        """
        try:
            project_id = rest_helpers.resolve_project_id_by_name_optional(
                self._rest_client, project_name=project_name
            )
            prompts_matching_name_string = self._rest_client.prompts.get_prompts(
                name=name, project_id=project_id
            ).content
            if (
                prompts_matching_name_string is None
                or len(prompts_matching_name_string) == 0
            ):
                raise ValueError("No prompts found for name: " + name)

            filtered_prompt_list = [
                x.id for x in prompts_matching_name_string if name == x.name
            ]
            if len(filtered_prompt_list) == 0:
                raise ValueError("No prompts found for name: " + name)

            filters: Optional[str] = None
            if filter_string:
                oql = opik_query_language.OpikQueryLanguage.for_prompt_versions(
                    filter_string
                )
                filters = oql.parsed_filters

            prompt_id = filtered_prompt_list[0]
            return self._get_prompt_versions_by_id_paginated(
                prompt_id, search=search, filters=filters
            )

        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e

        return []

    def _get_prompt_versions_by_id_paginated(
        self,
        prompt_id: str,
        search: Optional[str] = None,
        filters: Optional[str] = None,
    ) -> List[prompt_version_detail.PromptVersionDetail]:
        page = 1
        size = 100
        prompts: List[prompt_version_detail.PromptVersionDetail] = []
        while True:
            prompt_versions_page = self._rest_client.prompts.get_prompt_versions(
                id=prompt_id,
                page=page,
                size=size,
                search=search,
                filters=filters,
            ).content

            versions = prompt_versions_page or []
            prompts.extend(
                [
                    # 转换为 PromptVersionDetail 以与其他方法保持一致。
                    # TODO: 后端应实现非前端端点，返回 PromptVersionDetail 对象
                    prompt_version_detail.PromptVersionDetail(
                        id=version.id,
                        prompt_id=version.prompt_id,
                        template=version.template,
                        type=version.type,
                        version_type=version.version_type,
                        environments=version.environments,
                        metadata=version.metadata,
                        commit=version.commit,
                        version_number=version.version_number,
                        change_description=version.change_description,
                        template_structure=version.template_structure,
                        created_at=version.created_at,
                        created_by=version.created_by,
                        tags=version.tags,
                    )
                    for version in versions
                ]
            )

            if len(versions) < size:
                break
            page += 1

        return prompts

    def search_prompts(
        self,
        *,
        name: Optional[str] = None,
        parsed_filters: Optional[List[Dict[str, Any]]] = None,
        project_name: Optional[str] = None,
    ) -> List[PromptSearchResult]:
        """
        按可选的名称子字符串和过滤器搜索提示词容器，
        然后返回每个匹配提示词容器的最新版本详情。

        Args:
            name: 可选的提示词名称子字符串，用于搜索。
            parsed_filters: 已解析的过滤器（OQL）列表，将被序列化为字符串发送给后端。
            project_name: 要搜索的项目名称。如果未提供，将使用默认项目。

        Returns:
            List[PromptSearchResult]: 每个结果包含 name、template_structure 和 prompt_version_detail。
        """
        try:
            filters_str = (
                json.dumps(parsed_filters) if parsed_filters is not None else None
            )

            # 分页遍历所有提示词容器并收集：
            # (name, template_structure, tags)
            page = 1
            size = 1000
            prompt_info: List[Tuple[str, str, Optional[List[str]]]] = []
            project_id = rest_helpers.resolve_project_id_by_name_optional(
                self._rest_client, project_name=project_name
            )
            while True:
                prompts_page = self._rest_client.prompts.get_prompts(
                    page=page,
                    size=size,
                    name=name,
                    filters=filters_str,
                    project_id=project_id,
                )
                content = prompts_page.content or []
                if len(content) == 0:
                    break
                prompt_info.extend(
                    [(p.name, p.template_structure or "text", p.tags) for p in content]
                )
                if len(content) < size:
                    break
                page += 1

            if len(prompt_info) == 0:
                return []

            # 检索每个容器名称的最新版本
            results: List[PromptSearchResult] = []
            for prompt_name, template_structure, tags in prompt_info:
                try:
                    latest_version = self._rest_client.prompts.retrieve_prompt_version(
                        name=prompt_name, project_name=project_name
                    )
                    # retrieve_prompt_version 可能不返回标签，因此我们需要从 get_prompts 响应中设置它们
                    if tags is not None and latest_version.tags is None:
                        # Pydantic 对象是冻结的，因此我们复制以注入标签；
                        # model_copy 自动保留所有其他字段。
                        latest_version = latest_version.model_copy(
                            update={"tags": tags}
                        )
                    results.append(
                        PromptSearchResult(
                            name=prompt_name,
                            template_structure=template_structure,
                            prompt_version_detail=latest_version,
                            project_name=project_name,
                        )
                    )
                except rest_api_core.ApiError as e:
                    # 跳过无法检索的提示词（例如，在搜索和检索之间被删除的）
                    if e.status_code == 404:
                        continue
                    raise e

            return results

        except rest_api_core.ApiError as e:
            if e.status_code != 404:
                raise e
            return []

    def __internal_api__create_mask(
        self,
        name: str,
        prompt: str,
        type: prompt_types.PromptType = prompt_types.PromptType.MUSTACHE,
        metadata: Optional[Dict[str, Any]] = None,
        template_structure: Literal["text", "chat"] = "text",
        project_name: Optional[str] = None,
        change_description: Optional[str] = None,
    ) -> prompt_version_detail.PromptVersionDetail:
        """创建一个隐藏的遮罩提示词版本，在遮罩上下文激活时覆盖 get_prompt 的输出。

        内部 API — 不面向最终用户使用。遮罩是一个隐藏的
        提示词版本（version_type="mask"），不会出现在提示词历史或版本列表中。
        当遮罩上下文激活时（通过 ``prompt_mask_context``），
        ``get_prompt`` 返回遮罩的模板而不是最新的可见版本。

        Args:
            name: 要附加遮罩的提示词名称。提示词必须已存在。
            prompt: 遮罩版本的模板内容。
            type: 模板类型（MUSTACHE 或 JINJA2）。
            metadata: 可选的元数据字典。
            template_structure: "text" 或 "chat"。
            project_name: 可选的项目范围。如果未提供，将使用默认项目。
            change_description: 可选的创建此遮罩的原因描述。

        Returns:
            创建的 PromptVersionDetail，version_type="mask"。
        """
        new_version = prompt_version_detail.PromptVersionDetail(
            template=prompt,
            metadata=metadata,
            type=type,
            version_type="mask",
            change_description=change_description,
        )
        return self._rest_client.prompts.create_prompt_version(
            name=name,
            version=new_version,
            template_structure=template_structure,
            project_name=project_name,
        )

    def batch_update_prompt_version_tags(
        self,
        version_ids: List[str],
        tags: Optional[List[str]] = None,
        merge: Optional[bool] = None,
    ) -> None:
        """
        在单个批量操作中更新一个或多个提示词版本的标签。

        Args:
            version_ids: 要更新的提示词版本 ID 列表。
            tags: 要设置或合并的标签。语义：
                - None：不更改标签（保留现有标签）。
                - []：清除所有标签（当 merge 为 False 或 None 时）。
                - ['tag1', 'tag2']：设置或合并标签（基于 merge 参数）。
            merge: 控制标签更新行为。语义：
                - None：使用后端默认行为（替换模式）。
                - False：替换所有现有标签（替换模式）。
                - True：将新标签与现有标签合并（并集）。

        Example:
            # 替换多个版本上的标签（默认行为）
            prompts_client.batch_update_prompt_version_tags(
                version_ids=["version-id-1", "version-id-2"],
                tags=["production", "v2"]
            )

            # 将新标签与现有标签合并
            prompts_client.batch_update_prompt_version_tags(
                version_ids=["version-id-1"],
                tags=["hotfix"],
                merge=True
            )

            # 清除所有标签
            prompts_client.batch_update_prompt_version_tags(
                version_ids=["version-id-1"],
                tags=[]
            )
        """
        update = PromptVersionUpdate(tags=tags)
        self._rest_client.prompts.update_prompt_versions(
            ids=version_ids, update=update, merge_tags=merge
        )
