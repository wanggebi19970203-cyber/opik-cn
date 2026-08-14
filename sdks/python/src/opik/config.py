from __future__ import annotations
import configparser
import logging
import os
import sys
import pathlib
import urllib.parse
from typing import Any, Dict, Final, List, Literal, Optional, Tuple, Type, Union

import pydantic
import pydantic_settings
from pydantic_settings import BaseSettings, InitSettingsSource
from pydantic_settings.sources import ConfigFileSourceMixin

from . import dict_utils, url_helpers
from .api_key import opik_api_key

PathType = Union[
    pathlib.Path,
    str,
    List[Union[pathlib.Path, str]],
    Tuple[Union[pathlib.Path, str], ...],
]

_SESSION_CACHE_DICT: Dict[str, Any] = {}

MAX_BATCH_SIZE_MB = 5

OPIK_URL_CLOUD: Final[str] = "https://www.comet.com/opik/api/"
OPIK_URL_LOCAL: Final[str] = "http://localhost:5173/api/"

OPIK_PROJECT_DEFAULT_NAME: Final[str] = "Default Project"
OPIK_WORKSPACE_DEFAULT_NAME: Final[str] = "default"

CONFIG_FILE_PATH_DEFAULT: Final[str] = "~/.opik.config"

LOGGER = logging.getLogger(__name__)


class IniConfigSettingsSource(InitSettingsSource, ConfigFileSourceMixin):
    """
    从 INI 文件加载变量的源类
    """

    def __init__(
        self,
        settings_cls: Type[BaseSettings],
    ):
        config_file_path = os.getenv("OPIK_CONFIG_PATH", CONFIG_FILE_PATH_DEFAULT)
        expanded_path = pathlib.Path(config_file_path).expanduser()
        if config_file_path != CONFIG_FILE_PATH_DEFAULT and not expanded_path.exists():
            LOGGER.warning(
                f"在 `OPIK_CONFIG_PATH` 环境变量提供的路径 '{expanded_path}' 处未找到配置文件。"
            )
        self.ini_data = self._read_files(expanded_path)

        super().__init__(settings_cls, self.ini_data)

    def _read_file(self, file_path: pathlib.Path) -> Dict[str, Any]:
        config = configparser.ConfigParser()
        config.read(file_path)
        config_values = {
            section: dict(config.items(section)) for section in config.sections()
        }

        if "opik" in config_values:
            return config_values["opik"]

        return {}


class OpikConfig(pydantic_settings.BaseSettings):
    """
    使用第一个找到的值初始化每个配置变量。使用的来源顺序为：
    1. 用户传入的值
    2. 会话配置字典（可通过调用 `update_session_config(...)` 填充）
    3. 环境变量（必须以 "OPIK_" 前缀开头）
    4. 从文件加载
    5. 默认值
    """

    model_config = pydantic_settings.SettingsConfigDict(env_prefix="opik_")

    @classmethod
    def settings_customise_sources(
        cls,
        settings_cls: Type[pydantic_settings.BaseSettings],
        init_settings: pydantic_settings.PydanticBaseSettingsSource,
        env_settings: pydantic_settings.PydanticBaseSettingsSource,
        dotenv_settings: pydantic_settings.PydanticBaseSettingsSource,
        file_secret_settings: pydantic_settings.PydanticBaseSettingsSource,
    ) -> Tuple[pydantic_settings.PydanticBaseSettingsSource, ...]:
        return (
            init_settings,
            pydantic_settings.InitSettingsSource(
                pydantic_settings.BaseSettings, _SESSION_CACHE_DICT
            ),
            env_settings,
            IniConfigSettingsSource(settings_cls=cls),
        )

    # 以下是 Opik 配置

    url_override: str = OPIK_URL_CLOUD
    """Opik 后端基础 URL"""

    project_name: str = OPIK_PROJECT_DEFAULT_NAME
    """Opik 项目名称"""

    workspace: str = OPIK_WORKSPACE_DEFAULT_NAME
    """Opik 工作区"""

    default_llm: str = "openai/gpt-5-nano"
    """未提供 model 时，评估模型工厂使用的默认 LLM 模型名称。"""

    api_key: Optional[str] = None
    """Opik API 密钥。如果你针对开源 Opik 安装运行，则不需要此项。"""

    default_flush_timeout: Optional[int] = None
    """
    刷新 Opik 消息队列时等待的最长时间（以秒为单位）。
    特别是在调用以下方法时会等待：
    * Opik().flush()
    * Opik().end()
    * flush_tracker()
    以及在进程结束时。

    如果未设置，则没有超时。
    """

    background_workers: int = 4
    """
    向后端提交数据的后台线程数量。
    """

    file_upload_background_workers: int = 16
    """
    向后端上传文件的后台线程数量。
    """

    console_logging_level: Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"] = (
        "INFO"
    )
    """
    控制台日志的日志级别。
    """

    file_logging_level: Optional[
        Literal["DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"]
    ] = None
    """
    文件日志的日志级别。未配置时不会向文件写入任何日志。
    """

    logging_file: str = "opik.log"
    """
    写入日志的文件。
    """

    pytest_experiment_enabled: bool = True
    """
    如果启用，使用 `llm_unit` 装饰的测试会将数据记录到 Opik 实验中。
    """

    check_tls_certificate: bool = True
    """
    如果启用，将对所有 HTTP 请求启用 TLS 验证。
    """

    track_disable: bool = False
    """
    如果设置为 True，则 `@track` 装饰器和 `track_LIBRARY(...)` 集成不会记录任何数据。
    其他任何 API 都将继续正常工作。

    可以在运行时通过以下方式覆盖此设置：
    - opik.set_tracing_active(False)  # 禁用追踪
    - opik.set_tracing_active(True)   # 启用追踪
    - opik.is_tracing_active()        # 检查当前状态
    - opik.reset_tracing_to_config_default()  # 重置为此配置值

    运行时覆盖优先于此静态配置。

    我们建议不要禁用追踪，除非你的项目中只使用追踪功能，因为
    它可能导致依赖已创建 span/trace 的功能出现意外结果。
    """

    sentry_enable: bool = True
    """
    如果设置为 True，Opik 会将错误信息发送到 Sentry。
    """

    sentry_dsn: str = "https://fbde8a9ef528f379de25bdfb19749ca5@o168229.ingest.us.sentry.io/4508620148441088"  # 16.04.2026
    """
    用作 Sentry 事件目标的 Sentry 项目 DSN。
    如果需要更新报告规则并停止从现有用户接收事件，
    应在 Sentry 项目设置中禁用当前 DSN，创建一个新的 DSN 并将其放在此处，
    以替换旧的 DSN。
    """

    enable_litellm_models_monitoring: bool = True
    """
    如果设置为 True，Opik 将为 LiteLLMChatModel 调用创建 llm span。
    这主要用于测试，因为 litellm 使用外部的 Opik 回调，
    其 HTTP 请求不经过 opik 包。
    """

    enable_json_request_compression: bool = True
    """
    如果设置为 True，Opik 将压缩 JSON 请求体。
    """

    guardrail_timeout: int = 30
    """
    guardrail.validate 调用的超时时间（以秒为单位）。如果响应耗时超过此值，将被视为失败并抛出异常。
    """

    guardrails_url_override: Optional[str] = None
    """
    guardrails 后端服务的 URL。
    设置后，将覆盖根据 url_override 推导出的默认 guardrails URL。
    """

    maximal_queue_size: int = 1_000_000
    """
    指定在发生连接错误或速率限制生效时，可以排队等待投递的最大消息数量。
    """
    maximal_queue_size_batch_factor: int = 10
    """
    定义应用于 `maximal_queue_size` 的因子，用于在启用批处理时减小最大消息队列大小。
    """

    log_start_trace_span: bool = True
    """
    如果设置为 True，将记录 trace 和 span 的开始与结束。这对于持续时间较长的 trace 和 span 很有用。
    对于较短的 trace/span，建议保持此设置禁用，以尽量减少数据记录开销。
    """

    min_base64_embedded_attachment_size: int = 256_000
    """
    附件字符串保持内嵌在 base64 字符串中的最小大小（以字节为单位）。(250KB)
    大于此大小的附件将从 span/trace 的输入/输出中提取并上传到 Opik 后端。
    """

    is_attachment_extraction_active: bool = True
    """
    如果设置为 True，大于 `min_base64_embedded_attachment_size` 的附件将从 span/trace 中提取并上传到 Opik 后端。
    """

    max_payload_size_mb: int = 20
    """
    每个 span **和 trace** 的可截断字段（``input``/``output``）的单个对象大小限制（以 MB 为单位），
    在发送到后端之前（附件已提取之后）应用。超过此限制的 ``input`` 或 ``output`` ——
    或者两者合计超过此限制 —— 将被替换为截断标记并记录一条警告。``metadata`` 从不被截断
    （它保存后端依赖的小型路由/成本字段，例如 ``thread_id`` 和 ``model``），且不计入此限制；
    过大的 ``metadata`` 会交由服务端请求/文档防护（413/400）处理，而不是被裁剪。设置为 ``0``
    （或任何 ``<= 0`` 的值）可完全禁用截断。为避免截断，可将大负载作为附件记录。
    """

    connection_monitor_ping_interval: float = 10
    """
    OPIK 服务器连接监控 ping 之间的间隔（以秒为单位）。
    """

    connection_monitor_check_timeout: float = 5
    """
    OPIK 服务器连接监控检查的超时时间（以秒为单位）。
    """

    runner_poll_interval: float = 0.5
    """
    本地运行器（`opik connect` / `opik endpoint`）空闲时，轮询新作业的间隔（以秒为单位）。
    每次空闲轮询都会向 Opik 服务器发送一个请求，因此默认的 0.5 秒会产生约 120 个请求/分钟。
    如果企业防火墙或代理会限制或阻断持续轮询流量，请增大此值（代价是作业接收变慢）。
    """

    replay_batch_size: int = 50
    """
    连接到 OPIK 服务器恢复后，单批重放失败消息的数量。
    消息按批重放，以避免一次性发送过多请求压垮系统，并控制内存消耗。
    """
    replay_batch_replay_delay: float = 0.5
    """
    连接到 OPIK 服务器恢复后，重放各批失败消息之间的延迟（以秒为单位）。
    这是为了控制内存消耗，并避免一次性发送过多请求压垮系统。
    """

    replay_tick_interval: float = 0.3
    """
    重放管理器线程每次 tick 之间的间隔（以秒为单位）。
    这是为了控制重放管理器线程操作的频率，例如检查与 OPIK 服务器的连接状态，以及在连接恢复时重放失败消息。
    """

    unauthorized_message_type_retry_interval: float = 10.0
    """
    重试未授权消息类型之间的间隔（以秒为单位）。
    这是为了控制重试未授权消息类型的频率。
    """

    unauthorized_message_type_max_retry_count: Optional[int] = None
    """
    未授权消息类型的最大重试次数。
    这是为了控制未授权消息类型在放弃之前重试的次数。如果为 None，则没有限制。
    """

    environment: Optional[str] = None
    """
    当未显式提供 ``environment=`` 参数时，应用于 trace 和 span 的默认环境名称。
    环境变量：OPIK_ENVIRONMENT
    """

    suppress_batching_update_warning: bool = False
    """
    在启用批处理时，抑制对 span/trace 调用 .end() 或 .update() 可能造成数据丢失的警告。
    如果你的更新发生在创建之后很久且该警告不相关，请设置为 True。
    环境变量：OPIK_SUPPRESS_BATCHING_UPDATE_WARNING
    """

    prompt_cache_ttl_seconds: pydantic.PositiveInt = 300
    """
    缓存提示词的 TTL（以秒为单位）。控制未固定的提示词在从后端刷新之前保留多长时间。
    最小值为 1。
    环境变量：OPIK_PROMPT_CACHE_TTL_SECONDS
    """

    @property
    def config_file_fullpath(self) -> pathlib.Path:
        config_file_path = os.getenv("OPIK_CONFIG_PATH", CONFIG_FILE_PATH_DEFAULT)
        return pathlib.Path(config_file_path).expanduser()

    @property
    def config_file_exists(self) -> bool:
        """
        确定配置文件是否存在于指定路径。
        """
        return self.config_file_fullpath.exists()

    @property
    def is_cloud_installation(self) -> bool:
        """
        确定安装类型是否为云安装。
        """
        return url_helpers.get_base_url(self.url_override) == url_helpers.get_base_url(
            OPIK_URL_CLOUD
        )

    @property
    def is_localhost_installation(self) -> bool:
        return "localhost" in self.url_override

    @property
    def guardrails_backend_host(self) -> str:
        if self.guardrails_url_override is not None:
            return self.guardrails_url_override
        return url_helpers.get_base_url(self.url_override) + "guardrails/"

    @pydantic.model_validator(mode="after")
    def _set_url_override_from_api_key(self) -> OpikConfig:
        url_was_not_provided = (
            "url_override" not in self.model_fields_set or self.url_override is None
        )
        url_needs_configuration = self.api_key is not None and url_was_not_provided

        if not url_needs_configuration:
            return self

        assert self.api_key is not None
        opik_api_key_ = opik_api_key.parse_api_key(self.api_key)

        if opik_api_key_ is not None and opik_api_key_.base_url is not None:
            self.url_override = urllib.parse.urljoin(
                opik_api_key_.base_url, "opik/api/"
            )

        return self

    def save_to_file(self) -> None:
        """
        将配置保存到文件

        Raises:
            OSError: 写入文件时出现问题。
        """
        config_file_content = configparser.ConfigParser()

        config_file_content["opik"] = {
            "url_override": self.url_override,
            "workspace": self.workspace,
            "project_name": self.project_name,
        }

        if self.api_key is not None:
            config_file_content["opik"]["api_key"] = self.api_key

        try:
            with open(
                self.config_file_fullpath, mode="w+", encoding="utf-8"
            ) as config_file:
                config_file_content.write(config_file)
            LOGGER.info(f"配置已保存到文件：{self.config_file_fullpath}")
        except OSError as e:
            LOGGER.error(f"保存配置失败：{e}")
            raise

    def as_dict(self, mask_api_key: bool) -> Dict[str, Any]:
        """
        检索当前配置，其中 API 密钥值已被遮蔽。
        """
        current_values = self.model_dump()
        if current_values.get("api_key") is not None and mask_api_key:
            current_values["api_key"] = "*** HIDDEN ***"
        return current_values

    def check_for_known_misconfigurations(
        self, show_misconfiguration_message: bool = False
    ) -> bool:
        """
        尝试检测 Opik 是否配置错误，并可选择显示相应的错误消息。
        仅适用于 Opik 云和 OSS localhost 安装。

        Parameters:
        show_misconfiguration_message : 一个标志，指示当配置被判定为错误时是否显示详细的错误消息。
            默认为 False。
        """
        if "pytest" in sys.modules:
            return False

        is_misconfigured_flag, error_message = (
            self.get_misconfiguration_detection_results()
        )

        if is_misconfigured_flag:
            if show_misconfiguration_message:
                print()
                LOGGER.error(
                    "========================\n"
                    f"{error_message}\n"
                    "==============================\n"
                )
            return True

        return False

    def get_misconfiguration_detection_results(self) -> Tuple[bool, Optional[str]]:
        """
        尝试检测云或 localhost 环境的配置错误。
        对于任何其他类型的安装，此检测将不起作用。

        Returns:
            Tuple[bool, Optional[str]]: 一个元组，其中第一个元素表示
            配置是否错误（True 表示配置错误，False 表示有效）。
            第二个元素是一个可选字符串，如果存在配置问题则包含错误消息，
            如果配置有效则为 None。
        """
        is_misconfigured_for_cloud_flag, error_message = (
            self._is_misconfigured_for_cloud()
        )
        if is_misconfigured_for_cloud_flag:
            return True, error_message

        is_misconfigured_for_localhost_flag, error_message = (
            self._is_misconfigured_for_localhost()
        )
        if is_misconfigured_for_localhost_flag:
            return True, error_message

        return False, None

    def _is_misconfigured_for_cloud(self) -> Tuple[bool, Optional[str]]:
        """
        确定当前 Opik 配置对于云日志记录是否配置错误。

        Returns:
            Tuple[bool, Optional[str]]: 一个元组，其中第一个元素是一个布尔值，指示
            配置对于云日志记录是否错误；第二个元素是指示配置错误原因的错误消息，或 None。
        """
        api_key_configured = self.api_key is not None
        tracking_disabled = self.track_disable

        if (
            self.is_cloud_installation
            and (not api_key_configured)
            and (not tracking_disabled)
        ):
            error_message = (
                "必须指定 API 密钥才能将数据记录到 https://www.comet.com/opik。\n"
                "你可以使用 `opik configure` CLI 命令为日志记录配置环境。\n"
                "请参阅文档中的配置详情：https://www.comet.com/docs/opik/tracing/advanced/sdk_configuration。\n"
            )
            return True, error_message

        return False, None

    def _is_misconfigured_for_localhost(self) -> Tuple[bool, Optional[str]]:
        """
        确定当前设置对于本地开源安装是否配置错误。

        Returns:
            Tuple[bool, Optional[str]]: 一个元组，其中第一个元素是一个布尔值，指示
            配置对于本地日志记录是否错误；第二个元素是指示配置错误原因的错误消息，或 None。
        """

        workspace_is_default = self.workspace == OPIK_WORKSPACE_DEFAULT_NAME
        tracking_disabled = self.track_disable

        if (
            self.is_localhost_installation
            and (not workspace_is_default)
            and (not tracking_disabled)
        ):
            error_message = (
                "开源安装不支持指定工作区。仅可使用 `default`。\n"
                "请参阅文档中的配置详情：https://www.comet.com/docs/opik/tracing/advanced/sdk_configuration\n"
                "如果你需要高级工作区管理，可以考虑使用我们的云服务 (https://www.comet.com/site/pricing/)\n"
                "或联系我们的团队购买和设置自托管安装。\n"
            )
            return True, error_message

        return False, None


def update_session_config(key: str, value: Any) -> None:
    _SESSION_CACHE_DICT[key] = value


def get_from_user_inputs(**user_inputs: Any) -> OpikConfig:
    """
    使用提供的用户输入实例化 OpikConfig。
    """
    cleaned_user_inputs = dict_utils.remove_none_from_dict(user_inputs)

    return OpikConfig(**cleaned_user_inputs)
