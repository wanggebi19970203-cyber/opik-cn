"""
评估模型的能力注册表。

该注册表设计为可扩展，不仅限于视觉支持（例如未来可支持音频）。
"""

from __future__ import annotations

from typing import Callable, Dict, Iterable, Optional, Set

CapabilityDetector = Callable[[str], bool]


VISION_MODEL_PREFIXES: Set[str] = {
    # OpenAI
    "gpt-4-vision",
    "gpt-4o",
    "gpt-4o-mini",
    "gpt-4-turbo",
    "chatgpt-4o-latest",
    "gpt-5-mini",
    "gpt-4.1",
    "gpt-4.1-mini",
    "gpt-4.1-nano",
    "gpt-4.1-preview",
    # Anthropic
    "claude-3",
    "claude-3-5",
    # Google
    "gemini-1.5-pro",
    "gemini-1.5-flash",
    "gemini-pro-vision",
    "gemini-2.0-flash",
    # Meta
    "llama-3.2-11b-vision",
    "llama-3.2-90b-vision",
    # Mistral
    "pixtral",
    # Misc
    "qwen-vl",
    "qwen2-vl",
    "phi-3-vision",
    "phi-3.5-vision",
    "llava",
    "cogvlm",
    "yi-vl",
}
VISION_MODEL_PREFIXES = {prefix.lower() for prefix in VISION_MODEL_PREFIXES}
VISION_MODEL_SUFFIXES: Set[str] = {"-vision", "-vl"}


def _strip_provider_prefix(model_name: str) -> str:
    if "/" not in model_name:
        return model_name
    _, suffix = model_name.split("/", 1)
    return suffix


def _litellm_supports_vision(model_name: str) -> bool:
    try:
        import litellm  # type: ignore

        return litellm.supports_vision(model=model_name)
    except Exception:
        return False


def vision_capability_detector(model_name: str) -> bool:
    stripped = _strip_provider_prefix(model_name)
    candidates = {model_name, stripped}
    for candidate in candidates:
        if _litellm_supports_vision(candidate):
            return True
        normalized = candidate.lower()
        if any(normalized.startswith(prefix) for prefix in VISION_MODEL_PREFIXES):
            return True
        if any(normalized.endswith(suffix) for suffix in VISION_MODEL_SUFFIXES):
            return True
    return False


def video_capability_detector(model_name: str) -> bool:
    """
    启发式判断模型是否接受视频输入。

    提供商很少暴露视频支持的结构化元数据，因此回退到命名约定
    （例如名称包含 ``video`` 或 ``qwen`` + ``vl`` 的模型）。
    当启发式方法失败时，委托给视觉检测器，因为当前 SDK 集成
    将视频视为多模态/视觉 API 的扩展。
    """
    stripped = _strip_provider_prefix(model_name)
    candidates = {model_name, stripped}
    for candidate in candidates:
        normalized = candidate.lower()
        if "video" in normalized:
            return True
        if "qwen" in normalized and "vl" in normalized:
            return True
    # TODO(opik): litellm/model metadata still treats video + image inputs the same.
    # Fall back to the vision heuristic so we can keep this dedicated capability
    # and tighten detection once providers expose richer metadata.
    return vision_capability_detector(model_name)


class ModelCapabilitiesRegistry:
    """模型能力检测的中央注册表。"""

    def __init__(self) -> None:
        self._capability_detectors: Dict[str, CapabilityDetector] = {}

    def register_capability_detector(
        self, capability: str, detector: CapabilityDetector
    ) -> None:
        """为指定能力名称注册检测器可调用对象。"""
        self._capability_detectors[capability] = detector

    def supports(self, capability: str, model_name: Optional[str]) -> bool:
        """当指定的模型名称支持请求的能力时返回 True。"""
        if not model_name:
            return False

        detector = self._capability_detectors.get(capability)
        if detector is None:
            return False

        try:
            return detector(model_name)
        except Exception:
            return False

    def supports_vision(self, model_name: Optional[str]) -> bool:
        """视觉能力检测的便捷方法。"""
        return self.supports("vision", model_name)

    def supports_video(self, model_name: Optional[str]) -> bool:
        """视频能力检测的便捷方法。"""
        return self.supports("video", model_name)

    def add_vision_model(self, model_name: str) -> None:
        # 扩展 vision_capability_detector 使用的模块级注册表
        VISION_MODEL_PREFIXES.add(self._strip_provider_prefix(model_name).lower())

    def add_vision_models(self, model_names: Iterable[str]) -> None:
        for model_name in model_names:
            self.add_vision_model(model_name)

    def _supports_vision(self, model_name: str) -> bool:
        return vision_capability_detector(model_name)

    @staticmethod
    def _strip_provider_prefix(model_name: str) -> str:
        return _strip_provider_prefix(model_name)

    @staticmethod
    def _litellm_supports_vision(model_name: str) -> bool:
        return _litellm_supports_vision(model_name)


MODEL_CAPABILITIES_REGISTRY = ModelCapabilitiesRegistry()
MODEL_CAPABILITIES_REGISTRY.register_capability_detector(
    "vision", vision_capability_detector
)
MODEL_CAPABILITIES_REGISTRY.register_capability_detector(
    "video", video_capability_detector
)

# 向后兼容性垫片，适配之前暴露类方法的旧 API。
ModelCapabilities = MODEL_CAPABILITIES_REGISTRY


__all__ = [
    "ModelCapabilitiesRegistry",
    "MODEL_CAPABILITIES_REGISTRY",
    "ModelCapabilities",
    "vision_capability_detector",
    "video_capability_detector",
]
