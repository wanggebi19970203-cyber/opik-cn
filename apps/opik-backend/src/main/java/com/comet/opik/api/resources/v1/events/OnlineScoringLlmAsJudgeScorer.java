package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.CompressionTier;
import com.comet.opik.api.resources.v1.events.tools.EntityRef;
import com.comet.opik.api.resources.v1.events.tools.EntityType;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

@EagerSingleton
@Slf4j
public class OnlineScoringLlmAsJudgeScorer extends OnlineScoringBaseScorer<TraceToScoreLlmAsJudge> {

    private static final String ONLINE_SCORING_NAMESPACE = "online_scoring";
    private static final AttributeKey<String> WORKSPACE_ID_KEY = AttributeKey.stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME_KEY = AttributeKey.stringKey("workspace_name");
    private static final AttributeKey<String> PATH_KEY = AttributeKey.stringKey("path");
    private static final AttributeKey<String> TRIGGER_KEY = AttributeKey.stringKey("trigger");

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;
    private final SpanService spanService;
    private final AgenticScoringService agenticScoringService;
    private final TraceCompressor traceCompressor;
    private final WorkspaceNameService workspaceNameService;
    private final OpikConfiguration opikConfiguration;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final OnlineEvaluationRecorder onlineEvaluationRecorder;
    private final AttachmentService attachmentService;
    private final LongCounter routingDecisions;

    @Inject
    public OnlineScoringLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull TestSuiteAssertionCounterService testSuiteAssertionCounterService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull SpanService spanService,
            @NonNull AgenticScoringService agenticScoringService,
            @NonNull TraceCompressor traceCompressor,
            @NonNull WorkspaceNameService workspaceNameService,
            @NonNull OpikConfiguration opikConfiguration,
            @NonNull OnlineEvaluationRecorder onlineEvaluationRecorder,
            @NonNull AttachmentService attachmentService) {
        super(config, redisson, feedbackScoreService, traceService,
                LLM_AS_JUDGE, Constants.LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
        this.spanService = spanService;
        this.agenticScoringService = agenticScoringService;
        this.traceCompressor = traceCompressor;
        this.workspaceNameService = workspaceNameService;
        this.opikConfiguration = opikConfiguration;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.onlineEvaluationRecorder = onlineEvaluationRecorder;
        this.attachmentService = attachmentService;
        this.routingDecisions = GlobalOpenTelemetry.getMeter(ONLINE_SCORING_NAMESPACE)
                .counterBuilder("online_scoring_agentic_routing_total")
                .setDescription("Agentic vs inline routing decisions per evaluation, by trigger and workspace")
                .build();
    }

    /**
     * 为评分后链路解析 workspaceName。之所以需要这样做，是因为
     * {@link com.comet.opik.domain.ExperimentService#finishExperiments(Set)} 从响应式上下文中读取
     * {@code WORKSPACE_NAME}，而 {@link TraceToScoreLlmAsJudge} 只携带 {@code workspaceId}。
     * {@link WorkspaceNameService#getWorkspaceName} 是按 workspaceId 作为键的 {@code @Cacheable}，
     * 因此每个工作区的后续调用都是免费的。查找失败时我们回退到 {@code workspaceId}，使链路仍然完成——
     * 完成实验比名称好看更重要。
     */
    private String resolveWorkspaceName(String workspaceId) {
        try {
            return workspaceNameService.getWorkspaceName(workspaceId,
                    opikConfiguration.getAuthentication().getReactService().url());
        } catch (Exception e) {
            log.warn("解析 '{}' 的 workspaceName 失败，回退使用工作区 id。错误：{}",
                    workspaceId, e.getMessage());
            return workspaceId;
        }
    }

    @Override
    protected Mono<Void> doScore(TraceToScoreLlmAsJudge message) {
        UUID experimentId = message.experimentId();
        if (experimentId != null) {
            // 在订阅时惰性解析 workspaceName。ExperimentService.finishExperiments
            // （在断言计数器归零时通过 decrementAndFinishIfComplete 到达）从响应式上下文中读取
            // WORKSPACE_NAME；没有它，评分后链路会抛出 NoSuchElementException，消息不会被确认，
            // Redis Streams 会重试整个评分运行——重新运行 LLM 并重新插入断言行。
            // 覆盖 doScore（而非 processEvent）使这个评分后步骤保持在基类用已处理成功计数器包裹的链路内部，
            // 因此这里的失败不会被计为已处理。
            return super.doScore(message)
                    .then(Mono.fromCallable(() -> resolveWorkspaceName(message.workspaceId()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(workspaceName -> testSuiteAssertionCounterService
                                    .decrementAndFinishIfComplete(message.workspaceId(), experimentId)
                                    .contextWrite(ctx -> ctx
                                            .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                            .put(RequestContext.WORKSPACE_NAME, workspaceName)
                                            .put(RequestContext.USER_NAME, message.userName()))));
        }
        return super.doScore(message);
    }

    @Override
    protected Mono<Void> score(@NonNull TraceToScoreLlmAsJudge message) {
        var trace = message.trace();
        log.info("收到消息，traceId '{}'、userName '{}'，将在 '{}' 中评分",
                trace.id(), message.userName(), message.llmAsJudgeCode().model().name());

        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.TRACE_ID, trace.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString());

        // spans 在这里、在响应式链路中被获取，仅当它们确实会被下游消费时——
        // 要么预填充 agentic 工具缓存（提供方支持工具且 experimentId 分支或基于大小的开关开启），
        // 要么在内联路径上替换进 {{spans}} 模板变量（哨兵：任何映射到裸字符串 "spans" 的变量）。
        // 否则跳过该 I/O。保持获取是响应式的、并放在 evaluate() 之外，避免了 .block() 模式，
        // 后者会为上游等待钉住 workersScheduler 线程（OPIK-6308）。
        Mono<List<Span>> spansMono = shouldFetchSpans(message)
                ? spanService.getByTraceIds(Set.of(trace.id()))
                        .collectList()
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, message.userName()))
                : Mono.just(List.of());

        // {{trace}} 变量的存在是声明式的 agentic 触发器：它将 trace 结构（trace id、span id、
        // 附件 file_name）注入提示词，使评判器可以用真实 id 调用 get_attachment 而不是猜测。
        // 由 agentic 工具开关门控，与基于大小的路径相同。
        boolean referencesTrace = serviceTogglesConfig.isAgenticToolsEnabled()
                && OnlineScoringEngine.templateReferencesTraceStructure(
                        message.llmAsJudgeCode().messages(),
                        message.llmAsJudgeCode().variables(),
                        message.promptType());

        // 评估循环的监控记录器（OPIK-6994）：每次评估一个隐藏的 source=evaluator trace，
        // 每轮 LLM 一个 llm span。开关关闭时为 NOOP——此时评估完全像以前一样运行，没有额外写入。
        EvaluationRecorder recorder = serviceTogglesConfig.isOnlineScoringTracingEnabled()
                ? onlineEvaluationRecorder.begin(trace, message.ruleId(), message.ruleName(),
                        message.llmAsJudgeCode().model().name(), message.workspaceId(), message.userName())
                : EvaluationRecorder.NOOP;

        Mono<List<FeedbackScoreBatchItem>> scoring = spansMono
                .flatMap(spans -> referencesTrace
                        ? buildTraceStructure(trace, spans, message)
                                .flatMap(structure -> evaluate(message, spans, structure.envelopeJson(),
                                        structure.fullJson(), true, mdc, recorder))
                        : evaluate(message, spans, null, null, false, mdc, recorder));

        return recorder.monitor(scoring)
                .flatMap(scores -> storeScores(scores, trace, message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("traceId '{}' 的分数已成功存储：\n\n{}", trace.id(), loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("对 traceId '{}' 使用规则 '{}' 评分时发生意外错误：\n\n{}",
                                trace.id(), message.ruleName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    /**
     * 构建注入提示词的 {@code {{trace}}} 结构：trace+spans 内容
     * （通过 {@link TraceCompressor#compress} 压缩——与 {@code read} 工具使用的路径相同），
     * 在两个层级上都富化了附件 {@code file_name}——trace 节点上 trace 自身的附件，以及每个 span 在
     * {@code span_tree} / {@code spans[]} 条目上的附件——并包裹在一个小的 id 信封中
     * （{@code trace_id} + {@code tier} + {@code data}）。因此评判器可以对 trace 中的任何附件调用
     * {@code get_attachment} 并传入正确的 {@code (type, id, file_name)}，无论该附件位于 trace 上还是其某个 span 上。
     *
     * <p>trace 级别的附件元数据不携带在 {@link Trace} 对象上，每个 span 的元数据也不在 {@link Span} 对象上，
     * 因此两者都需要获取：trace 自身的通过一次容忍竞态的单次查找，spans 的通过一次容忍竞态的批量查找。
     * 两者都容忍附件上传竞态（当评分入队时附件可能尚未持久化），并在列表失败时降级为无附件，而不是阻塞评分。
     */
    private Mono<TraceStructure> buildTraceStructure(Trace trace, List<Span> spans, TraceToScoreLlmAsJudge message) {
        Mono<List<AttachmentInfo>> traceColdFetch = attachmentService
                .getAttachmentInfoByEntity(trace.id(), com.comet.opik.api.attachment.EntityType.TRACE,
                        trace.projectId())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName()));
        // trace 自身的附件：容忍竞态（可能尚未上传），以 trace 正文引用附件为门控。
        Mono<List<AttachmentInfo>> traceAttachmentsMono = agenticScoringService.listAttachmentsToleratingUploadRace(
                traceColdFetch, message.workspaceId(), trace.id(),
                trace.input(), trace.output(), trace.metadata());

        return Mono.zip(gatherSpanAttachments(trace, spans, message), traceAttachmentsMono)
                // buildFullJson + compress(FULL) 是路由中 CPU/GC 最昂贵的部分（对 trace + 其所有 spans
                // 做 valueToTree，再加上为富化轮做 deepCopy）。上面的 zip 在运行附件查找的
                // R2DBC/附件调度器线程上发出，因此将此序列化跳到 Schedulers.parallel() 上——
                // 镜像 evaluate() 的准备跳跃——以避免在 {{trace}} 路径上加重 DB 调度器的负担。
                .flatMap(tuple -> Mono.fromCallable(() -> {
                    // 在此处构建一次，并向下游传递到 prepareEvaluation（大小估算）和工具缓存预填充，
                    // 因此 {{trace}} 路径只序列化 {trace, spans} 一次。compress() 在修改前会深拷贝
                    // （FULL/MEDIUM），因此此节点保持原始状态，可安全复用。
                    JsonNode fullJson = traceCompressor.buildFullJson(trace, spans);
                    // 同时富化每个 span 和 trace 级别的附件，使评判器可以获取其中任何一个。
                    var compressed = traceCompressor.compress(fullJson, trace, spans, CompressionTier.FULL,
                            tuple.getT1(), tuple.getT2());
                    // compress() 与 id 无关；在此之上添加 id 信封，镜像 ReadTool。
                    ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
                    envelope.put("trace_id", trace.id() != null ? trace.id().toString() : null);
                    envelope.put("tier", compressed.tier().name());
                    envelope.set("data", compressed.payload());
                    return TraceStructure.builder()
                            .envelopeJson(envelope.toString())
                            .fullJson(fullJson)
                            .build();
                }).subscribeOn(Schedulers.parallel()));
    }

    /**
     * 在一次批量的、容忍上传竞态的查找中列出 trace 所有 spans 的附件，并按 span id 分组。
     * 正文引用了附件的 span 会驱动有界重试，直到该附件被持久化并可见（以便在 trace 创建/更新后立即
     * 入队评分时，注入的 {@code {{trace}}} 载荷不会缺少 span 级别的 {@code file_name}）；
     * 当存在持久副本时，每个 span 的临时自动剥离副本会被丢弃。参见
     * {@link AgenticScoringService#listSpanAttachmentsToleratingUploadRace}。
     */
    private Mono<Map<UUID, List<AttachmentInfo>>> gatherSpanAttachments(
            Trace trace, List<Span> spans, TraceToScoreLlmAsJudge message) {
        Set<UUID> spanIds = spans.stream()
                .map(Span::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (spanIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        // 每个 span 在其正文中引用的附件文件名集合。既用于驱动上传竞态重试
        // （期望附件的 span = 非空集合），也用于在分组时保留被引用的自动剥离副本
        // （参见 AgenticScoringService#preferPersistentAttachments）。
        Map<UUID, Set<String>> referencedNamesBySpan = spans.stream()
                .filter(span -> span.id() != null)
                .collect(Collectors.toMap(Span::id,
                        span -> AttachmentUtils.collectAttachmentReferences(
                                JsonUtils.getMapper(), span.input(), span.output(), span.metadata()),
                        (a, b) -> a));
        Set<UUID> spanIdsExpectingAttachment = referencedNamesBySpan.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        Mono<List<AttachmentInfo>> coldBatchedFetch = attachmentService
                .getAttachmentInfoByEntityIds(com.comet.opik.api.attachment.EntityType.SPAN, spanIds)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName()));
        return agenticScoringService.listSpanAttachmentsToleratingUploadRace(
                coldBatchedFetch, message.workspaceId(), trace.id(), spanIdsExpectingAttachment,
                referencedNamesBySpan);
    }

    private Mono<List<FeedbackScoreBatchItem>> evaluate(TraceToScoreLlmAsJudge message, List<Span> spans,
            String traceStructureJson, JsonNode prebuiltFullJson, boolean referencesTrace, Map<String, String> mdc,
            EvaluationRecorder recorder) {
        var trace = message.trace();
        // 每次 trace 评估一个守卫；当规则未设置 maxCostUsd 时为 UNLIMITED。对每次 LLM 调用计费
        // （初始 + 工具轮 + 收尾），并告诉工具循环何时开始收尾。
        var costGuard = BudgetGuard.create(message.llmAsJudgeCode().maxCostUsd(),
                message.llmAsJudgeCode().model().name(), llmProviderFactory);
        // 同步准备是 CPU 密集的（用于大小估算的 JSON 序列化 + 提示词渲染）——
        // 在 Schedulers.parallel() 上调度，这样既不会加重上游发出 spans 获取的 R2DBC 调度器的负担，
        // 也不会在内联路径上钉住 workersScheduler 线程。
        // MDC 在 prepareEvaluation 内通过 try-with-resources 应用；下面的响应式操作符通过 withMdc()
        // 重新应用 MDC，因为它们可能运行在与发出聊天响应的 boundedElastic 工作线程不同的线程上。
        return Mono.fromCallable(
                () -> prepareEvaluation(message, spans, traceStructureJson, prebuiltFullJson, referencesTrace, mdc))
                .subscribeOn(Schedulers.parallel())
                .flatMap(prepared -> {
                    // 在第一轮 LLM 之前，将前置的检索 + 上下文组装（span 获取、大小估算、模式决策）
                    // 记录为准备 span；agentic 标志也会设置父 trace 的模式。
                    recorder.recordPreparation(spans.size(), prepared.estimatedTokens(), prepared.useTools());
                    return scoreTraceReactive(prepared.scoreRequest(), message, recorder, costGuard)
                            .doOnNext(withMdc(mdc, chatResponse -> {
                                if (userFacingLogger.isInfoEnabled()) {
                                    userFacingLogger.info("收到 traceId '{}' 的响应：'{}'",
                                            trace.id(), agenticScoringService.summarizeResponse(chatResponse));
                                }
                            }))
                            .flatMap(initialResponse -> prepared.useTools()
                                    ? handleToolCalls(initialResponse, prepared.scoreRequest(),
                                            prepared.structuredRequest(), message, spans, prepared.fullJson(), mdc,
                                            recorder, costGuard)
                                    : Mono.just(initialResponse));
                })
                .map(chatResponse -> {
                    try (var logContext = wrapWithMdc(mdc)) {
                        if (costGuard.wasBudgetEnforced()) {
                            // 以 wasBudgetEnforced()（循环的预算门控确实缩短了运行）为键，
                            // 而非 shouldWrapUp()（仅仅花费 >= 限制）：此警告声称调查被停止，因此它
                            // 绝不能在内联单次调用路径或仅跨越花费的自然停止上触发。
                            // 与 ToolCallLoop 内标记的 budget_exceeded trace 标签是同一权威信号。
                            userFacingLogger.warn(
                                    "已达到 '{}' USD 的花费预算，traceId '{}'（已花费 '{}'）；"
                                            + "停止调查，并用目前收集到的分数收尾。",
                                    costGuard.limitUsd(), trace.id(), costGuard.spentUsd());
                        }
                        // 在记录日志之前重新映射名称，使规则的日志使用用户配置的分数名称，
                        // 而不是内部的 assertion_N 键。空映射（常规在线评分）是无操作。
                        var parsed = OnlineScoringEngine
                                .toFeedbackScores(chatResponse, message.llmAsJudgeCode().schema())
                                .withUserFacingNames(message.scoreNameMapping());
                        OnlineScoringEngine.logSkippedNullScores(userFacingLogger, parsed, "traceId", trace.id());
                        OnlineScoringEngine.logResponseIssues(userFacingLogger, parsed, "traceId", trace.id());
                        return parsed.scores().stream()
                                .map(item -> (FeedbackScoreBatchItem) item.toBuilder()
                                        .categoryName(message.categoryName())
                                        .id(trace.id())
                                        .projectId(trace.projectId())
                                        .projectName(trace.projectName())
                                        .build())
                                .toList();
                    }
                });
    }

    private PreparedEvaluation prepareEvaluation(TraceToScoreLlmAsJudge message, List<Span> spans,
            String traceStructureJson, JsonNode prebuiltFullJson, boolean referencesTrace, Map<String, String> mdc) {
        var trace = message.trace();
        // 规则 + trace 的日志标签；覆盖此同步准备中的所有 userFacingLogger 调用。
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("正在评估 traceId '{}'，由规则 '{}' 采样", trace.id(), message.ruleName());

            // spans 已在 score() 中响应式获取并传递过来，因此 handleToolCalls 可以在无需二次查询的情况下
            // 填充 read 工具缓存。estimateTraceContextTokens 作用于 {trace, spans} 复合体——
            // 带有巨大 spans 的小 trace 仍会触发基于大小的 agentic 工具分支。
            //
            // 完整 JSON 只序列化一次：在 {{trace}} 路径上 buildTraceStructure 已经构建了它并在此传入
            // （prebuiltFullJson）；否则我们现在为大小估算构建它。如果 useTools 解析为 true，
            // handleToolCalls 会复用同一个 JsonNode 来预填充缓存而不是重建——在每次大 trace 运行上
            // 节省一次完整的 trace+spans 序列化（路由中 CPU/GC 最昂贵的部分）。
            String modelName = message.llmAsJudgeCode().model().name();
            JsonNode fullJson = prebuiltFullJson != null
                    ? prebuiltFullJson
                    : traceCompressor.buildFullJson(trace, spans);
            int estimatedContextTokens = agenticScoringService.estimateTokensFromJson(
                    fullJson, onlineScoringConfig.getAgenticToolsCharsPerToken());
            boolean useTools = shouldUseAgenticTools(message, estimatedContextTokens, modelName,
                    referencesTrace);

            ChatRequest scoreRequest;
            ChatRequest structuredRequest;
            try {
                if (useTools) {
                    // 工具路径：截断变量替换，使巨大的 trace 输入/输出 JSON 不会预加载上下文。
                    // 代理有 read/jq 工具可按需向下钻取。钻取提示将模型指向 MEDIUM 层
                    // （路径截断、结构完整）以及用于路径定位查找的 jq——绝不指向 tier=FULL，
                    // 后者会在巨大 trace 上撑爆上下文。ReadTool 无论如何都会静默降级 tier=FULL，
                    // 但这避免了一次浪费的轮次。
                    String drillDownHint = ("call read(type=trace, id=%s, tier=MEDIUM) for full structure"
                            + " with per-string truncation hints, or jq(type=trace, id=%s,"
                            + " expression='<path>') for a specific section")
                            .formatted(trace.id(), trace.id());
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace, new InstructionStrategy(),
                            message.promptType(), onlineScoringConfig.getMaxPromptFieldChars(), drillDownHint, spans,
                            traceStructureJson);
                    // 工具循环后的收尾必须使用提供方原生的结构化输出策略
                    // （例如 OpenAI 上的 response_format=json_schema）。InstructionStrategy 是软提示，
                    // 而 Anthropic 尤其常在收尾轮返回对话式散文（"Now let me check..."），
                    // 这随后会在 toFeedbackScores 中导致 JSON 解析失败并产生零个分数。
                    structuredRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), onlineScoringConfig.getMaxPromptFieldChars(), drillDownHint, spans,
                            traceStructureJson);
                } else if (referencesTrace) {
                    // 在非工具调用提供方上对 {{trace}} 规则的内联回退：结构是为（不可用的）工具路径
                    // 以 FULL 层构建的，而这里没有 read/jq 工具可向下钻取，因此截断替换以限制上下文窗口——
                    // 否则大型 trace 会以不截断的方式注入，并可能溢出模型的上下文。没有向下钻取提示：
                    // 模型无法对其采取行动，因此超出上限的值只是被截断。
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), onlineScoringConfig.getMaxPromptFieldChars(), INLINE_TRUNCATION_HINT,
                            spans,
                            traceStructureJson);
                    structuredRequest = scoreRequest;
                } else {
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), spans, traceStructureJson);
                    structuredRequest = scoreRequest;
                }
            } catch (Exception exception) {
                OnlineScoringEngine.logPreparingLlmRequestError(userFacingLogger, log, "traceId",
                        trace.id(), exception);
                throw exception;
            }

            if (useTools) {
                // 仅在第一次调用时 REQUIRED：强制在模型能够给出回答之前至少调用一次工具。
                // 使用 tool_choice=AUTO 的 OpenAI 评判器会一致地跳过工具循环并从可见上下文中回答，
                // 即使在系统提示中有明确的 "you MUST call tools first" 指引——经验上的不对称性参见
                // SupportedJudgeProvider。handleToolCalls 中的后续轮切换到 AUTO，使模型可以决定何时
                // 有足够信息停止调查；统一的 REQUIRED 会永远循环，因为收尾轮也会被迫调用工具。
                scoreRequest = agenticScoringService.addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
            }

            // summarizeRequest 很廉价（由于 chars-count 字段已被移除，不再有逐消息 toString 流式处理）。
            // 使用 INFO 级别，使查看规则 UI 日志的运维人员能在 "Evaluating" 和 "Received response" 之间
            // 看到匹配的 "Sending" 行。
            userFacingLogger.info("将 traceId '{}' 发送到 LLM：{}",
                    trace.id(), agenticScoringService.summarizeRequest(scoreRequest,
                            message.llmAsJudgeCode().model().name(), useTools));

            // fullJson 仅在下游的 agentic 工具路径上有用（handleToolCalls 将其预填充到工具缓存中）。
            // 在内联路径上我们丢弃它——我们已经为计算大小估算付出了构建成本；跳过携带避免了在链上
            // 为一个不消费它的评估持有一个可能达数 MB 的 JsonNode。
            return new PreparedEvaluation(scoreRequest, structuredRequest, useTools,
                    useTools ? fullJson : null, estimatedContextTokens);
        }
    }

    /**
     * 将同步的 {@code ChatLanguageModel.chat} 调用包装进 Mono，并在 {@link Schedulers#boundedElastic()}
     * 上调度，使阻塞的 Jersey 客户端 I/O 不会钉住每个流的 worker 调度器线程（OPIK-6308）。
     */
    private Mono<ChatResponse> scoreTraceReactive(ChatRequest request, TraceToScoreLlmAsJudge message,
            EvaluationRecorder recorder, BudgetGuard costGuard) {
        var call = Mono.fromCallable(() -> aiProxyService.scoreTrace(
                request, message.llmAsJudgeCode().model(), message.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
        return costGuard.track(recorder.recordLlmCall(request, call));
    }

    /**
     * 关于是否在运行 LLM 调用之前获取 trace 的 spans 的路由决策。
     * 在两种情况下需要 spans：
     * <ul>
     *   <li>agentic 工具路径可用（提供方支持工具，且 experimentId 分支开启或基于大小的开关启用）——
     *       spans 预填充 read 工具缓存，使循环内的 {@code get_trace_spans} 调用不必重新获取。
     *   <li>内联提示词模板引用了 {@code {{spans}}}——两种选择加入形态
     *       （哨兵值变量 AND 隐式模板引用）参见 {@link OnlineScoringEngine#templateReferencesSpans}。
     *       <strong>由 {@code isAgenticToolsEnabled} 门控</strong>：两条路径都在同一标志下发布。
     * </ul>
     *
     * <p><strong>开关语义——重要且不对称：</strong>此方法门控 {@code spanService.getByTraceIds} 的 I/O。
     * 它<em>不</em>门控替换本身——{@link OnlineScoringEngine#injectSpansIntoReplacements} 在
     * {@code prepareLlmRequest} 内无条件运行。当开关关闭而规则仍携带哨兵映射的 {@code spans} 变量
     * （例如由开关翻转前的旧 FE 保存）时，{@code {{spans}}} 会通过传入的空 spans 列表渲染为空 JSON 数组
     * {@code []}。我们<em>不</em>门控替换，因为这样做会让哨兵值 {@code "spans"} 通过 {@code toReplacements}
     * 的字面值回退泄漏，并在提示词中渲染裸单词 {@code spans}——比 {@code []} 更糟糕的 UX。开关关闭时的净行为：
     * <ul>
     *   <li>带哨兵映射的现有规则 → {@code Spans: []}
     *   <li>通过 FE 创建的新规则 → FE 跳过自动填充，用户像任何其他变量一样映射 {@code spans}，
     *       BE 渲染他们选择的任何路径。
     *   <li>Experiment-id（测试套件断言）路径 → agentic 工具无论如何都会触发，因此 spans 仍被获取，
     *       且 {@code {{spans}}} 替换为实际 JSON。
     * </ul>
     */
    // 包私有，供单元测试使用。
    boolean shouldFetchSpans(TraceToScoreLlmAsJudge message) {
        String modelName = message.llmAsJudgeCode().model().name();
        boolean agenticToolsPathPossible = agenticScoringService.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName))
                && (LlmAsJudgeToolsMode.shouldUseTools(message)
                        || serviceTogglesConfig.isAgenticToolsEnabled());
        boolean templateNeedsSpans = serviceTogglesConfig.isAgenticToolsEnabled()
                && (OnlineScoringEngine.templateReferencesSpans(
                        message.llmAsJudgeCode().messages(),
                        message.llmAsJudgeCode().variables(),
                        message.promptType())
                        || OnlineScoringEngine.templateReferencesTraceStructure(
                                message.llmAsJudgeCode().messages(),
                                message.llmAsJudgeCode().variables(),
                                message.promptType()));
        return agenticToolsPathPossible || templateNeedsSpans;
    }

    /**
     * 关于是否附加工具规格 + 运行工具调用循环的路由决策。当以下任一项成立时工具会触发：
     * (a) experimentId 驱动的分支适用（测试套件断言），(b) 基于大小的分支适用（开关开启，上下文高于阈值），
     * 或 (c) 提示词引用了 {@code {{trace}}} 骨架变量（开关开启）——并且提供方支持工具调用。
     * 没有提供方检查的话，通过 {@code test_suite_model} 元数据选择的非工具调用模型（Ollama / Custom / OpikFree）
     * 会在请求携带 {@code toolSpecifications} 时于 LangChain4j 聊天调用内崩溃。
     *
     * <p>当某条路径需要工具但提供方无法处理它们时，我们回退到内联路径并发出面向用户的警告——
     * 大声暴露配置错误比旧代码产生的静默崩溃更好。
     *
     * <p>副作用：每当决策并非平凡时发出面向用户的诊断日志，使运维人员能够将路由与 trace 关联起来。
     */
    // 包私有，供单元测试使用。
    boolean shouldUseAgenticTools(TraceToScoreLlmAsJudge message, int estimatedContextTokens, String modelName,
            boolean referencesTrace) {
        boolean experimentIdPath = LlmAsJudgeToolsMode.shouldUseTools(message);
        boolean providerSupportsTools = agenticScoringService.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName));
        boolean overSizeThreshold = serviceTogglesConfig.isAgenticToolsEnabled()
                && estimatedContextTokens >= onlineScoringConfig.getAgenticToolsThresholdTokens();
        // {{trace}} 变量会无视上下文大小强制走 agentic 工具路径：提示词携带 trace 骨架
        // （id + 附件 file_name），评判器使用 get_attachment / read 向下钻取。
        // 与大小路径由同一开关门控。
        boolean traceVariablePath = serviceTogglesConfig.isAgenticToolsEnabled() && referencesTrace;
        boolean wantsTools = experimentIdPath || overSizeThreshold || traceVariablePath;
        boolean useTools = wantsTools && providerSupportsTools;

        if (experimentIdPath && !providerSupportsTools) {
            userFacingLogger.warn(
                    "traceId '{}' 的测试套件断言选择了不支持工具调用的模型 '{}'；"
                            + "回退到内联路径——依赖工具驱动的 span 检查的断言对此模型无效。"
                            + "请为规则选择一个支持工具调用的提供方"
                            + "（OpenAI / Anthropic / Gemini / OpenRouter / Vertex / Bedrock）。",
                    message.trace().id(), modelName);
        } else if (!experimentIdPath && overSizeThreshold && !providerSupportsTools) {
            userFacingLogger.warn(
                    "追踪上下文超过 '{}' 个 token，但模型 '{}' 的提供方不支持工具调用；"
                            + "回退到内联路径——可能溢出上下文窗口。",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), modelName);
        } else if (!experimentIdPath && overSizeThreshold && useTools) {
            userFacingLogger.info(
                    "追踪上下文超过 '{}' 个 token；为 traceId '{}' 切换到 agentic 工具模式",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), message.trace().id());
        } else if (!experimentIdPath && !overSizeThreshold && traceVariablePath && !providerSupportsTools) {
            // 向用户呈现：他们的提示词引用了 {{trace}}（因此他们期望对 trace 骨架 / 附件进行工具驱动的
            // 检查），但所选模型的提供方无法调用工具——这是一个可操作的配置错误，
            // 不同于仅留在内部日志上的纯内部路由决策。
            userFacingLogger.warn(
                    "追踪 '{}' 的规则引用了 {{trace}}，但模型 '{}' 的提供方不支持工具调用；"
                            + "回退到内联路径——评判器无法检查 trace 骨架或加载附件。"
                            + "请选择一个支持工具调用的提供方"
                            + "（OpenAI / Anthropic / Gemini / OpenRouter / Vertex / Bedrock）。",
                    message.trace().id(), modelName);
        } else if (!experimentIdPath && !overSizeThreshold && traceVariablePath && useTools) {
            log.debug("追踪 '{}' 的规则引用了 {{trace}}；切换到 agentic 工具模式",
                    message.trace().id());
        }

        recordRoutingDecision(message, useTools, experimentIdPath, overSizeThreshold, traceVariablePath);
        return useTools;
    }

    private void recordRoutingDecision(TraceToScoreLlmAsJudge message, boolean useTools,
            boolean experimentIdPath, boolean overSizeThreshold, boolean traceVariablePath) {
        String path = useTools ? "agentic" : "inline";
        String trigger = "none";
        if (useTools) {
            if (experimentIdPath) {
                trigger = "experiment_id";
            } else if (overSizeThreshold) {
                trigger = "size_threshold";
            } else if (traceVariablePath) {
                trigger = "trace_variable";
            } else {
                trigger = "attachments";
            }
        }
        String wsId = message.workspaceId();
        String wsName = StringUtils.defaultIfBlank(message.workspaceName(), wsId);
        routingDecisions.add(1, Attributes.of(
                PATH_KEY, path,
                TRIGGER_KEY, trigger,
                WORKSPACE_ID_KEY, wsId,
                WORKSPACE_NAME_KEY, wsName));
    }

    // 包私有，供单元测试使用。
    Mono<ChatResponse> handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceToScoreLlmAsJudge message, List<Span> spans,
            JsonNode fullJson, Map<String, String> mdc, EvaluationRecorder recorder, BudgetGuard costGuard) {
        var trace = message.trace();
        // 共享的循环编排位于基础评分器中；这里我们只提供 trace 特有的上下文填充。
        // 缓存用 prepareEvaluation 已为大小估算构建的 JSON 预填充（在大 trace 上节省一次重建）；
        // 当调用方未提供时回退到重建（例如直接调用 handleToolCalls 的单元测试）。
        return agenticScoringService.runToolCallLoop(chatResponse, toolRequest, structuredRequest,
                () -> {
                    var ctx = TraceToolContext.forActiveTrace(trace, spans, message.workspaceId(),
                            message.userName(), onlineScoringConfig.getAgenticToolsMaxInjectedBytes());
                    ctx.cache(new EntityRef(EntityType.TRACE, trace.id().toString()),
                            fullJson != null ? fullJson : traceCompressor.buildFullJson(trace, spans));
                    return ctx;
                },
                request -> scoreTraceReactive(request, message, recorder, costGuard),
                costGuard,
                () -> message.llmAsJudgeCode().model().name(), trace.id().toString(), userFacingLogger, mdc,
                recorder);
    }

    /**
     * 从 {@link #prepareEvaluation} 传递到 {@link #evaluate}。{@code fullJson} 是预先构建的
     * {@code {trace, spans}} JSON，既用于大小估算，也（当 {@code useTools} 为 true 时）用于预填充
     * 工具上下文的缓存——在内联路径上为 null，因此对于不会消费它的评估，我们不会持有一个多 MB 的 JsonNode。
     */
    private record PreparedEvaluation(ChatRequest scoreRequest, ChatRequest structuredRequest, boolean useTools,
            JsonNode fullJson, int estimatedTokens) {
    }

    /**
     * 在 {@code {{trace}}} 路径上从 {@link #buildTraceStructure} 传递到 {@link #evaluate}。
     * {@code envelopeJson} 是注入提示词的渲染结构；{@code fullJson} 是与之一起构建的
     * {@code {trace, spans}} 复合体，向下传递以便 {@link #prepareEvaluation} 复用它做大小估算 /
     * 工具缓存预填充，而不是再次序列化 trace。
     */
    @Builder(toBuilder = true)
    private record TraceStructure(@NonNull String envelopeJson, @NonNull JsonNode fullJson) {
    }

}
