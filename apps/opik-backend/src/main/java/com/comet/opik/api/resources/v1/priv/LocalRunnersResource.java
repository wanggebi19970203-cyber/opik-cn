package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.runner.BridgeCommand;
import com.comet.opik.api.runner.BridgeCommandBatchResponse;
import com.comet.opik.api.runner.BridgeCommandNextRequest;
import com.comet.opik.api.runner.BridgeCommandResultRequest;
import com.comet.opik.api.runner.BridgeCommandSubmitRequest;
import com.comet.opik.api.runner.BridgeCommandSubmitResponse;
import com.comet.opik.api.runner.CreateLocalRunnerJobRequest;
import com.comet.opik.api.runner.LocalRunner;
import com.comet.opik.api.runner.LocalRunnerHeartbeatRequest;
import com.comet.opik.api.runner.LocalRunnerHeartbeatResponse;
import com.comet.opik.api.runner.LocalRunnerJob;
import com.comet.opik.api.runner.LocalRunnerJobResultRequest;
import com.comet.opik.api.runner.LocalRunnerLogEntry;
import com.comet.opik.api.runner.LocalRunnerStatus;
import com.comet.opik.domain.ConnectBridgeService;
import com.comet.opik.domain.EndpointJobService;
import com.comet.opik.domain.RunnerService;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Path("/v1/private/local-runners")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Runners", description = "本地运行器管理端点")
public class LocalRunnersResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull RunnerService runnerService;
    private final @NonNull EndpointJobService endpointJobService;
    private final @NonNull ConnectBridgeService connectBridgeService;
    private final @NonNull LocalRunnerConfig runnerConfig;
    private final @NonNull AnalyticsService analyticsService;

    @GET
    @Operation(operationId = "listRunners", summary = "列出本地运行器", description = "列出当前用户在工作区中拥有的本地运行器", responses = {
            @ApiResponse(responseCode = "200", description = "运行器列表", content = @Content(schema = @Schema(implementation = LocalRunner.LocalRunnerPage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response listRunners(
            @QueryParam("project_id") @NotNull UUID projectId,
            @QueryParam("status") LocalRunnerStatus status,
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("25") @Min(1) int size) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        LocalRunner.LocalRunnerPage runnerPage = runnerService.listRunners(workspaceId, userName, projectId, status,
                page, size);
        return Response.ok(runnerPage).build();
    }

    @GET
    @Path("/{runnerId}")
    @Operation(operationId = "getRunner", summary = "获取本地运行器", description = "获取单个本地运行器及其已注册的代理", responses = {
            @ApiResponse(responseCode = "200", description = "运行器详情", content = @Content(schema = @Schema(implementation = LocalRunner.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getRunner(@PathParam("runnerId") UUID runnerId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        LocalRunner runner = runnerService.getRunner(workspaceId, userName, runnerId);
        return Response.ok(runner).build();
    }

    @DELETE
    @Path("/{runnerId}")
    @Operation(operationId = "disconnectRunner", summary = "断开本地运行器", description = "断开本地运行器连接，终止其连接并使所有待处理任务失败", responses = {
            @ApiResponse(responseCode = "204", description = "无内容")})
    public Response disconnectRunner(@PathParam("runnerId") UUID runnerId) {
        // 仅在实际断开连接时发送事件；无操作调用（已被回收/不属于当前用户）
        // 会污染正常关闭与心跳回收的比较结果。
        runnerService.disconnectRunner(runnerId).ifPresent(type -> {
            String workspaceId = requestContext.get().getWorkspaceId();
            String userName = requestContext.get().getUserName();
            analyticsService.trackEvent("opik_runner_disconnected", Map.of(
                    "runner_id", runnerId.toString(),
                    "workspace_id", workspaceId,
                    "user_name", userName,
                    "runner_type", type.getValue(),
                    "reason", "stopped",
                    "date", Instant.now().toString()));
        });
        return Response.noContent().build();
    }

    @PUT
    @Path("/{runnerId}/agents")
    @RateLimited
    @Operation(operationId = "registerAgents", summary = "注册本地运行器代理", description = "注册或更新本地运行器的代理列表", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response registerAgents(@PathParam("runnerId") UUID runnerId,
            @RequestBody(description = "代理名称到代理定义的映射", content = @Content(schema = @Schema(implementation = Object.class))) @NotNull @Valid Map<String, LocalRunner.Agent> agents) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        endpointJobService.registerAgents(runnerId, workspaceId, userName, agents);
        return Response.noContent().build();
    }

    @PATCH
    @Path("/{runnerId}/checklist")
    @RateLimited
    @Operation(operationId = "patchChecklist", summary = "更新运行器检查清单", description = "部分更新运行器的检查清单（深度合并）", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response patchChecklist(@PathParam("runnerId") UUID runnerId,
            @RequestBody(content = @Content(schema = @Schema(implementation = Object.class))) @NotNull JsonNode updates) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        runnerService.patchChecklist(runnerId, workspaceId, userName, updates);
        return Response.noContent().build();
    }

    @POST
    @Path("/{runnerId}/heartbeats")
    @RateLimited
    @Operation(operationId = "heartbeat", summary = "本地运行器心跳", description = "刷新本地运行器心跳", responses = {
            @ApiResponse(responseCode = "200", description = "心跳响应", content = @Content(schema = @Schema(implementation = LocalRunnerHeartbeatResponse.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "410", description = "已失效", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response heartbeat(@PathParam("runnerId") UUID runnerId,
            @RequestBody(content = @Content(schema = @Schema(implementation = LocalRunnerHeartbeatRequest.class))) LocalRunnerHeartbeatRequest body) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        LocalRunnerHeartbeatResponse response = runnerService.heartbeat(runnerId, workspaceId, userName,
                body != null ? body.capabilities() : null);
        return Response.ok(response).build();
    }

    @POST
    @Path("/jobs")
    @RateLimited
    @Operation(operationId = "createJob", summary = "创建本地运行器任务", description = "创建本地运行器任务并将其加入执行队列", responses = {
            @ApiResponse(responseCode = "201", description = "任务已创建", headers = @Header(name = "Location", description = "任务的URI")),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response createJob(
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateLocalRunnerJobRequest.class))) @NotNull @Valid CreateLocalRunnerJobRequest request,
            @Context UriInfo uriInfo) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        UUID jobId = endpointJobService.createJob(workspaceId, userName, request);

        var uri = uriInfo.getAbsolutePathBuilder().path("/{jobId}").build(jobId);
        return Response.created(uri).build();
    }

    @GET
    @Path("/{runnerId}/jobs")
    @Operation(operationId = "listJobs", summary = "列出本地运行器任务", description = "列出本地运行器的任务", responses = {
            @ApiResponse(responseCode = "200", description = "任务列表", content = @Content(schema = @Schema(implementation = LocalRunnerJob.LocalRunnerJobPage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response listJobs(@PathParam("runnerId") UUID runnerId,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("page") @DefaultValue("0") @Min(0) int page,
            @QueryParam("size") @DefaultValue("25") @Min(1) int size) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        LocalRunnerJob.LocalRunnerJobPage jobPage = endpointJobService.listJobs(runnerId, projectId, workspaceId,
                userName, page, size);
        return Response.ok(jobPage).build();
    }

    @POST
    @Path("/{runnerId}/jobs/next")
    @Operation(operationId = "nextJob", summary = "获取下一个本地运行器任务", description = "长轮询获取下一个待处理的本地运行器任务", responses = {
            @ApiResponse(responseCode = "200", description = "任务可用，如果没有待处理任务则返回null", content = @Content(schema = @Schema(nullable = true, allOf = LocalRunnerJob.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public void nextJob(@PathParam("runnerId") UUID runnerId,
            @Suspended AsyncResponse asyncResponse) {
        long pollTimeoutSeconds = runnerConfig.getNextJobPollTimeout().toSeconds();
        long bufferSeconds = runnerConfig.getNextJobAsyncTimeoutBuffer().toSeconds();
        asyncResponse.setTimeout(pollTimeoutSeconds + bufferSeconds, TimeUnit.SECONDS);
        asyncResponse.setTimeoutHandler(
                ar -> ar.resume(Response.ok(NullNode.getInstance()).build()));
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        endpointJobService.nextJob(runnerId, workspaceId, userName)
                .map(job -> Response.ok(job).build())
                .defaultIfEmpty(Response.ok(NullNode.getInstance()).build())
                .subscribe(
                        asyncResponse::resume,
                        error -> {
                            if (error instanceof WebApplicationException wae) {
                                asyncResponse.resume(wae);
                            } else {
                                log.error("Error polling next job for runner='{}' workspace='{}'", runnerId,
                                        workspaceId, error);
                                asyncResponse.resume(Response.serverError().build());
                            }
                        });
    }

    @GET
    @Path("/jobs/{jobId}")
    @Operation(operationId = "getJob", summary = "获取本地运行器任务", description = "获取单个本地运行器任务的状态和结果", responses = {
            @ApiResponse(responseCode = "200", description = "任务详情", content = @Content(schema = @Schema(implementation = LocalRunnerJob.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getJob(@PathParam("jobId") UUID jobId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        LocalRunnerJob job = endpointJobService.getJob(jobId, workspaceId, userName);
        return Response.ok(job).build();
    }

    @GET
    @Path("/jobs/{jobId}/logs")
    @Operation(operationId = "getJobLogs", summary = "获取本地运行器任务日志", description = "获取本地运行器任务的日志条目", responses = {
            @ApiResponse(responseCode = "200", description = "日志条目", content = @Content(array = @ArraySchema(schema = @Schema(implementation = LocalRunnerLogEntry.class)))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getJobLogs(@PathParam("jobId") UUID jobId,
            @QueryParam("offset") @DefaultValue("0") @Min(0) int offset) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        List<LocalRunnerLogEntry> logs = endpointJobService.getJobLogs(jobId, offset, workspaceId, userName);
        return Response.ok(logs).build();
    }

    @POST
    @Path("/jobs/{jobId}/logs")
    @RateLimited
    @Operation(operationId = "appendJobLogs", summary = "追加本地运行器任务日志", description = "为正在运行的本地运行器任务追加日志条目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response appendLogs(@PathParam("jobId") UUID jobId,
            @RequestBody(content = @Content(array = @ArraySchema(schema = @Schema(implementation = LocalRunnerLogEntry.class)))) @NotNull @Valid List<@NotNull LocalRunnerLogEntry> entries) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        endpointJobService.appendLogs(jobId, workspaceId, userName, entries);
        return Response.noContent().build();
    }

    @POST
    @Path("/jobs/{jobId}/results")
    @Operation(operationId = "reportJobResult", summary = "报告本地运行器任务结果", description = "报告本地运行器任务完成或失败", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response reportResult(@PathParam("jobId") UUID jobId,
            @RequestBody(content = @Content(schema = @Schema(implementation = LocalRunnerJobResultRequest.class))) @NotNull @Valid LocalRunnerJobResultRequest result) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        endpointJobService.reportResult(jobId, workspaceId, userName, result);
        return Response.noContent().build();
    }

    @POST
    @Path("/jobs/{jobId}/cancel")
    @Operation(operationId = "cancelJob", summary = "取消本地运行器任务", description = "取消待处理或正在运行的本地运行器任务", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response cancelJob(@PathParam("jobId") UUID jobId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        endpointJobService.cancelJob(jobId, workspaceId, userName);
        return Response.noContent().build();
    }

    @POST
    @Path("/{runnerId}/bridge/commands")
    @RateLimited
    @Operation(operationId = "createBridgeCommand", summary = "提交桥接命令", description = "提交桥接命令供本地守护进程执行", responses = {
            @ApiResponse(responseCode = "201", description = "命令已提交", headers = @Header(name = "Location", description = "命令的URI"), content = @Content(schema = @Schema(implementation = BridgeCommandSubmitResponse.class))),
            @ApiResponse(responseCode = "404", description = "运行器未找到或未连接", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "运行器不支持桥接", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "请求过多", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response createBridgeCommand(@PathParam("runnerId") UUID runnerId,
            @RequestBody(content = @Content(schema = @Schema(implementation = BridgeCommandSubmitRequest.class))) @NotNull @Valid BridgeCommandSubmitRequest request,
            @Context UriInfo uriInfo) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        UUID commandId = connectBridgeService.createBridgeCommand(runnerId, workspaceId, userName, request);
        var uri = uriInfo.getBaseUriBuilder()
                .path("v1/private/local-runners/{runnerId}/bridge/commands/{commandId}")
                .build(runnerId, commandId);
        return Response.created(uri)
                .entity(BridgeCommandSubmitResponse.builder().commandId(commandId).build())
                .build();
    }

    @POST
    @Path("/{runnerId}/bridge/commands/next")
    @Operation(operationId = "nextBridgeCommands", summary = "轮询下一个桥接命令", description = "长轮询获取待处理的桥接命令（批量）", responses = {
            @ApiResponse(responseCode = "200", description = "命令批次", content = @Content(schema = @Schema(implementation = BridgeCommandBatchResponse.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public void nextBridgeCommands(@PathParam("runnerId") UUID runnerId,
            @Valid BridgeCommandNextRequest request,
            @Suspended AsyncResponse asyncResponse) {
        int maxCommands = request != null ? request.effectiveMaxCommands() : 10;
        long pollTimeoutSeconds = runnerConfig.getBridgePollTimeout().toSeconds();
        long bufferSeconds = runnerConfig.getBridgeAsyncTimeoutBuffer().toSeconds();
        asyncResponse.setTimeout(pollTimeoutSeconds + bufferSeconds, TimeUnit.SECONDS);
        asyncResponse.setTimeoutHandler(
                ar -> ar.resume(Response.ok(BridgeCommandBatchResponse.builder()
                        .commands(List.of()).build()).build()));
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        connectBridgeService.nextBridgeCommands(runnerId, workspaceId, userName, maxCommands)
                .map(batch -> Response.ok(batch).build())
                .subscribe(
                        asyncResponse::resume,
                        error -> {
                            if (error instanceof WebApplicationException wae) {
                                asyncResponse.resume(wae);
                            } else {
                                log.error("Error polling bridge commands for runner='{}' workspace='{}'", runnerId,
                                        workspaceId, error);
                                asyncResponse.resume(Response.serverError().build());
                            }
                        });
    }

    @POST
    @Path("/{runnerId}/bridge/commands/{commandId}/results")
    @Operation(operationId = "reportBridgeResult", summary = "报告桥接命令结果", description = "报告桥接命令完成或失败", responses = {
            @ApiResponse(responseCode = "204", description = "结果已接受"),
            @ApiResponse(responseCode = "404", description = "命令未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "已完成", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response reportBridgeResult(@PathParam("runnerId") UUID runnerId,
            @PathParam("commandId") UUID commandId,
            @RequestBody(content = @Content(schema = @Schema(implementation = BridgeCommandResultRequest.class))) @NotNull @Valid BridgeCommandResultRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        connectBridgeService.reportBridgeCommandResult(runnerId, workspaceId, userName, commandId, request);
        return Response.noContent().build();
    }

    @GET
    @Path("/{runnerId}/bridge/commands/{commandId}")
    @Operation(operationId = "getBridgeCommand", summary = "获取桥接命令", description = "获取桥接命令状态，可选择长轮询等待完成", responses = {
            @ApiResponse(responseCode = "200", description = "命令状态", content = @Content(schema = @Schema(implementation = BridgeCommand.class))),
            @ApiResponse(responseCode = "404", description = "命令未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public void getBridgeCommand(@PathParam("runnerId") UUID runnerId,
            @PathParam("commandId") UUID commandId,
            @QueryParam("wait") @DefaultValue("false") boolean wait,
            @QueryParam("timeout") @DefaultValue("30") int timeout,
            @Suspended AsyncResponse asyncResponse) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        if (!wait) {
            BridgeCommand command = connectBridgeService.getBridgeCommand(runnerId, workspaceId, userName, commandId);
            asyncResponse.resume(Response.ok(command).build());
            return;
        }

        int maxTimeout = (int) runnerConfig.getBridgeMaxCommandTimeout().toSeconds();
        int clampedTimeout = Math.min(Math.max(timeout, 1), maxTimeout);
        long bufferSeconds = runnerConfig.getBridgeAsyncTimeoutBuffer().toSeconds();
        asyncResponse.setTimeout(clampedTimeout + bufferSeconds, TimeUnit.SECONDS);
        asyncResponse.setTimeoutHandler(
                ar -> ar.resume(Response.status(Response.Status.REQUEST_TIMEOUT).build()));

        connectBridgeService.awaitBridgeCommand(runnerId, workspaceId, userName, commandId, clampedTimeout)
                .map(cmd -> Response.ok(cmd).build())
                .subscribe(
                        asyncResponse::resume,
                        error -> {
                            if (error instanceof WebApplicationException wae) {
                                asyncResponse.resume(wae);
                            } else {
                                log.error("Error awaiting bridge command='{}' runner='{}' workspace='{}'",
                                        commandId,
                                        runnerId, workspaceId, error);
                                asyncResponse.resume(Response.serverError().build());
                            }
                        });
    }
}
