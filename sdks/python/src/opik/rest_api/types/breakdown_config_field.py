# 此文件由 Fern 根据我们的 API 定义自动生成。

import typing

BreakdownConfigField = typing.Union[
    typing.Literal[
        "none", "tags", "metadata", "name", "error_info", "error_type", "model", "provider", "type", "guardrail_name"
    ],
    typing.Any,
]
