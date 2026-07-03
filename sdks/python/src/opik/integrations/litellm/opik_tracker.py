from typing import Callable, Optional, TypeVar

from . import litellm_completion_decorator
from . import completion_chunks_aggregator
from opik.types import TraceSource

F = TypeVar("F", bound=Callable)


def track_completion(
    project_name: Optional[str] = None,
    source: Optional[TraceSource] = None,
) -> Callable[[F], F]:
    """用于追踪 LiteLLM 函数调用的装饰器，集成 Opik 监控。

    可在其他 Opik 追踪函数内部使用，以创建正确的 span 层级结构。

    支持流式和非流式模式：
    * `litellm.completion`
    * `litellm.acompletion`

    示例:
        ```python
        import litellm
        from opik.integrations.litellm import track_completion

        tracked_completion = track_completion(project_name="my-project")(litellm.completion)
        response = tracked_completion(model="gpt-3.5-turbo", messages=[...])
        ```

    参数:
        project_name: 用于记录数据的项目名称。
        source: 追踪来源（例如 "sdk"、"optimization"）。

    返回:
        装饰器函数，用于包装 completion 函数并集成 Opik 追踪。
    """

    decorator_factory = litellm_completion_decorator.LiteLLMCompletionTrackDecorator()

    return decorator_factory.track(  # type: ignore
        type="llm",
        name=None,  # 使用函数名（completion 或 acompletion）
        project_name=project_name,
        generations_aggregator=completion_chunks_aggregator.aggregate,
        source=source,
    )
