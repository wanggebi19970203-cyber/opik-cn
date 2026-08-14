package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.domain.evaluators.TraceFilterEvaluationService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.LogContextAware;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.eventbus.Subscribe;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * 此服务监听追踪创建的服务器内存事件（通过 EventBus）。事件发生时，它会获取追踪所属项目的
 * 自动化规则，并对追踪批次进行采样以进行相应评分。追踪和代码（可以是 LLM 裁判、Python 代码或
 * 我们新增的集成）被入队到专用于该评估器类型的 Redis 流中。
 */
@EagerSingleton
@Slf4j
public class OnlineScoringSampler {

    private static final String ONLINE_SCORING_NAMESPACE = "online_scoring";
    private static final AttributeKey<String> WORKSPACE_ID_KEY = AttributeKey.stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME_KEY = AttributeKey.stringKey("workspace_name");
    private static final AttributeKey<String> EVALUATOR_TYPE_KEY = AttributeKey.stringKey("evaluator_type");
    private static final AttributeKey<String> DECISION_KEY = AttributeKey.stringKey("decision");
    // 采样决策值（摄入与评分之间的每工作区漏斗）：
    private static final String DECISION_SAMPLED = "sampled"; // 通过所有检查 -> 入队以进行评分
    private static final String DECISION_SKIPPED_DISABLED = "skipped_disabled";
    private static final String DECISION_SKIPPED_FILTER = "skipped_filter";
    private static final String DECISION_SKIPPED_SAMPLING = "skipped_sampling";

    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final TraceFilterEvaluationService filterEvaluationService;
    private final TraceService traceService;
    private final ProjectService projectService;
    private final SecureRandom secureRandom;
    private final Logger userFacingLogger;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final OnlineScorePublisher onlineScorePublisher;
    private final LongCounter samplingDecisions;

    @Inject
    public OnlineScoringSampler(@NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull AutomationRuleEvaluatorService ruleEvaluatorService,
            @NonNull TraceFilterEvaluationService filterEvaluationService,
            @NonNull OnlineScorePublisher onlineScorePublisher,
            @NonNull TraceService traceService,
            @NonNull ProjectService projectService) throws NoSuchAlgorithmException {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.filterEvaluationService = filterEvaluationService;
        this.onlineScorePublisher = onlineScorePublisher;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.traceService = traceService;
        this.projectService = projectService;
        secureRandom = SecureRandom.getInstanceStrong();
        userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringSampler.class);

        Meter meter = GlobalOpenTelemetry.getMeter(ONLINE_SCORING_NAMESPACE);
        this.samplingDecisions = meter.counterBuilder("online_scoring_sampler_decisions_total")
                .setDescription("Online-scoring sampling decisions, by workspace, evaluator type and outcome "
                        + "(sampled / skipped_disabled / skipped_filter / skipped_sampling)")
                .build();
    }

    private void recordDecision(String workspaceId, String workspaceName, AutomationRuleEvaluator<?, ?> evaluator,
            String decision, long count) {
        // workspaceName 在追踪事件发布时从 RequestContext.WORKSPACE_NAME 解析，并随
        // 消息/事件携带；缺失时回退到 id，使标签始终被填充。
        samplingDecisions.add(count, Attributes.of(
                WORKSPACE_ID_KEY, workspaceId,
                WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, workspaceId),
                EVALUATOR_TYPE_KEY, evaluator.getType().name(),
                DECISION_KEY, decision));
    }

    /**
     * 记录一个跳过决策并为其发出面向用户的日志行。由 {@link #shouldSampleTrace} 的禁用、
     * 过滤器不匹配和采样跳过分支共享，因此新的跳过原因只需一个调用点而非三个。始终返回 {@code false}，
     * 使调用方可以 {@code return skip(...)}。
     */
    private boolean skip(String workspaceId, String workspaceName, AutomationRuleEvaluator<?, ?> evaluator, Trace trace,
            String decision, String message, Object... args) {
        recordDecision(workspaceId, workspaceName, evaluator, decision, 1);
        // 出于日志目的，设置 workspaceId 很重要
        try (var logContext = createTraceLoggingContext(workspaceId, evaluator, trace)) {
            userFacingLogger.info(message, args);
        }
        return false;
    }

    /**
     * 监听追踪批次，检查是否存在用于评分的自动化规则。它对追踪批次进行采样，
     * 并将样本入队到 Redis Stream。
     *
     * @param tracesBatch 一个带有 workspaceId 和 userName 的追踪批次
     */
    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        // 过滤掉不完整的追踪（无 end_time），以避免对不完整数据进行评分。
        // SDK 可能先发送一个 "start" 事件（有输入但无输出/end_time），随后发送一个
        // "complete" 事件（有输出和 end_time）。只对完整追踪进行评分。
        var completeTraces = tracesBatch.traces().stream()
                .filter(trace -> trace.endTime() != null)
                .toList();

        log.info("收到 TracesCreated，完整 '{}'，总计 '{}'，工作区 '{}'",
                completeTraces.size(), tracesBatch.traces().size(), tracesBatch.workspaceId());

        sampleAndScore(completeTraces, tracesBatch.workspaceId(), tracesBatch.userName(),
                tracesBatch.workspaceName());
    }

    /**
     * 监听包含 end_time 被设置的追踪更新。这处理了 SDK 在函数开始时发送 POST（创建）、
     * 在函数结束时发送 PATCH（更新）的情况（例如手动 trace.end() API）。没有这一点，
     * 通过 PATCH 完成的追踪将永远不会被评分，因为 onTracesCreated 只能看到初始的不完整追踪。
     */
    @Subscribe
    public void onTracesUpdated(TracesUpdated event) {
        if (event.traceUpdate().endTime() == null) {
            log.debug("TracesUpdated 事件没有 endTime -> 追踪不完整，不会评分。");
            return;
        }

        log.info("收到带有 end_time 的 TracesUpdated，traceIds '{}'，工作区 '{}'",
                event.traceIds().size(), event.workspaceId());

        // 注意：在多节点 ClickHouse 集群中存在潜在的竞态条件——写入可能已落在某个副本上，
        // 而此读取命中了尚未复制的另一个副本。实际上，doOnSuccess 在 INSERT 完成后触发，
        // 且读取使用 FINAL，因此这种情况不太可能发生。如果成为问题，可考虑在事件中携带完整的 Trace 对象。
        var traces = traceService.getByIds(new ArrayList<>(event.traceIds()))
                .filter(trace -> trace.endTime() != null)
                .collectList()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .block();

        sampleAndScore(traces, event.workspaceId(), event.userName(), event.workspaceName());
    }

    private void sampleAndScore(List<Trace> traces, String workspaceId, String userName, String workspaceName) {
        if (CollectionUtils.isEmpty(traces)) {
            log.info("工作区 '{}' 没有要评分的追踪", workspaceId);
            return;
        }

        // TraceDAO.findByIds（由 onTracesUpdated 路径使用）填充 projectId 但不填充
        // projectName——ClickHouse 的 traces 表不携带名称。在下游，
        // FeedbackScoreService.processScoreBatch 按 projectName 分组并从中解析 projectId，
        // 因此那里的 null 名称会导致每条评分落入 "Default Project"。
        // 在发布评分事件之前，将名称重新盖回，每个项目从 MySQL 解析一次。
        traces = stampMissingProjectNames(traces, workspaceId);

        var tracesByProject = traces.stream().collect(Collectors.groupingBy(Trace::projectId));

        var countMap = tracesByProject.entrySet().stream()
                .collect(Collectors.toMap(entry -> "projectId: " + entry.getKey(),
                        entry -> entry.getValue().size()));

        log.info("对追踪评分，数量 '{}'，工作区 '{}'，项目 '{}'", traces.size(), workspaceId, countMap);

        // 按项目获取自动化规则
        tracesByProject.forEach((projectId, projectTraces) -> {
            // SDK 和实验追踪可被触发器作用域匹配的评估器评分。
            // 我们有意不从 SDK 或实验追踪中读取 selected_rule_ids。
            // 其他非 SDK 追踪（playground、优化）仅在携带 selected_rule_ids 元数据
            // （用户在 playground 中的显式选择）时才会被评分。
            var scorableTraces = new ArrayList<Trace>();
            var selectedRuleIdsByTrace = new HashMap<UUID, Set<UUID>>();
            for (var trace : projectTraces) {
                if (Source.isLoggingSource(trace.source()) || trace.source() == Source.EXPERIMENT) {
                    scorableTraces.add(trace);
                } else {
                    var ruleIds = extractSelectedRuleIds(trace);
                    if (!ruleIds.isEmpty()) {
                        scorableTraces.add(trace);
                        selectedRuleIdsByTrace.put(trace.id(), ruleIds);
                    }
                }
            }
            if (scorableTraces.isEmpty()) {
                log.info(
                        "没有可评分的追踪：来源不是 SDK 且没有 selected_rule_ids，projectId '{}'，workspaceId '{}'",
                        projectId, workspaceId);
                return;
            }

            log.info("获取评估器，追踪 '{}'，项目 '{}'，工作区 '{}'",
                    scorableTraces.size(), projectId, workspaceId);

            List<? extends AutomationRuleEvaluator<?, ?>> evaluators = ruleEvaluatorService.findAll(
                    projectId, workspaceId);

            //在多线程使用 MDC 时，必须确保上下文被传播。因此，必须使用 wrapWithMdc 方法。
            evaluators.parallelStream().forEach(evaluator -> {
                // 为此规则采样追踪。
                // 如果有任何追踪携带显式的规则选择，则将评估器过滤到该集合。
                // 如果没有找到选择，则使用所有评估器（默认行为，用于向后兼容）。
                var samples = scorableTraces.stream()
                        .filter(trace -> matchesTriggerScope(evaluator, trace))
                        .filter(trace -> isEvaluatorSelectedForTrace(evaluator, trace, selectedRuleIdsByTrace))
                        .filter(trace -> shouldSampleTrace(evaluator, workspaceId, workspaceName, trace));
                switch (evaluator.getType()) {
                    case LLM_AS_JUDGE -> {
                        var messages = samples
                                .map(trace -> toLlmAsJudgeMessage(workspaceId, userName, workspaceName,
                                        (AutomationRuleEvaluatorLlmAsJudge) evaluator, trace))
                                .toList();
                        logSampledTrace(evaluator, messages, scorableTraces.size());
                        if (!messages.isEmpty()) {
                            recordDecision(workspaceId, workspaceName, evaluator, DECISION_SAMPLED, messages.size());
                            OnlineScoringSamplerSupport.publishSampled(onlineScorePublisher, log, messages,
                                    AutomationRuleEvaluatorType.LLM_AS_JUDGE, workspaceId, workspaceName);
                        }
                    }
                    case USER_DEFINED_METRIC_PYTHON -> {
                        if (serviceTogglesConfig.isPythonEvaluatorEnabled()) {
                            var messages = samples
                                    .map(trace -> toScoreUserDefinedMetricPython(workspaceId, userName, workspaceName,
                                            (AutomationRuleEvaluatorUserDefinedMetricPython) evaluator, trace))
                                    .toList();
                            logSampledTrace(evaluator, messages, scorableTraces.size());
                            if (!messages.isEmpty()) {
                                recordDecision(workspaceId, workspaceName, evaluator, DECISION_SAMPLED,
                                        messages.size());
                                OnlineScoringSamplerSupport.publishSampled(onlineScorePublisher, log, messages,
                                        AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON, workspaceId,
                                        workspaceName);
                            }
                        } else {
                            log.warn("Python 评估器已禁用。跳过评估器类型 '{}' 的采样",
                                    evaluator.getType());
                        }
                    }
                    default -> log.warn("未为评估器类型 '{}' 定义处理过程", evaluator.getType());
                }
            });
        });
    }

    /**
     * 返回给定的追踪列表，其中每个 {@code projectName == null} 条目会使用从
     * {@link ProjectService#findIdToNameByIds} 解析出的名称重建。已携带 projectName 的条目，
     * 以及 projectId 无法解析的条目，原样通过——后者会记录警告。我们有意识地对无法解析的 id
     * 不做快速失败：暂时的查询未命中不应完全丢弃评分；下游的 {@code FeedbackScoreService}
     * 会通过现有契约回退到 Default Project，而 warn 日志会将问题呈现出来以便跟进。
     */
    private List<Trace> stampMissingProjectNames(List<Trace> traces, String workspaceId) {
        Set<UUID> missingNameProjectIds = traces.stream()
                .filter(trace -> trace.projectName() == null)
                .map(Trace::projectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (missingNameProjectIds.isEmpty()) {
            return traces;
        }
        Map<UUID, String> projectNamesById = projectService.findIdToNameByIds(
                workspaceId, missingNameProjectIds);
        return traces.stream()
                .map(trace -> {
                    if (trace.projectName() != null) {
                        return trace;
                    }
                    String resolved = projectNamesById.get(trace.projectId());
                    if (resolved == null) {
                        log.warn(
                                "无法为 projectId '{}'（traceId '{}'，工作区 '{}'）解析 projectName；"
                                        + " 评分将继续，但反馈评分可能不会落在预期的项目上",
                                trace.projectId(), trace.id(), workspaceId);
                        return trace;
                    }
                    return trace.toBuilder().projectName(resolved).build();
                })
                .toList();
    }

    private boolean matchesTriggerScope(AutomationRuleEvaluator<?, ?> evaluator, Trace trace) {
        EvalTriggerScope scope = evaluator.getTriggerScope() != null
                ? evaluator.getTriggerScope()
                : EvalTriggerScope.PRODUCTION;
        if (Source.isLoggingSource(trace.source())) {
            return scope == EvalTriggerScope.PRODUCTION || scope == EvalTriggerScope.BOTH;
        }
        if (trace.source() == Source.EXPERIMENT) {
            return scope == EvalTriggerScope.EXPERIMENT || scope == EvalTriggerScope.BOTH;
        }
        return true;
    }

    private boolean shouldSampleTrace(AutomationRuleEvaluator<?, ?> evaluator, String workspaceId,
            String workspaceName, Trace trace) {
        // 首先检查规则是否已启用
        if (!evaluator.isEnabled()) {
            return skip(workspaceId, workspaceName, evaluator, trace, DECISION_SKIPPED_DISABLED,
                    "traceId '{}' 被跳过，规则：'{}'，因为该规则已禁用",
                    trace.id(), evaluator.getName());
        }

        // 检查追踪是否匹配所有过滤器
        if (!filterEvaluationService.matchesAllFilters(evaluator.getFilters(), trace)) {
            return skip(workspaceId, workspaceName, evaluator, trace, DECISION_SKIPPED_FILTER,
                    "traceId '{}' 被跳过，规则：'{}'，因为它不匹配配置的过滤器",
                    trace.id(), evaluator.getName());
        }

        if (secureRandom.nextFloat() >= evaluator.getSamplingRate()) {
            return skip(workspaceId, workspaceName, evaluator, trace, DECISION_SKIPPED_SAMPLING,
                    "traceId '{}' 被跳过，规则：'{}'，依据采样率 '{}'",
                    trace.id(), evaluator.getName(), evaluator.getSamplingRate());
        }

        // DECISION_SAMPLED 指标在入队时记录（参见 sampleAndScore），因此它
        // 反映的是实际发布到 Redis 的消息，而不仅仅是采样结果。
        return true;
    }

    private TraceToScoreLlmAsJudge toLlmAsJudgeMessage(String workspaceId, String userName, String workspaceName,
            AutomationRuleEvaluatorLlmAsJudge evaluator,
            Trace trace) {
        return TraceToScoreLlmAsJudge.builder()
                .trace(trace)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .llmAsJudgeCode(evaluator.getCode())
                .workspaceId(workspaceId)
                .userName(userName)
                .workspaceName(workspaceName)
                .scoreNameMapping(Map.of())
                .promptType(PromptType.MUSTACHE)
                .build();
    }

    private TraceToScoreUserDefinedMetricPython toScoreUserDefinedMetricPython(String workspaceId, String userName,
            String workspaceName,
            AutomationRuleEvaluatorUserDefinedMetricPython evaluator,
            Trace trace) {
        return TraceToScoreUserDefinedMetricPython.builder()
                .trace(trace)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .code(evaluator.getCode())
                .workspaceId(workspaceId)
                .userName(userName)
                .workspaceName(workspaceName)
                .build();
    }

    private void logSampledTrace(AutomationRuleEvaluator<?, ?> evaluator, List<?> messages, int totalTraces) {
        log.info("[自动化规则 '{}'，类型 '{}'] 从追踪批次中采样 '{}/{}'（预期速率：'{}'）",
                evaluator.getName(),
                evaluator.getType(),
                messages.size(),
                totalTraces,
                evaluator.getSamplingRate());
    }

    private LogContextAware.Closable createTraceLoggingContext(String workspaceId,
            AutomationRuleEvaluator<?, ?> evaluator,
            Trace trace) {
        return wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, workspaceId,
                UserLog.RULE_ID, evaluator.getId().toString(),
                UserLog.TRACE_ID, trace.id().toString()));
    }

    /**
     * 根据每条追踪的规则选择，判断给定评估器是否适用于给定追踪。
     * <ul>
     *   <li>SDK 追踪（以及遗留的 null 来源追踪）不在 {@code selectedRuleIdsByTrace} 中，
     *       并且始终针对每个评估器运行。</li>
     *   <li>携带 {@code selected_rule_ids} 元数据的非 SDK 追踪（例如 Playground）
     *       存在于映射中，并且仅针对其自身选择中的 ID 所对应的评估器运行。</li>
     * </ul>
     *
     * @param evaluator              正在考虑的评估器
     * @param trace                  正在被采样的追踪
     * @param selectedRuleIdsByTrace 追踪 id 到所选规则 ID 的映射，仅为非 SDK 追踪填充
     * @return 如果评估器适用于该追踪则返回 true
     */
    private boolean isEvaluatorSelectedForTrace(AutomationRuleEvaluator<?, ?> evaluator, Trace trace,
            Map<UUID, Set<UUID>> selectedRuleIdsByTrace) {
        var selectedRuleIds = selectedRuleIdsByTrace.get(trace.id());
        return selectedRuleIds == null || selectedRuleIds.contains(evaluator.getId());
    }

    /**
     * 从追踪元数据中提取 selected_rule_ids。
     *
     * @param trace 要检查的追踪
     * @return 元数据中找到的规则 UUID 集合，若缺失/无效则返回空集合
     */
    private Set<UUID> extractSelectedRuleIds(Trace trace) {
        return Optional.ofNullable(trace.metadata())
                .map(metadata -> metadata.get("selected_rule_ids"))
                .filter(JsonNode::isArray)
                .map(ruleIdsNode -> {
                    Set<UUID> ruleIds = new HashSet<>();
                    try {
                        ruleIdsNode.forEach(idNode -> {
                            if (idNode.isTextual()) {
                                try {
                                    ruleIds.add(UUID.fromString(idNode.asText()));
                                } catch (IllegalArgumentException exception) {
                                    log.warn("追踪 '{}' 的 selected_rule_ids 元数据中 UUID 格式无效",
                                            trace.id(), exception);
                                }
                            }
                        });
                    } catch (RuntimeException exception) {
                        log.warn("解析追踪 '{}' 的 selected_rule_ids 元数据时出错", trace.id(), exception);
                    }
                    return ruleIds;
                })
                .orElse(Set.of());
    }
}
