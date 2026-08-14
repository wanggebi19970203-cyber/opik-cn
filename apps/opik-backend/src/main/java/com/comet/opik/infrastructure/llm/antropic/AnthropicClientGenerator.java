package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.AnthropicClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.chat.ChatModel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

@RequiredArgsConstructor
public class AnthropicClientGenerator implements LlmProviderClientGenerator<AnthropicClient> {

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    private AnthropicClient newAnthropicClient(@NonNull LlmProviderClientApiConfig config) {
        var anthropicClientBuilder = AnthropicClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(AnthropicClientConfig::url)
                .filter(StringUtils::isNotEmpty)
                .ifPresent(anthropicClientBuilder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            anthropicClientBuilder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(AnthropicClientConfig::version)
                .filter(StringUtils::isNotBlank)
                .ifPresent(anthropicClientBuilder::version);
        Optional.ofNullable(llmProviderClientConfig.getLogRequests())
                .ifPresent(anthropicClientBuilder::logRequests);
        Optional.ofNullable(llmProviderClientConfig.getLogResponses())
                .ifPresent(anthropicClientBuilder::logResponses);
        // anthropic 客户端构建器只接收一种超时变体
        Optional.ofNullable(llmProviderClientConfig.getCallTimeout())
                .ifPresent(callTimeout -> anthropicClientBuilder.timeout(callTimeout.toJavaDuration()));
        return anthropicClientBuilder
                .apiKey(config.apiKey())
                .build();
    }

    private ChatModel newChatLanguageModel(LlmProviderClientApiConfig config,
            LlmAsJudgeModelParameters modelParameters) {
        var builder = AnthropicChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(modelParameters.name())
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(builder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }

        var customParameters = modelParameters.customParameters();
        var thinking = parseThinking(customParameters);

        // Anthropic 在两种情况下会以 400 拒绝 temperature：(1) 自适应思考模型
        // （claude-sonnet-5、claude-opus-4-7/4-8）报告不支持采样参数，以及 (2) 任何模型
        // 一旦通过 custom_parameters 按规则启用了扩展思考。在服务端对两者都做门控，
        // 这样 API 创建的规则（绕过 FE 净化器）就不会失败。
        if (AnthropicModelName.supportsSamplingParams(modelParameters.name()) && !thinking.enabled()) {
            Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);
        }

        applyCustomParameters(builder, customParameters, thinking);

        return builder.build();
    }

    /**
     * 从规则的 {@code custom_parameters} 单次解码 {@code thinking} 块。仅当 {@code type} 是
     * 除 {@code "disabled"} 以外的显式、非空值时，思考才算启用 —— 因此
     * {@code "enabled"}、{@code "adaptive"} 以及任何未来的类型都会关闭 temperature 门控，而缺失/空白
     * 的 {@code type}（或缺块）不算启用，且不得门控 temperature 或影响 max_tokens。
     */
    private ThinkingParams parseThinking(JsonNode customParameters) {
        if (customParameters == null || customParameters.isNull()) {
            return ThinkingParams.ABSENT;
        }
        var thinkingNode = customParameters.get("thinking");
        if (thinkingNode == null || !thinkingNode.isObject()) {
            return ThinkingParams.ABSENT;
        }

        String type = null;
        var typeNode = thinkingNode.get("type");
        if (typeNode != null && typeNode.isTextual() && StringUtils.isNotBlank(typeNode.asText())) {
            type = typeNode.asText();
        }

        Integer budgetTokens = null;
        var budgetNode = thinkingNode.get("budget_tokens");
        if (budgetNode != null && budgetNode.canConvertToInt() && budgetNode.asInt() > 0) {
            budgetTokens = budgetNode.asInt();
        }

        return new ThinkingParams(type != null && !"disabled".equalsIgnoreCase(type), type, budgetTokens);
    }

    /**
     * 把规则的 {@code custom_parameters}（thinking、max_tokens）转发到 judge 路径的构建器，
     * 并保证始终发送 {@code max_tokens}。Anthropic 要求 max_tokens，而没有显式上限时，
     * 自适应思考可能消耗整个预算，产生空响应（finishReason=LENGTH）。
     */
    private void applyCustomParameters(AnthropicChatModel.AnthropicChatModelBuilder builder,
            JsonNode customParameters, ThinkingParams thinking) {
        Optional.ofNullable(thinking.type()).ifPresent(builder::thinkingType);

        // budget_tokens 仅在与启用的思考一起时才有效；用缺失或 "disabled" 的
        // type 转发它会产生 Anthropic 以 400 拒绝的部分配置。
        Integer thinkingBudgetTokens = thinking.enabled() ? thinking.budgetTokens() : null;
        Optional.ofNullable(thinkingBudgetTokens).ifPresent(builder::thinkingBudgetTokens);

        builder.maxTokens(resolveMaxTokens(parseMaxTokens(customParameters), thinkingBudgetTokens));
    }

    private Integer parseMaxTokens(JsonNode customParameters) {
        if (customParameters == null || customParameters.isNull()) {
            return null;
        }
        var maxTokensNode = customParameters.get("max_tokens");
        if (maxTokensNode != null && maxTokensNode.canConvertToInt() && maxTokensNode.asInt() > 0) {
            return maxTokensNode.asInt();
        }
        return null;
    }

    /**
     * 解析发送给 Anthropic 的 {@code max_tokens}，保证 {@code max_tokens > thinking.budget_tokens}
     * （否则 Anthropic 会拒绝，因为 max_tokens 覆盖 thinking + 输出）。当显式规则值
     * 已经超过预算时予以保留；否则把它提高到在预算之上留出输出余量。
     */
    private int resolveMaxTokens(Integer maxTokens, Integer thinkingBudgetTokens) {
        int resolved = maxTokens != null ? maxTokens : LlmProviderAnthropicMapper.DEFAULT_MAX_COMPLETION_TOKENS;
        if (thinkingBudgetTokens != null && resolved <= thinkingBudgetTokens) {
            // 在添加余量之前先拓宽为 long，这样极端预算不会溢出成负 int。
            return (int) Math.min(Integer.MAX_VALUE,
                    (long) thinkingBudgetTokens + LlmProviderAnthropicMapper.DEFAULT_MAX_COMPLETION_TOKENS);
        }
        return resolved;
    }

    private record ThinkingParams(boolean enabled, String type, Integer budgetTokens) {
        private static final ThinkingParams ABSENT = new ThinkingParams(false, null, null);
    }

    @Override
    public AnthropicClient generate(@NonNull LlmProviderClientApiConfig config, Object... params) {
        return newAnthropicClient(config);
    }

    @Override
    public ChatModel generateChat(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newChatLanguageModel(config, modelParameters);
    }
}
