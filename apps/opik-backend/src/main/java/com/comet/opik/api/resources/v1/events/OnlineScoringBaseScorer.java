package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.RedisSubscriberMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringStreamConfigurationAdapter;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * 供所有具体实现继承的基础在线评分器。它监听 Redis 流中待评分的追踪/跨度/线程。子类提供一个特定的
 * {@link #score(Object)} 实现，返回 {@link Mono}，从而使整个处理链从 Redis 读取到反馈评分持久化保持非阻塞。
 * 由 {@link BaseRedisSubscriber} 拥有的 Reactor 管道在每流工作调度器上调度执行；子类不应在 {@code score()} 中
 * 调用 {@code .block()}。
 */
public abstract class OnlineScoringBaseScorer<M extends RedisSubscriberMessage> extends BaseRedisSubscriber<M> {

    public static final int TRACE_PAGE_LIMIT = 2000;

    /**
     * 用于无工具内联 {@code {{trace}}} / {@code {{span}}} 回退的截断标记提示。没有 {@code read}/{@code jq}
     * 工具可供下钻，因此该提示仅标记值已被截断，而非指向一个（不存在的）后续工具。
     */
    protected static final String INLINE_TRUNCATION_HINT = "full content not shown";

    private static final String ONLINE_SCORING_NAMESPACE = "online_scoring";

    /**
     * 用于实际子类的 Logger，以便在日志中拥有正确的类名。
     */
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    protected final OnlineScoringConfig onlineScoringConfig;
    protected final FeedbackScoreService feedbackScoreService;
    protected final TraceService traceService;
    protected final AutomationRuleEvaluatorType type;

    protected OnlineScoringBaseScorer(@NonNull @Config OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull TraceService traceService,
            @NonNull AutomationRuleEvaluatorType type,
            @NonNull String metricsBaseName) {
        super(OnlineScoringStreamConfigurationAdapter.create(config, type),
                redisson,
                OnlineScoringConfig.PAYLOAD_FIELD,
                ONLINE_SCORING_NAMESPACE,
                metricsBaseName);
        this.onlineScoringConfig = config;
        this.feedbackScoreService = feedbackScoreService;
        this.traceService = traceService;
        this.type = type;
    }

    /**
     * 追踪线程跨度预加载的上限（以字节为单位）。这是转换以 MB 为单位的配置
     * （{@code onlineScoring.agenticToolsMaxPreloadMb}）的唯一位置，使追踪线程评分器向大小门控和
     * 有界预加载传递一致的值。参见 OPIK-7454。
     */
    protected long agenticToolsMaxPreloadBytes() {
        return (long) onlineScoringConfig.getAgenticToolsMaxPreloadMb() * 1024 * 1024;
    }

    /**
     * 从有界预加载返回缓冲的跨度，并作为副作用，当预加载 {@link ThreadSpanPreload#overflowed()}
     * 超出字节上限（尽管大小估算已将线程路由到增强路径）时发出面向用户的警告——即廉价的大小聚合
     * 低估了真实的序列化大小。溢出已在上游被安全处理（缓冲区被丢弃，因此返回列表为空，
     * 线程使用未增强的上下文进行评分）；该警告只是让原本静默的回退可见。参见 OPIK-7454。
     */
    protected List<Span> getSpansFromPreloadAndLogOverflow(@NonNull ThreadSpanPreload preload,
            @NonNull Logger userFacingLogger, String threadId, Map<String, String> mdc) {
        if (preload.overflowed()) {
            try (var logContext = wrapWithMdc(mdc)) {
                userFacingLogger.warn("""
                        线程跨度预加载超出了增强上限，尽管大小估算合适；\
                        使用未增强的上下文进行评分。threadId='{}', approxBytes='{}', capBytes='{}'""",
                        threadId, preload.approxBytes(), agenticToolsMaxPreloadBytes());
            }
        }
        return preload.spans();
    }

    /**
     * 将消息所属的工作区/用户传播到整个评分链的响应式上下文中（反馈评分持久化会读取它）。
     * 每条消息的吞吐量和错误指标由 {@link BaseRedisSubscriber} 根据
     * {@link #messageContext(Object)} 自动归属。
     */
    @Override
    protected final Mono<Void> processEvent(M message) {
        var workspaceName = StringUtils.defaultIfBlank(message.workspaceName(), message.workspaceId());
        return doScore(message)
                // 来源于消息（在追踪事件发布时从 RequestContext.WORKSPACE_NAME 解析）。
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.WORKSPACE_NAME, workspaceName)
                        .put(RequestContext.USER_NAME, message.userName()));
    }

    /**
     * 完整的每条消息处理链。默认使用 {@link #score(Object)}，并延迟执行，使任何同步工作
     * 在订阅时于每流工作调度器上运行。需要评分后步骤（例如测试套件断言收尾）的子类应覆盖此方法——
     * 而非 {@code processEvent}——从而使基类仅在整个链成功完成后才将消息记录为已处理。
     */
    protected Mono<Void> doScore(M message) {
        return Mono.defer(() -> score(message));
    }

    /**
     * 对消息进行评分并持久化所得的反馈评分。实现必须组合响应式操作符（不得 {@code .block()}）；
     * 参见 {@link #storeScores}、{@link #storeSpanScores}、{@link #storeThreadScores}。
     */
    protected abstract Mono<Void> score(M message);

    protected Mono<Map<String, List<BigDecimal>>> storeScores(
            List<FeedbackScoreBatchItem> scores, Trace trace, String userName, String workspaceId) {
        log.info("收到 '{}' 条针对 traceId '{}'（工作区 '{}'）的评分。正在存储它们",
                scores.size(), trace.id(), workspaceId);
        return feedbackScoreService.scoreBatchOfTraces(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .thenReturn(groupScoresByName(scores));
    }

    protected Mono<Map<String, List<BigDecimal>>> storeSpanScores(
            List<FeedbackScoreBatchItem> scores, com.comet.opik.api.Span span, String userName, String workspaceId) {
        log.info("收到 '{}' 条针对 spanId '{}'（工作区 '{}'）的评分。正在存储它们",
                scores.size(), span.id(), workspaceId);
        return feedbackScoreService.scoreBatchOfSpans(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .thenReturn(groupScoresByName(scores));
    }

    protected Mono<Map<String, List<BigDecimal>>> storeThreadScores(
            List<FeedbackScoreBatchItemThread> scores, String threadId, String userName, String workspaceId) {
        log.info("收到 '{}' 条针对 threadId '{}'（工作区 '{}'）的评分。正在存储它们",
                scores.size(), threadId, workspaceId);
        return feedbackScoreService.scoreBatchOfThreads(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .thenReturn(groupScoresByName(scores));
    }

    private static <T extends FeedbackScoreItem> Map<String, List<BigDecimal>> groupScoresByName(List<T> scores) {
        return scores.stream()
                .collect(Collectors.groupingBy(FeedbackScoreItem::name,
                        Collectors.mapping(FeedbackScoreItem::value, Collectors.toList())));
    }

    /**
     * 检索给定线程 ID 的完整线程上下文，递归获取追踪直到找不到更多为止。
     *
     * @param threadId 要检索其上下文的线程的 ID
     * @param lastReceivedIdRef 用于存储最后接收到的追踪 ID 的引用
     * @param projectId 线程所属项目的 ID
     * @return 表示完整线程上下文的 Trace 对象 Flux
     */
    //TODO: 将其移动到通用服务或工具类
    protected Flux<Trace> retrieveFullThreadContext(@NotNull String threadId,
            @NotNull AtomicReference<UUID> lastReceivedIdRef, @NotNull UUID projectId) {

        return Flux.defer(() -> traceService.search(TRACE_PAGE_LIMIT, TraceSearchCriteria.builder()
                .projectId(projectId)
                .filters(List.of(TraceFilter.builder()
                        .field(TraceField.THREAD_ID)
                        .operator(Operator.EQUAL)
                        .value(threadId)
                        .build()))
                .lastReceivedId(lastReceivedIdRef.get())
                .build())
                .collectList()
                .flatMapMany(results -> {
                    if (results.isEmpty()) {
                        return Flux.empty();
                    }
                    lastReceivedIdRef.set(results.getLast().id());
                    return Flux.fromIterable(results)
                            .concatWith(Flux
                                    .defer(() -> retrieveFullThreadContext(threadId, lastReceivedIdRef, projectId)));
                }));
    }
}
