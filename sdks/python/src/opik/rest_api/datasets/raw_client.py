# 此文件由 Fern 根据我们的 API 定义自动生成。

import contextlib
import typing
from json.decoder import JSONDecodeError

from ..core.api_error import ApiError
from ..core.client_wrapper import AsyncClientWrapper, SyncClientWrapper
from ..core.http_response import AsyncHttpResponse, HttpResponse
from ..core.jsonable_encoder import jsonable_encoder
from ..core.pydantic_utilities import parse_obj_as
from ..core.request_options import RequestOptions
from ..core.serialization import convert_and_respect_annotation_metadata
from ..errors.bad_request_error import BadRequestError
from ..errors.conflict_error import ConflictError
from ..errors.not_found_error import NotFoundError
from ..types.dataset_expansion_response import DatasetExpansionResponse
from ..types.dataset_export_job_public import DatasetExportJobPublic
from ..types.dataset_item_changes_public import DatasetItemChangesPublic
from ..types.dataset_item_filter import DatasetItemFilter
from ..types.dataset_item_page_compare import DatasetItemPageCompare
from ..types.dataset_item_page_public import DatasetItemPagePublic
from ..types.dataset_item_public import DatasetItemPublic
from ..types.dataset_item_update import DatasetItemUpdate
from ..types.dataset_item_write import DatasetItemWrite
from ..types.dataset_item_write_source import DatasetItemWriteSource
from ..types.dataset_page_public import DatasetPagePublic
from ..types.dataset_public import DatasetPublic
from ..types.dataset_version_diff import DatasetVersionDiff
from ..types.dataset_version_page_public import DatasetVersionPagePublic
from ..types.dataset_version_public import DatasetVersionPublic
from ..types.evaluator_item import EvaluatorItem
from ..types.evaluator_item_write import EvaluatorItemWrite
from ..types.execution_policy import ExecutionPolicy
from ..types.execution_policy_write import ExecutionPolicyWrite
from ..types.json_node import JsonNode
from ..types.page_columns import PageColumns
from ..types.project_stats_public import ProjectStatsPublic
from ..types.span_enrichment_options import SpanEnrichmentOptions
from ..types.trace_enrichment_options import TraceEnrichmentOptions
from .types.create_dataset_items_from_json_request_format import CreateDatasetItemsFromJsonRequestFormat
from .types.dataset_update_visibility import DatasetUpdateVisibility
from .types.dataset_write_type import DatasetWriteType
from .types.dataset_write_visibility import DatasetWriteVisibility

# 此值用作可选参数的默认值
OMIT = typing.cast(typing.Any, ...)


class RawDatasetsClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._client_wrapper = client_wrapper

    def apply_dataset_item_changes(
        self,
        id: str,
        *,
        request: DatasetItemChangesPublic,
        override: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DatasetVersionPublic]:
        """
        对数据集版本应用增量变更（新增、编辑、删除），并进行冲突检测。

        此端点：
        - 创建一个应用了变更的新版本
        - 校验 baseVersion 与最新版本是否匹配（除非 override=true）
        - 如果 baseVersion 已过期且未设置 override，则返回 409 Conflict

        使用 `override=true` 查询参数，即使在 baseVersion 已过期的情况下也强制创建版本。

        在请求体中同时设置 'copy_from_dataset_id' 和 'copy_from_version_id'，以从
        所提供的 (dataset, version) 对中读取结转行，而不是从目的数据集的
        先前版本中读取。当这些字段为 null 时，结转行从目的数据集的先前版本中读取。

        Parameters
        ----------
        id : str

        request : DatasetItemChangesPublic

        override : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetVersionPublic]
            版本创建成功
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items/changes",
            method="POST",
            params={
                "override": override,
            },
            json=request,
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPublic,
                    parse_obj_as(
                        type_=DatasetVersionPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 409:
                raise ConflictError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def batch_update_dataset_items(
        self,
        *,
        update: DatasetItemUpdate,
        ids: typing.Optional[typing.Sequence[str]] = OMIT,
        filters: typing.Optional[typing.Sequence[DatasetItemFilter]] = OMIT,
        dataset_id: typing.Optional[str] = OMIT,
        merge_tags: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        更新多个数据集条目

        Parameters
        ----------
        update : DatasetItemUpdate

        ids : typing.Optional[typing.Sequence[str]]
            要更新的数据集条目 ID 列表（最多 1000 个）。与 'filters' 互斥。

        filters : typing.Optional[typing.Sequence[DatasetItemFilter]]

        dataset_id : typing.Optional[str]
            数据集 ID。使用 'filters' 时必填，使用 'ids' 时可选。

        merge_tags : typing.Optional[bool]
            若为 true，则将标签与现有标签合并而不是替换。默认值：false。使用 'filters' 时，此值会自动设为 true。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items/batch",
            method="PATCH",
            json={
                "ids": ids,
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[DatasetItemFilter], direction="write"
                ),
                "dataset_id": dataset_id,
                "update": convert_and_respect_annotation_metadata(
                    object_=update, annotation=DatasetItemUpdate, direction="write"
                ),
                "merge_tags": merge_tags,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def find_datasets(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        with_experiments_only: typing.Optional[bool] = None,
        with_optimizations_only: typing.Optional[bool] = None,
        prompt_id: typing.Optional[str] = None,
        project_id: typing.Optional[str] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DatasetPagePublic]:
        """
        查找数据集

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        with_experiments_only : typing.Optional[bool]

        with_optimizations_only : typing.Optional[bool]

        prompt_id : typing.Optional[str]

        project_id : typing.Optional[str]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetPagePublic]
            数据集资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets",
            method="GET",
            params={
                "page": page,
                "size": size,
                "with_experiments_only": with_experiments_only,
                "with_optimizations_only": with_optimizations_only,
                "prompt_id": prompt_id,
                "project_id": project_id,
                "name": name,
                "sorting": sorting,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetPagePublic,
                    parse_obj_as(
                        type_=DatasetPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def create_dataset(
        self,
        *,
        name: str,
        id: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        type: typing.Optional[DatasetWriteType] = OMIT,
        visibility: typing.Optional[DatasetWriteVisibility] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        description: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        创建数据集

        Parameters
        ----------
        name : str

        id : typing.Optional[str]

        project_id : typing.Optional[str]
            项目 ID。当同时提供两者时，优先级高于 project_name。

        project_name : typing.Optional[str]
            对于项目作用域，指定 project_id 或 project_name 之一。如果提供了 project_name 且项目不存在，则会创建该项目。提供 project_id 时此字段被忽略。如果两者都未提供，则数据集在工作空间级别创建。

        type : typing.Optional[DatasetWriteType]

        visibility : typing.Optional[DatasetWriteVisibility]

        tags : typing.Optional[typing.Sequence[str]]

        description : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets",
            method="POST",
            json={
                "id": id,
                "name": name,
                "project_id": project_id,
                "project_name": project_name,
                "type": type,
                "visibility": visibility,
                "tags": tags,
                "description": description,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def create_or_update_dataset_items(
        self,
        *,
        items: typing.Sequence[DatasetItemWrite],
        dataset_name: typing.Optional[str] = OMIT,
        dataset_id: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        batch_group_id: typing.Optional[str] = OMIT,
        copy_from_dataset_id: typing.Optional[str] = OMIT,
        copy_from_version_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        根据数据集条目的 id 创建/更新数据集条目。
        每个条目的 'id' 字段是稳定的标识符和 upsert 键。
        提供它可更新现有条目，省略它则可创建新条目。

        同时设置 'copy_from_dataset_id' 和 'copy_from_version_id'，以从
        所提供的 (dataset, version) 对中读取结转行，而不是从目的数据集的
        先前版本中读取。当这些字段为 null 时，结转行从目的数据集的先前版本中读取。

        Parameters
        ----------
        items : typing.Sequence[DatasetItemWrite]

        dataset_name : typing.Optional[str]
            若为 null，则必须提供 dataset_id

        dataset_id : typing.Optional[str]
            若为 null，则必须提供 dataset_name

        project_name : typing.Optional[str]
            可选。按名称将批次关联到项目。提供 project_id 时被忽略。

        project_id : typing.Optional[str]
            可选。按 ID 将批次关联到项目。优先级高于 project_name。

        batch_group_id : typing.Optional[str]
            可选的批组 ID，用于将多个批次归组到单个数据集版本。若为 null，则变更最新版本而不是创建新版本。

        copy_from_dataset_id : typing.Optional[str]
            可选。在物化新版本时读取结转行的数据集。必须与 copy_from_version_id 一起提供。为 null 时，结转行从目的数据集的先前版本中读取。

        copy_from_version_id : typing.Optional[str]
            可选。copy_from_dataset_id 中用于读取结转行的版本。必须与 copy_from_dataset_id 一起提供。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items",
            method="PUT",
            json={
                "dataset_name": dataset_name,
                "dataset_id": dataset_id,
                "project_name": project_name,
                "project_id": project_id,
                "items": convert_and_respect_annotation_metadata(
                    object_=items, annotation=typing.Sequence[DatasetItemWrite], direction="write"
                ),
                "batch_group_id": batch_group_id,
                "copy_from_dataset_id": copy_from_dataset_id,
                "copy_from_version_id": copy_from_version_id,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def create_dataset_items_from_csv(
        self,
        *,
        file: typing.Dict[str, typing.Optional[typing.Any]],
        dataset_id: str,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        从上传的 CSV 文件创建数据集条目。CSV 应在第一行包含表头。处理以批次异步进行。

        Parameters
        ----------
        file : typing.Dict[str, typing.Optional[typing.Any]]

        dataset_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items/from-csv",
            method="POST",
            data={
                "file": file,
                "dataset_id": dataset_id,
            },
            files={},
            headers={
                "content-type": "multipart/form-data",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def create_dataset_items_from_json(
        self,
        *,
        file: typing.Dict[str, typing.Optional[typing.Any]],
        dataset_id: str,
        format: CreateDatasetItemsFromJsonRequestFormat,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        从上传的 JSON 或 JSONL 文件创建数据集条目。JSON 文件必须包含顶层的对象数组。
        JSONL 文件的每个非空行包含一个 JSON 对象；不支持多行 JSON 对象。
        保留键（id、source、description、tags、evaluators、execution_policy）会被提取到
        对应的 DatasetItem 字段中；其余所有键构成条目的数据映射，并保留其 JSON 类型。
        要将数据集条目关联到特定的 trace 或 span，请使用专用的 /items/from-traces 或 /items/from-spans 端点。
        处理以批次异步进行。启用数据集版本控制后，提供的 id 会作为 upsert 键。

        Parameters
        ----------
        file : typing.Dict[str, typing.Optional[typing.Any]]

        dataset_id : str

        format : CreateDatasetItemsFromJsonRequestFormat

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items/from-json",
            method="POST",
            data={
                "file": file,
                "dataset_id": dataset_id,
                "format": format,
            },
            files={},
            headers={
                "content-type": "multipart/form-data",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def create_dataset_items_from_spans(
        self,
        dataset_id: str,
        *,
        span_ids: typing.Sequence[str],
        enrichment_options: SpanEnrichmentOptions,
        evaluators: typing.Optional[typing.Sequence[EvaluatorItem]] = OMIT,
        execution_policy: typing.Optional[ExecutionPolicy] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        从带增强元数据的 span 创建数据集条目

        Parameters
        ----------
        dataset_id : str

        span_ids : typing.Sequence[str]
            要添加到数据集的 span ID 集合

        enrichment_options : SpanEnrichmentOptions

        evaluators : typing.Optional[typing.Sequence[EvaluatorItem]]
            要应用到所创建条目的可选评估器

        execution_policy : typing.Optional[ExecutionPolicy]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(dataset_id)}/items/from-spans",
            method="POST",
            json={
                "span_ids": span_ids,
                "enrichment_options": convert_and_respect_annotation_metadata(
                    object_=enrichment_options, annotation=SpanEnrichmentOptions, direction="write"
                ),
                "evaluators": convert_and_respect_annotation_metadata(
                    object_=evaluators, annotation=typing.Sequence[EvaluatorItem], direction="write"
                ),
                "execution_policy": convert_and_respect_annotation_metadata(
                    object_=execution_policy, annotation=ExecutionPolicy, direction="write"
                ),
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def create_dataset_items_from_traces(
        self,
        dataset_id: str,
        *,
        trace_ids: typing.Sequence[str],
        enrichment_options: TraceEnrichmentOptions,
        evaluators: typing.Optional[typing.Sequence[EvaluatorItem]] = OMIT,
        execution_policy: typing.Optional[ExecutionPolicy] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        从带增强元数据的 trace 创建数据集条目

        Parameters
        ----------
        dataset_id : str

        trace_ids : typing.Sequence[str]
            要添加到数据集的 trace ID 集合

        enrichment_options : TraceEnrichmentOptions

        evaluators : typing.Optional[typing.Sequence[EvaluatorItem]]
            要应用到所创建条目的可选评估器

        execution_policy : typing.Optional[ExecutionPolicy]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(dataset_id)}/items/from-traces",
            method="POST",
            json={
                "trace_ids": trace_ids,
                "enrichment_options": convert_and_respect_annotation_metadata(
                    object_=enrichment_options, annotation=TraceEnrichmentOptions, direction="write"
                ),
                "evaluators": convert_and_respect_annotation_metadata(
                    object_=evaluators, annotation=typing.Sequence[EvaluatorItem], direction="write"
                ),
                "execution_policy": convert_and_respect_annotation_metadata(
                    object_=execution_policy, annotation=ExecutionPolicy, direction="write"
                ),
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_dataset_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[DatasetPublic]:
        """
        按 ID 获取数据集

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetPublic]
            数据集资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetPublic,
                    parse_obj_as(
                        type_=DatasetPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def update_dataset(
        self,
        id: str,
        *,
        name: str,
        description: typing.Optional[str] = OMIT,
        visibility: typing.Optional[DatasetUpdateVisibility] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        按 ID 更新数据集

        Parameters
        ----------
        id : str

        name : str

        description : typing.Optional[str]

        visibility : typing.Optional[DatasetUpdateVisibility]

        tags : typing.Optional[typing.Sequence[str]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}",
            method="PUT",
            json={
                "name": name,
                "description": description,
                "visibility": visibility,
                "tags": tags,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def delete_dataset(self, id: str, *, request_options: typing.Optional[RequestOptions] = None) -> HttpResponse[None]:
        """
        按 ID 删除数据集

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}",
            method="DELETE",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def delete_dataset_by_name(
        self,
        *,
        dataset_name: str,
        project_name: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        按名称删除数据集

        Parameters
        ----------
        dataset_name : str

        project_name : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets/delete",
            method="POST",
            json={
                "dataset_name": dataset_name,
                "project_name": project_name,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def delete_dataset_items(
        self,
        *,
        item_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        dataset_id: typing.Optional[str] = OMIT,
        filters: typing.Optional[typing.Sequence[DatasetItemFilter]] = OMIT,
        batch_group_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        使用以下两种模式之一删除数据集条目：
        1. **按 ID 删除**：提供 'item_ids' 以按 ID 删除特定条目
        2. **按筛选条件删除**：提供 'dataset_id' 和可选的 'filters'，以删除符合条件匹配的条目

        使用筛选条件时，空的 'filters' 数组将删除指定数据集中的所有条目。

        Parameters
        ----------
        item_ids : typing.Optional[typing.Sequence[str]]
            要删除的数据集条目 ID 列表（最多 1000 个）。使用它可按 ID 删除特定条目。与 'dataset_id' 和 'filters' 互斥。

        dataset_id : typing.Optional[str]
            用于限定删除范围的数据集 ID。使用 'filters' 时必填。与 'item_ids' 互斥。

        filters : typing.Optional[typing.Sequence[DatasetItemFilter]]
            用于选择要在指定数据集中删除的数据集条目的筛选条件。必须与 'dataset_id' 一起使用。与 'item_ids' 互斥。空数组表示“删除数据集中的所有条目”。

        batch_group_id : typing.Optional[str]
            可选的批组 ID，用于将多个删除操作归组到单个数据集版本。若为 null，则变更最新版本而不是创建新版本。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items/delete",
            method="POST",
            json={
                "item_ids": item_ids,
                "dataset_id": dataset_id,
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[DatasetItemFilter], direction="write"
                ),
                "batch_group_id": batch_group_id,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def delete_datasets_batch(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        批量删除数据集

        Parameters
        ----------
        ids : typing.Sequence[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets/delete-batch",
            method="POST",
            json={
                "ids": ids,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    @contextlib.contextmanager
    def download_dataset_export(
        self, job_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> typing.Iterator[HttpResponse[typing.Iterator[bytes]]]:
        """
        为已完成的导出任务下载导出的 CSV 文件。此端点会代理文件下载，以避免暴露内部存储 URL。

        Parameters
        ----------
        job_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。你可以传入诸如 `chunk_size` 等配置来定制请求和响应。

        Returns
        -------
        typing.Iterator[HttpResponse[typing.Iterator[bytes]]]
            CSV 文件内容
        """
        with self._client_wrapper.httpx_client.stream(
            f"v1/private/datasets/export-jobs/{jsonable_encoder(job_id)}/download",
            method="GET",
            request_options=request_options,
        ) as _response:

            def stream() -> HttpResponse[typing.Iterator[bytes]]:
                try:
                    if 200 <= _response.status_code < 300:
                        _chunk_size = request_options.get("chunk_size", None) if request_options is not None else None
                        return HttpResponse(
                            response=_response, data=(_chunk for _chunk in _response.iter_bytes(chunk_size=_chunk_size))
                        )
                    _response.read()
                    if _response.status_code == 400:
                        raise BadRequestError(
                            headers=dict(_response.headers),
                            body=typing.cast(
                                typing.Optional[typing.Any],
                                parse_obj_as(
                                    type_=typing.Optional[typing.Any],  # type: ignore
                                    object_=_response.json(),
                                ),
                            ),
                        )
                    if _response.status_code == 404:
                        raise NotFoundError(
                            headers=dict(_response.headers),
                            body=typing.cast(
                                typing.Optional[typing.Any],
                                parse_obj_as(
                                    type_=typing.Optional[typing.Any],  # type: ignore
                                    object_=_response.json(),
                                ),
                            ),
                        )
                    _response_json = _response.json()
                except JSONDecodeError:
                    raise ApiError(
                        status_code=_response.status_code, headers=dict(_response.headers), body=_response.text
                    )
                raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

            yield stream()

    def expand_dataset(
        self,
        id: str,
        *,
        model: str,
        sample_count: typing.Optional[int] = OMIT,
        preserve_fields: typing.Optional[typing.Sequence[str]] = OMIT,
        variation_instructions: typing.Optional[str] = OMIT,
        custom_prompt: typing.Optional[str] = OMIT,
        max_completion_tokens: typing.Optional[int] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DatasetExpansionResponse]:
        """
        基于现有数据模式，使用 LLM 生成合成数据集样本

        Parameters
        ----------
        id : str

        model : str
            用于合成数据生成的模型

        sample_count : typing.Optional[int]
            要生成的合成样本数量

        preserve_fields : typing.Optional[typing.Sequence[str]]
            要从原始数据中保留模式的字段

        variation_instructions : typing.Optional[str]
            用于数据变体的附加指令

        custom_prompt : typing.Optional[str]
            用于生成的自定义提示词，替代自动生成的提示词

        max_completion_tokens : typing.Optional[int]
            LLM 响应的最大 token 数。Anthropic 必填，Gemini 用作 maxOutputTokens。若未提供，仅 Anthropic 模型默认为 4000。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetExpansionResponse]
            生成的合成样本
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/expansions",
            method="POST",
            json={
                "model": model,
                "sample_count": sample_count,
                "preserve_fields": preserve_fields,
                "variation_instructions": variation_instructions,
                "custom_prompt": custom_prompt,
                "max_completion_tokens": max_completion_tokens,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetExpansionResponse,
                    parse_obj_as(
                        type_=DatasetExpansionResponse,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def find_dataset_items_with_experiment_items(
        self,
        id: str,
        *,
        experiment_ids: str,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        filters: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        truncate: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DatasetItemPageCompare]:
        """
        查找带实验条目的数据集条目

        Parameters
        ----------
        id : str

        experiment_ids : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        filters : typing.Optional[str]

        sorting : typing.Optional[str]

        search : typing.Optional[str]

        truncate : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetItemPageCompare]
            数据集条目资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items/experiments/items",
            method="GET",
            params={
                "page": page,
                "size": size,
                "experiment_ids": experiment_ids,
                "filters": filters,
                "sorting": sorting,
                "search": search,
                "truncate": truncate,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetItemPageCompare,
                    parse_obj_as(
                        type_=DatasetItemPageCompare,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_dataset_by_identifier(
        self,
        *,
        dataset_name: str,
        project_name: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DatasetPublic]:
        """
        按名称获取数据集

        Parameters
        ----------
        dataset_name : str

        project_name : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetPublic]
            数据集资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets/retrieve",
            method="POST",
            json={
                "dataset_name": dataset_name,
                "project_name": project_name,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetPublic,
                    parse_obj_as(
                        type_=DatasetPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_dataset_experiment_items_stats(
        self,
        id: str,
        *,
        experiment_ids: str,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[ProjectStatsPublic]:
        """
        获取数据集的实验条目统计信息

        Parameters
        ----------
        id : str

        experiment_ids : str

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ProjectStatsPublic]
            实验条目统计信息资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items/experiments/items/stats",
            method="GET",
            params={
                "experiment_ids": experiment_ids,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ProjectStatsPublic,
                    parse_obj_as(
                        type_=ProjectStatsPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_dataset_export_job(
        self, job_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[DatasetExportJobPublic]:
        """
        获取数据集导出任务的当前状态

        Parameters
        ----------
        job_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetExportJobPublic]
            导出任务详情
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/export-jobs/{jsonable_encoder(job_id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetExportJobPublic,
                    parse_obj_as(
                        type_=DatasetExportJobPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_dataset_export_jobs(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[typing.List[DatasetExportJobPublic]]:
        """
        获取工作空间的所有导出任务。用于在页面刷新后恢复导出面板的状态。

        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[typing.List[DatasetExportJobPublic]]
            导出任务列表
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/datasets/export-jobs",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    typing.List[DatasetExportJobPublic],
                    parse_obj_as(
                        type_=typing.List[DatasetExportJobPublic],  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_dataset_item_by_id(
        self, item_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[DatasetItemPublic]:
        """
        按 ID 获取数据集条目

        Parameters
        ----------
        item_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetItemPublic]
            数据集条目资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/items/{jsonable_encoder(item_id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetItemPublic,
                    parse_obj_as(
                        type_=DatasetItemPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def patch_dataset_item(
        self,
        item_id: str,
        *,
        source: DatasetItemWriteSource,
        data: JsonNode,
        id: typing.Optional[str] = OMIT,
        trace_id: typing.Optional[str] = OMIT,
        span_id: typing.Optional[str] = OMIT,
        description: typing.Optional[str] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        evaluators: typing.Optional[typing.Sequence[EvaluatorItemWrite]] = OMIT,
        execution_policy: typing.Optional[ExecutionPolicyWrite] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        按 ID 部分更新数据集条目。仅更新提供的字段。

        Parameters
        ----------
        item_id : str

        source : DatasetItemWriteSource

        data : JsonNode

        id : typing.Optional[str]
            稳定的条目标识符。
            写入时，用作 upsert 键。
            若省略，则生成新 ID。
            跨数据集版本保持不变

        trace_id : typing.Optional[str]

        span_id : typing.Optional[str]

        description : typing.Optional[str]

        tags : typing.Optional[typing.Sequence[str]]

        evaluators : typing.Optional[typing.Sequence[EvaluatorItemWrite]]

        execution_policy : typing.Optional[ExecutionPolicyWrite]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/items/{jsonable_encoder(item_id)}",
            method="PATCH",
            json={
                "id": id,
                "trace_id": trace_id,
                "span_id": span_id,
                "source": source,
                "data": data,
                "description": description,
                "tags": tags,
                "evaluators": convert_and_respect_annotation_metadata(
                    object_=evaluators, annotation=typing.Sequence[EvaluatorItemWrite], direction="write"
                ),
                "execution_policy": convert_and_respect_annotation_metadata(
                    object_=execution_policy, annotation=ExecutionPolicyWrite, direction="write"
                ),
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_dataset_items(
        self,
        id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        version: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        truncate: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DatasetItemPagePublic]:
        """
        获取数据集条目

        Parameters
        ----------
        id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        version : typing.Optional[str]

        filters : typing.Optional[str]

        truncate : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetItemPagePublic]
            数据集条目资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items",
            method="GET",
            params={
                "page": page,
                "size": size,
                "version": version,
                "filters": filters,
                "truncate": truncate,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetItemPagePublic,
                    parse_obj_as(
                        type_=DatasetItemPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_dataset_items_output_columns(
        self,
        id: str,
        *,
        experiment_ids: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[PageColumns]:
        """
        获取数据集条目的输出列

        Parameters
        ----------
        id : str

        experiment_ids : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[PageColumns]
            数据集条目输出列
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items/experiments/items/output/columns",
            method="GET",
            params={
                "experiment_ids": experiment_ids,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    PageColumns,
                    parse_obj_as(
                        type_=PageColumns,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def mark_dataset_export_job_viewed(
        self, job_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        通过设置 viewed_at 时间戳将数据集导出任务标记为已查看。用于跟踪用户已看到失败任务的错误消息。此操作是幂等的。

        Parameters
        ----------
        job_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/export-jobs/{jsonable_encoder(job_id)}/mark-viewed",
            method="PUT",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def start_dataset_export(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[DatasetExportJobPublic]:
        """
        为数据集启动异步的 CSV 导出任务。立即返回任务详情以供轮询。

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetExportJobPublic]
            已存在进行中的导出任务
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/export",
            method="POST",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetExportJobPublic,
                    parse_obj_as(
                        type_=DatasetExportJobPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    @contextlib.contextmanager
    def stream_dataset_items(
        self,
        *,
        dataset_name: str,
        last_retrieved_id: typing.Optional[str] = OMIT,
        steam_limit: typing.Optional[int] = OMIT,
        dataset_version: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        filters: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> typing.Iterator[HttpResponse[typing.Iterator[bytes]]]:
        """
        流式获取数据集条目

        Parameters
        ----------
        dataset_name : str

        last_retrieved_id : typing.Optional[str]

        steam_limit : typing.Optional[int]

        dataset_version : typing.Optional[str]

        project_name : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。你可以传入诸如 `chunk_size` 等配置来定制请求和响应。

        Returns
        -------
        typing.Iterator[HttpResponse[typing.Iterator[bytes]]]
            处理过程中的数据集条目流或错误
        """
        with self._client_wrapper.httpx_client.stream(
            "v1/private/datasets/items/stream",
            method="POST",
            json={
                "dataset_name": dataset_name,
                "last_retrieved_id": last_retrieved_id,
                "steam_limit": steam_limit,
                "dataset_version": dataset_version,
                "project_name": project_name,
                "filters": filters,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        ) as _response:

            def stream() -> HttpResponse[typing.Iterator[bytes]]:
                try:
                    if 200 <= _response.status_code < 300:
                        _chunk_size = request_options.get("chunk_size", None) if request_options is not None else None
                        return HttpResponse(
                            response=_response, data=(_chunk for _chunk in _response.iter_bytes(chunk_size=_chunk_size))
                        )
                    _response.read()
                    _response_json = _response.json()
                except JSONDecodeError:
                    raise ApiError(
                        status_code=_response.status_code, headers=dict(_response.headers), body=_response.text
                    )
                raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

            yield stream()

    def compare_dataset_versions(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[DatasetVersionDiff]:
        """
        将最新已提交的数据集版本与当前草稿状态进行比较。此端点可让你了解自上次提交版本以来所做的变更。该比较会计算最新版本快照与当前草稿之间的新增、修改、删除和未变更条目。

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetVersionDiff]
            差异计算成功
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/diff",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionDiff,
                    parse_obj_as(
                        type_=DatasetVersionDiff,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def create_version_tag(
        self, id: str, version_hash: str, *, tag: str, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        为特定数据集版本添加标签，以便于引用（例如 'baseline'、'v1.0'、'production'）

        Parameters
        ----------
        id : str

        version_hash : str

        tag : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/hash/{jsonable_encoder(version_hash)}/tags",
            method="POST",
            json={
                "tag": tag,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 409:
                raise ConflictError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def delete_version_tag(
        self, id: str, version_hash: str, tag: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        从数据集版本中移除标签。版本本身不会被删除，只会删除标签引用。

        Parameters
        ----------
        id : str

        version_hash : str

        tag : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/{jsonable_encoder(version_hash)}/tags/{jsonable_encoder(tag)}",
            method="DELETE",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def list_dataset_versions(
        self,
        id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DatasetVersionPagePublic]:
        """
        获取数据集版本的分页列表，按创建时间排序（最新的在前）

        Parameters
        ----------
        id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetVersionPagePublic]
            数据集版本
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions",
            method="GET",
            params={
                "page": page,
                "size": size,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPagePublic,
                    parse_obj_as(
                        type_=DatasetVersionPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def restore_dataset_version(
        self, id: str, *, version_ref: str, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[DatasetVersionPublic]:
        """
        通过创建一个新版本（条目从指定版本复制而来）将数据集恢复到先前的版本状态。如果该版本已经是最新版本，则原样返回（无操作）。

        Parameters
        ----------
        id : str

        version_ref : str
            要恢复的版本哈希或标签

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetVersionPublic]
            版本恢复成功
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/restore",
            method="POST",
            json={
                "version_ref": version_ref,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPublic,
                    parse_obj_as(
                        type_=DatasetVersionPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 409:
                raise ConflictError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def retrieve_dataset_version(
        self, id: str, *, version_name: str, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[DatasetVersionPublic]:
        """
        通过版本名称（例如 'v1'、'v373'）获取特定版本。对于大型数据集，这比遍历所有版本分页更高效。

        Parameters
        ----------
        id : str

        version_name : str
            格式为 'vN' 的版本名称（例如 'v1'、'v373'）

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetVersionPublic]
            数据集版本
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/retrieve",
            method="POST",
            json={
                "version_name": version_name,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPublic,
                    parse_obj_as(
                        type_=DatasetVersionPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def update_dataset_version(
        self,
        id: str,
        version_hash: str,
        *,
        change_description: typing.Optional[str] = OMIT,
        tags_to_add: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DatasetVersionPublic]:
        """
        更新数据集版本的 change_description 和/或添加新标签

        Parameters
        ----------
        id : str

        version_hash : str

        change_description : typing.Optional[str]
            此版本变更的可选描述

        tags_to_add : typing.Optional[typing.Sequence[str]]
            要添加到此版本的可选标签列表

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetVersionPublic]
            版本更新成功
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/hash/{jsonable_encoder(version_hash)}",
            method="PATCH",
            json={
                "change_description": change_description,
                "tags_to_add": tags_to_add,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPublic,
                    parse_obj_as(
                        type_=DatasetVersionPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 409:
                raise ConflictError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)


class AsyncRawDatasetsClient:
    def __init__(self, *, client_wrapper: AsyncClientWrapper):
        self._client_wrapper = client_wrapper

    async def apply_dataset_item_changes(
        self,
        id: str,
        *,
        request: DatasetItemChangesPublic,
        override: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DatasetVersionPublic]:
        """
        对数据集版本应用增量变更（新增、编辑、删除），并进行冲突检测。

        此端点：
        - 创建一个应用了变更的新版本
        - 校验 baseVersion 与最新版本是否匹配（除非 override=true）
        - 如果 baseVersion 已过期且未设置 override，则返回 409 Conflict

        使用 `override=true` 查询参数，即使在 baseVersion 已过期的情况下也强制创建版本。

        在请求体中同时设置 'copy_from_dataset_id' 和 'copy_from_version_id'，以从
        所提供的 (dataset, version) 对中读取结转行，而不是从目的数据集的
        先前版本中读取。当这些字段为 null 时，结转行从目的数据集的先前版本中读取。

        Parameters
        ----------
        id : str

        request : DatasetItemChangesPublic

        override : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetVersionPublic]
            版本创建成功
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items/changes",
            method="POST",
            params={
                "override": override,
            },
            json=request,
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPublic,
                    parse_obj_as(
                        type_=DatasetVersionPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 409:
                raise ConflictError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def batch_update_dataset_items(
        self,
        *,
        update: DatasetItemUpdate,
        ids: typing.Optional[typing.Sequence[str]] = OMIT,
        filters: typing.Optional[typing.Sequence[DatasetItemFilter]] = OMIT,
        dataset_id: typing.Optional[str] = OMIT,
        merge_tags: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        更新多个数据集条目

        Parameters
        ----------
        update : DatasetItemUpdate

        ids : typing.Optional[typing.Sequence[str]]
            要更新的数据集条目 ID 列表（最多 1000 个）。与 'filters' 互斥。

        filters : typing.Optional[typing.Sequence[DatasetItemFilter]]

        dataset_id : typing.Optional[str]
            数据集 ID。使用 'filters' 时必填，使用 'ids' 时可选。

        merge_tags : typing.Optional[bool]
            若为 true，则将标签与现有标签合并而不是替换。默认值：false。使用 'filters' 时，此值会自动设为 true。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items/batch",
            method="PATCH",
            json={
                "ids": ids,
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[DatasetItemFilter], direction="write"
                ),
                "dataset_id": dataset_id,
                "update": convert_and_respect_annotation_metadata(
                    object_=update, annotation=DatasetItemUpdate, direction="write"
                ),
                "merge_tags": merge_tags,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def find_datasets(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        with_experiments_only: typing.Optional[bool] = None,
        with_optimizations_only: typing.Optional[bool] = None,
        prompt_id: typing.Optional[str] = None,
        project_id: typing.Optional[str] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DatasetPagePublic]:
        """
        查找数据集

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        with_experiments_only : typing.Optional[bool]

        with_optimizations_only : typing.Optional[bool]

        prompt_id : typing.Optional[str]

        project_id : typing.Optional[str]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetPagePublic]
            数据集资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets",
            method="GET",
            params={
                "page": page,
                "size": size,
                "with_experiments_only": with_experiments_only,
                "with_optimizations_only": with_optimizations_only,
                "prompt_id": prompt_id,
                "project_id": project_id,
                "name": name,
                "sorting": sorting,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetPagePublic,
                    parse_obj_as(
                        type_=DatasetPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def create_dataset(
        self,
        *,
        name: str,
        id: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        type: typing.Optional[DatasetWriteType] = OMIT,
        visibility: typing.Optional[DatasetWriteVisibility] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        description: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        创建数据集

        Parameters
        ----------
        name : str

        id : typing.Optional[str]

        project_id : typing.Optional[str]
            项目 ID。当同时提供两者时，优先级高于 project_name。

        project_name : typing.Optional[str]
            对于项目作用域，指定 project_id 或 project_name 之一。如果提供了 project_name 且项目不存在，则会创建该项目。提供 project_id 时此字段被忽略。如果两者都未提供，则数据集在工作空间级别创建。

        type : typing.Optional[DatasetWriteType]

        visibility : typing.Optional[DatasetWriteVisibility]

        tags : typing.Optional[typing.Sequence[str]]

        description : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets",
            method="POST",
            json={
                "id": id,
                "name": name,
                "project_id": project_id,
                "project_name": project_name,
                "type": type,
                "visibility": visibility,
                "tags": tags,
                "description": description,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def create_or_update_dataset_items(
        self,
        *,
        items: typing.Sequence[DatasetItemWrite],
        dataset_name: typing.Optional[str] = OMIT,
        dataset_id: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        batch_group_id: typing.Optional[str] = OMIT,
        copy_from_dataset_id: typing.Optional[str] = OMIT,
        copy_from_version_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        根据数据集条目的 id 创建/更新数据集条目。
        每个条目的 'id' 字段是稳定的标识符和 upsert 键。
        提供它可更新现有条目，省略它则可创建新条目。

        同时设置 'copy_from_dataset_id' 和 'copy_from_version_id'，以从
        所提供的 (dataset, version) 对中读取结转行，而不是从目的数据集的
        先前版本中读取。当这些字段为 null 时，结转行从目的数据集的先前版本中读取。

        Parameters
        ----------
        items : typing.Sequence[DatasetItemWrite]

        dataset_name : typing.Optional[str]
            若为 null，则必须提供 dataset_id

        dataset_id : typing.Optional[str]
            若为 null，则必须提供 dataset_name

        project_name : typing.Optional[str]
            可选。按名称将批次关联到项目。提供 project_id 时被忽略。

        project_id : typing.Optional[str]
            可选。按 ID 将批次关联到项目。优先级高于 project_name。

        batch_group_id : typing.Optional[str]
            可选的批组 ID，用于将多个批次归组到单个数据集版本。若为 null，则变更最新版本而不是创建新版本。

        copy_from_dataset_id : typing.Optional[str]
            可选。在物化新版本时读取结转行的数据集。必须与 copy_from_version_id 一起提供。为 null 时，结转行从目的数据集的先前版本中读取。

        copy_from_version_id : typing.Optional[str]
            可选。copy_from_dataset_id 中用于读取结转行的版本。必须与 copy_from_dataset_id 一起提供。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items",
            method="PUT",
            json={
                "dataset_name": dataset_name,
                "dataset_id": dataset_id,
                "project_name": project_name,
                "project_id": project_id,
                "items": convert_and_respect_annotation_metadata(
                    object_=items, annotation=typing.Sequence[DatasetItemWrite], direction="write"
                ),
                "batch_group_id": batch_group_id,
                "copy_from_dataset_id": copy_from_dataset_id,
                "copy_from_version_id": copy_from_version_id,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def create_dataset_items_from_csv(
        self,
        *,
        file: typing.Dict[str, typing.Optional[typing.Any]],
        dataset_id: str,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        从上传的 CSV 文件创建数据集条目。CSV 应在第一行包含表头。处理以批次异步进行。

        Parameters
        ----------
        file : typing.Dict[str, typing.Optional[typing.Any]]

        dataset_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items/from-csv",
            method="POST",
            data={
                "file": file,
                "dataset_id": dataset_id,
            },
            files={},
            headers={
                "content-type": "multipart/form-data",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def create_dataset_items_from_json(
        self,
        *,
        file: typing.Dict[str, typing.Optional[typing.Any]],
        dataset_id: str,
        format: CreateDatasetItemsFromJsonRequestFormat,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        从上传的 JSON 或 JSONL 文件创建数据集条目。JSON 文件必须包含顶层的对象数组。
        JSONL 文件的每个非空行包含一个 JSON 对象；不支持多行 JSON 对象。
        保留键（id、source、description、tags、evaluators、execution_policy）会被提取到
        对应的 DatasetItem 字段中；其余所有键构成条目的数据映射，并保留其 JSON 类型。
        要将数据集条目关联到特定的 trace 或 span，请使用专用的 /items/from-traces 或 /items/from-spans 端点。
        处理以批次异步进行。启用数据集版本控制后，提供的 id 会作为 upsert 键。

        Parameters
        ----------
        file : typing.Dict[str, typing.Optional[typing.Any]]

        dataset_id : str

        format : CreateDatasetItemsFromJsonRequestFormat

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items/from-json",
            method="POST",
            data={
                "file": file,
                "dataset_id": dataset_id,
                "format": format,
            },
            files={},
            headers={
                "content-type": "multipart/form-data",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def create_dataset_items_from_spans(
        self,
        dataset_id: str,
        *,
        span_ids: typing.Sequence[str],
        enrichment_options: SpanEnrichmentOptions,
        evaluators: typing.Optional[typing.Sequence[EvaluatorItem]] = OMIT,
        execution_policy: typing.Optional[ExecutionPolicy] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        从带增强元数据的 span 创建数据集条目

        Parameters
        ----------
        dataset_id : str

        span_ids : typing.Sequence[str]
            要添加到数据集的 span ID 集合

        enrichment_options : SpanEnrichmentOptions

        evaluators : typing.Optional[typing.Sequence[EvaluatorItem]]
            要应用到所创建条目的可选评估器

        execution_policy : typing.Optional[ExecutionPolicy]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(dataset_id)}/items/from-spans",
            method="POST",
            json={
                "span_ids": span_ids,
                "enrichment_options": convert_and_respect_annotation_metadata(
                    object_=enrichment_options, annotation=SpanEnrichmentOptions, direction="write"
                ),
                "evaluators": convert_and_respect_annotation_metadata(
                    object_=evaluators, annotation=typing.Sequence[EvaluatorItem], direction="write"
                ),
                "execution_policy": convert_and_respect_annotation_metadata(
                    object_=execution_policy, annotation=ExecutionPolicy, direction="write"
                ),
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def create_dataset_items_from_traces(
        self,
        dataset_id: str,
        *,
        trace_ids: typing.Sequence[str],
        enrichment_options: TraceEnrichmentOptions,
        evaluators: typing.Optional[typing.Sequence[EvaluatorItem]] = OMIT,
        execution_policy: typing.Optional[ExecutionPolicy] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        从带增强元数据的 trace 创建数据集条目

        Parameters
        ----------
        dataset_id : str

        trace_ids : typing.Sequence[str]
            要添加到数据集的 trace ID 集合

        enrichment_options : TraceEnrichmentOptions

        evaluators : typing.Optional[typing.Sequence[EvaluatorItem]]
            要应用到所创建条目的可选评估器

        execution_policy : typing.Optional[ExecutionPolicy]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(dataset_id)}/items/from-traces",
            method="POST",
            json={
                "trace_ids": trace_ids,
                "enrichment_options": convert_and_respect_annotation_metadata(
                    object_=enrichment_options, annotation=TraceEnrichmentOptions, direction="write"
                ),
                "evaluators": convert_and_respect_annotation_metadata(
                    object_=evaluators, annotation=typing.Sequence[EvaluatorItem], direction="write"
                ),
                "execution_policy": convert_and_respect_annotation_metadata(
                    object_=execution_policy, annotation=ExecutionPolicy, direction="write"
                ),
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_dataset_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[DatasetPublic]:
        """
        按 ID 获取数据集

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetPublic]
            数据集资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetPublic,
                    parse_obj_as(
                        type_=DatasetPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def update_dataset(
        self,
        id: str,
        *,
        name: str,
        description: typing.Optional[str] = OMIT,
        visibility: typing.Optional[DatasetUpdateVisibility] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 更新数据集

        Parameters
        ----------
        id : str

        name : str

        description : typing.Optional[str]

        visibility : typing.Optional[DatasetUpdateVisibility]

        tags : typing.Optional[typing.Sequence[str]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}",
            method="PUT",
            json={
                "name": name,
                "description": description,
                "visibility": visibility,
                "tags": tags,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def delete_dataset(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 删除数据集

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}",
            method="DELETE",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def delete_dataset_by_name(
        self,
        *,
        dataset_name: str,
        project_name: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        按名称删除数据集

        Parameters
        ----------
        dataset_name : str

        project_name : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets/delete",
            method="POST",
            json={
                "dataset_name": dataset_name,
                "project_name": project_name,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def delete_dataset_items(
        self,
        *,
        item_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        dataset_id: typing.Optional[str] = OMIT,
        filters: typing.Optional[typing.Sequence[DatasetItemFilter]] = OMIT,
        batch_group_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        使用以下两种模式之一删除数据集条目：
        1. **按 ID 删除**：提供 'item_ids' 以按 ID 删除特定条目
        2. **按筛选条件删除**：提供 'dataset_id' 和可选的 'filters'，以删除符合条件匹配的条目

        使用筛选条件时，空的 'filters' 数组将删除指定数据集中的所有条目。

        Parameters
        ----------
        item_ids : typing.Optional[typing.Sequence[str]]
            要删除的数据集条目 ID 列表（最多 1000 个）。使用它可按 ID 删除特定条目。与 'dataset_id' 和 'filters' 互斥。

        dataset_id : typing.Optional[str]
            用于限定删除范围的数据集 ID。使用 'filters' 时必填。与 'item_ids' 互斥。

        filters : typing.Optional[typing.Sequence[DatasetItemFilter]]
            用于选择要在指定数据集中删除的数据集条目的筛选条件。必须与 'dataset_id' 一起使用。与 'item_ids' 互斥。空数组表示“删除数据集中的所有条目”。

        batch_group_id : typing.Optional[str]
            可选的批组 ID，用于将多个删除操作归组到单个数据集版本。若为 null，则变更最新版本而不是创建新版本。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets/items/delete",
            method="POST",
            json={
                "item_ids": item_ids,
                "dataset_id": dataset_id,
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[DatasetItemFilter], direction="write"
                ),
                "batch_group_id": batch_group_id,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def delete_datasets_batch(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        批量删除数据集

        Parameters
        ----------
        ids : typing.Sequence[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets/delete-batch",
            method="POST",
            json={
                "ids": ids,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    @contextlib.asynccontextmanager
    async def download_dataset_export(
        self, job_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> typing.AsyncIterator[AsyncHttpResponse[typing.AsyncIterator[bytes]]]:
        """
        为已完成的导出任务下载导出的 CSV 文件。此端点会代理文件下载，以避免暴露内部存储 URL。

        Parameters
        ----------
        job_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。你可以传入诸如 `chunk_size` 等配置来定制请求和响应。

        Returns
        -------
        typing.AsyncIterator[AsyncHttpResponse[typing.AsyncIterator[bytes]]]
            CSV 文件内容
        """
        async with self._client_wrapper.httpx_client.stream(
            f"v1/private/datasets/export-jobs/{jsonable_encoder(job_id)}/download",
            method="GET",
            request_options=request_options,
        ) as _response:

            async def stream() -> AsyncHttpResponse[typing.AsyncIterator[bytes]]:
                try:
                    if 200 <= _response.status_code < 300:
                        _chunk_size = request_options.get("chunk_size", None) if request_options is not None else None
                        return AsyncHttpResponse(
                            response=_response,
                            data=(_chunk async for _chunk in _response.aiter_bytes(chunk_size=_chunk_size)),
                        )
                    await _response.aread()
                    if _response.status_code == 400:
                        raise BadRequestError(
                            headers=dict(_response.headers),
                            body=typing.cast(
                                typing.Optional[typing.Any],
                                parse_obj_as(
                                    type_=typing.Optional[typing.Any],  # type: ignore
                                    object_=_response.json(),
                                ),
                            ),
                        )
                    if _response.status_code == 404:
                        raise NotFoundError(
                            headers=dict(_response.headers),
                            body=typing.cast(
                                typing.Optional[typing.Any],
                                parse_obj_as(
                                    type_=typing.Optional[typing.Any],  # type: ignore
                                    object_=_response.json(),
                                ),
                            ),
                        )
                    _response_json = _response.json()
                except JSONDecodeError:
                    raise ApiError(
                        status_code=_response.status_code, headers=dict(_response.headers), body=_response.text
                    )
                raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

            yield await stream()

    async def expand_dataset(
        self,
        id: str,
        *,
        model: str,
        sample_count: typing.Optional[int] = OMIT,
        preserve_fields: typing.Optional[typing.Sequence[str]] = OMIT,
        variation_instructions: typing.Optional[str] = OMIT,
        custom_prompt: typing.Optional[str] = OMIT,
        max_completion_tokens: typing.Optional[int] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DatasetExpansionResponse]:
        """
        基于现有数据模式，使用 LLM 生成合成数据集样本

        Parameters
        ----------
        id : str

        model : str
            用于合成数据生成的模型

        sample_count : typing.Optional[int]
            要生成的合成样本数量

        preserve_fields : typing.Optional[typing.Sequence[str]]
            要从原始数据中保留模式的字段

        variation_instructions : typing.Optional[str]
            用于数据变体的附加指令

        custom_prompt : typing.Optional[str]
            用于生成的自定义提示词，替代自动生成的提示词

        max_completion_tokens : typing.Optional[int]
            LLM 响应的最大 token 数。Anthropic 必填，Gemini 用作 maxOutputTokens。若未提供，仅 Anthropic 模型默认为 4000。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetExpansionResponse]
            生成的合成样本
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/expansions",
            method="POST",
            json={
                "model": model,
                "sample_count": sample_count,
                "preserve_fields": preserve_fields,
                "variation_instructions": variation_instructions,
                "custom_prompt": custom_prompt,
                "max_completion_tokens": max_completion_tokens,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetExpansionResponse,
                    parse_obj_as(
                        type_=DatasetExpansionResponse,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def find_dataset_items_with_experiment_items(
        self,
        id: str,
        *,
        experiment_ids: str,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        filters: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        truncate: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DatasetItemPageCompare]:
        """
        查找带实验条目的数据集条目

        Parameters
        ----------
        id : str

        experiment_ids : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        filters : typing.Optional[str]

        sorting : typing.Optional[str]

        search : typing.Optional[str]

        truncate : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetItemPageCompare]
            数据集条目资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items/experiments/items",
            method="GET",
            params={
                "page": page,
                "size": size,
                "experiment_ids": experiment_ids,
                "filters": filters,
                "sorting": sorting,
                "search": search,
                "truncate": truncate,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetItemPageCompare,
                    parse_obj_as(
                        type_=DatasetItemPageCompare,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_dataset_by_identifier(
        self,
        *,
        dataset_name: str,
        project_name: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DatasetPublic]:
        """
        按名称获取数据集

        Parameters
        ----------
        dataset_name : str

        project_name : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetPublic]
            数据集资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets/retrieve",
            method="POST",
            json={
                "dataset_name": dataset_name,
                "project_name": project_name,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetPublic,
                    parse_obj_as(
                        type_=DatasetPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_dataset_experiment_items_stats(
        self,
        id: str,
        *,
        experiment_ids: str,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[ProjectStatsPublic]:
        """
        获取数据集的实验条目统计信息

        Parameters
        ----------
        id : str

        experiment_ids : str

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ProjectStatsPublic]
            实验条目统计信息资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items/experiments/items/stats",
            method="GET",
            params={
                "experiment_ids": experiment_ids,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ProjectStatsPublic,
                    parse_obj_as(
                        type_=ProjectStatsPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_dataset_export_job(
        self, job_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[DatasetExportJobPublic]:
        """
        获取数据集导出任务的当前状态

        Parameters
        ----------
        job_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetExportJobPublic]
            导出任务详情
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/export-jobs/{jsonable_encoder(job_id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetExportJobPublic,
                    parse_obj_as(
                        type_=DatasetExportJobPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_dataset_export_jobs(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[typing.List[DatasetExportJobPublic]]:
        """
        获取工作空间的所有导出任务。用于在页面刷新后恢复导出面板的状态。

        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[typing.List[DatasetExportJobPublic]]
            导出任务列表
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/datasets/export-jobs",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    typing.List[DatasetExportJobPublic],
                    parse_obj_as(
                        type_=typing.List[DatasetExportJobPublic],  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_dataset_item_by_id(
        self, item_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[DatasetItemPublic]:
        """
        按 ID 获取数据集条目

        Parameters
        ----------
        item_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetItemPublic]
            数据集条目资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/items/{jsonable_encoder(item_id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetItemPublic,
                    parse_obj_as(
                        type_=DatasetItemPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def patch_dataset_item(
        self,
        item_id: str,
        *,
        source: DatasetItemWriteSource,
        data: JsonNode,
        id: typing.Optional[str] = OMIT,
        trace_id: typing.Optional[str] = OMIT,
        span_id: typing.Optional[str] = OMIT,
        description: typing.Optional[str] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        evaluators: typing.Optional[typing.Sequence[EvaluatorItemWrite]] = OMIT,
        execution_policy: typing.Optional[ExecutionPolicyWrite] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 部分更新数据集条目。仅更新提供的字段。

        Parameters
        ----------
        item_id : str

        source : DatasetItemWriteSource

        data : JsonNode

        id : typing.Optional[str]
            稳定的条目标识符。
            写入时，用作 upsert 键。
            若省略，则生成新 ID。
            跨数据集版本保持不变

        trace_id : typing.Optional[str]

        span_id : typing.Optional[str]

        description : typing.Optional[str]

        tags : typing.Optional[typing.Sequence[str]]

        evaluators : typing.Optional[typing.Sequence[EvaluatorItemWrite]]

        execution_policy : typing.Optional[ExecutionPolicyWrite]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/items/{jsonable_encoder(item_id)}",
            method="PATCH",
            json={
                "id": id,
                "trace_id": trace_id,
                "span_id": span_id,
                "source": source,
                "data": data,
                "description": description,
                "tags": tags,
                "evaluators": convert_and_respect_annotation_metadata(
                    object_=evaluators, annotation=typing.Sequence[EvaluatorItemWrite], direction="write"
                ),
                "execution_policy": convert_and_respect_annotation_metadata(
                    object_=execution_policy, annotation=ExecutionPolicyWrite, direction="write"
                ),
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_dataset_items(
        self,
        id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        version: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        truncate: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DatasetItemPagePublic]:
        """
        获取数据集条目

        Parameters
        ----------
        id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        version : typing.Optional[str]

        filters : typing.Optional[str]

        truncate : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetItemPagePublic]
            数据集条目资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items",
            method="GET",
            params={
                "page": page,
                "size": size,
                "version": version,
                "filters": filters,
                "truncate": truncate,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetItemPagePublic,
                    parse_obj_as(
                        type_=DatasetItemPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_dataset_items_output_columns(
        self,
        id: str,
        *,
        experiment_ids: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[PageColumns]:
        """
        获取数据集条目的输出列

        Parameters
        ----------
        id : str

        experiment_ids : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[PageColumns]
            数据集条目输出列
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/items/experiments/items/output/columns",
            method="GET",
            params={
                "experiment_ids": experiment_ids,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    PageColumns,
                    parse_obj_as(
                        type_=PageColumns,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def mark_dataset_export_job_viewed(
        self, job_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        通过设置 viewed_at 时间戳将数据集导出任务标记为已查看。用于跟踪用户已看到失败任务的错误消息。此操作是幂等的。

        Parameters
        ----------
        job_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/export-jobs/{jsonable_encoder(job_id)}/mark-viewed",
            method="PUT",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def start_dataset_export(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[DatasetExportJobPublic]:
        """
        为数据集启动异步的 CSV 导出任务。立即返回任务详情以供轮询。

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetExportJobPublic]
            已存在进行中的导出任务
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/export",
            method="POST",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetExportJobPublic,
                    parse_obj_as(
                        type_=DatasetExportJobPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    @contextlib.asynccontextmanager
    async def stream_dataset_items(
        self,
        *,
        dataset_name: str,
        last_retrieved_id: typing.Optional[str] = OMIT,
        steam_limit: typing.Optional[int] = OMIT,
        dataset_version: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        filters: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> typing.AsyncIterator[AsyncHttpResponse[typing.AsyncIterator[bytes]]]:
        """
        流式获取数据集条目

        Parameters
        ----------
        dataset_name : str

        last_retrieved_id : typing.Optional[str]

        steam_limit : typing.Optional[int]

        dataset_version : typing.Optional[str]

        project_name : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。你可以传入诸如 `chunk_size` 等配置来定制请求和响应。

        Returns
        -------
        typing.AsyncIterator[AsyncHttpResponse[typing.AsyncIterator[bytes]]]
            处理过程中的数据集条目流或错误
        """
        async with self._client_wrapper.httpx_client.stream(
            "v1/private/datasets/items/stream",
            method="POST",
            json={
                "dataset_name": dataset_name,
                "last_retrieved_id": last_retrieved_id,
                "steam_limit": steam_limit,
                "dataset_version": dataset_version,
                "project_name": project_name,
                "filters": filters,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        ) as _response:

            async def stream() -> AsyncHttpResponse[typing.AsyncIterator[bytes]]:
                try:
                    if 200 <= _response.status_code < 300:
                        _chunk_size = request_options.get("chunk_size", None) if request_options is not None else None
                        return AsyncHttpResponse(
                            response=_response,
                            data=(_chunk async for _chunk in _response.aiter_bytes(chunk_size=_chunk_size)),
                        )
                    await _response.aread()
                    _response_json = _response.json()
                except JSONDecodeError:
                    raise ApiError(
                        status_code=_response.status_code, headers=dict(_response.headers), body=_response.text
                    )
                raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

            yield await stream()

    async def compare_dataset_versions(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[DatasetVersionDiff]:
        """
        将最新已提交的数据集版本与当前草稿状态进行比较。此端点可让你了解自上次提交版本以来所做的变更。该比较会计算最新版本快照与当前草稿之间的新增、修改、删除和未变更条目。

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetVersionDiff]
            差异计算成功
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/diff",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionDiff,
                    parse_obj_as(
                        type_=DatasetVersionDiff,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def create_version_tag(
        self, id: str, version_hash: str, *, tag: str, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        为特定数据集版本添加标签，以便于引用（例如 'baseline'、'v1.0'、'production'）

        Parameters
        ----------
        id : str

        version_hash : str

        tag : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/hash/{jsonable_encoder(version_hash)}/tags",
            method="POST",
            json={
                "tag": tag,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 409:
                raise ConflictError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def delete_version_tag(
        self, id: str, version_hash: str, tag: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        从数据集版本中移除标签。版本本身不会被删除，只会删除标签引用。

        Parameters
        ----------
        id : str

        version_hash : str

        tag : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/{jsonable_encoder(version_hash)}/tags/{jsonable_encoder(tag)}",
            method="DELETE",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def list_dataset_versions(
        self,
        id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DatasetVersionPagePublic]:
        """
        获取数据集版本的分页列表，按创建时间排序（最新的在前）

        Parameters
        ----------
        id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetVersionPagePublic]
            数据集版本
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions",
            method="GET",
            params={
                "page": page,
                "size": size,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPagePublic,
                    parse_obj_as(
                        type_=DatasetVersionPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def restore_dataset_version(
        self, id: str, *, version_ref: str, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[DatasetVersionPublic]:
        """
        通过创建一个新版本（条目从指定版本复制而来）将数据集恢复到先前的版本状态。如果该版本已经是最新版本，则原样返回（无操作）。

        Parameters
        ----------
        id : str

        version_ref : str
            要恢复的版本哈希或标签

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetVersionPublic]
            版本恢复成功
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/restore",
            method="POST",
            json={
                "version_ref": version_ref,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPublic,
                    parse_obj_as(
                        type_=DatasetVersionPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 409:
                raise ConflictError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def retrieve_dataset_version(
        self, id: str, *, version_name: str, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[DatasetVersionPublic]:
        """
        通过版本名称（例如 'v1'、'v373'）获取特定版本。对于大型数据集，这比遍历所有版本分页更高效。

        Parameters
        ----------
        id : str

        version_name : str
            格式为 'vN' 的版本名称（例如 'v1'、'v373'）

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetVersionPublic]
            数据集版本
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/retrieve",
            method="POST",
            json={
                "version_name": version_name,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPublic,
                    parse_obj_as(
                        type_=DatasetVersionPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def update_dataset_version(
        self,
        id: str,
        version_hash: str,
        *,
        change_description: typing.Optional[str] = OMIT,
        tags_to_add: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DatasetVersionPublic]:
        """
        更新数据集版本的 change_description 和/或添加新标签

        Parameters
        ----------
        id : str

        version_hash : str

        change_description : typing.Optional[str]
            此版本变更的可选描述

        tags_to_add : typing.Optional[typing.Sequence[str]]
            要添加到此版本的可选标签列表

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetVersionPublic]
            版本更新成功
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/datasets/{jsonable_encoder(id)}/versions/hash/{jsonable_encoder(version_hash)}",
            method="PATCH",
            json={
                "change_description": change_description,
                "tags_to_add": tags_to_add,
            },
            headers={
                "content-type": "application/json",
            },
            request_options=request_options,
            omit=OMIT,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DatasetVersionPublic,
                    parse_obj_as(
                        type_=DatasetVersionPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            if _response.status_code == 400:
                raise BadRequestError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 404:
                raise NotFoundError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            if _response.status_code == 409:
                raise ConflictError(
                    headers=dict(_response.headers),
                    body=typing.cast(
                        typing.Optional[typing.Any],
                        parse_obj_as(
                            type_=typing.Optional[typing.Any],  # type: ignore
                            object_=_response.json(),
                        ),
                    ),
                )
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)
