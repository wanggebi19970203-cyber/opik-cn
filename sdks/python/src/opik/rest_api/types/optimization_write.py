# 此文件由 Fern 根据我们的 API 定义自动生成。

import datetime as dt
import typing

import pydantic
from ..core.pydantic_utilities import IS_PYDANTIC_V2, UniversalBaseModel
from .error_info_write import ErrorInfoWrite
from .json_list_string_write import JsonListStringWrite
from .optimization_studio_config_write import OptimizationStudioConfigWrite
from .optimization_write_status import OptimizationWriteStatus


class OptimizationWrite(UniversalBaseModel):
    id: typing.Optional[str] = None
    name: typing.Optional[str] = None
    dataset_name: str
    project_name: typing.Optional[str] = pydantic.Field(default=None)
    """
    项目名称。若项目不存在则创建它。提供 project_id 时此字段被忽略。
    """

    project_id: typing.Optional[str] = pydantic.Field(default=None)
    """
    项目 ID。当同时提供两者时，优先级高于 project_name。
    """

    objective_name: str
    status: OptimizationWriteStatus
    metadata: typing.Optional[JsonListStringWrite] = None
    studio_config: typing.Optional[OptimizationStudioConfigWrite] = None
    error_info: typing.Optional[ErrorInfoWrite] = None
    last_updated_at: typing.Optional[dt.datetime] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow
