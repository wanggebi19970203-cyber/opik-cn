# 此文件由 Fern 根据我们的 API 定义自动生成。

import typing

AlertTriggerConfigWriteType = typing.Union[
    typing.Literal[
        "scope:project",
        "threshold:feedback_score",
        "threshold:cost",
        "threshold:latency",
        "threshold:errors",
        "filter:guardrail_type",
    ],
    typing.Any,
]
