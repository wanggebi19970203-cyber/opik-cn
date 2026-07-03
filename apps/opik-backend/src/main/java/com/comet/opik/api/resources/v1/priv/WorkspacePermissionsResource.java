package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.WorkspaceUserPermissions;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.WorkspacePermissionsService;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/workspace-permissions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Workspace permissions", description = "工作区权限相关资源")
public class WorkspacePermissionsResource {

    private final @NonNull WorkspacePermissionsService workspacePermissionsService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "getWorkspaceUserPermissions", summary = "获取已认证用户的工作区权限", description = "获取已认证用户的工作区权限", responses = {
            @ApiResponse(responseCode = "200", description = "工作区权限", content = @Content(schema = @Schema(implementation = WorkspaceUserPermissions.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getWorkspaceUserPermissions() {
        String workspaceName = requestContext.get().getWorkspaceName();
        String apiKey = requestContext.get().getApiKey();

        log.info("获取工作区 '{}' 的权限", workspaceName);
        WorkspaceUserPermissions permissions = workspacePermissionsService.getPermissions(apiKey, workspaceName);
        log.info("已获取工作区 '{}' 的权限", workspaceName);

        return Response.ok(permissions).build();
    }
}