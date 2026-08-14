package com.comet.opik.infrastructure.db;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.error.InvalidUUIDException.Reason;
import com.comet.opik.domain.retention.RetentionUtils;
import com.comet.opik.infrastructure.UuidValidationConfig;
import com.comet.opik.infrastructure.metrics.UuidValidationMetrics;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 校验摄入的 {@code id} 所内嵌的 UUIDv7 时间戳是否落在
 * {@code [now() - window, now() + window]} 范围内。
 *
 * <p>内嵌时间戳正是 ClickHouse 用来计算分区的时间戳，因此一行声称
 * 遥远未来时间戳的数据会落进保留策略永远触及不到的分区，并破坏
 * 分区布局。在摄入时约束时间戳能让有问题的客户端表现为
 * HTTP 400，而不是静默地破坏分区。
 *
 * <p>该校验有意对时间戳位不区分版本：无论 UUID 版本如何，它都检查高 48
 * 位，因为每次写入时这些位都决定分区放置位置。
 *
 * <p>三种模式，派生自 {@link UuidValidationConfig}：
 * <ul>
 *   <li><b>disabled</b>（{@code enabled=false}）：空操作的紧急开关，不检查 id。</li>
 *   <li><b>reject</b>（{@code enabled=true, auditOnly=false}）：超出窗口的 id 通过
 *   {@link InvalidUUIDException}（HTTP 400）被拒绝；拒绝率指标由其
 *   {@link com.comet.opik.api.error.InvalidUUIDExceptionMapper} 记录。</li>
 *   <li><b>audit</b>（{@code enabled=true, auditOnly=true}）：超出窗口的 id 被计数
 *   （{@link UuidValidationMetrics}，按工作区打标签）并记录日志，但不被拒绝 —— 这是在不破坏摄入的
 *   前提下暴露有问题的客户端的影子 / 仅日志模式（OPIK-7402）。</li>
 * </ul>
 */
@Slf4j
@Singleton
public class UuidV7TimestampValidator {

    private final boolean enabled;
    private final boolean auditOnly;
    private final Duration window;
    private final UuidValidationMetrics metrics;

    @Inject
    public UuidV7TimestampValidator(@NonNull @Config("uuidValidation") UuidValidationConfig config,
            @NonNull UuidValidationMetrics metrics) {
        this.enabled = config.enabled();
        this.auditOnly = config.auditOnly();
        this.window = config.window().toJavaDuration();
        this.metrics = metrics;
    }

    /**
     * 在拒绝模式下拒绝（HTTP 400）一个内嵌时间戳超出窗口（太旧或太靠未来）
     * 的 id；在审计模式下计数并记录日志但不拒绝；当校验被禁用或 id 可接受时为空操作。
     * 用于创建路径。{@code resource}（trace/span）和 {@code workspaceId} 会附加到审计指标上。
     */
    public void validate(@NonNull UUID id, String resource, String workspaceId) {
        evaluate(id).ifPresent(rejection -> handle(rejection, resource, workspaceId));
    }

    /**
     * 与 {@link #validate} 类似，但仅在内嵌时间戳过于靠未来时才采取行动。旧的
     * id 会被接受，因此更新一个长生命周期的实体（例如几个月前创建的）永远不会被标记。
     * 用于更新路径。
     */
    public void validateNotInFuture(@NonNull UUID id, String resource, String workspaceId) {
        evaluate(id)
                .filter(rejection -> rejection.getLeft() == Reason.TOO_FAR_FUTURE)
                .ifPresent(rejection -> handle(rejection, resource, workspaceId));
    }

    /**
     * 纯校验决策：如果 id 的内嵌时间戳落在窗口之外，返回拒绝原因及其配对的时间戳；
     * 如果可接受（或校验被禁用），则返回空。
     */
    private Optional<Pair<Reason, Instant>> evaluate(UUID id) {
        if (!enabled) {
            return Optional.empty();
        }
        var timestamp = RetentionUtils.extractInstant(id);
        var now = Instant.now();
        if (timestamp.isBefore(now.minus(window))) {
            return Optional.of(Pair.of(Reason.TOO_OLD, timestamp));
        }
        if (timestamp.isAfter(now.plus(window))) {
            return Optional.of(Pair.of(Reason.TOO_FAR_FUTURE, timestamp));
        }
        return Optional.empty();
    }

    /**
     * 在审计模式下，记录按工作区的拒绝率指标并记录本会被拒绝的情况，然后
     * 放行写入。否则抛出 {@link InvalidUUIDException}（HTTP 400）。
     */
    private void handle(Pair<Reason, Instant> rejection, String resource, String workspaceId) {
        var reason = rejection.getLeft();
        var timestamp = rejection.getRight();
        if (auditOnly) {
            metrics.recordAudit(reason.getValue(), resource, workspaceId);
            // 保留一个固定、可搜索的前缀（“UUIDv7 audit: would-reject id ...”），并把
            // 可变字段追加在末尾，这样日志搜索按消息而不是按值匹配。
            log.info(
                    "UUIDv7 审计：将拒绝 id，内嵌时间戳 '{}' 超出窗口 '{}'，原因 '{}'，资源 '{}'，工作区 '{}'",
                    timestamp, window, reason.getValue(), resource, workspaceId);
            return;
        }
        throw new InvalidUUIDException(reason,
                "id with timestamp '%s' must be in the allowed ingestion window of '%s' around now, reason '%s'"
                        .formatted(timestamp, window, reason.getValue()));
    }
}
