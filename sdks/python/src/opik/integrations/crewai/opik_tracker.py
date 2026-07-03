import importlib.metadata
import logging
from typing import Optional

import crewai

import opik.semantic_version

from . import crewai_decorator, patchers

LOGGER = logging.getLogger(__name__)


def track_crewai(
    project_name: Optional[str] = None,
    crew: Optional[crewai.Crew] = None,
) -> None:
    """
    通过为各种关键方法启用追踪装饰器来跟踪 CrewAI 活动。

    该函数将追踪装饰器应用于 CrewAI 的核心组件和方法，实现活动的日志记录或监控。
    追踪功能全局启用，且只能初始化一次。

    注意：如果使用此追踪器，请避免同时使用 OpenAI 追踪器，
    以防止 LLM 调用和 token 使用量的重复记录。

    Args:
        project_name: 与追踪关联的项目名称。
        crew: 要追踪的 Crew 实例。CrewAI v1.0.0+ 版本需要此参数才能正确追踪 LLM 调用。
    """

    decorator_factory = crewai_decorator.CrewAITrackDecorator()

    crewai_wrapper = decorator_factory.track(
        project_name=project_name,
    )

    crewai.Crew.kickoff = crewai_wrapper(crewai.Crew.kickoff)
    crewai.Crew.kickoff_for_each = crewai_wrapper(crewai.Crew.kickoff_for_each)
    crewai.Agent.execute_task = crewai_wrapper(crewai.Agent.execute_task)
    crewai.Task.execute_sync = crewai_wrapper(crewai.Task.execute_sync)

    # 修补 CrewAI 使用的 LiteLLM 函数
    patchers.patch_litellm_completion(project_name=project_name)

    # 修补 Flow 类 (v1.0.0+)
    patchers.patch_flow(project_name=project_name)

    # 修补 CrewAI 代理使用的 LLM 客户端 (v1.0.0+)
    if crew is not None and is_crewai_v1():
        patchers.patch_llm_client(crew, project_name)


def is_crewai_v1() -> bool:
    """
    检查是否安装了 CrewAI v1.0.0+ 版本。

    Returns:
        如果检测到 CrewAI v1.0.0+ 版本则返回 True，否则返回 False。
    """
    try:
        version_str = importlib.metadata.version("crewai")
        return opik.semantic_version.SemanticVersion.parse(version_str) >= "1.0.0"  # type: ignore
    except Exception:
        return False
