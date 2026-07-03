package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.BatchDeleteByProject;
import com.comet.opik.api.Comment;
import com.comet.opik.api.CreateCommentResponse;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.DeleteThreadFeedbackScores;
import com.comet.opik.api.DeleteTraceThreads;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchContainer;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Trace.TracePage;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceBatchUpdate;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadBatchIdentifier;
import com.comet.opik.api.TraceThreadBatchUpdate;
import com.comet.opik.api.TraceThreadIdentifier;
import com.comet.opik.api.TraceThreadSearchStreamRequest;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.TraceSortingFactory;
import com.comet.opik.api.sorting.TraceThreadSortingFactory;
import com.comet.opik.domain.CommentDAO;
import com.comet.opik.domain.CommentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.Streamer;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.TraceThreadQueryService;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.domain.workspaces.WorkspaceMetadataService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.infrastructure.usagelimit.UsageLimited;
import com.comet.opik.utils.RetryUtils;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.server.ChunkedOutput;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatch;
import static com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatchThread;
import static com.comet.opik.api.TraceThread.TraceThreadPage;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static com.comet.opik.utils.ValidationUtils.validateProjectNameAndProjectId;
import static com.comet.opik.utils.ValidationUtils.validateTimeRangeParameters;

/**
 * 追踪资源REST API端点
 * 提供追踪(Trace)相关的CRUD操作、反馈评分、评论和线程管理功能
 */
@Path("/v1/private/traces")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "Traces", description = "Trace related resources / 追踪相关资源")
public class TracesResource {

    private final @NonNull TraceService service;
    private final @NonNull TraceThreadQueryService traceThreadQueryService;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull CommentService commentService;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull WorkspaceMetadataService workspaceMetadataService;
    private final @NonNull TraceSortingFactory traceSortingFactory;
    private final @NonNull TraceThreadSortingFactory traceThreadSortingFactory;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull Streamer streamer;
    private final @NonNull ProjectService projectService;
    private final @NonNull TraceThreadService traceThreadService;
    private final @NonNull InstantToUUIDMapper instantToUUIDMapper;

    /**
     * 根据项目名称或项目ID获取追踪列表
     *
     * @param page 页码，从1开始
     * @param size 每页大小
     * @param projectName 项目名称
     * @param projectId 项目ID
     * @param filters 过滤条件JSON字符串
     * @param truncate 是否截断输入、输出和元数据以减小负载
     * @param stripAttachments 如果为true，返回附件引用如[file.png]；如果为false，下载并重新注入已剥离的附件
     * @param sorting 排序规则
     * @param exclude 排除的字段
     * @param search 跨追踪字段的全文搜索
     * @param startTime 过滤从此时间开始创建的追踪（ISO-8601格式）
     * @param endTime 过滤截止到此时间创建的追踪（ISO-8601格式）。如未提供，默认为当前时间。必须晚于from_time
     * @param annotationQueueId 过滤属于此标注队列的追踪，并将反馈评分/评论限定在该队列范围内
     * @return 追踪分页结果
     */
    @GET
    @Operation(operationId = "getTracesByProject", summary = "Get traces by project_name or project_id / 根据项目名称或ID获取追踪", description = "Get traces by project_name or project_id / 根据项目名称或项目ID获取追踪列表", responses = {
            @ApiResponse(responseCode = "200", description = "Trace resource / 追踪资源", content = @Content(schema = @Schema(implementation = TracePage.class)))})
    @JsonView(Trace.View.Public.class)
    @RateLimited(value = "getTraces:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response getTracesByProject(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("project_name") String projectName,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("filters") String filters,
            @QueryParam("truncate") @DefaultValue("false") @Schema(description = "Truncate input, output and metadata to slim payloads / 截断输入、输出和元数据以减小负载") boolean truncate,
            @QueryParam("strip_attachments") @DefaultValue("false") @Schema(description = "If true, returns attachment references like [file.png]; if false, downloads and reinjects stripped attachments / 如果为true返回附件引用，如果为false下载并重新注入附件") boolean stripAttachments,
            @QueryParam("sorting") String sorting,
            @QueryParam("exclude") String exclude,
            @QueryParam("search") @Schema(description = "Full-text search across trace fields / 跨追踪字段的全文搜索") String search,
            @QueryParam("from_time") @Schema(description = "Filter traces created from this time (ISO-8601 format). / 过滤从此时间开始创建的追踪（ISO-8601格式）") Instant startTime,
            @QueryParam("to_time") @Schema(description = "Filter traces created up to this time (ISO-8601 format). If not provided, defaults to current time. Must be after 'from_time'. / 过滤截止到此时间创建的追踪，如未提供默认为当前时间，必须晚于from_time") Instant endTime,
            @QueryParam("annotation_queue_id") @Schema(description = "Filter traces belonging to this annotation queue and scope feedback scores/comments to it / 过滤属于此标注队列的追踪并限定反馈评分/评论范围") UUID annotationQueueId) {

        validateProjectNameAndProjectId(projectName, projectId);
        validateTimeRangeParameters(startTime, endTime);
        var traceFilters = filtersFactory.newFilters(filters, TraceFilter.LIST_TYPE_REFERENCE);
        var sortingFields = traceSortingFactory.newSorting(sorting);

        var workspaceId = requestContext.get().getWorkspaceId();

        var metadata = workspaceMetadataService
                .getProjectMetadata(workspaceId, projectId, projectName)
                // 解析项目ID需要上下文
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        // 如果由于数据量原因禁用了动态排序，则清空排序字段
        if (!sortingFields.isEmpty() && metadata.cannotUseDynamicSorting()) {
            sortingFields = List.of();
        }

        var searchCriteria = TraceSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(traceFilters)
                .truncate(truncate)
                .stripAttachments(stripAttachments)
                .uuidFromTime(instantToUUIDMapper.toLowerBound(startTime))
                .uuidToTime(instantToUUIDMapper.toUpperBound(endTime))
                .exclude(ParamsValidator.get(exclude, Trace.TraceField.class, "exclude"))
                .sortingFields(sortingFields)
                .searchText(StringUtils.trimToNull(search))
                .annotationQueueId(annotationQueueId)
                .build();

        log.info("根据条件 '{}' 在工作区 '{}' 获取追踪", searchCriteria, workspaceId);

        TracePage tracePage = service.find(page, size, searchCriteria)
                .map(it -> {
                    // 如果由于数据量禁用了动态排序，则移除sortableBy字段
                    if (metadata.cannotUseDynamicSorting()) {
                        return it.toBuilder().sortableBy(List.of()).build();
                    }
                    return it;
                })
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("根据条件 '{}' 找到追踪，数量 '{}'，工作区 '{}'", searchCriteria, tracePage.size(),
                workspaceId);

        return Response.ok(tracePage).build();
    }

    /**
     * 搜索追踪（流式返回）
     *
     * @param request 追踪搜索流式请求
     * @return 追踪流式输出
     */
    @POST
    @Path("/search")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "searchTraces", summary = "Search traces / 搜索追踪", description = "Search traces / 搜索追踪并流式返回结果", responses = {
            @ApiResponse(responseCode = "200", description = "Traces stream or error during process / 追踪流或处理过程中的错误", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    Trace.class,
                    ErrorMessage.class
            }), maxItems = 2000))),
            @ApiResponse(responseCode = "400", description = "Bad Request / 请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized / 未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @JsonView(Trace.View.Public.class)
    @RateLimited(value = "searchTraces:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public ChunkedOutput<JsonNode> searchTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceSearchStreamRequest.class))) @NotNull @Valid TraceSearchStreamRequest request) {

        var workspaceId = requestContext.get().getWorkspaceId();

        validateProjectNameAndProjectId(request.projectName(), request.projectId());
        validateTimeRangeParameters(request.fromTime(), request.toTime());

        log.info("流式传输追踪搜索结果，条件 '{}'，工作区 '{}'", request, workspaceId);

        var searchCriteria = TraceSearchCriteria.builder()
                .lastReceivedId(request.lastRetrievedId())
                .projectName(request.projectName())
                .projectId(request.projectId())
                .filters(filtersFactory.validateFilter(request.filters()))
                .truncate(request.truncate())
                .stripAttachments(request.stripAttachments())
                .exclude(request.exclude())
                .sortingFields(List.of())
                .uuidFromTime(instantToUUIDMapper.toLowerBound(request.fromTime()))
                .uuidToTime(instantToUUIDMapper.toUpperBound(request.toTime()))
                .build();

        Visibility visibility = requestContext.get().getVisibility();
        String userName = requestContext.get().getUserName();

        UUID projectId = projectService.resolveProjectIdAndVerifyVisibility(request.projectId(), request.projectName())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        searchCriteria = searchCriteria.toBuilder().projectId(projectId).build();

        Flux<Trace> items = service.search(request.limit(), searchCriteria)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.VISIBILITY, Optional.ofNullable(visibility).orElse(Visibility.PRIVATE)));

        return streamer.getOutputStream(items,
                () -> log.info("已流式传输追踪搜索结果，条件 '{}'，工作区 '{}'", request, workspaceId));
    }

    /**
     * 根据ID获取单个追踪
     *
     * @param id 追踪ID
     * @param stripAttachments 如果为true，返回附件引用如[file.png]；如果为false，从S3下载并重新注入附件内容（默认false以保持向后兼容）
     * @return 追踪资源
     */
    @GET
    @Path("{id}")
    @Operation(operationId = "getTraceById", summary = "Get trace by id / 根据ID获取追踪", description = "Get trace by id / 根据ID获取单个追踪", responses = {
            @ApiResponse(responseCode = "200", description = "Trace resource / 追踪资源", content = @Content(schema = @Schema(implementation = Trace.class)))})
    @JsonView(Trace.View.Public.class)
    public Response getById(
            @PathParam("id") UUID id,
            @QueryParam("strip_attachments") @DefaultValue("false") @Schema(description = "If true, returns attachment references like [file.png]; if false, downloads and reinjects attachment content from S3 (default: false for backward compatibility) / 如果为true返回附件引用，如果为false从S3下载并重新注入附件内容（默认false以保持向后兼容）") boolean stripAttachments) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据ID '{}' 获取追踪，工作区 '{}'，stripAttachments '{}'", id,
                workspaceId, stripAttachments);

        Trace trace = service.get(id, stripAttachments)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        // 验证项目可见性
        projectService.get(trace.projectId());

        log.info("已获取追踪 '{}'，项目ID '{}'，工作区 '{}'", trace.id(), trace.projectId(),
                workspaceId);

        return Response.ok(trace).build();
    }

    /**
     * 创建追踪
     *
     * @param trace 追踪对象
     * @param uriInfo URI信息
     * @return 201 Created，包含Location头
     */
    @POST
    @Operation(operationId = "createTrace", summary = "Create trace / 创建追踪", description = "Create trace / 创建新的追踪记录", responses = {
            @ApiResponse(responseCode = "201", description = "Created / 已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/traces/{traceId}", schema = @Schema(implementation = String.class))})})
    @RateLimited(value = RateLimited.SINGLE_TRACING_OPS
            + ":{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    @UsageLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Trace.class))) @JsonView(Trace.View.Write.class) @NotNull @Valid Trace trace,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("创建追踪，ID '{}'，项目名称 '{}'，工作区 '{}'",
                trace.id(), trace.projectName(), workspaceId);

        var id = service.create(trace)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已创建追踪，ID '{}'，项目名称 '{}'，工作区 '{}'",
                id, trace.projectName(), workspaceId);

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();

        return Response.created(uri).build();
    }

    /**
     * 批量创建追踪
     *
     * @param traces 追踪批量对象
     * @return 204 No Content
     */
    @POST
    @Path("/batch")
    @Operation(operationId = "createTraces", summary = "Create traces / 批量创建追踪", description = "Create traces / 批量创建追踪记录", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RateLimited
    @UsageLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response createTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceBatch.class))) @JsonView(Trace.View.Write.class) @NotNull @Valid TraceBatch traces) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("批量创建追踪，数量 '{}'，工作区 '{}'", traces.traces().size(), workspaceId);
        service.create(traces)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已批量创建追踪，数量 '{}'，工作区 '{}'", traces.traces().size(), workspaceId);
        return Response.noContent().build();
    }

    /**
     * 批量更新追踪
     *
     * @param batchUpdate 批量更新请求
     * @return 204 No Content
     */
    @PATCH
    @Path("/batch")
    @Operation(operationId = "batchUpdateTraces", summary = "Batch update traces / 批量更新追踪", description = "Update multiple traces / 批量更新多个追踪", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容"),
            @ApiResponse(responseCode = "400", description = "Bad Request / 请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response batchUpdate(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceBatchUpdate.class))) @Valid @NotNull TraceBatchUpdate batchUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("批量更新 '{}' 个追踪，工作区 '{}'", batchUpdate.ids().size(), workspaceId);

        service.batchUpdate(batchUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已批量更新 '{}' 个追踪，工作区 '{}'", batchUpdate.ids().size(), workspaceId);

        return Response.noContent().build();
    }

    /**
     * 根据ID更新追踪
     *
     * @param id 追踪ID
     * @param trace 追踪更新对象
     * @return 204 No Content
     */
    @PATCH
    @Path("{id}")
    @Operation(operationId = "updateTrace", summary = "Update trace by id / 根据ID更新追踪", description = "Update trace by id / 根据ID更新追踪", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RateLimited(value = RateLimited.SINGLE_TRACING_OPS
            + ":{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceUpdate.class))) @Valid @NonNull TraceUpdate trace) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("更新追踪，ID '{}'，工作区 '{}'", id, workspaceId);

        service.update(trace, id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已更新追踪，ID '{}'，工作区 '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    /**
     * 根据ID删除追踪
     *
     * @param id 追踪ID
     * @return 204 No Content
     */
    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteTraceById", summary = "Delete trace by id / 根据ID删除追踪", description = "Delete trace by id / 根据ID删除追踪", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_DELETE)
    public Response deleteById(@PathParam("id") UUID id) {

        log.info("删除追踪，ID '{}'", id);

        service.delete(Set.of(id), null)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已删除追踪，ID '{}'", id);

        return Response.noContent().build();
    }

    /**
     * 批量删除追踪
     *
     * @param request 批量删除请求（包含项目ID）
     * @return 204 No Content
     */
    @POST
    @Path("/delete")
    @Operation(operationId = "deleteTraces", summary = "Delete traces / 批量删除追踪", description = "Delete traces / 批量删除追踪", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_DELETE)
    public Response deleteTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @NotNull @Valid BatchDeleteByProject request) {
        log.info("删除追踪，项目ID '{}'，数量 '{}'", request.projectId(), request.ids().size());
        service.delete(request.ids(), request.projectId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已删除追踪，项目ID '{}'，数量 '{}'", request.projectId(), request.ids().size());
        return Response.noContent().build();
    }

    /**
     * 获取追踪统计信息
     *
     * @param projectId 项目ID
     * @param projectName 项目名称
     * @param filters 过滤条件
     * @param search 全文搜索
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 项目统计信息
     */
    @GET
    @Path("/stats")
    @Operation(operationId = "getTraceStats", summary = "Get trace stats / 获取追踪统计", description = "Get trace stats / 获取追踪统计信息", responses = {
            @ApiResponse(responseCode = "200", description = "Trace stats resource / 追踪统计资源", content = @Content(schema = @Schema(implementation = ProjectStats.class)))
    })
    @JsonView({ProjectStats.ProjectStatItem.View.Public.class})
    @RateLimited(value = "getTraceStats:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response getStats(@QueryParam("project_id") UUID projectId,
            @QueryParam("project_name") String projectName,
            @QueryParam("filters") String filters,
            @QueryParam("search") @Schema(description = "Full-text search across trace fields / 跨追踪字段的全文搜索") String search,
            @QueryParam("from_time") @Schema(description = "Filter traces created from this time (ISO-8601 format). / 过滤从此时间开始创建的追踪（ISO-8601格式）") Instant startTime,
            @QueryParam("to_time") @Schema(description = "Filter traces created up to this time (ISO-8601 format). If not provided, defaults to current time. Must be after 'from_time'. / 过滤截止到此时间创建的追踪，如未提供默认为当前时间，必须晚于from_time") Instant endTime) {

        validateProjectNameAndProjectId(projectName, projectId);
        validateTimeRangeParameters(startTime, endTime);
        var traceFilters = filtersFactory.newFilters(filters, TraceFilter.LIST_TYPE_REFERENCE);

        var searchCriteria = TraceSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(traceFilters)
                .searchText(StringUtils.trimToNull(search))
                .uuidFromTime(instantToUUIDMapper.toLowerBound(startTime))
                .uuidToTime(instantToUUIDMapper.toUpperBound(endTime))
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取追踪统计，条件 '{}'，工作区 '{}'", searchCriteria, workspaceId);

        ProjectStats projectStats = service.getStats(searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取追踪统计，条件 '{}'，数量 '{}'，工作区 '{}'", searchCriteria,
                projectStats.stats().size(), workspaceId);

        return Response.ok(projectStats).build();
    }

    // 反馈评分相关接口
    /**
     * 添加追踪反馈评分
     *
     * @param id 追踪ID
     * @param score 反馈评分对象
     * @return 204 No Content
     */
    @PUT
    @Path("/{id}/feedback-scores")
    @Operation(operationId = "addTraceFeedbackScore", summary = "Add trace feedback score / 添加追踪反馈评分", description = "Add trace feedback score / 为追踪添加反馈评分", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response addTraceFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScore.class))) @NotNull @Valid FeedbackScore score) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("添加追踪反馈评分 '{}'，ID '{}'，工作区 '{}'", score.name(), id, workspaceId);

        feedbackScoreService.scoreTrace(id, score)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已添加追踪反馈评分 '{}'，ID '{}'，工作区 '{}'", score.name(), id, workspaceId);

        return Response.noContent().build();
    }

    /**
     * 删除追踪反馈评分
     *
     * @param id 追踪ID
     * @param score 删除反馈评分请求
     * @return 204 No Content
     */
    @POST
    @Path("/{id}/feedback-scores/delete")
    @Operation(operationId = "deleteTraceFeedbackScore", summary = "Delete trace feedback score / 删除追踪反馈评分", description = "Delete trace feedback score / 删除追踪反馈评分", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response deleteTraceFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteFeedbackScore.class))) @NotNull @Valid DeleteFeedbackScore score) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("删除追踪反馈评分 '{}'，ID '{}'，作者 '{}'，工作区 '{}'", score.name(), id,
                score.author(), workspaceId);
        feedbackScoreService.deleteTraceScore(id, score)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已删除追踪反馈评分 '{}'，ID '{}'，作者 '{}'，工作区 '{}'", score.name(), id,
                score.author(), workspaceId);
        return Response.noContent().build();
    }

    /**
     * 批量为追踪添加反馈评分
     *
     * @param feedbackScoreBatch 批量反馈评分对象
     * @return 204 No Content
     */
    @PUT
    @Path("/feedback-scores")
    @Operation(operationId = "scoreBatchOfTraces", summary = "Batch feedback scoring for traces / 批量为追踪添加反馈评分", description = "Batch feedback scoring for traces / 批量为追踪添加反馈评分", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response scoreBatchOfTraces(
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScoreBatch.class))) @NotNull @Valid FeedbackScoreBatchContainer.FeedbackScoreBatch feedbackScoreBatch) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("批量添加追踪反馈评分，数量 {}，工作区 '{}'", feedbackScoreBatch.scores().size(),
                workspaceId);

        feedbackScoreService.scoreBatchOfTraces(feedbackScoreBatch.scores())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();

        log.info("已批量添加追踪反馈评分，数量 {}，工作区 '{}'", feedbackScoreBatch.scores().size(),
                workspaceId);

        return Response.noContent().build();
    }

    /**
     * 查找反馈评分名称列表
     *
     * @param projectId 项目ID
     * @return 反馈评分名称列表
     */
    @GET
    @Path("/feedback-scores/names")
    @Operation(operationId = "findFeedbackScoreNames", summary = "Find Feedback Score names / 查找反馈评分名称", description = "Find Feedback Score names / 查找反馈评分名称列表", responses = {
            @ApiResponse(responseCode = "200", description = "Feedback Scores resource / 反馈评分资源", content = @Content(schema = @Schema(implementation = FeedbackScoreNames.class)))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response findFeedbackScoreNames(
            @QueryParam("project_id") UUID projectId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据项目ID '{}' 查找反馈评分名称，工作区 '{}'",
                projectId, workspaceId);
        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getTraceFeedbackScoreNames(projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已找到反馈评分名称 '{}'，项目ID '{}'，工作区 '{}'",
                feedbackScoreNames.scores().size(), projectId, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }

    // 评论相关接口
    /**
     * 为追踪添加评论
     *
     * @param id 追踪ID
     * @param comment 评论对象
     * @param uriInfo URI信息
     * @return 201 Created，包含Location头和评论ID
     */
    @POST
    @Path("/{id}/comments")
    @Operation(operationId = "addTraceComment", summary = "Add trace comment / 添加追踪评论", description = "Add trace comment / 为追踪添加评论", responses = {
            @ApiResponse(responseCode = "201", description = "Created / 已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/traces/{traceId}/comments/{commentId}", schema = @Schema(implementation = String.class))})})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response addTraceComment(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("为追踪 '{}' 添加评论，工作区 '{}'", id, workspaceId);

        var commentId = commentService.create(id, comment, CommentDAO.EntityType.TRACE)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(commentId)).build();
        log.info("已添加评论 '{}'，追踪ID '{}'，工作区 '{}'", comment.id(), id,
                workspaceId);

        return Response.created(uri).entity(new CreateCommentResponse(commentId)).build();
    }

    /**
     * 获取追踪评论
     *
     * @param commentId 评论ID
     * @param traceId 追踪ID
     * @return 评论对象
     */
    @GET
    @Path("/{traceId}/comments/{commentId}")
    @Operation(operationId = "getTraceComment", summary = "Get trace comment / 获取追踪评论", description = "Get trace comment / 获取追踪评论", responses = {
            @ApiResponse(responseCode = "200", description = "Comment resource / 评论资源", content = @Content(schema = @Schema(implementation = Comment.class))),
            @ApiResponse(responseCode = "404", description = "Not found / 未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getTraceComment(@PathParam("commentId") @NotNull UUID commentId,
            @PathParam("traceId") @NotNull UUID traceId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取追踪评论，评论ID '{}'，工作区 '{}'", commentId, workspaceId);

        Comment comment = commentService.get(traceId, commentId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取追踪评论 '{}'，工作区 '{}'", comment.id(), workspaceId);

        return Response.ok(comment).build();
    }

    /**
     * 更新追踪评论
     *
     * @param commentId 评论ID
     * @param comment 评论更新对象
     * @return 204 No Content
     */
    @PATCH
    @Path("/comments/{commentId}")
    @Operation(operationId = "updateTraceComment", summary = "Update trace comment by id / 根据ID更新追踪评论", description = "Update trace comment by id / 根据ID更新追踪评论", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容"),
            @ApiResponse(responseCode = "404", description = "Not found / 未找到")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response updateTraceComment(@PathParam("commentId") UUID commentId,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("更新追踪评论，评论ID '{}'，工作区 '{}'", commentId, workspaceId);

        commentService.update(commentId, comment)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已更新追踪评论，评论ID '{}'，工作区 '{}'", commentId, workspaceId);

        return Response.noContent().build();
    }

    /**
     * 批量删除追踪评论
     *
     * @param batchDelete 批量删除请求
     * @return 204 No Content
     */
    @POST
    @Path("/comments/delete")
    @Operation(operationId = "deleteTraceComments", summary = "Delete trace comments / 删除追踪评论", description = "Delete trace comments / 批量删除追踪评论", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容"),
    })
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response deleteTraceComments(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("删除追踪评论，评论IDs '{}'，工作区 '{}'", batchDelete.ids(), workspaceId);

        commentService.delete(batchDelete)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已删除追踪评论，评论IDs '{}'，工作区 '{}'", batchDelete.ids(), workspaceId);

        return Response.noContent().build();
    }

    // 追踪线程相关接口
    /**
     * 获取追踪线程列表
     *
     * @param page 页码，从1开始
     * @param size 每页大小
     * @param projectName 项目名称
     * @param projectId 项目ID
     * @param truncate 是否截断输入、输出和元数据以减小负载
     * @param stripAttachments 如果为true，返回附件引用如[file.png]；如果为false，下载并重新注入已剥离的附件
     * @param filters 过滤条件
     * @param sorting 排序规则
     * @param search 跨线程字段的全文搜索
     * @param startTime 过滤从此时间开始创建的线程（ISO-8601格式）
     * @param endTime 过滤截止到此时间创建的线程（ISO-8601格式）。如未提供，默认为当前时间。必须晚于from_time
     * @param annotationQueueId 过滤属于此标注队列的线程，并将反馈评分/评论限定在该队列范围内
     * @return 追踪线程分页结果
     */
    @GET
    @Path("/threads")
    @Operation(operationId = "getTraceThreads", summary = "Get trace threads / 获取追踪线程", description = "Get trace threads / 获取追踪线程列表", responses = {
            @ApiResponse(responseCode = "200", description = "Trace threads resource / 追踪线程资源", content = @Content(schema = @Schema(implementation = TraceThreadPage.class)))})
    public Response getTraceThreads(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("project_name") String projectName,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("truncate") @DefaultValue("false") @Schema(description = "Truncate input, output and metadata to slim payloads / 截断输入、输出和元数据以减小负载") boolean truncate,
            @QueryParam("strip_attachments") @DefaultValue("false") @Schema(description = "If true, returns attachment references like [file.png]; if false, downloads and reinjects stripped attachments / 如果为true返回附件引用，如果为false下载并重新注入附件") boolean stripAttachments,
            @QueryParam("filters") String filters,
            @QueryParam("sorting") String sorting,
            @QueryParam("search") @Schema(description = "Full-text search across thread fields / 跨线程字段的全文搜索") String search,
            @QueryParam("from_time") @Schema(description = "Filter trace threads created from this time (ISO-8601 format). / 过滤从此时间开始创建的线程（ISO-8601格式）") Instant startTime,
            @QueryParam("to_time") @Schema(description = "Filter trace threads created up to this time (ISO-8601 format). If not provided, defaults to current time. Must be after 'from_time'. / 过滤截止到此时间创建的线程，如未提供默认为当前时间，必须晚于from_time") Instant endTime,
            @QueryParam("annotation_queue_id") @Schema(description = "Filter threads belonging to this annotation queue and scope feedback scores/comments to it / 过滤属于此标注队列的线程并限定反馈评分/评论范围") UUID annotationQueueId) {

        validateProjectNameAndProjectId(projectName, projectId);
        validateTimeRangeParameters(startTime, endTime);
        var traceFilters = filtersFactory.newFilters(filters, TraceThreadFilter.LIST_TYPE_REFERENCE);
        var sortingFields = traceThreadSortingFactory.newSorting(sorting);

        var workspaceId = requestContext.get().getWorkspaceId();

        var metadata = workspaceMetadataService
                .getProjectMetadata(workspaceId, projectId, projectName)
                // 解析项目ID需要上下文
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        // 如果由于工作区数据量禁用了动态排序，则清空排序字段
        if (!sortingFields.isEmpty() && metadata.cannotUseDynamicSorting()) {
            sortingFields = List.of();
        }

        var searchCriteria = TraceSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(traceFilters)
                .truncate(truncate)
                .stripAttachments(stripAttachments)
                .sortingFields(sortingFields)
                .searchText(StringUtils.trimToNull(search))
                .uuidFromTime(instantToUUIDMapper.toLowerBound(startTime))
                .uuidToTime(instantToUUIDMapper.toUpperBound(endTime))
                .annotationQueueId(annotationQueueId)
                .build();

        log.info("获取追踪线程，条件 '{}'，工作区 '{}'", searchCriteria, workspaceId);

        TraceThreadPage traceThreadPage = traceThreadQueryService.find(page, size, searchCriteria)
                .map(it -> {
                    // 如果由于工作区数据量禁用了动态排序，则移除sortableBy字段
                    if (metadata.cannotUseDynamicSorting()) {
                        return it.toBuilder().sortableBy(List.of()).build();
                    }
                    return it;
                })
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取追踪线程，条件 '{}'，数量 '{}'，工作区 '{}'", searchCriteria, traceThreadPage.size(),
                workspaceId);

        return Response.ok(traceThreadPage).build();
    }

    /**
     * 搜索追踪线程（流式返回）
     *
     * @param request 追踪线程搜索流式请求
     * @return 追踪线程流式输出
     */
    @POST
    @Path("/threads/search")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "searchTraceThreads", summary = "Search trace threads / 搜索追踪线程", description = "Search trace threads / 搜索追踪线程并流式返回结果", responses = {
            @ApiResponse(responseCode = "200", description = "Trace threads stream or error during process / 追踪线程流或处理过程中的错误", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    TraceThread.class,
                    ErrorMessage.class
            }), maxItems = 2000))),
            @ApiResponse(responseCode = "400", description = "Bad Request / 请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public ChunkedOutput<JsonNode> searchTraceThreads(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceThreadSearchStreamRequest.class))) @NotNull @Valid TraceThreadSearchStreamRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();
        Visibility visibility = requestContext.get().getVisibility();
        String userName = requestContext.get().getUserName();

        validateProjectNameAndProjectId(request.projectName(), request.projectId());
        validateTimeRangeParameters(request.fromTime(), request.toTime());

        log.info("流式传输追踪线程搜索结果，条件 '{}'，工作区 '{}'", request, workspaceId);

        var searchCriteria = TraceSearchCriteria.builder()
                .lastReceivedId(request.lastRetrievedThreadModelId())
                .projectName(request.projectName())
                .projectId(request.projectId())
                .filters(filtersFactory.validateFilter(request.filters()))
                .truncate(request.truncate())
                .stripAttachments(request.stripAttachments())
                .sortingFields(List.of())
                .uuidFromTime(instantToUUIDMapper.toLowerBound(request.fromTime()))
                .uuidToTime(instantToUUIDMapper.toUpperBound(request.toTime()))
                .build();

        Flux<TraceThread> items = traceThreadQueryService.search(request.limit(), searchCriteria)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.VISIBILITY, Optional.ofNullable(visibility).orElse(Visibility.PRIVATE)));

        return streamer.getOutputStream(items,
                () -> log.info("已流式传输追踪线程搜索结果，条件 '{}'，工作区 '{}'", request,
                        workspaceId));
    }

    /**
     * 获取单个追踪线程
     *
     * @param identifier 线程标识符
     * @return 追踪线程对象
     */
    @POST
    @Path("/threads/retrieve")
    @Operation(operationId = "getTraceThread", summary = "Get trace thread / 获取追踪线程", description = "Get trace thread / 获取单个追踪线程", responses = {
            @ApiResponse(responseCode = "200", description = "Trace thread resource / 追踪线程资源", content = @Content(schema = @Schema(implementation = TraceThread.class))),
            @ApiResponse(responseCode = "404", description = "Not found / 未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getTraceThread(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceThreadIdentifier.class))) @NotNull @Valid TraceThreadIdentifier identifier) {

        String workspaceId = requestContext.get().getWorkspaceId();
        UUID projectId = projectService.validateProjectIdentifier(identifier.projectId(), identifier.projectName(),
                workspaceId);

        log.info("获取追踪线程，线程ID '{}'，项目ID '{}'，工作区 '{}'，截断 '{}'",
                identifier.threadId(), projectId, workspaceId, identifier.truncate());

        TraceThread thread = traceThreadQueryService.getById(projectId, identifier.threadId(), identifier.truncate())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取追踪线程，线程ID '{}'，项目ID '{}'，工作区 '{}'", identifier.threadId(),
                projectId, workspaceId);

        return Response.ok(thread).build();
    }

    /**
     * 删除追踪线程
     *
     * @param traceThreads 删除线程请求
     * @return 204 No Content
     */
    @POST
    @Path("/threads/delete")
    @Operation(operationId = "deleteTraceThreads", summary = "Delete trace threads / 删除追踪线程", description = "Delete trace threads / 删除追踪线程", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    public Response deleteTraceThreads(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = DeleteTraceThreads.class))) @Valid DeleteTraceThreads traceThreads) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("删除追踪线程，项目名称 '{}' 或项目ID '{}'，工作区 '{}'",
                traceThreads.projectName(), traceThreads.projectId(), workspaceId);

        service.deleteTraceThreads(traceThreads)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已删除追踪线程，线程IDs '{}'，工作区 '{}'", traceThreads.threadIds(), workspaceId);

        return Response.noContent().build();
    }

    /**
     * 打开追踪线程
     *
     * @param identifier 线程标识符
     * @return 204 No Content
     */
    @PUT
    @Path("/threads/open")
    @Operation(operationId = "openTraceThread", summary = "Open trace thread / 打开追踪线程", description = "Open trace thread / 打开追踪线程", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response openTraceThread(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceThreadIdentifier.class))) @NotNull @Valid TraceThreadIdentifier identifier) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("打开追踪线程，线程ID '{}'，项目ID '{}'，项目名称 '{}'，工作区 '{}'",
                identifier.threadId(), identifier.projectId(), identifier.projectName(), workspaceId);

        traceThreadService.openThread(identifier.projectId(), identifier.projectName(), identifier.threadId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已打开追踪线程，线程ID '{}'，工作区 '{}'", identifier.threadId(), workspaceId);

        return Response.noContent().build();
    }

    /**
     * 关闭追踪线程（支持单个或批量操作）
     *
     * @param identifier 线程批量标识符
     * @return 204 No Content
     */
    @PUT
    @Path("/threads/close")
    @Operation(operationId = "closeTraceThread", summary = "Close trace thread(s) / 关闭追踪线程", description = "Close one or multiple trace threads. Supports both single thread_id and multiple thread_ids for batch operations. / 关闭一个或多个追踪线程，支持单个和批量操作", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容"),
            @ApiResponse(responseCode = "404", description = "Not found / 未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response closeTraceThread(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceThreadBatchIdentifier.class))) @NotNull @Valid TraceThreadBatchIdentifier identifier) {

        String workspaceId = requestContext.get().getWorkspaceId();

        // 处理单个和批量操作
        Set<String> threadIds = CollectionUtils.isNotEmpty(identifier.threadIds())
                ? Set.copyOf(identifier.threadIds())
                : Set.of(identifier.threadId());

        log.info("关闭追踪线程，线程IDs '{}'，项目ID '{}'，项目名称 '{}'，工作区 '{}'",
                threadIds, identifier.projectId(), identifier.projectName(), workspaceId);

        traceThreadService.closeThreads(identifier.projectId(), identifier.projectName(), threadIds)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已关闭追踪线程，线程IDs '{}'，工作区 '{}'", threadIds, workspaceId);

        return Response.noContent().build();
    }

    /**
     * 批量更新线程
     *
     * @param batchUpdate 批量更新请求
     * @return 204 No Content
     */
    @PATCH
    @Path("/threads/batch")
    @Operation(operationId = "batchUpdateThreads", summary = "Batch update threads / 批量更新线程", description = "Update multiple threads / 批量更新多个线程", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容"),
            @ApiResponse(responseCode = "400", description = "Bad Request / 请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response batchUpdateThreads(
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceThreadBatchUpdate.class))) @Valid @NotNull TraceThreadBatchUpdate batchUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("批量更新 '{}' 个线程，工作区 '{}'", batchUpdate.ids().size(), workspaceId);

        traceThreadService.batchUpdate(batchUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已批量更新 '{}' 个线程，工作区 '{}'", batchUpdate.ids().size(), workspaceId);

        return Response.noContent().build();
    }

    /**
     * 更新线程
     *
     * @param threadModelId 线程模型ID
     * @param threadUpdate 线程更新对象
     * @return 204 No Content
     */
    @PATCH
    @Path("/threads/{threadModelId}")
    @Operation(operationId = "updateThread", summary = "Update thread / 更新线程", description = "Update thread / 更新线程", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容"),
            @ApiResponse(responseCode = "404", description = "Not found / 未找到")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response updateThread(@PathParam("threadModelId") UUID threadModelId,
            @RequestBody(content = @Content(schema = @Schema(implementation = TraceThreadUpdate.class))) @NotNull @Valid TraceThreadUpdate threadUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("更新线程，线程模型ID '{}'，工作区 '{}'", threadModelId, workspaceId);

        traceThreadService.update(threadModelId, threadUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已更新线程，线程模型ID '{}'，工作区 '{}'", threadModelId, workspaceId);

        return Response.noContent().build();
    }

    /**
     * 获取追踪线程统计信息
     *
     * @param projectId 项目ID
     * @param projectName 项目名称
     * @param filters 过滤条件
     * @param search 全文搜索
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 项目统计信息
     */
    @GET
    @Path("/threads/stats")
    @Operation(operationId = "getTraceThreadStats", summary = "Get trace thread stats / 获取追踪线程统计", description = "Get trace thread stats / 获取追踪线程统计信息", responses = {
            @ApiResponse(responseCode = "200", description = "Trace thread stats resource / 追踪线程统计资源", content = @Content(schema = @Schema(implementation = ProjectStats.class)))
    })
    @JsonView({ProjectStats.ProjectStatItem.View.Public.class})
    public Response getThreadStats(@QueryParam("project_id") UUID projectId,
            @QueryParam("project_name") String projectName,
            @QueryParam("filters") String filters,
            @QueryParam("search") @Schema(description = "Full-text search across thread fields / 跨线程字段的全文搜索") String search,
            @QueryParam("from_time") @Schema(description = "Filter trace threads created from this time (ISO-8601 format). / 过滤从此时间开始创建的线程（ISO-8601格式）") Instant startTime,
            @QueryParam("to_time") @Schema(description = "Filter trace threads created up to this time (ISO-8601 format). If not provided, defaults to current time. Must be after 'from_time'. / 过滤截止到此时间创建的线程，如未提供默认为当前时间，必须晚于from_time") Instant endTime) {

        validateProjectNameAndProjectId(projectName, projectId);
        validateTimeRangeParameters(startTime, endTime);
        var threadFilters = filtersFactory.newFilters(filters, TraceThreadFilter.LIST_TYPE_REFERENCE);

        var searchCriteria = TraceSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(threadFilters)
                .searchText(StringUtils.trimToNull(search))
                .uuidFromTime(instantToUUIDMapper.toLowerBound(startTime))
                .uuidToTime(instantToUUIDMapper.toUpperBound(endTime))
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取追踪线程统计，条件 '{}'，工作区 '{}'", searchCriteria, workspaceId);

        ProjectStats projectStats = traceThreadQueryService.getStats(searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取追踪线程统计，条件 '{}'，数量 '{}'，工作区 '{}'", searchCriteria,
                projectStats.stats().size(), workspaceId);

        return Response.ok(projectStats).build();
    }

    /**
     * 批量为线程添加反馈评分
     *
     * @param batch 批量反馈评分对象
     * @return 204 No Content
     */
    @PUT
    @Path("/threads/feedback-scores")
    @Operation(operationId = "scoreBatchOfThreads", summary = "Batch feedback scoring for threads / 批量为线程添加反馈评分", description = "Batch feedback scoring for threads / 批量为线程添加反馈评分", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response scoreBatchOfThreads(
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScoreBatchThread.class))) @NotNull @Valid FeedbackScoreBatchThread batch) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("批量添加线程反馈评分，数量 '{}'，工作区 '{}'", batch.scores().size(),
                workspaceId);

        feedbackScoreService.scoreBatchOfThreads(batch.scores())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();

        log.info("已批量添加线程反馈评分，数量 '{}'，工作区 '{}'", batch.scores().size(),
                workspaceId);

        return Response.noContent().build();
    }

    /**
     * 删除线程反馈评分
     *
     * @param scores 删除线程反馈评分请求
     * @return 204 No Content
     */
    @POST
    @Path("/threads/feedback-scores/delete")
    @Operation(operationId = "deleteThreadFeedbackScores", summary = "Delete thread feedback scores / 删除线程反馈评分", description = "Delete thread feedback scores / 删除线程反馈评分", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response deleteThreadFeedbackScores(
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteThreadFeedbackScores.class))) @NotNull @Valid DeleteThreadFeedbackScores scores) {
        var workspaceId = requestContext.get().getWorkspaceId();
        String projectName = scores.projectName();

        log.info("删除线程反馈评分，线程ID '{}'，项目名称 '{}'，作者 '{}'，工作区 '{}'",
                scores.threadId(),
                projectName, scores.author(), workspaceId);

        feedbackScoreService.deleteThreadScores(projectName, scores.threadId(), scores.names(), scores.author(),
                scores.sourceQueueId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已删除线程反馈评分，线程ID '{}'，项目名称 '{}'，作者 '{}'，工作区 '{}'",
                scores.threadId(),
                projectName, scores.author(), workspaceId);

        return Response.noContent().build();
    }

    /**
     * 查找追踪线程反馈评分名称列表
     *
     * @param projectId 项目ID
     * @return 反馈评分名称列表
     */
    @GET
    @Path("/threads/feedback-scores/names")
    @Operation(operationId = "findTraceThreadsFeedbackScoreNames", summary = "Find Trace Threads Feedback Score names / 查找追踪线程反馈评分名称", description = "Find Trace Threads Feedback Score names / 查找追踪线程反馈评分名称列表", responses = {
            @ApiResponse(responseCode = "200", description = "Find Trace Threads Feedback Score names / 追踪线程反馈评分名称", content = @Content(schema = @Schema(implementation = FeedbackScoreNames.class)))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response findTraceThreadsFeedbackScoreNames(
            @QueryParam("project_id") UUID projectId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据项目ID '{}' 查找追踪线程反馈评分名称，工作区 '{}'",
                projectId, workspaceId);

        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getTraceThreadsFeedbackScoreNames(projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已找到追踪线程反馈评分名称 '{}'，项目ID '{}'，工作区 '{}'",
                feedbackScoreNames.scores().size(), projectId, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }

    /**
     * 为线程添加评论
     *
     * @param id 线程ID
     * @param comment 评论对象
     * @param uriInfo URI信息
     * @return 201 Created，包含Location头和评论ID
     */
    @POST
    @Path("/threads/{id}/comments")
    @Operation(operationId = "addThreadComment", summary = "Add thread comment / 添加线程评论", description = "Add thread comment / 为线程添加评论", responses = {
            @ApiResponse(responseCode = "201", description = "Created / 已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/traces/threads/{threadId}/comments/{commentId}", schema = @Schema(implementation = String.class))})})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response addThreadComment(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("为线程 '{}' 添加评论，工作区 '{}'", id, workspaceId);

        var commentId = commentService.create(id, comment, CommentDAO.EntityType.THREAD)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(commentId)).build();
        log.info("已添加评论 '{}'，线程ID '{}'，工作区 '{}'", comment.id(), id,
                workspaceId);

        return Response.created(uri).entity(new CreateCommentResponse(commentId)).build();
    }

    /**
     * 获取线程评论
     *
     * @param commentId 评论ID
     * @param threadId 线程ID
     * @return 评论对象
     */
    @GET
    @Path("/threads/{threadId}/comments/{commentId}")
    @Operation(operationId = "getThreadComment", summary = "Get thread comment / 获取线程评论", description = "Get thread comment / 获取线程评论", responses = {
            @ApiResponse(responseCode = "200", description = "Comment resource / 评论资源", content = @Content(schema = @Schema(implementation = Comment.class))),
            @ApiResponse(responseCode = "404", description = "Not found / 未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getThreadComment(@PathParam("commentId") @NotNull UUID commentId,
            @PathParam("threadId") @NotNull UUID threadId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取线程评论，评论ID '{}'，工作区 '{}'", commentId, workspaceId);

        Comment comment = commentService.get(threadId, commentId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取线程评论 '{}'，工作区 '{}'", comment.id(), workspaceId);

        return Response.ok(comment).build();
    }

    /**
     * 更新线程评论
     *
     * @param commentId 评论ID
     * @param comment 评论更新对象
     * @return 204 No Content
     */
    @PATCH
    @Path("/threads/comments/{commentId}")
    @Operation(operationId = "updateThreadComment", summary = "Update thread comment by id / 根据ID更新线程评论", description = "Update thread comment by id / 根据ID更新线程评论", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容"),
            @ApiResponse(responseCode = "404", description = "Not found / 未找到")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response updateThreadComment(@PathParam("commentId") UUID commentId,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("更新线程评论，评论ID '{}'，工作区 '{}'", commentId, workspaceId);

        commentService.update(commentId, comment)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已更新线程评论，评论ID '{}'，工作区 '{}'", commentId, workspaceId);

        return Response.noContent().build();
    }

    /**
     * 批量删除线程评论
     *
     * @param batchDelete 批量删除请求
     * @return 204 No Content
     */
    @POST
    @Path("/threads/comments/delete")
    @Operation(operationId = "deleteThreadComments", summary = "Delete thread comments / 删除线程评论", description = "Delete thread comments / 批量删除线程评论", responses = {
            @ApiResponse(responseCode = "204", description = "No Content / 无内容"),
    })
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response deleteThreadComments(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("删除线程评论，评论IDs '{}'，工作区 '{}'", batchDelete.ids(), workspaceId);

        commentService.delete(batchDelete)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已删除线程评论，评论IDs '{}'，工作区 '{}'", batchDelete.ids(), workspaceId);

        return Response.noContent().build();
    }

}
