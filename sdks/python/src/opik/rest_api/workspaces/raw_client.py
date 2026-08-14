# 此文件由 Fern 根据我们的 API 定义自动生成。

import datetime as dt
import typing
from json.decoder import JSONDecodeError

from ..core.api_error import ApiError
from ..core.client_wrapper import AsyncClientWrapper, SyncClientWrapper
from ..core.http_response import AsyncHttpResponse, HttpResponse
from ..core.pydantic_utilities import parse_obj_as
from ..core.request_options import RequestOptions
from ..core.serialization import convert_and_respect_annotation_metadata
from ..errors.bad_request_error import BadRequestError
from ..errors.not_found_error import NotFoundError
from ..errors.unprocessable_entity_error import UnprocessableEntityError
from ..types.breakdown_config import BreakdownConfig
from ..types.result import Result
from ..types.span_filter import SpanFilter
from ..types.token_usage_names import TokenUsageNames
from ..types.workspace_configuration import WorkspaceConfiguration
from ..types.workspace_metric_response import WorkspaceMetricResponse
from ..types.workspace_metrics_summary_response import WorkspaceMetricsSummaryResponse
from .types.workspace_span_metric_request_interval import WorkspaceSpanMetricRequestInterval
from .types.workspace_span_metric_request_metric_type import WorkspaceSpanMetricRequestMetricType

# 此值用作可选参数的默认值
OMIT = typing.cast(typing.Any, ...)


class RawWorkspacesClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._client_wrapper = client_wrapper

    def costs_summary(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[Result]:
        """
        获取成本汇总

        Parameters
        ----------
        interval_start : dt.datetime

        interval_end : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[Result]
            工作空间指标
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/costs/summaries",
            method="POST",
            json={
                "project_ids": project_ids,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    Result,
                    parse_obj_as(
                        type_=Result,  # type: ignore
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

    def get_workspace_configuration(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[WorkspaceConfiguration]:
        """
        获取工作空间配置

        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[WorkspaceConfiguration]
            工作空间配置
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/configurations",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    WorkspaceConfiguration,
                    parse_obj_as(
                        type_=WorkspaceConfiguration,  # type: ignore
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

    def upsert_workspace_configuration(
        self,
        *,
        timeout_to_mark_thread_as_inactive: typing.Optional[str] = OMIT,
        truncation_on_tables: typing.Optional[bool] = OMIT,
        color_map: typing.Optional[typing.Dict[str, str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[WorkspaceConfiguration]:
        """
        新增或更新工作空间配置

        Parameters
        ----------
        timeout_to_mark_thread_as_inactive : typing.Optional[str]
            以 ISO-8601 格式表示的时长（例如 PT30M 表示 30 分钟，PT2H 表示 2 小时，P1D 表示 1 天）。支持的最低精度为秒，请使用秒级或更高精度的时长。此外，允许的最长时长为 7 天。

        truncation_on_tables : typing.Optional[bool]
            启用或禁用表格视图中的数据截断。禁用时，前端将限制分页以防止性能问题。默认值：true（启用截断）。

        color_map : typing.Optional[typing.Dict[str, str]]
            工作空间级别的颜色映射。将标签名称映射到十六进制颜色值（例如 #FF0000）。最多 10000 个条目。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[WorkspaceConfiguration]
            配置已更新
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/configurations",
            method="PUT",
            json={
                "timeout_to_mark_thread_as_inactive": timeout_to_mark_thread_as_inactive,
                "truncation_on_tables": truncation_on_tables,
                "color_map": color_map,
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
                    WorkspaceConfiguration,
                    parse_obj_as(
                        type_=WorkspaceConfiguration,  # type: ignore
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

    def delete_workspace_configuration(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        删除工作空间配置

        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/configurations",
            method="DELETE",
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

    def get_cost(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[WorkspaceMetricResponse]:
        """
        获取每日成本数据

        Parameters
        ----------
        interval_start : dt.datetime

        interval_end : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[WorkspaceMetricResponse]
            按天统计的工作空间成本数据
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/costs",
            method="POST",
            json={
                "project_ids": project_ids,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    WorkspaceMetricResponse,
                    parse_obj_as(
                        type_=WorkspaceMetricResponse,  # type: ignore
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

    def get_metric(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[WorkspaceMetricResponse]:
        """
        获取每日指标数据

        Parameters
        ----------
        interval_start : dt.datetime

        interval_end : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[WorkspaceMetricResponse]
            按天统计的工作空间指标数据
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/metrics",
            method="POST",
            json={
                "project_ids": project_ids,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    WorkspaceMetricResponse,
                    parse_obj_as(
                        type_=WorkspaceMetricResponse,  # type: ignore
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

    def get_workspace_span_metric(
        self,
        *,
        interval_start: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        metric_type: typing.Optional[WorkspaceSpanMetricRequestMetricType] = OMIT,
        interval: typing.Optional[WorkspaceSpanMetricRequestInterval] = OMIT,
        breakdown: typing.Optional[BreakdownConfig] = OMIT,
        filters: typing.Optional[typing.Sequence[SpanFilter]] = OMIT,
        interval_end: typing.Optional[dt.datetime] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[WorkspaceMetricResponse]:
        """
        获取跨工作空间聚合的 span 指标时间序列。当 project_ids 为空时，包含工作空间中的所有项目；否则仅包含给定的项目。

        Parameters
        ----------
        interval_start : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        metric_type : typing.Optional[WorkspaceSpanMetricRequestMetricType]

        interval : typing.Optional[WorkspaceSpanMetricRequestInterval]

        breakdown : typing.Optional[BreakdownConfig]

        filters : typing.Optional[typing.Sequence[SpanFilter]]

        interval_end : typing.Optional[dt.datetime]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[WorkspaceMetricResponse]
            工作空间 span 指标
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/metrics/spans",
            method="POST",
            json={
                "project_ids": project_ids,
                "metric_type": metric_type,
                "interval": interval,
                "breakdown": convert_and_respect_annotation_metadata(
                    object_=breakdown, annotation=BreakdownConfig, direction="write"
                ),
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[SpanFilter], direction="write"
                ),
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    WorkspaceMetricResponse,
                    parse_obj_as(
                        type_=WorkspaceMetricResponse,  # type: ignore
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

    def get_workspace_token_usage_names(
        self,
        *,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[TokenUsageNames]:
        """
        获取跨工作空间聚合的去重 span token 用量键名称。当 project_ids 为空时，包含工作空间中的所有项目；否则仅包含给定的项目。

        Parameters
        ----------
        project_ids : typing.Optional[typing.Sequence[str]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[TokenUsageNames]
            Token 用量名称资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/token-usage/names",
            method="POST",
            json={
                "project_ids": project_ids,
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
                    TokenUsageNames,
                    parse_obj_as(
                        type_=TokenUsageNames,  # type: ignore
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

    def metrics_summary(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[WorkspaceMetricsSummaryResponse]:
        """
        获取指标汇总

        Parameters
        ----------
        interval_start : dt.datetime

        interval_end : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[WorkspaceMetricsSummaryResponse]
            工作空间指标
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/metrics/summaries",
            method="POST",
            json={
                "project_ids": project_ids,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    WorkspaceMetricsSummaryResponse,
                    parse_obj_as(
                        type_=WorkspaceMetricsSummaryResponse,  # type: ignore
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


class AsyncRawWorkspacesClient:
    def __init__(self, *, client_wrapper: AsyncClientWrapper):
        self._client_wrapper = client_wrapper

    async def costs_summary(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[Result]:
        """
        获取成本汇总

        Parameters
        ----------
        interval_start : dt.datetime

        interval_end : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[Result]
            工作空间指标
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/costs/summaries",
            method="POST",
            json={
                "project_ids": project_ids,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    Result,
                    parse_obj_as(
                        type_=Result,  # type: ignore
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

    async def get_workspace_configuration(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[WorkspaceConfiguration]:
        """
        获取工作空间配置

        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[WorkspaceConfiguration]
            工作空间配置
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/configurations",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    WorkspaceConfiguration,
                    parse_obj_as(
                        type_=WorkspaceConfiguration,  # type: ignore
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

    async def upsert_workspace_configuration(
        self,
        *,
        timeout_to_mark_thread_as_inactive: typing.Optional[str] = OMIT,
        truncation_on_tables: typing.Optional[bool] = OMIT,
        color_map: typing.Optional[typing.Dict[str, str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[WorkspaceConfiguration]:
        """
        新增或更新工作空间配置

        Parameters
        ----------
        timeout_to_mark_thread_as_inactive : typing.Optional[str]
            以 ISO-8601 格式表示的时长（例如 PT30M 表示 30 分钟，PT2H 表示 2 小时，P1D 表示 1 天）。支持的最低精度为秒，请使用秒级或更高精度的时长。此外，允许的最长时长为 7 天。

        truncation_on_tables : typing.Optional[bool]
            启用或禁用表格视图中的数据截断。禁用时，前端将限制分页以防止性能问题。默认值：true（启用截断）。

        color_map : typing.Optional[typing.Dict[str, str]]
            工作空间级别的颜色映射。将标签名称映射到十六进制颜色值（例如 #FF0000）。最多 10000 个条目。

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[WorkspaceConfiguration]
            配置已更新
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/configurations",
            method="PUT",
            json={
                "timeout_to_mark_thread_as_inactive": timeout_to_mark_thread_as_inactive,
                "truncation_on_tables": truncation_on_tables,
                "color_map": color_map,
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
                    WorkspaceConfiguration,
                    parse_obj_as(
                        type_=WorkspaceConfiguration,  # type: ignore
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

    async def delete_workspace_configuration(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        删除工作空间配置

        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/configurations",
            method="DELETE",
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

    async def get_cost(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[WorkspaceMetricResponse]:
        """
        获取每日成本数据

        Parameters
        ----------
        interval_start : dt.datetime

        interval_end : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[WorkspaceMetricResponse]
            按天统计的工作空间成本数据
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/costs",
            method="POST",
            json={
                "project_ids": project_ids,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    WorkspaceMetricResponse,
                    parse_obj_as(
                        type_=WorkspaceMetricResponse,  # type: ignore
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

    async def get_metric(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[WorkspaceMetricResponse]:
        """
        获取每日指标数据

        Parameters
        ----------
        interval_start : dt.datetime

        interval_end : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[WorkspaceMetricResponse]
            按天统计的工作空间指标数据
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/metrics",
            method="POST",
            json={
                "project_ids": project_ids,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    WorkspaceMetricResponse,
                    parse_obj_as(
                        type_=WorkspaceMetricResponse,  # type: ignore
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

    async def get_workspace_span_metric(
        self,
        *,
        interval_start: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        metric_type: typing.Optional[WorkspaceSpanMetricRequestMetricType] = OMIT,
        interval: typing.Optional[WorkspaceSpanMetricRequestInterval] = OMIT,
        breakdown: typing.Optional[BreakdownConfig] = OMIT,
        filters: typing.Optional[typing.Sequence[SpanFilter]] = OMIT,
        interval_end: typing.Optional[dt.datetime] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[WorkspaceMetricResponse]:
        """
        获取跨工作空间聚合的 span 指标时间序列。当 project_ids 为空时，包含工作空间中的所有项目；否则仅包含给定的项目。

        Parameters
        ----------
        interval_start : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        metric_type : typing.Optional[WorkspaceSpanMetricRequestMetricType]

        interval : typing.Optional[WorkspaceSpanMetricRequestInterval]

        breakdown : typing.Optional[BreakdownConfig]

        filters : typing.Optional[typing.Sequence[SpanFilter]]

        interval_end : typing.Optional[dt.datetime]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[WorkspaceMetricResponse]
            工作空间 span 指标
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/metrics/spans",
            method="POST",
            json={
                "project_ids": project_ids,
                "metric_type": metric_type,
                "interval": interval,
                "breakdown": convert_and_respect_annotation_metadata(
                    object_=breakdown, annotation=BreakdownConfig, direction="write"
                ),
                "filters": convert_and_respect_annotation_metadata(
                    object_=filters, annotation=typing.Sequence[SpanFilter], direction="write"
                ),
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    WorkspaceMetricResponse,
                    parse_obj_as(
                        type_=WorkspaceMetricResponse,  # type: ignore
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

    async def get_workspace_token_usage_names(
        self,
        *,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[TokenUsageNames]:
        """
        获取跨工作空间聚合的去重 span token 用量键名称。当 project_ids 为空时，包含工作空间中的所有项目；否则仅包含给定的项目。

        Parameters
        ----------
        project_ids : typing.Optional[typing.Sequence[str]]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[TokenUsageNames]
            Token 用量名称资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/token-usage/names",
            method="POST",
            json={
                "project_ids": project_ids,
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
                    TokenUsageNames,
                    parse_obj_as(
                        type_=TokenUsageNames,  # type: ignore
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

    async def metrics_summary(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[WorkspaceMetricsSummaryResponse]:
        """
        获取指标汇总

        Parameters
        ----------
        interval_start : dt.datetime

        interval_end : dt.datetime

        project_ids : typing.Optional[typing.Sequence[str]]

        start_before_end : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[WorkspaceMetricsSummaryResponse]
            工作空间指标
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/workspaces/metrics/summaries",
            method="POST",
            json={
                "project_ids": project_ids,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "start_before_end": start_before_end,
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
                    WorkspaceMetricsSummaryResponse,
                    parse_obj_as(
                        type_=WorkspaceMetricsSummaryResponse,  # type: ignore
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
