package com.comet.opik.domain;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span.SpanBuilder;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRuleFactory;
import com.comet.opik.domain.mapping.otel.ElasticInferenceServiceResolver;
import com.comet.opik.domain.mapping.otel.GeneralMappingRules;
import com.comet.opik.domain.mapping.otel.GoogleProviderResolver;
import com.comet.opik.domain.retention.RetentionUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.mapping.OpenTelemetryEventsMapper.processEvents;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractCost;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractTags;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractToJsonColumn;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.extractUsageField;
import static com.comet.opik.domain.mapping.OpenTelemetryMappingUtils.storageKey;

@UtilityClass
@Slf4j
public class OpenTelemetryMapper {

    /**
     * 将 OpenTelemetry Span 转换为 Opik Span。尽管概念上相似，但仍需要一些转换，
     * 尤其是在 ID 方面。
     * <p>
     * 我们会将该 span 关联到一个预先计算好的 Opik traceId，该 traceId 带有我们能获取到的最近时间戳。
     * 我们可以从该 traceId 中提取时间戳，并用于 spanId 的 otel -> opik 转换，这样该 trace 的所有 span ID
     * 都可预测，并且使用相同的时间戳参考。
     *
     * @param otelSpan        OpenTelemetry Span
     * @param opikTraceId     用于该 span 的 Opik UUID
     * @param integrationName 检测到的集成名称（如有）
     * @return 转换后的 Opik Span
     */
    public static com.comet.opik.api.Span toOpikSpan(Span otelSpan, UUID opikTraceId, String integrationName) {
        var traceTimestamp = extractTimestampFromUUIDv7(opikTraceId);

        var startTimeMs = Duration.ofNanos(otelSpan.getStartTimeUnixNano()).toMillis();
        var endTimeMs = Duration.ofNanos(otelSpan.getEndTimeUnixNano()).toMillis();

        var otelSpanId = otelSpan.getSpanId();

        // 检查 opik.trace_id、opik.span_id 和 opik.parent_span_id 覆盖属性。
        // 当这些属性存在时，会将 span 原样连接到现有的 OPIK trace/span（无需 ID 转换）。
        // opik.span_id 通常由 SDK 的 OpikSpanProcessor 设置，它为每个 span 生成一个 UUIDv7，
        // 并通过 opik.parent_span_id 将其串联起来，使附加子树的子孙 span 保持正确的链接关系。
        var opikSpanIdOverride = extractOpikSpanId(otelSpan);
        var opikTraceIdOverride = extractOpikTraceId(otelSpan);
        var opikParentSpanIdOverride = extractOpikParentSpanId(otelSpan);

        var opikSpanId = opikSpanIdOverride
                .orElseGet(() -> convertOtelIdToUUIDv7(otelSpanId.toByteArray(), traceTimestamp));

        UUID effectiveTraceId;
        UUID opikParentSpanId;

        if (opikTraceIdOverride.isPresent()) {
            effectiveTraceId = opikTraceIdOverride.get();
            // 当设置 opik.trace_id 时，如果存在 opik.parent_span_id 则使用它，否则为 null
            // （span 作为根 span 直接连接到 trace）
            opikParentSpanId = opikParentSpanIdOverride.orElse(null);
        } else {
            if (opikParentSpanIdOverride.isPresent()) {
                log.warn("Span '{}' 具有 '{}' 但缺少 '{}'，忽略父 span ID 覆盖",
                        otelSpan.getName(), GeneralMappingRules.OPIK_PARENT_SPAN_ID_ATTR,
                        GeneralMappingRules.OPIK_TRACE_ID_ATTR);
            }
            effectiveTraceId = opikTraceId;
            var otelParentSpanId = otelSpan.getParentSpanId();
            // 有些插桩会将 parent_span_id 设置为 16 字节的 trace id，表示“顶层 span”。
            // 转换它会得到一个与 Redis 中映射的 trace id 不匹配的悬空 UUID，
            // 因此将其视为根 span。
            boolean parentIsTraceId = !otelParentSpanId.isEmpty()
                    && otelParentSpanId.equals(otelSpan.getTraceId());
            opikParentSpanId = (otelParentSpanId.isEmpty() || parentIsTraceId)
                    ? null
                    : convertOtelIdToUUIDv7(otelParentSpanId.toByteArray(), traceTimestamp);
        }

        var spanBuilder = com.comet.opik.api.Span.builder()
                .id(opikSpanId)
                .traceId(effectiveTraceId)
                .parentSpanId(opikParentSpanId)
                .name(otelSpan.getName())
                .type(SpanType.general)
                .source(Source.SDK)
                .startTime(Instant.ofEpochMilli(startTimeMs))
                .endTime(Instant.ofEpochMilli(endTimeMs));

        List<Span.Event> events = otelSpan.getEventsList();
        enrichSpanWithAttributes(spanBuilder, otelSpan.getAttributesList(), integrationName, events,
                otelSpan.getName());

        extractErrorInfo(otelSpan).ifPresent(spanBuilder::errorInfo);

        return spanBuilder.build();
    }

    /**
     * 从杂乱的 KeyValue 列表中提取相关内容，并将值添加到 input/output/metadata/model/usage。
     *
     * @param spanBuilder 我们将注入提取值的 span 构建器
     * @param attributes 从 otel 负载中提取的 span 属性列表
     * @param integrationName 发送 span 的集成名称（可以为空）
     * @param events 从 otel 负载中提取的事件列表
     */
    public static void enrichSpanWithAttributes(SpanBuilder spanBuilder, List<KeyValue> attributes,
            String integrationName, List<Span.Event> events) {
        enrichSpanWithAttributes(spanBuilder, attributes, integrationName, events, null);
    }

    private static final String CLAUDE_CODE_LLM_SPAN = "claude_code.llm_request";
    private static final String NEW_CONTEXT_ATTR = "new_context";

    // 保留的元数据键，不得被来自 opik.metadata 合并的用户提供 JSON 覆盖。
    private static final Set<String> RESERVED_METADATA_KEYS = Set.of("thread_id", "integration", "server.address");

    /**
     * 与 {@link #enrichSpanWithAttributes(SpanBuilder, List, String, List)} 相同，但带有 OTEL
     * span 名称，用于基于 span 名称的路由（例如，Claude Code 的 {@code new_context} 仅在
     * {@code claude_code.llm_request} span 上映射到 input）。
     *
     * @param spanName OTEL span 名称（可以为 null）
     */
    public static void enrichSpanWithAttributes(SpanBuilder spanBuilder, List<KeyValue> attributes,
            String integrationName, List<Span.Event> events, String spanName) {
        Map<String, Integer> usage = new HashMap<>();
        ObjectNode input = JsonUtils.createObjectNode();
        ObjectNode output = JsonUtils.createObjectNode();
        ObjectNode metadata = JsonUtils.createObjectNode();
        Set<String> tags = new HashSet<>();
        // Claude Code span 携带大量并非 input 的会话/配置属性。对于该集成，
        // 未映射属性的默认存储位置是 metadata（而非 input），因此只有显式提升的内容属性
        // 才会进入 input/output/usage。
        // 按 span 名称逐个决定（而非根据下方的批次级 integrationName）：单个 OTLP
        // 批次可能混合多个集成的 scope，因此若按批次级值判断，
        // 可能会错误路由非 Claude span，或跳过真正的 Claude Code span 的路由。
        boolean isClaudeCode = OpenTelemetryMappingRuleFactory.isClaudeCodeSpan(spanName);
        ObjectNode defaultBucket = isClaudeCode ? metadata : input;

        // 将 model 和 provider 保留到属性循环完成之后，以便应用需要这两个值的
        // 后处理（例如 Elastic Inference Service 路由）。
        // Claude Code 仅使用 Anthropic，从不发送 provider 属性，因此直接设置它。
        String model = null;
        String provider = isClaudeCode ? "anthropic" : null;

        if (StringUtils.isNotBlank(integrationName)) {
            metadata.put("integration", integrationName);
        }

        // 遍历每个属性键值对
        for (KeyValue attribute : attributes) {
            var key = attribute.getKey();
            var value = attribute.getValue();

            // Claude Code 的 `new_context` 是 llm_request span 上送入模型的最新消息
            // （即真正的 LLM 输入）；在 interaction/tool span 上它只是重复 prompt /
            // 工具结果，因此在那里将其保留在 metadata 而非 input 中。
            if (isClaudeCode && NEW_CONTEXT_ATTR.equals(key)) {
                extractToJsonColumn(CLAUDE_CODE_LLM_SPAN.equals(spanName) ? input : metadata, key, value);
                continue;
            }

            var ruleOpt = OpenTelemetryMappingRuleFactory.findRule(key, isClaudeCode);

            if (ruleOpt.isEmpty()) {
                log.debug("未映射的属性键 '{}' 未找到规则。使用默认存储位置。", key);
                extractToJsonColumn(defaultBucket, key, value);
                continue;
            }

            var rule = ruleOpt.get();
            Optional.ofNullable(rule.getSpanType()).ifPresent(spanBuilder::type);

            switch (rule.getOutcome()) {
                case MODEL :
                    model = value.getStringValue();
                    break;

                case PROVIDER :
                    provider = value.getStringValue();
                    break;

                case USAGE :
                    extractUsageField(usage, rule, key, value);
                    break;

                case COST :
                    extractCost(value).ifPresent(spanBuilder::totalEstimatedCost);
                    break;

                case INPUT :
                case OUTPUT :
                case METADATA :
                    ObjectNode node = switch (rule.getOutcome()) {
                        case INPUT -> input;
                        case OUTPUT -> output;
                        default -> metadata;
                    };

                    String jsonKey = storageKey(rule, key);
                    // 如果后缀为空，则尝试作为 JSON 对象合并，
                    // 否则嵌套在去掉前缀的键或规则键之下。
                    if (jsonKey.isEmpty() && value.getValueCase() == AnyValue.ValueCase.STRING_VALUE) {
                        mergeJsonObjectOrFallback(node, rule.getRule(), key, value);
                    } else if (jsonKey.isEmpty()) {
                        extractToJsonColumn(node, rule.getRule(), value);
                    } else {
                        extractToJsonColumn(node, jsonKey, value);
                    }
                    break;

                case TAGS :
                    List<String> span_tags = extractTags(value);
                    if (CollectionUtils.isNotEmpty(span_tags)) {
                        tags.addAll(span_tags);
                    }
                    break;

                case THREAD_ID :
                    // 作为 'thread_id' 存入 metadata 以进行 trace 分组
                    // 如果多个属性映射到 THREAD_ID，以第一个值为准
                    if (!metadata.has("thread_id")) {
                        extractToJsonColumn(metadata, "thread_id", value);
                    }
                    break;

                case DROP :
                    // 显式丢弃该属性
                    break;
            }
        }

        // 处理事件并将其添加到 metadata
        processEvents(events, metadata);

        // Claude Code 将工具结果作为 `tool.output` span 事件发出（Bash 将其放在
        // `output` 上，文件工具放在 `content` 上）。将其作为工具 span 的 output 展示，
        // 而不是只保留在 metadata.opentelemetry.events 中。
        if (isClaudeCode) {
            extractToolOutputEvent(events, output);
        }

        // 将 Elastic Inference Service 的 model/provider 改写为底层 provider，
        // 以便成本查询和基于 provider 的过滤能看到真实的上游。为便于追溯，
        // 在 metadata 中记录原始值。返回（可能未改变的）二元组。
        var resolved = ElasticInferenceServiceResolver.resolve(model, provider, metadata);
        model = resolved.model();
        provider = resolved.provider();

        // 使用 server.address 将泛化的 'google' provider（PydanticAI / google-genai）
        // 区分为 Vertex AI 与 Gemini API 的规范名称，以便成本查询能匹配到价格行。
        provider = GoogleProviderResolver.resolve(provider, metadata);

        // 代理运行的 span（gen_ai.operation.name=invoke_agent）不是 LLM 调用。
        // 其上的其他属性（如 gen_ai.system_instructions）否则会将其归类为 llm；强制为 general。
        if ("invoke_agent".equals(metadata.path("gen_ai.operation.name").asText(null))) {
            spanBuilder.type(SpanType.general);
        }

        if (model != null) {
            spanBuilder.model(model);
        }
        if (provider != null) {
            spanBuilder.provider(provider);
        }

        if (!metadata.isEmpty()) {
            spanBuilder.metadata(metadata);
        }
        if (!output.isEmpty()) {
            spanBuilder.output(output);
        }
        if (!input.isEmpty()) {
            spanBuilder.input(input);
        }
        if (!usage.isEmpty()) {
            // 有些集成（如 PydanticAI）会发送 prompt_tokens 和 completion_tokens，
            // 但省略 total_tokens。计算它，以便调用方始终获得完整的信息。
            if (!usage.containsKey("total_tokens")
                    && usage.containsKey("prompt_tokens")
                    && usage.containsKey("completion_tokens")) {
                usage.put("total_tokens", usage.get("prompt_tokens") + usage.get("completion_tokens"));
            }
            spanBuilder.usage(usage);
        }
        if (!tags.isEmpty()) {
            spanBuilder.tags(tags);
        }
    }

    /**
     * 当存储键为空且值为字符串时，尝试将其解析为 JSON 对象，并将其字段合并到 {@code node} 中，
     * 跳过 {@link #RESERVED_METADATA_KEYS}。当 JSON 值不是对象或解析失败时，
     * 回退为通过 {@link #extractToJsonColumn} 将原始值存储到规则键下。
     */
    private static void mergeJsonObjectOrFallback(ObjectNode node, String ruleKey, String key, AnyValue value) {
        String stringValue = value.getStringValue();

        // 仅尝试解析看起来像 JSON 的字符串
        String trimmed = StringUtils.trimToEmpty(stringValue);
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            extractToJsonColumn(node, ruleKey, value);
            return;
        }
        try {
            var jsonNode = JsonUtils.getJsonNodeFromString(stringValue);
            if (jsonNode.isObject()) {
                jsonNode.fields()
                        .forEachRemaining(entry -> {
                            if (!RESERVED_METADATA_KEYS.contains(entry.getKey())) {
                                node.set(entry.getKey(), entry.getValue());
                            }
                        });
            } else {
                extractToJsonColumn(node, ruleKey, value);
            }
        } catch (UncheckedIOException e) {
            log.warn("解析 JSON 失败，键 '{}' 回退为文本", key, e);
            extractToJsonColumn(node, ruleKey, value);
        }
    }

    private static final String TOOL_OUTPUT_EVENT_NAME = "tool.output";
    private static final Set<String> TOOL_OUTPUT_CONTENT_KEYS = Set.of("output", "content");

    /**
     * 将 Claude Code 的 {@code tool.output} span 事件映射到工具 span 的 output 中。
     * 该事件在 {@code output}（Bash）或 {@code content}（文件工具）上携带工具结果。
     * 如果存在多个事件，以最后一个为准。
     *
     * @param events 从 otel 负载中提取的事件列表
     * @param output 要填充的输出节点
     */
    private static void extractToolOutputEvent(List<Span.Event> events, ObjectNode output) {
        findLastEvent(events, TOOL_OUTPUT_EVENT_NAME)
                .ifPresent(event -> event.getAttributesList().stream()
                        .filter(attribute -> TOOL_OUTPUT_CONTENT_KEYS.contains(attribute.getKey()))
                        .forEach(attribute -> extractToJsonColumn(output, attribute.getKey(), attribute.getValue())));
    }

    /**
     * 查找具有给定名称的最后一个 span 事件；当存在多个时，以最后一个为准。
     */
    private static Optional<Span.Event> findLastEvent(List<Span.Event> events, String name) {
        if (CollectionUtils.isEmpty(events)) {
            return Optional.empty();
        }
        return events.stream()
                .filter(event -> name.equals(event.getName()))
                .reduce((first, second) -> second);
    }

    private static final String EXCEPTION_EVENT_NAME = "exception";
    private static final String EXCEPTION_TYPE_ATTR = "exception.type";
    private static final String EXCEPTION_MESSAGE_ATTR = "exception.message";
    private static final String EXCEPTION_STACKTRACE_ATTR = "exception.stacktrace";
    private static final String DEFAULT_EXCEPTION_TYPE = "Error";

    /**
     * 将 OpenTelemetry 错误信号转换为 Opik 的 {@link ErrorInfo}，使失败的 span
     * 以错误形式呈现，而不是将失败隐藏在原始事件元数据中。这两种信号都是
     * 每个插桩都会发出的 OTel 核心约定，并非 PydanticAI 特有：
     * <ul>
     *     <li>携带 {@code exception.type} / {@code exception.message} / {@code exception.stacktrace}
     *     的 {@code exception} span 事件（来自 {@code Span.record_exception}）。</li>
     *     <li>带有可选消息的 span {@code STATUS_CODE_ERROR} 状态。</li>
     * </ul>
     * 异常事件信息更丰富，因此优先；当存在多个时，以最后一个为准。
     *
     * @param otelSpan 要检查的 OpenTelemetry span
     * @return 提取的错误信息；当 span 未失败时为空
     */
    static Optional<ErrorInfo> extractErrorInfo(Span otelSpan) {
        var exceptionEvent = findLastEvent(otelSpan.getEventsList(), EXCEPTION_EVENT_NAME);

        if (exceptionEvent.isPresent()) {
            var attributes = exceptionEvent.get().getAttributesList();
            var message = eventAttribute(attributes, EXCEPTION_MESSAGE_ATTR);
            return Optional.of(ErrorInfo.builder()
                    .exceptionType(StringUtils.firstNonBlank(
                            eventAttribute(attributes, EXCEPTION_TYPE_ATTR), DEFAULT_EXCEPTION_TYPE))
                    .message(message)
                    .traceback(StringUtils.firstNonBlank(
                            eventAttribute(attributes, EXCEPTION_STACKTRACE_ATTR), message, DEFAULT_EXCEPTION_TYPE))
                    .build());
        }

        if (otelSpan.getStatus().getCode() == Status.StatusCode.STATUS_CODE_ERROR) {
            var message = StringUtils.trimToNull(otelSpan.getStatus().getMessage());
            return Optional.of(ErrorInfo.builder()
                    .exceptionType(DEFAULT_EXCEPTION_TYPE)
                    .message(message)
                    .traceback(StringUtils.firstNonBlank(message, DEFAULT_EXCEPTION_TYPE))
                    .build());
        }

        return Optional.empty();
    }

    private static String eventAttribute(List<KeyValue> attributes, String key) {
        return attributes.stream()
                .filter(attribute -> key.equals(attribute.getKey()))
                .map(attribute -> attribute.getValue().getStringValue())
                .findFirst()
                .orElse(null);
    }

    /**
     * 使用 64 位整数 OpenTelemetry SpanId 及其时间戳来生成一个良好的 UUIDv7 ID。这实际上是
     * 一个良好的 UUIDv7（与 traceId 相反），因为它由 ID 和时间戳组成，因此 span 会在
     * span 表中正确排序。
     * 当你分多个批次接收到非 UUID，且无法从已知的 Otel 整数 ID 预测出实际的 Opik UUID 时，
     * 截断时间戳选项就很有用。因此我们采用按时间窗口截断的 span 时间戳，使其可预测。
     * 这能很好地使 UUID 可预测，并且它们在 ClickHouse 上彼此相邻存储，但有两个缺点：
     * (1) trace 在 Traces 页面中可能显示为无序（周一的 trace 可能显示为比周五的 trace “更新”，
     * 因为它们的 UUID 具有相同的时间戳：周日 00:00:00）；
     * (2) 在周六 23:59:30 到周日 00:00:30 之间运行的例程会被拆分为 2 个 trace，两者都不完整。
     *
     * @param otelSpanId OpenTelemetry 64 位整数 spanId
     * @param timestampMs span 的时间戳（毫秒）
     * @return 有效的 UUIDv7
     */
    public static UUID convertOtelIdToUUIDv7(byte[] otelSpanId, long timestampMs) {
        // 为 UUID 准备 16 字节数组
        byte[] uuidBytes = new byte[16];

        // 字节 0-5：48 位时间戳（大端序）
        long ts48 = timestampMs & 0xFFFFFFFFFFFFL; // 48 位
        uuidBytes[0] = (byte) ((ts48 >> 40) & 0xFF);
        uuidBytes[1] = (byte) ((ts48 >> 32) & 0xFF);
        uuidBytes[2] = (byte) ((ts48 >> 24) & 0xFF);
        uuidBytes[3] = (byte) ((ts48 >> 16) & 0xFF);
        uuidBytes[4] = (byte) ((ts48 >> 8) & 0xFF);
        uuidBytes[5] = (byte) (ts48 & 0xFF);

        // 字节 6-15：由 spanId 哈希派生的 80 位
        // 使用 SHA-256 对 spanId（8 字节）进行哈希，并取前 10 字节（80 位）
        byte[] hash = DigestUtils.sha256(otelSpanId);
        System.arraycopy(hash, 0, uuidBytes, 6, 10);

        // 将版本设置为 7（存储在字节 6 的高半字节中）
        uuidBytes[6] = (byte) ((uuidBytes[6] & 0x0F) | 0x70);
        // 设置变体（字节 8 的最高两位应为 10）
        uuidBytes[8] = (byte) ((uuidBytes[8] & 0x3F) | 0x80);

        // 从字节数组构建 UUID
        ByteBuffer byteBuffer = ByteBuffer.wrap(uuidBytes);
        long mostSigBits = byteBuffer.getLong(); // 提示：它会读取并改变偏移量
        long leastSigBits = byteBuffer.getLong();
        return new UUID(mostSigBits, leastSigBits);
    }

    /**
     * 从 UUIDv7 中提取以毫秒为单位的 Unix 纪元时间戳。
     *
     * @param uuid UUIDv7 实例
     * @return 提取的时间戳（long，自 Unix 纪元以来的毫秒数）
     */
    private long extractTimestampFromUUIDv7(UUID uuid) {
        return RetentionUtils.extractInstant(uuid).toEpochMilli();
    }

    /**
     * 从 OTEL span 中提取 opik.trace_id 属性（如果存在）。
     * 该属性允许将 OTEL span 连接到现有的 OPIK trace。
     *
     * @param otelSpan 要从中提取的 OTEL span
     * @return 如果属性存在且有效，则返回 OPIK trace UUID
     */
    public static Optional<UUID> extractOpikTraceId(Span otelSpan) {
        return extractStringAttribute(otelSpan, GeneralMappingRules.OPIK_TRACE_ID_ATTR)
                .flatMap(value -> parseUUIDv7(value, GeneralMappingRules.OPIK_TRACE_ID_ATTR));
    }

    /**
     * 从 OTEL span 中提取 opik.parent_span_id 属性（如果存在）。
     * 该属性允许将 OTEL span 作为子节点连接到现有的 OPIK span。
     * 仅在 opik.trace_id 也存在时才有意义。
     *
     * @param otelSpan 要从中提取的 OTEL span
     * @return 如果属性存在且有效，则返回 OPIK 父 span UUID
     */
    public static Optional<UUID> extractOpikParentSpanId(Span otelSpan) {
        return extractStringAttribute(otelSpan, GeneralMappingRules.OPIK_PARENT_SPAN_ID_ATTR)
                .flatMap(value -> parseUUIDv7(value, GeneralMappingRules.OPIK_PARENT_SPAN_ID_ATTR));
    }

    /**
     * 从 OTEL span 中提取 opik.span_id 属性（如果存在）。
     * 当设置时，该值将原样用作 Opik span ID，绕过对 OTEL span ID 的 SHA-256
     * 转换。SDK 的 OpikSpanProcessor 为每个 span 生成该值，
     * 并在每个子节点上将其作为 opik.parent_span_id 传递，这样附加的
     * OTEL 子树的子孙在跨批次边界时仍保持链接，而无需依赖 Redis。
     *
     * @param otelSpan 要从中提取的 OTEL span
     * @return 如果属性存在且有效，则返回 OPIK span UUID
     */
    public static Optional<UUID> extractOpikSpanId(Span otelSpan) {
        return extractStringAttribute(otelSpan, GeneralMappingRules.OPIK_SPAN_ID_ATTR)
                .flatMap(value -> parseUUIDv7(value, GeneralMappingRules.OPIK_SPAN_ID_ATTR));
    }

    private static Optional<String> extractStringAttribute(Span otelSpan, String key) {
        return otelSpan.getAttributesList().stream()
                .filter(attr -> key.equals(attr.getKey()))
                .map(attr -> attr.getValue().getStringValue())
                .filter(StringUtils::isNotBlank)
                .findFirst();
    }

    private static Optional<UUID> parseUUIDv7(String value, String attributeName) {
        try {
            var uuid = UUID.fromString(value);
            if (uuid.version() != 7) {
                log.warn("属性 '{}' 的值 '{}' 不是 UUIDv7（版本 {}），忽略",
                        attributeName, value, uuid.version());
                return Optional.empty();
            }
            return Optional.of(uuid);
        } catch (IllegalArgumentException e) {
            log.warn("属性 '{}' 的值 '{}' 不是有效的 UUIDv7，忽略", attributeName, value);
            return Optional.empty();
        }
    }
}
