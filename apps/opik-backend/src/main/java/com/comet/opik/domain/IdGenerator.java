package com.comet.opik.domain;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.api.error.InvalidUUIDException.Reason;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.UuidV7TimestampValidator;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

import java.time.Instant;
import java.util.UUID;

@ImplementedBy(IdGeneratorImpl.class)
public interface IdGenerator {

    UUID generateId();

    UUID generateId(Instant timestamp);

    UUID getTimeOrderedEpoch(long epochMilli);

    /**
     * 校验摄取进来的 {@code id}：它必须是版本 7 的 UUID
     * （{@link #validateVersion(UUID, String)}），其内嵌时间戳必须在配置的
     * 摄取时间窗口内，否则抛出 {@link InvalidUUIDException}。{@code workspaceId} 用于
     * 标注来源工作区以便观测；不可用时传入 {@link ErrorMetricsResolver#UNKNOWN}。
     */
    void validateId(UUID id, String resource, String workspaceId);

    Mono<UUID> validateIdAsync(UUID id, String resource);

    /**
     * 校验一个可能合法指向过去创建实体的 {@code id}：它必须是
     * 版本 7 的 UUID（{@link #validateVersion(UUID, String)}），且不得内嵌过于未来的时间戳
     * （否则会破坏分区布局 / 保留期的 ID 范围）。与 {@link #validateId} 不同，
     * 这里允许旧的 ID。
     *
     * <p>在更新路径（更新几个月前创建的长期实体是合法的）以及
     * 摄取时被引用/外键的 ID（例如 span 的 {@code traceId}：保留期按
     * {@code trace_id} 范围对 span 排序，假定它是按时间排序的 UUIDv7，而旧追踪上的迟到 span 很常见，
     * 因此旧的没问题，但非 v7 或未来日期的必须被拒绝）上都会使用。
     */
    void validateIdNotInFuture(UUID id, String resource);

    /**
     * {@link #validateIdNotInFuture(UUID, String)} 的带工作区属性的重载：知道
     * 请求工作区的调用方会传入它，以便归属审计指标。对于不携带工作区的调用方，2 参数形式默认使用
     * {@link ErrorMetricsResolver#UNKNOWN}。
     */
    void validateIdNotInFuture(UUID id, String resource, String workspaceId);

    Mono<UUID> validateIdNotInFutureAsync(UUID id, String resource);

    /**
     * {@link #validateIdNotInFuture} 的空安全变体，用于可选的被引用 ID（例如可选的
     * {@code projectId}，可能改为按名称解析）。当 {@code id} 为 null 时为空操作。
     */
    void validateIdNotInFutureIfPresent(UUID id, String resource);

    /** {@link #validateIdNotInFutureIfPresent(UUID, String)} 的带工作区属性的重载。 */
    void validateIdNotInFutureIfPresent(UUID id, String resource, String workspaceId);

    Mono<UUID> validateIdNotInFutureIfPresentAsync(UUID id, String resource);

    static Mono<UUID> validateVersionAsync(@NonNull UUID id, String resource) {
        return Mono.fromCallable(() -> {
            validateVersion(id, resource);
            return id;
        });
    }

    static void validateVersion(@NonNull UUID id, String resource) {
        if (id.version() != 7)
            throw new InvalidUUIDException(Reason.NOT_V7, "%s id must be a version 7 UUID".formatted(resource));
    }
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class IdGeneratorImpl implements IdGenerator {

    private static final TimeBasedEpochGenerator UUID_GENERATOR = Generators.timeBasedEpochGenerator();

    private final @NonNull UuidV7TimestampValidator uuidV7TimestampValidator;

    @Override
    public UUID generateId() {
        return UUID_GENERATOR.generate();
    }

    @Override
    public UUID generateId(@NonNull Instant timestamp) {
        return getTimeOrderedEpoch(timestamp.toEpochMilli());
    }

    @Override
    public UUID getTimeOrderedEpoch(long epochMilli) {
        return UUID_GENERATOR.construct(epochMilli);
    }

    @Override
    public void validateId(@NonNull UUID id, String resource, String workspaceId) {
        IdGenerator.validateVersion(id, resource);
        uuidV7TimestampValidator.validate(id, resource, workspaceId);
    }

    @Override
    public Mono<UUID> validateIdAsync(@NonNull UUID id, String resource) {
        return Mono.deferContextual(ctx -> {
            validateId(id, resource, workspaceId(ctx));
            return Mono.just(id);
        });
    }

    @Override
    public void validateIdNotInFuture(@NonNull UUID id, String resource) {
        // 不携带工作区的调用方（大多数配置实体引用）默认使用 UNKNOWN。
        validateIdNotInFuture(id, resource, ErrorMetricsResolver.UNKNOWN);
    }

    @Override
    public void validateIdNotInFuture(@NonNull UUID id, String resource, String workspaceId) {
        IdGenerator.validateVersion(id, resource);
        uuidV7TimestampValidator.validateNotInFuture(id, resource, workspaceId);
    }

    @Override
    public Mono<UUID> validateIdNotInFutureAsync(@NonNull UUID id, String resource) {
        return Mono.deferContextual(ctx -> {
            validateIdNotInFuture(id, resource, workspaceId(ctx));
            return Mono.just(id);
        });
    }

    /**
     * 从响应式上下文中读取 {@code workspace_id}（异步摄取路径将其放在
     * 那里，而不是请求作用域的线程本地变量中），回退到 {@link ErrorMetricsResolver#UNKNOWN}，
     * 以便审计指标始终有值。
     */
    private static String workspaceId(ContextView ctx) {
        return ctx.getOrDefault(RequestContext.WORKSPACE_ID, ErrorMetricsResolver.UNKNOWN);
    }

    @Override
    public void validateIdNotInFutureIfPresent(UUID id, String resource) {
        if (id != null) {
            validateIdNotInFuture(id, resource);
        }
    }

    @Override
    public void validateIdNotInFutureIfPresent(UUID id, String resource, String workspaceId) {
        if (id != null) {
            validateIdNotInFuture(id, resource, workspaceId);
        }
    }

    @Override
    public Mono<UUID> validateIdNotInFutureIfPresentAsync(UUID id, String resource) {
        return id == null ? Mono.empty() : validateIdNotInFutureAsync(id, resource);
    }
}
