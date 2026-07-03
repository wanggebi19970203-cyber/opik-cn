package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.connect.ActivateRequest;
import com.comet.opik.api.connect.CreateSessionRequest;
import com.comet.opik.api.connect.CreateSessionResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.pairing.PairingService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
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
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
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
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Path("/v1/private/pairing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Pairing", description = "用于 `opik connect` 和 `opik endpoint` CLI 命令的配对会话")
public class PairingResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull PairingService pairingService;
    private final @NonNull AnalyticsService analyticsService;

    @POST
    @Path("/sessions")
    @RateLimited
    @Operation(operationId = "createPairingSession", summary = "创建配对会话", description = "注册一个短期配对会话，稍后由本地守护进程通过 HMAC 激活", responses = {
            @ApiResponse(responseCode = "201", description = "会话已创建", content = @Content(schema = @Schema(implementation = CreateSessionResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "项目未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "无法处理的实体", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "请求过多", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response createSession(
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateSessionRequest.class))) @NotNull @Valid CreateSessionRequest request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        CreateSessionResponse response = pairingService.create(workspaceId, userName, request);

        analyticsService.trackEvent("opik_connect_started", Map.of(
                "session_id", response.sessionId().toString(),
                "project_id", request.projectId().toString(),
                "workspace_id", workspaceId,
                "user_name", userName,
                "runner_type", request.type().getValue(),
                "date", Instant.now().toString()));

        return Response.status(Response.Status.CREATED).entity(response).build();
    }

    @POST
    @Path("/sessions/{sessionId}/activate")
    @RateLimited
    @Operation(operationId = "activatePairingSession", summary = "激活配对会话", description = "验证激活 HMAC 并将运行器状态切换为已连接", responses = {
            @ApiResponse(responseCode = "201", description = "会话已激活", headers = @Header(name = "Location", description = "运行器的 URI")),
            @ApiResponse(responseCode = "403", description = "HMAC 无效", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "会话未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "会话已激活", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "无法处理的实体", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "429", description = "请求过多", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response activate(
            @PathParam("sessionId") @NotNull UUID sessionId,
            @RequestBody(content = @Content(schema = @Schema(implementation = ActivateRequest.class))) @NotNull @Valid ActivateRequest request,
            @Context UriInfo uriInfo) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        try {
            PairingService.ActivationResult result = pairingService.activate(workspaceId, userName, sessionId, request);

            analyticsService.trackEvent("opik_connect_succeeded", Map.of(
                    "session_id", sessionId.toString(),
                    "workspace_id", workspaceId,
                    "user_name", userName,
                    "runner_type", result.runnerType().getValue(),
                    "date", Instant.now().toString()));

            URI location = uriInfo.getBaseUriBuilder()
                    .path("v1/private/local-runners/{runnerId}")
                    .build(result.runnerId());
            return Response.created(location).build();
        } catch (Exception e) {
            Map<String, String> props = new HashMap<>(Map.of(
                    "session_id", sessionId.toString(),
                    "workspace_id", workspaceId,
                    "user_name", userName,
                    "error", e.getClass().getSimpleName(),
                    "date", Instant.now().toString()));
            // 尽力而为：当会话不存在或在其他工作空间时为空。
            pairingService.peekSessionType(workspaceId, sessionId)
                    .ifPresent(type -> props.put("runner_type", type.getValue()));
            analyticsService.trackEvent("opik_connect_failed", props);
            throw e;
        }
    }
}
