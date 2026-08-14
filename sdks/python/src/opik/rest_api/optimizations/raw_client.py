# 此文件由 Fern 根据我们的 API 定义自动生成。

import datetime as dt
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
from ..errors.not_found_error import NotFoundError
from ..types.error_info import ErrorInfo
from ..types.error_info_write import ErrorInfoWrite
from ..types.json_list_string import JsonListString
from ..types.json_list_string_write import JsonListStringWrite
from ..types.optimization_page_public import OptimizationPagePublic
from ..types.optimization_public import OptimizationPublic
from ..types.optimization_studio_config_write import OptimizationStudioConfigWrite
from ..types.optimization_studio_log import OptimizationStudioLog
from ..types.optimization_write_status import OptimizationWriteStatus
from .types.optimization_update_status import OptimizationUpdateStatus

# 此值用作可选参数的默认值
OMIT = typing.cast(typing.Any, ...)


class RawOptimizationsClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._client_wrapper = client_wrapper

    def find_optimizations(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        dataset_id: typing.Optional[str] = None,
        name: typing.Optional[str] = None,
        dataset_name: typing.Optional[str] = None,
        dataset_deleted: typing.Optional[bool] = None,
        project_id: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[OptimizationPagePublic]:
        """
        查找优化

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        dataset_id : typing.Optional[str]

        name : typing.Optional[str]

        dataset_name : typing.Optional[str]

        dataset_deleted : typing.Optional[bool]

        project_id : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[OptimizationPagePublic]
            优化资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/optimizations",
            method="GET",
            params={
                "page": page,
                "size": size,
                "dataset_id": dataset_id,
                "name": name,
                "dataset_name": dataset_name,
                "dataset_deleted": dataset_deleted,
                "project_id": project_id,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    OptimizationPagePublic,
                    parse_obj_as(
                        type_=OptimizationPagePublic,  # type: ignore
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

    def create_optimization(
        self,
        *,
        dataset_name: str,
        objective_name: str,
        status: OptimizationWriteStatus,
        id: typing.Optional[str] = OMIT,
        name: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        metadata: typing.Optional[JsonListStringWrite] = OMIT,
        studio_config: typing.Optional[OptimizationStudioConfigWrite] = OMIT,
        error_info: typing.Optional[ErrorInfoWrite] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        创建优化

        Parameters
        ----------
        dataset_name : str

        objective_name : str

        status : OptimizationWriteStatus

        id : typing.Optional[str]

        name : typing.Optional[str]

        project_name : typing.Optional[str]
            项目名称。若项目不存在则创建它。提供 project_id 时此字段被忽略。

        project_id : typing.Optional[str]
            项目 ID。当同时提供两者时，优先级高于 project_name。

        metadata : typing.Optional[JsonListStringWrite]

        studio_config : typing.Optional[OptimizationStudioConfigWrite]

        error_info : typing.Optional[ErrorInfoWrite]

        last_updated_at : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/optimizations",
            method="POST",
            json={
                "id": id,
                "name": name,
                "dataset_name": dataset_name,
                "project_name": project_name,
                "project_id": project_id,
                "objective_name": objective_name,
                "status": status,
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListStringWrite, direction="write"
                ),
                "studio_config": convert_and_respect_annotation_metadata(
                    object_=studio_config, annotation=OptimizationStudioConfigWrite, direction="write"
                ),
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfoWrite, direction="write"
                ),
                "last_updated_at": last_updated_at,
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

    def upsert_optimization(
        self,
        *,
        dataset_name: str,
        objective_name: str,
        status: OptimizationWriteStatus,
        id: typing.Optional[str] = OMIT,
        name: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        metadata: typing.Optional[JsonListStringWrite] = OMIT,
        studio_config: typing.Optional[OptimizationStudioConfigWrite] = OMIT,
        error_info: typing.Optional[ErrorInfoWrite] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        新增或更新优化

        Parameters
        ----------
        dataset_name : str

        objective_name : str

        status : OptimizationWriteStatus

        id : typing.Optional[str]

        name : typing.Optional[str]

        project_name : typing.Optional[str]
            项目名称。若项目不存在则创建它。提供 project_id 时此字段被忽略。

        project_id : typing.Optional[str]
            项目 ID。当同时提供两者时，优先级高于 project_name。

        metadata : typing.Optional[JsonListStringWrite]

        studio_config : typing.Optional[OptimizationStudioConfigWrite]

        error_info : typing.Optional[ErrorInfoWrite]

        last_updated_at : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/optimizations",
            method="PUT",
            json={
                "id": id,
                "name": name,
                "dataset_name": dataset_name,
                "project_name": project_name,
                "project_id": project_id,
                "objective_name": objective_name,
                "status": status,
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListStringWrite, direction="write"
                ),
                "studio_config": convert_and_respect_annotation_metadata(
                    object_=studio_config, annotation=OptimizationStudioConfigWrite, direction="write"
                ),
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfoWrite, direction="write"
                ),
                "last_updated_at": last_updated_at,
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

    def delete_optimizations_by_id(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        按 ID 删除优化

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
            "v1/private/optimizations/delete",
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

    def get_optimization_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[OptimizationPublic]:
        """
        按 ID 获取优化

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[OptimizationPublic]
            优化资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/optimizations/{jsonable_encoder(id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    OptimizationPublic,
                    parse_obj_as(
                        type_=OptimizationPublic,  # type: ignore
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

    def update_optimizations_by_id(
        self,
        id: str,
        *,
        name: typing.Optional[str] = OMIT,
        status: typing.Optional[OptimizationUpdateStatus] = OMIT,
        error_info: typing.Optional[ErrorInfo] = OMIT,
        metadata: typing.Optional[JsonListString] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        按 ID 更新优化

        Parameters
        ----------
        id : str

        name : typing.Optional[str]

        status : typing.Optional[OptimizationUpdateStatus]

        error_info : typing.Optional[ErrorInfo]

        metadata : typing.Optional[JsonListString]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/optimizations/{jsonable_encoder(id)}",
            method="PUT",
            json={
                "name": name,
                "status": status,
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfo, direction="write"
                ),
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListString, direction="write"
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

    def get_studio_optimization_logs(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[OptimizationStudioLog]:
        """
        获取用于下载优化日志的预签名 S3 URL

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[OptimizationStudioLog]
            日志响应
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/optimizations/studio/{jsonable_encoder(id)}/logs",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    OptimizationStudioLog,
                    parse_obj_as(
                        type_=OptimizationStudioLog,  # type: ignore
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


class AsyncRawOptimizationsClient:
    def __init__(self, *, client_wrapper: AsyncClientWrapper):
        self._client_wrapper = client_wrapper

    async def find_optimizations(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        dataset_id: typing.Optional[str] = None,
        name: typing.Optional[str] = None,
        dataset_name: typing.Optional[str] = None,
        dataset_deleted: typing.Optional[bool] = None,
        project_id: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[OptimizationPagePublic]:
        """
        查找优化

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        dataset_id : typing.Optional[str]

        name : typing.Optional[str]

        dataset_name : typing.Optional[str]

        dataset_deleted : typing.Optional[bool]

        project_id : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[OptimizationPagePublic]
            优化资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/optimizations",
            method="GET",
            params={
                "page": page,
                "size": size,
                "dataset_id": dataset_id,
                "name": name,
                "dataset_name": dataset_name,
                "dataset_deleted": dataset_deleted,
                "project_id": project_id,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    OptimizationPagePublic,
                    parse_obj_as(
                        type_=OptimizationPagePublic,  # type: ignore
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

    async def create_optimization(
        self,
        *,
        dataset_name: str,
        objective_name: str,
        status: OptimizationWriteStatus,
        id: typing.Optional[str] = OMIT,
        name: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        metadata: typing.Optional[JsonListStringWrite] = OMIT,
        studio_config: typing.Optional[OptimizationStudioConfigWrite] = OMIT,
        error_info: typing.Optional[ErrorInfoWrite] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        创建优化

        Parameters
        ----------
        dataset_name : str

        objective_name : str

        status : OptimizationWriteStatus

        id : typing.Optional[str]

        name : typing.Optional[str]

        project_name : typing.Optional[str]
            项目名称。若项目不存在则创建它。提供 project_id 时此字段被忽略。

        project_id : typing.Optional[str]
            项目 ID。当同时提供两者时，优先级高于 project_name。

        metadata : typing.Optional[JsonListStringWrite]

        studio_config : typing.Optional[OptimizationStudioConfigWrite]

        error_info : typing.Optional[ErrorInfoWrite]

        last_updated_at : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/optimizations",
            method="POST",
            json={
                "id": id,
                "name": name,
                "dataset_name": dataset_name,
                "project_name": project_name,
                "project_id": project_id,
                "objective_name": objective_name,
                "status": status,
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListStringWrite, direction="write"
                ),
                "studio_config": convert_and_respect_annotation_metadata(
                    object_=studio_config, annotation=OptimizationStudioConfigWrite, direction="write"
                ),
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfoWrite, direction="write"
                ),
                "last_updated_at": last_updated_at,
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

    async def upsert_optimization(
        self,
        *,
        dataset_name: str,
        objective_name: str,
        status: OptimizationWriteStatus,
        id: typing.Optional[str] = OMIT,
        name: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        metadata: typing.Optional[JsonListStringWrite] = OMIT,
        studio_config: typing.Optional[OptimizationStudioConfigWrite] = OMIT,
        error_info: typing.Optional[ErrorInfoWrite] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        新增或更新优化

        Parameters
        ----------
        dataset_name : str

        objective_name : str

        status : OptimizationWriteStatus

        id : typing.Optional[str]

        name : typing.Optional[str]

        project_name : typing.Optional[str]
            项目名称。若项目不存在则创建它。提供 project_id 时此字段被忽略。

        project_id : typing.Optional[str]
            项目 ID。当同时提供两者时，优先级高于 project_name。

        metadata : typing.Optional[JsonListStringWrite]

        studio_config : typing.Optional[OptimizationStudioConfigWrite]

        error_info : typing.Optional[ErrorInfoWrite]

        last_updated_at : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/optimizations",
            method="PUT",
            json={
                "id": id,
                "name": name,
                "dataset_name": dataset_name,
                "project_name": project_name,
                "project_id": project_id,
                "objective_name": objective_name,
                "status": status,
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListStringWrite, direction="write"
                ),
                "studio_config": convert_and_respect_annotation_metadata(
                    object_=studio_config, annotation=OptimizationStudioConfigWrite, direction="write"
                ),
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfoWrite, direction="write"
                ),
                "last_updated_at": last_updated_at,
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

    async def delete_optimizations_by_id(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 删除优化

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
            "v1/private/optimizations/delete",
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

    async def get_optimization_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[OptimizationPublic]:
        """
        按 ID 获取优化

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[OptimizationPublic]
            优化资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/optimizations/{jsonable_encoder(id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    OptimizationPublic,
                    parse_obj_as(
                        type_=OptimizationPublic,  # type: ignore
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

    async def update_optimizations_by_id(
        self,
        id: str,
        *,
        name: typing.Optional[str] = OMIT,
        status: typing.Optional[OptimizationUpdateStatus] = OMIT,
        error_info: typing.Optional[ErrorInfo] = OMIT,
        metadata: typing.Optional[JsonListString] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 更新优化

        Parameters
        ----------
        id : str

        name : typing.Optional[str]

        status : typing.Optional[OptimizationUpdateStatus]

        error_info : typing.Optional[ErrorInfo]

        metadata : typing.Optional[JsonListString]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/optimizations/{jsonable_encoder(id)}",
            method="PUT",
            json={
                "name": name,
                "status": status,
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfo, direction="write"
                ),
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListString, direction="write"
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

    async def get_studio_optimization_logs(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[OptimizationStudioLog]:
        """
        获取用于下载优化日志的预签名 S3 URL

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[OptimizationStudioLog]
            日志响应
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/optimizations/studio/{jsonable_encoder(id)}/logs",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    OptimizationStudioLog,
                    parse_obj_as(
                        type_=OptimizationStudioLog,  # type: ignore
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
