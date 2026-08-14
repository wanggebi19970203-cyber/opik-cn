import logging
from typing import Any, Optional, Literal

from opik.rest_api import client as rest_api_client
from opik.rest_api import types as rest_api_types
from opik.rest_api.core.api_error import ApiError

LOGGER = logging.getLogger(__name__)


class Optimization:
    def __init__(
        self,
        id: str,
        rest_client: rest_api_client.OpikApi,
        project_name: Optional[str] = None,
    ) -> None:
        self._id = id
        self._rest_client = rest_client
        self._project_name = project_name

    @property
    def id(self) -> str:
        return self._id

    @property
    def project_name(self) -> Optional[str]:
        return self._project_name

    def update(
        self,
        name: Optional[str] = None,
        status: Optional[
            Literal["running", "completed", "cancelled", "initialized", "error"]
        ] = None,
        error_info: Optional[dict] = None,
    ) -> None:
        LOGGER.debug(
            f"正在更新优化 {self.id}，名称为 {name}，状态为 {status}"
        )
        # 仅在提供 error_info 时才转发它；传入 None 会序列化为显式的 null，
        # 并可能在后续的非错误更新中覆盖之前持久化的原因。
        extra = {"error_info": error_info} if error_info is not None else {}
        try:
            self._rest_client.optimizations.update_optimizations_by_id(
                id=self.id,
                name=name,
                status=status,
                **extra,
            )
        except TypeError:
            # 较旧的已安装 opik，其带类型的 ``update_optimizations_by_id``
            # 早于 ``error_info`` 字段，会以 TypeError 拒绝该 kwarg。
            # 回退到 SDK 预先配置的 httpx 客户端（接受 snake_case 字段，忽略未知字段），
            # 这样更新 —— 尤其是错误路径上的 ``status`` 转换 —— 仍然能够落地，
            # 而不是在尝试记录失败时抛出异常并让运行卡住。
            # 这镜像了 python-backend worker 的 status_manager 回退。
            # 一旦 SDK 提供了 ``error_info``，此分支将永远不会被执行。
            if not extra:
                raise
            LOGGER.debug(
                "已安装的 opik SDK 缺少 'error_info' 更新字段；"
                "改为通过原始 REST 客户端发送优化更新。"
            )
            body: dict = {"error_info": error_info}
            if name is not None:
                body["name"] = name
            if status is not None:
                body["status"] = status
            response = self._rest_client.optimizations._raw_client._client_wrapper.httpx_client.request(
                f"v1/private/optimizations/{self.id}",
                method="PUT",
                json=body,
            )
            if response.status_code >= 300:
                # 保留生成客户端的错误契约：执行 `except ApiError` /
                # 检查 status_code 的调用方必须看到与带类型路径相同的
                # 异常类型，而不是 raise_for_status() 泄漏出来的
                # httpx.HTTPStatusError。
                error_body: Any
                try:
                    error_body = response.json()
                except Exception:
                    error_body = response.text
                raise ApiError(
                    status_code=response.status_code,
                    headers=dict(response.headers),
                    body=error_body,
                )

    def fetch_content(self) -> rest_api_types.OptimizationPublic:
        LOGGER.debug(f"正在获取优化数据 {self.id}")
        return self._rest_client.optimizations.get_optimization_by_id(id=self.id)
