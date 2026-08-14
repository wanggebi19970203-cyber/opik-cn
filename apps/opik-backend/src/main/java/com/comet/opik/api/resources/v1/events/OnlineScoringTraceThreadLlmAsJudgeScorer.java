package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.evaluation.EvaluatedThread;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

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
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

@EagerSingleton
@Slf4j
public class OnlineScoringTraceThreadLlmAsJudgeScorer extends OnlineScoringBaseScorer<TraceThreadToScoreLlmAsJudge> {

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final TraceThreadService traceThreadService;
    private final ProjectService projectService;
    private final AutomationRuleEvaluatorService automationRuleEvaluatorService;
    private final AgenticScoringService agenticScoringService;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final SpanService spanService;
    private final OnlineEvaluationRecorder onlineEvaluationRecorder;
    private final AttachmentService attachmentService;

    @Inject
    public OnlineScoringTraceThreadLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull TraceService traceService,
            @NonNull TraceThreadService traceThreadService,
            @NonNull ProjectService projectService,
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService,
            @NonNull AgenticScoringService agenticScoringService,
            @NonNull SpanService spanService,
            @NonNull OnlineEvaluationRecorder onlineEvaluationRecorder,
            @NonNull AttachmentService attachmentService) {
        super(config, redisson, feedbackScoreService, traceService, TRACE_THREAD_LLM_AS_JUDGE,
                Constants.TRACE_THREAD_LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.llmProviderFactory = llmProviderFactory;
        this.traceThreadService = traceThreadService;
        this.projectService = projectService;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.agenticScoringService = agenticScoringService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.spanService = spanService;
        this.onlineEvaluationRecorder = onlineEvaluationRecorder;
        this.attachmentService = attachmentService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringTraceThreadLlmAsJudgeScorer.class);
    }

    /**
     * 使用 AI Proxy 对追踪线程评分并将其存储为反馈评分。
     * 如果评估器有多个分数定义，它会为每个分数定义调用一次 LLM。
     *
     * @param message 一条 Redis 消息，包含要使用评估器代码、工作区和用户名评分的追踪线程。
     */
    @Override
    protected Mono<Void> score(@NonNull TraceThreadToScoreLlmAsJudge message) {

        log.info("收到消息，projectId：'{}'、ruleId：'{}'、threadIds：'{}'，工作区 '{}'",
                message.projectId(), message.ruleId(), message.threadIds(), message.workspaceId());

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

    private Mono<Void> processThreadScores(TraceThreadToScoreLlmAsJudge message, String currentThreadId) {
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
    private Mono<Void> processScoring(TraceThreadToScoreLlmAsJudge message, List<Trace> traces,
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
    private Optional<AutomationRuleEvaluator<?, ?>> findRule(TraceThreadToScoreLlmAsJudge message, String threadId,
            Map<String, String> mdc) {
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
    private Mono<Void> scoreThread(TraceThreadToScoreLlmAsJudge message, List<Trace> traces, UUID threadModelId,
            String threadId, AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc) {
        var traceIds = traces.stream().map(Trace::id).collect(Collectors.toSet());
        // OPIK-7454——在获取之前路由。当标志开启时，用一个廉价的 ClickHouse 聚合
        // （span 字段长度之和）来确定整个线程的大小，该聚合不物化任何 span：仅从这个数字就能检测出
        // 大线程，并走工具路径（骨架 + 每个 trace 的 ReadTool 向下钻取），无需任何批量获取。
        // spans 只在更下方的内联路径上获取，而那里线程按构造低于阈值。
        //
        // 当标志关闭时，大小为 0（不查询），内联序列化器通过 @JsonInclude(NON_NULL) 省略 `spans` 字段，
        // 因此渲染出的 JSON 不变。
        var spansSizeMono = serviceTogglesConfig.isAgenticToolsEnabled()
                ? spanService.getSpansSizeByTraceIds(traceIds)
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, message.userName()))
                : Mono.just(0L);
        // 监控记录器（OPIK-6994）：每次线程评估一个隐藏的评估器 trace，每轮 LLM 一个 llm span，
        // agentic 循环对应工具 span。开关关闭时为 NOOP。响应式解析，因为项目名称查找是阻塞的。
        Mono<EvaluationRecorder> recorderMono = Mono.fromCallable(
                () -> serviceTogglesConfig.isOnlineScoringTracingEnabled()
                        ? onlineEvaluationRecorder.begin(
                                EvaluatedThread.builder()
                                        .id(threadId)
                                        .projectId(message.projectId())
                                        .projectName(projectService.get(message.projectId(),
                                                message.workspaceId()).name())
                                        .build(),
                                rule.getId(), rule.getName(), message.code().model().name(),
                                message.workspaceId(), message.userName())
                        : EvaluationRecorder.NOOP)
                .subscribeOn(Schedulers.boundedElastic())
                // 监控是尽力而为的：项目名称查找失败（例如缺少项目）绝不能中止实际的线程评分，
                // 因此降级为 NOOP 而不是让 zip 失败。
                .onErrorResume(error -> {
                    log.warn("启动线程 '{}' 的在线评估监控失败，继续而不带监控", threadId, error);
                    return Mono.just(EvaluationRecorder.NOOP);
                });

        // 检查线程中是否有任何 trace 有附件——如果有，则无视上下文大小强制走 agentic 工具路径
        // （评判器需要 get_attachment 来获取它们）。尽力而为：临时列表错误返回 false，
        // 并回退到正常的基于大小的路由。
        Mono<Boolean> hasAttachmentsMono = serviceTogglesConfig.isAgenticToolsEnabled()
                ? attachmentService.hasAnyAttachmentByEntityIds(EntityType.TRACE, traceIds)
                        .onErrorReturn(false)
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, message.userName()))
                : Mono.just(false);

        return Mono.zip(recorderMono, spansSizeMono, hasAttachmentsMono)
                .flatMap(tuple -> {
                    var recorder = tuple.getT1();
                    var spanBytes = tuple.getT2();
                    var hasAttachments = tuple.getT3();

                    // 估算内联提示词大小（trace 正文 + span 内容）以进行路由；开关关闭时为 0。
                    // spanBytes 来自廉价聚合；服务再加上堆内的 trace 正文。
                    var estimatedTokens = serviceTogglesConfig.isAgenticToolsEnabled()
                            ? agenticScoringService.estimateThreadContextTokens(traces, spanBytes)
                            : 0;
                    // 仅在内联/富化路径上获取 spans：开关开启、低于路由阈值、且没有附件
                    // （附件会强制走工具路径）。这样的线程按构造是小的，因此获取是有界的；
                    // 流式字节上限仍是兜底。工具路径在这里不获取任何内容——它按需通过 ReadTool 逐 trace 钻取。
                    var maxPreloadBytes = agenticToolsMaxPreloadBytes();
                    var fetchSpansForInline = serviceTogglesConfig.isAgenticToolsEnabled()
                            && estimatedTokens < onlineScoringConfig.getAgenticToolsThresholdTokens()
                            && !hasAttachments;
                    var spansMono = fetchSpansForInline
                            ? agenticScoringService.preloadThreadSpansBounded(
                                    spanService.getByTraceIds(traceIds), maxPreloadBytes)
                                    .map(preload -> getSpansFromPreloadAndLogOverflow(preload, userFacingLogger,
                                            threadId, mdc))
                                    .contextWrite(ctx -> ctx
                                            .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                            .put(RequestContext.USER_NAME, message.userName()))
                            : Mono.just(List.<Span>of());

                    return spansMono.flatMap(spans -> recorder.monitor(
                            evaluate(message, traces, spans, estimatedTokens, hasAttachments, threadModelId,
                                    threadId, rule, mdc, recorder))
                            .flatMap(scores -> storeThreadScores(scores, threadId, message.userName(),
                                    message.workspaceId())));
                })
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("threadId '{}' 的分数已成功存储：\n\n{}", threadId, loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("对 threadId '{}' 使用规则 '{}' 评分时发生意外错误：\n\n{}",
                                threadId, rule.getName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    /**
     * 为线程构建并运行 LLM 评分链路。当开关开启且满足以下任一条件时，通过 agentic 工具分支路由
     * （骨架 + ReadTool/JqTool/SearchTool 向下钻取）：内联渲染的线程会超过配置的大小阈值，或线程有附件
     * （使评判器可以调用 get_attachment）。提供方必须支持工具调用，且模板必须仅含文本。
     * 否则内联路径保持不变地运行——形态与现在相同。
     */
    private Mono<List<FeedbackScoreBatchItemThread>> evaluate(TraceThreadToScoreLlmAsJudge message,
            List<Trace> traces, List<Span> spans, int estimatedTokens, boolean hasAttachments,
            UUID threadModelId, String threadId, AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc,
            EvaluationRecorder recorder) {
        // `spans` 在工具路径上为空（没有获取任何内容——模型通过 ReadTool 逐 trace 钻取），
        // 否则为有界的内联 span 列表。`estimatedTokens` 已预先从廉价大小聚合计算出来，
        // 因此路由决策的大小估算不需要 spans。
        // 每次线程评估一个守卫；当规则未设置 maxCostUsd 时为 UNLIMITED。对每次 LLM 调用计费
        // （初始 + 工具轮 + 收尾），并告诉工具循环何时开始收尾。
        var costGuard = BudgetGuard.create(message.code().maxCostUsd(), message.code().model().name(),
                llmProviderFactory);
        return Mono.fromCallable(
                () -> prepareEvaluation(message, traces, spans, estimatedTokens, hasAttachments, threadId, rule,
                        mdc))
                .subscribeOn(Schedulers.parallel())
                .flatMap(prepared -> {
                    // 与 trace 评估统一的结构：在第一轮 LLM 之前记录 prepare_evaluation span
                    // （获取的 spans、大小估算、模式）。agentic 标志也会设置父 trace 的模式。
                    recorder.recordPreparation(spans.size(), prepared.estimatedTokens(), prepared.useTools());
                    return scoreTraceReactive(prepared.scoreRequest(), message, recorder, costGuard)
                            .doOnNext(withMdc(mdc, chatResponse -> {
                                if (userFacingLogger.isInfoEnabled()) {
                                    userFacingLogger.info("收到 threadId '{}' 的响应：'{}'",
                                            threadId, agenticScoringService.summarizeResponse(chatResponse));
                                }
                            }))
                            .flatMap(initialResponse -> prepared.useTools()
                                    ? handleToolCalls(initialResponse, prepared.scoreRequest(),
                                            prepared.structuredRequest(), message, mdc, recorder, costGuard)
                                    : Mono.just(initialResponse));
                })
                .map(chatResponse -> {
                    try (var logContext = wrapWithMdc(mdc)) {
                        Project project = projectService.get(message.projectId(), message.workspaceId());
                        if (costGuard.wasBudgetEnforced()) {
                            // 以 wasBudgetEnforced()（循环的预算门控确实缩短了运行）为键，
                            // 而非 shouldWrapUp()（仅仅花费 >= 限制）：此警告声称调查被停止，因此它
                            // 绝不能在内联单次调用路径或仅跨越花费的自然停止上触发。
                            // 与 ToolCallLoop 内标记的 budget_exceeded trace 标签是同一权威信号。
                            userFacingLogger.warn(
                                    "已达到 '{}' USD 的花费预算，threadId '{}'（已花费 '{}'）；"
                                            + "停止调查，并用目前收集到的分数收尾。",
                                    costGuard.limitUsd(), threadId, costGuard.spentUsd());
                        }
                        var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse, message.code().schema());
                        OnlineScoringEngine.logSkippedNullScores(userFacingLogger, parsed, "threadId", threadId);
                        OnlineScoringEngine.logResponseIssues(userFacingLogger, parsed, "threadId", threadId);
                        return parsed.scores().stream()
                                .map(item -> FeedbackScoresMapper.INSTANCE.map(
                                        item.toBuilder()
                                                .id(threadModelId)
                                                .projectId(message.projectId())
                                                .projectName(project.name())
                                                .source(ScoreSource.ONLINE_SCORING)
                                                .build(),
                                        threadId))
                                .toList();
                    }
                });
    }

    /**
     * 同步准备步骤——选择路径（内联 vs agentic 工具）并构建聊天请求。由调用方用
     * {@code Schedulers.parallel()} 上的 {@code Mono.fromCallable} 包裹，因为其主体是 CPU 密集的
     * （提示词渲染 / 内联上下文的 JSON 序列化）；这里没有阻塞 I/O。大小估算由调用方提供
     * （来自廉价的 ClickHouse 聚合），因此此步骤不再仅仅为了估算路由大小而序列化 spans。
     */
    private PreparedEvaluation prepareEvaluation(TraceThreadToScoreLlmAsJudge message, List<Trace> traces,
            List<Span> spans, int estimatedContextTokens, boolean hasAttachments, String threadId,
            AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("正在评估 threadId '{}'，由规则 '{}' 采样", threadId, rule.getName());

            String modelName = message.code().model().name();
            // estimatedContextTokens 已预先从廉价的 ClickHouse 大小聚合得出（开关关闭时为 0）。
            // shouldUseAgenticTools 重新检查开关、提供方工具支持和模板模态；`spans` 仅用于渲染内联路径
            // （在工具路径上为空）。
            boolean useTools = shouldUseAgenticTools(estimatedContextTokens, hasAttachments, modelName,
                    threadId, message.code().messages());

            ChatRequest scoreRequest;
            ChatRequest structuredRequest;
            try {
                var strategy = llmProviderFactory.getStructuredOutputStrategy(modelName);
                if (useTools) {
                    // 工具路径：骨架 + 向下钻取提示，而不是完整 trace 转储。代理通过
                    // read(type=trace, id=X) 钻取特定 trace——与 trace 级别路径相同的惰性模式。
                    // 任何一个 trace 的 spans 仅在模型实际请求该 trace 时于 ReadTool.readTrace 中响应式获取。
                    scoreRequest = OnlineScoringEngine.prepareThreadLlmRequestWithTools(
                            message.code(), traces, strategy);
                    // 工具循环后的收尾使用相同的结构化输出策略——对于线程没有单独的 InstructionStrategy
                    // 变体，因此初始和收尾请求共享一种形态（除工具规格外）。
                    structuredRequest = scoreRequest;
                } else {
                    // 内联路径：使用富化的每个 assistant `spans` 形态渲染 {{context}}。
                    // 当开关关闭时，`spans` 是空列表，JSON 与当前的 [{role, content}, ...] 在线上完全一致。
                    scoreRequest = OnlineScoringEngine.prepareThreadLlmRequest(message.code(), traces, strategy,
                            spans);
                    structuredRequest = scoreRequest;
                }
            } catch (Exception exception) {
                OnlineScoringEngine.logPreparingLlmRequestError(userFacingLogger, log, "threadId",
                        threadId, exception);
                throw exception;
            }

            if (useTools) {
                // 仅在第一次调用时 REQUIRED——与 trace 评分器相同的理由：强制在模型能够仅从骨架回答之前
                // 至少调用一次工具。后续轮切换到 AUTO，使收尾轮可以发出 JSON 而无需调用工具。
                scoreRequest = agenticScoringService.addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
            }

            // summarizeRequest 很廉价（没有逐消息 toString 流式处理）。使用 INFO 级别以镜像
            // trace 评分器对称的 Evaluating / Sending / Received 链路。
            userFacingLogger.info("将 threadId '{}' 发送到 LLM：{}",
                    threadId, agenticScoringService.summarizeRequest(scoreRequest, modelName, useTools));
            return new PreparedEvaluation(scoreRequest, structuredRequest, useTools, estimatedContextTokens);
        }
    }

    /**
     * 关于是否为线程附加工具规格 + 运行工具调用循环的路由决策。
     * 要求开关开启且至少满足以下之一：
     * <ul>
     *   <li>估算的线程上下文超过配置的 token 阈值，或</li>
     *   <li>线程有附件（评判器需要 get_attachment 来获取它们）</li>
     * </ul>
     * 两种情况还要求提供方支持工具调用且模板仅含文本。当这些次级守卫失败时，
     * 回退到内联并发出面向用户的警告。
     */
    boolean shouldUseAgenticTools(int estimatedContextTokens, boolean hasAttachments, String modelName,
            String threadId, List<LlmAsJudgeMessage> templateMessages) {
        if (!serviceTogglesConfig.isAgenticToolsEnabled()) {
            return false;
        }
        boolean overSizeThreshold = estimatedContextTokens >= onlineScoringConfig.getAgenticToolsThresholdTokens();
        // 当两个触发器都未满足时跳过提供方查找——大多数评估要么开关关闭，
        // 要么既没有大上下文也没有附件。
        if (!overSizeThreshold && !hasAttachments) {
            return false;
        }
        boolean providerSupportsTools = agenticScoringService.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName));
        if (!providerSupportsTools) {
            userFacingLogger.warn(
                    "线程有附件或上下文超过 '{}' 个 token，但模型 '{}' 的提供方不支持工具调用；"
                            + "为 threadId '{}' 回退到内联路径——可能溢出上下文窗口。",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), modelName, threadId);
            return false;
        }
        // 线程的 agentic 工具渲染路径只替换字符串内容；多模态模板（与文本并存的图片 / 音频 / 视频部分）
        // 否则会触发 renderThreadMessagesWithReplacement 中的安全抛出并使评估失败。
        if (OnlineScoringEngine.hasMultimodalTemplate(templateMessages)) {
            userFacingLogger.warn(
                    "线程有附件或上下文超过 '{}' 个 token，但评估器模板含有多模态内容；"
                            + "为 threadId '{}' 回退到内联路径——可能溢出上下文窗口。",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), threadId);
            return false;
        }
        if (hasAttachments) {
            log.debug("线程有附件；为 threadId '{}' 切换到 agentic 工具模式", threadId);
        } else {
            log.debug("线程上下文超过 '{}' 个 token；为 threadId '{}' 切换到 agentic 工具模式",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), threadId);
        }
        return true;
    }

    /**
     * 将同步的 {@code ChatLanguageModel.chat} 调用包装进 Mono，并在 {@link Schedulers#boundedElastic()}
     * 上调度，使阻塞的 Jersey 客户端 I/O 不会钉住每个流的 worker 调度器线程（OPIK-6308）。镜像 trace 评分器。
     */
    private Mono<ChatResponse> scoreTraceReactive(ChatRequest request, TraceThreadToScoreLlmAsJudge message,
            EvaluationRecorder recorder, BudgetGuard costGuard) {
        var call = Mono.fromCallable(() -> aiProxyService.scoreTrace(
                request, message.code().model(), message.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
        return costGuard.track(recorder.recordLlmCall(request, call));
    }

    // 包私有，供单元测试使用。
    Mono<ChatResponse> handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceThreadToScoreLlmAsJudge message, Map<String, String> mdc,
            EvaluationRecorder recorder, BudgetGuard costGuard) {
        // 共享的循环编排位于 AgenticScoringService 中；这里我们只提供线程作用域的上下文——
        // 没有单一的活动 trace。ReadTool 通过标准的 read(type=trace, id=X) 路径按需从线程中获取任何 trace。
        // GetTraceSpansTool 在此上下文上返回重定向错误（参见 GetTraceSpansTool#execute）。
        return agenticScoringService.runToolCallLoop(chatResponse, toolRequest, structuredRequest,
                () -> TraceToolContext.forThread(message.workspaceId(), message.userName(), message.projectId(),
                        onlineScoringConfig.getAgenticToolsMaxInjectedBytes()),
                request -> scoreTraceReactive(request, message, recorder, costGuard),
                costGuard,
                () -> message.code().model().name(), "threadId/ruleId=" + message.ruleId(), userFacingLogger, mdc,
                recorder);
    }

    private record PreparedEvaluation(ChatRequest scoreRequest, ChatRequest structuredRequest, boolean useTools,
            int estimatedTokens) {
    }
}
