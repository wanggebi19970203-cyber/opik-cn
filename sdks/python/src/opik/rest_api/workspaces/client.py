# 此文件由 Fern 根据我们的 API 定义自动生成。

import datetime as dt
import typing

from ..core.client_wrapper import AsyncClientWrapper, SyncClientWrapper
from ..core.request_options import RequestOptions
from ..types.breakdown_config import BreakdownConfig
from ..types.result import Result
from ..types.span_filter import SpanFilter
from ..types.token_usage_names import TokenUsageNames
from ..types.workspace_configuration import WorkspaceConfiguration
from ..types.workspace_metric_response import WorkspaceMetricResponse
from ..types.workspace_metrics_summary_response import WorkspaceMetricsSummaryResponse
from .raw_client import AsyncRawWorkspacesClient, RawWorkspacesClient
from .types.workspace_span_metric_request_interval import WorkspaceSpanMetricRequestInterval
from .types.workspace_span_metric_request_metric_type import WorkspaceSpanMetricRequestMetricType

# 此值用作可选参数的默认值
OMIT = typing.cast(typing.Any, ...)


class WorkspacesClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._raw_client = RawWorkspacesClient(client_wrapper=client_wrapper)

    @property
    def with_raw_response(self) -> RawWorkspacesClient:
        """
        获取此客户端的原始实现，该实现返回原始响应。

        Returns
        -------
        RawWorkspacesClient
        """
        return self._raw_client

    def costs_summary(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> Result:
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
        Result
            工作空间指标

        Examples
        --------
        from Opik import OpikApi
        import datetime
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.workspaces.costs_summary(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), interval_end=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        """
        _response = self._raw_client.costs_summary(
            interval_start=interval_start,
            interval_end=interval_end,
            project_ids=project_ids,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data

    def get_workspace_configuration(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> WorkspaceConfiguration:
        """
        获取工作空间配置

        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        WorkspaceConfiguration
            工作空间配置

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.workspaces.get_workspace_configuration()
        """
        _response = self._raw_client.get_workspace_configuration(request_options=request_options)
        return _response.data

    def upsert_workspace_configuration(
        self,
        *,
        timeout_to_mark_thread_as_inactive: typing.Optional[str] = OMIT,
        truncation_on_tables: typing.Optional[bool] = OMIT,
        color_map: typing.Optional[typing.Dict[str, str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> WorkspaceConfiguration:
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
        WorkspaceConfiguration
            配置已更新

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.workspaces.upsert_workspace_configuration()
        """
        _response = self._raw_client.upsert_workspace_configuration(
            timeout_to_mark_thread_as_inactive=timeout_to_mark_thread_as_inactive,
            truncation_on_tables=truncation_on_tables,
            color_map=color_map,
            request_options=request_options,
        )
        return _response.data

    def delete_workspace_configuration(self, *, request_options: typing.Optional[RequestOptions] = None) -> None:
        """
        删除工作空间配置

        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        None

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.workspaces.delete_workspace_configuration()
        """
        _response = self._raw_client.delete_workspace_configuration(request_options=request_options)
        return _response.data

    def get_cost(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> WorkspaceMetricResponse:
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
        WorkspaceMetricResponse
            按天统计的工作空间成本数据

        Examples
        --------
        from Opik import OpikApi
        import datetime
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.workspaces.get_cost(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), interval_end=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        """
        _response = self._raw_client.get_cost(
            interval_start=interval_start,
            interval_end=interval_end,
            project_ids=project_ids,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data

    def get_metric(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> WorkspaceMetricResponse:
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
        WorkspaceMetricResponse
            按天统计的工作空间指标数据

        Examples
        --------
        from Opik import OpikApi
        import datetime
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.workspaces.get_metric(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), interval_end=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        """
        _response = self._raw_client.get_metric(
            interval_start=interval_start,
            interval_end=interval_end,
            project_ids=project_ids,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data

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
    ) -> WorkspaceMetricResponse:
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
        WorkspaceMetricResponse
            工作空间 span 指标

        Examples
        --------
        from Opik import OpikApi
        import datetime
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.workspaces.get_workspace_span_metric(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        """
        _response = self._raw_client.get_workspace_span_metric(
            interval_start=interval_start,
            project_ids=project_ids,
            metric_type=metric_type,
            interval=interval,
            breakdown=breakdown,
            filters=filters,
            interval_end=interval_end,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data

    def get_workspace_token_usage_names(
        self,
        *,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> TokenUsageNames:
        """
        获取跨工作空间聚合的去重 span token 用量键名称。当 project_ids 为空时，包含工作空间中的所有项目；否则仅包含给定的项目。

        Parameters
        ----------
        project_ids : typing.Optional[typing.Sequence[str]]

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
        client.workspaces.get_workspace_token_usage_names()
        """
        _response = self._raw_client.get_workspace_token_usage_names(
            project_ids=project_ids, request_options=request_options
        )
        return _response.data

    def metrics_summary(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> WorkspaceMetricsSummaryResponse:
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
        WorkspaceMetricsSummaryResponse
            工作空间指标

        Examples
        --------
        from Opik import OpikApi
        import datetime
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.workspaces.metrics_summary(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), interval_end=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        """
        _response = self._raw_client.metrics_summary(
            interval_start=interval_start,
            interval_end=interval_end,
            project_ids=project_ids,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data


class AsyncWorkspacesClient:
    def __init__(self, *, client_wrapper: AsyncClientWrapper):
        self._raw_client = AsyncRawWorkspacesClient(client_wrapper=client_wrapper)

    @property
    def with_raw_response(self) -> AsyncRawWorkspacesClient:
        """
        获取此客户端的原始实现，该实现返回原始响应。

        Returns
        -------
        AsyncRawWorkspacesClient
        """
        return self._raw_client

    async def costs_summary(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> Result:
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
        Result
            工作空间指标

        Examples
        --------
        from Opik import AsyncOpikApi
        import datetime
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.workspaces.costs_summary(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), interval_end=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        asyncio.run(main())
        """
        _response = await self._raw_client.costs_summary(
            interval_start=interval_start,
            interval_end=interval_end,
            project_ids=project_ids,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data

    async def get_workspace_configuration(
        self, *, request_options: typing.Optional[RequestOptions] = None
    ) -> WorkspaceConfiguration:
        """
        获取工作空间配置

        Parameters
        ----------
        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        WorkspaceConfiguration
            工作空间配置

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.workspaces.get_workspace_configuration()
        asyncio.run(main())
        """
        _response = await self._raw_client.get_workspace_configuration(request_options=request_options)
        return _response.data

    async def upsert_workspace_configuration(
        self,
        *,
        timeout_to_mark_thread_as_inactive: typing.Optional[str] = OMIT,
        truncation_on_tables: typing.Optional[bool] = OMIT,
        color_map: typing.Optional[typing.Dict[str, str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> WorkspaceConfiguration:
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
        WorkspaceConfiguration
            配置已更新

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.workspaces.upsert_workspace_configuration()
        asyncio.run(main())
        """
        _response = await self._raw_client.upsert_workspace_configuration(
            timeout_to_mark_thread_as_inactive=timeout_to_mark_thread_as_inactive,
            truncation_on_tables=truncation_on_tables,
            color_map=color_map,
            request_options=request_options,
        )
        return _response.data

    async def delete_workspace_configuration(self, *, request_options: typing.Optional[RequestOptions] = None) -> None:
        """
        删除工作空间配置

        Parameters
        ----------
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
            await client.workspaces.delete_workspace_configuration()
        asyncio.run(main())
        """
        _response = await self._raw_client.delete_workspace_configuration(request_options=request_options)
        return _response.data

    async def get_cost(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> WorkspaceMetricResponse:
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
        WorkspaceMetricResponse
            按天统计的工作空间成本数据

        Examples
        --------
        from Opik import AsyncOpikApi
        import datetime
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.workspaces.get_cost(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), interval_end=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_cost(
            interval_start=interval_start,
            interval_end=interval_end,
            project_ids=project_ids,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data

    async def get_metric(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> WorkspaceMetricResponse:
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
        WorkspaceMetricResponse
            按天统计的工作空间指标数据

        Examples
        --------
        from Opik import AsyncOpikApi
        import datetime
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.workspaces.get_metric(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), interval_end=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_metric(
            interval_start=interval_start,
            interval_end=interval_end,
            project_ids=project_ids,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data

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
    ) -> WorkspaceMetricResponse:
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
        WorkspaceMetricResponse
            工作空间 span 指标

        Examples
        --------
        from Opik import AsyncOpikApi
        import datetime
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.workspaces.get_workspace_span_metric(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_workspace_span_metric(
            interval_start=interval_start,
            project_ids=project_ids,
            metric_type=metric_type,
            interval=interval,
            breakdown=breakdown,
            filters=filters,
            interval_end=interval_end,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data

    async def get_workspace_token_usage_names(
        self,
        *,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> TokenUsageNames:
        """
        获取跨工作空间聚合的去重 span token 用量键名称。当 project_ids 为空时，包含工作空间中的所有项目；否则仅包含给定的项目。

        Parameters
        ----------
        project_ids : typing.Optional[typing.Sequence[str]]

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
            await client.workspaces.get_workspace_token_usage_names()
        asyncio.run(main())
        """
        _response = await self._raw_client.get_workspace_token_usage_names(
            project_ids=project_ids, request_options=request_options
        )
        return _response.data

    async def metrics_summary(
        self,
        *,
        interval_start: dt.datetime,
        interval_end: dt.datetime,
        project_ids: typing.Optional[typing.Sequence[str]] = OMIT,
        start_before_end: typing.Optional[bool] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> WorkspaceMetricsSummaryResponse:
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
        WorkspaceMetricsSummaryResponse
            工作空间指标

        Examples
        --------
        from Opik import AsyncOpikApi
        import datetime
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.workspaces.metrics_summary(interval_start=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), interval_end=datetime.datetime.fromisoformat("2024-01-15 09:30:00+00:00", ), )
        asyncio.run(main())
        """
        _response = await self._raw_client.metrics_summary(
            interval_start=interval_start,
            interval_end=interval_end,
            project_ids=project_ids,
            start_before_end=start_before_end,
            request_options=request_options,
        )
        return _response.data
