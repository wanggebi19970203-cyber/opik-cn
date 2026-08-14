package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OnlineScoringConfig {

    public static final String PAYLOAD_FIELD = "message";

    @JsonProperty
    @NotBlank private String consumerGroupName;

    @JsonProperty
    @Min(1) private int consumerBatchSize;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration;

    @JsonProperty
    @Min(2) private int claimIntervalRatio;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration;

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries;

    @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen;

    @JsonProperty
    @Min(0) @Max(10_000) private int streamTrimLimit;

    @Valid @JsonProperty
    @NotEmpty private List<@NotNull @Valid StreamConfiguration> streams;

    /**
     * 预估 token 阈值，超过该阈值时 LLM-as-judge 在线评分器会走 agentic-tools 路径
     * （read/jq/search 工具）。按 128 K token 模型窗口设定；在更大的窗口上可调高，
     * 以便让更多规则留在更便宜的内联路径上。
     *
     * <p>下限为 1000 token——低于该值，任何非平凡的 trace 都会切到 tools 路径，因此一个打错的环境变量
     * （例如 {@code =1}）会把整个在线评分集群悄然放到 agentic 路径上。Dropwizard 会在启动时快速失败。
     *
     * <p>使用字段初始化器（而不是 {@code @Builder.Default}），使默认值在 Dropwizard 的 YAML 反序列化
     * （无参构造函数）期间也生效，而不仅仅通过构建器。
     */
    @JsonProperty
    @Min(1000) private int agenticToolsThresholdTokens = 50_000;

    /**
     * {@code estimateTraceContextTokens} 使用的每 token 字符数比例，用于把 {@code {trace, spans}}
     * 序列化后的 JSON 长度换算为 token 估算。4 是被广泛引用的自然语言英语近似值；随机/代码内容更接近 2，
     * 但 agentic-tools 阈值本身留有裕量，因此这并非精度关键。可配置，让运维人员能针对偏向代码/JSON 的
     * 工作负载进行调优（比例越低 = 估算越悲观 = 越早切换到 agentic-tools 路径）。
     */
    @JsonProperty
    @Min(1) private int agenticToolsCharsPerToken = 4;

    /**
     * 单次评判调用中可作为多模态内容注入的附件数据总字节上限。每个注入的附件在每一轮后续工具调用以及
     * 最终结构化重发时都会被重新发送，因此成本随轮数成倍增长。50 MB（默认）足以容纳多张高分辨率图片而
     * 不撑爆上下文；在内存受限的部署中可调低。
     *
     * <p>下限为 1 MB——低于该值几乎没有真实附件能容纳，因此打错的环境变量会悄然禁用附件注入而不是快速
     * 失败。Dropwizard 会在启动时捕获该违规。
     */
    @JsonProperty
    @Min(1_048_576) private long agenticToolsMaxInjectedBytes = 50L * 1024 * 1024;

    /**
     * 用于确定内联 vs agentic-tools 路由决策的 trace-thread span 预加载的堆硬上限（MB）。Span 在该上限内
     * 流式处理；一旦越过上限，线程就路由到 agentic-tools 路径（按需逐 trace 钻取），因此非常大的线程绝不会
     * 被完整缓冲。低于上限时，有界 span 列表会被保留给内联/增强路径。参见 OPIK-7454。
     *
     * <p>加以边界约束以保持估算有意义且堆使用可预测：至少 1 MB（低于该值几乎任何线程都会被当作超大），
     * 至多 512 MB。
     */
    @JsonProperty
    @Min(1) @Max(512) private int agenticToolsMaxPreloadMb;

    /**
     * 在线评分期间 {@code get_attachment} agentic 工具生成的 S3 预签名下载 URL 的 TTL（秒）。该 URL 在单轮
     * 工具调用内被 LLM 提供商消费，因此 120 秒（默认）已绰绰有余；仅当你的提供商延迟异常高时才调高。
     *
     * <p>上限为 3600 秒——TTL 很长的预签名 URL 一旦泄漏会带来安全风险。
     */
    @JsonProperty
    @Min(1) @Max(3600) private int agenticToolsS3PresignTtlSeconds = 120;

    /**
     * agentic-tools / 结构注入路径的每变量替换上限（字符），由 trace 级和 span 级评分器共享。约 4 KB 字符
     * （约 1 K token）足够让小的 trace/span 输入/输出内联渲染（便宜，无需工具往返），又足够小，使超大实体
     * 不会撑爆上下文——agent 通过 {@code read} 工具获取其余部分，或者在无工具内联回退时直接截断该值。
     *
     * <p>下限为 500 字符，这样打错的环境变量不会把每个字段都截断成噪声。上限为 100 K 字符（约 25 K token）——
     * 超过该值单个字段会占据提示词预算的主导，因此想要更高的运维人员应改而调高 agentic-tools 阈值。
     * 使用字段初始化器（而不是 {@code @Builder.Default}），使默认值在 YAML 反序列化期间也生效。
     */
    @JsonProperty
    @Min(500) @Max(100_000) private int maxPromptFieldChars = 4_000;

    /**
     * {@code {{trace}}} / {@code {{span}}} 结构的附件上传竞态容忍度：当实体体引用了附件但尚不可见持久化副本时，
     * （冷）附件查找会被重新订阅的次数。这是在评分入队时附件上传可能仍在途中的临时守卫；预先评估的分发延迟
     * （OPIK-7224）旨在取代它。{@code 0} 禁用重试（单次尽力读取）。参见
     * {@code OnlineScoringBaseScorer#listAttachmentsToleratingUploadRace}。
     */
    @JsonProperty
    @Min(0) @Max(20) private int attachmentFetchMaxRetries = 5;

    /**
     * 附件查找重试之间的延迟（参见 {@link #attachmentFetchMaxRetries}）。最坏情况下增加的延迟约为
     * {@code attachmentFetchMaxRetries × 本值}，且只针对确实期望附件的实体。字段初始化器提供默认值，
     * 使该键在 YAML 中可选。
     */
    @Valid @JsonProperty
    @NotNull @MinDuration(value = 50, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.SECONDS)
    private Duration attachmentFetchRetryDelay = Duration.milliseconds(300);

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamConfiguration {
        @JsonProperty
        @NotBlank private String scorer;

        @JsonProperty
        @NotBlank private String streamName;

        @JsonProperty
        @NotBlank private String codec;

        @JsonProperty
        @Min(1) private Integer consumerBatchSize;

        @Valid @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        private Duration poolingInterval;

        @Valid @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
        private Duration longPollingDuration;

        @JsonProperty
        @Min(2) private Integer claimIntervalRatio;

        @Valid @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        private Duration pendingMessageDuration;

        @JsonProperty
        @Min(1) @Max(10) private Integer maxRetries;

        @JsonProperty
        @Min(1000) @Max(10_000_000) private Integer streamMaxLen;

        @JsonProperty
        @Min(0) @Max(10_000) private Integer streamTrimLimit;
    }
}
