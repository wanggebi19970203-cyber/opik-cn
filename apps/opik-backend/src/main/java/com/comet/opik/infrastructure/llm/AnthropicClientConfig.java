package com.comet.opik.infrastructure.llm;

import jakarta.validation.constraints.NotBlank;

/**
 * Anthropic 客户端的配置。
 */
public record AnthropicClientConfig(@NotBlank String url, @NotBlank String version) {
}
