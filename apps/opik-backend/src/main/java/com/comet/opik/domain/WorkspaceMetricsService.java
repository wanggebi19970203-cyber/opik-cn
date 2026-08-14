package com.comet.opik.domain;

import com.comet.opik.api.metrics.BreakdownQueryBuilder;
import com.comet.opik.api.metrics.MetricType;
import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.metrics.WorkspaceSpanMetricRequest;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(WorkspaceMetricsServiceImpl.class)
public interface WorkspaceMetricsService {
    @Deprecated
    Mono<WorkspaceMetricsSummaryResponse> getWorkspaceFeedbackScoresSummary(WorkspaceMetricsSummaryRequest request);

    @Deprecated
    Mono<WorkspaceMetricResponse> getWorkspaceFeedbackScores(WorkspaceMetricRequest request);

    Mono<WorkspaceMetricsSummaryResponse.Result> getWorkspaceCostsSummary(WorkspaceMetricsSummaryRequest request);

    Mono<WorkspaceMetricResponse> getWorkspaceCosts(WorkspaceMetricRequest request);

    Mono<WorkspaceMetricResponse> getWorkspaceSpanMetric(WorkspaceSpanMetricRequest request);

    Mono<List<String>> getWorkspaceTokenUsageNames(Set<UUID> projectIds);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
class WorkspaceMetricsServiceImpl implements WorkspaceMetricsService {
    private final @NonNull WorkspaceMetricsDAO workspaceMetricsDAO;
    private final @NonNull ProjectService projectService;

    @Override
    public Mono<WorkspaceMetricsSummaryResponse> getWorkspaceFeedbackScoresSummary(
            @NonNull WorkspaceMetricsSummaryRequest request) {
        return workspaceMetricsDAO.getFeedbackScoresSummary(request)
                .map(metrics -> WorkspaceMetricsSummaryResponse.builder()
                        .results(metrics)
                        .build());

    }

    @Override
    public Mono<WorkspaceMetricResponse> getWorkspaceFeedbackScores(@NonNull WorkspaceMetricRequest request) {
        return workspaceMetricsDAO.getFeedbackScoresDaily(request)
                .map(results -> WorkspaceMetricResponse.builder()
                        .results(results)
                        .build());
    }

    @Override
    public Mono<WorkspaceMetricsSummaryResponse.Result> getWorkspaceCostsSummary(
            @NonNull WorkspaceMetricsSummaryRequest request) {
        return workspaceMetricsDAO.getCostsSummary(request);
    }

    @Override
    public Mono<WorkspaceMetricResponse> getWorkspaceCosts(@NonNull WorkspaceMetricRequest request) {
        return workspaceMetricsDAO.getCostsDaily(request)
                .map(results -> WorkspaceMetricResponse.builder()
                        .results(results)
                        .build());
    }

    @Override
    public Mono<WorkspaceMetricResponse> getWorkspaceSpanMetric(@NonNull WorkspaceSpanMetricRequest request) {
        validate(request);
        return resolveProjectIds(request)
                .flatMap(resolved -> CollectionUtils.isEmpty(resolved.projectIds())
                        ? Mono.just(List.<WorkspaceMetricResponse.Result>of())
                        : dispatch(resolved))
                .map(results -> WorkspaceMetricResponse.builder()
                        .results(results)
                        .build());
    }

    // "All projects"（projectIds 为空）会被解析为显式的工作区项目 id 集合，使 DAO 始终
    // 查询有界的 `project_id IN (...)` 列表。对于中小规模的选取，这在 spans 主键上裁剪得很好，
    // 但它只以工作区的项目为界：对于项目很多的租户，IN(<all ids>)
    // 读取的颗粒数量与全工作区扫描大致相同，因为 id/时间窗口无法在许多不相交的项目前缀上
    // 做主键级裁剪。显式选取原样通过；没有任何项目的工作区则返回空。
    private Mono<WorkspaceSpanMetricRequest> resolveProjectIds(WorkspaceSpanMetricRequest request) {
        if (CollectionUtils.isNotEmpty(request.projectIds())) {
            return Mono.just(request);
        }
        return projectService.findProjectIdsByWorkspace()
                .map(projectIds -> request.toBuilder().projectIds(projectIds).build());
    }

    @Override
    public Mono<List<String>> getWorkspaceTokenUsageNames(Set<UUID> projectIds) {
        return resolveProjectIds(projectIds)
                .flatMap(resolved -> CollectionUtils.isEmpty(resolved)
                        ? Mono.just(List.<String>of())
                        : workspaceMetricsDAO.getWorkspaceTokenUsageNames(resolved));
    }

    // "All projects"（projectIds 为空）会被解析为显式的工作区项目 id 集合，使 DAO 始终
    // 查询有界的 `project_id IN (...)` 列表；显式选取原样通过。
    private Mono<Set<UUID>> resolveProjectIds(Set<UUID> projectIds) {
        return CollectionUtils.isNotEmpty(projectIds)
                ? Mono.just(projectIds)
                : projectService.findProjectIdsByWorkspace();
    }

    private Mono<List<WorkspaceMetricResponse.Result>> dispatch(WorkspaceSpanMetricRequest request) {
        return switch (request.metricType()) {
            case SPAN_TOKEN_USAGE -> workspaceMetricsDAO.getSpanTokenUsage(request);
            default -> throw new BadRequestException("Unsupported metric type '%s'".formatted(request.metricType()));
        };
    }

    private void validate(WorkspaceSpanMetricRequest request) {
        if (request.metricType() == null) {
            throw new BadRequestException("'metric_type' must be provided");
        }
        if (request.interval() == null) {
            throw new BadRequestException("'interval' must be provided");
        }
        if (!WorkspaceMetricsDAO.SUPPORTED_SPAN_METRICS.contains(request.metricType())) {
            throw new BadRequestException("Unsupported metric type '%s'. Supported: %s"
                    .formatted(request.metricType(), WorkspaceMetricsDAO.SUPPORTED_SPAN_METRICS));
        }
        if (request.hasBreakdown()) {
            try {
                BreakdownQueryBuilder.validate(request.breakdown(), request.metricType());
            } catch (IllegalArgumentException exception) {
                throw new BadRequestException(exception.getMessage());
            }
            if (request.metricType() == MetricType.SPAN_TOKEN_USAGE
                    && StringUtils.isBlank(request.breakdown().subMetric())) {
                throw new BadRequestException(
                        "'sub_metric' is required for token usage breakdown. It should be the usage key name (e.g., completion_tokens, prompt_tokens).");
            }
        }
    }
}
