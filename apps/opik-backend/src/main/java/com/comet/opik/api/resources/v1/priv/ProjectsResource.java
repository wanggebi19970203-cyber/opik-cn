package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Page;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectRetrieve;
import com.comet.opik.api.ProjectStatsSummary;
import com.comet.opik.api.ProjectUpdate;
import com.comet.opik.api.TokenUsageNames;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.metrics.KpiCardRequest;
import com.comet.opik.api.metrics.KpiCardResponse;
import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.SortingFactoryProjects;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.KpiCardCriteria;
import com.comet.opik.domain.KpiCardService;
import com.comet.opik.domain.ProjectCriteria;
import com.comet.opik.domain.ProjectMetricsService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.api.Project.ProjectPage;
import static com.comet.opik.api.Project.View;
import static com.comet.opik.domain.ProjectMetricsService.ERR_START_BEFORE_END;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/projects")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "项目相关资源")
public class ProjectsResource {

    private static final String PAGE_SIZE = "10";
    private final @NonNull ProjectService projectService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull SortingFactoryProjects sortingFactory;
    private final @NonNull ProjectMetricsService projectMetricsService;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull KpiCardService kpiCardService;

    @GET
    @Operation(operationId = "findProjects", summary = "查找项目", description = "查找项目列表", responses = {
            @ApiResponse(responseCode = "200", description = "项目资源", content = @Content(schema = @Schema(implementation = ProjectPage.class)))
    })
    @JsonView({View.Public.class})
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue(PAGE_SIZE) int size,
            @QueryParam("name") @Schema(description = "按名称过滤项目（部分匹配，不区分大小写）") String name,
            @QueryParam("sorting") String sorting) {

        var criteria = ProjectCriteria.builder()
                .projectName(name)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        log.info("根据条件 '{}' 在工作区 '{}' 查找项目", criteria, workspaceId);
        Page<Project> projectPage = projectService.find(page, size, criteria, sortingFields);
        log.info("根据条件 '{}' 找到项目，数量 '{}'，工作区 '{}'", criteria, projectPage.size(), workspaceId);

        return Response.ok().entity(projectPage).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getProjectById", summary = "根据ID获取项目", description = "根据ID获取项目", responses = {
            @ApiResponse(responseCode = "200", description = "项目资源", content = @Content(schema = @Schema(implementation = Project.class)))})
    @JsonView({View.Public.class})
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据ID '{}' 获取项目，工作区 '{}'", id, workspaceId);

        Project project = projectService.get(id);

        log.info("已获取项目，ID '{}'，工作区 '{}'", id, workspaceId);

        return Response.ok().entity(project).build();
    }

    @POST
    @Operation(operationId = "createProject", summary = "创建项目", description = "创建新项目", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/projects/{projectId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_CREATE)
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Project.class))) @JsonView(View.Write.class) @Valid Project project,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("创建项目，名称 '{}'，工作区 '{}'", project.name(), workspaceId);

        var projectId = projectService.create(project).id();

        log.info("已创建项目，名称 '{}'，ID '{}'，工作区 '{}'", project.name(), projectId,
                workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(projectId)).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("{id}")
    @Operation(operationId = "updateProject", summary = "根据ID更新项目", description = "根据ID更新项目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = ProjectUpdate.class))) @Valid ProjectUpdate project) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("更新项目，ID '{}'，工作区 '{}'", id, workspaceId);
        projectService.update(id, project);
        log.info("已更新项目，ID '{}'，工作区 '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteProjectById", summary = "根据ID删除项目", description = "根据ID删除项目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DELETE)
    public Response deleteById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("删除项目，ID '{}'，工作区 '{}'", id, workspaceId);
        projectService.delete(id);
        log.info("已删除项目，ID '{}'，工作区 '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/retrieve")
    @Operation(operationId = "retrieveProject", summary = "检索项目", description = "检索项目信息", responses = {
            @ApiResponse(responseCode = "200", description = "项目资源", content = @Content(schema = @Schema(implementation = Project.class))),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView({View.Detailed.class})
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response retrieveProject(
            @RequestBody(content = @Content(schema = @Schema(implementation = ProjectRetrieve.class))) @Valid ProjectRetrieve retrieve) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("根据名称 '{}' 检索项目，工作区 '{}'", retrieve.name(), workspaceId);
        Project project = projectService.retrieveByName(retrieve.name(), retrieve.includeStats());
        log.info("已检索项目，ID '{}'，名称 '{}'，工作区 '{}'", project.id(), retrieve.name(),
                workspaceId);
        return Response.ok().entity(project).build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteProjectsBatch", summary = "批量删除项目", description = "批量删除项目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DELETE)
    public Response deleteProjectsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("批量删除项目，数量 '{}'，工作区 '{}'", batchDelete.ids().size(), workspaceId);
        projectService.delete(batchDelete.ids());
        log.info("已批量删除项目，数量 '{}'，工作区 '{}'", batchDelete.ids().size(), workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/metrics")
    @Operation(operationId = "getProjectMetrics", summary = "获取项目指标", description = "获取项目的指定指标", responses = {
            @ApiResponse(responseCode = "200", description = "项目指标", content = @Content(schema = @Schema(implementation = ProjectMetricResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @JsonView({View.Public.class})
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getProjectMetrics(
            @PathParam("id") UUID projectId,
            @RequestBody(content = @Content(schema = @Schema(implementation = ProjectMetricRequest.class))) @Valid ProjectMetricRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        validate(request);

        log.info("获取项目指标，项目ID '{}'，工作区 '{}'，指标类型 '{}'", projectId,
                workspaceId, request.metricType());
        request = request.toBuilder()
                .spanFilters(filtersFactory.validateFilter(request.spanFilters()))
                .traceFilters(filtersFactory.validateFilter(request.traceFilters()))
                .threadFilters(filtersFactory.validateFilter(request.threadFilters()))
                .build();

        ProjectMetricResponse<? extends Number> response = projectMetricsService.getProjectMetrics(projectId, request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已获取项目指标，项目ID '{}'，工作区 '{}'，指标类型 '{}'", projectId,
                workspaceId, request.metricType());

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/{id}/kpi-cards")
    @Operation(operationId = "getProjectKpiCards", summary = "获取项目KPI卡片", description = "获取项目的KPI卡片指标", responses = {
            @ApiResponse(responseCode = "200", description = "KPI卡片指标", content = @Content(schema = @Schema(implementation = KpiCardResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getProjectKpiCards(
            @PathParam("id") UUID projectId,
            @RequestBody(content = @Content(schema = @Schema(implementation = KpiCardRequest.class))) @Valid KpiCardRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取KPI卡片，项目ID '{}'，工作区 '{}'，实体类型 '{}'", projectId,
                workspaceId, request.entityType());

        var filters = switch (request.entityType()) {
            case TRACES -> filtersFactory.newFilters(request.filters(), TraceFilter.LIST_TYPE_REFERENCE);
            case SPANS -> filtersFactory.newFilters(request.filters(), SpanFilter.LIST_TYPE_REFERENCE);
            case THREADS -> filtersFactory.newFilters(request.filters(), TraceThreadFilter.LIST_TYPE_REFERENCE);
        };

        var criteria = KpiCardCriteria.builder()
                .projectId(projectId)
                .entityType(request.entityType())
                .filters(filters)
                .intervalStart(request.intervalStart())
                .intervalEnd(request.intervalEnd() != null ? request.intervalEnd() : Instant.now())
                .build();

        KpiCardResponse response = kpiCardService.getKpiCards(criteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取KPI卡片，项目ID '{}'，工作区 '{}'，实体类型 '{}'", projectId,
                workspaceId, request.entityType());

        return Response.ok().entity(response).build();
    }

    @GET
    @Path("/feedback-scores/names")
    @Operation(operationId = "findFeedbackScoreNamesByProjectIds", summary = "根据项目ID查找反馈评分名称", description = "根据项目ID查找反馈评分名称", responses = {
            @ApiResponse(responseCode = "200", description = "反馈评分资源", content = @Content(schema = @Schema(implementation = FeedbackScoreNames.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response findFeedbackScoreNames(
            @QueryParam("project_ids") String projectIdsQueryParam) {

        var projectIds = Optional.ofNullable(projectIdsQueryParam)
                .map(ParamsValidator::getIds)
                .orElse(Collections.emptySet());

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据项目IDs '{}' 查找反馈评分名称，工作区 '{}'",
                projectIds, workspaceId);
        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getProjectsFeedbackScoreNames(projectIds)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已找到反馈评分名称 '{}'，项目IDs '{}'，工作区 '{}'",
                feedbackScoreNames.scores().size(), projectIds, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }

    private void validate(ProjectMetricRequest request) {
        // interval_end 是可选的，但如果提供了，interval_start 必须早于 interval_end
        if (request.intervalEnd() != null && !request.intervalStart().isBefore(request.intervalEnd())) {
            throw new BadRequestException(ERR_START_BEFORE_END);
        }
    }

    @GET
    @Path("/stats")
    @Operation(operationId = "getProjectStats", summary = "获取项目统计", description = "获取项目统计信息", responses = {
            @ApiResponse(responseCode = "200", description = "项目统计", content = @Content(schema = @Schema(implementation = ProjectStatsSummary.class))),
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getProjectStats(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue(PAGE_SIZE) int size,
            @QueryParam("name") @Schema(description = "按名称过滤项目（部分匹配，不区分大小写）") String name,
            @QueryParam("filters") String filters,
            @QueryParam("sorting") String sorting) {

        var traceFilters = filtersFactory.newFilters(filters, TraceFilter.LIST_TYPE_REFERENCE);

        var criteria = ProjectCriteria.builder()
                .projectName(name)
                .filters(traceFilters)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        log.info("根据条件 '{}' 获取项目统计，工作区 '{}'", criteria, workspaceId);
        ProjectStatsSummary projectStatisticsSummary = projectService.getStats(page, size, criteria, sortingFields);
        log.info("已获取项目统计，条件 '{}'，数量 '{}'，工作区 '{}'", criteria,
                projectStatisticsSummary.content().size(), workspaceId);

        return Response.ok().entity(projectStatisticsSummary).build();
    }

    @GET
    @Path("/{id}/token-usage/names")
    @Operation(operationId = "findTokenUsageNames", summary = "查找Token使用量名称", description = "查找Token使用量名称", responses = {
            @ApiResponse(responseCode = "200", description = "Token使用量名称资源", content = @Content(schema = @Schema(implementation = TokenUsageNames.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response findTokenUsageNames(@PathParam("id") UUID projectId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据项目ID '{}' 查找Token使用量名称，工作区 '{}'", projectId, workspaceId);
        List<String> tokenUsageNames = projectMetricsService.getProjectTokenUsageNames(workspaceId, projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已找到Token使用量名称 '{}'，项目ID '{}'，工作区 '{}'",
                tokenUsageNames.size(), projectId, workspaceId);

        return Response.ok(TokenUsageNames.builder().names(tokenUsageNames).build()).build();
    }

}
