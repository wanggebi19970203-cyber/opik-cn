package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanForLlm;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.resources.v1.events.tools.StringTruncator;
import com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.infrastructure.log.LogContextAware;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateParseUtils;
import com.comet.opik.utils.ValidationUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.common.annotations.VisibleForTesting;
import com.jayway.jsonpath.JsonPath;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;

@UtilityClass
@Slf4j
public class OnlineScoringEngine {

    static final String SCORE_FIELD_NAME = "score";
    static final String REASON_FIELD_NAME = "reason";

    private static final int MAX_REPORTED_FIELD_NAMES = 10;
    private static final int MAX_LOGGED_VALUE_CHARS = 100;
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private static final Map<String, Boolean> PASS_FAIL_SCORES = Map.of(
            "pass", true, "passed", true, "fail", false, "failed", false);

    private static final BigDecimal MIN_SCORE_VALUE = new BigDecimal(ValidationUtils.MIN_FEEDBACK_SCORE_VALUE);
    private static final BigDecimal MAX_SCORE_VALUE = new BigDecimal(ValidationUtils.MAX_FEEDBACK_SCORE_VALUE);

    private static final String SPANS_VARIABLE_NAME = "spans";
    private static final String TRACE_VARIABLE_NAME = "trace";
    private static final String SPAN_VARIABLE_NAME = "span";

    private static final Set<String> SENTINEL_VARIABLE_VALUES = Set.of(
            SPANS_VARIABLE_NAME, TRACE_VARIABLE_NAME, SPAN_VARIABLE_NAME);

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.getMapper();

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    private static final Comparator<Span> BY_SPAN_START_TIME = Comparator
            .comparing(Span::startTime, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * 准备一个发往 LLM 评判器（ChatLanguageModel）的请求，使用 Trace 变量和正确的结构化输出格式来渲染模板消息。
     *
     * @param evaluatorCode LLM 评判器的 'code'
     * @param trace         待评分的采样 Trace
     * @return 一个可触发任何受支持提供方（具备 ChatLanguageModel）的请求
     */
    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull List<Span> spans) {
        return prepareLlmRequest(evaluatorCode, trace, structuredOutputStrategy, PromptType.MUSTACHE, spans);
    }

    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull PromptType promptType,
            @NonNull List<Span> spans) {
        return prepareLlmRequest(evaluatorCode, trace, structuredOutputStrategy, promptType, spans, null);
    }

    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull PromptType promptType,
            @NonNull List<Span> spans, String traceStructureJson) {
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), trace);
        injectSpansIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, spans);
        injectTraceIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, traceStructureJson);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), replacements, promptType);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * {@link #prepareLlmRequest(LlmAsJudgeCode, Trace, StructuredOutputStrategy, PromptType, List)}
     * 的变体，它在 {@code maxReplacementChars} 处截断哨兵变量替换（例如 {@code {{spans}}}），
     * 同时让用户映射的变量（例如 {@code output}、{@code expected_output}）保持不截断。
     *
     * <p>用户映射的变量是评判器评估所需的评分数据——对它们截断会迫使 LLM 通过工具向下钻取，
     * 这是非确定性的，并会产生间歇性的 "Insufficient data" 失败（OPIK-7110）。哨兵变量是结构性上下文，
     * 评判器可以通过 {@code read}/{@code jq} 工具按需向下钻取。
     */
    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull PromptType promptType,
            int maxReplacementChars, @NonNull String drillDownHint, @NonNull List<Span> spans) {
        return prepareLlmRequest(evaluatorCode, trace, structuredOutputStrategy, promptType,
                maxReplacementChars, drillDownHint, spans, null);
    }

    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull PromptType promptType,
            int maxReplacementChars, @NonNull String drillDownHint, @NonNull List<Span> spans,
            String traceStructureJson) {
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), trace);
        injectSpansIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, spans);
        injectTraceIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, traceStructureJson);
        Set<String> userMappedKeys = userMappedVariableKeys(evaluatorCode.variables());
        Map<String, String> capped = capReplacements(replacements, maxReplacementChars,
                drillDownHint, userMappedKeys);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), capped, promptType);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * 规则是否需要将 trace 的 spans 列表渲染进提示词——通过 {@code "spans"} 哨兵选择加入
     * （两种选择加入形态参见 {@link #referencesSpecialVariable}）。
     * 供 trace 评分器用来为模板引用了 {@code {{spans}}} 的内联 LLM 评判评估
     * 选择加入 {@code spanService.getByTraceIds(...)} 的获取。
     */
    public static boolean templateReferencesSpans(
            @NonNull List<LlmAsJudgeMessage> messages,
            @NonNull Map<String, String> variables,
            @NonNull PromptType promptType) {
        return referencesSpecialVariable(messages, variables, promptType, SPANS_VARIABLE_NAME);
    }

    /**
     * 规则是否引用了 {@code {{trace}}} 结构变量——这是评判器需要 agentic 工具循环的声明式信号
     * （它会将 trace id、span id 和附件 {@code file_name} 注入提示词，使评判器无需伪造 id 即可调用
     * {@code get_attachment}）。通过 {@code "trace"} 哨兵选择加入（参见 {@link #referencesSpecialVariable}）。
     */
    public static boolean templateReferencesTraceStructure(
            @NonNull List<LlmAsJudgeMessage> messages,
            @NonNull Map<String, String> variables,
            @NonNull PromptType promptType) {
        return referencesSpecialVariable(messages, variables, promptType, TRACE_VARIABLE_NAME);
    }

    /**
     * 跨度级别的规则是否引用了 {@code {{span}}} 结构变量——这是 span 评判器需要 agentic 工具循环的声明式信号
     * （它会注入 span id 加上 span 自身的附件 {@code file_name}，使评判器无需伪造 id 即可调用
     * {@code get_attachment(type=span, ...)}）。通过 {@code "span"} 哨兵选择加入（参见 {@link #referencesSpecialVariable}）；
     * 与 trace 级别的 {@code {{spans}}} 列表哨兵不同。
     */
    public static boolean templateReferencesSpanStructure(
            @NonNull List<LlmAsJudgeMessage> messages,
            @NonNull Map<String, String> variables,
            @NonNull PromptType promptType) {
        return referencesSpecialVariable(messages, variables, promptType, SPAN_VARIABLE_NAME);
    }

    /**
     * 规则是否选择了名为 {@code sentinel} 的特殊变量——这是 {@code {{spans}}}、{@code {{trace}}} 和
     * {@code {{span}}} 背后的共享检测。两种选择加入形态：
     * <ul>
     *   <li>哨兵值变量：{@code variables} 中值为纯哨兵字符串（无 JSONPath 前缀）的任何条目——
     *       即用户输入 {@code {{<sentinel>}}} 时 FE 写入的内容。
     *   <li>直接模板引用：消息引用了 {@code {{<sentinel>}}}（根据 {@code promptType}），
     *       且 variables 映射未将其绑定到自定义路径，因此显式的用户映射优先。
     * </ul>
     */
    private static boolean referencesSpecialVariable(
            List<LlmAsJudgeMessage> messages, Map<String, String> variables, PromptType promptType,
            String sentinel) {
        return variables.containsValue(sentinel)
                || messagesReferenceSpecialVariableDirectly(messages, variables, promptType, sentinel);
    }

    /**
     * 当至少一个消息模板引用了 {@code {{<sentinel>}}}（根据 {@code promptType}）且 variables 映射
     * 未将 {@code sentinel} 绑定到自定义路径时为 true。遍历两种消息形态——简单字符串 {@code content}
     * 和多模态 {@code contentArray} 的文本部分（通过 {@link #renderableTextOf}）；只扫描 {@code content}
     * 会漏掉多模态提示词中的引用，导致渲染后的文本部分因选择加入从未触发而未被替换。
     */
    private static boolean messagesReferenceSpecialVariableDirectly(
            List<LlmAsJudgeMessage> messages, Map<String, String> variables, PromptType promptType,
            String sentinel) {
        if (variables.containsKey(sentinel)) {
            return false;
        }
        return messages.stream()
                .filter(Objects::nonNull)
                .flatMap(OnlineScoringEngine::renderableTextOf)
                .anyMatch(text -> TemplateParseUtils.extractVariables(text, promptType).contains(sentinel));
    }

    /**
     * 消息中所有可替换变量的文本流：存在时是简单 {@code content} 字符串，否则是 {@code contentArray}
     * 中每个非空的 {@code text} 部分。镜像渲染器会替换的内容——任何我们需要扫描 {@code {{spans}}}
     * 引用的内容也必须由这个辅助方法扫描，否则检测会偏离渲染。
     */
    private static Stream<String> renderableTextOf(LlmAsJudgeMessage message) {
        if (message.isStringContent()) {
            return Stream.of(message.content());
        }
        if (message.isStructuredContent()) {
            return message.contentArray().stream()
                    .filter(Objects::nonNull)
                    .map(LlmAsJudgeMessageContent::text)
                    .filter(Objects::nonNull);
        }
        return Stream.empty();
    }

    /**
     * 将任何映射到 {@code "spans"} 哨兵的变量（以及隐式的 {@code {{spans}}} 引用）替换为 JSON 序列化的
     * spans 列表（父→子树，兄弟节点按 start_time 排序）。共享替换机制参见 {@link #injectSpecialVariable}；
     * 该树是惰性序列化的，仅在实际引用了哨兵时才会序列化。
     *
     * <p>空的 spans 列表仍会触发重写（渲染为 {@code "[]"}）。
     * <strong>有意不受 {@code isAgenticToolsEnabled} 门控</strong>：当开关关闭时，评分器跳过 spans 获取，
     * 并在这里传入空列表，这仍会将哨兵映射的变量重写为 {@code "[]"}。对此进行门控会再次导致那些在开关
     * 翻转之前 variables 映射仍携带哨兵的规则泄漏裸单词。开关语义的完整理由参见
     * {@code OnlineScoringLlmAsJudgeScorer.shouldFetchSpans}。
     */
    private static void injectSpansIntoReplacements(
            Map<String, String> replacements, Map<String, String> variables,
            List<LlmAsJudgeMessage> messages, PromptType promptType, List<Span> spans) {
        injectSpecialVariable(replacements, variables, messages, promptType, SPANS_VARIABLE_NAME,
                () -> serializeSpansTree(spans));
    }

    /**
     * 用预先构建的 trace 结构 JSON（在评分器的响应式附件获取中于上游构建）替换 {@code "trace"} 哨兵。
     * 空结构渲染为 {@code "{}"}，因此变量永远不会泄漏裸单词 "trace"。参见 {@link #injectSpecialVariable}。
     */
    private static void injectTraceIntoReplacements(
            Map<String, String> replacements, Map<String, String> variables,
            List<LlmAsJudgeMessage> messages, PromptType promptType, String traceStructureJson) {
        injectSpecialVariable(replacements, variables, messages, promptType, TRACE_VARIABLE_NAME,
                () -> traceStructureJson != null ? traceStructureJson : "{}");
    }

    /**
     * 用预先构建的 span 结构 JSON 替换 {@code "span"} 哨兵。空结构渲染为 {@code "{}"}。
     * {@link #injectTraceIntoReplacements} 的跨度级别镜像；参见 {@link #injectSpecialVariable}。
     */
    private static void injectSpanIntoReplacements(
            Map<String, String> replacements, Map<String, String> variables,
            List<LlmAsJudgeMessage> messages, PromptType promptType, String spanStructureJson) {
        injectSpecialVariable(replacements, variables, messages, promptType, SPAN_VARIABLE_NAME,
                () -> spanStructureJson != null ? spanStructureJson : "{}");
    }

    /**
     * 哨兵命名的特殊变量的共享替换。就地修改 {@code replacements}：源路径为 {@code sentinel} 的每个变量
     * （以及对于没有绑定的隐式 {@code {{<sentinel>}}} 模板引用，哨兵键本身）都被设置为 {@code value}。
     * 当没有任何内容引用哨兵时不执行操作——且 {@code value} 不会被调用——因此调用方可以将昂贵值的构建
     * （例如序列化 spans 树）推迟到 supplier 中。
     *
     * <p>空值/占位值仍会触发重写（例如 {@code "[]"} / {@code "{}"}）：如果没有它，{@code toReplacements}
     * 会将裸哨兵保留为字面量，提示词就会渲染出哨兵单词而不是实际内容。同时处理隐式引用的情况（模板使用
     * {@code {{<sentinel>}}} 但 variables 映射未绑定它），镜像 FE 的自动填充，使通过 API 创建的规则无需
     * 了解哨兵约定也能表现一致。
     */
    private static void injectSpecialVariable(
            Map<String, String> replacements, Map<String, String> variables,
            List<LlmAsJudgeMessage> messages, PromptType promptType, String sentinel,
            Supplier<String> value) {
        boolean sentinelMapped = variables.containsValue(sentinel);
        boolean templateOnly = messagesReferenceSpecialVariableDirectly(messages, variables, promptType, sentinel);
        if (!sentinelMapped && !templateOnly) {
            return;
        }
        String rendered = value.get();
        variables.forEach((name, path) -> {
            if (sentinel.equals(path)) {
                replacements.put(name, rendered);
            }
        });
        if (templateOnly) {
            replacements.put(sentinel, rendered);
        }
    }

    private static String serializeSpansTree(List<Span> spans) {
        // 投影为 SpanForLlm 并重建父→子层级，使评判器看到调用树而不是扁平列表。
        // 丢弃审计元数据、反馈评分、评论、成本数据——这些都无助于评判器且都会消耗 token。
        // 兄弟节点在 buildSpanTree 内部按 start_time 排序。
        try {
            return OBJECT_MAPPER.writeValueAsString(buildSpanTree(spans));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 返回替换映射中对应于用户映射变量（如 {@code output}、{@code input.question} 等 trace 节或字面常量）
     * 的键集合，与哨兵变量（如 {@code spans}）相对。供 {@link #capReplacements} 用来决定哪些键保持不截断。
     */
    @VisibleForTesting
    static Set<String> userMappedVariableKeys(Map<String, String> variables) {
        var keys = new HashSet<>(variables.keySet());
        keys.removeIf(k -> SENTINEL_VARIABLE_VALUES.contains(variables.get(k)));
        return keys;
    }

    /**
     * 将替换值在 {@code maxReplacementChars} 处截断，跳过 {@code uncappedKeys} 中的键。
     * 传入 {@code Set.of()} 可截断所有内容。用户映射的评分变量不应截断——截断它们会迫使
     * 非确定性的工具向下钻取（OPIK-7110）。
     */
    @VisibleForTesting
    static Map<String, String> capReplacements(Map<String, String> replacements,
            int maxReplacementChars, String drillDownHint, Set<String> uncappedKeys) {
        return replacements.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> uncappedKeys.contains(e.getKey())
                        ? e.getValue()
                        : StringTruncator.truncate(e.getValue(), maxReplacementChars, drillDownHint),
                (a, b) -> b,
                LinkedHashMap::new));
    }

    /**
     * 准备一个发往 LLM 评判器（ChatLanguageModel）的请求，使用 Span 变量和正确的结构化输出格式来渲染模板消息。
     *
     * @param evaluatorCode LLM 评判器的 'code'
     * @param span          待评分的采样 Span
     * @return 一个可触发任何受支持提供方（具备 ChatLanguageModel）的请求
     */
    public static ChatRequest prepareSpanLlmRequest(
            @NonNull AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode evaluatorCode,
            @NonNull Span span,
            @NonNull StructuredOutputStrategy structuredOutputStrategy) {
        var renderedMessages = renderMessages(evaluatorCode.messages(), evaluatorCode.variables(), span);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * 内联变体，注入预先构建的 {@code {{span}}} 结构（span id + span 自身的附件 {@code file_name}）而不截断——
     * 供 span 评分器在规则引用了 {@code {{span}}} 但提供方无法调用工具时使用，因此变量仍会渲染结构
     * 而不是裸单词 "span"。Span 模板始终使用 {@link PromptType#MUSTACHE} 渲染。
     */
    public static ChatRequest prepareSpanLlmRequest(
            @NonNull AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode evaluatorCode,
            @NonNull Span span,
            @NonNull StructuredOutputStrategy structuredOutputStrategy,
            String spanStructureJson) {
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), span);
        injectSpanIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), PromptType.MUSTACHE, spanStructureJson);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), replacements,
                PromptType.MUSTACHE);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * {@link #prepareSpanLlmRequest(AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode, Span, StructuredOutputStrategy)}
     * 的工具模式变体，供 span 评分器的 agentic 工具路径使用：注入预先构建的 {@code {{span}}} 结构
     * （span id + span 自身的附件 {@code file_name}），并在 {@code maxReplacementChars} 处截断哨兵变量替换
     * （例如 {@code {{span}}}），同时让用户映射的变量保持不截断（OPIK-7110）。Span 模板始终使用
     * {@link PromptType#MUSTACHE} 渲染。
     */
    public static ChatRequest prepareSpanLlmRequest(
            @NonNull AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode evaluatorCode,
            @NonNull Span span,
            @NonNull StructuredOutputStrategy structuredOutputStrategy,
            int maxReplacementChars, @NonNull String drillDownHint, String spanStructureJson) {
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), span);
        injectSpanIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), PromptType.MUSTACHE, spanStructureJson);
        Set<String> userMappedKeys = userMappedVariableKeys(evaluatorCode.variables());
        Map<String, String> capped = capReplacements(replacements, maxReplacementChars,
                drillDownHint, userMappedKeys);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), capped, PromptType.MUSTACHE);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * 从渲染后的消息构建 ChatRequest 的公共实现。
     * 提取出来以减少 prepareLlmRequest、prepareSpanLlmRequest 和 prepareThreadLlmRequest 之间的重复。
     */
    private static ChatRequest buildChatRequest(
            List<ChatMessage> renderedMessages,
            List<LlmAsJudgeOutputSchema> schema,
            StructuredOutputStrategy structuredOutputStrategy) {
        var chatRequestBuilder = ChatRequest.builder().messages(renderedMessages);
        return structuredOutputStrategy.apply(chatRequestBuilder, renderedMessages, schema).build();
    }

    /**
     * 准备一个发往 LLM 评判器（ChatLanguageModel）的请求，使用 Trace 变量和正确的结构化输出格式来渲染模板消息。
     *
     * @param evaluatorCode LLM 评判器的 'code'
     * @param traces        来自待评分 trace 线程的采样 traces
     * @return 一个可触发任何受支持提供方（具备 ChatLanguageModel）的请求
     */
    public static ChatRequest prepareThreadLlmRequest(
            @NonNull TraceThreadLlmAsJudgeCode evaluatorCode, @NonNull List<Trace> traces,
            @NonNull StructuredOutputStrategy structuredOutputStrategy,
            @NonNull List<Span> spans) {
        var renderedMessages = renderThreadMessages(evaluatorCode.messages(),
                Map.of(TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME, ""), traces, spans);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * agentic 工具分支的变体：仅为线程渲染一个紧凑的每个 trace 的 <em>骨架</em>
     * （id、名称、时长、span 数量）以及一个指向 {@code read(type=trace, id=X)} 的向下钻取提示。
     * 模型通过 ReadTool 按需获取任何特定 trace 的完整内容（及其 spans）——与 trace 级别路径相同的惰性机制。
     * 即使在有数千个 trace 的线程上也能保持内联提示词有界。
     *
     * <p>{@code context} 变量被替换为骨架 + 向下钻取指引，因此引用了 {@code {{context}}} 的
     * 用户提供的提示词模板无需修改即可继续工作。
     *
     * <p><strong>前置条件：</strong>所有 {@code evaluatorCode.messages()} 必须声明字符串内容。
     * 多模态模板在此路径上不受支持——{@link #renderThreadMessagesWithReplacement} 会在第一个非字符串
     * 条目处抛出异常。调用方应通过 {@link #hasMultimodalTemplate(List)} 在上游检测多模态模板并回退到内联路径。
     */
    public static ChatRequest prepareThreadLlmRequestWithTools(
            @NonNull TraceThreadLlmAsJudgeCode evaluatorCode, @NonNull List<Trace> traces,
            @NonNull StructuredOutputStrategy structuredOutputStrategy) {
        String skeleton;
        try {
            skeleton = OBJECT_MAPPER.writeValueAsString(toThreadSkeleton(traces));
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
        String drillDownHint = "Call read(type=trace, id=<uuid>) on any trace id from the"
                + " thread skeleton above to inspect its full input/output + spans, or"
                + " jq(type=trace, id=<uuid>, expression='<path>') for path-targeted lookups.";
        String contextValue = "Thread skeleton (compact per-trace summary; use tools to drill in):\n"
                + skeleton + "\n\n" + drillDownHint;

        var renderedMessages = renderThreadMessagesWithReplacement(evaluatorCode.messages(),
                TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME, contextValue);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * agentic 工具分支渲染进提示词而非完整 trace 列表的紧凑每个 trace 摘要。
     * 每个 trace 约 100 个字符——因此一个 1 万个 trace 的线程约为 1 MB，即使不进一步压缩也远低于模型窗口。
     * 模型从该列表中挑选 id 并通过 ReadTool 向下钻取。
     */
    static List<ThreadTraceSkeleton> toThreadSkeleton(List<Trace> traces) {
        return traces.stream()
                .map(trace -> new ThreadTraceSkeleton(
                        trace.id(),
                        trace.name(),
                        trace.startTime(),
                        trace.endTime(),
                        trace.duration(),
                        trace.spanCount(),
                        trace.llmSpanCount()))
                .toList();
    }

    /**
     * 作为线程骨架的一部分发送给模型的紧凑每个 trace 摘要。
     * 字段集有意保持精简——任何超出此范围的内容都只需一次 {@code read} 即可获得。
     *
     * <p>{@code @JsonNaming(SnakeCaseStrategy)} 使线上结构与 {@link Trace} 的序列化保持一致
     * （同样通过该策略使用 snake_case），因此模型在骨架和后续 {@code read(type=trace, id=X)}
     * 响应中看到相同的字段名。这里若使用驼峰命名会让模型对同一实体看到两种不同的模式。
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Builder(toBuilder = true)
    public record ThreadTraceSkeleton(
            @NonNull UUID id,
            String name,
            @NonNull Instant startTime,
            Instant endTime,
            Double duration,
            int spanCount,
            int llmSpanCount) {
    }

    /**
     * {@link #renderThreadMessages} 的轻量孪生版本，直接替换已渲染的 {@code context} 字符串，
     * 跳过 Jackson 序列化 traces 的步骤。供工具路径使用，其中变量值（骨架 + 向下钻取提示）由调用方计算。
     *
     * <p>调用方（线程评分器的 {@code shouldUseAgenticTools} 门控）通过 {@link #hasMultimodalTemplate(List)}
     * 在上游检测多模态模板并回退到内联路径，因此到达这里时 {@code templateMessages} 保证只包含字符串。
     * 下面的断言是防御性兜底而非主要安全机制——实际渲染委托给 {@link #renderMessagesWithReplacements}，
     * 使角色切换/模板引擎逻辑集中在一处。
     */
    private static List<ChatMessage> renderThreadMessagesWithReplacement(
            List<LlmAsJudgeMessage> templateMessages, String variableName, String contextValue) {
        if (hasMultimodalTemplate(templateMessages)) {
            throw new UnsupportedOperationException(
                    "Multimodal thread message content is not supported on the agentic-tools path");
        }
        Map<String, String> replacements = Map.of(variableName, contextValue);
        return renderMessagesWithReplacements(templateMessages, replacements);
    }

    static List<ChatMessage> renderThreadMessages(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> variablesMap, List<Trace> traces,
            List<Span> spans) {
        // 准备用于所有消息的替换映射
        Map<String, String> replacements = variablesMap.keySet().stream()
                .map(variableName -> switch (variableName) {
                    case TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME -> {
                        // 始终使用富化形态——当 `spans` 为空（开关关闭）时，
                        // `spans` 字段通过 @JsonInclude(NON_NULL) 被省略，且
                        // JSON 与当前的 [{role, content}, ...] 形态在线上完全一致。
                        try {
                            yield MessageVariableMapping.builder()
                                    .variableName(variableName)
                                    .valueToReplace(OBJECT_MAPPER.writeValueAsString(
                                            fromTraceToThreadEnriched(traces, spans)))
                                    .build();
                        } catch (JsonProcessingException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    }
                    default -> throw new IllegalArgumentException("Invalid variable name: " + variableName);
                })
                .collect(
                        Collectors.toMap(MessageVariableMapping::variableName, MessageVariableMapping::valueToReplace));
        // 一旦构建好替换，渲染本身与 trace / span 流程相同——
        // 委托出去，使角色展开、多模态处理和提示词类型默认值集中在一处。
        // 线程特有的部分只是上面的替换组装。
        return renderMessagesWithReplacements(templateMessages, replacements);
    }

    /**
     * 使用实际 trace 中的值渲染规则评估器的消息模板。
     * <p>
     * 由于规则可能由多条消息组成，我们检查每条消息中是否有需要填充的变量。
     * 然后遍历每个变量模板，将其替换为来自 trace 的值。
     *
     * @param templateMessages 需要填充 Trace 值的带变量消息列表
     * @param variablesMap     模板变量到 Trace 中值路径的映射
     * @param trace            用于替换模板变量的带值的 trace
     * @return 渲染了模板的 AI 消息列表
     */
    static List<ChatMessage> renderMessages(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> variablesMap, Trace trace) {
        Map<String, String> replacements = toReplacements(variablesMap, trace);
        return renderMessagesWithReplacements(templateMessages, replacements);
    }

    /**
     * 使用实际 span 中的值渲染规则评估器的消息模板。
     * 与 renderMessages 类似，但用于 span。
     *
     * @param templateMessages 需要填充 Span 值的带变量消息列表
     * @param variablesMap     模板变量到 Span 中值路径的映射
     * @param span             用于替换模板变量的带值的 span
     * @return 渲染了模板的 AI 消息列表
     */
    static List<ChatMessage> renderMessages(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> variablesMap, Span span) {
        Map<String, String> replacements = toReplacements(variablesMap, span);
        return renderMessagesWithReplacements(templateMessages, replacements);
    }

    /**
     * 使用替换渲染消息的公共实现。
     * 此方法处理 traces 和 spans 之间共享的实际消息渲染逻辑。
     */
    private static List<ChatMessage> renderMessagesWithReplacements(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> replacements) {
        return renderMessagesWithReplacements(templateMessages, replacements, PromptType.MUSTACHE);
    }

    private static List<ChatMessage> renderMessagesWithReplacements(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> replacements, PromptType promptType) {
        // 渲染来自评估器规则的消息模板
        return templateMessages.stream()
                .map(templateMessage -> {
                    // 检查内容是字符串（文本）还是数组（多模态）
                    if (templateMessage.isStringContent()) {
                        // 字符串格式：纯文本内容
                        var txtContent = templateMessage.asString();
                        var renderedMessage = TemplateParseUtils.render(txtContent, replacements, promptType);
                        return switch (templateMessage.role()) {
                            case USER -> UserMessage.from(renderedMessage);
                            case SYSTEM -> SystemMessage.from(renderedMessage);
                            default -> {
                                log.info("消息角色类型 {} 无映射", templateMessage.role());
                                yield null;
                            }
                        };
                    } else if (templateMessage.isStructuredContent()) {
                        // 数组格式：结构化内容部分
                        return switch (templateMessage.role()) {
                            case USER -> buildUserMessageFromContentParts(
                                    templateMessage.asContentList(), replacements, promptType);
                            case SYSTEM -> {
                                // 对于带数组内容的 SYSTEM 消息，提取第一个文本部分
                                var textContent = templateMessage.asContentList().stream()
                                        .filter(part -> "text".equals(part.type()))
                                        .map(LlmAsJudgeMessageContent::text)
                                        .filter(Objects::nonNull)
                                        .map(text -> TemplateParseUtils.render(text, replacements, promptType))
                                        .findFirst()
                                        .orElse("");
                                yield SystemMessage.from(textContent);
                            }
                            default -> {
                                log.info("消息角色类型 {} 无映射", templateMessage.role());
                                yield null;
                            }
                        };
                    } else {
                        log.warn("消息角色 {} 的内容类型未知", templateMessage.role());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 从实体中提取 JSON 节（input/output/metadata）的函数式接口。
     */
    @FunctionalInterface
    private interface JsonSectionExtractor {
        JsonNode extract(TraceSection section);
    }

    public static Map<String, String> toReplacements(Map<String, String> variables, Trace trace) {
        return toReplacements(variables, section -> switch (section) {
            case INPUT -> trace.input();
            case OUTPUT -> trace.output();
            case METADATA -> trace.metadata();
        });
    }

    /**
     * 注入内置 {@code spans} 变量的变体，该变量持有投影为 {@link SpanForLlm} 并重建为父→子树的
     * trace 的 spans。供其 {@code score(...)} 签名接受 {@code spans} 的 Python 指标使用；
     * 用户通过在 {@code arguments} 映射中包含 {@code "spans"} 键来选择加入。用户为 {@code spans}
     * 映射的值会被类型化列表覆盖——{@code spans} 是保留的内置变量，不是普通的按路径解析的变量。
     *
     * <p>该形态与此 PR 上所有其他 span 渲染路径一致（LLM 评判的 trace 作用域 {@code {{spans}}}、
     * LLM 评判和 Python 的线程作用域 {@code {{context}}}）：精简的 {@link SpanForLlm} 投影
     * （11 个字段——name、type、in/out、timing、model/provider、error_info，加上嵌套的子节点）、
     * {@code @JsonInclude(NON_NULL)} 使 JSON 紧凑，以及在 {@link #buildSpanTree} 内部的每一层按
     * {@code startTime} 排序兄弟节点以保留调用顺序。
     *
     * <p>返回的映射类型为 {@code Map<String, Object>}，因此 spans 值可以通过 Jackson 序列化将
     * {@code List<SpanForLlm>} 树作为 JSON 数组传递给 Python 运行器。Python 端在 {@code json.loads(...)}
     * 之后将 {@code spans} 接收为字典列表——而不是用户需要重新解析的 JSON 字符串。
     *
     * <p>调用方负责仅在用户确实请求了 spans 时调用此重载（即 {@code arguments.containsKey("spans")}）。
     * 评分器做出此决定，从而在不需要 spans 的指标上跳过 span 获取。
     */
    public static Map<String, Object> toReplacements(
            @NonNull Map<String, String> variables, @NonNull Trace trace, @NonNull List<Span> spans) {
        var base = toReplacements(variables, trace);
        var result = new LinkedHashMap<String, Object>(base);
        result.put(SPANS_VARIABLE_NAME, buildSpanTree(spans));
        return result;
    }

    public static Map<String, String> toReplacements(Map<String, String> variables, Span span) {
        return toReplacements(variables, section -> switch (section) {
            case INPUT -> span.input();
            case OUTPUT -> span.output();
            case METADATA -> span.metadata();
        });
    }

    /**
     * 将变量转换为替换的公共实现。
     * 通过接受提取 JSON 节的函数，同时适用于 Trace 和 Span。
     */
    private static Map<String, String> toReplacements(
            Map<String, String> variables, JsonSectionExtractor sectionExtractor) {
        var parsedVariables = toVariableMapping(variables);
        // 从实体中提取实际值
        return parsedVariables.stream().map(mapper -> {
            var section = mapper.traceSection();
            var jsonSection = section != null ? sectionExtractor.extract(section) : null;
            // 如果没有节，就没有替换，直接采用字面值
            var valueToReplace = jsonSection != null
                    ? extractFromJson(jsonSection, mapper.jsonPath())
                    : mapper.valueToReplace;
            return mapper.toBuilder()
                    .valueToReplace(valueToReplace)
                    .build();
        }).filter(mapper -> mapper.valueToReplace() != null)
                .collect(
                        Collectors.toMap(MessageVariableMapping::variableName, MessageVariableMapping::valueToReplace));
    }

    /**
     * 将评估器的变量映射解析为可用的映射列表。
     *
     * @param evaluatorVariables 包含变量及指向 trace input/output/metadata 中待替换值的路径的映射
     * @return 解析后的映射列表，更便于模板渲染使用
     */
    static List<MessageVariableMapping> toVariableMapping(Map<String, String> evaluatorVariables) {
        return evaluatorVariables.entrySet().stream()
                .map(mapper -> {
                    var templateVariable = mapper.getKey();
                    var tracePath = mapper.getValue();
                    var builder = MessageVariableMapping.builder().variableName(templateVariable);
                    // 检查它是否为 input/output/metadata 变量并修正 json 路径
                    Arrays.stream(TraceSection.values())
                            .filter(traceSection -> {
                                // 匹配 "input." 或仅 "input"（output/metadata 同理）
                                String prefixWithDot = traceSection.prefix;
                                String prefixWithoutDot = prefixWithDot.substring(0, prefixWithDot.length() - 1);
                                return tracePath.startsWith(prefixWithDot) || tracePath.equals(prefixWithoutDot);
                            })
                            .findFirst()
                            .ifPresentOrElse(traceSection -> {
                                // 如果路径包含点，提取嵌套路径；否则使用根 "$"
                                String jsonPath = tracePath.contains(".")
                                        ? "$." + tracePath.substring(traceSection.prefix.length())
                                        : "$";
                                builder.traceSection(traceSection).jsonPath(jsonPath);
                            },
                                    // 如果不是 trace 节，它就是待替换的字面值
                                    () -> builder.valueToReplace(tracePath));

                    return builder.build();
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 从结构化内容部分（数组格式）构建 UserMessage。
     * 支持 text、image_url、video_url 和 audio_url 内容类型。
     */
    private UserMessage buildUserMessageFromContentParts(
            List<LlmAsJudgeMessageContent> contentParts, Map<String, String> replacements) {
        return buildUserMessageFromContentParts(contentParts, replacements, PromptType.MUSTACHE);
    }

    private UserMessage buildUserMessageFromContentParts(
            List<LlmAsJudgeMessageContent> contentParts, Map<String, String> replacements, PromptType promptType) {
        var builder = UserMessage.builder();

        for (var part : contentParts) {
            switch (part.type()) {
                case "text" -> {
                    if (part.text() != null) {
                        var renderedText = TemplateParseUtils.render(part.text(), replacements, promptType);
                        if (StringUtils.isNotBlank(renderedText)) {
                            builder.addContent(TextContent.from(renderedText));
                        }
                    }
                }
                case "image_url" -> {
                    if (part.imageUrl() != null && part.imageUrl().url() != null) {
                        var url = TemplateParseUtils.render(part.imageUrl().url(), replacements, promptType);
                        builder.addContent(ImageContent.from(url));
                    }
                }
                case "video_url" -> {
                    if (part.videoUrl() != null && part.videoUrl().url() != null) {
                        var url = TemplateParseUtils.render(part.videoUrl().url(), replacements, promptType);
                        builder.addContent(VideoContent.from(url));
                    }
                }
                case "audio_url" -> {
                    if (part.audioUrl() != null && part.audioUrl().url() != null) {
                        var url = TemplateParseUtils.render(part.audioUrl().url(), replacements, promptType);
                        builder.addContent(AudioContent.from(url));
                    }
                }
                default -> log.warn("未知内容类型：{}", part.type());
            }
        }

        return builder.build();
    }

    private static String extractFromJson(JsonNode json, String path) {
        // 特殊情况：如果 path 为 "$"，将整个 JSON 对象作为字符串返回
        if ("$".equals(path)) {
            try {
                return OBJECT_MAPPER.writeValueAsString(json);
            } catch (JsonProcessingException e) {
                log.warn("无法序列化整个 json 对象，json={}", json, e);
                return null;
            }
        }

        Map<String, Object> forcedObject;
        try {
            // JsonPath 与 JsonNode 不兼容，即使显式使用
            // JacksonJsonProvider 也是如此，因此我们转换为 Map
            forcedObject = OBJECT_MAPPER.convertValue(json, new TypeReference<>() {
            });
        } catch (InvalidArgumentException e) {
            log.warn("解析 json 失败，json={}", json, e);
            return null;
        }

        try {
            var value = JsonPath.parse(forcedObject).read(path);
            return value != null ? serializeToJsonString(value) : null;
        } catch (Exception e) {
            log.warn("在 json 中找不到路径，尝试扁平结构，path={}, json={}", path, json, e);
            return Optional.ofNullable(forcedObject.get(path.replace("$.", "")))
                    .map(OnlineScoringEngine::serializeToJsonString)
                    .orElseGet(() -> {
                        log.info("在 json 中找不到扁平或嵌套路径，path={}, json={}", path, json);
                        return null;
                    });
        }
    }

    /**
     * 将值序列化为 JSON 字符串。对于简单类型（String、Number、Boolean），
     * 直接将值作为字符串返回。对于复杂类型（Map、List），序列化为 JSON。
     */
    private static String serializeToJsonString(Object value) {
        if (value == null) {
            return null;
        }
        // 对于简单类型，按原样返回以保持向后兼容
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        // 对于复杂类型（Map、List 等），序列化为正确的 JSON
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("无法将值序列化为 JSON，回退到 toString()，value={}", value, e);
            return value.toString();
        }
    }

    public static List<TraceThreadPythonEvaluatorRequest.ChatMessage> fromTraceToThread(List<Trace> traces) {
        return traces.stream()
                .flatMap(trace -> Stream.of(
                        TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                .role(TraceThreadPythonEvaluatorRequest.ROLE_USER)
                                .content(trace.input())
                                .build(),
                        TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                .role(TraceThreadPythonEvaluatorRequest.ROLE_ASSISTANT)
                                .content(trace.output())
                                .build()))
                .toList();
    }

    /**
     * 构建聊天消息列表，用于在 LLM 线程渲染路径中填充 {@code {{context}}}，
     * 可选择性地用附加到其 assistant 消息上的每个 trace 的子 spans 进行富化。
     * 向后兼容：只要 trace 没有 spans（或调用方传入空列表），JSON 中就会省略 {@code spans} 字段，
     * 因此针对当前 {@code [{role, content}, ...]} 形态编写的规则不会看到任何差异。
     *
     * <p>每个 trace 内的 spans 按 start_time 排序，使线上顺序与调用顺序一致——
     * 与 trace 作用域 {@code {{spans}}} 路径相同的约定。
     *
     * <p>返回与 {@link #fromTraceToThread(List)} 相同的 {@link TraceThreadPythonEvaluatorRequest.ChatMessage}
     * 类型，使两条渲染路径共享一种线上形态：统一的 {@code ChatMessage} 携带一个可选的 {@code spans} 字段
     * （为 null 时通过 {@code @JsonInclude(NON_NULL)} 省略），使富化后的 JSON 成为旧版
     * {@code [{role, content}, ...]} 契约的严格超集。
     */
    public static List<TraceThreadPythonEvaluatorRequest.ChatMessage> fromTraceToThreadEnriched(
            @NonNull List<Trace> traces, @NonNull List<Span> spans) {
        Map<UUID, List<Span>> spansByTrace = spans.stream()
                .collect(Collectors.groupingBy(Span::traceId));
        return traces.stream()
                .flatMap(trace -> {
                    // 按每个 trace 重建父 → 子层级，使 assistant 条目携带 spans 树而非扁平列表。
                    // buildSpanTree 负责在每一层按 start_time 排序兄弟节点。
                    List<SpanForLlm> traceSpans = buildSpanTree(
                            spansByTrace.getOrDefault(trace.id(), List.of()));
                    return Stream.of(
                            TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                    .role(TraceThreadPythonEvaluatorRequest.ROLE_USER)
                                    .content(trace.input())
                                    .build(),
                            TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                    .role(TraceThreadPythonEvaluatorRequest.ROLE_ASSISTANT)
                                    .content(trace.output())
                                    .spans(traceSpans.isEmpty() ? null : traceSpans)
                                    .build());
                })
                .toList();
    }

    /**
     * 为内联 LLM 渲染构建给定 spans 的嵌套树投影。
     *
     * <p>从 {@code parentSpanId} 链接重建父 → 子层级，将每个节点投影为 {@link SpanForLlm}
     * （丢弃 {@code Span} 携带的审计/评分/成本噪音），并在每一层按 {@code startTime} 排序兄弟节点，
     * 使线上顺序跟踪每个分支内的调用顺序。返回顶层根节点。
     *
     * <p>孤儿节点（其 {@code parentSpanId} 不在输入列表中的 spans）会被提升为根节点——
     * 发生在调用方传入 trace 的 spans 子集，或父节点在服务端被丢弃时。树保持良好结构，
     * 而不是静默丢弃孤儿子树。
     */
    public static List<SpanForLlm> buildSpanTree(@NonNull List<Span> spans) {
        if (spans.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Span>> childrenByParent = new HashMap<>();
        Set<UUID> presentIds = new HashSet<>();
        for (Span span : spans) {
            if (span.id() != null) {
                presentIds.add(span.id());
            }
            UUID parentId = span.parentSpanId();
            if (parentId != null) {
                childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(span);
            }
        }
        // 根节点：parent 为 null 或 parent 不在可见集合中（孤儿提升）。
        List<Span> roots = spans.stream()
                .filter(s -> s.parentSpanId() == null || !presentIds.contains(s.parentSpanId()))
                .sorted(BY_SPAN_START_TIME)
                .toList();
        return roots.stream()
                .map(root -> buildSpanNode(root, childrenByParent))
                .toList();
    }

    private static SpanForLlm buildSpanNode(Span span, Map<UUID, List<Span>> childrenByParent) {
        List<SpanForLlm> children = span.id() == null
                ? List.of()
                : childrenByParent.getOrDefault(span.id(), List.of()).stream()
                        .sorted(BY_SPAN_START_TIME)
                        .map(child -> buildSpanNode(child, childrenByParent))
                        .toList();
        return SpanForLlm.builder()
                .name(span.name())
                .type(span.type())
                .startTime(span.startTime())
                .endTime(span.endTime())
                .duration(span.duration())
                .input(span.input())
                .output(span.output())
                .metadata(span.metadata())
                .model(span.model())
                .provider(span.provider())
                .errorInfo(span.errorInfo())
                .spans(children.isEmpty() ? null : children)
                .build();
    }

    /**
     * @param unreadableScoreNames 评判器以我们无法读取的形式给出的分数值
     * @param undeclaredScoreNames 评判器以规则未声明的名称返回的分数
     * @param problem              当整个回答没有产生任何结果时设置；否则为 null
     */
    @Builder(toBuilder = true)
    public record ParsedFeedbackScores(List<FeedbackScoreBatchItem> scores, List<String> nullScoreNames,
            List<String> unreadableScoreNames, List<String> undeclaredScoreNames, ResponseProblem problem) {

        public ParsedFeedbackScores {
            scores = scores == null ? List.of() : List.copyOf(scores);
            nullScoreNames = nullScoreNames == null ? List.of() : List.copyOf(nullScoreNames);
            unreadableScoreNames = unreadableScoreNames == null ? List.of() : List.copyOf(unreadableScoreNames);
            undeclaredScoreNames = undeclaredScoreNames == null ? List.of() : List.copyOf(undeclaredScoreNames);
        }

        static ParsedFeedbackScores problem(ResponseProblem.Kind kind, String evidence, List<String> fields,
                int omittedFields) {
            return ParsedFeedbackScores.builder()
                    .problem(new ResponseProblem(kind, evidence, fields, omittedFields))
                    .build();
        }

        /**
         * 通过 {@code mapping} 重新映射每个分数名称，使规则日志和存储的分数使用用户配置的名称。
         * 测试套件路径在提示前会将每个模式名重写为 {@code assertion_N}，因此没有这个操作，
         * 日志会显示用户从未见过的分数名称。
         */
        public ParsedFeedbackScores withUserFacingNames(@NonNull Map<String, String> mapping) {
            if (mapping.isEmpty()) {
                return this;
            }
            UnaryOperator<String> userFacing = name -> mapping.getOrDefault(name, name);
            return ParsedFeedbackScores.builder()
                    .scores(scores.stream()
                            .map(item -> (FeedbackScoreBatchItem) item.toBuilder()
                                    .name(userFacing.apply(item.name()))
                                    .build())
                            .toList())
                    .nullScoreNames(nullScoreNames.stream().map(userFacing).toList())
                    .unreadableScoreNames(unreadableScoreNames.stream().map(userFacing).toList())
                    // 未声明的名称是评判器自己的，不在映射中，因此原样通过。
                    .undeclaredScoreNames(undeclaredScoreNames.stream().map(userFacing).toList())
                    .problem(problem == null
                            ? null
                            : problem.toBuilder()
                                    .fields(problem.fields().stream().map(userFacing).toList())
                                    .build())
                    .build();
        }
    }

    @Builder(toBuilder = true)
    public record ResponseProblem(@NonNull Kind kind, @NonNull String evidence, @NonNull List<String> fields,
            int omittedFields) {
        /** 快照字段名称：此记录是一次回答的诊断记录，不是实时视图。 */
        public ResponseProblem {
            fields = List.copyOf(fields);
        }

        public enum Kind {
            NOT_JSON,
            NOT_A_JSON_OBJECT,
            NO_SCORE_FIELDS
        }
    }

    public static void logSkippedNullScores(
            Logger userFacingLogger, ParsedFeedbackScores parsed, String entityType, Object entityId) {
        parsed.nullScoreNames().forEach(name -> userFacingLogger.info(
                "跳过分数 '{}'（{} '{}'），因为评判器返回了 null 值（视为不适用）",
                name, entityType, entityId));
    }

    /**
     * 将我们无法读取的评判器回答展示在规则日志上。没有它，一次失败的解析在用户看来就像一次成功运行：
     * 没有分数，也没有解释（OPIK-7354）。
     */
    public static void logResponseIssues(
            Logger userFacingLogger, ParsedFeedbackScores parsed, String entityType, Object entityId) {
        if (!parsed.unreadableScoreNames().isEmpty()) {
            userFacingLogger.warn(
                    "无法使用 {} 在 {} '{}' 上的分数值——预期为 {} 到 {} 之间的布尔值或数字",
                    renderNames(parsed.unreadableScoreNames(), 0), entityType, entityId,
                    ValidationUtils.MIN_FEEDBACK_SCORE_VALUE, ValidationUtils.MAX_FEEDBACK_SCORE_VALUE);
        }
        if (!parsed.undeclaredScoreNames().isEmpty()) {
            userFacingLogger.warn(
                    "忽略 {}（{} '{}'）——评判器为其打了分，但此规则未声明该名称",
                    renderNames(parsed.undeclaredScoreNames(), 0), entityType, entityId);
        }
        if (parsed.problem() != null) {
            userFacingLogger.warn("{} '{}' 未得到任何评分：{}", entityType, entityId,
                    describe(parsed.problem()));
        }
    }

    private static String describe(ResponseProblem problem) {
        return switch (problem.kind()) {
            case NOT_JSON -> "评判器的回答不是有效的 JSON（%s）".formatted(problem.evidence());
            case NOT_A_JSON_OBJECT -> "评判器的回答不是 JSON 对象（%s）"
                    .formatted(problem.evidence());
            case NO_SCORE_FIELDS -> ("评判器的回答没有任何预期的分数字段。其字段为 "
                    + "%s；预期为 { '<scoreName>': { 'score': <number|boolean>, 'reason': <string> } }")
                    .formatted(renderNames(problem.fields(), problem.omittedFields()));
        };
    }

    private static String sizeOf(String content) {
        return "%,d 个字符".formatted(content.length());
    }

    public static ParsedFeedbackScores toFeedbackScores(@NonNull ChatResponse chatResponse,
            List<LlmAsJudgeOutputSchema> schema) {
        var declaredSchemas = Objects.requireNonNullElse(schema, List.<LlmAsJudgeOutputSchema>of());
        var content = extractJson(chatResponse.aiMessage().text());
        JsonNode structuredResponse;
        try {
            structuredResponse = OBJECT_MAPPER.readTree(content);
            if (!structuredResponse.isObject()) {
                log.warn("评判器回答不是 JSON 对象：size='{}'", sizeOf(content));
                return ParsedFeedbackScores.problem(
                        ResponseProblem.Kind.NOT_A_JSON_OBJECT, sizeOf(content), List.of(), 0);
            }
        } catch (JsonProcessingException e) {
            log.warn("评判器回答不是有效的 JSON：size='{}' error='{}'", sizeOf(content),
                    e.getOriginalMessage());
            return ParsedFeedbackScores.problem(ResponseProblem.Kind.NOT_JSON, sizeOf(content), List.of(), 0);
        }
        // 每一轮仅在尚未识别出任何内容时运行，因此顺序即优先级。
        var collected = new CollectedScores(declaredSchemas);

        // 1. 我们要求的形态：评判器按规则声明的方式命名的每个分数。
        collected.collectDeclared(structuredResponse);

        // 2. 没有任何名称匹配——将扁平的 {"score": ...} 回答解读为唯一声明的分数。
        if (collected.foundNothing()) {
            collected.collectFlatSingleScore(structuredResponse);
        }

        // 3. 仍然没有——将单个不同名称的分数归因到唯一声明的分数。
        if (collected.foundNothing()) {
            collected.collectRenamedSingleScore(structuredResponse);
        }

        // 4. 没有任何一轮能理解该回答。
        if (collected.foundNothing()) {
            return noRecognisableScoreFields(structuredResponse, content);
        }
        return collected.toParsed();
    }

    /**
     * 将评判器的回答读取为分数，同时拥有归因各轮及其累积的状态。
     */
    private static final class CollectedScores {
        private final List<LlmAsJudgeOutputSchema> schema;
        private final Map<String, String> declaredNames;
        private final List<FeedbackScoreBatchItem> scores = new ArrayList<>();
        private final List<String> nullScoreNames = new ArrayList<>();
        private final List<String> unreadableScoreNames = new ArrayList<>();
        private final List<String> undeclaredScoreNames = new ArrayList<>();
        private final Set<String> claimedNames = new HashSet<>();

        CollectedScores(List<LlmAsJudgeOutputSchema> schemas) {
            this.schema = schemas.stream()
                    .filter(definition -> definition != null && StringUtils.isNotBlank(definition.name()))
                    .toList();
            this.declaredNames = this.schema.stream().collect(Collectors.toMap(
                    definition -> definition.name().toLowerCase(Locale.ROOT), LlmAsJudgeOutputSchema::name,
                    (first, dup) -> first));
        }

        /** 每个携带分数的顶层对象，按评判器书写它们的顺序。 */
        private static List<Map.Entry<String, JsonNode>> scoreCandidates(JsonNode structuredResponse) {
            return structuredResponse.properties().stream()
                    .filter(entry -> entry.getValue() != null && !entry.getValue().isMissingNode()
                            && entry.getValue().has(SCORE_FIELD_NAME))
                    .toList();
        }

        /**
         * 第一轮，也是唯一可能产生多个分数的一轮。名称匹配不区分大小写。
         */
        void collectDeclared(JsonNode structuredResponse) {
            for (var candidate : scoreCandidates(structuredResponse)) {
                var scoreName = candidate.getKey();
                // 空的 schema 不声明任何可匹配的内容，也不声明任何可能被劫持的内容。
                var declaredName = declaredNames.isEmpty()
                        ? scoreName
                        : declaredNames.get(scoreName.toLowerCase(Locale.ROOT));
                if (declaredName == null) {
                    log.debug("忽略未声明的分数字段：'{}'", sanitize(scoreName));
                    undeclaredScoreNames.add(scoreName);
                } else {
                    accept(declaredName, candidate.getValue());
                }
            }
        }

        /** 第二轮：扁平的 {@code {"score": ...}} 回答属于唯一声明的分数。 */
        void collectFlatSingleScore(JsonNode structuredResponse) {
            if (schema.size() != 1 || !structuredResponse.has(SCORE_FIELD_NAME)) {
                return;
            }
            log.debug("将扁平的单分数响应解读为分数：'{}'", schema.getFirst().name());
            accept(schema.getFirst().name(), structuredResponse);
        }

        /**
         * 第三轮：评判器将其唯一分数命名为规则未声明的名称（例如 {@code relevance_score}
         * 对应名为 {@code Relevance} 的 schema 条目）。只有一个声明分数时，它只能有一种含义。
         * 在扁平轮之后运行，因此散落的嵌套对象无法压过它。
         */
        void collectRenamedSingleScore(JsonNode structuredResponse) {
            var candidates = scoreCandidates(structuredResponse);
            // 多个候选：报告而不是猜测。
            if (schema.size() != 1 || candidates.size() != 1) {
                return;
            }
            var declaredName = schema.getFirst().name();
            var judgeName = candidates.getFirst().getKey();
            // 毕竟已归因，因此撤回第一轮为其记录的 "ignored" 备注。
            undeclaredScoreNames.remove(judgeName);
            log.debug("将重命名的分数归因到唯一声明的分数：'{}' -> '{}'",
                    sanitize(judgeName), sanitize(declaredName));
            accept(declaredName, candidates.getFirst().getValue());
        }

        void accept(String declaredName, JsonNode scoreNode) {
            if (StringUtils.isBlank(declaredName)) {
                log.warn("跳过规则以空名称声明的分数");
                return;
            }
            // 名称匹配不区分大小写，因此一个回答中的多个键可能声索同一个声明的分数。
            // 两者都会被插入并折叠为在时间戳上胜出的那一行，因此第一个获胜。
            if (!claimedNames.add(declaredName)) {
                log.debug("跳过多于一次声索的分数：'{}'", sanitize(declaredName));
                return;
            }
            var actualScore = scoreNode.path(SCORE_FIELD_NAME);
            if (actualScore.isNull()) {
                log.debug("跳过评判器返回为 null 的分数：'{}'", sanitize(declaredName));
                nullScoreNames.add(declaredName);
                return;
            }
            toScoreValue(actualScore).filter(OnlineScoringEngine::isStorable)
                    .map(OnlineScoringEngine::toStorableScale).ifPresentOrElse(
                            value -> scores.add(FeedbackScoreBatchItem.builder()
                                    .name(declaredName)
                                    .reason(extractReason(scoreNode))
                                    .source(ScoreSource.ONLINE_SCORING)
                                    .value(value)
                                    .build()),
                            () -> unreadableScoreNames.add(declaredName));
        }

        // 没有任何可用内容，也没有任何明确不适用——即该轮未识别出该形态。
        // 未声明的名称有意不计入：只包含这些名称的回答仍应进入扁平回退，
        // 若仍失败，则报告为没有可识别的分数字段。
        boolean foundNothing() {
            return scores.isEmpty() && nullScoreNames.isEmpty() && unreadableScoreNames.isEmpty();
        }

        ParsedFeedbackScores toParsed() {
            return ParsedFeedbackScores.builder()
                    .scores(scores)
                    .nullScoreNames(nullScoreNames)
                    .unreadableScoreNames(unreadableScoreNames)
                    .undeclaredScoreNames(undeclaredScoreNames)
                    .build();
        }
    }

    /** 评判器提供的名称列表在任何展示处如何展示——日志和用户消息保持一致。 */
    private static String renderNames(List<String> names, int omitted) {
        if (names.isEmpty()) {
            return "（无）";
        }
        var shown = names.stream()
                .map(name -> "'%s'".formatted(sanitize(name)))
                .collect(Collectors.joining(", "));
        return omitted == 0 ? shown : "%s 以及另外 %,d 个".formatted(shown, omitted);
    }

    /** 评判器选择的即将写入日志行的文本：换行符不得伪造条目，超大的值也不得淹没条目。 */
    private static String sanitize(String value) {
        var stripped = CONTROL_CHARS.matcher(value).replaceAll(" ");
        return stripped.length() <= MAX_LOGGED_VALUE_CHARS
                ? stripped
                : stripped.substring(0, MAX_LOGGED_VALUE_CHARS) + "…";
    }

    /**
     * {@code feedback_scores.value} 是 {@code Decimal(18, 9)}，ClickHouse 会静默丢弃多余的数字而不是拒绝它们，
     * 因此评判器以更高精度回答时，存储的数字会与评分所使用的数字不同。在此处四舍五入使两者一致；
     * 已在精度范围内的值原样返回，保持其精确表示。
     */
    private static BigDecimal toStorableScale(BigDecimal value) {
        return value.scale() > ValidationUtils.SCALE
                ? value.setScale(ValidationUtils.SCALE, RoundingMode.HALF_UP)
                : value;
    }

    /**
     * 接受 OPIK-7354 之前创建的规则仍然指示的扁平 {@code { "score": ... }} 形态，
     * 以及在 schema 命名的嵌套形态之上。仅限单分数 schema：扁平对象不携带名称，
     * 因此有多个分数时没有可归因的对象。
     */
    private static ParsedFeedbackScores noRecognisableScoreFields(JsonNode structuredResponse, String content) {
        // 在此处而非渲染处进行上限限制：数量来自评判器的回答，否则一次回复
        // 将决定此错误路径分配和携带多少内容。
        var topLevelKeys = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(structuredResponse.fieldNames(),
                        Spliterator.ORDERED | Spliterator.NONNULL),
                false)
                .limit(MAX_REPORTED_FIELD_NAMES)
                .toList();
        var omittedFields = structuredResponse.size() - topLevelKeys.size();
        log.warn("评判器回答没有可识别的分数字段：fields=\"{}\" size='{}'",
                renderNames(topLevelKeys, omittedFields), sizeOf(content));
        return ParsedFeedbackScores.problem(
                ResponseProblem.Kind.NO_SCORE_FIELDS, "", topLevelKeys, omittedFields);
    }

    /**
     * {@code decimalValue()} 对任何非数字节点都返回 {@code ZERO}，因此带引号的分数
     * （{@code "score": "0.8"}）过去会被存储为 0——这是错误的分数而非缺失的分数，
     * 且与真实的零无法区分（OPIK-7354）。带引号的数字和布尔值会被解析，因为评判器经常给它们加引号；
     * 其他任何内容都会产生空值，以便调用方报告。
     */
    private static Optional<BigDecimal> toScoreValue(JsonNode actualScore) {
        if (actualScore.isBoolean()) {
            return Optional.of(actualScore.asBoolean() ? BigDecimal.ONE : BigDecimal.ZERO);
        }
        if (actualScore.isNumber()) {
            return Optional.of(actualScore.decimalValue());
        }
        if (!actualScore.isTextual()) {
            return Optional.empty();
        }
        var text = actualScore.asText().trim();
        var asBoolean = Optional.ofNullable(BooleanUtils.toBooleanObject(text))
                .orElseGet(() -> PASS_FAIL_SCORES.get(text.toLowerCase(Locale.ROOT)));
        if (asBoolean != null) {
            return Optional.of(asBoolean ? BigDecimal.ONE : BigDecimal.ZERO);
        }
        try {
            return Optional.of(new BigDecimal(text));
        } catch (NumberFormatException e) {
            log.debug("分数值既不是数字也不是布尔值：'{}'", sanitize(text));
            return Optional.empty();
        }
    }

    /**
     * feedback-score 列为 {@code Decimal(18, 9)}。{@link FeedbackScoreBatchItem} 上的
     * {@code @DecimalMin}/{@code @DecimalMax} 仅对请求体生效，而此路径直接构建条目，
     * 因此超出范围的评判器值会到达插入操作并使整个批次失败——丢失其中所有分数而不只是这一个。
     * 改为报告为不可读。
     */
    private static boolean isStorable(BigDecimal value) {
        if (value.compareTo(MIN_SCORE_VALUE) >= 0 && value.compareTo(MAX_SCORE_VALUE) <= 0) {
            return true;
        }
        log.debug("分数值超出可存储范围：'{}'", value);
        return false;
    }

    /**
     * 内置模板要求 {@code "reason": ["..."]}，而对数组调用 {@code asText()} 会产生空字符串——
     * 静默丢弃解释。用逗号连接以匹配 UI 将多个原因合并到一个单元格的方式（{@code ReasonCell.tsx}）。
     */
    private static String extractReason(JsonNode scoreNode) {
        var reason = scoreNode.path(REASON_FIELD_NAME);
        if (!reason.isArray()) {
            return reason.asText();
        }
        return StreamSupport.stream(reason.spliterator(), false)
                // 对于对象或数组，asText() 为空，空值过滤器随后会将其丢弃，
                // 从而丢失评判器放入的任何内容。改为序列化这些内容，使原因保留其内容。
                .map(element -> element.isContainerNode() ? element.toString() : element.asText())
                .filter(StringUtils::isNotBlank)
                .collect(Collectors.joining(", "));
    }

    private static String extractJson(String response) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // 假设整个响应是原始 JSON
        return response.trim();
    }

    @AllArgsConstructor
    enum TraceSection {
        INPUT("input."),
        OUTPUT("output."),
        METADATA("metadata.");

        final String prefix;
    }

    @Builder(toBuilder = true)
    record MessageVariableMapping(
            TraceSection traceSection, String variableName, String jsonPath, String valueToReplace) {
    }

    /**
     * trace 和 span Python 评分器使用的共享 "evaluate → prepare → log" 包装器。
     * 消除了重复的样板代码：MDC 作用域、"Evaluating X 'id' sampled by rule 'name'" 入口日志、
     * "Sending X 'id' to Python evaluator: '<summary>'" 出口日志，以及重新抛出并记录错误的回退。
     * 调用方只提供真正不同的内容：实体标签（{@code "traceId"} / {@code "spanId"}）、id、规则名称，
     * 以及构建渲染后评估器输入的 supplier。
     *
     * <p>错误路径的日志被拆分：{@code userFacingLogger} 获得经过净化的单行日志
     * （不含 Throwable，因此内部类名/栈跟踪中的路径不会泄漏到用户可见的日志槽），
     * 而 {@code internalLogger}（评分器的 slf4j 日志器）获得完整栈跟踪。
     */
    public static Map<String, Object> logAndPrepareEvaluatorInput(
            @NonNull Logger userFacingLogger,
            @NonNull Logger internalLogger,
            @NonNull Map<String, String> mdc,
            @NonNull String entityLabel,
            @NonNull Object entityId,
            String ruleName,
            @NonNull Supplier<Map<String, Object>> dataSupplier) {
        try (var logContext = LogContextAware.wrapWithMdc(mdc)) {
            userFacingLogger.info("正在评估 {} '{}'，由规则 '{}' 采样", entityLabel, entityId, ruleName);
            try {
                Map<String, Object> data = dataSupplier.get();
                if (userFacingLogger.isInfoEnabled()) {
                    userFacingLogger.info("将 {} '{}' 发送到 Python 评估器：'{}'",
                            entityLabel, entityId, summarizeEvaluatorInput(data));
                }
                return data;
            } catch (Exception exception) {
                userFacingLogger.error("为 {} '{}' 准备 Python 请求时出错", entityLabel, entityId);
                internalLogger.error("为 {} '{}' 准备 Python 请求时出错",
                        entityLabel, entityId, exception);
                throw exception;
            }
        }
    }

    /**
     * 仅包含形态的渲染后 Python 评估器输入摘要，用于用户可见日志。
     * 值是渲染后的 trace/span 内容（input/output/metadata/spans）；逐字记录它们
     * 会让用户数据落入用户可见日志所馈送的任何下游槽，因此我们只展示键名和大小。
     */
    public static String summarizeEvaluatorInput(@NonNull Map<String, Object> data) {
        var parts = data.entrySet().stream()
                .map(e -> {
                    var v = e.getValue();
                    if (v instanceof List<?> list) {
                        return String.format("%s=list(%d)", e.getKey(), list.size());
                    }
                    var s = v == null ? "" : v.toString();
                    return String.format("%s=%dc", e.getKey(), s.length());
                })
                .collect(Collectors.joining(", "));
        return String.format("arguments=[%s]", parts);
    }

    /**
     * trace 和 thread 评分器上 {@code prepareEvaluation} catch 块共享的错误日志辅助方法。
     * 两个日志器是有意为之：
     * <ul>
     *   <li>{@code userFacingLogger} 携带仅含实体 id 的净化单行日志——
     *       不含 Throwable，因此栈跟踪（含内部类名/路径）不会泄漏到用户可见的日志槽。</li>
     *   <li>{@code internalLogger}（评分器的 slf4j 日志器）携带完整栈跟踪，
     *       使运维人员能够诊断实际出错的原因。</li>
     * </ul>
     * <p>参数 {@code idLabel}（{@code "traceId"} / {@code "threadId"}）和 {@code id}
     * 按后端日志约定使用单引号占位符格式化。
     */
    public static void logPreparingLlmRequestError(@NonNull Logger userFacingLogger,
            @NonNull Logger internalLogger, @NonNull String idLabel, @NonNull Object id,
            @NonNull Exception exception) {
        userFacingLogger.error("为 {} '{}' 准备 LLM 请求时出错", idLabel, id);
        internalLogger.error("为 {} '{}' 准备 LLM 请求时出错", idLabel, id, exception);
    }

    /**
     * 是否有任何模板消息声明了非字符串（多模态）内容。
     * 线程上的 agentic 工具渲染路径只将上下文变量替换进字符串内容——
     * 多模态模板（图片 / 音频 / 视频与文本并存）会被拒绝。
     * 调用方在此处检测并回退到内联路径，而不是抛出异常。
     */
    public static boolean hasMultimodalTemplate(@NonNull List<LlmAsJudgeMessage> templateMessages) {
        return templateMessages.stream().anyMatch(m -> !m.isStringContent());
    }

}
