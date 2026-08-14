package com.comet.opik.api.metrics;

import com.comet.opik.api.TimeInterval;
import com.comet.opik.api.filter.SpanFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 跨项目聚合的工作区级 span 指标请求。当 {@code projectIds} 为空时，服务会在查询前
 * 将其解析为工作区中的每个项目，因此聚合始终针对一个明确的项目集合执行，
 * 而不是仅针对工作区的谓词；否则只使用给定的项目。这在 spans 主键上对小型和中型
 * 选择具有良好的剪枝效果，但它受限于工作区的项目数量，并不廉价：
 * 对于拥有大量项目的租户，{@code project_id IN (<all ids>)} 读取的数据颗粒与完整的工作区
 * span 扫描大致相同，因为 {@code id}/时间窗口无法在多个不相交的项目前缀上于主键级别进行剪枝。
 * {@code intervalEnd} 是可选的，服务端默认值为 "now"，与单项目指标端点保持一致。
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record WorkspaceSpanMetricRequest(
        Set<@NotNull UUID> projectIds,
        MetricType metricType,
        TimeInterval interval,
        @Valid BreakdownConfig breakdown,
        List<SpanFilter> filters,
        @NotNull Instant intervalStart,
        Instant intervalEnd) {

    @AssertTrue(message = "intervalStart must be before intervalEnd") public boolean isStartBeforeEnd() {
        return intervalEnd == null || intervalStart.isBefore(intervalEnd);
    }

    public boolean hasBreakdown() {
        return Optional.ofNullable(breakdown)
                .map(BreakdownQueryBuilder::isEnabled)
                .orElse(false);
    }
}
