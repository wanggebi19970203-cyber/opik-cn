package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.evaluators.python.PythonEvaluatorService;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON;
import static com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest.ChatMessage;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

@EagerSingleton
@Slf4j
public class OnlineScoringTraceThreadUserDefinedMetricPythonScorer
        extends
            OnlineScoringBaseScorer<TraceThreadToScoreUserDefinedMetricPython> {

    private final ServiceTogglesConfig serviceTogglesConfig;
    private final PythonEvaluatorService pythonEvaluatorService;
    private final TraceThreadService traceThreadService;
    private final Logger userFacingLogger;
    private final ProjectService projectService;
    private final AutomationRuleEvaluatorService automationRuleEvaluatorService;
    private final SpanService spanService;
    private final AgenticScoringService agenticScoringService;

    @Inject
    public OnlineScoringTraceThreadUserDefinedMetricPythonScorer(
            @NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull PythonEvaluatorService pythonEvaluatorService,
            @NonNull TraceService traceService,
            @NonNull TraceThreadService traceThreadService,
            @NonNull ProjectService projectService,
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService,
            @NonNull SpanService spanService,
            @NonNull AgenticScoringService agenticScoringService) {
        super(config, redisson, feedbackScoreService, traceService, TRACE_THREAD_USER_DEFINED_METRIC_PYTHON,
                Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON);
        this.pythonEvaluatorService = pythonEvaluatorService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.traceThreadService = traceThreadService;
        this.projectService = projectService;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.spanService = spanService;
        this.agenticScoringService = agenticScoringService;
        this.userFacingLogger = UserFacingLoggingFactory
                .getLogger(OnlineScoringTraceThreadUserDefinedMetricPythonScorer.class);
    }

    @Override
    public void start() {
        if (serviceTogglesConfig.isTraceThreadPythonEvaluatorEnabled()) {
            super.start();
        } else {
            log.warn("在线评分 Python 评估器消费者因被禁用而不会启动。");
        }
    }

    @Override
    protected Mono<Void> score(@NonNull TraceThreadToScoreUserDefinedMetricPython message) {

        log.info("收到消息，projectId '{}'、ruleId '{}'，工作区 '{}'",
                message.projectId(), message.ruleId(), message.workspaceId());

        return Flux.fromIterable(message.threadIds())
                // 独立评分每个 thread id：单个线程的失败绝不能阻止对兄弟 thread id 的评分。
                // 每个线程的错误被物化（onErrorResume），使 flatMap 对每个线程都完成；
                // 批次的第一个失败随后在下面被重新抛出。这使失败保留在由
                // BaseRedisSubscriber.processMessage 的 onErrorResume 处理的 Mono 错误路径上——
                // 被归类为处理错误，遵循正常的可重试/不可重试路径——而不是通过 Flux.flatMap
                // 泄漏到外围的 onErrorContinue（后者会丢弃该元素并将其计为 "unexpected" 错误）。
                .flatMap(threadId -> processThreadScores(message, threadId)
                        .then(Mono.<Throwable>empty())
                        .onErrorResume(Mono::just))
                .collectList()
                .flatMap(errors -> errors.isEmpty() ? Mono.<Void>empty() : Mono.error(errors.getFirst()))
                .contextWrite(context -> context.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName())
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                .doOnSuccess(unused -> log.info(
                        "已处理 projectId '{}'、ruleId '{}'（工作区 '{}'）的追踪线程",
                        message.projectId(), message.ruleId(), message.workspaceId()))
                .doOnError(error -> log.error(
                        "处理 projectId '{}'、ruleId '{}'（工作区 '{}'）的追踪线程时出错",
                        message.projectId(), message.ruleId(), message.workspaceId(), error))
                .then();
    }

    private Mono<Void> processThreadScores(TraceThreadToScoreUserDefinedMetricPython message,
            String currentThreadId) {
        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.RULE_ID, message.ruleId().toString());
        return retrieveFullThreadContext(currentThreadId, new AtomicReference<>(null), message.projectId())
                .sort(Comparator.comparing(Trace::id))
                .collectList()
                .flatMap(traces -> {
                    if (traces.isEmpty()) {
                        try (var logContext = wrapWithMdc(mdc)) {
                            userFacingLogger.info(
                                    "未找到 threadId '{}'（在 projectId '{}' 中）的追踪。跳过评分。",
                                    currentThreadId, message.projectId());
                        }
                        return Mono.empty();
                    }
                    return traceThreadService.getThreadModelId(message.projectId(), currentThreadId)
                            .switchIfEmpty(Mono.defer(() -> {
                                try (var logContext = wrapWithMdc(mdc)) {
                                    userFacingLogger.info(
                                            "未找到 threadId '{}'（在 projectId '{}' 中）的线程模型。跳过评分。",
                                            currentThreadId, message.projectId());
                                }
                                return Mono.empty();
                            }))
                            .flatMap(threadModelId -> processScoring(message, traces, threadModelId,
                                    currentThreadId));
                })
                .then();
    }

    /**
     * 针对给定规则对单个线程评分，并持久化所得的反馈评分。
     */
    private Mono<Void> processScoring(TraceThreadToScoreUserDefinedMetricPython message, List<Trace> traces,
            UUID threadModelId, String threadId) {
        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.THREAD_MODEL_ID, threadModelId.toString(),
                UserLog.RULE_ID, message.ruleId().toString());
        return Mono.fromCallable(() -> findRule(message, threadId, mdc))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("查找 threadId '{}' 的规则时发生意外错误：\n\n{}",
                                threadId,
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .flatMap(maybeRule -> maybeRule
                        .map(rule -> scoreThread(message, traces, threadModelId, threadId, rule, mdc))
                        .orElseGet(Mono::empty));
    }

    /**
     * 为本次评分运行解析自动化规则。如果规则已被删除，返回 {@link Optional#empty()}，
     * 示意调用方跳过而不抛出异常。
     */
    private Optional<AutomationRuleEvaluator<?, ?>> findRule(
            TraceThreadToScoreUserDefinedMetricPython message, String threadId, Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            try {
                var rule = automationRuleEvaluatorService.findById(message.ruleId(),
                        Set.of(message.projectId()), message.workspaceId());
                return Optional.of(rule);
            } catch (NotFoundException ex) {
                log.warn(
                        "未找到 ID 为 '{}' 的自动化规则，projectId '{}'、工作区 '{}'。跳过对 threadId '{}' 的评分。",
                        message.ruleId(), message.projectId(), message.workspaceId(), threadId);
                return Optional.empty();
            }
        }
    }

    /**
     * 针对已知规则运行评分链路并持久化所得的反馈评分。调用方保证 {@code traces} 非空。
     */
    private Mono<Void> scoreThread(TraceThreadToScoreUserDefinedMetricPython message, List<Trace> traces,
            UUID threadModelId, String threadId, AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc) {
        // OPIK-7454——在获取之前路由。用一个廉价的 ClickHouse 聚合（不物化 span）来确定整个线程的大小，
        // 然后仅在线程符合堆上限时才获取 spans 进行富化。此 Python 路径没有内联-vs-工具路由，
        // 因此超过上限的线程降级为未富化的 {role, content} 上下文，而不是完整缓冲。当富化时，
        // spans 通过 fromTraceToThreadEnriched 嵌套在每个 trace 的 assistant ChatMessage 下，
        // 使用户的 Python score(...) 看到完整的调用树。开关关闭 → 大小 0（不查询）→ 未富化，不变。
        var traceIds = traces.stream().map(Trace::id).collect(Collectors.toSet());
        var maxPreloadBytes = agenticToolsMaxPreloadBytes();
        var spansSizeMono = serviceTogglesConfig.isAgenticToolsEnabled()
                ? spanService.getSpansSizeByTraceIds(traceIds)
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, message.userName()))
                : Mono.just(0L);
        return spansSizeMono
                .flatMap(sizeBytes -> {
                    var enrich = serviceTogglesConfig.isAgenticToolsEnabled() && sizeBytes <= maxPreloadBytes;
                    if (serviceTogglesConfig.isAgenticToolsEnabled() && !enrich) {
                        try (var logContext = wrapWithMdc(mdc)) {
                            userFacingLogger.warn("""
                                    线程 span 大小估算超过富化上限；使用未富化的上下文进行评分。\
                                    threadId='{}', sizeBytes='{}', capBytes='{}'""",
                                    threadId, sizeBytes, maxPreloadBytes);
                        }
                    }
                    // 仅在富化一个足够小的线程时才获取 spans（流式字节上限作为兜底）。
                    var spansMono = enrich
                            ? agenticScoringService.preloadThreadSpansBounded(
                                    spanService.getByTraceIds(traceIds), maxPreloadBytes)
                                    .map(preload -> getSpansFromPreloadAndLogOverflow(preload, userFacingLogger,
                                            threadId, mdc))
                                    .contextWrite(ctx -> ctx
                                            .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                            .put(RequestContext.USER_NAME, message.userName()))
                            : Mono.just(List.<Span>of());
                    // 使用 boundedElastic，使 prepareScoring（projectService.get）内的阻塞 JDBC 调用
                    // 不会钉住上游的响应式线程。
                    return spansMono.flatMap(spans -> Mono.fromCallable(
                            () -> prepareScoring(message, traces, spans, threadId, rule, mdc))
                            .subscribeOn(Schedulers.boundedElastic()));
                })
                .flatMap(context -> evaluateAndStore(message, threadModelId, threadId, context, mdc))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("对 threadId '{}' 使用规则 '{}' 评分时发生意外错误：\n\n{}",
                                threadId, rule.getName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    /**
     * 为给定线程构建 Python 评估器请求（项目 + 聊天消息）。调用方保证 {@code traces} 非空。
     */
    private Pair<Project, List<ChatMessage>> prepareScoring(TraceThreadToScoreUserDefinedMetricPython message,
            List<Trace> traces, List<Span> spans, String threadId, AutomationRuleEvaluator<?, ?> rule,
            Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("正在评估 threadId '{}'，由规则 '{}' 采样", threadId, rule.getName());

            Project project = projectService.get(message.projectId(), message.workspaceId());

            // 始终使用富化辅助方法——当 `spans` 为空时（开关关闭，参见 scoreThread），
            // 它通过 ChatMessage.spans 上的 @JsonInclude(NON_NULL) 发出旧版 [{role, content}, ...] 形态。
            // 当非空时，每个 trace 的 assistant 条目携带嵌套的 span 树。
            List<ChatMessage> context;
            try {
                context = OnlineScoringEngine.fromTraceToThreadEnriched(traces, spans);
            } catch (Exception exception) {
                userFacingLogger.error("为 threadId '{}' 准备 Python 请求时出错：\n\n{}",
                        threadId, exception.getMessage());
                throw exception;
            }

            userFacingLogger.info("将 threadId '{}' 发送到 Python 评估器，使用以下上下文：\n\n{}",
                    threadId, context);

            return Pair.of(project, context);
        }
    }

    private Mono<Map<String, List<BigDecimal>>> evaluateAndStore(
            TraceThreadToScoreUserDefinedMetricPython message, UUID threadModelId, String threadId,
            Pair<Project, List<ChatMessage>> context, Map<String, String> mdc) {
        var project = context.getLeft();
        var chatMessages = context.getRight();
        return pythonEvaluatorService.evaluateThread(message.code().metric(), chatMessages)
                .doOnNext(withMdc(mdc, scoreResults -> userFacingLogger
                        .info("收到 threadId '{}' 的响应：\n\n{}", threadId, scoreResults)))
                .flatMap(scoreResults -> {
                    List<FeedbackScoreBatchItemThread> scores = scoreResults.stream()
                            .map(scoreResult -> FeedbackScoresMapper.INSTANCE.map(
                                    scoreResult,
                                    threadModelId,
                                    threadId,
                                    message.projectId(),
                                    project.name(),
                                    ScoreSource.ONLINE_SCORING))
                            .toList();
                    return storeThreadScores(scores, threadId, message.userName(), message.workspaceId());
                })
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("threadId '{}' 的分数已成功存储：\n\n{}", threadId, loggedScores)));
    }
}
