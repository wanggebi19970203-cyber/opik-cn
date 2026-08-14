package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.infrastructure.llm.StructuredOutputSupported;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Set;

/**
 * 此信息取自 <a href="https://docs.anthropic.com/en/docs/about-claude/models">Anthropic 文档</a>
 */
@Getter
@RequiredArgsConstructor
public enum AnthropicModelName implements StructuredOutputSupported {
    CLAUDE_SONNET_3_7("claude-3-7-sonnet-20250219"),
    CLAUDE_FABLE_5("claude-fable-5"),
    CLAUDE_HAIKU_4_5("claude-haiku-4-5-20251001"),
    CLAUDE_OPUS_4_1("claude-opus-4-1-20250805"),
    CLAUDE_OPUS_4("claude-opus-4-20250514"),
    CLAUDE_OPUS_4_5("claude-opus-4-5-20251101"),
    CLAUDE_OPUS_4_6("claude-opus-4-6"),
    CLAUDE_OPUS_4_7("claude-opus-4-7"),
    CLAUDE_OPUS_4_8("claude-opus-4-8"),
    CLAUDE_OPUS_5("claude-opus-5"),
    CLAUDE_SONNET_4("claude-sonnet-4-20250514"),
    CLAUDE_SONNET_4_5("claude-sonnet-4-5"),
    CLAUDE_SONNET_4_5_20250929("claude-sonnet-4-5-20250929"),
    CLAUDE_SONNET_4_6("claude-sonnet-4-6"),
    CLAUDE_SONNET_5("claude-sonnet-5");

    private final String value;

    /**
     * Anthropic 自适应思考模型的 API id，它们会以
     * {@code 400 "temperature is deprecated for this model"} 拒绝采样参数
     * （temperature/top_p/top_k）。
     *
     * <p><strong>为什么这是一个独立集合而不是逐常量标志。</strong>上面的枚举
     * 常量列表由周期性运行的“同步 provider 模型定义”任务重新生成
     * （例如 #7088、#7315、#7582）。该生成器把每个模型都输出为单参数的
     * {@code NAME("value")} 常量，没有额外的能力参数概念，因此
     * 逐常量标志（最初的 #7531 形态，{@code NAME("value", false)}）会在下一次同步时被静默
     * 丢弃 —— 这正是 #7582 回退了自适应思考的退出选择并重新打开问题 #7526 的
     * 方式。把这个能力保留在这里、放在生成的常量列表之外，
     * 就能让同步自由地增删/重排常量而不会破坏它。引用枚举
     * 常量（而不是复制字符串字面量）意味着一次重命名或移除
     * 被引用常量的同步会导致编译失败，而不是静默漂移。
     *
     * <p><strong>残余的手工步骤。</strong>这守护了下方 <em>已知</em> 自适应模型的
     * 重命名/移除；它无法把 <em>新同步</em> 的自适应模型强制纳入
     * 集合。当 Anthropic 发布新的自适应思考模型时，必须手工把它的 API id 加到
     * 这里（持久的修复是教会生成器直接输出该能力）。
     */
    private static final Set<String> ADAPTIVE_THINKING_MODEL_IDS = Set.of(
            CLAUDE_OPUS_4_7.value,
            CLAUDE_OPUS_4_8.value,
            CLAUDE_SONNET_5.value);

    /**
     * 模型是否接受采样参数（temperature/top_p/top_k）。自适应思考模型
     * 会以 400 拒绝它们并被显式退出。未知模型名 —— 包括
     * {@code null} 或空白 —— 默认为 {@code true}，这样仅注册在注册表中的 Anthropic 模型
     * （未在此枚举的）仍保留 temperature 支持。
     */
    public static boolean supportsSamplingParams(String modelName) {
        return StringUtils.isBlank(modelName) || !ADAPTIVE_THINKING_MODEL_IDS.contains(modelName);
    }

    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean isStructuredOutputSupported() {
        return false;
    }
}
