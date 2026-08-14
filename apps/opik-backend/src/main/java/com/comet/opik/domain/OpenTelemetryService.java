package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.api.Source;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRuleFactory;
import com.comet.opik.infrastructure.OpenTelemetryConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ImplementedBy(OpenTelemetryServiceImpl.class)
public interface OpenTelemetryService {

    Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName);
}

@Singleton
@RequiredArgsConstructor
@Slf4j
class OpenTelemetryServiceImpl implements OpenTelemetryService {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ProjectService projectService;
    private final @NonNull RedissonReactiveClient redisson;
    private final @NonNull OpenTelemetryConfig config;

    @Inject
    public OpenTelemetryServiceImpl(@NonNull @Config("openTelemetry") OpenTelemetryConfig config,
            @NonNull TraceService traceService,
            @NonNull SpanService spanService,
            @NonNull ProjectService projectService,
            @NonNull RedissonReactiveClient redisson) {
        this.config = config;
        this.traceService = traceService;
        this.spanService = spanService;
        this.projectService = projectService;
        this.redisson = redisson;
    }

    @Override
    public Mono<Long> parseAndStoreSpans(@NonNull ExportTraceServiceRequest traceRequest, @NonNull String projectName) {

        // 在开始处理之前确保项目存在
        return projectService.getOrCreate(projectName)
                .map(Project::id)
                .flatMap(projectId -> Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

                    // 提取批次中的所有 otel span，按开始时间排序
                    var otelSpans = traceRequest.getResourceSpansList().stream()
                            .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                            .flatMap(scopeSpans -> scopeSpans.getSpansList().stream())
                            .toList();

                    // 找出集成库的名称
                    var integrationName = traceRequest.getResourceSpansList().stream()
                            .flatMap(resourceSpans -> resourceSpans.getScopeSpansList().stream())
                            .map(scopeSpans -> scopeSpans.getScope().getName())
                            .distinct()
                            .filter(OpenTelemetryMappingRuleFactory::isValidInstrumentation)
                            .findFirst()
                            .orElse(null);

                    // otelTraceId -> 该 traceId 出现的最小时间戳
                    var otelTracesAndMinTimestamp = otelSpans.stream()
                            .collect(Collectors.toMap(Span::getTraceId, Span::getStartTimeUnixNano, Math::min));

                    // 获取或创建 otel trace id -> opik trace id 的映射
                    return otelToOpikTraceIdMapper(otelTracesAndMinTimestamp, projectId, workspaceId)
                            .flatMap(traceMapper -> doStoreSpans(otelSpans, traceMapper, projectName, integrationName));
                })).subscribeOn(Schedulers.boundedElastic());
    }

    private String base64OtelId(ByteString idBytes) {
        return Base64.getEncoder().encodeToString(idBytes.toByteArray());
    }

    private String redisKey(String workspaceId, UUID projectId, String otelId) {
        return "otelTraceId:" + workspaceId + ":" + projectId + ":" + otelId;
    }

    private Mono<Long> doStoreSpans(List<Span> otelSpans, Map<String, UUID> traceIdMapper, String projectName,
            String integrationName) {

        // 跟踪来自 opik.trace_id 属性覆盖的 trace ID。
        // 这些 span 连接到现有的 OPIK trace，因此我们不能为它们创建新的 trace。
        Set<UUID> overriddenTraceIds = new HashSet<>();

        // 使用映射后的 opik trace id 将 otel span 转换为 opik span
        var opikSpans = otelSpans.stream()
                .map(otelSpan -> {
                    var otelTraceIdBase64 = base64OtelId(otelSpan.getTraceId());

                    var opikTraceId = traceIdMapper.get(otelTraceIdBase64);

                    OpenTelemetryMapper.extractOpikTraceId(otelSpan)
                            .ifPresent(overriddenTraceIds::add);

                    return OpenTelemetryMapper.toOpikSpan(otelSpan, opikTraceId, integrationName);
                })
                .map(opikSpan -> opikSpan.toBuilder()
                        .projectName(projectName)
                        .build())
                .toList();

        // 有些集成（如 PydanticAI/Logfire）将累计使用量 token 放在父/代理 span 上，
        // 而这些 token 已经表示所有子 LLM 调用 span 的总和。若将父 span 和子 span 都
        // 计入 sumMap() 聚合，会导致 token 重复计数。清除那些子 span 也携带使用量的
        // 父 span 的使用量，使只有叶子 LLM span 对聚合 token 计数做出贡献。
        var parentIdsWithChildrenHavingUsage = opikSpans.stream()
                .filter(span -> span.parentSpanId() != null
                        && span.usage() != null
                        && !span.usage().isEmpty())
                .map(com.comet.opik.api.Span::parentSpanId)
                .collect(Collectors.toSet());

        var dedupedSpans = parentIdsWithChildrenHavingUsage.isEmpty()
                ? opikSpans
                : opikSpans.stream()
                        .map(span -> parentIdsWithChildrenHavingUsage.contains(span.id())
                                && span.usage() != null
                                && !span.usage().isEmpty()
                                        ? span.toBuilder().usage(null).build()
                                        : span)
                        .toList();

        // 使用没有 parentId 的 span 作为 Trace 根节点。跳过 opik.trace_id 覆盖
        // （它们附加到现有 trace）。多个根节点可以共享同一个 traceId
        // （例如当 mapper 中将 parent_span_id==trace_id 置空时），因此按 traceId
        // 去重，每个 ID 只创建一个 trace。按遇到顺序，第一个根节点生效。
        var seenTraceIds = new HashSet<UUID>();
        var rootSpansByTraceId = dedupedSpans.stream()
                .filter(span -> span.parentSpanId() == null)
                .filter(span -> !overriddenTraceIds.contains(span.traceId()))
                .filter(span -> seenTraceIds.add(span.traceId()))
                .toList();
        return Flux.fromStream(rootSpansByTraceId.stream())
                .flatMap(rootSpan -> {
                    // 如果存在，从根 span 元数据中提取 thread_id
                    String threadId = null;
                    if (rootSpan.metadata() != null && rootSpan.metadata().has("thread_id")) {
                        threadId = rootSpan.metadata().get("thread_id").asText();
                    }

                    var traceBuilder = Trace.builder()
                            .id(rootSpan.traceId())
                            .name(rootSpan.name())
                            .projectName(rootSpan.projectName())
                            .startTime(rootSpan.startTime())
                            .endTime(rootSpan.endTime())
                            .duration(rootSpan.duration())
                            .input(rootSpan.input())
                            .output(rootSpan.output())
                            .metadata(rootSpan.metadata())
                            .tags(rootSpan.tags())
                            .errorInfo(rootSpan.errorInfo())
                            .source(Source.SDK);

                    if (StringUtils.isNotBlank(threadId)) {
                        traceBuilder.threadId(threadId);
                    }

                    return traceService.create(traceBuilder.build());
                })
                .doOnNext(traceId -> log.info("TraceId '{}' 已创建", traceId))
                .then(Mono.defer(() -> {
                    var spanBatch = SpanBatch.builder().spans(dedupedSpans).build();

                    log.info("已将项目 '{}' 的 OpenTelemetry span 批次解析为 {} 个 span", projectName,
                            dedupedSpans.size());

                    return spanService.create(spanBatch);
                }));
    }

    private Mono<Map<String, UUID>> otelToOpikTraceIdMapper(Map<ByteString, Long> otelTraceIds, UUID projectId,
            String workspaceId) {
        // 在 Redis 中检查批次中的 otel traceId；我们之前是否见过它们？
        // 建立映射（base64 otel id -> UUIDv7 opik id）
        return Flux.fromIterable(otelTraceIds.entrySet()).flatMap(otelPack -> {
            var otelTraceId = otelPack.getKey();
            var otelTimestamp = Duration.ofNanos(otelPack.getValue()).toMillis();

            var otelTraceIdBase64 = base64OtelId(otelTraceId);

            // 检查该键在 redis 中是否已映射
            var otelTraceIdRedisKey = redisKey(workspaceId, projectId, otelTraceIdBase64);
            var checkId = redisson.getBucket(otelTraceIdRedisKey).getAndExpire(config.getTtl().toJavaDuration());

            return checkId.switchIfEmpty(Mono.defer(() -> {
                // 这是一个未知的 otel trace id，让我们使用该 span 时间戳创建 opik trace id，
                // 由于上一步已按时间对 otel span 排序，这将是尽可能接近实际 trace 开始的时间
                var opikTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId.toByteArray(), otelTimestamp);

                log.info("在 Redis 中为 otel trace id '{}' -> opik trace id '{}' 创建映射", otelTraceIdRedisKey,
                        opikTraceId);
                return redisson.getBucket(otelTraceIdRedisKey)
                        .set(opikTraceId.toString(), config.getTtl().toJavaDuration())
                        .then(Mono.just(opikTraceId.toString()));
            })).map(opikTraceId -> Map.entry(otelTraceIdBase64, opikTraceId));
        }).collectMap(Map.Entry::getKey, entry -> UUID.fromString((String) entry.getValue()));
    }
}
