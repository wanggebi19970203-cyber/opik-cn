"""`models_factory` 和适配器共享的模型名称判断函数。

独立为模块，使 `models_factory`（将模型名称路由到原生 Anthropic 适配器
或 LiteLLM 适配器）和 `litellm_chat_model`（需要知道是否在与 Anthropic
交互以应用提供商特定的冲突解决策略）能够共享单一事实来源，同时避免循环导入。

适配器直接导入 `models_factory` 会导致循环依赖，因为工厂会导入适配器模块
来构造实例；将判断函数下沉到此叶子模块可打破该循环，保持两个调用点同步。
"""


def is_anthropic_model(model_name: str) -> bool:
    """判断配置的模型名称是否指向 Anthropic。

    匹配 LiteLLM 的 `anthropic/<name>` 前缀约定以及 Anthropic SDK
    直接接受的 `claude-...` 裸格式。
    需与 LiteLLM 路由到其 Anthropic 提供商的键保持同步 —— 如果 LiteLLM
    添加新前缀（或我们接受第三方 Anthropic 网关），在此扩展检查即可，
    变更会自动传播到所有调用点。
    """
    return model_name.startswith("anthropic/") or model_name.startswith("claude")
