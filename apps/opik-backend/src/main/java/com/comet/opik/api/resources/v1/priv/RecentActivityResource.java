package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.RecentActivity;
import com.comet.opik.domain.RecentActivityService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/projects/{projectId}/activities")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "项目近期活动")
public class RecentActivityResource {

    private final @NonNull RecentActivityService recentActivityService;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "getRecentActivity", summary = "获取项目近期活动", description = "返回项目中所有实体类型的最近活动条目，按日期降序排列。", responses = {
            @ApiResponse(responseCode = "200", description = "近期活动分页", content = @Content(schema = @Schema(implementation = RecentActivity.RecentActivityPage.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "500", description = "服务器内部错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    @JsonView(RecentActivity.View.Public.class)
    public Response getRecentActivity(
            @PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @Max(100) @DefaultValue("10") int size) {

        var activity = recentActivityService.getRecentActivity(projectId, page, size)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok(activity).build();
    }
}
