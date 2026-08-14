# 此文件由 Fern 根据我们的 API 定义自动生成。

import contextlib
import datetime as dt
import typing
from json.decoder import JSONDecodeError

from ..core.api_error import ApiError
from ..core.client_wrapper import AsyncClientWrapper, SyncClientWrapper
from ..core.datetime_utils import serialize_datetime
from ..core.http_response import AsyncHttpResponse, HttpResponse
from ..core.jsonable_encoder import jsonable_encoder
from ..core.pydantic_utilities import parse_obj_as
from ..core.request_options import RequestOptions
from ..core.serialization import convert_and_respect_annotation_metadata
from ..errors.bad_request_error import BadRequestError
from ..errors.not_found_error import NotFoundError
from ..errors.unauthorized_error import UnauthorizedError
from ..errors.unprocessable_entity_error import UnprocessableEntityError
from ..types.comment import Comment
from ..types.error_info import ErrorInfo
from ..types.error_info_write import ErrorInfoWrite
from ..types.existence_response import ExistenceResponse
from ..types.feedback_score_batch_item import FeedbackScoreBatchItem
from ..types.feedback_score_batch_item_thread import FeedbackScoreBatchItemThread
from ..types.feedback_score_names_public import FeedbackScoreNamesPublic
from ..types.feedback_score_source import FeedbackScoreSource
from ..types.json_list_string import JsonListString
from ..types.json_list_string_write import JsonListStringWrite
from ..types.project_stats_public import ProjectStatsPublic
from ..types.trace_filter_public import TraceFilterPublic
from ..types.trace_page_public import TracePagePublic
from ..types.trace_public import TracePublic
from ..types.trace_thread import TraceThread
from ..types.trace_thread_filter import TraceThreadFilter
from ..types.trace_thread_page import TraceThreadPage
from ..types.trace_thread_update import TraceThreadUpdate
from ..types.trace_update import TraceUpdate
from ..types.trace_update_source import TraceUpdateSource
from ..types.trace_write import TraceWrite
from ..types.trace_write_source import TraceWriteSource
from ..types.value_entry import ValueEntry
from .types.trace_search_stream_request_public_exclude_item import TraceSearchStreamRequestPublicExcludeItem

# 此值用作可选参数的默认值
OMIT = typing.cast(typing.Any, ...)


class RawTracesClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._client_wrapper = client_wrapper

    def add_thread_comment(
        self,
        id_: str,
        *,
        text: str,
        id: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        添加线程评论

        Parameters
        ----------
        id_ : str

        text : str

        id : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/threads/{jsonable_encoder(id_)}/comments",
            method="POST",
            json={
                "id": id,
                "text": text,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
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

    def add_trace_comment(
        self,
        id_: str,
        *,
        text: str,
        id: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        添加 trace 评论

        Parameters
        ----------
        id_ : str

        text : str

        id : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id_)}/comments",
            method="POST",
            json={
                "id": id,
                "text": text,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
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

    def add_trace_feedback_score(
        self,
        id: str,
        *,
        name: str,
        value: float,
        source: FeedbackScoreSource,
        category_name: typing.Optional[str] = OMIT,
        reason: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        value_by_author: typing.Optional[typing.Dict[str, ValueEntry]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        添加 trace 反馈评分

        Parameters
        ----------
        id : str

        name : str

        value : float

        source : FeedbackScoreSource

        category_name : typing.Optional[str]

        reason : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        value_by_author : typing.Optional[typing.Dict[str, ValueEntry]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id)}/feedback-scores",
            method="PUT",
            json={
                "name": name,
                "category_name": category_name,
                "value": value,
                "reason": reason,
                "source": source,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
                "value_by_author": convert_and_respect_annotation_metadata(
                    object_=value_by_author, annotation=typing.Dict[str, ValueEntry], direction="write"
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

    def create_traces(
        self, *, traces: typing.Sequence[TraceWrite], request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        创建 traces

        Parameters
        ----------
        traces : typing.Sequence[TraceWrite]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/batch",
            method="POST",
            json={
                "traces": convert_and_respect_annotation_metadata(
                    object_=traces, annotation=typing.Sequence[TraceWrite], direction="write"
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

    def batch_update_traces(
        self,
        *,
        ids: typing.Sequence[str],
        update: TraceUpdate,
        merge_tags: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        更新多个 traces

        Parameters
        ----------
        ids : typing.Sequence[str]
            要更新的 trace ID 列表（最多 1000 个）

        update : TraceUpdate

        merge_tags : typing.Optional[bool]
            若为 true，则将标签与现有标签合并而不是替换。默认值：false

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/batch",
            method="PATCH",
            json={
                "ids": ids,
                "update": convert_and_respect_annotation_metadata(
                    object_=update, annotation=TraceUpdate, direction="write"
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

    def batch_update_threads(
        self,
        *,
        ids: typing.Sequence[str],
        update: TraceThreadUpdate,
        merge_tags: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        更新多个线程

        Parameters
        ----------
        ids : typing.Sequence[str]
            要更新的线程模型 ID 列表（最多 1000 个）

        update : TraceThreadUpdate

        merge_tags : typing.Optional[bool]
            若为 true，则将标签与现有标签合并而不是替换。默认值：false

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/batch",
            method="PATCH",
            json={
                "ids": ids,
                "update": convert_and_respect_annotation_metadata(
                    object_=update, annotation=TraceThreadUpdate, direction="write"
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

    def close_trace_thread(
        self,
        *,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        thread_id: typing.Optional[str] = OMIT,
        thread_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        关闭一个或多个 trace 线程。支持单个 thread_id 和用于批量操作的多个 thread_ids。

        Parameters
        ----------
        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        thread_id : typing.Optional[str]

        thread_ids : typing.Optional[typing.Sequence[str]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/close",
            method="PUT",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "thread_id": thread_id,
                "thread_ids": thread_ids,
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

    def get_traces_by_project(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        project_name: typing.Optional[str] = None,
        project_id: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        truncate: typing.Optional[bool] = None,
        strip_attachments: typing.Optional[bool] = None,
        sorting: typing.Optional[str] = None,
        exclude: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        annotation_queue_id: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[TracePagePublic]:
        """
        按 project_name 或 project_id 获取 traces

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        filters : typing.Optional[str]

        truncate : typing.Optional[bool]

        strip_attachments : typing.Optional[bool]

        sorting : typing.Optional[str]

        exclude : typing.Optional[str]

        search : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        annotation_queue_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[TracePagePublic]
            trace 资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces",
            method="GET",
            params={
                "page": page,
                "size": size,
                "project_name": project_name,
                "project_id": project_id,
                "filters": filters,
                "truncate": truncate,
                "strip_attachments": strip_attachments,
                "sorting": sorting,
                "exclude": exclude,
                "search": search,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
                "annotation_queue_id": annotation_queue_id,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    TracePagePublic,
                    parse_obj_as(
                        type_=TracePagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def create_trace(
        self,
        *,
        start_time: dt.datetime,
        id: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        name: typing.Optional[str] = OMIT,
        end_time: typing.Optional[dt.datetime] = OMIT,
        input: typing.Optional[JsonListStringWrite] = OMIT,
        output: typing.Optional[JsonListStringWrite] = OMIT,
        metadata: typing.Optional[JsonListStringWrite] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        error_info: typing.Optional[ErrorInfoWrite] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        ttft: typing.Optional[float] = OMIT,
        thread_id: typing.Optional[str] = OMIT,
        source: typing.Optional[TraceWriteSource] = OMIT,
        environment: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        获取 trace

        Parameters
        ----------
        start_time : dt.datetime

        id : typing.Optional[str]

        project_name : typing.Optional[str]
            若为 null，则使用默认项目

        name : typing.Optional[str]

        end_time : typing.Optional[dt.datetime]

        input : typing.Optional[JsonListStringWrite]

        output : typing.Optional[JsonListStringWrite]

        metadata : typing.Optional[JsonListStringWrite]

        tags : typing.Optional[typing.Sequence[str]]

        error_info : typing.Optional[ErrorInfoWrite]

        last_updated_at : typing.Optional[dt.datetime]

        ttft : typing.Optional[float]
            到首个 token 的时间（毫秒）

        thread_id : typing.Optional[str]

        source : typing.Optional[TraceWriteSource]

        environment : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces",
            method="POST",
            json={
                "id": id,
                "project_name": project_name,
                "name": name,
                "start_time": start_time,
                "end_time": end_time,
                "input": convert_and_respect_annotation_metadata(
                    object_=input, annotation=JsonListStringWrite, direction="write"
                ),
                "output": convert_and_respect_annotation_metadata(
                    object_=output, annotation=JsonListStringWrite, direction="write"
                ),
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListStringWrite, direction="write"
                ),
                "tags": tags,
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfoWrite, direction="write"
                ),
                "last_updated_at": last_updated_at,
                "ttft": ttft,
                "thread_id": thread_id,
                "source": source,
                "environment": environment,
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

    def get_trace_by_id(
        self,
        id: str,
        *,
        strip_attachments: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[TracePublic]:
        """
        按 ID 获取 trace

        Parameters
        ----------
        id : str

        strip_attachments : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[TracePublic]
            trace 资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id)}",
            method="GET",
            params={
                "strip_attachments": strip_attachments,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    TracePublic,
                    parse_obj_as(
                        type_=TracePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def delete_trace_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        按 ID 删除 trace

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
            f"v1/private/traces/{jsonable_encoder(id)}",
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

    def update_trace(
        self,
        id: str,
        *,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        name: typing.Optional[str] = OMIT,
        end_time: typing.Optional[dt.datetime] = OMIT,
        input: typing.Optional[JsonListString] = OMIT,
        output: typing.Optional[JsonListString] = OMIT,
        metadata: typing.Optional[JsonListString] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        tags_to_add: typing.Optional[typing.Sequence[str]] = OMIT,
        tags_to_remove: typing.Optional[typing.Sequence[str]] = OMIT,
        error_info: typing.Optional[ErrorInfo] = OMIT,
        thread_id: typing.Optional[str] = OMIT,
        ttft: typing.Optional[float] = OMIT,
        source: typing.Optional[TraceUpdateSource] = OMIT,
        environment: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        按 ID 更新 trace

        Parameters
        ----------
        id : str

        project_name : typing.Optional[str]
            若为 null 且未指定 project_id，则假定为默认项目

        project_id : typing.Optional[str]
            若为 null 且未指定 project_name，则假定为默认项目

        name : typing.Optional[str]

        end_time : typing.Optional[dt.datetime]

        input : typing.Optional[JsonListString]

        output : typing.Optional[JsonListString]

        metadata : typing.Optional[JsonListString]

        tags : typing.Optional[typing.Sequence[str]]
            标签

        tags_to_add : typing.Optional[typing.Sequence[str]]
            要添加的标签

        tags_to_remove : typing.Optional[typing.Sequence[str]]
            要移除的标签

        error_info : typing.Optional[ErrorInfo]

        thread_id : typing.Optional[str]

        ttft : typing.Optional[float]

        source : typing.Optional[TraceUpdateSource]

        environment : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id)}",
            method="PATCH",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "name": name,
                "end_time": end_time,
                "input": convert_and_respect_annotation_metadata(
                    object_=input, annotation=JsonListString, direction="write"
                ),
                "output": convert_and_respect_annotation_metadata(
                    object_=output, annotation=JsonListString, direction="write"
                ),
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListString, direction="write"
                ),
                "tags": tags,
                "tags_to_add": tags_to_add,
                "tags_to_remove": tags_to_remove,
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfo, direction="write"
                ),
                "thread_id": thread_id,
                "ttft": ttft,
                "source": source,
                "environment": environment,
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

    def delete_thread_comments(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        删除线程评论

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
            "v1/private/traces/threads/comments/delete",
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

    def delete_thread_feedback_scores(
        self,
        *,
        project_name: str,
        thread_id: str,
        names: typing.Sequence[str],
        author: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        删除线程反馈评分

        Parameters
        ----------
        project_name : str

        thread_id : str

        names : typing.Sequence[str]

        author : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/feedback-scores/delete",
            method="POST",
            json={
                "project_name": project_name,
                "thread_id": thread_id,
                "names": names,
                "author": author,
                "source_queue_id": source_queue_id,
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

    def delete_trace_comments(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        删除 trace 评论

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
            "v1/private/traces/comments/delete",
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

    def delete_trace_feedback_score(
        self,
        id: str,
        *,
        name: str,
        author: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        删除 trace 反馈评分

        Parameters
        ----------
        id : str

        name : str

        author : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id)}/feedback-scores/delete",
            method="POST",
            json={
                "name": name,
                "author": author,
                "source_queue_id": source_queue_id,
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

    def delete_trace_threads(
        self,
        *,
        thread_ids: typing.Sequence[str],
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        删除 trace 线程

        Parameters
        ----------
        thread_ids : typing.Sequence[str]

        project_name : typing.Optional[str]
            若为 null，则必须提供 project_id

        project_id : typing.Optional[str]
            若为 null，则必须提供 project_name

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/delete",
            method="POST",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "thread_ids": thread_ids,
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

    def delete_traces(
        self,
        *,
        ids: typing.Sequence[str],
        project_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        删除 traces

        Parameters
        ----------
        ids : typing.Sequence[str]
            要删除的 traces 的 ID

        project_id : typing.Optional[str]
            可选。将删除范围限定到该项目。省略时，会自动解析每个 trace 所属的项目，并按完整键删除该 trace，因此无需知道 trace 的项目即可删除它。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/delete",
            method="POST",
            json={
                "ids": ids,
                "project_id": project_id,
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
            if _response.status_code == 422:
                raise UnprocessableEntityError(
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

    def find_feedback_score_names2(
        self, *, project_id: typing.Optional[str] = None, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[FeedbackScoreNamesPublic]:
        """
        查找反馈评分名称

        Parameters
        ----------
        project_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[FeedbackScoreNamesPublic]
            反馈评分资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/feedback-scores/names",
            method="GET",
            params={
                "project_id": project_id,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    FeedbackScoreNamesPublic,
                    parse_obj_as(
                        type_=FeedbackScoreNamesPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def find_trace_threads_feedback_score_names(
        self, *, project_id: typing.Optional[str] = None, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[FeedbackScoreNamesPublic]:
        """
        查找 trace 线程反馈评分名称

        Parameters
        ----------
        project_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[FeedbackScoreNamesPublic]
            查找 trace 线程反馈评分名称
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/feedback-scores/names",
            method="GET",
            params={
                "project_id": project_id,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    FeedbackScoreNamesPublic,
                    parse_obj_as(
                        type_=FeedbackScoreNamesPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_trace_stats(
        self,
        *,
        project_id: typing.Optional[str] = None,
        project_name: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[ProjectStatsPublic]:
        """
        获取 trace 统计信息

        Parameters
        ----------
        project_id : typing.Optional[str]

        project_name : typing.Optional[str]

        filters : typing.Optional[str]

        search : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ProjectStatsPublic]
            trace 统计信息资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/stats",
            method="GET",
            params={
                "project_id": project_id,
                "project_name": project_name,
                "filters": filters,
                "search": search,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
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

    def get_thread_comment(
        self, thread_id: str, comment_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[Comment]:
        """
        获取线程评论

        Parameters
        ----------
        thread_id : str

        comment_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[Comment]
            评论资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/threads/{jsonable_encoder(thread_id)}/comments/{jsonable_encoder(comment_id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    Comment,
                    parse_obj_as(
                        type_=Comment,  # type: ignore
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

    def get_trace_thread_stats(
        self,
        *,
        project_id: typing.Optional[str] = None,
        project_name: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[ProjectStatsPublic]:
        """
        获取 trace 线程统计信息

        Parameters
        ----------
        project_id : typing.Optional[str]

        project_name : typing.Optional[str]

        filters : typing.Optional[str]

        search : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ProjectStatsPublic]
            trace 线程统计信息资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/stats",
            method="GET",
            params={
                "project_id": project_id,
                "project_name": project_name,
                "filters": filters,
                "search": search,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
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

    def get_trace_comment(
        self, trace_id: str, comment_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[Comment]:
        """
        获取 trace 评论

        Parameters
        ----------
        trace_id : str

        comment_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[Comment]
            评论资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(trace_id)}/comments/{jsonable_encoder(comment_id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    Comment,
                    parse_obj_as(
                        type_=Comment,  # type: ignore
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

    def get_trace_thread(
        self,
        *,
        thread_id: str,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        truncate: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[TraceThread]:
        """
        获取 trace 线程

        Parameters
        ----------
        thread_id : str

        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        truncate : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[TraceThread]
            trace 线程资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/retrieve",
            method="POST",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "thread_id": thread_id,
                "truncate": truncate,
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
                    TraceThread,
                    parse_obj_as(
                        type_=TraceThread,  # type: ignore
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

    def get_trace_threads(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        project_name: typing.Optional[str] = None,
        project_id: typing.Optional[str] = None,
        truncate: typing.Optional[bool] = None,
        strip_attachments: typing.Optional[bool] = None,
        filters: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        annotation_queue_id: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[TraceThreadPage]:
        """
        获取 trace 线程

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        truncate : typing.Optional[bool]

        strip_attachments : typing.Optional[bool]

        filters : typing.Optional[str]

        sorting : typing.Optional[str]

        search : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        annotation_queue_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[TraceThreadPage]
            trace 线程资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads",
            method="GET",
            params={
                "page": page,
                "size": size,
                "project_name": project_name,
                "project_id": project_id,
                "truncate": truncate,
                "strip_attachments": strip_attachments,
                "filters": filters,
                "sorting": sorting,
                "search": search,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
                "annotation_queue_id": annotation_queue_id,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    TraceThreadPage,
                    parse_obj_as(
                        type_=TraceThreadPage,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def open_trace_thread(
        self,
        *,
        thread_id: str,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        truncate: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        打开 trace 线程

        Parameters
        ----------
        thread_id : str

        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        truncate : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/open",
            method="PUT",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "thread_id": thread_id,
                "truncate": truncate,
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

    def score_batch_of_threads(
        self,
        *,
        scores: typing.Sequence[FeedbackScoreBatchItemThread],
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        批量给线程打分

        Parameters
        ----------
        scores : typing.Sequence[FeedbackScoreBatchItemThread]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/feedback-scores",
            method="PUT",
            json={
                "scores": convert_and_respect_annotation_metadata(
                    object_=scores, annotation=typing.Sequence[FeedbackScoreBatchItemThread], direction="write"
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

    def score_batch_of_traces(
        self,
        *,
        scores: typing.Sequence[FeedbackScoreBatchItem],
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        批量给 traces 打分

        Parameters
        ----------
        scores : typing.Sequence[FeedbackScoreBatchItem]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/feedback-scores",
            method="PUT",
            json={
                "scores": convert_and_respect_annotation_metadata(
                    object_=scores, annotation=typing.Sequence[FeedbackScoreBatchItem], direction="write"
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

    @contextlib.contextmanager
    def search_trace_threads(
        self,
        *,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        filters: typing.Optional[typing.Sequence[TraceThreadFilter]] = OMIT,
        last_retrieved_thread_model_id: typing.Optional[str] = OMIT,
        limit: typing.Optional[int] = OMIT,
        truncate: typing.Optional[bool] = OMIT,
        strip_attachments: typing.Optional[bool] = OMIT,
        from_time: typing.Optional[dt.datetime] = OMIT,
        to_time: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> typing.Iterator[HttpResponse[typing.Iterator[bytes]]]:
        """
        搜索 trace 线程

        Parameters
        ----------
        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        filters : typing.Optional[typing.Sequence[TraceThreadFilter]]

        last_retrieved_thread_model_id : typing.Optional[str]

        limit : typing.Optional[int]
            要流式传输的 trace 线程最大数量

        truncate : typing.Optional[bool]
            截断 input、output 和 metadata 以精简负载

        strip_attachments : typing.Optional[bool]
            若为 true，则返回诸如 [file.png] 的附件引用；若为 false，则下载并重新注入被剥离的附件

        from_time : typing.Optional[dt.datetime]
            过滤从此时间之后创建的 trace 线程（ISO-8601 格式）。

        to_time : typing.Optional[dt.datetime]
            过滤到此时间之前创建的 trace 线程（ISO-8601 格式）。若未提供，默认为当前时间。必须晚于 'from_time'。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。你可以传入诸如 `chunk_size` 等配置来定制请求和响应。

        Returns
        -------
        typing.Iterator[HttpResponse[typing.Iterator[bytes]]]
            处理过程中的 trace 线程流或错误
        """
        with self._client_wrapper.httpx_client.stream(
            "v1/private/traces/threads/search",
            method="POST",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[TraceThreadFilter], direction="write"
                ),
                "last_retrieved_thread_model_id": last_retrieved_thread_model_id,
                "limit": limit,
                "truncate": truncate,
                "strip_attachments": strip_attachments,
                "from_time": from_time,
                "to_time": to_time,
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
                    raise ApiError(
                        status_code=_response.status_code, headers=dict(_response.headers), body=_response.text
                    )
                raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

            yield stream()

    @contextlib.contextmanager
    def search_traces(
        self,
        *,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        filters: typing.Optional[typing.Sequence[TraceFilterPublic]] = OMIT,
        last_retrieved_id: typing.Optional[str] = OMIT,
        limit: typing.Optional[int] = OMIT,
        truncate: typing.Optional[bool] = OMIT,
        strip_attachments: typing.Optional[bool] = OMIT,
        exclude: typing.Optional[typing.Sequence[TraceSearchStreamRequestPublicExcludeItem]] = OMIT,
        from_time: typing.Optional[dt.datetime] = OMIT,
        to_time: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> typing.Iterator[HttpResponse[typing.Iterator[bytes]]]:
        """
        搜索 traces

        Parameters
        ----------
        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        filters : typing.Optional[typing.Sequence[TraceFilterPublic]]

        last_retrieved_id : typing.Optional[str]

        limit : typing.Optional[int]
            要流式传输的 traces 最大数量

        truncate : typing.Optional[bool]
            截断 input、output 和 metadata 以精简负载

        strip_attachments : typing.Optional[bool]
            若为 true，则返回诸如 [file.png] 的附件引用；若为 false，则下载并重新注入被剥离的附件

        exclude : typing.Optional[typing.Sequence[TraceSearchStreamRequestPublicExcludeItem]]
            要从响应中排除的字段

        from_time : typing.Optional[dt.datetime]
            过滤从此时间之后创建的 traces（ISO-8601 格式）。

        to_time : typing.Optional[dt.datetime]
            过滤到此时间之前创建的 traces（ISO-8601 格式）。若未提供，默认为当前时间。必须晚于 'from_time'。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。你可以传入诸如 `chunk_size` 等配置来定制请求和响应。

        Returns
        -------
        typing.Iterator[HttpResponse[typing.Iterator[bytes]]]
            处理过程中的 traces 流或错误
        """
        with self._client_wrapper.httpx_client.stream(
            "v1/private/traces/search",
            method="POST",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[TraceFilterPublic], direction="write"
                ),
                "last_retrieved_id": last_retrieved_id,
                "limit": limit,
                "truncate": truncate,
                "strip_attachments": strip_attachments,
                "exclude": exclude,
                "from_time": from_time,
                "to_time": to_time,
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
                    if _response.status_code == 401:
                        raise UnauthorizedError(
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

    def exist(
        self,
        *,
        project_id: typing.Optional[str] = None,
        project_name: typing.Optional[str] = None,
        source: typing.Optional[str] = None,
        thread_only: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[ExistenceResponse]:
        """
        返回该项目是否至少有一个匹配给定范围的 trace。一种廉价的存在性探测（LIMIT 1），用于驱动空状态决策，而无需扫描或聚合整个项目。

        Parameters
        ----------
        project_id : typing.Optional[str]

        project_name : typing.Optional[str]

        source : typing.Optional[str]

        thread_only : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ExistenceResponse]
            trace 是否存在
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/traces/exists",
            method="GET",
            params={
                "project_id": project_id,
                "project_name": project_name,
                "source": source,
                "thread_only": thread_only,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ExistenceResponse,
                    parse_obj_as(
                        type_=ExistenceResponse,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def update_thread(
        self,
        thread_model_id: str,
        *,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        tags_to_add: typing.Optional[typing.Sequence[str]] = OMIT,
        tags_to_remove: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        更新线程

        Parameters
        ----------
        thread_model_id : str

        tags : typing.Optional[typing.Sequence[str]]

        tags_to_add : typing.Optional[typing.Sequence[str]]

        tags_to_remove : typing.Optional[typing.Sequence[str]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/threads/{jsonable_encoder(thread_model_id)}",
            method="PATCH",
            json={
                "tags": tags,
                "tags_to_add": tags_to_add,
                "tags_to_remove": tags_to_remove,
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

    def update_thread_comment(
        self,
        comment_id: str,
        *,
        text: str,
        id: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        按 ID 更新线程评论

        Parameters
        ----------
        comment_id : str

        text : str

        id : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/threads/comments/{jsonable_encoder(comment_id)}",
            method="PATCH",
            json={
                "id": id,
                "text": text,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
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

    def update_trace_comment(
        self,
        comment_id: str,
        *,
        text: str,
        id: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        按 ID 更新 trace 评论

        Parameters
        ----------
        comment_id : str

        text : str

        id : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/traces/comments/{jsonable_encoder(comment_id)}",
            method="PATCH",
            json={
                "id": id,
                "text": text,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
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


class AsyncRawTracesClient:
    def __init__(self, *, client_wrapper: AsyncClientWrapper):
        self._client_wrapper = client_wrapper

    async def add_thread_comment(
        self,
        id_: str,
        *,
        text: str,
        id: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        添加线程评论

        Parameters
        ----------
        id_ : str

        text : str

        id : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/threads/{jsonable_encoder(id_)}/comments",
            method="POST",
            json={
                "id": id,
                "text": text,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
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

    async def add_trace_comment(
        self,
        id_: str,
        *,
        text: str,
        id: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        添加 trace 评论

        Parameters
        ----------
        id_ : str

        text : str

        id : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id_)}/comments",
            method="POST",
            json={
                "id": id,
                "text": text,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
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

    async def add_trace_feedback_score(
        self,
        id: str,
        *,
        name: str,
        value: float,
        source: FeedbackScoreSource,
        category_name: typing.Optional[str] = OMIT,
        reason: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        value_by_author: typing.Optional[typing.Dict[str, ValueEntry]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        添加 trace 反馈评分

        Parameters
        ----------
        id : str

        name : str

        value : float

        source : FeedbackScoreSource

        category_name : typing.Optional[str]

        reason : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        value_by_author : typing.Optional[typing.Dict[str, ValueEntry]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id)}/feedback-scores",
            method="PUT",
            json={
                "name": name,
                "category_name": category_name,
                "value": value,
                "reason": reason,
                "source": source,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
                "value_by_author": convert_and_respect_annotation_metadata(
                    object_=value_by_author, annotation=typing.Dict[str, ValueEntry], direction="write"
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

    async def create_traces(
        self, *, traces: typing.Sequence[TraceWrite], request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        创建 traces

        Parameters
        ----------
        traces : typing.Sequence[TraceWrite]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/batch",
            method="POST",
            json={
                "traces": convert_and_respect_annotation_metadata(
                    object_=traces, annotation=typing.Sequence[TraceWrite], direction="write"
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

    async def batch_update_traces(
        self,
        *,
        ids: typing.Sequence[str],
        update: TraceUpdate,
        merge_tags: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        更新多个 traces

        Parameters
        ----------
        ids : typing.Sequence[str]
            要更新的 trace ID 列表（最多 1000 个）

        update : TraceUpdate

        merge_tags : typing.Optional[bool]
            若为 true，则将标签与现有标签合并而不是替换。默认值：false

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/batch",
            method="PATCH",
            json={
                "ids": ids,
                "update": convert_and_respect_annotation_metadata(
                    object_=update, annotation=TraceUpdate, direction="write"
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

    async def batch_update_threads(
        self,
        *,
        ids: typing.Sequence[str],
        update: TraceThreadUpdate,
        merge_tags: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        更新多个线程

        Parameters
        ----------
        ids : typing.Sequence[str]
            要更新的线程模型 ID 列表（最多 1000 个）

        update : TraceThreadUpdate

        merge_tags : typing.Optional[bool]
            若为 true，则将标签与现有标签合并而不是替换。默认值：false

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/batch",
            method="PATCH",
            json={
                "ids": ids,
                "update": convert_and_respect_annotation_metadata(
                    object_=update, annotation=TraceThreadUpdate, direction="write"
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

    async def close_trace_thread(
        self,
        *,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        thread_id: typing.Optional[str] = OMIT,
        thread_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        关闭一个或多个 trace 线程。支持单个 thread_id 和用于批量操作的多个 thread_ids。

        Parameters
        ----------
        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        thread_id : typing.Optional[str]

        thread_ids : typing.Optional[typing.Sequence[str]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/close",
            method="PUT",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "thread_id": thread_id,
                "thread_ids": thread_ids,
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

    async def get_traces_by_project(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        project_name: typing.Optional[str] = None,
        project_id: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        truncate: typing.Optional[bool] = None,
        strip_attachments: typing.Optional[bool] = None,
        sorting: typing.Optional[str] = None,
        exclude: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        annotation_queue_id: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[TracePagePublic]:
        """
        按 project_name 或 project_id 获取 traces

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        filters : typing.Optional[str]

        truncate : typing.Optional[bool]

        strip_attachments : typing.Optional[bool]

        sorting : typing.Optional[str]

        exclude : typing.Optional[str]

        search : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        annotation_queue_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[TracePagePublic]
            trace 资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces",
            method="GET",
            params={
                "page": page,
                "size": size,
                "project_name": project_name,
                "project_id": project_id,
                "filters": filters,
                "truncate": truncate,
                "strip_attachments": strip_attachments,
                "sorting": sorting,
                "exclude": exclude,
                "search": search,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
                "annotation_queue_id": annotation_queue_id,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    TracePagePublic,
                    parse_obj_as(
                        type_=TracePagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def create_trace(
        self,
        *,
        start_time: dt.datetime,
        id: typing.Optional[str] = OMIT,
        project_name: typing.Optional[str] = OMIT,
        name: typing.Optional[str] = OMIT,
        end_time: typing.Optional[dt.datetime] = OMIT,
        input: typing.Optional[JsonListStringWrite] = OMIT,
        output: typing.Optional[JsonListStringWrite] = OMIT,
        metadata: typing.Optional[JsonListStringWrite] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        error_info: typing.Optional[ErrorInfoWrite] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        ttft: typing.Optional[float] = OMIT,
        thread_id: typing.Optional[str] = OMIT,
        source: typing.Optional[TraceWriteSource] = OMIT,
        environment: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        获取 trace

        Parameters
        ----------
        start_time : dt.datetime

        id : typing.Optional[str]

        project_name : typing.Optional[str]
            若为 null，则使用默认项目

        name : typing.Optional[str]

        end_time : typing.Optional[dt.datetime]

        input : typing.Optional[JsonListStringWrite]

        output : typing.Optional[JsonListStringWrite]

        metadata : typing.Optional[JsonListStringWrite]

        tags : typing.Optional[typing.Sequence[str]]

        error_info : typing.Optional[ErrorInfoWrite]

        last_updated_at : typing.Optional[dt.datetime]

        ttft : typing.Optional[float]
            到首个 token 的时间（毫秒）

        thread_id : typing.Optional[str]

        source : typing.Optional[TraceWriteSource]

        environment : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces",
            method="POST",
            json={
                "id": id,
                "project_name": project_name,
                "name": name,
                "start_time": start_time,
                "end_time": end_time,
                "input": convert_and_respect_annotation_metadata(
                    object_=input, annotation=JsonListStringWrite, direction="write"
                ),
                "output": convert_and_respect_annotation_metadata(
                    object_=output, annotation=JsonListStringWrite, direction="write"
                ),
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListStringWrite, direction="write"
                ),
                "tags": tags,
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfoWrite, direction="write"
                ),
                "last_updated_at": last_updated_at,
                "ttft": ttft,
                "thread_id": thread_id,
                "source": source,
                "environment": environment,
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

    async def get_trace_by_id(
        self,
        id: str,
        *,
        strip_attachments: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[TracePublic]:
        """
        按 ID 获取 trace

        Parameters
        ----------
        id : str

        strip_attachments : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[TracePublic]
            trace 资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id)}",
            method="GET",
            params={
                "strip_attachments": strip_attachments,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    TracePublic,
                    parse_obj_as(
                        type_=TracePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def delete_trace_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 删除 trace

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
            f"v1/private/traces/{jsonable_encoder(id)}",
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

    async def update_trace(
        self,
        id: str,
        *,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        name: typing.Optional[str] = OMIT,
        end_time: typing.Optional[dt.datetime] = OMIT,
        input: typing.Optional[JsonListString] = OMIT,
        output: typing.Optional[JsonListString] = OMIT,
        metadata: typing.Optional[JsonListString] = OMIT,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        tags_to_add: typing.Optional[typing.Sequence[str]] = OMIT,
        tags_to_remove: typing.Optional[typing.Sequence[str]] = OMIT,
        error_info: typing.Optional[ErrorInfo] = OMIT,
        thread_id: typing.Optional[str] = OMIT,
        ttft: typing.Optional[float] = OMIT,
        source: typing.Optional[TraceUpdateSource] = OMIT,
        environment: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 更新 trace

        Parameters
        ----------
        id : str

        project_name : typing.Optional[str]
            若为 null 且未指定 project_id，则假定为默认项目

        project_id : typing.Optional[str]
            若为 null 且未指定 project_name，则假定为默认项目

        name : typing.Optional[str]

        end_time : typing.Optional[dt.datetime]

        input : typing.Optional[JsonListString]

        output : typing.Optional[JsonListString]

        metadata : typing.Optional[JsonListString]

        tags : typing.Optional[typing.Sequence[str]]
            标签

        tags_to_add : typing.Optional[typing.Sequence[str]]
            要添加的标签

        tags_to_remove : typing.Optional[typing.Sequence[str]]
            要移除的标签

        error_info : typing.Optional[ErrorInfo]

        thread_id : typing.Optional[str]

        ttft : typing.Optional[float]

        source : typing.Optional[TraceUpdateSource]

        environment : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id)}",
            method="PATCH",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "name": name,
                "end_time": end_time,
                "input": convert_and_respect_annotation_metadata(
                    object_=input, annotation=JsonListString, direction="write"
                ),
                "output": convert_and_respect_annotation_metadata(
                    object_=output, annotation=JsonListString, direction="write"
                ),
                "metadata": convert_and_respect_annotation_metadata(
                    object_=metadata, annotation=JsonListString, direction="write"
                ),
                "tags": tags,
                "tags_to_add": tags_to_add,
                "tags_to_remove": tags_to_remove,
                "error_info": convert_and_respect_annotation_metadata(
                    object_=error_info, annotation=ErrorInfo, direction="write"
                ),
                "thread_id": thread_id,
                "ttft": ttft,
                "source": source,
                "environment": environment,
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

    async def delete_thread_comments(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        删除线程评论

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
            "v1/private/traces/threads/comments/delete",
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

    async def delete_thread_feedback_scores(
        self,
        *,
        project_name: str,
        thread_id: str,
        names: typing.Sequence[str],
        author: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        删除线程反馈评分

        Parameters
        ----------
        project_name : str

        thread_id : str

        names : typing.Sequence[str]

        author : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/feedback-scores/delete",
            method="POST",
            json={
                "project_name": project_name,
                "thread_id": thread_id,
                "names": names,
                "author": author,
                "source_queue_id": source_queue_id,
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

    async def delete_trace_comments(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        删除 trace 评论

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
            "v1/private/traces/comments/delete",
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

    async def delete_trace_feedback_score(
        self,
        id: str,
        *,
        name: str,
        author: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        删除 trace 反馈评分

        Parameters
        ----------
        id : str

        name : str

        author : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(id)}/feedback-scores/delete",
            method="POST",
            json={
                "name": name,
                "author": author,
                "source_queue_id": source_queue_id,
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

    async def delete_trace_threads(
        self,
        *,
        thread_ids: typing.Sequence[str],
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        删除 trace 线程

        Parameters
        ----------
        thread_ids : typing.Sequence[str]

        project_name : typing.Optional[str]
            若为 null，则必须提供 project_id

        project_id : typing.Optional[str]
            若为 null，则必须提供 project_name

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/delete",
            method="POST",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "thread_ids": thread_ids,
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

    async def delete_traces(
        self,
        *,
        ids: typing.Sequence[str],
        project_id: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        删除 traces

        Parameters
        ----------
        ids : typing.Sequence[str]
            要删除的 traces 的 ID

        project_id : typing.Optional[str]
            可选。将删除范围限定到该项目。省略时，会自动解析每个 trace 所属的项目，并按完整键删除该 trace，因此无需知道 trace 的项目即可删除它。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/delete",
            method="POST",
            json={
                "ids": ids,
                "project_id": project_id,
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
            if _response.status_code == 422:
                raise UnprocessableEntityError(
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

    async def find_feedback_score_names2(
        self, *, project_id: typing.Optional[str] = None, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[FeedbackScoreNamesPublic]:
        """
        查找反馈评分名称

        Parameters
        ----------
        project_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[FeedbackScoreNamesPublic]
            反馈评分资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/feedback-scores/names",
            method="GET",
            params={
                "project_id": project_id,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    FeedbackScoreNamesPublic,
                    parse_obj_as(
                        type_=FeedbackScoreNamesPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def find_trace_threads_feedback_score_names(
        self, *, project_id: typing.Optional[str] = None, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[FeedbackScoreNamesPublic]:
        """
        查找 trace 线程反馈评分名称

        Parameters
        ----------
        project_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[FeedbackScoreNamesPublic]
            查找 trace 线程反馈评分名称
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/feedback-scores/names",
            method="GET",
            params={
                "project_id": project_id,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    FeedbackScoreNamesPublic,
                    parse_obj_as(
                        type_=FeedbackScoreNamesPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_trace_stats(
        self,
        *,
        project_id: typing.Optional[str] = None,
        project_name: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[ProjectStatsPublic]:
        """
        获取 trace 统计信息

        Parameters
        ----------
        project_id : typing.Optional[str]

        project_name : typing.Optional[str]

        filters : typing.Optional[str]

        search : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ProjectStatsPublic]
            trace 统计信息资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/stats",
            method="GET",
            params={
                "project_id": project_id,
                "project_name": project_name,
                "filters": filters,
                "search": search,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
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

    async def get_thread_comment(
        self, thread_id: str, comment_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[Comment]:
        """
        获取线程评论

        Parameters
        ----------
        thread_id : str

        comment_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[Comment]
            评论资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/threads/{jsonable_encoder(thread_id)}/comments/{jsonable_encoder(comment_id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    Comment,
                    parse_obj_as(
                        type_=Comment,  # type: ignore
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

    async def get_trace_thread_stats(
        self,
        *,
        project_id: typing.Optional[str] = None,
        project_name: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[ProjectStatsPublic]:
        """
        获取 trace 线程统计信息

        Parameters
        ----------
        project_id : typing.Optional[str]

        project_name : typing.Optional[str]

        filters : typing.Optional[str]

        search : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ProjectStatsPublic]
            trace 线程统计信息资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/stats",
            method="GET",
            params={
                "project_id": project_id,
                "project_name": project_name,
                "filters": filters,
                "search": search,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
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

    async def get_trace_comment(
        self, trace_id: str, comment_id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[Comment]:
        """
        获取 trace 评论

        Parameters
        ----------
        trace_id : str

        comment_id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[Comment]
            评论资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/{jsonable_encoder(trace_id)}/comments/{jsonable_encoder(comment_id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    Comment,
                    parse_obj_as(
                        type_=Comment,  # type: ignore
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

    async def get_trace_thread(
        self,
        *,
        thread_id: str,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        truncate: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[TraceThread]:
        """
        获取 trace 线程

        Parameters
        ----------
        thread_id : str

        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        truncate : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[TraceThread]
            trace 线程资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/retrieve",
            method="POST",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "thread_id": thread_id,
                "truncate": truncate,
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
                    TraceThread,
                    parse_obj_as(
                        type_=TraceThread,  # type: ignore
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

    async def get_trace_threads(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        project_name: typing.Optional[str] = None,
        project_id: typing.Optional[str] = None,
        truncate: typing.Optional[bool] = None,
        strip_attachments: typing.Optional[bool] = None,
        filters: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        search: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        annotation_queue_id: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[TraceThreadPage]:
        """
        获取 trace 线程

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        truncate : typing.Optional[bool]

        strip_attachments : typing.Optional[bool]

        filters : typing.Optional[str]

        sorting : typing.Optional[str]

        search : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        annotation_queue_id : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[TraceThreadPage]
            trace 线程资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads",
            method="GET",
            params={
                "page": page,
                "size": size,
                "project_name": project_name,
                "project_id": project_id,
                "truncate": truncate,
                "strip_attachments": strip_attachments,
                "filters": filters,
                "sorting": sorting,
                "search": search,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
                "annotation_queue_id": annotation_queue_id,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    TraceThreadPage,
                    parse_obj_as(
                        type_=TraceThreadPage,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def open_trace_thread(
        self,
        *,
        thread_id: str,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        truncate: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        打开 trace 线程

        Parameters
        ----------
        thread_id : str

        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        truncate : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/open",
            method="PUT",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "thread_id": thread_id,
                "truncate": truncate,
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

    async def score_batch_of_threads(
        self,
        *,
        scores: typing.Sequence[FeedbackScoreBatchItemThread],
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        批量给线程打分

        Parameters
        ----------
        scores : typing.Sequence[FeedbackScoreBatchItemThread]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/threads/feedback-scores",
            method="PUT",
            json={
                "scores": convert_and_respect_annotation_metadata(
                    object_=scores, annotation=typing.Sequence[FeedbackScoreBatchItemThread], direction="write"
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

    async def score_batch_of_traces(
        self,
        *,
        scores: typing.Sequence[FeedbackScoreBatchItem],
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        批量给 traces 打分

        Parameters
        ----------
        scores : typing.Sequence[FeedbackScoreBatchItem]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/feedback-scores",
            method="PUT",
            json={
                "scores": convert_and_respect_annotation_metadata(
                    object_=scores, annotation=typing.Sequence[FeedbackScoreBatchItem], direction="write"
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

    @contextlib.asynccontextmanager
    async def search_trace_threads(
        self,
        *,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        filters: typing.Optional[typing.Sequence[TraceThreadFilter]] = OMIT,
        last_retrieved_thread_model_id: typing.Optional[str] = OMIT,
        limit: typing.Optional[int] = OMIT,
        truncate: typing.Optional[bool] = OMIT,
        strip_attachments: typing.Optional[bool] = OMIT,
        from_time: typing.Optional[dt.datetime] = OMIT,
        to_time: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> typing.AsyncIterator[AsyncHttpResponse[typing.AsyncIterator[bytes]]]:
        """
        搜索 trace 线程

        Parameters
        ----------
        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        filters : typing.Optional[typing.Sequence[TraceThreadFilter]]

        last_retrieved_thread_model_id : typing.Optional[str]

        limit : typing.Optional[int]
            要流式传输的 trace 线程最大数量

        truncate : typing.Optional[bool]
            截断 input、output 和 metadata 以精简负载

        strip_attachments : typing.Optional[bool]
            若为 true，则返回诸如 [file.png] 的附件引用；若为 false，则下载并重新注入被剥离的附件

        from_time : typing.Optional[dt.datetime]
            过滤从此时间之后创建的 trace 线程（ISO-8601 格式）。

        to_time : typing.Optional[dt.datetime]
            过滤到此时间之前创建的 trace 线程（ISO-8601 格式）。若未提供，默认为当前时间。必须晚于 'from_time'。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。你可以传入诸如 `chunk_size` 等配置来定制请求和响应。

        Returns
        -------
        typing.AsyncIterator[AsyncHttpResponse[typing.AsyncIterator[bytes]]]
            处理过程中的 trace 线程流或错误
        """
        async with self._client_wrapper.httpx_client.stream(
            "v1/private/traces/threads/search",
            method="POST",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[TraceThreadFilter], direction="write"
                ),
                "last_retrieved_thread_model_id": last_retrieved_thread_model_id,
                "limit": limit,
                "truncate": truncate,
                "strip_attachments": strip_attachments,
                "from_time": from_time,
                "to_time": to_time,
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
                    raise ApiError(
                        status_code=_response.status_code, headers=dict(_response.headers), body=_response.text
                    )
                raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

            yield await stream()

    @contextlib.asynccontextmanager
    async def search_traces(
        self,
        *,
        project_name: typing.Optional[str] = OMIT,
        project_id: typing.Optional[str] = OMIT,
        filters: typing.Optional[typing.Sequence[TraceFilterPublic]] = OMIT,
        last_retrieved_id: typing.Optional[str] = OMIT,
        limit: typing.Optional[int] = OMIT,
        truncate: typing.Optional[bool] = OMIT,
        strip_attachments: typing.Optional[bool] = OMIT,
        exclude: typing.Optional[typing.Sequence[TraceSearchStreamRequestPublicExcludeItem]] = OMIT,
        from_time: typing.Optional[dt.datetime] = OMIT,
        to_time: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> typing.AsyncIterator[AsyncHttpResponse[typing.AsyncIterator[bytes]]]:
        """
        搜索 traces

        Parameters
        ----------
        project_name : typing.Optional[str]

        project_id : typing.Optional[str]

        filters : typing.Optional[typing.Sequence[TraceFilterPublic]]

        last_retrieved_id : typing.Optional[str]

        limit : typing.Optional[int]
            要流式传输的 traces 最大数量

        truncate : typing.Optional[bool]
            截断 input、output 和 metadata 以精简负载

        strip_attachments : typing.Optional[bool]
            若为 true，则返回诸如 [file.png] 的附件引用；若为 false，则下载并重新注入被剥离的附件

        exclude : typing.Optional[typing.Sequence[TraceSearchStreamRequestPublicExcludeItem]]
            要从响应中排除的字段

        from_time : typing.Optional[dt.datetime]
            过滤从此时间之后创建的 traces（ISO-8601 格式）。

        to_time : typing.Optional[dt.datetime]
            过滤到此时间之前创建的 traces（ISO-8601 格式）。若未提供，默认为当前时间。必须晚于 'from_time'。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。你可以传入诸如 `chunk_size` 等配置来定制请求和响应。

        Returns
        -------
        typing.AsyncIterator[AsyncHttpResponse[typing.AsyncIterator[bytes]]]
            处理过程中的 traces 流或错误
        """
        async with self._client_wrapper.httpx_client.stream(
            "v1/private/traces/search",
            method="POST",
            json={
                "project_name": project_name,
                "project_id": project_id,
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[TraceFilterPublic], direction="write"
                ),
                "last_retrieved_id": last_retrieved_id,
                "limit": limit,
                "truncate": truncate,
                "strip_attachments": strip_attachments,
                "exclude": exclude,
                "from_time": from_time,
                "to_time": to_time,
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
                    if _response.status_code == 401:
                        raise UnauthorizedError(
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

    async def exist(
        self,
        *,
        project_id: typing.Optional[str] = None,
        project_name: typing.Optional[str] = None,
        source: typing.Optional[str] = None,
        thread_only: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[ExistenceResponse]:
        """
        返回该项目是否至少有一个匹配给定范围的 trace。一种廉价的存在性探测（LIMIT 1），用于驱动空状态决策，而无需扫描或聚合整个项目。

        Parameters
        ----------
        project_id : typing.Optional[str]

        project_name : typing.Optional[str]

        source : typing.Optional[str]

        thread_only : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ExistenceResponse]
            trace 是否存在
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/traces/exists",
            method="GET",
            params={
                "project_id": project_id,
                "project_name": project_name,
                "source": source,
                "thread_only": thread_only,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ExistenceResponse,
                    parse_obj_as(
                        type_=ExistenceResponse,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def update_thread(
        self,
        thread_model_id: str,
        *,
        tags: typing.Optional[typing.Sequence[str]] = OMIT,
        tags_to_add: typing.Optional[typing.Sequence[str]] = OMIT,
        tags_to_remove: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        更新线程

        Parameters
        ----------
        thread_model_id : str

        tags : typing.Optional[typing.Sequence[str]]

        tags_to_add : typing.Optional[typing.Sequence[str]]

        tags_to_remove : typing.Optional[typing.Sequence[str]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/threads/{jsonable_encoder(thread_model_id)}",
            method="PATCH",
            json={
                "tags": tags,
                "tags_to_add": tags_to_add,
                "tags_to_remove": tags_to_remove,
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

    async def update_thread_comment(
        self,
        comment_id: str,
        *,
        text: str,
        id: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 更新线程评论

        Parameters
        ----------
        comment_id : str

        text : str

        id : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/threads/comments/{jsonable_encoder(comment_id)}",
            method="PATCH",
            json={
                "id": id,
                "text": text,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
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

    async def update_trace_comment(
        self,
        comment_id: str,
        *,
        text: str,
        id: typing.Optional[str] = OMIT,
        source_queue_id: typing.Optional[str] = OMIT,
        created_at: typing.Optional[dt.datetime] = OMIT,
        last_updated_at: typing.Optional[dt.datetime] = OMIT,
        created_by: typing.Optional[str] = OMIT,
        last_updated_by: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 更新 trace 评论

        Parameters
        ----------
        comment_id : str

        text : str

        id : typing.Optional[str]

        source_queue_id : typing.Optional[str]

        created_at : typing.Optional[dt.datetime]

        last_updated_at : typing.Optional[dt.datetime]

        created_by : typing.Optional[str]

        last_updated_by : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/traces/comments/{jsonable_encoder(comment_id)}",
            method="PATCH",
            json={
                "id": id,
                "text": text,
                "source_queue_id": source_queue_id,
                "created_at": created_at,
                "last_updated_at": last_updated_at,
                "created_by": created_by,
                "last_updated_by": last_updated_by,
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
