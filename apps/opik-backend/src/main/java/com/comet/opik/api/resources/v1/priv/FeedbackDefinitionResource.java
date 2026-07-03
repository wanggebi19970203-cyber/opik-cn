package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.Page;
import com.comet.opik.domain.FeedbackDefinitionCriteria;
import com.comet.opik.domain.FeedbackDefinitionService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
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

import java.util.UUID;

import static com.comet.opik.domain.FeedbackDefinitionModel.FeedbackType;

@Path("/v1/private/feedback-definitions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Feedback-definitions", description = "反馈定义相关资源")
public class FeedbackDefinitionResource {

    private final @NonNull FeedbackDefinitionService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @GET
    @Operation(operationId = "findFeedbackDefinitions", summary = "查找反馈定义", description = "查找反馈定义", responses = {
            @ApiResponse(responseCode = "200", description = "反馈定义资源", content = @Content(schema = @Schema(implementation = FeedbackDefinition.FeedbackDefinitionPage.class)))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "按名称筛选反馈定义（部分匹配，不区分大小写）") String name,
            @QueryParam("type") FeedbackType type) {

        var criteria = FeedbackDefinitionCriteria.builder()
                .name(name)
                .type(type)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Find feedback definitions by '{}' on workspaceId '{}'", criteria, workspaceId);
        Page<FeedbackDefinition<?>> definitionPage = service.find(page, size, criteria);
        log.info("Found feedback definitions by '{}' on workspaceId '{}'", criteria, workspaceId);

        return Response.ok()
                .entity(definitionPage)
                .build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getFeedbackDefinitionById", summary = "根据ID获取反馈定义", description = "根据ID获取反馈定义", responses = {
            @ApiResponse(responseCode = "200", description = "反馈定义资源", content = @Content(schema = @Schema(implementation = FeedbackDefinition.class)))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response getById(@PathParam("id") @NotNull UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Get feedback definition by id '{}' on workspaceId '{}'", id, workspaceId);
        FeedbackDefinition<?> feedbackDefinition = service.get(id);
        log.info("Got feedback definition by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(feedbackDefinition).build();
    }

    @POST
    @Operation(operationId = "createFeedbackDefinition", summary = "创建反馈定义", description = "创建反馈定义", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/feedback-definitions/{feedbackId}", schema = @Schema(implementation = String.class))})
    })
    @RateLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackDefinition.class))) @JsonView({
                    FeedbackDefinition.View.Create.class}) @NotNull @Valid FeedbackDefinition<?> feedbackDefinition,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating feedback definition with id '{}', name '{}' on workspaceId '{}'", feedbackDefinition.getId(),
                feedbackDefinition.getName(), workspaceId);
        FeedbackDefinition<?> createdFeedbackDefinition = service.create(feedbackDefinition);
        log.info("Created feedback definition with id '{}', name '{}' on workspaceId '{}'",
                createdFeedbackDefinition.getId(), createdFeedbackDefinition.getName(), workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(createdFeedbackDefinition.getId()))
                .build();

        return Response.created(uri).build();
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updateFeedbackDefinition", summary = "根据ID更新反馈定义", description = "根据ID更新反馈定义", responses = {
            @ApiResponse(responseCode = "204", description = "无内容")
    })
    @RateLimited
    public Response update(final @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackDefinition.class))) @JsonView({
                    FeedbackDefinition.View.Update.class}) @NotNull @Valid FeedbackDefinition<?> feedbackDefinition) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating feedback definition with id '{}' on workspaceId '{}'", feedbackDefinition.getId(),
                workspaceId);
        service.update(id, feedbackDefinition);
        log.info("Updated feedback definition with id '{}' on workspaceId '{}'", feedbackDefinition.getId(),
                workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteFeedbackDefinitionById", summary = "根据ID删除反馈定义", description = "根据ID删除反馈定义", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    public Response deleteById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting feedback definition by id '{}' on workspaceId '{}'", id, workspaceId);
        service.delete(id);
        log.info("Deleted feedback definition by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteFeedbackDefinitionsBatch", summary = "批量删除反馈定义", description = "批量删除反馈定义", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    public Response deleteFeedbackDefinitionsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting feedback definitions by ids, count '{}', on workspace_id '{}'", batchDelete.ids().size(),
                workspaceId);
        service.delete(batchDelete.ids());
        log.info("Deleted feedback definitions by ids, count '{}', on workspace_id '{}'", batchDelete.ids().size(),
                workspaceId);
        return Response.noContent().build();
    }
}
