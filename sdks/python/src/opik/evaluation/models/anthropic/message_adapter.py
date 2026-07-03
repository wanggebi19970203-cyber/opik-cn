"""将 OpenAI 风格消息适配为 Anthropic API 约定。"""

from __future__ import annotations

import json
import logging
from typing import Any, Dict, List, Optional, Set, Tuple, Type

import pydantic

LOGGER = logging.getLogger(__name__)

# anthropic.messages.create() 接受的参数。
#
# 显著遗漏 —— `reasoning_effort`（OpenAI 风格的扩展思考控制参数）。
# 故意不列出，以便 `filter_unsupported_params` 静默丢弃它。LiteLLM 路径
# 将 `reasoning_effort` → `thinking={"type": "enabled", "budget_tokens": N}`
# （并在显式非 1 的 `temperature` 冲突时条件性丢弃 —— 参见
# `LiteLLMChatModel._remove_unnecessary_not_supported_params`）。此处的原生路径
# 故意不镜像该转换：effort→budget 映射是 LiteLLM 的约定而非 Anthropic 的，
# 在此编码会将我们绑定到 LiteLLM 的选择。
#
# 实际影响 —— 对于希望在原生适配器上使用扩展思考的用户，请直接传递
# `thinking={...}`（还需将其添加到此集合中以免被过滤），而非依赖
# `reasoning_effort`。因此，当调用方设置 `reasoning_effort` 且温度不冲突时，
# 同模型切换适配器的行为会 diverge：原生丢弃，LiteLLM 保留。参见
# `suite_evaluators/agentic/loop.py` 中关于 `temperature=0` 理由的讨论，
# 了解为何此问题未通过 agentic judge 暴露（循环固定 `temperature=0`，
# 迫使两条路径进入丢弃分支，隐藏了不对称性）。
_SUPPORTED_PARAMS: frozenset[str] = frozenset(
    {
        "model",
        "messages",
        "max_tokens",
        "system",
        "temperature",
        "top_p",
        "top_k",
        "stop_sequences",
        "tools",
        "tool_choice",
        "metadata",
    }
)


def _parse_tool_call_arguments(arguments: Any) -> Dict[str, Any]:
    """将 OpenAI 工具调用的 `arguments` 字段解码为字典。

    OpenAI 将 `arguments` 作为 JSON 编码字符串发出；Anthropic 的 `tool_use.input`
    指定为 JSON 对象 —— 顶层的数组、标量或 null 会被 API 拒绝。此辅助函数仅返回字典：

    - 字典输入 → 字典输出。
    - JSON 编码的字典字符串 → 解码后的字典。
    - 其他任何值（顶层列表/标量/null、格式错误的 JSON、非字符串非字典值）→ 空字典。

    回退到 `{}` 而非抛出异常。如果调用确实需要结构化参数，Anthropic SDK 会针对
    工具的 `input_schema` 暴露模式不匹配错误，这比"你的工具参数不是对象"更具可操作性。
    另一方面，在此转发列表/标量会以不透明的 400 错误短路 SDK 的验证。
    """
    if isinstance(arguments, dict):
        return arguments
    if isinstance(arguments, str):
        try:
            decoded = json.loads(arguments)
        except (TypeError, ValueError):
            return {}
        if isinstance(decoded, dict):
            return decoded
    return {}


def normalize_messages(
    messages: List[Dict[str, Any]],
) -> List[Dict[str, Any]]:
    """将 OpenAI 格式的对话历史翻译为 Anthropic 格式。

    对于包含工具往返（即 agentic 循环的后续轮次）的历史，需要两种转换：

    1. `{"role": "tool", "tool_call_id": X, "content": ...}` 变为用户消息内的
       `{"type": "tool_result", "tool_use_id": X, "content": ...}` 块。
       Anthropic 直接拒绝 `tool` 角色（"Allowed roles are 'user' or 'assistant'"）。

    2. `{"role": "assistant", "content": ..., "tool_calls": [...]}` 变为 assistant 消息，
       其 `content` 为块列表 —— 可选的 `text` 块在前，然后每次调用一个 `tool_use` 块
       （带 JSON 解码的 `input`）。

    连续的 tool 消息合并为包含多个 `tool_result` 块的单条用户消息 ——
    当前一个 assistant 轮次发出多个 `tool_use` 块时，Anthropic 要求如此。
    其他消息形状（system、普通 user、assistant 文本）原样传递，
    以保持与循环前调用方的差异最小。
    """
    result: List[Dict[str, Any]] = []
    pending_tool_results: List[Dict[str, Any]] = []

    def flush_tool_results() -> None:
        if pending_tool_results:
            result.append({"role": "user", "content": list(pending_tool_results)})
            pending_tool_results.clear()

    for msg in messages:
        role = msg.get("role")
        if role == "tool":
            pending_tool_results.append(
                {
                    "type": "tool_result",
                    "tool_use_id": msg.get("tool_call_id"),
                    "content": msg.get("content", ""),
                }
            )
            continue

        flush_tool_results()

        if role == "assistant":
            tool_calls = msg.get("tool_calls") or []
            text_content = msg.get("content")
            if not tool_calls:
                # 纯文本 assistant 消息 —— 保持 Anthropic 接受的简单字符串内容格式。
                result.append({"role": "assistant", "content": text_content})
                continue
            blocks: List[Dict[str, Any]] = []
            if isinstance(text_content, str) and text_content:
                blocks.append({"type": "text", "text": text_content})
            for call in tool_calls:
                function = call.get("function") or {}
                blocks.append(
                    {
                        "type": "tool_use",
                        "id": call.get("id"),
                        "name": function.get("name"),
                        "input": _parse_tool_call_arguments(
                            function.get("arguments", "{}")
                        ),
                    }
                )
            result.append({"role": "assistant", "content": blocks})
            continue

        # 透传任何其他角色（`user`，以及 `extract_system_messages` 遗留的内容
        # —— system 通常在此运行前已分离，但即使出现在此处也无大碍）。
        result.append(msg)

    flush_tool_results()
    return result


def extract_system_messages(
    messages: List[Dict[str, Any]],
) -> Tuple[Optional[str], List[Dict[str, Any]]]:
    """将 system 消息与对话消息分离。

    Anthropic 要求 system 内容作为顶层参数而非 role="system" 的消息。
    """
    system_parts: List[str] = []
    non_system: List[Dict[str, Any]] = []
    for msg in messages:
        if msg.get("role") == "system":
            system_parts.append(msg.get("content", ""))
        else:
            non_system.append(msg)
    system_text = "\n\n".join(system_parts) if system_parts else None
    return system_text, non_system


def pydantic_to_output_config(model: Type[pydantic.BaseModel]) -> Dict[str, Any]:
    """从 pydantic 模型构建 Anthropic ``output_config`` 字典。

    使用原生 ``json_schema`` 输出格式（SDK >=0.85），约束模型直接生成
    匹配 schema 的 JSON，无需 tool_use 间接层。
    """
    schema = model.model_json_schema()
    schema.pop("title", None)
    return {
        "format": {
            "type": "json_schema",
            "schema": schema,
        },
    }


def strip_anthropic_prefix(model_name: str) -> str:
    if model_name.startswith("anthropic/"):
        return model_name[len("anthropic/") :]
    return model_name


def filter_unsupported_params(
    params: Dict[str, Any],
    already_warned: Set[str],
) -> Dict[str, Any]:
    filtered: Dict[str, Any] = {}
    for key, value in params.items():
        if key in _SUPPORTED_PARAMS:
            filtered[key] = value
        elif key not in already_warned:
            LOGGER.debug(
                "Dropping unsupported Anthropic parameter '%s'.",
                key,
            )
            already_warned.add(key)
    return filtered


# OpenAI 接受 `tool_choice` 为裸字符串（"auto" / "none" / "required"）
# 或命名特定函数的对象；Anthropic 在所有情况下都要求对象形式且使用不同的键。
# agentic 循环发出 OpenAI 风格的值因为这是主流约定，因此我们在适配器边界翻译，
# 而非将提供商感知穿透到调用方。
#
# 映射理由：
# - "auto" → {"type": "auto"}（让模型决定）
# - "none" → {"type": "none"}（禁止使用工具）
# - "required" → {"type": "any"}（强制使用某个工具，不指定具体哪个）
# - {"type": "function", "function": {"name": X}} → {"type": "tool",
#   "name": X}（按名称强制使用特定工具）
_OPENAI_TO_ANTHROPIC_TOOL_CHOICE_STR: Dict[str, Dict[str, str]] = {
    "auto": {"type": "auto"},
    "none": {"type": "none"},
    "required": {"type": "any"},
}


def _normalize_one_tool(tool: Any) -> Any:
    """将一个 OpenAI 风格的工具规范翻译为 Anthropic 格式。

    OpenAI：
        {"type": "function", "function": {
            "name": ..., "description": ..., "parameters": {...}
        }}
    Anthropic（新 schema 要求 `type: "custom"` 鉴别器；API 拒绝 `function` 值）：
        {"type": "custom", "name": ..., "description": ...,
         "input_schema": {...}}

    已经是 Anthropic 格式的工具（任何非 OpenAI `type=function` 包装器的内容）
    原样传递，以免干扰手动构建原生规范的调用方。
    """
    if not isinstance(tool, dict):
        return tool
    if tool.get("type") != "function":
        return tool
    function = tool.get("function") or {}
    name = function.get("name")
    if not isinstance(name, str):
        # 格式错误的 OpenAI 规范 —— 让 SDK 暴露错误，而非静默重写为
        # Anthropic 同样会拒绝的内容。
        return tool
    translated: Dict[str, Any] = {
        "type": "custom",
        "name": name,
    }
    if "description" in function:
        translated["description"] = function["description"]
    if "parameters" in function:
        translated["input_schema"] = function["parameters"]
    return translated


def extract_tool_names(tools: Any) -> List[str]:
    """从每个工具规范中提取 `name` 字段。

    兼容 OpenAI 格式（`tool["function"]["name"]`）和 Anthropic 原生格式
    （`tool["name"]`），因此调用方可在 `normalize_tools` 运行前后使用。
    跳过缺少名称的条目而非抛出异常 —— SDK 会通过自身的验证暴露这些错误。
    """
    if not isinstance(tools, list):
        return []
    names: List[str] = []
    for tool in tools:
        if not isinstance(tool, dict):
            continue
        if tool.get("type") == "function":
            function = tool.get("function") or {}
            name = function.get("name")
        else:
            name = tool.get("name")
        if isinstance(name, str):
            names.append(name)
    return names


def normalize_tools(tools: Any) -> Any:
    """将 OpenAI 风格的工具规范列表翻译为 Anthropic 格式。

    非列表值原样传递，以免传入 `None` 或其他哨兵值的调用方受到意外影响。
    """
    if not isinstance(tools, list):
        return tools
    return [_normalize_one_tool(t) for t in tools]


def normalize_tool_choice(value: Any) -> Any:
    """将 OpenAI 风格的 `tool_choice` 值翻译为 Anthropic 对象形式。
    已正确格式和无法识别的值原样传递（让 SDK 暴露错误而非静默丢弃）。
    """
    if isinstance(value, str):
        return _OPENAI_TO_ANTHROPIC_TOOL_CHOICE_STR.get(value, value)
    if isinstance(value, dict):
        # OpenAI 的"强制使用此特定函数"格式：
        #   {"type": "function", "function": {"name": "read"}}
        # Anthropic 等效格式：
        #   {"type": "tool", "name": "read"}
        if value.get("type") == "function":
            function = value.get("function") or {}
            name = function.get("name")
            if isinstance(name, str):
                return {"type": "tool", "name": name}
    return value
