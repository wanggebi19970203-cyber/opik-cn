package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueBatch;
import com.comet.opik.api.AnnotationQueueItemIds;
import com.comet.opik.api.AnnotationQueueSearchCriteria;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.LockResponse;
import com.comet.opik.api.LocksResponse;
import com.comet.opik.api.filter.AnnotationQueueFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.sorting.AnnotationQueueSortingFactory;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.AnnotationQueueItemLockService;
import com.comet.opik.domain.AnnotationQueueService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
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
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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

import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/annotation-queues")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Annotation Queues", description = "私有标注队列操作")
public class AnnotationQueuesResource {

    private final @NonNull AnnotationQueueService annotationQueueService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull AnnotationQueueSortingFactory sortingFactory;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull AnnotationQueueItemLockService lockService;

    @GET
    @Operation(operationId = "findAnnotationQueues", summary = "查找标注队列", description = "通过过滤和排序查找标注队列", responses = {
            @ApiResponse(responseCode = "200", description = "标注队列分页", content = @Content(schema = @Schema(implementation = AnnotationQueue.AnnotationQueuePage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_VIEW)
    @JsonView(AnnotationQueue.View.Public.class)
    public Response findAnnotationQueues(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "按名称过滤标注队列（部分匹配，不区分大小写）") String name,
            @QueryParam("filters") String filters,
            @QueryParam("sorting") String sorting) {

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        var annotationQueueFilters = filtersFactory.newFilters(filters, AnnotationQueueFilter.LIST_TYPE_REFERENCE);

        var searchCriteria = AnnotationQueueSearchCriteria.builder()
                .name(name)
                .filters(annotationQueueFilters)
                .sortingFields(sortingFields)
                .build();

        log.info("Finding annotation queues by '{}', page '{}', size '{}'", searchCriteria, page, size);
        var annotationQueues = annotationQueueService.find(page, size, searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found annotation queues by '{}', count '{}', page '{}', size '{}'",
                searchCriteria, annotationQueues.size(), page, size);
        return Response.ok().entity(annotationQueues).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getAnnotationQueueById", summary = "根据ID获取标注队列", description = "根据ID获取标注队列", responses = {
            @ApiResponse(responseCode = "200", description = "标注队列资源", content = @Content(schema = @Schema(implementation = AnnotationQueue.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_VIEW)
    @JsonView(AnnotationQueue.View.Public.class)
    public Response getAnnotationQueueById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding annotation queue by id '{}' on workspaceId '{}'", id, workspaceId);

        var annotationQueue = annotationQueueService.findById(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Found annotation queue by id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.ok().entity(annotationQueue).build();
    }

    @POST
    @Path("/batch")
    @Operation(operationId = "createAnnotationQueueBatch", summary = "批量创建标注队列", description = "为人工标注工作流创建多个标注队列", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_CREATE)
    public Response createAnnotationQueueBatch(
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueBatch.class))) @JsonView(AnnotationQueue.View.Write.class) @NotNull @Valid AnnotationQueueBatch batch) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating annotation queue batch with '{}' items, workspaceId '{}'",
                batch.annotationQueues().size(), workspaceId);

        var items = annotationQueueService.createBatch(batch)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Created annotation queue batch with '{}' items, workspaceId '{}'",
                items, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Operation(operationId = "createAnnotationQueue", summary = "创建标注队列", description = "为人工标注工作流创建标注队列", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/traces/{annotationId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_CREATE)
    public Response createAnnotationQueue(
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueue.class))) @JsonView(AnnotationQueue.View.Write.class) @NotNull @Valid AnnotationQueue request,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Creating annotation queue with id '{}' on workspaceId '{}'",
                request.id(), workspaceId);

        var id = annotationQueueService.create(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Created annotation queue with id '{}' on workspaceId '{}'",
                id, workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();

        return Response.created(uri).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateAnnotationQueue", summary = "更新标注队列", description = "根据ID更新标注队列", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_EDIT)
    @RateLimited
    public Response updateAnnotationQueue(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueUpdate.class))) @NotNull @Valid AnnotationQueueUpdate request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating annotation queue with id '{}' on workspaceId '{}'", id, workspaceId);

        annotationQueueService.update(id, request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Updated annotation queue with id '{}' on workspaceId '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteAnnotationQueueBatch", summary = "批量删除标注队列", description = "根据ID批量删除多个标注队列", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_DELETE)
    public Response deleteAnnotationQueueBatch(
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @NotNull @Valid BatchDelete batch) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting annotation queue batch with '{}' items, workspaceId '{}'",
                batch.ids().size(), workspaceId);

        var deletedCount = annotationQueueService.deleteBatch(batch.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Deleted annotation queue batch with '{}' items deleted, workspaceId '{}'",
                deletedCount, workspaceId);

        return Response.noContent().build();
    }

    //    标注队列项目

    @POST
    @Path("/{id}/items/add")
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_ANNOTATE)
    @Operation(operationId = "addItemsToAnnotationQueue", summary = "向标注队列添加项目", description = "向标注队列添加跟踪或线程", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response addItemsToAnnotationQueue(
            @PathParam("id") UUID queueId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueItemIds.class))) @Valid AnnotationQueueItemIds request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Adding '{}' items to annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        annotationQueueService.addItems(queueId, request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Added '{}' items to annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/items/delete")
    @Operation(operationId = "removeItemsFromAnnotationQueue", summary = "从标注队列移除项目", description = "从标注队列移除项目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_DELETE)
    public Response removeItemsFromAnnotationQueue(
            @PathParam("id") UUID queueId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnnotationQueueItemIds.class))) @Valid AnnotationQueueItemIds request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Removing '{}' items from annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        annotationQueueService.removeItems(queueId, request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Removed '{}' items from annotation queue with id '{}' on workspaceId '{}'",
                request.ids().size(), queueId, workspaceId);

        return Response.noContent().build();
    }

    //    标注队列项目锁

    @PUT
    @Path("/{queueId}/items/{itemId}/lock")
    @Operation(operationId = "lockAnnotationQueueItem", summary = "创建或延长标注队列项目锁", description = "为当前用户认领标注队列项目，或延长现有锁", responses = {
            @ApiResponse(responseCode = "200", description = "锁定结果", content = @Content(schema = @Schema(implementation = LockResponse.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_ANNOTATE)
    public Response lockItem(
            @PathParam("queueId") UUID queueId,
            @PathParam("itemId") UUID itemId) {

        var result = annotationQueueService.tryLockItem(queueId, itemId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok().entity(result).build();
    }

    @GET
    @Path("/{queueId}/locks")
    @Operation(operationId = "getAnnotationQueueLocks", summary = "获取标注队列的所有活跃锁", description = "返回队列中所有被锁定项目的锁定状态", responses = {
            @ApiResponse(responseCode = "200", description = "队列锁", content = @Content(schema = @Schema(implementation = LocksResponse.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.ANNOTATION_QUEUE_ANNOTATE)
    public Response getQueueLocks(@PathParam("queueId") UUID queueId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        var locks = lockService.getLocksForQueue(workspaceId, queueId)
                .block();

        return Response.ok()
                .entity(LocksResponse.builder().locks(locks).build())
                .build();
    }
}
