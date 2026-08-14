# 此文件由 Fern 根据我们的 API 定义自动生成。

import typing

import pydantic
from ..core.pydantic_utilities import IS_PYDANTIC_V2, UniversalBaseModel
from .breakdown_config_field import BreakdownConfigField


class BreakdownConfig(UniversalBaseModel):
    field: typing.Optional[BreakdownConfigField] = None
    metadata_key: typing.Optional[str] = None
    sub_metric: typing.Optional[str] = None

    if IS_PYDANTIC_V2:
        model_config: typing.ClassVar[pydantic.ConfigDict] = pydantic.ConfigDict(extra="allow", frozen=True)  # type: ignore # Pydantic v2
    else:

        class Config:
            frozen = True
            smart_union = True
            extra = pydantic.Extra.allow
