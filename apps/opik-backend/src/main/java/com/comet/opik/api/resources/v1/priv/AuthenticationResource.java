package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AuthDetailsHolder;
import com.comet.opik.api.WorkspaceNameHolder;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/private/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Check", description = "访问检查资源")
public class AuthenticationResource {

    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Operation(operationId = "checkAccess", summary = "检查用户对工作区的访问权限", description = "检查用户对工作区的访问权限", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "401", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "访问禁止", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response checkAccess(
            @RequestBody(content = @Content(schema = @Schema(implementation = AuthDetailsHolder.class))) @Valid AuthDetailsHolder authDetailsHolder) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("User '{}' has access to workspace_id '{}'", userName, workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("workspace")
    @Operation(operationId = "getWorkspaceName", summary = "用户默认工作区名称", description = "用户默认工作区名称", responses = {
            @ApiResponse(responseCode = "200", description = "认证资源", content = @Content(schema = @Schema(implementation = WorkspaceNameHolder.class))),
            @ApiResponse(responseCode = "401", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "403", description = "访问禁止", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getWorkspaceName() {
        String workspaceName = requestContext.get().getWorkspaceName();
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("User '{}' has workspaceName '{}', workspaceid '{}'", userName, workspaceName, workspaceId);

        return Response.ok().entity(WorkspaceNameHolder.builder()
                .workspaceName(workspaceName)
                .build())
                .build();
    }
}
