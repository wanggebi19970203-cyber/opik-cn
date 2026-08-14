package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.Span;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.AttachmentSummaries;
import com.comet.opik.api.resources.v1.events.tools.EntityRef;
import com.comet.opik.api.resources.v1.events.tools.EntityType;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * 此服务监听 Redis 流，以获取要在 LLM 提供方中评分的 Span。它使用 Span 中的值渲染
 * 评估器的消息模板，并准备结构化输出模式。
 *
 * <p>默认情况下评分是内联的（替换模板变量，一次 LLM 调用）。当规则的提示词引用了 {@code {{span}}}
 * 结构变量（且 {@code agentic_tools} 开关开启、提供方支持工具调用）时，评分器改为注入一个紧凑的
 * span 结构（span id + span 自身的附件 {@code file_name}）并运行 agentic 工具循环，使评判器可以调用
 * {@code get_attachment(type=span, ...)} / {@code read} / {@code jq} 来加载和检查 span 的附件。
 * 这是 {@link OnlineScoringLlmAsJudgeScorer} 中 {@code {{trace}}} 路径的跨度级别对应物。
 */
@EagerSingleton
@Slf4j
public class OnlineScoringSpanLlmAsJudgeScorer extends OnlineScoringBaseScorer<SpanToScoreLlmAsJudge> {

    private final ServiceTogglesConfig serviceTogglesConfig;
    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final AgenticScoringService agenticScoringService;
    private final AttachmentService attachmentService;
    private final OnlineEvaluationRecorder onlineEvaluationRecorder;

    @Inject
    public OnlineScoringSpanLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull AgenticScoringService agenticScoringService,
            @NonNull AttachmentService attachmentService,
            @NonNull OnlineEvaluationRecorder onlineEvaluationRecorder) {
        super(config, redisson, feedbackScoreService, traceService, SPAN_LLM_AS_JUDGE, Constants.SPAN_LLM_AS_JUDGE);
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringSpanLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.agenticScoringService = agenticScoringService;
        this.attachmentService = attachmentService;
        this.onlineEvaluationRecorder = onlineEvaluationRecorder;
    }

    @Override
    public void start() {
        if (serviceTogglesConfig.isSpanLlmAsJudgeEnabled()) {
            super.start();
        } else {
            log.info("在线评分 Span LLM 评判器消费者因被禁用而不会启动");
        }
    }

    /**
     * 使用 AI Proxy 对 span 评分并将其存储为 FeedbackScore。
     * 如果评估器有多个分数定义，它会为每个分数定义调用一次 LLM。
     *
     * @param message 一条 Redis 消息，包含要使用评估器代码、工作区和用户名评分的 Span。
     */
    @Override
    protected Mono<Void> score(@NonNull SpanToScoreLlmAsJudge message) {
        var span = message.span();
        log.info("收到消息，spanId '{}'、userName '{}'，将在 '{}' 中评分",
                span.id(), message.userName(), message.llmAsJudgeCode().model().name());

        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.SPAN_ID, span.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString());

        // {{span}} 变量是声明式的 agentic 触发器。检测独立于 agentic 工具开关，
        // 因此该变量始终被替换（绝不会泄漏裸 "span" 哨兵字面量）：
        //   - 开关开 → 构建真实的 span 结构（span id + 附件 file_name）；如果提供方支持工具，
        //              运行 agentic 循环，使评判器可以 get_attachment。
        //   - 开关关 → 跳过附件获取并注入空结构，因此 {{span}} 在内联中渲染为
        //              "{}"（由 prepareEvaluation 中的内联分支处理）。
        boolean referencesSpan = OnlineScoringEngine.templateReferencesSpanStructure(
                message.llmAsJudgeCode().messages(),
                message.llmAsJudgeCode().variables(),
                PromptType.MUSTACHE);
        boolean agenticToolsEnabled = serviceTogglesConfig.isAgenticToolsEnabled();

        // 监控记录器（OPIK-6994）：每次 span 评估一个隐藏的 source=evaluator trace，
        // 评分调用对应一个 llm span。开关关闭时为 NOOP——没有额外写入。
        EvaluationRecorder recorder = serviceTogglesConfig.isOnlineScoringTracingEnabled()
                ? onlineEvaluationRecorder.begin(span, message.ruleId(), message.ruleName(),
                        message.llmAsJudgeCode().model().name(), message.workspaceId(), message.userName())
                : EvaluationRecorder.NOOP;

        Mono<List<FeedbackScoreBatchItem>> scoresMono = (referencesSpan && agenticToolsEnabled)
                ? buildSpanStructure(span, message)
                        .flatMap(structure -> evaluate(message, structure, true, mdc, recorder))
                : evaluate(message, null, referencesSpan, mdc, recorder);

        return recorder.monitor(scoresMono)
                .flatMap(scores -> storeSpanScores(scores, span, message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("spanId '{}' 的分数已成功存储：\n\n{}", span.id(), loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("对 spanId '{}' 使用规则 '{}' 评分时发生意外错误：\n\n{}",
                                span.id(), message.ruleName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    /**
     * 构建注入提示词的 {@code {{span}}} 结构：一个小信封
     * （{@code span_id} + span 自身的 {@code attachments} 列表 + {@code data} = span JSON），
     * 使评判器内联拥有真实的 span id 和附件 {@code file_name}，并能用正确的值调用
     * {@code get_attachment(type=span, id=<span_id>, file_name=...)}。附件元数据不携带在
     * {@link Span} 对象上，因此通过一次尽力而为的查找获取——列表失败会降级为无附件，而不是阻塞评分。
     */
    private Mono<String> buildSpanStructure(Span span, SpanToScoreLlmAsJudge message) {
        return fetchSpanAttachments(span, message)
                .map(spanAttachments -> {
                    ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
                    envelope.put("span_id", span.id() != null ? span.id().toString() : null);
                    envelope.set("attachments", AttachmentSummaries.toJsonArray(spanAttachments));
                    envelope.set("data", JsonUtils.getMapper().valueToTree(span));
                    return envelope.toString();
                });
    }

    /**
     * 列出 span 自身的附件，通过 {@link AgenticScoringService#listAttachmentsToleratingUploadRace}
     * 容忍上传竞态（以 span 正文引用附件为门控）。
     */
    private Mono<List<AttachmentInfo>> fetchSpanAttachments(Span span, SpanToScoreLlmAsJudge message) {
        Mono<List<AttachmentInfo>> fetch = attachmentService
                .getAttachmentInfoByEntity(span.id(), com.comet.opik.api.attachment.EntityType.SPAN,
                        span.projectId())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName()));
        return agenticScoringService.listAttachmentsToleratingUploadRace(fetch, message.workspaceId(), span.id(),
                span.input(), span.output(), span.metadata());
    }

    private Mono<List<FeedbackScoreBatchItem>> evaluate(SpanToScoreLlmAsJudge message,
            String spanStructureJson, boolean referencesSpan, Map<String, String> mdc,
            EvaluationRecorder recorder) {
        var span = message.span();
        // 同步准备（提示词渲染）是 CPU 密集的——在 Schedulers.parallel() 上调度，
        // 这样就不会加重上游发出附件获取的 R2DBC 调度器的负担。MDC 通过 withMdc()
        // 在下面的响应式操作符上重新应用，因为它们可能运行在不同的线程上。
        return Mono.fromCallable(() -> prepareEvaluation(message, spanStructureJson, referencesSpan, mdc))
                .subscribeOn(Schedulers.parallel())
                .flatMap(prepared -> {
                    // span 路径上没有 span 获取或 token 估算；记录模式决策，
                    // 使父监控 trace 仍能反映 agentic 与内联的区分。
                    recorder.recordPreparation(0, 0, prepared.useTools());
                    return scoreSpanReactive(prepared.scoreRequest(), message, recorder)
                            .doOnNext(withMdc(mdc, chatResponse -> {
                                if (userFacingLogger.isInfoEnabled()) {
                                    userFacingLogger.info("收到 spanId '{}' 的响应：'{}'",
                                            span.id(), agenticScoringService.summarizeResponse(chatResponse));
                                }
                            }))
                            .flatMap(initialResponse -> prepared.useTools()
                                    ? handleToolCalls(initialResponse, prepared.scoreRequest(),
                                            prepared.structuredRequest(), message, mdc, recorder)
                                    : Mono.just(initialResponse));
                })
                .map(chatResponse -> {
                    try (var logContext = wrapWithMdc(mdc)) {
                        var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse,
                                message.llmAsJudgeCode().schema());
                        OnlineScoringEngine.logSkippedNullScores(userFacingLogger, parsed, "spanId", span.id());
                        OnlineScoringEngine.logResponseIssues(userFacingLogger, parsed, "spanId", span.id());
                        return parsed.scores().stream()
                                .map(item -> (FeedbackScoreBatchItem) item.toBuilder()
                                        .id(span.id())
                                        .projectId(span.projectId())
                                        .projectName(span.projectName())
                                        .build())
                                .toList();
                    }
                });
    }

    private PreparedEvaluation prepareEvaluation(SpanToScoreLlmAsJudge message, String spanStructureJson,
            boolean referencesSpan, Map<String, String> mdc) {
        var span = message.span();
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("正在评估 spanId '{}'，由规则 '{}' 采样", span.id(), message.ruleName());

            String modelName = message.llmAsJudgeCode().model().name();
            boolean agenticToolsEnabled = serviceTogglesConfig.isAgenticToolsEnabled();
            boolean providerSupportsTools = agenticScoringService.supportsToolCalling(
                    llmProviderFactory.getLlmProvider(modelName));
            // 工具需要 {{span}} 触发器 AND agentic 工具开关 AND 支持工具调用的提供方。
            // {{span}} 替换本身独立于工具——参见下面的内联分支。
            boolean useTools = referencesSpan && agenticToolsEnabled && providerSupportsTools;

            if (referencesSpan && agenticToolsEnabled && !providerSupportsTools) {
                // 可操作的配置错误：提示词引用了 {{span}}（因此用户期望工具驱动的检查 / 附件加载），
                // 但所选模型的提供方无法调用工具。我们仍在内联中注入结构，使评判器至少能看到 id。
                userFacingLogger.warn(
                        "跨度 '{}' 的规则引用了 {{span}}，但模型 '{}' 的提供方不支持工具调用；"
                                + "回退到内联路径——评判器无法加载附件。"
                                + "请选择一个支持工具调用的提供方（OpenAI / Anthropic / Gemini / OpenRouter /"
                                + " Vertex / Bedrock）。",
                        span.id(), modelName);
            }

            LlmRequests requests;
            try {
                if (useTools) {
                    requests = buildToolCallingRequests(message, span, modelName, spanStructureJson);
                } else if (referencesSpan && spanStructureJson != null) {
                    requests = buildInlineStructureRequests(message, span, modelName, spanStructureJson);
                } else if (referencesSpan) {
                    requests = buildSentinelStructureRequests(message, span, modelName, spanStructureJson);
                } else {
                    requests = buildPlainRequests(message, span, modelName);
                }
            } catch (Exception exception) {
                userFacingLogger.error("为 spanId '{}' 准备 LLM 请求时出错：",
                        span.id(), exception);
                throw exception;
            }

            userFacingLogger.info("将 spanId '{}' 发送到 LLM：{}",
                    span.id(), agenticScoringService.summarizeRequest(requests.score(), modelName, useTools));

            return PreparedEvaluation.builder()
                    .scoreRequest(requests.score())
                    .structuredRequest(requests.structured())
                    .useTools(useTools)
                    .build();
        }
    }

    /** 由 {@code build*Requests} 分支之一产生的评分和结构化输出请求。 */
    @Builder(toBuilder = true)
    private record LlmRequests(ChatRequest score, ChatRequest structured) {
    }

    /**
     * {@code useTools}：{{span}} + agentic 工具 + 支持工具调用的提供方。工具循环请求使用软
     * InstructionStrategy；收尾使用提供方原生的结构化输出策略（与 trace 评分器相同的不对称性——
     * 在 InstructionStrategy 下 Anthropic 尤其会在收尾轮返回散文）。
     */
    private LlmRequests buildToolCallingRequests(SpanToScoreLlmAsJudge message, Span span, String modelName,
            String spanStructureJson) {
        String drillDownHint = ("call read(type=span, id=%s, tier=MEDIUM) for the full span"
                + " with per-string truncation hints, or jq(type=span, id=%s,"
                + " expression='<path>') for a specific section")
                .formatted(span.id(), span.id());
        ChatRequest scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span, new InstructionStrategy(),
                onlineScoringConfig.getMaxPromptFieldChars(), drillDownHint, spanStructureJson);
        ChatRequest structuredRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span,
                llmProviderFactory.getStructuredOutputStrategy(modelName),
                onlineScoringConfig.getMaxPromptFieldChars(), drillDownHint, spanStructureJson);
        // 仅在第一次调用时 REQUIRED 强制至少一次工具调用；后续轮在 handleToolCalls 中切换到 AUTO，
        // 使模型可以决定何时停止调查。
        scoreRequest = agenticScoringService.addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
        return LlmRequests.builder().score(scoreRequest).structured(structuredRequest).build();
    }

    /**
     * 内联回退：非工具调用提供方上的 {{span}}（开关开启，真实结构）。没有 read/jq 工具可向下钻取，
     * 因此截断替换以限制上下文窗口——否则大型 span 会以不截断的方式注入，并可能溢出模型的上下文。
     * 没有向下钻取提示：模型无法对其采取行动，因此超出上限的值只是被截断。
     */
    private LlmRequests buildInlineStructureRequests(SpanToScoreLlmAsJudge message, Span span,
            String modelName, String spanStructureJson) {
        ChatRequest scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span,
                llmProviderFactory.getStructuredOutputStrategy(modelName),
                onlineScoringConfig.getMaxPromptFieldChars(), INLINE_TRUNCATION_HINT, spanStructureJson);
        return LlmRequests.builder().score(scoreRequest).structured(scoreRequest).build();
    }

    /**
     * 仍注入 {{span}} 结构的内联路径，使变量得以渲染而不是泄漏裸哨兵。
     * 开关关闭：spanStructureJson 为 null → 渲染 "{}"（很小，无需截断；用户变量像正常内联路径一样保持不截断）。
     */
    private LlmRequests buildSentinelStructureRequests(SpanToScoreLlmAsJudge message, Span span,
            String modelName, String spanStructureJson) {
        ChatRequest scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span,
                llmProviderFactory.getStructuredOutputStrategy(modelName), spanStructureJson);
        return LlmRequests.builder().score(scoreRequest).structured(scoreRequest).build();
    }

    /** 正常内联路径：没有 {{span}} 引用，因此不注入结构。 */
    private LlmRequests buildPlainRequests(SpanToScoreLlmAsJudge message, Span span, String modelName) {
        ChatRequest scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span,
                llmProviderFactory.getStructuredOutputStrategy(modelName));
        return LlmRequests.builder().score(scoreRequest).structured(scoreRequest).build();
    }

    /**
     * 将同步的 {@code ChatLanguageModel.chat} 调用包装进在 {@link Schedulers#boundedElastic()}
     * 上调度的 Mono，使阻塞的 Jersey 客户端 I/O 不会钉住每个流的 worker 调度器线程。
     */
    private Mono<ChatResponse> scoreSpanReactive(ChatRequest request, SpanToScoreLlmAsJudge message,
            EvaluationRecorder recorder) {
        var call = Mono.fromCallable(() -> aiProxyService.scoreTrace(
                request, message.llmAsJudgeCode().model(), message.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
        return recorder.recordLlmCall(request, call);
    }

    // 包私有，供单元测试使用。
    Mono<ChatResponse> handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, SpanToScoreLlmAsJudge message, Map<String, String> mdc,
            EvaluationRecorder recorder) {
        var span = message.span();
        // 共享的循环编排位于基础评分器中；这里我们只提供 span 特有的上下文填充——
        // 预填充活动 span，使 read(type=span) / jq(type=span) 无需重新获取即可解析它。
        // 此方法运行与 trace/thread 评分器相同的多轮 agentic 工具循环，但 span 评估器 DTO
        // 不暴露 maxCostUsd 字段（不同于 trace/thread），因此没有需要强制执行的每次评估花费预算——
        // 始终传入无限守卫。
        return agenticScoringService.runToolCallLoop(chatResponse, toolRequest, structuredRequest,
                () -> {
                    var ctx = TraceToolContext.forActiveSpan(span, message.workspaceId(),
                            message.userName(), onlineScoringConfig.getAgenticToolsMaxInjectedBytes());
                    ctx.cache(new EntityRef(EntityType.SPAN, span.id().toString()),
                            JsonUtils.getMapper().valueToTree(span));
                    return ctx;
                },
                request -> scoreSpanReactive(request, message, recorder),
                BudgetGuard.UNLIMITED,
                () -> message.llmAsJudgeCode().model().name(), span.id().toString(), userFacingLogger, mdc,
                recorder);
    }

    /**
     * 从 {@link #prepareEvaluation} 传递到 {@link #evaluate}。{@code useTools} 决定在第一个响应之后
     * {@code handleToolCalls} 是否运行 agentic 循环。
     */
    @Builder(toBuilder = true)
    private record PreparedEvaluation(ChatRequest scoreRequest, ChatRequest structuredRequest, boolean useTools) {
    }
}
