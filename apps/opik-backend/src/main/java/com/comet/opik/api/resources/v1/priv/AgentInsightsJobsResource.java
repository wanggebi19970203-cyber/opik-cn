package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentInsightsJob;
import com.comet.opik.api.AgentInsightsJobUpdate;
import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

@Path("/v1/private/agent-insights/jobs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Agent Insights Jobs", description = "按（工作区，项目）的智能体洞察报告配置")
public class AgentInsightsJobsResource {

    private final @NonNull AgentInsightsJobService service;

    @POST
    @Path("/{projectId}")
    @Operation(operationId = "createAgentInsightsJob", summary = "创建智能体洞察任务", description = "为项目创建智能体洞察任务。如果已存在则返回409。", responses = {
            @ApiResponse(responseCode = "201", description = "任务已创建", headers = @Header(name = "Location", description = "已创建任务的URI", schema = @Schema(type = "string")), content = @Content(schema = @Schema(implementation = AgentInsightsJob.class))),
            @ApiResponse(responseCode = "404", description = "项目未找到"),
            @ApiResponse(responseCode = "409", description = "任务已存在")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response create(@PathParam("projectId") @NotNull UUID projectId, @Context UriInfo uriInfo) {
        AgentInsightsJob job = service.create(projectId);

        return Response.created(uriInfo.getAbsolutePathBuilder().build()).entity(job).build();
    }

    @GET
    @Path("/{projectId}")
    @Operation(operationId = "getAgentInsightsJob", summary = "获取智能体洞察任务", description = "返回（工作区，项目）的智能体洞察任务，如果不存在则返回404。", responses = {
            @ApiResponse(responseCode = "200", description = "任务", content = @Content(schema = @Schema(implementation = AgentInsightsJob.class))),
            @ApiResponse(responseCode = "404", description = "任务未找到")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response get(@PathParam("projectId") @NotNull UUID projectId) {
        AgentInsightsJob job = service.getByProject(projectId);

        return Response.ok(job).build();
    }

    @PATCH
    @Path("/{projectId}")
    @Operation(operationId = "updateAgentInsightsJob", summary = "更新智能体洞察任务", description = "部分更新项目的智能体洞察任务（例如状态；不会删除）。返回更新后的任务，如果不存在则返回404。", responses = {
            @ApiResponse(responseCode = "200", description = "任务已更新", content = @Content(schema = @Schema(implementation = AgentInsightsJob.class))),
            @ApiResponse(responseCode = "404", description = "任务未找到")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response update(@PathParam("projectId") @NotNull UUID projectId,
            @Valid @NotNull AgentInsightsJobUpdate update) {
        AgentInsightsJob job = service.update(projectId, update.status());

        return Response.ok(job).build();
    }

    @POST
    @Path("/{projectId}/trigger")
    @Operation(operationId = "triggerAgentInsightsJob", summary = "触发智能体洞察任务", description = "为现有任务触发立即报告运行（过去24小时）。即发即忘；返回202。如果不存在则返回404。", responses = {
            @ApiResponse(responseCode = "202", description = "运行已接受"),
            @ApiResponse(responseCode = "404", description = "任务未找到")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response trigger(@PathParam("projectId") @NotNull UUID projectId) {
        service.triggerNow(projectId);

        return Response.accepted().build();
    }
}
