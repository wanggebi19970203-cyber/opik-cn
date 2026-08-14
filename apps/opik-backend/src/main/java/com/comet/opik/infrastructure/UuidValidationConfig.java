package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.concurrent.TimeUnit;

/**
 * UUIDv7 摄入校验策略。
 *
 * <p>{@code enabled} 是一个运维级紧急开关：当它为 {@code false} 时，id 不会针对
 * {@code window} 进行校验。{@code window} 约束了内嵌时间戳与当前时间的距离，因此行为异常的
 * 客户端无法把一行落到遥远未来的分区中。
 *
 * <p>{@code auditOnly} 在 {@code enabled} 开关之上增加了第三种影子状态。它只在
 * {@code enabled} 为 {@code true} 时生效：校验器不再拒绝超出窗口的 id，而是
 * 记录拒绝率指标（按工作区打标签）并记录日志，但放行写入。这样就能在
 * 不破坏摄入的前提下实时暴露有问题的客户端。有效模式为：
 * {@code enabled=false} → 禁用（空操作）；{@code enabled=true, auditOnly=true} → 审计（计数 + 日志，
 * 不拒绝）；{@code enabled=true, auditOnly=false} → 拒绝（HTTP 400）。
 */
@Builder(toBuilder = true)
public record UuidValidationConfig(
        boolean enabled,
        boolean auditOnly,
        @NotNull @MinDuration(value = 12, unit = TimeUnit.HOURS) @MaxDuration(value = 45, unit = TimeUnit.DAYS) Duration window) {
}
