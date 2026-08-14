# 此文件由 Fern 根据我们的 API 定义自动生成。

import datetime as dt
import typing

from ..core.client_wrapper import AsyncClientWrapper, SyncClientWrapper
from ..core.request_options import RequestOptions
from ..types.error_info import ErrorInfo
from ..types.error_info_write import ErrorInfoWrite
from ..types.json_list_string import JsonListString
from ..types.json_list_string_write import JsonListStringWrite
from ..types.optimization_page_public import OptimizationPagePublic
from ..types.optimization_public import OptimizationPublic
from ..types.optimization_studio_config_write import OptimizationStudioConfigWrite
from ..types.optimization_studio_log import OptimizationStudioLog
from ..types.optimization_write_status import OptimizationWriteStatus
from .raw_client import AsyncRawOptimizationsClient, RawOptimizationsClient
from .types.optimization_update_status import OptimizationUpdateStatus

# 此值用作可选参数的默认值
OMIT = typing.cast(typing.Any, ...)


class OptimizationsClient:
    def __init__(self, *, client_wrapper: SyncClientWrapper):
        self._raw_client = RawOptimizationsClient(client_wrapper=client_wrapper)

    @property
    def with_raw_response(self) -> RawOptimizationsClient:
        """
        获取此客户端的原始实现，该实现返回原始响应。

        Returns
        -------
        RawOptimizationsClient
        """
        return self._raw_client

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
    ) -> OptimizationPagePublic:
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
        OptimizationPagePublic
            优化资源

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.optimizations.find_optimizations()
        """
        _response = self._raw_client.find_optimizations(
            page=page,
            size=size,
            dataset_id=dataset_id,
            name=name,
            dataset_name=dataset_name,
            dataset_deleted=dataset_deleted,
            project_id=project_id,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

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
    ) -> None:
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
        None

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.optimizations.create_optimization(dataset_name='dataset_name', objective_name='objective_name', status="running", )
        """
        _response = self._raw_client.create_optimization(
            dataset_name=dataset_name,
            objective_name=objective_name,
            status=status,
            id=id,
            name=name,
            project_name=project_name,
            project_id=project_id,
            metadata=metadata,
            studio_config=studio_config,
            error_info=error_info,
            last_updated_at=last_updated_at,
            request_options=request_options,
        )
        return _response.data

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
    ) -> None:
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
        None

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.optimizations.upsert_optimization(dataset_name='dataset_name', objective_name='objective_name', status="running", )
        """
        _response = self._raw_client.upsert_optimization(
            dataset_name=dataset_name,
            objective_name=objective_name,
            status=status,
            id=id,
            name=name,
            project_name=project_name,
            project_id=project_id,
            metadata=metadata,
            studio_config=studio_config,
            error_info=error_info,
            last_updated_at=last_updated_at,
            request_options=request_options,
        )
        return _response.data

    def delete_optimizations_by_id(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> None:
        """
        按 ID 删除优化

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
        client.optimizations.delete_optimizations_by_id(ids=['ids'], )
        """
        _response = self._raw_client.delete_optimizations_by_id(ids=ids, request_options=request_options)
        return _response.data

    def get_optimization_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> OptimizationPublic:
        """
        按 ID 获取优化

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        OptimizationPublic
            优化资源

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.optimizations.get_optimization_by_id(id='id', )
        """
        _response = self._raw_client.get_optimization_by_id(id, request_options=request_options)
        return _response.data

    def update_optimizations_by_id(
        self,
        id: str,
        *,
        name: typing.Optional[str] = OMIT,
        status: typing.Optional[OptimizationUpdateStatus] = OMIT,
        error_info: typing.Optional[ErrorInfo] = OMIT,
        metadata: typing.Optional[JsonListString] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
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
        None

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.optimizations.update_optimizations_by_id(id='id', )
        """
        _response = self._raw_client.update_optimizations_by_id(
            id, name=name, status=status, error_info=error_info, metadata=metadata, request_options=request_options
        )
        return _response.data

    def get_studio_optimization_logs(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> OptimizationStudioLog:
        """
        获取用于下载优化日志的预签名 S3 URL

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        OptimizationStudioLog
            日志响应

        Examples
        --------
        from Opik import OpikApi
        client = OpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        client.optimizations.get_studio_optimization_logs(id='id', )
        """
        _response = self._raw_client.get_studio_optimization_logs(id, request_options=request_options)
        return _response.data


class AsyncOptimizationsClient:
    def __init__(self, *, client_wrapper: AsyncClientWrapper):
        self._raw_client = AsyncRawOptimizationsClient(client_wrapper=client_wrapper)

    @property
    def with_raw_response(self) -> AsyncRawOptimizationsClient:
        """
        获取此客户端的原始实现，该实现返回原始响应。

        Returns
        -------
        AsyncRawOptimizationsClient
        """
        return self._raw_client

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
    ) -> OptimizationPagePublic:
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
        OptimizationPagePublic
            优化资源

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.optimizations.find_optimizations()
        asyncio.run(main())
        """
        _response = await self._raw_client.find_optimizations(
            page=page,
            size=size,
            dataset_id=dataset_id,
            name=name,
            dataset_name=dataset_name,
            dataset_deleted=dataset_deleted,
            project_id=project_id,
            filters=filters,
            request_options=request_options,
        )
        return _response.data

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
    ) -> None:
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
        None

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.optimizations.create_optimization(dataset_name='dataset_name', objective_name='objective_name', status="running", )
        asyncio.run(main())
        """
        _response = await self._raw_client.create_optimization(
            dataset_name=dataset_name,
            objective_name=objective_name,
            status=status,
            id=id,
            name=name,
            project_name=project_name,
            project_id=project_id,
            metadata=metadata,
            studio_config=studio_config,
            error_info=error_info,
            last_updated_at=last_updated_at,
            request_options=request_options,
        )
        return _response.data

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
    ) -> None:
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
        None

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.optimizations.upsert_optimization(dataset_name='dataset_name', objective_name='objective_name', status="running", )
        asyncio.run(main())
        """
        _response = await self._raw_client.upsert_optimization(
            dataset_name=dataset_name,
            objective_name=objective_name,
            status=status,
            id=id,
            name=name,
            project_name=project_name,
            project_id=project_id,
            metadata=metadata,
            studio_config=studio_config,
            error_info=error_info,
            last_updated_at=last_updated_at,
            request_options=request_options,
        )
        return _response.data

    async def delete_optimizations_by_id(
        self, *, ids: typing.Sequence[str], request_options: typing.Optional[RequestOptions] = None
    ) -> None:
        """
        按 ID 删除优化

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
            await client.optimizations.delete_optimizations_by_id(ids=['ids'], )
        asyncio.run(main())
        """
        _response = await self._raw_client.delete_optimizations_by_id(ids=ids, request_options=request_options)
        return _response.data

    async def get_optimization_by_id(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> OptimizationPublic:
        """
        按 ID 获取优化

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        OptimizationPublic
            优化资源

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.optimizations.get_optimization_by_id(id='id', )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_optimization_by_id(id, request_options=request_options)
        return _response.data

    async def update_optimizations_by_id(
        self,
        id: str,
        *,
        name: typing.Optional[str] = OMIT,
        status: typing.Optional[OptimizationUpdateStatus] = OMIT,
        error_info: typing.Optional[ErrorInfo] = OMIT,
        metadata: typing.Optional[JsonListString] = OMIT,
        request_options: typing.Optional[RequestOptions] = None,
    ) -> None:
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
        None

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.optimizations.update_optimizations_by_id(id='id', )
        asyncio.run(main())
        """
        _response = await self._raw_client.update_optimizations_by_id(
            id, name=name, status=status, error_info=error_info, metadata=metadata, request_options=request_options
        )
        return _response.data

    async def get_studio_optimization_logs(
        self, id: str, *, request_options: typing.Optional[RequestOptions] = None
    ) -> OptimizationStudioLog:
        """
        获取用于下载优化日志的预签名 S3 URL

        Parameters
        ----------
        id : str

        request_options : typing.Optional[RequestOptions]
            请求的特定配置。

        Returns
        -------
        OptimizationStudioLog
            日志响应

        Examples
        --------
        from Opik import AsyncOpikApi
        import asyncio
        client = AsyncOpikApi(api_key="YOUR_API_KEY", workspace_name="YOUR_WORKSPACE_NAME", )
        async def main() -> None:
            await client.optimizations.get_studio_optimization_logs(id='id', )
        asyncio.run(main())
        """
        _response = await self._raw_client.get_studio_optimization_logs(id, request_options=request_options)
        return _response.data
