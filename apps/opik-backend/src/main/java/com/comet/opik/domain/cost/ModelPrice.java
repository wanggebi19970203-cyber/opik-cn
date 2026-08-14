package com.comet.opik.domain.cost;

import lombok.Builder;
import lombok.NonNull;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

@Builder(toBuilder = true)
public record ModelPrice(
        @NonNull BigDecimal inputPrice,
        @NonNull BigDecimal outputPrice,
        @NonNull BigDecimal cacheCreationInputTokenPrice,
        @NonNull BigDecimal cacheReadInputTokenPrice,
        @NonNull BigDecimal videoOutputPrice,
        @NonNull BigDecimal audioInputCharacterPrice,
        @NonNull BigDecimal inputAudioTokenPrice,
        @NonNull BigDecimal outputAudioTokenPrice,
        @NonNull BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator,
        @NonNull List<PromptTier> promptTiers) {

    /**
     * LiteLLM 发布的 {@code *_above_NNNk_tokens} 费率的整体提示词分层阈值。
     * 当总提示词严格超过阈值时，该层的费率会整体替换基础费率
     * （与 LiteLLM 的 {@code _get_token_base_cost} 一致）。当前可命中的模型：
     * Gemini 1.5 Flash 为 128K，Gemini 2.5 Pro / Claude Sonnet 4.5 为 200K，
     * GPT-5.4 / GPT-5.5（openai 和 azure）为 272K。
     */
    public static final int TIER_THRESHOLD_128K = 128_000;

    public static final int TIER_THRESHOLD_200K = 200_000;

    public static final int TIER_THRESHOLD_272K = 272_000;

    /**
     * 模型的一个提示词大小分层：阈值加上该层所覆盖的每个费率。任何保留为
     * {@link BigDecimal#ZERO} 的费率都表示“该层不覆盖该费率 —— 回落到更低层或基础费率。”
     * LiteLLM 在 200K 发布下列四种费率，但在 128K 和 272K 仅发布 {@code input}/{@code output}，
     * 因此这些层上的缓存字段通常为零，并通过 {@link #applicableTier} 正确地成为空操作。
     */
    @Builder(toBuilder = true)
    public record PromptTier(
            int threshold,
            @NonNull BigDecimal inputPrice,
            @NonNull BigDecimal outputPrice,
            @NonNull BigDecimal cacheCreationInputTokenPrice,
            @NonNull BigDecimal cacheReadInputTokenPrice) {
    }

    /**
     * 返回一个预填充了零费率、空操作 {@code defaultCost} 计算器和空分层列表的 builder。
     * 调用方只需覆盖它们关心的字段，这既让测试夹具和空占位符保持简洁，
     * 又无需重新引入重载构造函数。
     */
    public static ModelPriceBuilder defaultBuilder() {
        return builder()
                .inputPrice(BigDecimal.ZERO)
                .outputPrice(BigDecimal.ZERO)
                .cacheCreationInputTokenPrice(BigDecimal.ZERO)
                .cacheReadInputTokenPrice(BigDecimal.ZERO)
                .videoOutputPrice(BigDecimal.ZERO)
                .audioInputCharacterPrice(BigDecimal.ZERO)
                .inputAudioTokenPrice(BigDecimal.ZERO)
                .outputAudioTokenPrice(BigDecimal.ZERO)
                .calculator(SpanCostCalculator::defaultCost)
                .promptTiers(List.of());
    }

    public static ModelPrice empty() {
        return defaultBuilder().build();
    }

    public BigDecimal effectiveInputPrice(int totalPromptTokens) {
        return applicableTier(totalPromptTokens, PromptTier::inputPrice).orElse(inputPrice);
    }

    public BigDecimal effectiveOutputPrice(int totalPromptTokens) {
        return applicableTier(totalPromptTokens, PromptTier::outputPrice).orElse(outputPrice);
    }

    public BigDecimal effectiveCacheCreationInputTokenPrice(int totalPromptTokens) {
        return applicableTier(totalPromptTokens, PromptTier::cacheCreationInputTokenPrice)
                .orElse(cacheCreationInputTokenPrice);
    }

    public BigDecimal effectiveCacheReadInputTokenPrice(int totalPromptTokens) {
        return applicableTier(totalPromptTokens, PromptTier::cacheReadInputTokenPrice)
                .orElse(cacheReadInputTokenPrice);
    }

    /**
     * 遍历 {@code promptTiers}（调用方按阈值降序存储），返回第一个其阈值被
     * {@code totalPromptTokens} 严格超过、且所请求费率大于零的层。适用层最高者胜出；
     * 零值费率绝不会压制更低层的费率或基础费率。
     */
    private Optional<BigDecimal> applicableTier(int totalPromptTokens, Function<PromptTier, BigDecimal> rate) {
        return promptTiers.stream()
                .filter(tier -> totalPromptTokens > tier.threshold())
                .map(rate)
                .filter(price -> price.compareTo(BigDecimal.ZERO) > 0)
                .findFirst();
    }
}
