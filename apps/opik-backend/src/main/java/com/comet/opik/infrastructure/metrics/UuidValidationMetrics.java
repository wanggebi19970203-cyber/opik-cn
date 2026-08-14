package com.comet.opik.infrastructure.metrics;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.UNKNOWN;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;

/**
 * {@code opik.ingestion.uuid_v7.rejected} 计数器的中央所有者 —— 为两条 UUIDv7 摄入校验路径
 * 定义该 instrument 及其标签词汇表的唯一位置。校验器
 * （{@link com.comet.opik.infrastructure.db.UuidV7TimestampValidator}）通过 {@link #recordAudit}
 * 记录审计（影子 / 仅日志）路径；异常映射器
 * （{@link com.comet.opik.api.error.InvalidUUIDExceptionMapper}）通过 {@link #recordReject}
 * 委托强制拒绝路径。
 * <p>
 * 这是 UUIDv7 重新启用行动（OPIK-7402）的新鲜度机制：当校验器处于
 * 审计模式时，超出窗口的 id 在这里按工作区计数但不被拒绝，因此有问题的客户端
 * （例如有缺陷的 LiteLLM 原生 Opik 集成）能在不破坏摄入的前提下实时暴露。
 * <p>
 * 两条路径都携带 {@code mode} 标签（{@link #MODE_AUDIT} 对 {@link #MODE_REJECT}），因此查询
 * 总能区分审计与拒绝。只有审计路径携带 {@code workspace_id}：拒绝路径运行在
 * 异常映射器中，那里没有传递请求作用域的工作区（在那里打标签是一项
 * 后续工作），而是携带 {@code http_route}。
 * <p>
 * {@code workspace_id} 标签受限于实际发出超出窗口 id 的客户端集合
 * （一个小群体），因此它不会像无条件的逐工作区标签那样膨胀指标基数。
 */
@Singleton
public class UuidValidationMetrics {

    private static final String METRIC_NAMESPACE = "opik.ingestion";
    private static final String COUNTER_DESCRIPTION = "Number of writes rejected because the id failed UUIDv7 ingestion validation";

    public static final String MODE_AUDIT = "audit";
    public static final String MODE_REJECT = "reject";

    public static final AttributeKey<String> MODE_KEY = AttributeKey.stringKey("mode");
    public static final AttributeKey<String> REASON_KEY = AttributeKey.stringKey("reason");
    public static final AttributeKey<String> RESOURCE_KEY = AttributeKey.stringKey("resource");
    public static final AttributeKey<String> HTTP_ROUTE_KEY = AttributeKey.stringKey("http_route");

    private final LongCounter rejectedCounter;

    public UuidValidationMetrics() {
        Meter meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.rejectedCounter = meter
                .counterBuilder("%s.uuid_v7.rejected".formatted(METRIC_NAMESPACE))
                .setDescription(COUNTER_DESCRIPTION)
                .build();
    }

    /**
     * 记录一次审计模式检测：一个本会被拒绝（超出窗口）但被放行的 id。
     * {@code reason} 是低基数的 {@link
     * com.comet.opik.api.error.InvalidUUIDException.Reason} 值，{@code resource} 是实体种类
     * （trace/span），{@code workspaceId} 是发出方工作区。全部回退到 {@code unknown}。
     */
    public void recordAudit(String reason, String resource, String workspaceId) {
        record(MODE_AUDIT, reason, Attributes.builder()
                .put(RESOURCE_KEY, StringUtils.defaultIfBlank(resource, UNKNOWN))
                .put(WORKSPACE_ID_KEY, StringUtils.defaultIfBlank(workspaceId, UNKNOWN)));
    }

    /**
     * 记录一次强制拒绝（HTTP 400），由 {@link
     * com.comet.opik.api.error.InvalidUUIDExceptionMapper} 委托。按 {@code reason} 和匹配的
     * {@code http_route} 打标签；{@code workspace_id} 被有意省略（该路径上未传递）。全部
     * 回退到 {@code unknown}。
     */
    public void recordReject(String reason, String httpRoute) {
        record(MODE_REJECT, reason, Attributes.builder()
                .put(HTTP_ROUTE_KEY, StringUtils.defaultIfBlank(httpRoute, UNKNOWN)));
    }

    /**
     * {@code opik.ingestion.uuid_v7.rejected} 计数器的共享组装：把公共的
     * {@code mode} 和 {@code reason} 标签加盖到调用方路径特定的属性上并递增。
     */
    private void record(String mode, String reason, AttributesBuilder attributes) {
        rejectedCounter.add(1, attributes
                .put(MODE_KEY, mode)
                .put(REASON_KEY, StringUtils.defaultIfBlank(reason, UNKNOWN))
                .build());
    }
}
