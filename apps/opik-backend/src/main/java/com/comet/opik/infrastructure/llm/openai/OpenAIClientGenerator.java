package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static dev.langchain4j.model.openai.internal.OpenAiUtils.DEFAULT_OPENAI_URL;

@RequiredArgsConstructor
@Slf4j
public class OpenAIClientGenerator implements LlmProviderClientGenerator<OpenAiClient> {

    private static final String CONFIG_KEY_PIPELINE_MODE = "openai_pipeline_mode";

    // langchain4j 的 OpenAiOfficialResponsesChatModel 构造函数在构建时要求非 null 的 modelName。
    // 在代理路径上，真实模型是通过 ChatRequest.parameters().modelName(...) 按请求提供的，
    // 因此这个占位符永远不会到达 OpenAI。
    private static final String PROXY_MODEL_NAME_PLACEHOLDER = "placeholder-model-value";

    public enum ApiPipelineMode {
        CHAT_COMPLETIONS_API, // 使用传统的 /v1/chat/completions
        RESPONSES_API // 使用现代的 /v1/responses
    }

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public OpenAiClient newOpenAiClient(@NonNull LlmProviderClientApiConfig config) {
        var openAiClientBuilder = OpenAiClient.builder()
                .baseUrl(DEFAULT_OPENAI_URL)
                // 将 insufficient_quota（429，额度耗尽）视为不可重试，这样
                // ChatCompletionService 中的外层重试策略就不会持续打击一个已耗尽的 key。
                .httpClientBuilder(QuotaAwareHttpClient.builder())
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(openAiClientBuilder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            openAiClientBuilder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(config.headers())
                .filter(MapUtils::isNotEmpty)
                .ifPresent(openAiClientBuilder::customHeaders);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> openAiClientBuilder.connectTimeout(connectTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                .ifPresent(readTimeout -> openAiClientBuilder.readTimeout(readTimeout.toJavaDuration()));

        return openAiClientBuilder
                .apiKey(config.apiKey())
                .build();
    }

    public ChatModel newOpenAiChatLanguageModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return switch (extractApiPipelineMode(config)) {
            case CHAT_COMPLETIONS_API -> newCompletionsApiChatModel(config, modelParameters);
            case RESPONSES_API -> newResponsesApiChatModel(config, modelParameters);
        };
    }

    @Override
    public OpenAiClient generate(@NonNull LlmProviderClientApiConfig config, Object... params) {
        return newOpenAiClient(config);
    }

    @Override
    public ChatModel generateChat(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newOpenAiChatLanguageModel(config, modelParameters);
    }

    ApiPipelineMode extractApiPipelineMode(@NonNull LlmProviderClientApiConfig config) {
        String pipelineMode = Optional.ofNullable(config.configuration())
                .orElse(Map.of())
                .get(CONFIG_KEY_PIPELINE_MODE);
        if (StringUtils.isBlank(pipelineMode)) {
            return ApiPipelineMode.CHAT_COMPLETIONS_API;
        }
        try {
            return ApiPipelineMode.valueOf(pipelineMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("未知的 OpenAI '{}' 值 '{}'，回退到 '{}'",
                    CONFIG_KEY_PIPELINE_MODE, pipelineMode, ApiPipelineMode.CHAT_COMPLETIONS_API);
            return ApiPipelineMode.CHAT_COMPLETIONS_API;
        }
    }

    ChatModel newCompletionsApiChatModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var builder = OpenAiChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(config.apiKey())
                // 将 insufficient_quota（429，额度耗尽）视为不可重试，这样无论是模型的
                // 内部重试还是外层重试策略都不会持续打击一个已耗尽的 key。
                .httpClientBuilder(QuotaAwareHttpClient.builder())
                .logRequests(true)
                .logResponses(true);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(builder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(config.headers())
                .filter(MapUtils::isNotEmpty)
                .ifPresent(builder::customHeaders);

        Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);
        Optional.ofNullable(modelParameters.seed()).ifPresent(builder::seed);

        return builder.build();
    }

    /**
     * 代理路径重载 —— 合成占位的 judge 参数。真实模型名是
     * 通过 {@code ChatRequest.parameters().modelName(...)} 按请求提供的，因此占位符
     * 永远不会到达 OpenAI；它只是在构建时满足 langchain4j 的必填字段校验。
     * <p>
     * {@code strictJsonSchema} 控制 langchain4j 对 {@code json_schema}
     * 响应格式的构建时严格模式。Responses-API 代理会按请求窥探入站的
     * {@code response_format.json_schema.strict} 并在这里挑选正确的变体，因为
     * langchain4j 没有按请求的 strict 槽位。
     */
    ChatModel newResponsesApiChatModel(@NonNull LlmProviderClientApiConfig config, boolean strictJsonSchema) {
        return newResponsesApiChatModel(
                config,
                LlmAsJudgeModelParameters.builder().name(PROXY_MODEL_NAME_PLACEHOLDER).build(),
                strictJsonSchema);
    }

    ChatModel newResponsesApiChatModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newResponsesApiChatModel(config, modelParameters, false);
    }

    ChatModel newResponsesApiChatModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters, boolean strictJsonSchema) {
        var builder = OpenAiOfficialResponsesChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(config.apiKey())
                .strictJsonSchema(strictJsonSchema);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(builder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(config.headers())
                .filter(MapUtils::isNotEmpty)
                .ifPresent(builder::customHeaders);

        Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);

        return builder.build();
    }

    /**
     * 代理路径流式对应 {@link #newResponsesApiChatModel(LlmProviderClientApiConfig, boolean)}。
     * 与非流式变体一样，真实模型名是通过
     * {@code ChatRequest.parameters().modelName(...)} 按请求提供的；占位符仅在构建时使用以
     * 满足 langchain4j 的必填字段校验。{@code strictJsonSchema} 是 langchain4j 模型上的
     * 构建时设置 —— 代理在这里传递按请求的 {@code strict} 位。
     */
    StreamingChatModel newResponsesApiStreamingChatModel(@NonNull LlmProviderClientApiConfig config,
            boolean strictJsonSchema) {
        var builder = OpenAiOfficialResponsesStreamingChatModel.builder()
                .modelName(PROXY_MODEL_NAME_PLACEHOLDER)
                .apiKey(config.apiKey())
                .strictJsonSchema(strictJsonSchema);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(builder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(config.headers())
                .filter(MapUtils::isNotEmpty)
                .ifPresent(builder::customHeaders);

        return builder.build();
    }
}
