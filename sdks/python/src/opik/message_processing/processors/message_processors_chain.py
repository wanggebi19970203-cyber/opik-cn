import logging
from typing import Optional

from opik.file_upload import base_upload_manager
from opik.rest_api import client as rest_api_client

from . import (
    message_processors,
    online_message_processor,
)
from .. import data_loss, permissions
from ..emulation import local_emulator_message_processor
from ..replay import replay_manager


LOGGER = logging.getLogger(__name__)


def create_message_processors_chain(
    rest_client: rest_api_client.OpikApi,
    file_upload_manager: base_upload_manager.BaseFileUploadManager,
    fallback_replay_manager: replay_manager.ReplayManager,
    unauthorized_message_types_registry: permissions.UnauthorizedMessageTypeRegistry,
    data_loss_tracker: data_loss.DataLossTracker,
    max_payload_size_mb: Optional[float] = None,
) -> message_processors.ChainedMessageProcessor:
    """
    通过组合在线处理器和本地模拟器处理器来创建消息处理器链。该链主要用于按顺序处理消息，
    链中的每个处理器都会贡献各自的功能。

    在线处理器使用提供的 REST API 客户端进行初始化。本地模拟器处理器被包含在内，
    但默认处于非激活状态。构建出的链可确保组合化、流线化的处理，并根据评估激活情况
    同时满足在线和本地模拟的需求。

    Args:
        rest_client: 用于配置在线消息处理器的 REST API 客户端实例。
        file_upload_manager: 用于配置在线消息处理器的文件上传管理器实例。
        fallback_replay_manager: 用于配置在线消息处理器的重放管理器实例。
        unauthorized_message_types_registry: 用于配置在线消息处理器的未授权消息类型注册表实例。
        max_payload_size_mb: span **和** trace 的每个对象大小限制（单位 MB），由在线处理器在发送前应用。
            超过限制的 ``input``/``output`` 字段——或两者加起来超过限制——会被替换为截断标记（并记录警告）。
            ``metadata`` 永不被截断，且被排除在测量之外。``None`` 或 ``<= 0`` 的值会禁用检查（不截断）。

    Returns:
        包含在线处理器和本地模拟器处理器的链式消息处理器。
    """
    online = online_message_processor.OpikMessageProcessor(
        rest_client=rest_client,
        file_upload_manager=file_upload_manager,
        fallback_replay_manager=fallback_replay_manager,
        unauthorized_message_types_registry=unauthorized_message_types_registry,
        data_loss_tracker=data_loss_tracker,
        max_payload_size_mb=max_payload_size_mb,
    )
    # 默认不激活——将在评估期间被激活
    local = local_emulator_message_processor.LocalEmulatorMessageProcessor(active=False)

    return message_processors.ChainedMessageProcessor(processors=[online, local])


def toggle_local_emulator_message_processor(
    active: bool, chain: message_processors.ChainedMessageProcessor, reset: bool = True
) -> None:
    """
    切换给定 ChainedMessageProcessor 中本地模拟器消息处理器的状态。该函数根据
    `active` 参数激活或停用处理器，若被激活则重置其状态。如果在链中找不到
    本地模拟器消息处理器，则记录一条警告。

    Args:
        active: 决定是激活还是停用本地模拟器消息处理器。若为 True，则处理器被激活。
        chain: 包含待切换的本地模拟器消息处理器的消息处理器链。
        reset: 决定是否重置本地模拟器消息处理器。可用于在评估前清空本地模拟器的状态；
            也可用于在评估后清理本地模拟器的状态，以释放系统资源（内存）。
    """
    local = chain.get_processor_by_type(
        local_emulator_message_processor.LocalEmulatorMessageProcessor
    )
    if local is None:
        LOGGER.warning("在链中未找到本地模拟器消息处理器。")
        return

    # 引用计数：第一次 acquire 会激活（并且，配合 reset，会清空陈旧状态）；
    # 最后一次 release 会停用。这让共享处理链的并发使用者能够相互协调，
    # 而不是彼此把对方关闭。
    if active:
        local.acquire(reset=reset)
    else:
        local.release(reset=reset)


def get_local_emulator_message_processor(
    chain: message_processors.ChainedMessageProcessor,
) -> Optional[local_emulator_message_processor.LocalEmulatorMessageProcessor]:
    """
    从给定的消息处理器链中获取本地模拟器消息处理器。

    该函数在提供的链中进行搜索，查找类型为 LocalEmulatorMessageProcessor 的处理器。
    如果找到则返回它；否则返回 None。

    Args:
        chain: 可能包含 LocalEmulatorMessageProcessor 的消息处理器链。

    Returns:
        如果在链中找到，则返回 LocalEmulatorMessageProcessor，否则返回 None。
    """
    local = chain.get_processor_by_type(
        local_emulator_message_processor.LocalEmulatorMessageProcessor
    )
    return local
