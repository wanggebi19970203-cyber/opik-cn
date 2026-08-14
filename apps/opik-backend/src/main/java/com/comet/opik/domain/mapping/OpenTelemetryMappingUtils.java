package com.comet.opik.domain.mapping;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 用于在 OpenTelemetry 数据上下文中映射和提取字段的工具类。
 * 提供处理 JSON 数据、解析复杂值，以及为分析或监控目的映射用量相关字段的方法。
 */
@Slf4j
public class OpenTelemetryMappingUtils {

    private static final Map<String, String> USAGE_KEYS_MAPPING = Map.of(
            "input_tokens", "prompt_tokens",
            "output_tokens", "completion_tokens",
            // Claude Code 发出简短的缓存 token 名称；规范化为 Anthropic 缓存成本路径（SpanCostCalculator）
            // 能识别的名称，否则缓存定价会被跳过。
            "cache_read_tokens", "cache_read_input_tokens",
            "cache_creation_tokens", "cache_creation_input_tokens");

    /**
     * 从 AnyValue 对象中提取值，并将其写入 ObjectNode 中指定的 JSON 字段。
     * 根据值的类型和格式，该方法将其作为文本数据、数值数据、布尔数据或数组来处理并相应转换。
     *
     * @param node 应写入数据的 JSON 节点
     * @param key 用于将提取的值添加到 JSON 节点的键
     * @param value 包含待提取和写入值的 AnyValue 对象
     */
    public static void extractToJsonColumn(ObjectNode node, String key, @NonNull AnyValue value) {
        switch (value.getValueCase()) {
            case STRING_VALUE -> {
                var stringValue = value.getStringValue();
                // 检查字符串值实际上是一个字符串还是一个字符串化的 JSON
                if (stringValue.startsWith("\"") || stringValue.startsWith("[")
                        || stringValue.startsWith("{")) {
                    try {
                        var jsonNode = JsonUtils.getJsonNodeFromString(stringValue);
                        if (jsonNode.isTextual()) {
                            try {
                                jsonNode = JsonUtils.getJsonNodeFromString(jsonNode.asText());
                            } catch (UncheckedIOException e) {
                                log.warn("解析键 {} 的嵌套 JSON 字符串失败: {}。按纯文本使用。",
                                        key, e.getMessage());
                                node.put(key, jsonNode.asText());
                                return;
                            }
                        }
                        node.set(key, jsonNode);
                    } catch (UncheckedIOException e) {
                        log.warn("解析键 {} 的 JSON 字符串失败: {}。按纯文本使用。", key,
                                e.getMessage());
                        node.put(key, stringValue);
                    }
                } else {
                    node.put(key, stringValue);
                }
            }
            case INT_VALUE -> node.put(key, value.getIntValue());
            case DOUBLE_VALUE -> node.put(key, value.getDoubleValue());
            case BOOL_VALUE -> node.put(key, value.getBoolValue());
            case ARRAY_VALUE -> {
                var array = JsonUtils.createArrayNode();
                value.getArrayValue().getValuesList().forEach(val -> array.add(val.getStringValue()));
                node.set(key, array);
            }
            default -> log.warn("不支持的属性: {} -> {}", key, value);
        }
    }

    /**
     * 计算某属性在映射规则下的存储键。
     * <p>
     * 对于精确匹配规则，原键原样返回。对于前缀规则，前缀会被去掉，并且后缀前导的点号也会被移除。
     * 后缀可能为空，此时调用方通常应把该值合并到外层对象中。如果前缀规则实际上并未匹配该键
     * （键比前缀短，或者在前缀不含点号时去掉前缀后的后缀不以点号开头），原键原样返回。
     *
     * @param rule 要应用的映射规则；不能为 {@code null}
     * @param key 要转换的属性键；不能为 {@code null}
     * @return 要使用的存储键，绝不为 {@code null}
     */
    public static String storageKey(@NonNull OpenTelemetryMappingRule rule, @NonNull String key) {
        if (!rule.isPrefix()) {
            return key;
        }

        String prefix = rule.getRule();
        if (key.length() < prefix.length()) {
            return key;
        }
        String suffix = key.substring(prefix.length());

        // 当后缀为空、后缀以 '.' 开头，或规则本身以 '.' 结尾时进行去除（例如 "gen_ai.input."）。
        // 像 "input" 对 "input_tokens" 的前缀匹配绝不能去除。
        if (suffix.isEmpty() || suffix.startsWith(".") || prefix.endsWith(".")) {
            if (suffix.startsWith(".")) {
                suffix = suffix.substring(1);
            }
            return suffix;
        }

        return key;
    }

    // 前缀规则（例如 `gen_ai.usage.`）把用量名称放在后缀中；精确匹配规则（例如 Claude Code 的 `input_tokens`）
    // 使用完整键。
    private static String usageKey(OpenTelemetryMappingRule rule, String key) {
        return rule.isPrefix() ? key.substring(rule.getRule().length()) : key;
    }

    /**
     * 从给定值中提取用量相关字段，并将它们添加到用量映射中。
     * 该方法支持从整数值、字符串值和 JSON 对象中提取用量。
     *
     * @param usage 将存储所提取用量字段的映射
     * @param rule 用于处理键和值的映射规则
     * @param key 与该值关联的属性键
     * @param value 待处理和提取的值
     */
    public static void extractUsageField(@NonNull Map<String, Integer> usage, @NonNull OpenTelemetryMappingRule rule,
            @NonNull String key, @NonNull AnyValue value) {
        // 用量可能以单个 int 或字符串值出现，也可能是一个 JSON 对象
        if (value.hasIntValue()) {
            var actualKey = usageKey(rule, key);
            usage.put(USAGE_KEYS_MAPPING.getOrDefault(actualKey, actualKey), (int) value.getIntValue());
        } else if (value.hasStringValue()) {
            boolean extracted = tryExtractUsageFromString(usage, rule, key, value.getStringValue());
            if (!extracted) {
                // 从 JSON 对象中提取
                tryExtractUsageFromJsonObject(usage, key, value.getStringValue());
            }
        }
    }

    /**
     * 从 AnyValue 中提取预先计算好的成本值。某些集成（例如 LiteLLM）会把确切的请求成本作为浮点属性发送；
     * 我们将其作为权威成本使用，而不是重新计算。支持 double、integer 和数值字符串值。
     *
     * @param value 包含成本的 AnyValue
     * @return 解析出的成本，如果值不是可解析的数字则为空
     */
    public static Optional<BigDecimal> extractCost(@NonNull AnyValue value) {
        return switch (value.getValueCase()) {
            case DOUBLE_VALUE -> parseCostDouble(value.getDoubleValue());
            case INT_VALUE -> parseCostInt(value.getIntValue());
            case STRING_VALUE -> parseCostString(value.getStringValue());
            default -> {
                log.warn("成本提取遇到不支持的值类型: '{}'", value.getValueCase());
                yield Optional.empty();
            }
        };
    }

    private static Optional<BigDecimal> parseCostDouble(double value) {
        // BigDecimal.valueOf 对 NaN / Infinity 会抛出 NumberFormatException。使用更宽的 catch 可以保证
        // 无论将来转换如何失败，成本提取都不会致命。
        try {
            return Optional.of(BigDecimal.valueOf(value));
        } catch (RuntimeException exception) {
            log.warn("解析成本 double 值 '{}' 失败", value, exception);
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> parseCostInt(long value) {
        try {
            return Optional.of(BigDecimal.valueOf(value));
        } catch (RuntimeException exception) {
            log.warn("解析成本 int 值 '{}' 失败", value, exception);
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> parseCostString(String stringValue) {
        try {
            return Optional.of(new BigDecimal(stringValue.strip()));
        } catch (RuntimeException exception) {
            log.warn("将成本字符串值 '{}' 作为数字解析失败", stringValue, exception);
            return Optional.empty();
        }
    }

    /**
     * 从 AnyValue 中提取标签并以字符串列表返回。
     * 支持从字符串值（逗号分隔）、数组值和 JSON 数组中提取标签。
     *
     * @param value 包含标签数据的 AnyValue
     * @return 提取出的标签字符串列表，如果没有找到有效标签则为空
     */
    public static List<String> extractTags(@NonNull AnyValue value) {
        switch (value.getValueCase()) {
            case STRING_VALUE -> {
                var stringValue = value.getStringValue();

                // 检查它是否是 JSON 数组字符串
                if (stringValue.startsWith("[") && stringValue.endsWith("]")) {
                    try {
                        JsonNode arrayNode = JsonUtils.getJsonNodeFromString(stringValue);
                        if (arrayNode.isArray()) {
                            List<String> tags = new ArrayList<>();
                            arrayNode.forEach(node -> {
                                if (node.isTextual()) {
                                    String tag = node.asText().trim();
                                    if (!tag.isEmpty()) {
                                        tags.add(tag);
                                    }
                                }
                            });
                            return tags;
                        }
                    } catch (UncheckedIOException e) {
                        log.debug("解析标签的 JSON 数组失败: {}。按逗号分隔字符串处理。",
                                e.getMessage());
                    }
                }

                // 按逗号分隔字符串处理
                return Stream.of(stringValue.split(","))
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .toList();
            }

            case ARRAY_VALUE -> {
                return value.getArrayValue().getValuesList().stream()
                        .filter(AnyValue::hasStringValue)
                        .map(AnyValue::getStringValue)
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .toList();
            }

            default -> {
                log.warn("标签提取遇到不支持的值类型: {}", value.getValueCase());
                return List.of();
            }
        }
    }

    /**
     * 尝试把字符串值解析为整数并添加到用量映射中。
     *
     * @param usage       要更新的用量映射
     * @param rule        正在处理的映射规则
     * @param key         原始属性键
     * @param stringValue 要解析的字符串值
     * @return 如果字符串被成功解析并添加则为 true，否则为 false
     */
    private static boolean tryExtractUsageFromString(Map<String, Integer> usage, OpenTelemetryMappingRule rule,
            String key, String stringValue) {
        try {
            int intValue = Integer.parseInt(stringValue);
            var actualKey = usageKey(rule, key);
            usage.put(USAGE_KEYS_MAPPING.getOrDefault(actualKey, actualKey), intValue);
            return true;
        } catch (NumberFormatException e) {
            log.debug("将键 '{}' 的用量字符串值 '{}' 解析为整数失败", stringValue, key);
            return false;
        }
    }

    /**
     * 从 JSON 对象字符串中提取用量字段并添加到用量映射中。
     *
     * @param usage       要更新的用量映射
     * @param key         原始属性键（用于错误日志）
     * @param stringValue 要解析的 JSON 字符串
     * @throws BadRequestException 如果 JSON 解析严重失败
     */
    private static void tryExtractUsageFromJsonObject(Map<String, Integer> usage, String key, String stringValue) {
        try {
            JsonNode usageNode = JsonUtils.getJsonNodeFromString(stringValue);
            if (usageNode.isTextual()) {
                try {
                    usageNode = JsonUtils.getJsonNodeFromString(usageNode.asText());
                } catch (UncheckedIOException e) {
                    log.warn(
                            "解析用量字段 {} 的嵌套 JSON 字符串失败: {}。跳过用量提取。",
                            key, e.getMessage());
                    return;
                }
            }

            // 我们期望用量字段只有整数
            usageNode.properties().forEach(entry -> {
                if (entry.getValue().isNumber()) {
                    usage.put(
                            USAGE_KEYS_MAPPING.getOrDefault(entry.getKey(), entry.getKey()),
                            entry.getValue().intValue());
                } else {
                    log.warn("无法识别的用量属性 {}: {}", entry.getKey(), entry.getValue());
                }
            });
        } catch (UncheckedIOException ex) {
            log.warn("解析用量字段 {} 的 JSON 字符串失败: {}。跳过用量提取。", key,
                    ex.getMessage());
            throw new BadRequestException(
                    "Failed to parse JSON string for usage field " + key + ": " + ex.getMessage());
        }
    }
}
