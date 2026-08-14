# 此文件由 Fern 根据我们的 API 定义自动生成。

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
from ..errors.conflict_error import ConflictError
from ..errors.internal_server_error import InternalServerError
from ..errors.not_found_error import NotFoundError
from ..errors.unprocessable_entity_error import UnprocessableEntityError
from ..types.alert_page_public import AlertPagePublic
from ..types.breakdown_config_public import BreakdownConfigPublic
from ..types.dashboard_page_public import DashboardPagePublic
from ..types.dataset_page_public import DatasetPagePublic
from ..types.experiment_page_public import ExperimentPagePublic
from ..types.feedback_score_names import FeedbackScoreNames
from ..types.kpi_card_response import KpiCardResponse
from ..types.optimization_page_public import OptimizationPagePublic
from ..types.project_detailed import ProjectDetailed
from ..types.project_metric_response_public import ProjectMetricResponsePublic
from ..types.project_page_public import ProjectPagePublic
from ..types.project_public import ProjectPublic
from ..types.project_stats_summary import ProjectStatsSummary
from ..types.prompt_page_public import PromptPagePublic
from ..types.recent_activity_page_public import RecentActivityPagePublic
from ..types.span_filter_public import SpanFilterPublic
from ..types.token_usage_names import TokenUsageNames
from ..types.trace_filter_public import TraceFilterPublic
from ..types.trace_thread_filter_public import TraceThreadFilterPublic
from .types.kpi_card_request_entity_type import KpiCardRequestEntityType
from .types.project_metric_request_public_interval import ProjectMetricRequestPublicInterval
from .types.project_metric_request_public_metric_type import ProjectMetricRequestPublicMetricType
from .types.project_update_visibility import ProjectUpdateVisibility
from .types.project_write_visibility import ProjectWriteVisibility

# 此值用作可选参数的默认值
OMIT = typing.cast(typing.Any, ...)


class RawProjectsClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._client_wrapper = client_wrapper

    def find_alerts_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[AlertPagePublic]:
        """
        查找限定到某个项目的告警

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[AlertPagePublic]
            告警分页
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/alerts",
            method="GET",
            params={
                "page": page,
                "size": size,
                "sorting": sorting,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    AlertPagePublic,
                    parse_obj_as(
                        type_=AlertPagePublic,  # type: ignore
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

    def find_dashboards_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DashboardPagePublic]:
        """
        查找限定到某个项目的仪表盘

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DashboardPagePublic]
            仪表盘分页
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/dashboards",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "sorting": sorting,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DashboardPagePublic,
                    parse_obj_as(
                        type_=DashboardPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def find_datasets_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        with_experiments_only: typing.Optional[bool] = None,
        with_optimizations_only: typing.Optional[bool] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[DatasetPagePublic]:
        """
        查找限定到某个项目的数据集

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        with_experiments_only : typing.Optional[bool]

        with_optimizations_only : typing.Optional[bool]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[DatasetPagePublic]
            数据集分页
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/datasets",
            method="GET",
            params={
                "page": page,
                "size": size,
                "with_experiments_only": with_experiments_only,
                "with_optimizations_only": with_optimizations_only,
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

    def find_experiments_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        dataset_id: typing.Optional[str] = None,
        optimization_id: typing.Optional[str] = None,
        types: typing.Optional[str] = None,
        name: typing.Optional[str] = None,
        dataset_deleted: typing.Optional[bool] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        experiment_ids: typing.Optional[str] = None,
        force_sorting: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[ExperimentPagePublic]:
        """
        查找限定到某个项目的实验

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        dataset_id : typing.Optional[str]

        optimization_id : typing.Optional[str]

        types : typing.Optional[str]

        name : typing.Optional[str]

        dataset_deleted : typing.Optional[bool]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        experiment_ids : typing.Optional[str]

        force_sorting : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ExperimentPagePublic]
            实验分页
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/experiments",
            method="GET",
            params={
                "page": page,
                "size": size,
                "datasetId": dataset_id,
                "optimization_id": optimization_id,
                "types": types,
                "name": name,
                "dataset_deleted": dataset_deleted,
                "sorting": sorting,
                "filters": filters,
                "experiment_ids": experiment_ids,
                "force_sorting": force_sorting,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ExperimentPagePublic,
                    parse_obj_as(
                        type_=ExperimentPagePublic,  # type: ignore
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

    def find_optimizations_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        dataset_id: typing.Optional[str] = None,
        dataset_name: typing.Optional[str] = None,
        name: typing.Optional[str] = None,
        dataset_deleted: typing.Optional[bool] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[OptimizationPagePublic]:
        """
        查找限定到某个项目的优化

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        dataset_id : typing.Optional[str]

        dataset_name : typing.Optional[str]

        name : typing.Optional[str]

        dataset_deleted : typing.Optional[bool]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[OptimizationPagePublic]
            优化分页
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/optimizations",
            method="GET",
            params={
                "page": page,
                "size": size,
                "dataset_id": dataset_id,
                "dataset_name": dataset_name,
                "name": name,
                "dataset_deleted": dataset_deleted,
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

    def get_prompts_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[PromptPagePublic]:
        """
        获取限定到某个项目的提示词

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[PromptPagePublic]
            OK
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/prompts",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "sorting": sorting,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    PromptPagePublic,
                    parse_obj_as(
                        type_=PromptPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def find_projects(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[ProjectPagePublic]:
        """
        查找项目

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ProjectPagePublic]
            项目资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/projects",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "sorting": sorting,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ProjectPagePublic,
                    parse_obj_as(
                        type_=ProjectPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def create_project(
        self,
        *,
        name: str,
        visibility: typing.Optional[ProjectWriteVisibility] = OMIT,
        description: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        创建项目

        Parameters
        ----------
        name : str

        visibility : typing.Optional[ProjectWriteVisibility]

        description : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/projects",
            method="POST",
            json={
                "name": name,
                "visibility": visibility,
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

    def get_project_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[ProjectPublic]:
        """
        按 ID 获取项目

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ProjectPublic]
            项目资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ProjectPublic,
                    parse_obj_as(
                        type_=ProjectPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def delete_project_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        按 ID 删除项目

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
            f"v1/private/projects/{jsonable_encoder(id)}",
            method="DELETE",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return HttpResponse(response=_response, data=None)
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

    def update_project(
        self,
        id: str,
        *,
        name: typing.Optional[str] = OMIT,
        description: typing.Optional[str] = OMIT,
        visibility: typing.Optional[ProjectUpdateVisibility] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[None]:
        """
        按 ID 更新项目

        Parameters
        ----------
        id : str

        name : typing.Optional[str]

        description : typing.Optional[str]

        visibility : typing.Optional[ProjectUpdateVisibility]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[None]
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}",
            method="PATCH",
            json={
                "name": name,
                "description": description,
                "visibility": visibility,
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

    def delete_projects_batch(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[None]:
        """
        批量删除项目

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
            "v1/private/projects/delete",
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

    def find_feedback_score_names_by_project_ids(
        self, *, project_ids: typing.Optional[str] = None, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[FeedbackScoreNames]:
        """
        按项目 ID 查找反馈评分名称

        Parameters
        ----------
        project_ids : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[FeedbackScoreNames]
            反馈评分资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/projects/feedback-scores/names",
            method="GET",
            params={
                "project_ids": project_ids,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    FeedbackScoreNames,
                    parse_obj_as(
                        type_=FeedbackScoreNames,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def find_token_usage_names(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> HttpResponse[TokenUsageNames]:
        """
        查找 Token 用量名称

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[TokenUsageNames]
            Token 用量名称资源
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}/token-usage/names",
            method="GET",
            request_options=request_options,
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
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def get_project_kpi_cards(
        self,
        id: str,
        *,
        entity_type: KpiCardRequestEntityType,
        interval_start: dt.datetime,
        filters: typing.Optional[str] = OMIT,
        interval_end: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[KpiCardResponse]:
        """
        获取项目的 KPI 卡片指标

        Parameters
        ----------
        id : str

        entity_type : KpiCardRequestEntityType

        interval_start : dt.datetime

        filters : typing.Optional[str]

        interval_end : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[KpiCardResponse]
            KPI 卡片指标
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}/kpi-cards",
            method="POST",
            json={
                "entity_type": entity_type,
                "filters": filters,
                "interval_start": interval_start,
                "interval_end": interval_end,
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
                    KpiCardResponse,
                    parse_obj_as(
                        type_=KpiCardResponse,  # type: ignore
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

    def get_project_metrics(
        self,
        id: str,
        *,
        metric_type: typing.Optional[ProjectMetricRequestPublicMetricType] = OMIT,
        interval: typing.Optional[ProjectMetricRequestPublicInterval] = OMIT,
        interval_start: typing.Optional[dt.datetime] = OMIT,
        interval_end: typing.Optional[dt.datetime] = OMIT,
        span_filters: typing.Optional[typing.Sequence[SpanFilterPublic]] = OMIT,
        trace_filters: typing.Optional[typing.Sequence[TraceFilterPublic]] = OMIT,
        thread_filters: typing.Optional[typing.Sequence[TraceThreadFilterPublic]] = OMIT,
        breakdown: typing.Optional[BreakdownConfigPublic] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[ProjectMetricResponsePublic]:
        """
        获取项目的指定指标

        Parameters
        ----------
        id : str

        metric_type : typing.Optional[ProjectMetricRequestPublicMetricType]

        interval : typing.Optional[ProjectMetricRequestPublicInterval]

        interval_start : typing.Optional[dt.datetime]

        interval_end : typing.Optional[dt.datetime]

        span_filters : typing.Optional[typing.Sequence[SpanFilterPublic]]

        trace_filters : typing.Optional[typing.Sequence[TraceFilterPublic]]

        thread_filters : typing.Optional[typing.Sequence[TraceThreadFilterPublic]]

        breakdown : typing.Optional[BreakdownConfigPublic]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ProjectMetricResponsePublic]
            项目指标
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}/metrics",
            method="POST",
            json={
                "metric_type": metric_type,
                "interval": interval,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "span_filters": convert_and_respect_annotation_metadata(
                    object_=span_filters, annotation=typing.Sequence[SpanFilterPublic], direction="write"
                ),
                "trace_filters": convert_and_respect_annotation_metadata(
                    object_=trace_filters, annotation=typing.Sequence[TraceFilterPublic], direction="write"
                ),
                "thread_filters": convert_and_respect_annotation_metadata(
                    object_=thread_filters, annotation=typing.Sequence[TraceThreadFilterPublic], direction="write"
                ),
                "breakdown": convert_and_respect_annotation_metadata(
                    object_=breakdown, annotation=BreakdownConfigPublic, direction="write"
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
                _data = typing.cast(
                    ProjectMetricResponsePublic,
                    parse_obj_as(
                        type_=ProjectMetricResponsePublic,  # type: ignore
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

    def get_project_stats(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        sorting: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[ProjectStatsSummary]:
        """
        获取项目统计信息

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        filters : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        sorting : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ProjectStatsSummary]
            项目统计信息
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/projects/stats",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "filters": filters,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
                "sorting": sorting,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ProjectStatsSummary,
                    parse_obj_as(
                        type_=ProjectStatsSummary,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return HttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    def retrieve_project(
        self,
        *,
        name: str,
        include_stats: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[ProjectDetailed]:
        """
        获取项目

        Parameters
        ----------
        name : str

        include_stats : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[ProjectDetailed]
            项目资源
        """
        _response = self._client_wrapper.httpx_client.request(
            "v1/private/projects/retrieve",
            method="POST",
            json={
                "name": name,
                "includeStats": include_stats,
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
                    ProjectDetailed,
                    parse_obj_as(
                        type_=ProjectDetailed,  # type: ignore
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

    def get_recent_activity(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> HttpResponse[RecentActivityPagePublic]:
        """
        返回某个项目所有实体类型中最新的活动条目，按日期降序排序。

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        HttpResponse[RecentActivityPagePublic]
            最近活动分页
        """
        _response = self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/activities",
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
                    RecentActivityPagePublic,
                    parse_obj_as(
                        type_=RecentActivityPagePublic,  # type: ignore
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
            if _response.status_code == 500:
                raise InternalServerError(
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


class AsyncRawProjectsClient:
    def __init__(self, *, client_wrapper: AsyncClientWrapper):
        self._client_wrapper = client_wrapper

    async def find_alerts_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[AlertPagePublic]:
        """
        查找限定到某个项目的告警

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[AlertPagePublic]
            告警分页
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/alerts",
            method="GET",
            params={
                "page": page,
                "size": size,
                "sorting": sorting,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    AlertPagePublic,
                    parse_obj_as(
                        type_=AlertPagePublic,  # type: ignore
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

    async def find_dashboards_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DashboardPagePublic]:
        """
        查找限定到某个项目的仪表盘

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DashboardPagePublic]
            仪表盘分页
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/dashboards",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "sorting": sorting,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    DashboardPagePublic,
                    parse_obj_as(
                        type_=DashboardPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def find_datasets_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        with_experiments_only: typing.Optional[bool] = None,
        with_optimizations_only: typing.Optional[bool] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[DatasetPagePublic]:
        """
        查找限定到某个项目的数据集

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        with_experiments_only : typing.Optional[bool]

        with_optimizations_only : typing.Optional[bool]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[DatasetPagePublic]
            数据集分页
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/datasets",
            method="GET",
            params={
                "page": page,
                "size": size,
                "with_experiments_only": with_experiments_only,
                "with_optimizations_only": with_optimizations_only,
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

    async def find_experiments_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        dataset_id: typing.Optional[str] = None,
        optimization_id: typing.Optional[str] = None,
        types: typing.Optional[str] = None,
        name: typing.Optional[str] = None,
        dataset_deleted: typing.Optional[bool] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        experiment_ids: typing.Optional[str] = None,
        force_sorting: typing.Optional[bool] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[ExperimentPagePublic]:
        """
        查找限定到某个项目的实验

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        dataset_id : typing.Optional[str]

        optimization_id : typing.Optional[str]

        types : typing.Optional[str]

        name : typing.Optional[str]

        dataset_deleted : typing.Optional[bool]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        experiment_ids : typing.Optional[str]

        force_sorting : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ExperimentPagePublic]
            实验分页
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/experiments",
            method="GET",
            params={
                "page": page,
                "size": size,
                "datasetId": dataset_id,
                "optimization_id": optimization_id,
                "types": types,
                "name": name,
                "dataset_deleted": dataset_deleted,
                "sorting": sorting,
                "filters": filters,
                "experiment_ids": experiment_ids,
                "force_sorting": force_sorting,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ExperimentPagePublic,
                    parse_obj_as(
                        type_=ExperimentPagePublic,  # type: ignore
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

    async def find_optimizations_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        dataset_id: typing.Optional[str] = None,
        dataset_name: typing.Optional[str] = None,
        name: typing.Optional[str] = None,
        dataset_deleted: typing.Optional[bool] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[OptimizationPagePublic]:
        """
        查找限定到某个项目的优化

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        dataset_id : typing.Optional[str]

        dataset_name : typing.Optional[str]

        name : typing.Optional[str]

        dataset_deleted : typing.Optional[bool]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[OptimizationPagePublic]
            优化分页
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/optimizations",
            method="GET",
            params={
                "page": page,
                "size": size,
                "dataset_id": dataset_id,
                "dataset_name": dataset_name,
                "name": name,
                "dataset_deleted": dataset_deleted,
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

    async def get_prompts_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[PromptPagePublic]:
        """
        获取限定到某个项目的提示词

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        filters : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[PromptPagePublic]
            OK
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/prompts",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "sorting": sorting,
                "filters": filters,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    PromptPagePublic,
                    parse_obj_as(
                        type_=PromptPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def find_projects(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[ProjectPagePublic]:
        """
        查找项目

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        sorting : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ProjectPagePublic]
            项目资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/projects",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "sorting": sorting,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ProjectPagePublic,
                    parse_obj_as(
                        type_=ProjectPagePublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def create_project(
        self,
        *,
        name: str,
        visibility: typing.Optional[ProjectWriteVisibility] = OMIT,
        description: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        创建项目

        Parameters
        ----------
        name : str

        visibility : typing.Optional[ProjectWriteVisibility]

        description : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/projects",
            method="POST",
            json={
                "name": name,
                "visibility": visibility,
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

    async def get_project_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[ProjectPublic]:
        """
        按 ID 获取项目

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ProjectPublic]
            项目资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}",
            method="GET",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ProjectPublic,
                    parse_obj_as(
                        type_=ProjectPublic,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def delete_project_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 删除项目

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
            f"v1/private/projects/{jsonable_encoder(id)}",
            method="DELETE",
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                return AsyncHttpResponse(response=_response, data=None)
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

    async def update_project(
        self,
        id: str,
        *,
        name: typing.Optional[str] = OMIT,
        description: typing.Optional[str] = OMIT,
        visibility: typing.Optional[ProjectUpdateVisibility] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[None]:
        """
        按 ID 更新项目

        Parameters
        ----------
        id : str

        name : typing.Optional[str]

        description : typing.Optional[str]

        visibility : typing.Optional[ProjectUpdateVisibility]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[None]
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}",
            method="PATCH",
            json={
                "name": name,
                "description": description,
                "visibility": visibility,
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

    async def delete_projects_batch(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[None]:
        """
        批量删除项目

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
            "v1/private/projects/delete",
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

    async def find_feedback_score_names_by_project_ids(
        self, *, project_ids: typing.Optional[str] = None, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[FeedbackScoreNames]:
        """
        按项目 ID 查找反馈评分名称

        Parameters
        ----------
        project_ids : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[FeedbackScoreNames]
            反馈评分资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/projects/feedback-scores/names",
            method="GET",
            params={
                "project_ids": project_ids,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    FeedbackScoreNames,
                    parse_obj_as(
                        type_=FeedbackScoreNames,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def find_token_usage_names(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> AsyncHttpResponse[TokenUsageNames]:
        """
        查找 Token 用量名称

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[TokenUsageNames]
            Token 用量名称资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}/token-usage/names",
            method="GET",
            request_options=request_options,
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
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def get_project_kpi_cards(
        self,
        id: str,
        *,
        entity_type: KpiCardRequestEntityType,
        interval_start: dt.datetime,
        filters: typing.Optional[str] = OMIT,
        interval_end: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[KpiCardResponse]:
        """
        获取项目的 KPI 卡片指标

        Parameters
        ----------
        id : str

        entity_type : KpiCardRequestEntityType

        interval_start : dt.datetime

        filters : typing.Optional[str]

        interval_end : typing.Optional[dt.datetime]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[KpiCardResponse]
            KPI 卡片指标
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}/kpi-cards",
            method="POST",
            json={
                "entity_type": entity_type,
                "filters": filters,
                "interval_start": interval_start,
                "interval_end": interval_end,
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
                    KpiCardResponse,
                    parse_obj_as(
                        type_=KpiCardResponse,  # type: ignore
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

    async def get_project_metrics(
        self,
        id: str,
        *,
        metric_type: typing.Optional[ProjectMetricRequestPublicMetricType] = OMIT,
        interval: typing.Optional[ProjectMetricRequestPublicInterval] = OMIT,
        interval_start: typing.Optional[dt.datetime] = OMIT,
        interval_end: typing.Optional[dt.datetime] = OMIT,
        span_filters: typing.Optional[typing.Sequence[SpanFilterPublic]] = OMIT,
        trace_filters: typing.Optional[typing.Sequence[TraceFilterPublic]] = OMIT,
        thread_filters: typing.Optional[typing.Sequence[TraceThreadFilterPublic]] = OMIT,
        breakdown: typing.Optional[BreakdownConfigPublic] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[ProjectMetricResponsePublic]:
        """
        获取项目的指定指标

        Parameters
        ----------
        id : str

        metric_type : typing.Optional[ProjectMetricRequestPublicMetricType]

        interval : typing.Optional[ProjectMetricRequestPublicInterval]

        interval_start : typing.Optional[dt.datetime]

        interval_end : typing.Optional[dt.datetime]

        span_filters : typing.Optional[typing.Sequence[SpanFilterPublic]]

        trace_filters : typing.Optional[typing.Sequence[TraceFilterPublic]]

        thread_filters : typing.Optional[typing.Sequence[TraceThreadFilterPublic]]

        breakdown : typing.Optional[BreakdownConfigPublic]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ProjectMetricResponsePublic]
            项目指标
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(id)}/metrics",
            method="POST",
            json={
                "metric_type": metric_type,
                "interval": interval,
                "interval_start": interval_start,
                "interval_end": interval_end,
                "span_filters": convert_and_respect_annotation_metadata(
                    object_=span_filters, annotation=typing.Sequence[SpanFilterPublic], direction="write"
                ),
                "trace_filters": convert_and_respect_annotation_metadata(
                    object_=trace_filters, annotation=typing.Sequence[TraceFilterPublic], direction="write"
                ),
                "thread_filters": convert_and_respect_annotation_metadata(
                    object_=thread_filters, annotation=typing.Sequence[TraceThreadFilterPublic], direction="write"
                ),
                "breakdown": convert_and_respect_annotation_metadata(
                    object_=breakdown, annotation=BreakdownConfigPublic, direction="write"
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
                _data = typing.cast(
                    ProjectMetricResponsePublic,
                    parse_obj_as(
                        type_=ProjectMetricResponsePublic,  # type: ignore
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

    async def get_project_stats(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        from_time: typing.Optional[dt.datetime] = None,
        to_time: typing.Optional[dt.datetime] = None,
        sorting: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[ProjectStatsSummary]:
        """
        获取项目统计信息

        Parameters
        ----------
        page : typing.Optional[int]

        size : typing.Optional[int]

        name : typing.Optional[str]

        filters : typing.Optional[str]

        from_time : typing.Optional[dt.datetime]

        to_time : typing.Optional[dt.datetime]

        sorting : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ProjectStatsSummary]
            项目统计信息
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/projects/stats",
            method="GET",
            params={
                "page": page,
                "size": size,
                "name": name,
                "filters": filters,
                "from_time": serialize_datetime(from_time) if from_time is not None else None,
                "to_time": serialize_datetime(to_time) if to_time is not None else None,
                "sorting": sorting,
            },
            request_options=request_options,
        )
        try:
            if 200 <= _response.status_code < 300:
                _data = typing.cast(
                    ProjectStatsSummary,
                    parse_obj_as(
                        type_=ProjectStatsSummary,  # type: ignore
                        object_=_response.json(),
                    ),
                )
                return AsyncHttpResponse(response=_response, data=_data)
            _response_json = _response.json()
        except JSONDecodeError:
            raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response.text)
        raise ApiError(status_code=_response.status_code, headers=dict(_response.headers), body=_response_json)

    async def retrieve_project(
        self,
        *,
        name: str,
        include_stats: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[ProjectDetailed]:
        """
        获取项目

        Parameters
        ----------
        name : str

        include_stats : typing.Optional[bool]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[ProjectDetailed]
            项目资源
        """
        _response = await self._client_wrapper.httpx_client.request(
            "v1/private/projects/retrieve",
            method="POST",
            json={
                "name": name,
                "includeStats": include_stats,
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
                    ProjectDetailed,
                    parse_obj_as(
                        type_=ProjectDetailed,  # type: ignore
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

    async def get_recent_activity(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AsyncHttpResponse[RecentActivityPagePublic]:
        """
        返回某个项目所有实体类型中最新的活动条目，按日期降序排序。

        Parameters
        ----------
        project_id : str

        page : typing.Optional[int]

        size : typing.Optional[int]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        AsyncHttpResponse[RecentActivityPagePublic]
            最近活动分页
        """
        _response = await self._client_wrapper.httpx_client.request(
            f"v1/private/projects/{jsonable_encoder(project_id)}/activities",
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
                    RecentActivityPagePublic,
                    parse_obj_as(
                        type_=RecentActivityPagePublic,  # type: ignore
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
            if _response.status_code == 500:
                raise InternalServerError(
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
