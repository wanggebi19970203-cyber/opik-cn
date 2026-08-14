package com.comet.opik.infrastructure.llm;

/**
 * OpenAI 客户端的配置。
 * <p>
 * {@code url} 故意不携带约束。随附的 {@code config.yml} 让它留空
 * （{@code url: ${LLM_PROVIDER_OPENAI_URL:-}}），反序列化为 {@code null}，意思是“使用 provider
 * 默认值” —— 每个调用方都通过 {@code Optional.ofNullable(...).filter(isNotBlank)} 读取它。因此这里的
 * {@code @NotNull} 或 {@code @NotBlank} 会导致默认安装启动失败。
 */
public record OpenAiClientConfig(String url) {
}
