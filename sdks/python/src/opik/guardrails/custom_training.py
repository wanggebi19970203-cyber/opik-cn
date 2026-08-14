import time
from typing import Any, Callable, Dict, List, Optional

import opik.exceptions as exceptions
from opik.api_objects import opik_client

from . import rest_api_client

TRAINING_REQUEST_TIMEOUT_SECONDS = 60


def create_custom_guardrail(
    name: str,
    description: str,
    examples: List[Dict[str, Any]],
    base_model: str = "Qwen/Qwen2.5-1.5B-Instruct",
    epochs: float = 3.0,
    overwrite: bool = False,
    wait: bool = True,
    poll_interval: float = 10.0,
    timeout: float = 3600.0,
    callback: Optional[Callable[[Dict[str, Any]], None]] = None,
) -> Dict[str, Any]:
    """
    在 guardrails 服务器上训练一个自定义二分类 guardrail，并使其可被
    :class:`~opik.guardrails.CustomGuardrail` 防护使用。

    Args:
        name: 模型名称，之后用于引用该 guardrail。
        description: 自然语言描述的评价标准，用于补全“判断其是否……”，
            例如“包含有毒或辱骂性语言”。
        examples: 形如 ``{"text": ..., "label": 0 或 1}`` 的带标签示例，其中 1 表示
            该标准成立（guardrail 应判定为失败）。
        base_model: 用于微调适配器的基础模型。
        epochs: 训练轮数。
        overwrite: 若为 True，则重新训练并替换同名已有 guardrail。
            若为 False（默认），同名 guardrail 已存在时会被拒绝。
        wait: 若为 True，则阻塞直到训练完成并返回最终状态。
        poll_interval: 等待期间每次状态检查之间的间隔秒数。
        timeout: 等待训练完成的最大秒数。
        callback: 可选的函数，在每次轮询时以当前状态调用一次，
            训练期间该状态携带一个 ``progress`` 字典（percent、epoch、train_loss、latest_eval）。
            仅在 ``wait`` 为 True 时使用。

    Returns:
        训练状态。当 ``wait`` 为 True 时包含评估指标。

    Raises:
        opik.exceptions.GuardrailTrainingError: 若训练失败或未在
            ``timeout`` 内完成。
    """
    client = opik_client.get_global_client()
    api_client = rest_api_client.GuardrailsApiClient(
        httpx_client=rest_api_client.build_httpx_client(
            config=client.config, timeout_seconds=TRAINING_REQUEST_TIMEOUT_SECONDS
        ),
        host_url=client.config.guardrails_backend_host,
    )

    result = api_client.train_custom(
        name=name,
        description=description,
        examples=examples,
        base_model=base_model,
        epochs=epochs,
        overwrite=overwrite,
    )

    if not wait:
        return result

    deadline = time.time() + timeout
    while time.time() < deadline:
        status = api_client.get_custom_training_status(name)
        if callback is not None:
            callback(status)
        state = status.get("status")
        if state == "completed":
            return status
        if state == "failed":
            raise exceptions.GuardrailTrainingError(
                f"自定义 guardrail '{name}' 训练失败：{status.get('error')}"
            )
        time.sleep(poll_interval)

    raise exceptions.GuardrailTrainingError(
        f"自定义 guardrail '{name}' 训练未在 {timeout} 秒内完成"
    )
