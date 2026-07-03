package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import com.comet.opik.domain.evaluators.ManualEvaluationService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

/**
 * 用于手动触发追踪、线程和跨度上评估规则的REST资源。
 * 允许用户在不进行采样的情况下，直接从UI对特定实体运行在线评估指标。
 */
@Path("/v1/private/manual-evaluation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "Manual Evaluation", description = "追踪、线程和跨度的手动评估资源")
public class ManualEvaluationResource {

    private final @NonNull ManualEvaluationService manualEvaluationService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/traces")
    @Operation(operationId = "evaluateTraces", summary = "手动评估追踪", description = "手动触发选定追踪上的评估规则。绕过采样，将所有指定追踪加入评估队列。", responses = {
            @ApiResponse(responseCode = "202", description = "已接受 - 评估请求已成功加入队列", content = @Content(schema = @Schema(implementation = ManualEvaluationResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求错误 - 无效请求或缺少自动化规则", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到 - 项目未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.ONLINE_EVALUATION_RULE_UPDATE)
    public Response evaluateTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = ManualEvaluationRequest.class))) @Valid @NonNull ManualEvaluationRequest request) {

        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();

        log.info(
                "Manual evaluation request for '{}' traces with '{}' rules in project '{}', workspace '{}' by user '{}'",
                request.entityIds().size(), request.ruleIds().size(), request.projectId(), workspaceId, userName);

        var response = manualEvaluationService.evaluate(request, request.projectId(), workspaceId, userName)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Manual evaluation request accepted for '{}' traces in project '{}', workspace '{}'",
                request.entityIds().size(), request.projectId(), workspaceId);

        return Response.status(Response.Status.ACCEPTED)
                .entity(response)
                .build();
    }

    @POST
    @Path("/threads")
    @Operation(operationId = "evaluateThreads", summary = "手动评估线程", description = "手动触发选定线程上的评估规则。绕过采样，将所有指定线程加入评估队列。", responses = {
            @ApiResponse(responseCode = "202", description = "已接受 - 评估请求已成功加入队列", content = @Content(schema = @Schema(implementation = ManualEvaluationResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求错误 - 无效请求或缺少自动化规则", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到 - 项目未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.ONLINE_EVALUATION_RULE_UPDATE)
    public Response evaluateThreads(
            @RequestBody(content = @Content(schema = @Schema(implementation = ManualEvaluationRequest.class))) @Valid @NonNull ManualEvaluationRequest request) {

        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();

        log.info(
                "Manual evaluation request for '{}' threads with '{}' rules in project '{}', workspace '{}' by user '{}'",
                request.entityIds().size(), request.ruleIds().size(), request.projectId(), workspaceId, userName);

        var response = manualEvaluationService.evaluate(request, request.projectId(), workspaceId, userName)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Manual evaluation request accepted for '{}' threads in project '{}', workspace '{}'",
                request.entityIds().size(), request.projectId(), workspaceId);

        return Response.status(Response.Status.ACCEPTED)
                .entity(response)
                .build();
    }

    @POST
    @Path("/spans")
    @Operation(operationId = "evaluateSpans", summary = "手动评估跨度", description = "手动触发选定跨度上的评估规则。绕过采样，将所有指定跨度加入评估队列。", responses = {
            @ApiResponse(responseCode = "202", description = "已接受 - 评估请求已成功加入队列", content = @Content(schema = @Schema(implementation = ManualEvaluationResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求错误 - 无效请求或缺少自动化规则", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到 - 项目未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.ONLINE_EVALUATION_RULE_UPDATE)
    public Response evaluateSpans(
            @RequestBody(content = @Content(schema = @Schema(implementation = ManualEvaluationRequest.class))) @Valid @NonNull ManualEvaluationRequest request) {

        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();

        log.info(
                "Manual evaluation request for '{}' spans with '{}' rules in project '{}', workspace '{}' by user '{}'",
                request.entityIds().size(), request.ruleIds().size(), request.projectId(), workspaceId, userName);

        var response = manualEvaluationService.evaluate(request, request.projectId(), workspaceId, userName)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Manual evaluation request accepted for '{}' spans in project '{}', workspace '{}'",
                request.entityIds().size(), request.projectId(), workspaceId);

        return Response.status(Response.Status.ACCEPTED)
                .entity(response)
                .build();
    }
}
