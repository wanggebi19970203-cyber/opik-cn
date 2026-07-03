package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SpendUserSortingFactory;
import com.comet.opik.api.spend.SpendBreakdownResponse;
import com.comet.opik.api.spend.SpendBreakdownsResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.api.spend.SpendSummaryResponse;
import com.comet.opik.api.spend.SpendUserPage;
import com.comet.opik.domain.AiSpendService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/ai-spend")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "AI Spend", description = "编程智能体花费分析")
public class AiSpendResource {

    private final @NonNull AiSpendService aiSpendService;
    private final @NonNull SpendUserSortingFactory spendUserSortingFactory;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/summary")
    @Operation(operationId = "getSpendSummary", summary = "获取花费概览", description = "获取编程智能体花费KPI概览", responses = {
            @ApiResponse(responseCode = "200", description = "花费概览", content = @Content(schema = @Schema(implementation = SpendSummaryResponse.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendSummary(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve spend summary on workspace_id '{}'", workspaceId);
        var response = aiSpendService.getSummary(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved spend summary on workspace_id '{}'", workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/composition")
    @Operation(operationId = "getSpendComposition", summary = "获取花费构成", description = "获取编程智能体令牌流量构成（桑基图）", responses = {
            @ApiResponse(responseCode = "200", description = "花费构成", content = @Content(schema = @Schema(implementation = SpendCompositionResponse.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendComposition(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve spend composition on workspace_id '{}'", workspaceId);
        var response = aiSpendService.getComposition(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved spend composition on workspace_id '{}'", workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/composition/{laneKey}/breakdown")
    @Operation(operationId = "getSpendLaneBreakdown", summary = "获取花费通道明细", description = "获取构成通道的逐项明细", responses = {
            @ApiResponse(responseCode = "200", description = "通道明细", content = @Content(schema = @Schema(implementation = SpendBreakdownResponse.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendLaneBreakdown(
            @PathParam("laneKey") String laneKey,
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve spend breakdown for lane '{}' on workspace_id '{}'", laneKey, workspaceId);
        var response = aiSpendService.getBreakdown(request, laneKey)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved spend breakdown for lane '{}' on workspace_id '{}'", laneKey, workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/composition/breakdowns")
    @Operation(operationId = "getSpendAllBreakdowns", summary = "获取所有花费通道明细", description = "一次请求获取所有构成通道的逐项明细", responses = {
            @ApiResponse(responseCode = "200", description = "通道明细", content = @Content(schema = @Schema(implementation = SpendBreakdownsResponse.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendAllBreakdowns(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve all spend breakdowns on workspace_id '{}'", workspaceId);
        var response = aiSpendService.getAllBreakdowns(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved all spend breakdowns on workspace_id '{}'", workspaceId);

        return Response.ok().entity(response).build();
    }

    @POST
    @Path("/users")
    @Operation(operationId = "getSpendUsers", summary = "获取花费用户排行榜", description = "获取每个用户的编程智能体花费", responses = {
            @ApiResponse(responseCode = "200", description = "用户排行榜", content = @Content(schema = @Schema(implementation = SpendUserPage.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getSpendUsers(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @Max(1000) @DefaultValue("25") int size,
            @QueryParam("sorting") String sorting,
            @QueryParam("name") @Schema(description = "按名称或邮箱过滤用户（部分匹配，不区分大小写）") String name,
            @RequestBody(content = @Content(schema = @Schema(implementation = SpendMetricRequest.class))) @NotNull @Valid SpendMetricRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Retrieve spend users on workspace_id '{}'", workspaceId);
        var sortingFields = spendUserSortingFactory.newSorting(sorting);
        var response = aiSpendService.getUsers(request, sortingFields, name, page, size)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Retrieved spend users on workspace_id '{}'", workspaceId);

        return Response.ok().entity(response).build();
    }
}
