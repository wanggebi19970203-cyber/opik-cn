package com.comet.opik.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.annotations.VisibleForTesting;
import dev.langchain4j.model.openai.internal.chat.Message;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@UtilityClass
@Slf4j
public class JsonUtils {

    /**
     * 用于内部 JSON 处理的 ObjectMapper。
     * 以最小默认值（20MB）初始化，并在启动期间由 OpikApplication
     * 重新配置以匹配 config.yml 设置。
     */
    private static volatile ObjectMapper MAPPER;

    static {
        MAPPER = createConfiguredMapper(StreamReadConstraints.DEFAULT_MAX_STRING_LEN, -1L);
        log.info("JsonUtils 已用默认 maxStringLength: '{}'、maxDocumentLength: 无限制 初始化",
                StreamReadConstraints.DEFAULT_MAX_STRING_LEN);
    }

    /**
     * 用 config.yml 中的限制配置 JsonUtils。
     * 由 OpikApplication 在启动期间调用。
     *
     * @param maxStringLength   单个字符串值的最大长度（字节）
     * @param maxDocumentLength 整个文档的最大长度（字节）（{@code <= 0} 表示无限制）
     */
    public static synchronized void configure(int maxStringLength, long maxDocumentLength) {
        MAPPER = createConfiguredMapper(maxStringLength, maxDocumentLength);
        log.info("JsonUtils 已配置 maxStringLength: '{}' 字节（'{}'MB），maxDocumentLength: '{}' 字节",
                maxStringLength, maxStringLength / 1024 / 1024, maxDocumentLength);
    }

    /**
     * 创建并用指定限制配置一个 ObjectMapper。
     * 该配置与 OpikApplication 中的 Dropwizard ObjectMapper 设置匹配。
     *
     * @param maxStringLength   单个字符串值的最大长度（字节）
     * @param maxDocumentLength 整个文档的最大长度（字节）（{@code <= 0} 表示无限制）
     * @return 已配置的 ObjectMapper 实例
     */
    @VisibleForTesting
    static ObjectMapper createConfiguredMapper(int maxStringLength, long maxDocumentLength) {
        ObjectMapper mapper = new ObjectMapper();

        // 与 Dropwizard 默认值匹配的基本配置
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE);
        mapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS, false);
        mapper.configure(SerializationFeature.INDENT_OUTPUT, false);
        mapper.configure(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT, false);
        mapper.enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.mappedFeature());

        // 注册 JavaTimeModule 以正确处理日期/时间
        mapper.registerModule(new JavaTimeModule()
                .addDeserializer(BigDecimal.class, JsonBigDecimalDeserializer.INSTANCE)
                .addDeserializer(Message.class, OpenAiMessageJsonDeserializer.INSTANCE)
                .addDeserializer(Duration.class, StrictDurationDeserializer.INSTANCE));

        applyStreamReadConstraints(mapper, maxStringLength, maxDocumentLength);

        return mapper;
    }

    /**
     * 将 JSON 流读取大小限制应用到 {@code mapper} 的 factory。注意：{@code maxDocumentLength}
     * 只在解析器缓冲区重新填充（流/读取器输入，例如 HTTP 请求体）时强制 —— 完全
     * 在内存中的 {@code String}/{@code byte[]} 读取会绕过它；{@code maxStringLength} 对两者都适用。
     */
    public static void applyStreamReadConstraints(@NonNull ObjectMapper mapper, int maxStringLength,
            long maxDocumentLength) {
        StreamReadConstraints readConstraints = StreamReadConstraints.builder()
                .maxStringLength(maxStringLength)
                .maxDocumentLength(maxDocumentLength)
                .build();
        mapper.getFactory().setStreamReadConstraints(readConstraints);
    }

    /**
     * 获取共享的 ObjectMapper 实例。
     *
     * @return 已配置的 ObjectMapper
     */
    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    /**
     * 创建一个新的空 ObjectNode。
     *
     * @return 一个新的 ObjectNode 实例
     */
    public static ObjectNode createObjectNode() {
        return MAPPER.createObjectNode();
    }

    /**
     * 将对象 {@code overrides} 浅合并到 {@code base} 之上，返回一个新的对象节点。
     * {@code overrides} 中存在的键会被添加/替换；仅存在于 {@code base} 中的键被保留。
     * <p>
     * 只有对象覆盖是可合并的：{@code null}、标量或数组的 {@code overrides} 会被忽略，
     * 并原样返回 {@code base}，因此非对象值永远不会被传播进存储
     * （这会违反调用方/UI 所依赖的对象形状元数据契约）。同样地，
     * 非对象的 {@code base} 会被丢弃而不是被合并到其上。
     */
    public static JsonNode merge(JsonNode base, JsonNode overrides) {
        if (overrides == null || !overrides.isObject()) {
            return base;
        }
        ObjectNode result = MAPPER.createObjectNode();
        if (base != null && base.isObject()) {
            result.setAll((ObjectNode) base);
        }
        result.setAll((ObjectNode) overrides);
        return result;
    }

    /**
     * 创建一个新的空 ArrayNode。
     *
     * @return 一个新的 ArrayNode 实例
     */
    public static ArrayNode createArrayNode() {
        return MAPPER.createArrayNode();
    }

    /**
     * 将 Java 对象转换为 JsonNode。
     *
     * @param value 要转换的 Java 对象
     * @return JsonNode 表示
     */
    public static JsonNode valueToTree(@NonNull Object value) {
        return MAPPER.valueToTree(value);
    }

    /**
     * 将 JsonNode 转换为指定类型的 Java 对象。
     *
     * @param node 要转换的 JsonNode
     * @param valueType 目标类类型
     * @return 转换后的 Java 对象
     */
    public static <T> T treeToValue(@NonNull JsonNode node, @NonNull Class<T> valueType) {
        try {
            return MAPPER.treeToValue(node, valueType);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 将值序列化为字节数组。
     *
     * @param value 要序列化的值
     * @return 序列化后的字节数组
     */
    public static byte[] writeValueAsBytes(@NonNull Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode getJsonNodeFromString(@NonNull String value) {
        try {
            return MAPPER.readTree(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static JsonNode getJsonNodeFromStringWithFallback(@NonNull String value) {
        try {
            return getJsonNodeFromString(value);
        } catch (UncheckedIOException e) {
            return TextNode.valueOf(value);
        }
    }

    public static JsonNode getJsonNodeFromString(@NonNull InputStream value) {
        try {
            return MAPPER.readTree(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String getStringOrDefault(JsonNode jsonNode) {
        return Optional.ofNullable(jsonNode).map(JsonNode::toString).orElse("");
    }

    public static JsonNode getJsonNodeOrDefault(String str) {
        return Optional.ofNullable(str)
                .filter(s -> !s.isBlank())
                .map(JsonUtils::getJsonNodeFromString)
                .orElse(null);
    }

    public JsonNode readTree(@NonNull Object content) {
        return MAPPER.convertValue(content, JsonNode.class);
    }

    public <T> T readValue(@NonNull String content, @NonNull TypeReference<T> valueTypeRef) {
        try {
            return MAPPER.readValue(content, valueTypeRef);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> T readValue(@NonNull String content, @NonNull Class<T> valueTypeRef) {
        try {
            return MAPPER.readValue(content, valueTypeRef);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> T readValue(@NonNull InputStream inputStream, @NonNull TypeReference<T> valueTypeRef) {
        try {
            return MAPPER.readValue(inputStream, valueTypeRef);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> T readValue(@NonNull byte[] content, @NonNull Class<T> valueTypeRef) {
        try {
            return MAPPER.readValue(content, valueTypeRef);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> T readCollectionValue(@NonNull String content, @NonNull Class<? extends Collection> collectionClass,
            @NonNull Class<?> valueClass) {
        return readCollectionValue(content,
                MAPPER.getTypeFactory().constructCollectionType(collectionClass, valueClass));
    }

    public <T> T readCollectionValue(@NonNull String content, @NonNull CollectionType collectionType) {
        try {
            return MAPPER.readValue(content, collectionType);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public <T> String writeListOrDefaultEmpty(List<T> list) {
        return writeValueAsString(list == null ? List.of() : list);
    }

    public String writeValueAsString(@NonNull Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public void writeValueAsString(@NonNull ByteArrayOutputStream baos, @NonNull Object value) {
        try {
            MAPPER.writeValue(baos, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeValue(@NonNull Writer writer, @NonNull Object value) {
        try {
            MAPPER.writeValue(writer, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeValue(@NonNull OutputStream outputStream, @NonNull Object value) {
        try {
            MAPPER.writeValue(outputStream, value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 节点的序列化字符（UTF-16）长度，不物化其 JSON 字符串 —— 节点被
     * 流经一个计数 writer，因此大字段只花费 O(1) 的瞬态堆，而不是完整
     * 拷贝。{@code null} 或 JSON-null 节点计为 0。使用 {@link #getSerializedLengthInBytes}
     * 来强制以字节计量的限制。
     */
    public long getSerializedLength(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0L;
        }
        var counter = new CountingWriter();
        writeValue(counter, node);
        return counter.getCount();
    }

    /**
     * 节点的序列化 UTF-8 字节长度，不物化其 JSON —— 节点被流经
     * 一个计数输出流，因此大字段只花费 O(1) 的瞬态堆，而不是完整拷贝。这是
     * {@link #getSerializedLength} 的字节精确变体，用于强制以字节计量的上限
     * （非 ASCII 文本会使字节数超过字符数）。{@code null} 或 JSON-null
     * 节点计为 0。
     */
    public long getSerializedLengthInBytes(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0L;
        }
        var counter = new CountingOutputStream();
        writeValue(counter, node);
        return counter.getCount();
    }

    public <T> T readJsonFile(@NonNull String fileName, @NonNull TypeReference<T> valueTypeRef) throws IOException {
        try (InputStream inputStream = JsonUtils.class.getClassLoader().getResourceAsStream(fileName)) {
            return MAPPER.readValue(inputStream, valueTypeRef);
        }
    }

    public <T> T convertValue(@NonNull Object fromValue, @NonNull TypeReference<T> toValueTypeRef) {
        return MAPPER.convertValue(fromValue, toValueTypeRef);
    }

    public static JsonNode prependField(
            JsonNode jsonNode,
            @NonNull String fieldName,
            String fieldValue) {
        if (StringUtils.isBlank(fieldValue)) {
            return jsonNode;
        }

        TextNode valueNode = MAPPER.getNodeFactory().textNode(fieldValue);
        return prependField(jsonNode, fieldName, valueNode);
    }

    public static JsonNode prependField(
            JsonNode jsonNode,
            @NonNull String fieldName,
            List<String> fieldValues) {
        if (CollectionUtils.isEmpty(fieldValues)) {
            return jsonNode;
        }

        ArrayNode arrayNode = MAPPER.createArrayNode();
        fieldValues.forEach(arrayNode::add);

        return prependField(jsonNode, fieldName, arrayNode);
    }

    private static JsonNode prependField(
            JsonNode jsonNode,
            @NonNull String fieldKey,
            @NonNull JsonNode fieldValue) {
        ObjectNode result = MAPPER.createObjectNode();
        result.set(fieldKey, fieldValue);

        return copyJsonNode(jsonNode, result);
    }

    private static ObjectNode copyJsonNode(JsonNode jsonNode, @NonNull ObjectNode result) {
        if (jsonNode != null && jsonNode.isObject()) {
            result.setAll((ObjectNode) jsonNode);
        }

        return result;
    }
}
