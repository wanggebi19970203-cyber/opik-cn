package com.comet.opik.infrastructure.llm.freemodel;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.FreeModelConfig;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import com.comet.opik.infrastructure.llm.openai.QuotaAwareHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

/**
 * Opik Free Model LLM provider 的服务提供者。
 * 该 provider 把模型名从 "opik-free-model" 转换为实际模型。
 */
@Slf4j
public class FreeModelServiceProvider implements LlmServiceProvider {

    private final OpenAIClientGenerator clientGenerator;
    private final FreeModelConfig freeModelConfig;
    private final LlmProviderClientConfig llmProviderClientConfig;

    public FreeModelServiceProvider(
            @NonNull OpenAIClientGenerator clientGenerator,
            @NonNull LlmProviderFactory factory,
            @NonNull FreeModelConfig freeModelConfig,
            @NonNull LlmProviderClientConfig llmProviderClientConfig) {
        this.clientGenerator = clientGenerator;
        this.freeModelConfig = freeModelConfig;
        this.llmProviderClientConfig = llmProviderClientConfig;

        if (freeModelConfig.isEnabled()) {
            factory.register(LlmProvider.OPIK_FREE, this);
            log.info("已注册 OPIK_FREE provider，实际模型为 '{}'", freeModelConfig.getActualModel());
        }
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig config) {
        return new FreeModelLlmProvider(
                clientGenerator.newOpenAiClient(config),
                freeModelConfig.getActualModel(),
                freeModelConfig.isReasoningModel());
    }

    @Override
    public ChatModel getLanguageModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        Double temperature = modelParameters.temperature();

        if (freeModelConfig.isReasoningModel() && temperature != null
                && temperature < FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE) {
            log.debug("将 temperature 从 '{}' 限制到 '{}'，用于推理模型 '{}'",
                    temperature, FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE,
                    freeModelConfig.getActualModel());
            temperature = FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE;
        }

        var transformedParameters = LlmAsJudgeModelParameters.builder()
                .name(freeModelConfig.getActualModel())
                .temperature(temperature)
                .seed(modelParameters.seed())
                .build();

        var builder = OpenAiChatModel.builder()
                .modelName(transformedParameters.name())
                .apiKey(config.apiKey())
                // 将 insufficient_quota（429，额度耗尽）视为不可重试，这样模型的
                // 内部重试就不会持续打击一个已耗尽的 key。
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
                .filter(headers -> !headers.isEmpty())
                .ifPresent(builder::customHeaders);

        Optional.ofNullable(transformedParameters.temperature()).ifPresent(builder::temperature);
        Optional.ofNullable(transformedParameters.seed()).ifPresent(builder::seed);

        return builder.build();
    }
}
