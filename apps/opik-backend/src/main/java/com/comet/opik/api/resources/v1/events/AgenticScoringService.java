package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * 封装了追踪级别、跨度级别和线程级别的 LLM 裁判评分器所共用的 agentic 工具能力：运行
 * {@code read}/{@code jq}/{@code search}/{@code get_attachment} 工具调用循环，在容忍上传竞态的前提下
 * 发现实体的附件，以及一小批与工具循环相邻的请求/响应辅助方法（工具规格附加、提供商能力检查、
 * 大小估算、脱敏后的请求/响应摘要）。
 *
 * <p>此前该逻辑位于 {@code OnlineScoringBaseScorer}，并通过继承传播给子类。将其抽入一个注入式服务意味着
 * 在线评分器是该能力的使用者而非拥有者——不需要 agentic 工具的普通评分器（例如 Python 指标评分器）
 * 不再继承它，而追踪/跨度/线程评分器共享同一个实现，而非线程评分器手动编写一份自己的循环副本。
 */
@ImplementedBy(AgenticScoringServiceImpl.class)
public interface AgenticScoringService {

    /**
     * 面向追踪级别、跨度级别和线程级别评分器的共享 agentic 工具循环编排。
     * 当初次响应不携带任何工具调用时原样返回；否则推迟到订阅时，并以 {@code ToolChoice.AUTO}
     * 后续轮次运行 {@link ToolCallLoop#runWithWrapUp}，在传播之前将任何注入媒体失败作为面向用户的日志呈现。
     *
     * <p>与实体相关的部分——构建并预填充 {@link TraceToolContext}（追踪、跨度或线程）以及每条消息的评分调用——
     * 由调用方以 {@code contextSupplier} 和 {@code scoreFn} 的形式提供。supplier 在内部
     * {@code defer} 中调用，因此上下文创建和缓存预填充每次订阅恰好发生一次。
     *
     * @param contextSupplier 构建并预填充工具上下文（在订阅时调用）
     * @param scoreFn         发起单次 LLM 调用（例如 {@code request -> scoreTraceReactive(...)}）
     * @param costGuard       传入 {@link ToolCallLoop} 的每次评估花费守卫，使得循环在预算用尽后停止开启新的轮次；
     *                        当作用域不强制预算时（例如跨度）传入 {@link BudgetGuard#UNLIMITED}
     * @param modelNameSupplier 裁判模型名称，仅在错误呈现路径上读取——惰性求值，使得模型访问器可能在
     *                          不完整消息上抛出异常/NPE 的调用方（例如在线程评估在路由决策解析之前）永远不会
     *                          在（远更常见的）无错误路径上付出代价
     * @param logId           用作工具循环日志关联 id 的追踪/跨度/线程 id
     * @param recorder        传入 {@link ToolCallLoop} 的评估监控记录器，使得每次工具调用都被记录（OPIK-6994）；
     *                        当监控关闭时传入 {@link EvaluationRecorder#NOOP}
     */
    Mono<ChatResponse> runToolCallLoop(ChatResponse initialResponse,
            ChatRequest toolRequest, ChatRequest structuredRequest,
            Supplier<TraceToolContext> contextSupplier,
            Function<ChatRequest, Mono<ChatResponse>> scoreFn,
            BudgetGuard costGuard,
            Supplier<String> modelNameSupplier, String logId,
            Logger userFacingLogger, Map<String, String> mdc,
            EvaluationRecorder recorder);

    /**
     * 在容忍上传竞态的前提下列出实体的附件（有界重试由
     * {@link OnlineScoringConfig#getAttachmentFetchMaxRetries()} /
     * {@link OnlineScoringConfig#getAttachmentFetchRetryDelay()} 配置）。由追踪级别和跨度级别评分器
     * 在构建注入的 {@code {{trace}}} / {@code {{span}}} 结构时共享。
     *
     * <p>上传会以 <em>自动剥离</em> 副本（{@code input-attachment-N-ts.ext}，无 {@code -sdk}）的形式短暂存在，
     * 一旦持久副本（例如 {@code …-sdk.jpg}）落地即被 <strong>删除</strong>。因此，在竞态中途进行的列表可能只包含
     * 即将 404 的临时名称。因此，当 {@code bodyNodes}（实体的输入/输出/元数据）中的任何一个引用了附件时，
     * 冷查询会被以短暂延迟重新订阅若干次，直到出现一个 <em>持久</em>（非自动剥离）附件，并且只要存在持久副本，
     * 临时副本就会被丢弃（从而裁判永远不会拿到一个将 404 的名称）。没有附件引用的实体跳过重试（常见情况）。
     * 如果重试预算耗尽——例如一个 REST 摄入的图像，其唯一副本被自动剥离且从未被替换——则退回到一次尽力而为的
     * 最终读取，而不是丢弃它。
     *
     * <p>真正的查询失败在降级为空列表之前会被记录一次（包含工作区/实体标识符和堆栈跟踪），
     * 因此尽力而为的行为对运维人员仍然可见。良性的重试耗尽路径（从未出现持久副本）以空完成而非错误完成，
     * 因此它<em>不会</em>被记录为失败。
     *
     * @param coldFetch   附件查询——必须是冷的（每次订阅时重新运行查询）
     * @param workspaceId 工作区 id，出于可观测性包含在失败日志中
     * @param entityId    正在列出其附件的追踪/跨度 id，包含在失败日志中
     * @param bodyNodes   实体的内容节点，被扫描以查找附件引用
     */
    Mono<List<AttachmentInfo>> listAttachmentsToleratingUploadRace(
            Mono<List<AttachmentInfo>> coldFetch, String workspaceId, UUID entityId,
            JsonNode... bodyNodes);

    /**
     * 针对 {@code {{trace}}} 结构的批量、容忍上传竞态的跨度附件查询。将追踪的跨度的附件的单次批量列表
     * 按跨度 id 分组（每个跨度优先使用持久副本，从而临时自动剥离名称永远不会被呈现）。对于正文引用了附件的跨度
     * （{@code spanIdsExpectingAttachment}），它会将（冷）批量查询重新订阅若干次，直到<em>每个</em>此类跨度
     * 都有可见的持久附件，从而容忍附件上传竞态；在预算耗尽时退回到尽力而为的分组（从而 REST-/仅后端自动剥离副本
     * 仍会被呈现）。列表失败会被记录一次，并降级为无跨度附件，而不是阻塞评分。
     *
     * <p>每次尝试一个批量查询（而非每个跨度一个），因此它可以扩展到大型追踪。单实体对应方法是
     * {@link #listAttachmentsToleratingUploadRace}。
     *
     * @param coldBatchedFetch           批量查询——必须是冷的（每次订阅时重新运行）
     * @param workspaceId                工作区 id，用于失败日志
     * @param traceId                    追踪 id，用于失败日志
     * @param spanIdsExpectingAttachment 正文引用了附件的跨度 id（驱动重试）
     * @param referencedNamesBySpan      每个跨度在其正文中引用的附件文件名的集合，
     *                                   用于保留被引用的自动剥离副本
     */
    Mono<Map<UUID, List<AttachmentInfo>>> listSpanAttachmentsToleratingUploadRace(
            Mono<List<AttachmentInfo>> coldBatchedFetch, String workspaceId, UUID traceId,
            Set<UUID> spanIdsExpectingAttachment,
            Map<UUID, Set<String>> referencedNamesBySpan);

    /**
     * 给定的提供商是否已知支持工具调用。用于门控 agentic 工具路径：不支持工具的提供商
     * 即使上下文超过大小阈值也会回退到内联路径（这可能会溢出模型的窗口——在这种情况下，
     * 运维人员应为这些工作负载选择不同的模型）。
     */
    boolean supportsToolCalling(LlmProvider provider);

    /**
     * 将注册的 {@link ToolRegistry} 中的工具规格和给定的 {@code toolChoice} 附加到
     * {@code request} 的参数上。工具规格位于 {@link ChatRequestParameters} 内部，因此我们通过
     * {@code overrideWith} 复制现有参数，并在其上叠加工具规格——直接在 {@link ChatRequest} 构建器上
     * 设置 {@code toolSpecifications} 会与参数冲突。{@code toBuilder()}（而非新建构建器 + .messages()）
     * 会保留 ChatRequest 上的任何其他顶层字段（无论现在还是将来），从而防止在初始评分调用与工具调用收尾中
     * 的结构化重新发起之间出现“静默丢弃字段”的回归。
     */
    ChatRequest addToolSpecs(ChatRequest request, ToolChoice toolChoice);

    /**
     * 对预构建的 JSON 载荷进行基于字符的粗略 token 估算。用于判断内联渲染的提示词是否会
     * 面临溢出模型窗口的风险——从而将评分器切换到 read/jq/search agentic 工具路径。
     *
     * <p>{@code charsPerToken} 是运维人员通过
     * {@code onlineScoring.agenticToolsCharsPerToken} 配置的每 token 字符数比率（默认 4 = 自然语言英文）。
     */
    int estimateTokensFromJson(JsonNode fullJson, int charsPerToken);

    /**
     * 与 {@link #estimateTokensFromJson} 相同，但先构建 {@code {trace, spans}} JSON。当调用方
     * 已经手握完整 JSON 时（例如因为它反正要被预填充到工具上下文的缓存中），优先直接使用
     * {@link #estimateTokensFromJson}——避免在大追踪评估中序列化追踪两次。
     */
    int estimateTraceContextTokens(Trace trace, List<Span> spans,
            TraceCompressor traceCompressor, int charsPerToken);

    /**
     * 对内联线程上下文的粗略 token 估算——即
     * {@code OnlineScoringEngine#fromTraceToThreadEnriched} 渲染的形状：追踪正文（用户/助手内容）加上线程的
     * 跨度内容。{@code spanBytes} 由调用方从廉价的 ClickHouse 大小聚合提供，因此这里不会物化任何跨度；
     * 追踪正文在堆内测量。
     *
     * <p>这是内联-vs-agentic 工具路由门控的近似值：它只对主要内容求和——
     * {@code spanBytes}（ClickHouse 字节长度）加上追踪正文（序列化字符长度）——
     * 而不包括 JSON 结构，有意混合这两种单位，因此可能略微低估。这是可以接受的，因为真正小的线程无论
     * 如何都保持内联，而过大的线程会远高于阈值。每 token 字符数比率从配置读取
     * （{@code onlineScoring.agenticToolsCharsPerToken}）。参见 OPIK-7454。
     */
    int estimateThreadContextTokens(List<Trace> traces, long spanBytes);

    /**
     * 流式、堆有界的线程跨度预加载，用于在内联-vs-agentic 工具路由决策中评估大小，
     * 而无需物化无界的线程。消费跨度 {@link Flux} 并累积跨度及其近似的序列化大小；
     * 一旦运行大小超过 {@code maxPreloadBytes}，它会取消上游获取并返回一个
     * {@link ThreadSpanPreload#overflowed()} {@code == true} 且跨度列表为空的预加载。
     *
     * <p>溢出强制走 agentic 工具路径，该路径按需逐个追踪下钻且无需缓冲区；
     * 低于上限时，返回有界跨度列表用于内联/增强路径。参见 OPIK-7454。
     */
    Mono<ThreadSpanPreload> preloadThreadSpansBounded(Flux<Span> spans, long maxPreloadBytes);

    /**
     * 为面向用户的日志构建对发出的 LLM 请求的脱敏单行描述。完整的 {@link ChatRequest} 包含渲染后的提示词、
     * 带有追踪输入/输出的用户消息、请求参数和工具规格——将所有这些内容呈现在存储日志中，会让追踪内容
     * （及其携带的任何 token 或 PII）以明文形式进入日志所供给的任何下游接收端。改为仅形状的摘要。
     */
    String summarizeRequest(ChatRequest request, String modelName, boolean useTools);

    /**
     * 构建对 LLM 响应的脱敏单行描述。完整的 {@link ChatResponse} 携带助手文本和任何工具调用参数，
     * 两者都可能回显模型正在推理的追踪内容——在面向用户的日志中呈现原始响应会让追踪内容
     * （及其携带的任何 token 或 PII）进入日志所供给的任何下游接收端。改为仅形状的摘要。
     */
    String summarizeResponse(ChatResponse response);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AgenticScoringServiceImpl implements AgenticScoringService {

    private final @NonNull @Config("onlineScoring") OnlineScoringConfig onlineScoringConfig;
    private final @NonNull ToolRegistry toolRegistry;

    @Override
    public Mono<ChatResponse> runToolCallLoop(@NonNull ChatResponse initialResponse,
            @NonNull ChatRequest toolRequest, @NonNull ChatRequest structuredRequest,
            @NonNull Supplier<TraceToolContext> contextSupplier,
            @NonNull Function<ChatRequest, Mono<ChatResponse>> scoreFn,
            @NonNull BudgetGuard costGuard,
            @NonNull Supplier<String> modelNameSupplier, String logId,
            @NonNull Logger userFacingLogger, @NonNull Map<String, String> mdc,
            @NonNull EvaluationRecorder recorder) {

        if (!initialResponse.aiMessage().hasToolExecutionRequests()) {
            return Mono.just(initialResponse);
        }

        // 使用 Defer 使上下文构建 + 缓存预填充 + 消息列表分配每次订阅恰好发生一次。
        // 后续轮次使用 ToolChoice.AUTO，以便模型在信息足够时停止；
        // 初始的 REQUIRED 强制由调用方在准备请求时应用。
        return Mono.defer(() -> {
            var ctx = contextSupplier.get();
            var followUpParameters = ChatRequestParameters.builder()
                    .overrideWith(toolRequest.parameters())
                    .toolChoice(ToolChoice.AUTO)
                    .build();
            var messages = new ArrayList<ChatMessage>(toolRequest.messages());
            var budget = new ToolCallLoop.Budget();

            return ToolCallLoop.runWithWrapUp(
                    initialResponse, toolRequest, structuredRequest, followUpParameters, toolRegistry,
                    scoreFn, messages, ctx, budget, costGuard, logId, mdc, recorder)
                    .onErrorResume(error -> surfaceInjectedMediaFailure(error, ctx, modelNameSupplier.get(),
                            userFacingLogger, mdc));
        });
    }

    /**
     * agentic 工具路径的共享错误呈现：当工具调用循环在至少一个附件被作为多模态内容注入后失败时，
     * 最可能的原因是裁判模型拒绝该媒体类型（我们尝试所有类型而非预先门控）。在传播之前，
     * 发出一则清晰、归因于附件的面向用户的消息，从而不支持视觉的模型会产生可理解的错误，
     * 而非原始的提供商堆栈跟踪。没有注入媒体时，失败原样通过。
     */
    // 包私有，供单元测试使用。
    static <T> Mono<T> surfaceInjectedMediaFailure(@NonNull Throwable error,
            @NonNull TraceToolContext ctx, String modelName, @NonNull Logger userFacingLogger,
            @NonNull Map<String, String> mdc) {
        if (ctx.hasInjectedMedia()) {
            String attachments = ctx.getInjectedAttachments().stream()
                    .map(a -> "'%s' (%s)".formatted(a.fileName(), a.category().name().toLowerCase()))
                    .collect(Collectors.joining(", "));
            String detail = Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                    .orElse(error.getMessage());
            try (var logContext = wrapWithMdc(mdc)) {
                userFacingLogger.error(
                        "加载附件 '{}' 后评分失败；裁判模型 '{}' 可能不支持此"
                                + " 附件类型。请使用支持该附件媒体类型的模型。详情：'{}'",
                        attachments, modelName, detail, error);
            }
        }
        return Mono.error(error);
    }

    @Override
    public Mono<List<AttachmentInfo>> listAttachmentsToleratingUploadRace(
            @NonNull Mono<List<AttachmentInfo>> coldFetch, String workspaceId, UUID entityId,
            JsonNode... bodyNodes) {
        // 将失败日志附加到冷获取本身，使其仅在真正的查询错误时触发——而非在
        // 空完成驱动的重试或下方良性的重试耗尽路径上触发。
        Mono<List<AttachmentInfo>> fetch = coldFetch.doOnError(error -> log.warn(
                "列出工作区 '{}'、实体 '{}' 的附件失败；降级为尽力而为"
                        + " 的附件发现（在线评分将在没有它们的情况下继续）",
                workspaceId, entityId, error));

        Set<String> referencedNames = AttachmentUtils.collectAttachmentReferences(JsonUtils.getMapper(), bodyNodes);
        Function<List<AttachmentInfo>, List<AttachmentInfo>> group = attachments -> preferPersistentAttachments(
                attachments, referencedNames);
        if (referencedNames.isEmpty()) {
            return fetch.map(group).onErrorReturn(List.of());
        }
        return resolveWithUploadRaceTolerance(fetch, coldFetch, group,
                AgenticScoringServiceImpl::hasPersistentAttachment, List.of(),
                error -> log.warn(
                        "对工作区 '{}'、实体 '{}' 的尽力而为附件重读失败；"
                                + " 在线评分将在没有附件的情况下继续",
                        workspaceId, entityId, error));
    }

    @Override
    public Mono<Map<UUID, List<AttachmentInfo>>> listSpanAttachmentsToleratingUploadRace(
            @NonNull Mono<List<AttachmentInfo>> coldBatchedFetch, String workspaceId, UUID traceId,
            @NonNull Set<UUID> spanIdsExpectingAttachment,
            @NonNull Map<UUID, Set<String>> referencedNamesBySpan) {
        Mono<List<AttachmentInfo>> logged = coldBatchedFetch.doOnError(error -> log.warn(
                "列出追踪 '{}'（工作区 '{}'）的跨度附件失败；降级为无",
                traceId, workspaceId, error));
        Function<List<AttachmentInfo>, Map<UUID, List<AttachmentInfo>>> group = attachments -> groupBySpanPreferringPersistent(
                attachments, referencedNamesBySpan);
        if (spanIdsExpectingAttachment.isEmpty()) {
            return logged.map(group).onErrorReturn(Map.of());
        }
        return resolveWithUploadRaceTolerance(logged, coldBatchedFetch, group,
                bySpan -> spanIdsExpectingAttachment.stream()
                        .allMatch(id -> hasPersistentAttachment(bySpan.getOrDefault(id, List.of()))),
                Map.of(),
                error -> log.warn(
                        "对追踪 '{}'（工作区 '{}'）的尽力而为跨度附件重读失败；"
                                + " 在线评分将在没有跨度附件的情况下继续",
                        traceId, workspaceId, error));
    }

    /**
     * {@link #listAttachmentsToleratingUploadRace} 和
     * {@link #listSpanAttachmentsToleratingUploadRace} 背后的共享重试/尽力而为管道——两者仅在于分组形状
     * （单个实体的列表 vs. 每个跨度的映射）及其日志标识符上有所不同，因此两者都委托到这里，
     * 以避免重试预算/回退管道在两者之间漂移。
     *
     * <p>通过 {@code repeatWhenEmpty} 重新订阅 {@code fetch}（调用方已记录失败日志的冷获取），
     * 直到 {@code group} 的输出满足 {@code isSatisfied}，最多尝试
     * {@link OnlineScoringConfig#getAttachmentFetchMaxRetries()} 次。如果预算耗尽，
     * 在降级到 {@code emptyResult} 之前，回退到再一次尽力而为的原始 {@code coldFetch} 读取——
     * 通过 {@code onBestEffortFailure} 记录日志。
     *
     * @param fetch              驱动重试循环的、已记录主失败日志的冷获取
     * @param coldFetch          原始冷获取，为尽力而为的最终读取而重新订阅
     * @param group              将原始附件列表映射为调用方的形状
     * @param isSatisfied        {@code group} 的输出是否意味着预期附件已落地
     * @param emptyResult        重试耗尽且最终读取也失败后的降级值
     * @param onBestEffortFailure 使用调用方自己的标识符记录尽力而为重读失败
     */
    private <T> Mono<T> resolveWithUploadRaceTolerance(
            @NonNull Mono<List<AttachmentInfo>> fetch, @NonNull Mono<List<AttachmentInfo>> coldFetch,
            @NonNull Function<List<AttachmentInfo>, T> group, @NonNull Predicate<T> isSatisfied,
            @NonNull T emptyResult, @NonNull Consumer<Throwable> onBestEffortFailure) {
        return fetch
                .map(group)
                .filter(isSatisfied)
                .repeatWhenEmpty(onlineScoringConfig.getAttachmentFetchMaxRetries(),
                        repeats -> repeats.delayElements(
                                onlineScoringConfig.getAttachmentFetchRetryDelay().toJavaDuration()))
                .onErrorResume(error -> Mono.empty())
                // 重试耗尽：尽力而为的最终读取（原始 coldFetch，因此它需要自己的失败日志——
                // 上方主尝试的日志不覆盖这第二次订阅），而不是直接丢弃附件。
                .switchIfEmpty(Mono.defer(() -> coldFetch
                        .map(group)
                        .doOnError(onBestEffortFailure)
                        .onErrorReturn(emptyResult)));
    }

    private static Map<UUID, List<AttachmentInfo>> groupBySpanPreferringPersistent(
            List<AttachmentInfo> attachments, Map<UUID, Set<String>> referencedNamesBySpan) {
        return attachments.stream()
                .filter(a -> a.entityId() != null)
                .collect(Collectors.groupingBy(AttachmentInfo::entityId)).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> preferPersistentAttachments(e.getValue(),
                        referencedNamesBySpan.getOrDefault(e.getKey(), Set.of()))));
    }

    /**
     * 保留每个持久附件以及仍在实体正文中被引用的任何自动剥离附件，
     * 只丢弃<em>孤立的</em>自动剥离副本（不再被引用）。被取代的临时副本——即被持久副本替换的那个——
     * 会被丢弃，因为正文引用现在指向持久名称，从而避免呈现临时副本（其一旦被清理就会 404）。
     *
     * <p>这是基于正文引用的逐附件决策，而非实体范围的“任何持久 ⇒ 丢弃所有自动剥离”门控：
     * 后者会在同一实体上共存<em>不相关</em>的持久附件时，丢弃合法的仅临时附件（例如 REST 摄入的图像）。
     * 文件名无法将临时副本与其持久孪生配对（后端临时名称与 SDK 的 {@code -sdk} 名称不共享任何键），
     * 因此正文引用是可靠的信号。
     *
     * @param referencedNames 实体正文中引用的附件文件名（参见
     *                        {@link AttachmentUtils#collectAttachmentReferences})
     */
    private static List<AttachmentInfo> preferPersistentAttachments(List<AttachmentInfo> attachments,
            Set<String> referencedNames) {
        // 当没有持久副本共存时，每个自动剥离副本都是真正的附件（无 SDK 替换的
        // 后端-/REST 摄入图像）——全部保留。
        if (!hasPersistentAttachment(attachments)) {
            return attachments;
        }
        // 持久副本共存：保留每个持久附件以及仍在正文中被引用的任何自动剥离副本，
        // 只丢弃孤立的自动剥离副本（不再被引用）。
        return attachments.stream()
                .filter(attachment -> !AttachmentUtils.isAutoStrippedAttachment(attachment.fileName())
                        || referencedNames.contains(attachment.fileName()))
                .collect(Collectors.toList());
    }

    private static boolean hasPersistentAttachment(List<AttachmentInfo> attachments) {
        return attachments.stream().anyMatch(a -> !AttachmentUtils.isAutoStrippedAttachment(a.fileName()));
    }

    @Override
    public boolean supportsToolCalling(@NonNull LlmProvider provider) {
        return switch (provider) {
            case OPEN_AI, ANTHROPIC, GEMINI, OPEN_ROUTER, VERTEX_AI, BEDROCK -> true;
            case OLLAMA, CUSTOM_LLM, OPIK_FREE -> false;
        };
    }

    @Override
    public ChatRequest addToolSpecs(@NonNull ChatRequest request, @NonNull ToolChoice toolChoice) {
        var parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(toolRegistry.specs())
                .toolChoice(toolChoice)
                .build();
        return request.toBuilder()
                .parameters(parameters)
                .build();
    }

    @Override
    public int estimateTokensFromJson(@NonNull JsonNode fullJson, int charsPerToken) {
        Preconditions.checkArgument(charsPerToken >= 1, "charsPerToken must be >= 1, got '%s'", charsPerToken);
        // 通过计数写入器流式处理节点，而不是物化其完整 JSON 字符串。
        return (int) (JsonUtils.getSerializedLength(fullJson) / charsPerToken);
    }

    @Override
    public int estimateTraceContextTokens(@NonNull Trace trace, @NonNull List<Span> spans,
            @NonNull TraceCompressor traceCompressor, int charsPerToken) {
        return estimateTokensFromJson(traceCompressor.buildFullJson(trace, spans), charsPerToken);
    }

    @Override
    public int estimateThreadContextTokens(@NonNull List<Trace> traces, long spanBytes) {
        // 内联提示词大小 ≈ 追踪正文（堆内测量）+ 跨度内容（spanBytes，来自廉价的
        // 大小聚合）。进行钳制，使非常大的线程不会溢出 int 估算值。
        var contextBytes = spanBytes + traceContentBytes(traces);
        return (int) Math.min(Integer.MAX_VALUE, contextBytes / onlineScoringConfig.getAgenticToolsCharsPerToken());
    }

    /**
     * 内联线程上下文所携带的追踪正文（用户/助手 {@code content}）的近似序列化大小，
     * 在已在堆内的追踪上测量，而无需物化它们的 JSON。
     */
    private long traceContentBytes(List<Trace> traces) {
        return traces.stream()
                .mapToLong(trace -> JsonUtils.getSerializedLength(trace.input())
                        + JsonUtils.getSerializedLength(trace.output()))
                .sum();
    }

    @Override
    public Mono<ThreadSpanPreload> preloadThreadSpansBounded(@NonNull Flux<Span> spans, long maxPreloadBytes) {
        Preconditions.checkArgument(maxPreloadBytes >= 1, "maxPreloadBytes must be >= 1, got '%s'", maxPreloadBytes);
        // 每次订阅使用全新的累加器（Mono.defer），因此重试/重新订阅永远不会重用缓冲状态。
        // 一旦越过上限，takeUntil 会立即取消上游获取，从而整个线程永远不会被物化；
        // 累加结果在流完成后发出。
        return Mono.defer(() -> {
            var accumulator = new BoundedSpanAccumulator(maxPreloadBytes);
            return spans
                    .takeUntil(accumulator::addAndCheckOverflow)
                    .then(Mono.fromSupplier(accumulator::toPreload));
        });
    }

    @Override
    public String summarizeRequest(@NonNull ChatRequest request, @NonNull String modelName, boolean useTools) {
        // 有意不计算总字符数：对多 MB 渲染提示词调用 m.toString() 会仅仅为了测量长度而
        // 分配完整字符串，这会给每次评估增加约 2 倍提示词大小的堆抖动。消息数量 + 工具数量
        // 足以识别正在发生的事情；需要字节级细节的运维人员可以查看规则的 debug 日志。
        int messageCount = request.messages() == null ? 0 : request.messages().size();
        int toolSpecCount = request.toolSpecifications() == null ? 0 : request.toolSpecifications().size();
        return String.format("model='%s', messages=%d, tools=%d, toolsEnabled=%s",
                modelName, messageCount, toolSpecCount, useTools);
    }

    @Override
    public String summarizeResponse(@NonNull ChatResponse response) {
        var ai = response.aiMessage();
        int textLength = ai.text() == null ? 0 : ai.text().length();
        int toolCallCount = ai.toolExecutionRequests() == null ? 0 : ai.toolExecutionRequests().size();
        var finishReason = response.metadata() == null ? null : response.metadata().finishReason();
        return String.format("textChars=%d, toolCalls=%d, finishReason=%s",
                textLength, toolCallCount, finishReason);
    }
}
