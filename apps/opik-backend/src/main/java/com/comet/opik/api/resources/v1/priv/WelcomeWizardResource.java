package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.welcomewizard.WelcomeWizardSubmission;
import com.comet.opik.api.welcomewizard.WelcomeWizardTracking;
import com.comet.opik.domain.WelcomeWizardTrackingService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
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

@Path("/v1/private/welcome-wizard")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Welcome Wizard", description = "欢迎向导跟踪资源")
public class WelcomeWizardResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull WelcomeWizardTrackingService service;

    @GET
    @Operation(operationId = "getWelcomeWizardStatus", summary = "获取欢迎向导跟踪状态", description = "获取当前工作区的欢迎向导跟踪状态", responses = {
            @ApiResponse(responseCode = "200", description = "欢迎向导跟踪状态", content = @Content(schema = @Schema(implementation = WelcomeWizardTracking.class)))})
    public Response getStatus() {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取工作区 '{}' 的欢迎向导状态", workspaceId);

        var tracking = service.getTrackingStatus(workspaceId);

        log.info("工作区 '{}' 的欢迎向导状态: completed={}", workspaceId,
                tracking.completed());

        return Response.ok().entity(tracking).build();
    }

    @POST
    @Operation(operationId = "submitWelcomeWizard", summary = "提交欢迎向导", description = "提交欢迎向导及用户信息", responses = {
            @ApiResponse(responseCode = "204", description = "欢迎向导提交成功")})
    @RateLimited
    public Response submitWizard(
            @RequestBody(content = @Content(schema = @Schema(implementation = WelcomeWizardSubmission.class))) @Valid WelcomeWizardSubmission submission) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("为工作区 '{}' 提交欢迎向导", workspaceId);

        service.submitWizard(workspaceId, submission);

        log.info("工作区 '{}' 的欢迎向导已提交", workspaceId);

        return Response.noContent().build();
    }
}
