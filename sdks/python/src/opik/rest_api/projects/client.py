# 此文件由 Fern 根据我们的 API 定义自动生成。

import datetime as dt
import typing

from ..core.client_wrapper import AsyncClientWrapper, SyncClientWrapper
from ..core.request_options import RequestOptions
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
from .raw_client import AsyncRawProjectsClient, RawProjectsClient
from .types.kpi_card_request_entity_type import KpiCardRequestEntityType
from .types.project_metric_request_public_interval import ProjectMetricRequestPublicInterval
from .types.project_metric_request_public_metric_type import ProjectMetricRequestPublicMetricType
from .types.project_update_visibility import ProjectUpdateVisibility
from .types.project_write_visibility import ProjectWriteVisibility

# 此值用作可选参数的默认值
OMIT = typing.cast(typing.Any, ...)


class ProjectsClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._raw_client = RawProjectsClient(client_wrapper=client_wrapper)

    @property
    def with_raw_response(self) -> RawProjectsClient:
        """
        获取此客户端的原始实现，该实现返回原始响应。

        Returns
        -------
        RawProjectsClient
        """
        return self._raw_client

    def find_alerts_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AlertPagePublic:
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
        AlertPagePublic
            告警分页

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.find_alerts_by_project(project_id='projectId', )
        """
        _response = self._raw_client.find_alerts_by_project(
            project_id, page=page, size=size, sorting=sorting, filters=filters, request_options=request_options
        )
        return _response.data

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
    ) -> DashboardPagePublic:
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
        DashboardPagePublic
            仪表盘分页

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.find_dashboards_by_project(project_id='projectId', )
        """
        _response = self._raw_client.find_dashboards_by_project(
            project_id,
            page=page,
            size=size,
            name=name,
            sorting=sorting,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

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
    ) -> DatasetPagePublic:
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
        DatasetPagePublic
            数据集分页

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.find_datasets_by_project(project_id='projectId', )
        """
        _response = self._raw_client.find_datasets_by_project(
            project_id,
            page=page,
            size=size,
            with_experiments_only=with_experiments_only,
            with_optimizations_only=with_optimizations_only,
            name=name,
            sorting=sorting,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

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
    ) -> ExperimentPagePublic:
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
        ExperimentPagePublic
            实验分页

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.find_experiments_by_project(project_id='projectId', )
        """
        _response = self._raw_client.find_experiments_by_project(
            project_id,
            page=page,
            size=size,
            dataset_id=dataset_id,
            optimization_id=optimization_id,
            types=types,
            name=name,
            dataset_deleted=dataset_deleted,
            sorting=sorting,
            filters=filters,
            experiment_ids=experiment_ids,
            force_sorting=force_sorting,
            request_options=request_options,
        )
        return _response.data

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
    ) -> OptimizationPagePublic:
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
        OptimizationPagePublic
            优化分页

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.find_optimizations_by_project(project_id='projectId', )
        """
        _response = self._raw_client.find_optimizations_by_project(
            project_id,
            page=page,
            size=size,
            dataset_id=dataset_id,
            dataset_name=dataset_name,
            name=name,
            dataset_deleted=dataset_deleted,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

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
    ) -> PromptPagePublic:
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
        PromptPagePublic
            OK

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.get_prompts_by_project(project_id='projectId', )
        """
        _response = self._raw_client.get_prompts_by_project(
            project_id,
            page=page,
            size=size,
            name=name,
            sorting=sorting,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

    def find_projects(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> ProjectPagePublic:
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
        ProjectPagePublic
            项目资源

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.find_projects()
        """
        _response = self._raw_client.find_projects(
            page=page, size=size, name=name, sorting=sorting, request_options=request_options
        )
        return _response.data

    def create_project(
        self,
        *,
        name: str,
        visibility: typing.Optional[ProjectWriteVisibility] = OMIT,
        description: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
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
        None

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.create_project(name='name', )
        """
        _response = self._raw_client.create_project(
            name=name, visibility=visibility, description=description, request_options=request_options
        )
        return _response.data

    def get_project_by_id(self, id: str, *, request_options: typing.Optional[RequestOptions] = None) -> ProjectPublic:
        """
        按 ID 获取项目

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        ProjectPublic
            项目资源

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.get_project_by_id(id='id', )
        """
        _response = self._raw_client.get_project_by_id(id, request_options=request_options)
        return _response.data

    def delete_project_by_id(self, id: str, *, request_options: typing.Optional[RequestOptions] = None) -> None:
        """
        按 ID 删除项目

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        None

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.delete_project_by_id(id='id', )
        """
        _response = self._raw_client.delete_project_by_id(id, request_options=request_options)
        return _response.data

    def update_project(
        self,
        id: str,
        *,
        name: typing.Optional[str] = OMIT,
        description: typing.Optional[str] = OMIT,
        visibility: typing.Optional[ProjectUpdateVisibility] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
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
        None

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.update_project(id='id', )
        """
        _response = self._raw_client.update_project(
            id, name=name, description=description, visibility=visibility, request_options=request_options
        )
        return _response.data

    def delete_projects_batch(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> None:
        """
        批量删除项目

        Parameters
        ----------
        ids : typing.Sequence[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        None

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.delete_projects_batch(ids=['ids'], )
        """
        _response = self._raw_client.delete_projects_batch(ids=ids, request_options=request_options)
        return _response.data

    def find_feedback_score_names_by_project_ids(
        self, *, project_ids: typing.Optional[str] = None, request_options: typing.Optional[RequestOptions] = None
    ) -> FeedbackScoreNames:
        """
        按项目 ID 查找反馈评分名称

        Parameters
        ----------
        project_ids : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        FeedbackScoreNames
            反馈评分资源

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.find_feedback_score_names_by_project_ids()
        """
        _response = self._raw_client.find_feedback_score_names_by_project_ids(
            project_ids=project_ids, request_options=request_options
        )
        return _response.data

    def find_token_usage_names(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> TokenUsageNames:
        """
        查找 Token 用量名称

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        TokenUsageNames
            Token 用量名称资源

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.find_token_usage_names(id='id', )
        """
        _response = self._raw_client.find_token_usage_names(id, request_options=request_options)
        return _response.data

    def get_project_kpi_cards(
        self,
        id: str,
        *,
        entity_type: KpiCardRequestEntityType,
        interval_start: dt.datetime,
        filters: typing.Optional[str] = OMIT,
        interval_end: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> KpiCardResponse:
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
        KpiCardResponse
            KPI 卡片指标

        Examples
        --------
        from Opik import OpikApi
        import datetime
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.get_project_kpi_cards(id='id', entity_type="traces", interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        """
        _response = self._raw_client.get_project_kpi_cards(
            id,
            entity_type=entity_type,
            interval_start=interval_start,
            filters=filters,
            interval_end=interval_end,
            request_options=request_options,
        )
        return _response.data

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
    ) -> ProjectMetricResponsePublic:
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
        ProjectMetricResponsePublic
            项目指标

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.get_project_metrics(id='id', )
        """
        _response = self._raw_client.get_project_metrics(
            id,
            metric_type=metric_type,
            interval=interval,
            interval_start=interval_start,
            interval_end=interval_end,
            span_filters=span_filters,
            trace_filters=trace_filters,
            thread_filters=thread_filters,
            breakdown=breakdown,
            request_options=request_options,
        )
        return _response.data

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
    ) -> ProjectStatsSummary:
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
        ProjectStatsSummary
            项目统计信息

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.get_project_stats()
        """
        _response = self._raw_client.get_project_stats(
            page=page,
            size=size,
            name=name,
            filters=filters,
            from_time=from_time,
            to_time=to_time,
            sorting=sorting,
            request_options=request_options,
        )
        return _response.data

    def retrieve_project(
        self,
        *,
        name: str,
        include_stats: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> ProjectDetailed:
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
        ProjectDetailed
            项目资源

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.retrieve_project(name='name', )
        """
        _response = self._raw_client.retrieve_project(
            name=name, include_stats=include_stats, request_options=request_options
        )
        return _response.data

    def get_recent_activity(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> RecentActivityPagePublic:
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
        RecentActivityPagePublic
            最近活动分页

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.projects.get_recent_activity(project_id='projectId', )
        """
        _response = self._raw_client.get_recent_activity(
            project_id, page=page, size=size, request_options=request_options
        )
        return _response.data


class AsyncProjectsClient:
    def __init__(self, *, client_wrapper: AsyncClientWrapper):
        self._raw_client = AsyncRawProjectsClient(client_wrapper=client_wrapper)

    @property
    def with_raw_response(self) -> AsyncRawProjectsClient:
        """
        获取此客户端的原始实现，该实现返回原始响应。

        Returns
        -------
        AsyncRawProjectsClient
        """
        return self._raw_client

    async def find_alerts_by_project(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        sorting: typing.Optional[str] = None,
        filters: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> AlertPagePublic:
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
        AlertPagePublic
            告警分页

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.find_alerts_by_project(project_id='projectId', )
        asyncio.run(main())
        """
        _response = await self._raw_client.find_alerts_by_project(
            project_id, page=page, size=size, sorting=sorting, filters=filters, request_options=request_options
        )
        return _response.data

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
    ) -> DashboardPagePublic:
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
        DashboardPagePublic
            仪表盘分页

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.find_dashboards_by_project(project_id='projectId', )
        asyncio.run(main())
        """
        _response = await self._raw_client.find_dashboards_by_project(
            project_id,
            page=page,
            size=size,
            name=name,
            sorting=sorting,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

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
    ) -> DatasetPagePublic:
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
        DatasetPagePublic
            数据集分页

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.find_datasets_by_project(project_id='projectId', )
        asyncio.run(main())
        """
        _response = await self._raw_client.find_datasets_by_project(
            project_id,
            page=page,
            size=size,
            with_experiments_only=with_experiments_only,
            with_optimizations_only=with_optimizations_only,
            name=name,
            sorting=sorting,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

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
    ) -> ExperimentPagePublic:
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
        ExperimentPagePublic
            实验分页

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.find_experiments_by_project(project_id='projectId', )
        asyncio.run(main())
        """
        _response = await self._raw_client.find_experiments_by_project(
            project_id,
            page=page,
            size=size,
            dataset_id=dataset_id,
            optimization_id=optimization_id,
            types=types,
            name=name,
            dataset_deleted=dataset_deleted,
            sorting=sorting,
            filters=filters,
            experiment_ids=experiment_ids,
            force_sorting=force_sorting,
            request_options=request_options,
        )
        return _response.data

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
    ) -> OptimizationPagePublic:
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
        OptimizationPagePublic
            优化分页

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.find_optimizations_by_project(project_id='projectId', )
        asyncio.run(main())
        """
        _response = await self._raw_client.find_optimizations_by_project(
            project_id,
            page=page,
            size=size,
            dataset_id=dataset_id,
            dataset_name=dataset_name,
            name=name,
            dataset_deleted=dataset_deleted,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

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
    ) -> PromptPagePublic:
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
        PromptPagePublic
            OK

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.get_prompts_by_project(project_id='projectId', )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_prompts_by_project(
            project_id,
            page=page,
            size=size,
            name=name,
            sorting=sorting,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

    async def find_projects(
        self,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        name: typing.Optional[str] = None,
        sorting: typing.Optional[str] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> ProjectPagePublic:
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
        ProjectPagePublic
            项目资源

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.find_projects()
        asyncio.run(main())
        """
        _response = await self._raw_client.find_projects(
            page=page, size=size, name=name, sorting=sorting, request_options=request_options
        )
        return _response.data

    async def create_project(
        self,
        *,
        name: str,
        visibility: typing.Optional[ProjectWriteVisibility] = OMIT,
        description: typing.Optional[str] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
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
        None

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.create_project(name='name', )
        asyncio.run(main())
        """
        _response = await self._raw_client.create_project(
            name=name, visibility=visibility, description=description, request_options=request_options
        )
        return _response.data

    async def get_project_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> ProjectPublic:
        """
        按 ID 获取项目

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        ProjectPublic
            项目资源

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.get_project_by_id(id='id', )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_project_by_id(id, request_options=request_options)
        return _response.data

    async def delete_project_by_id(self, id: str, *, request_options: typing.Optional[RequestOptions] = None) -> None:
        """
        按 ID 删除项目

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        None

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.delete_project_by_id(id='id', )
        asyncio.run(main())
        """
        _response = await self._raw_client.delete_project_by_id(id, request_options=request_options)
        return _response.data

    async def update_project(
        self,
        id: str,
        *,
        name: typing.Optional[str] = OMIT,
        description: typing.Optional[str] = OMIT,
        visibility: typing.Optional[ProjectUpdateVisibility] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
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
        None

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.update_project(id='id', )
        asyncio.run(main())
        """
        _response = await self._raw_client.update_project(
            id, name=name, description=description, visibility=visibility, request_options=request_options
        )
        return _response.data

    async def delete_projects_batch(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> None:
        """
        批量删除项目

        Parameters
        ----------
        ids : typing.Sequence[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        None

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.delete_projects_batch(ids=['ids'], )
        asyncio.run(main())
        """
        _response = await self._raw_client.delete_projects_batch(ids=ids, request_options=request_options)
        return _response.data

    async def find_feedback_score_names_by_project_ids(
        self, *, project_ids: typing.Optional[str] = None, request_options: typing.Optional[RequestOptions] = None
    ) -> FeedbackScoreNames:
        """
        按项目 ID 查找反馈评分名称

        Parameters
        ----------
        project_ids : typing.Optional[str]

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        FeedbackScoreNames
            反馈评分资源

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.find_feedback_score_names_by_project_ids()
        asyncio.run(main())
        """
        _response = await self._raw_client.find_feedback_score_names_by_project_ids(
            project_ids=project_ids, request_options=request_options
        )
        return _response.data

    async def find_token_usage_names(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> TokenUsageNames:
        """
        查找 Token 用量名称

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        TokenUsageNames
            Token 用量名称资源

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.find_token_usage_names(id='id', )
        asyncio.run(main())
        """
        _response = await self._raw_client.find_token_usage_names(id, request_options=request_options)
        return _response.data

    async def get_project_kpi_cards(
        self,
        id: str,
        *,
        entity_type: KpiCardRequestEntityType,
        interval_start: dt.datetime,
        filters: typing.Optional[str] = OMIT,
        interval_end: typing.Optional[dt.datetime] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> KpiCardResponse:
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
        KpiCardResponse
            KPI 卡片指标

        Examples
        --------
        from Opik import AsyncOpikApi
        import datetime
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.get_project_kpi_cards(id='id', entity_type="traces", interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_project_kpi_cards(
            id,
            entity_type=entity_type,
            interval_start=interval_start,
            filters=filters,
            interval_end=interval_end,
            request_options=request_options,
        )
        return _response.data

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
    ) -> ProjectMetricResponsePublic:
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
        ProjectMetricResponsePublic
            项目指标

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.get_project_metrics(id='id', )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_project_metrics(
            id,
            metric_type=metric_type,
            interval=interval,
            interval_start=interval_start,
            interval_end=interval_end,
            span_filters=span_filters,
            trace_filters=trace_filters,
            thread_filters=thread_filters,
            breakdown=breakdown,
            request_options=request_options,
        )
        return _response.data

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
    ) -> ProjectStatsSummary:
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
        ProjectStatsSummary
            项目统计信息

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.get_project_stats()
        asyncio.run(main())
        """
        _response = await self._raw_client.get_project_stats(
            page=page,
            size=size,
            name=name,
            filters=filters,
            from_time=from_time,
            to_time=to_time,
            sorting=sorting,
            request_options=request_options,
        )
        return _response.data

    async def retrieve_project(
        self,
        *,
        name: str,
        include_stats: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> ProjectDetailed:
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
        ProjectDetailed
            项目资源

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.retrieve_project(name='name', )
        asyncio.run(main())
        """
        _response = await self._raw_client.retrieve_project(
            name=name, include_stats=include_stats, request_options=request_options
        )
        return _response.data

    async def get_recent_activity(
        self,
        project_id: str,
        *,
        page: typing.Optional[int] = None,
        size: typing.Optional[int] = None,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> RecentActivityPagePublic:
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
        RecentActivityPagePublic
            最近活动分页

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.projects.get_recent_activity(project_id='projectId', )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_recent_activity(
            project_id, page=page, size=size, request_options=request_options
        )
        return _response.data
