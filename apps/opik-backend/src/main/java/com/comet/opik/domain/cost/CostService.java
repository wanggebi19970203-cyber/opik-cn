package com.comet.opik.domain.cost;

import com.comet.opik.api.ModelCostData;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.UsageUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

@Slf4j
public class CostService {
    private static final char MODEL_PROVIDER_SEPARATOR = '/';
    private static final Map<String, ModelPrice> modelProviderPrices;
    private static final Map<String, String> PROVIDERS_MAPPING = Map.ofEntries(
            Map.entry("openai", "openai"),
            Map.entry("vertex_ai-language-models", "google_vertexai"),
            Map.entry("gemini", "google_ai"),
            Map.entry("anthropic", "anthropic"),
            Map.entry("vertex_ai-anthropic_models", "anthropic_vertexai"),
            Map.entry("bedrock", "bedrock"),
            Map.entry("bedrock_converse", "bedrock"),
            Map.entry("groq", "groq"),
            Map.entry("jina_ai", "jina_ai"),
            Map.entry("elastic", "elastic"),
            Map.entry("microsoft", "azure"),
            Map.entry("azure", "azure"),
            Map.entry("mistral", "mistral"),
            Map.entry("xai", "xai"),
            Map.entry("deepseek", "deepseek"),
            Map.entry("perplexity", "perplexity"),
            Map.entry("fireworks_ai", "fireworks_ai"),
            Map.entry("moonshot", "moonshot"),
            Map.entry("moonshotai", "moonshot"),
            Map.entry("ai21", "ai21"),
            Map.entry("morph", "morph"),
            Map.entry("inception", "inception"),
            Map.entry("meta", "meta"),
            Map.entry("zai", "zai"),
            Map.entry("z-ai", "zai"),
            Map.entry("sambanova", "sambanova"),
            Map.entry("nebius", "nebius"));

    // 在线评估（以及 OTel 摄取）会将模型解析为 LlmProvider 序列化值，这些值的名称与规范价格表
    // 词汇不同。在查找时将这些名称归一化为单一的规范 provider，以便成本跟踪和每次评估的
    // 花费预算能适用于所有可用于在线评估的 provider。只有名称实际不同的 provider 才需要条目：
    // openai / anthropic / bedrock 已经与它们的规范名称一致，而自托管 provider（ollama、
    // custom-llm）没有可映射的公开定价。此处 Vertex 是无歧义的，因为在线评估中 Vertex 上只提供
    // Gemini 模型。规范名称（以及任何未列出的名称）原样通过。
    private static final Map<String, String> RUNTIME_PROVIDER_MAPPING = Map.of(
            "gemini", "google_ai",
            "vertex-ai", "google_vertexai");
    public static final String MODEL_PRICES_FILE = "model_prices_and_context_window.json";
    public static final String MODEL_PRICES_OVERRIDES_FILE = "model_prices_overrides.json";
    private static final String BEDROCK_PROVIDER = "bedrock";
    private static final String DATE_SUFFIX_PATTERN = "-\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$";
    private static final String VERSION_SUFFIX_PATTERN = ":\\d+$";
    private static final Map<String, BiFunction<ModelPrice, Map<String, Integer>, BigDecimal>> PROVIDERS_CACHE_COST_CALCULATOR = Map
            .ofEntries(
                    Map.entry("anthropic", SpanCostCalculator::textGenerationWithCacheCostAnthropic),
                    Map.entry("openai", SpanCostCalculator::textGenerationWithCacheCostOpenAI),
                    Map.entry("azure", SpanCostCalculator::textGenerationWithCacheCostOpenAI),
                    Map.entry("xai", SpanCostCalculator::textGenerationWithCacheCostOpenAI),
                    Map.entry("deepseek", SpanCostCalculator::textGenerationWithCacheCostOpenAI),
                    Map.entry("fireworks_ai", SpanCostCalculator::textGenerationWithCacheCostOpenAI),
                    Map.entry("moonshot", SpanCostCalculator::textGenerationWithCacheCostOpenAI),
                    Map.entry("bedrock", SpanCostCalculator::textGenerationWithCacheCostBedrock),
                    Map.entry("bedrock_converse", SpanCostCalculator::textGenerationWithCacheCostBedrock),
                    Map.entry("vertex_ai-language-models", SpanCostCalculator::textGenerationWithCacheCostGoogle),
                    Map.entry("gemini", SpanCostCalculator::textGenerationWithCacheCostGoogle),
                    Map.entry("vertex_ai-anthropic_models", SpanCostCalculator::textGenerationWithCacheCostAnthropic));

    static {
        try {
            modelProviderPrices = Collections.unmodifiableMap(parseModelPrices());
        } catch (IOException e) {
            log.error("加载模型价格失败", e);
            throw new UncheckedIOException(e);
        }
    }

    private static final ModelPrice DEFAULT_COST = ModelPrice.empty();

    public static BigDecimal calculateCost(@Nullable String modelName, @Nullable String provider,
            @Nullable Map<String, Integer> usage, @Nullable JsonNode metadata) {
        ModelPrice modelPrice = findModelPrice(modelName, provider);

        // 在计价前丢弃为 null 的 token 计数：计算器通过 getOrDefault(key, 0) 读取 usage，
        // 当某个键存在但值为 null 时，它返回的是 null（而非默认值），随后在
        // BigDecimal.valueOf(...) 中拆箱时会抛出 NPE。
        BigDecimal estimatedCost = modelPrice.calculator().apply(modelPrice, UsageUtils.sanitizeUsage(usage));

        return estimatedCost.compareTo(BigDecimal.ZERO) > 0 ? estimatedCost : getCostFromMetadata(metadata);
    }

    /**
     * 查找模型定价信息，并在未命中时回退到归一化后的模型名称。
     * 该方法通过先尝试精确匹配、再回退到归一化变体的方式提供向后兼容性。
     *
     * 修复 issue #4114：处理诸如 "claude-3.5-sonnet" 的模型名称变体，
     * 将其归一化为定价数据库中使用的 "claude-3-5-sonnet" 格式。
     *
     * 修复 issue #5018：处理诸如 "gpt-5.2-2025-12-17" 带日期后缀的模型名称，
     * 通过剥离日期后缀并回退到基础模型名称。
     *
     * 修复 issue #5621：处理 LiteLLM 通过 gen_ai.request.model 发送的
     * 带 provider 前缀（如 "openai/gpt-4o"）的模型名称，在查找前剥离前缀。
     *
     * 修复 issue #5130：处理带版本锁定后缀的 Bedrock 模型名称（如
     * "anthropic.claude-opus-4-6-v1:0"），通过剥离 ":N" 版本锁定并回退到
     * 定价数据库中使用的（例如 "anthropic.claude-opus-4-6-v1"）基础模型名称。
     *
     * @param modelName 模型名称（可能包含点或 provider 前缀，例如 "openai/gpt-4o"）
     * @param provider provider 名称（例如 "anthropic"）
     * @return 该模型的 ModelPrice，若未找到则返回 DEFAULT_COST
     */
    private static ModelPrice findModelPrice(String modelName, String provider) {
        if (StringUtils.isBlank(modelName) || StringUtils.isBlank(provider)) {
            return DEFAULT_COST;
        }

        // 将运行时 provider 名称（"gemini"、"vertex-ai"）归一化为规范价格表 provider，
        // 使持有 LlmProvider 值的调用方能命中与传入规范名称的调用方相同的行。
        provider = RUNTIME_PROVIDER_MAPPING.getOrDefault(provider, provider);

        // 保留原始名称，以便下方 provider 前缀回退逻辑能在调用方提供的 provider 下
        // 所有主要查找均未命中后对其进行检查。
        String originalModelName = modelName;

        // 若存在 provider 前缀则将其剥离（例如 "openai/gpt-4o" -> "gpt-4o"）。
        // LiteLLM 通过 gen_ai.request.model 发送带 provider 前缀的模型名称。
        // 这样做是安全的，因为 parseModelPrices() 在构建价格映射时也调用了 parseModelName()，
        // 因此存储的键绝不会包含 provider 前缀。后续所有归一化步骤（点→连字符、
        // 日期后缀剥离）都会应用于与填充映射时作为键所使用的同一个无前缀名称。
        modelName = parseModelName(modelName);

        // 首先尝试精确匹配（向后兼容）
        String exactKey = createModelProviderKey(modelName, provider);
        ModelPrice exactMatch = modelProviderPrices.get(exactKey);
        if (exactMatch != null) {
            return exactMatch;
        }

        // 尝试归一化后的模型名称（将点替换为连字符并转为小写）
        String normalizedModelName = normalizeModelName(modelName);
        if (!normalizedModelName.equalsIgnoreCase(modelName)) {
            String normalizedKey = createModelProviderKey(normalizedModelName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug("使用归一化名称找到模型价格。原始名称：'{}'，归一化名称：'{}'",
                        modelName, normalizedModelName);
                return normalizedMatch;
            }
        }

        // 尝试从保留点的原始名称中剥离日期后缀（例如 "gpt-5.2-2025-12-17" -> "gpt-5.2"）
        String baseOriginalModelName = stripDateSuffix(modelName);
        if (!baseOriginalModelName.equalsIgnoreCase(modelName)) {
            String normalizedKey = createModelProviderKey(baseOriginalModelName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug(
                        "剥离日期后缀后使用原始基础名称找到模型价格。原始名称：'{}'，基础名称：'{}'",
                        modelName, baseOriginalModelName);
                return normalizedMatch;
            }
        }

        // 尝试从归一化名称中剥离日期后缀（例如 "gpt-5-2-2025-12-17" -> "gpt-5-2"）
        String baseNormalizedModelName = stripDateSuffix(normalizedModelName);
        if (!baseNormalizedModelName.equalsIgnoreCase(normalizedModelName)) {
            String normalizedKey = createModelProviderKey(baseNormalizedModelName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug(
                        "剥离日期后缀后使用归一化基础名称找到模型价格。原始名称：'{}'，基础名称：'{}'",
                        modelName, baseNormalizedModelName);
                return normalizedMatch;
            }
        }

        // 尝试从保留点的原始名称中剥离版本锁定后缀
        // （例如 "anthropic.claude-opus-4-6-v1:0" -> "anthropic.claude-opus-4-6-v1"）
        String baseOriginalVersionName = stripVersionSuffix(modelName);
        if (!baseOriginalVersionName.equalsIgnoreCase(modelName)) {
            String normalizedKey = createModelProviderKey(baseOriginalVersionName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug(
                        "剥离版本后缀后使用原始基础名称找到模型价格。原始名称：'{}'，基础名称：'{}'",
                        modelName, baseOriginalVersionName);
                return normalizedMatch;
            }
        }

        // 尝试从归一化名称中剥离版本锁定后缀
        // （例如 "anthropic-claude-opus-4-6-v1:0" -> "anthropic-claude-opus-4-6-v1"）
        String baseNormalizedVersionName = stripVersionSuffix(normalizedModelName);
        if (!baseNormalizedVersionName.equalsIgnoreCase(normalizedModelName)) {
            String normalizedKey = createModelProviderKey(baseNormalizedVersionName, provider);
            ModelPrice normalizedMatch = modelProviderPrices.get(normalizedKey);
            if (normalizedMatch != null) {
                log.debug(
                        "剥离版本后缀后使用归一化基础名称找到模型价格。原始名称：'{}'，基础名称：'{}'",
                        modelName, baseNormalizedVersionName);
                return normalizedMatch;
            }
        }

        // provider 前缀回退：当所有主要查找均未命中，且模型名称带有可映射到我们已知定价的
        // 规范 provider 的前缀（例如 "perplexity/sonar"）时，改用该前缀对应的规范 provider
        // 重试查找。这覆盖了调用方通过聚合器路由的模型（LlmProviderFactoryImpl 在 OpenRouter 下
        // 枚举了 "perplexity/*"、"xai/*"、"deepseek/*"，因此 BudgetGuard 会以 provider="openrouter"
        // 调用 calculateCost —— 而定价行本身位于模型实际的原始 provider 下，例如
        // litellm_provider: "perplexity"）。该逻辑仅在回退时生效，因此对于已经直接传入
        // 匹配 provider 的调用方，现有查找语义不会改变。
        int prefixSlash = originalModelName.indexOf('/');
        if (prefixSlash > 0) {
            String modelPrefix = originalModelName.substring(0, prefixSlash);
            String canonicalFromPrefix = PROVIDERS_MAPPING.get(modelPrefix);
            if (canonicalFromPrefix != null && !canonicalFromPrefix.equalsIgnoreCase(provider)) {
                String prefixKey = createModelProviderKey(modelName, canonicalFromPrefix);
                ModelPrice prefixMatch = modelProviderPrices.get(prefixKey);
                if (prefixMatch != null) {
                    log.debug(
                            "使用模型名称中的 provider 前缀找到模型价格。原始模型：'{}'，传入的 provider：'{}'，由前缀推导出的 provider：'{}'",
                            originalModelName, provider, canonicalFromPrefix);
                    return prefixMatch;
                }
            }
        }

        log.debug("未找到模型 '{}'（provider：'{}'）的模型价格", modelName, provider);
        return DEFAULT_COST;
    }

    /**
     * 通过将点替换为连字符并转为小写来归一化模型名称。
     * 这处理了常见的命名变体：用户指定的模型名称如 "claude-3.5-sonnet" 或
     * "Claude-3.5-Sonnet"，而定价数据库使用的是 "claude-3-5-sonnet"。
     *
     * @param modelName 原始模型名称（调用方保证非 null 且非空白）
     * @return 归一化后的模型名称，点已替换为连字符并转为小写
     */
    private static String normalizeModelName(String modelName) {
        return modelName.replace('.', '-').toLowerCase(Locale.ROOT);
    }

    /**
     * 从模型名称中剥离日期后缀以支持回退定价查找。
     * 这处理了 provider 返回带日期的模型名称（例如 "gpt-5.2-2025-12-17"），
     * 而定价数据库只有基础模型名称（例如 "gpt-5.2"）的情况。
     *
     * 识别的日期模式：位于模型名称末尾的 YYYY-MM-DD（例如 "2025-12-17"）。
     *
     * @param modelName 模型名称
     * @return 若存在日期后缀则返回剥离后的小写模型名称，否则返回小写的原始名称
     */
    private static String stripDateSuffix(String modelName) {
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(DATE_SUFFIX_PATTERN, "");
    }

    /**
     * 从模型名称中剥离版本锁定后缀以支持回退定价查找。
     * Bedrock 发送带版本的名称（例如 "anthropic.claude-opus-4-6-v1:0"），
     * 而定价数据库存储的是基础名称（例如 "anthropic.claude-opus-4-6-v1"）。
     *
     * 识别的版本模式：位于模型名称末尾的 ":N"（一位或多位数字）。
     *
     * @param modelName 模型名称
     * @return 若存在版本后缀则返回剥离后的小写模型名称，否则返回小写的原始名称
     */
    private static String stripVersionSuffix(String modelName) {
        return modelName.toLowerCase(Locale.ROOT).replaceFirst(VERSION_SUFFIX_PATTERN, "");
    }

    public static BigDecimal getCostFromMetadata(JsonNode metadata) {
        return Optional.ofNullable(metadata)
                .map(md -> md.get("cost"))
                .map(cost -> Optional.ofNullable(cost.get("currency"))
                        .map(JsonNode::asText)
                        .filter("USD"::equals)
                        .flatMap(currency -> Optional.ofNullable(cost.get("total_cost")))
                        .map(JsonNode::decimalValue)
                        .orElse(BigDecimal.ZERO))
                .orElse(BigDecimal.ZERO);
    }

    private static Map<String, ModelPrice> parseModelPrices() throws IOException {
        Map<String, ModelCostData> modelCosts = JsonUtils.readJsonFile(MODEL_PRICES_FILE, new TypeReference<>() {
        });
        if (modelCosts.isEmpty()) {
            throw new UncheckedIOException(new IOException("Failed to load model prices"));
        }

        Map<String, ModelPrice> parsedModelPrices = new HashMap<>();
        modelCosts.forEach((modelName, modelCost) -> {
            String runtimeKey = buildRuntimeKey(modelName, modelCost);
            if (runtimeKey == null) {
                return;
            }
            ModelPrice price = buildModelPrice(modelCost);
            if (price != null) {
                parsedModelPrices.put(runtimeKey, price);
            }
        });

        // 应用 Opik 自有、能在每日 LiteLLM 同步流程中保留下来的覆盖配置。
        // 覆盖文件中支持三种形式：别名（`alias_of` 指向一个上游键）、全新模型，
        // 以及对已有键的价格覆盖。
        applyOverrides(parsedModelPrices, modelCosts);

        return parsedModelPrices;
    }

    /**
     * 若存在 {@link #MODEL_PRICES_OVERRIDES_FILE} 则加载它，并将其条目合并到给定的价格映射中。
     * 缺失或格式错误的覆盖文件会被容忍，仅记录一条日志消息。
     */
    private static void applyOverrides(Map<String, ModelPrice> prices,
            Map<String, ModelCostData> upstream) {
        Map<String, ModelCostData> overrides;
        try {
            overrides = JsonUtils.readJsonFile(MODEL_PRICES_OVERRIDES_FILE, new TypeReference<>() {
            });
        } catch (IOException | NullPointerException e) {
            log.warn("未加载模型价格覆盖配置（'{}'）：'{}'", MODEL_PRICES_OVERRIDES_FILE, e.getMessage());
            return;
        }
        if (overrides == null || overrides.isEmpty()) {
            return;
        }

        // 先应用直接覆盖，再应用别名。别名会针对 `upstream` 解析其目标
        // （它们的存在是为了用不同名称复用上游 LiteLLM 行），但它们的*价格*是从合并后的
        // `prices` 映射中读取的 —— 因此，当某个直接覆盖对上游键重新定价时，必须在解析其别名
        // 之前就位。JSON 文件中的顺序无关紧要。
        List<Map.Entry<String, ModelCostData>> aliasEntries = new ArrayList<>();
        overrides.forEach((modelName, override) -> {
            if (StringUtils.isNotBlank(override.aliasOf())) {
                aliasEntries.add(Map.entry(modelName, override));
            } else {
                applyDirectOverride(prices, modelName, override);
            }
        });
        aliasEntries.forEach(entry -> applyAlias(prices, upstream, entry.getKey(), entry.getValue()));
    }

    private static void applyAlias(Map<String, ModelPrice> prices, Map<String, ModelCostData> upstream,
            String aliasName, ModelCostData override) {
        String targetName = override.aliasOf();
        ModelCostData target = upstream.get(targetName);
        if (target == null) {
            log.warn("覆盖别名 '{}' 指向未知的上游模型 '{}'；跳过", aliasName, targetName);
            return;
        }
        if (StringUtils.isNotBlank(target.aliasOf())) {
            log.warn("覆盖别名 '{}' 指向另一个别名 '{}'；不支持别名的别名，跳过",
                    aliasName, targetName);
            return;
        }
        String targetKey = buildRuntimeKey(targetName, target);
        if (targetKey == null) {
            log.warn("覆盖别名 '{}' 的目标 '{}' 没有可加载的 provider；跳过", aliasName, targetName);
            return;
        }
        ModelPrice targetPrice = prices.get(targetKey);
        if (targetPrice == null) {
            log.warn("覆盖别名 '{}' 的目标 '{}'（键 '{}'）没有已加载的价格；跳过",
                    aliasName, targetName, targetKey);
            return;
        }
        // 别名继承目标的 litellm_provider，使别名和目标在运行时键中共用同一个 provider。
        String aliasKey = buildRuntimeKey(aliasName, target);
        if (aliasKey == null) {
            return;
        }
        prices.put(aliasKey, targetPrice);
    }

    private static void applyDirectOverride(Map<String, ModelPrice> prices, String modelName, ModelCostData override) {
        String runtimeKey = buildRuntimeKey(modelName, override);
        if (runtimeKey == null) {
            log.warn("覆盖条目 '{}' 的 provider '{}' 未知；跳过",
                    modelName, override.litellmProvider());
            return;
        }
        ModelPrice price = buildModelPrice(override);
        if (price != null) {
            prices.put(runtimeKey, price);
        }
    }

    /**
     * 为价格映射条目计算运行时键 {@code <parsedModel>/<canonicalProvider>}。
     * 如果 provider 不在 {@link #PROVIDERS_MAPPING} 中，或该条目对解析出的 provider
     * 无效（例如旧版 Bedrock 路径），则返回 null。
     */
    private static String buildRuntimeKey(String modelName, ModelCostData modelCost) {
        String provider = Optional.ofNullable(modelCost.litellmProvider()).orElse("");
        String canonical = PROVIDERS_MAPPING.get(provider);
        if (canonical == null) {
            return null;
        }
        if (!isValidModelProvider(modelName, canonical)) {
            return null;
        }
        return createModelProviderKey(parseModelName(modelName), canonical);
    }

    private static ModelPrice buildModelPrice(ModelCostData modelCost) {
        String provider = Optional.ofNullable(modelCost.litellmProvider()).orElse("");
        if (!PROVIDERS_MAPPING.containsKey(provider)) {
            return null;
        }

        BigDecimal inputPrice = Optional.ofNullable(modelCost.inputCostPerToken()).map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal outputPrice = Optional.ofNullable(modelCost.outputCostPerToken()).map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal cacheCreationInputTokenPrice = Optional.ofNullable(modelCost.cacheCreationInputTokenCost())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal cacheReadInputTokenPrice = Optional.ofNullable(modelCost.cacheReadInputTokenCost())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal videoOutputPrice = Optional.ofNullable(modelCost.outputCostPerVideoPerSecond())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal audioInputCharacterPrice = Optional.ofNullable(modelCost.inputCostPerCharacter())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal inputAudioTokenPrice = Optional.ofNullable(modelCost.inputCostPerAudioToken())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        BigDecimal outputAudioTokenPrice = Optional.ofNullable(modelCost.outputCostPerAudioToken())
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
        // 整体提示词分层：一旦提示词严格超过阈值，LiteLLM 的 above_{128k,200k,272k}_tokens
        // 费率会整体替换基础费率。仅当某一层中至少有一个费率非零时才纳入该层；这样可让
        // ~99% 未配置分层字段的模型保持分层列表为空。按降序排序，使 ModelPrice 上的
        // 生效价格辅助方法无需额外记账即可选出适用的最高层。
        // 当前可命中的模型：Gemini 1.5 Flash 为 128K，Gemini 2.5 Pro / Claude Sonnet 4.5
        // 为 200K，GPT-5.4 和 GPT-5.5（openai 和 azure）为 272K。
        List<ModelPrice.PromptTier> promptTiers = new ArrayList<>();
        addTierIfPresent(promptTiers, ModelPrice.TIER_THRESHOLD_272K,
                modelCost.inputCostPerTokenAbove272kTokens(), modelCost.outputCostPerTokenAbove272kTokens(),
                null, null);
        addTierIfPresent(promptTiers, ModelPrice.TIER_THRESHOLD_200K,
                modelCost.inputCostPerTokenAbove200kTokens(), modelCost.outputCostPerTokenAbove200kTokens(),
                modelCost.cacheCreationInputTokenCostAbove200kTokens(),
                modelCost.cacheReadInputTokenCostAbove200kTokens());
        addTierIfPresent(promptTiers, ModelPrice.TIER_THRESHOLD_128K,
                modelCost.inputCostPerTokenAbove128kTokens(), modelCost.outputCostPerTokenAbove128kTokens(),
                null, null);

        ModelMode mode = ModelMode.fromValue(modelCost.mode());

        BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> calculator = resolveCalculator(provider, mode,
                inputPrice, outputPrice, cacheCreationInputTokenPrice, cacheReadInputTokenPrice,
                videoOutputPrice, audioInputCharacterPrice);

        return ModelPrice.builder()
                .inputPrice(inputPrice)
                .outputPrice(outputPrice)
                .cacheCreationInputTokenPrice(cacheCreationInputTokenPrice)
                .cacheReadInputTokenPrice(cacheReadInputTokenPrice)
                .videoOutputPrice(videoOutputPrice)
                .audioInputCharacterPrice(audioInputCharacterPrice)
                .inputAudioTokenPrice(inputAudioTokenPrice)
                .outputAudioTokenPrice(outputAudioTokenPrice)
                .calculator(calculator)
                .promptTiers(List.copyOf(promptTiers))
                .build();
    }

    /**
     * 当至少一个按费率的 JSON 字符串非 null 且解析为正数金额时，将一个
     * {@link ModelPrice.PromptTier} 追加到 {@code tiers} 中。null 值（该层未发布费率 ——
     * 例如 LiteLLM 在 128K/272K 不携带缓存费率）会被分层记录视为 ZERO，
     * 并被生效价格辅助方法跳过。
     */
    private static void addTierIfPresent(List<ModelPrice.PromptTier> tiers, int threshold,
            String inputPrice, String outputPrice,
            String cacheCreationInputTokenPrice, String cacheReadInputTokenPrice) {
        BigDecimal in = parseTierRate(inputPrice);
        BigDecimal out = parseTierRate(outputPrice);
        BigDecimal cc = parseTierRate(cacheCreationInputTokenPrice);
        BigDecimal cr = parseTierRate(cacheReadInputTokenPrice);
        if (isPositive(in) || isPositive(out) || isPositive(cc) || isPositive(cr)) {
            tiers.add(ModelPrice.PromptTier.builder()
                    .threshold(threshold)
                    .inputPrice(in)
                    .outputPrice(out)
                    .cacheCreationInputTokenPrice(cc)
                    .cacheReadInputTokenPrice(cr)
                    .build());
        }
    }

    private static BigDecimal parseTierRate(String raw) {
        return Optional.ofNullable(raw).map(BigDecimal::new).orElse(BigDecimal.ZERO);
    }

    private static String parseModelName(String modelName) {
        int prefixIndex = modelName.indexOf('/');
        return prefixIndex == -1 ? modelName : modelName.substring(prefixIndex + 1);
    }

    private static String createModelProviderKey(String modelName, String provider) {
        return modelName + MODEL_PROVIDER_SEPARATOR + provider;
    }

    private static boolean isValidModelProvider(String modelName, String provider) {
        if (BEDROCK_PROVIDER.equals(provider) && modelName.contains("/")) {
            // 名称中包含 / 的 Bedrock 模型不被支持，因为其被视为旧模型
            return false;
        }

        return true;
    }

    private static BiFunction<ModelPrice, Map<String, Integer>, BigDecimal> resolveCalculator(
            String provider,
            ModelMode mode,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            BigDecimal cacheCreationInputTokenPrice,
            BigDecimal cacheReadInputTokenPrice,
            BigDecimal videoOutputPrice,
            BigDecimal audioInputCharacterPrice) {

        if (mode.isVideoGeneration() && isPositive(videoOutputPrice)) {
            return SpanCostCalculator::videoGenerationCost;
        }

        if (mode.isAudioSpeech() && isPositive(audioInputCharacterPrice)) {
            return SpanCostCalculator::audioSpeechCost;
        }

        if (isPositive(cacheCreationInputTokenPrice) || isPositive(cacheReadInputTokenPrice)) {
            return PROVIDERS_CACHE_COST_CALCULATOR.getOrDefault(provider, SpanCostCalculator::textGenerationCost);
        }

        if (isPositive(inputPrice) || isPositive(outputPrice)) {
            return SpanCostCalculator::textGenerationCost;
        }

        return SpanCostCalculator::defaultCost;
    }

    private static boolean isPositive(BigDecimal value) {
        return Optional.ofNullable(value).map(v -> v.compareTo(BigDecimal.ZERO) > 0).orElse(false);
    }

    @RequiredArgsConstructor
    private enum ModelMode {
        TEXT_GENERATION("text_generation"),
        CHAT("chat"),
        EMBEDDING("embedding"),
        COMPLETION("completion"),
        IMAGE_GENERATION("image_generation"),
        AUDIO_TRANSCRIPTION("audio_transcription"),
        AUDIO_SPEECH("audio_speech"),
        MODERATION("moderation"),
        RERANK("rerank"),
        SEARCH("search"),
        VIDEO_GENERATION("video_generation");

        private static final ModelMode DEFAULT = TEXT_GENERATION;
        private final String value;

        static ModelMode fromValue(String rawValue) {
            if (StringUtils.isBlank(rawValue)) {
                return DEFAULT;
            }

            for (ModelMode mode : values()) {
                if (mode.value.equalsIgnoreCase(rawValue)) {
                    return mode;
                }
            }

            return DEFAULT;
        }

        boolean isVideoGeneration() {
            return this == VIDEO_GENERATION;
        }

        boolean isAudioSpeech() {
            return this == AUDIO_SPEECH;
        }
    }
}
