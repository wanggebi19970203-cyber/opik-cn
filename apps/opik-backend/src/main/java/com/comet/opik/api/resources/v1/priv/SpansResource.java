package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.CreateCommentResponse;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchContainer;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.InstantToUUIDMapper;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanBatchUpdate;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.SpanSortingFactory;
import com.comet.opik.domain.CommentDAO;
import com.comet.opik.domain.CommentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanSearchCriteria;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.Streamer;
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
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.BadRequestException;
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
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.server.ChunkedOutput;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatch;
import static com.comet.opik.api.Span.SpanField;
import static com.comet.opik.api.Span.SpanPage;
import static com.comet.opik.api.Span.View;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static com.comet.opik.utils.ValidationUtils.validateProjectNameAndProjectId;
import static com.comet.opik.utils.ValidationUtils.validateTimeRangeParameters;

@Path("/v1/private/spans")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Spans", description = "Span相关资源")
public class SpansResource {

    private final @NonNull SpanService spanService;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull CommentService commentService;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull WorkspaceMetadataService workspaceMetadataService;
    private final @NonNull SpanSortingFactory sortingFactory;
    private final @NonNull ProjectService projectService;
    private final @NonNull InstantToUUIDMapper instantToUUIDMapper;

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull Streamer streamer;

    @GET
    @Operation(operationId = "getSpansByProject", summary = "根据项目名称或ID获取Span", description = "根据项目名称或ID获取Span，可选按trace_id和/或类型过滤", responses = {
            @ApiResponse(responseCode = "200", description = "Span资源", content = @Content(schema = @Schema(implementation = SpanPage.class)))})
    @JsonView(View.Public.class)
    @RateLimited(value = "getSpans:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response getSpansByProject(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("project_name") String projectName,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("trace_id") UUID traceId,
            @QueryParam("type") SpanType type,
            @QueryParam("filters") String filters,
            @QueryParam("truncate") @DefaultValue("false") @Schema(description = "截断输入、输出和元数据以减小负载") boolean truncate,
            @QueryParam("strip_attachments") @DefaultValue("false") @Schema(description = "如果为true，返回附件引用如[file.png]；如果为false，下载并重新注入已剥离的附件") boolean stripAttachments,
            @QueryParam("sorting") String sorting,
            @QueryParam("exclude") String exclude,
            @QueryParam("search") @Schema(description = "跨Span字段的全文搜索") String search,
            @QueryParam("from_time") @Schema(description = "过滤从此时间开始创建的Span（ISO-8601格式）。") Instant startTime,
            @QueryParam("to_time") @Schema(description = "过滤截止到此时间创建的Span（ISO-8601格式）。如未提供，默认为当前时间。必须晚于from_time。") Instant endTime) {

        validateProjectNameAndProjectId(projectName, projectId);
        validateTimeRangeParameters(startTime, endTime);
        var spanFilters = filtersFactory.newFilters(filters, SpanFilter.LIST_TYPE_REFERENCE);
        var sortingFields = sortingFactory.newSorting(sorting);

        var workspaceId = requestContext.get().getWorkspaceId();

        var workspaceMetadata = workspaceMetadataService
                .getProjectMetadata(workspaceId, projectId, projectName)
                // 解析项目ID需要上下文
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        if (!sortingFields.isEmpty() && workspaceMetadata.cannotUseDynamicSorting()) {
            sortingFields = List.of();
        }

        var spanSearchCriteria = SpanSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .traceId(traceId)
                .type(type)
                .filters(spanFilters)
                .truncate(truncate)
                .stripAttachments(stripAttachments)
                .uuidFromTime(instantToUUIDMapper.toLowerBound(startTime))
                .uuidToTime(instantToUUIDMapper.toUpperBound(endTime))
                .sortingFields(sortingFields)
                .exclude(ParamsValidator.get(exclude, SpanField.class, "exclude"))
                .searchText(StringUtils.trimToNull(search))
                .build();

        log.info("根据条件 '{}' 获取Span，工作区 '{}'", spanSearchCriteria, workspaceId);
        SpanPage spans = spanService.find(page, size, spanSearchCriteria)
                .map(it -> {
                    // 如果由于工作区数据量禁用了动态排序，则移除sortableBy字段
                    if (workspaceMetadata.cannotUseDynamicSorting()) {
                        return it.toBuilder().sortableBy(List.of()).build();
                    }
                    return it;
                })
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已找到Span，条件 '{}'，数量 '{}'，工作区 '{}'", spanSearchCriteria, spans.size(), workspaceId);
        return Response.ok().entity(spans).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getSpanById", summary = "根据ID获取Span", description = "根据ID获取Span", responses = {
            @ApiResponse(responseCode = "200", description = "Span资源", content = @Content(schema = @Schema(implementation = Span.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = Span.class)))})
    @JsonView(View.Public.class)
    @RateLimited(value = "getSpanById:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response getById(@PathParam("id") @NotNull UUID id,
            @QueryParam("strip_attachments") @DefaultValue("false") @Schema(description = "如果为true，返回附件引用如[file.png]；如果为false，从S3下载并重新注入附件内容（默认false以保持向后兼容）") boolean stripAttachments) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据ID '{}' 获取Span，工作区 '{}'，stripAttachments={}", id, workspaceId,
                stripAttachments);

        var span = spanService.getById(id, stripAttachments)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info(
                "已获取Span，ID '{}'，traceId '{}'，parentSpanId '{}'，工作区 '{}'，stripAttachments={}",
                span.id(), span.traceId(),
                span.parentSpanId(), workspaceId, stripAttachments);

        return Response.ok().entity(span).build();
    }

    @POST
    @Operation(operationId = "createSpan", summary = "创建Span", description = "创建新Span", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/spans/{spanId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = com.comet.opik.api.error.ErrorMessage.class)))})
    @RateLimited(value = RateLimited.SINGLE_TRACING_OPS
            + ":{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    @UsageLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Span.class))) @JsonView(View.Write.class) @NotNull @Valid Span span,
            @Context UriInfo uriInfo) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("创建Span，ID '{}'，项目名称 '{}'，traceId '{}'，parentSpanId '{}'，工作区 '{}'",
                span.id(), span.projectName(), span.traceId(), span.parentSpanId(), workspaceId);
        var id = spanService.create(span)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        log.info("已创建Span，ID '{}'，项目名称 '{}'，traceId '{}'，parentSpanId '{}'，工作区 '{}'",
                id, span.projectName(), span.traceId(), span.parentSpanId(), workspaceId);
        return Response.created(uri).build();
    }

    @POST
    @Path("/batch")
    @Operation(operationId = "createSpans", summary = "批量创建Span", description = "批量创建Span", responses = {
            @ApiResponse(responseCode = "204", description = "无内容")})
    @RateLimited
    @UsageLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response createSpans(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpanBatch.class))) @JsonView(View.Write.class) @NotNull @Valid SpanBatch spans) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("批量创建Span，数量 '{}'，工作区 '{}'", spans.spans().size(), workspaceId);
        spanService.create(spans)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已批量创建Span，数量 '{}'，工作区 '{}'", spans.spans().size(), workspaceId);
        return Response.noContent().build();
    }

    @PATCH
    @Path("/batch")
    @Operation(operationId = "batchUpdateSpans", summary = "批量更新Span", description = "批量更新多个Span", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response batchUpdate(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpanBatchUpdate.class))) @Valid @NotNull SpanBatchUpdate batchUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("批量更新 '{}' 个Span，工作区 '{}'", batchUpdate.ids().size(), workspaceId);

        spanService.batchUpdate(batchUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已批量更新 '{}' 个Span，工作区 '{}'", batchUpdate.ids().size(), workspaceId);

        return Response.noContent().build();
    }

    @PATCH
    @Path("{id}")
    @Operation(operationId = "updateSpan", summary = "根据ID更新Span", description = "根据ID更新Span", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "404", description = "未找到")})
    @RateLimited(value = RateLimited.SINGLE_TRACING_OPS
            + ":{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_LOG)
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = SpanUpdate.class))) @NotNull @Valid SpanUpdate spanUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("更新Span，ID '{}'，工作区 '{}'", id, workspaceId);
        spanService.update(id, spanUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已更新Span，ID '{}'，工作区 '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deleteSpanById", summary = "根据ID删除Span", description = "根据ID删除Span", responses = {
            @ApiResponse(responseCode = "501", description = "未实现"),
            @ApiResponse(responseCode = "204", description = "无内容")})
    public Response deleteById(@PathParam("id") @NotNull String id) {

        log.info("删除Span，ID '{}'，工作区 '{}'", id, requestContext.get().getWorkspaceId());
        return Response.status(501).build();
    }

    @PUT
    @Path("/{id}/feedback-scores")
    @Operation(operationId = "addSpanFeedbackScore", summary = "添加Span反馈评分", description = "为Span添加反馈评分", responses = {
            @ApiResponse(responseCode = "204", description = "无内容")})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response addSpanFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScore.class))) @NotNull @Valid FeedbackScore score) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("添加Span反馈评分 '{}'，ID '{}'，工作区 '{}'", score.name(), id, workspaceId);
        feedbackScoreService.scoreSpan(id, score)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已添加Span反馈评分 '{}'，ID '{}'，工作区 '{}'", score.name(), id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{id}/feedback-scores/delete")
    @Operation(operationId = "deleteSpanFeedbackScore", summary = "删除Span反馈评分", description = "删除Span反馈评分", responses = {
            @ApiResponse(responseCode = "204", description = "无内容")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response deleteSpanFeedbackScore(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteFeedbackScore.class))) @NotNull @Valid DeleteFeedbackScore score) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("删除Span反馈评分 '{}'，ID '{}'，作者 '{}'，工作区 '{}'", score.name(), id,
                score.author(), workspaceId);
        feedbackScoreService.deleteSpanScore(id, score)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已删除Span反馈评分 '{}'，ID '{}'，作者 '{}'，工作区 '{}'", score.name(), id,
                score.author(), workspaceId);
        return Response.noContent().build();
    }

    @PUT
    @Path("/feedback-scores")
    @Operation(operationId = "scoreBatchOfSpans", summary = "批量为Span添加反馈评分", description = "批量为Span添加反馈评分", responses = {
            @ApiResponse(responseCode = "204", description = "无内容")})
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response scoreBatchOfSpans(
            @RequestBody(content = @Content(schema = @Schema(implementation = FeedbackScoreBatch.class))) @NotNull @Valid FeedbackScoreBatchContainer.FeedbackScoreBatch batch) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("批量添加Span反馈评分，数量 {}，工作区 '{}'", batch.scores().size(), workspaceId);
        feedbackScoreService.scoreBatchOfSpans(batch.scores())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();
        log.info("已批量添加Span反馈评分，数量 {}，工作区 '{}'", batch.scores().size(), workspaceId);
        return Response.noContent().build();
    }

    @GET
    @Path("/stats")
    @Operation(operationId = "getSpanStats", summary = "获取Span统计", description = "获取Span统计信息", responses = {
            @ApiResponse(responseCode = "200", description = "Span统计资源", content = @Content(schema = @Schema(implementation = ProjectStats.class)))
    })
    @JsonView({ProjectStats.ProjectStatItem.View.Public.class})
    @RateLimited(value = "getSpanStats:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public Response getStats(@QueryParam("project_id") UUID projectId,
            @QueryParam("project_name") String projectName,
            @QueryParam("trace_id") UUID traceId,
            @QueryParam("type") SpanType type,
            @QueryParam("filters") String filters,
            @QueryParam("search") @Schema(description = "跨Span字段的全文搜索") String search,
            @QueryParam("from_time") @Schema(description = "过滤从此时间开始创建的Span（ISO-8601格式）。") Instant startTime,
            @QueryParam("to_time") @Schema(description = "过滤截止到此时间创建的Span（ISO-8601格式）。如未提供，默认为当前时间。必须晚于from_time。") Instant endTime) {

        validateProjectNameAndProjectId(projectName, projectId);
        validateTimeRangeParameters(startTime, endTime);
        var spanFilters = filtersFactory.newFilters(filters, SpanFilter.LIST_TYPE_REFERENCE);
        var searchCriteria = SpanSearchCriteria.builder()
                .projectName(projectName)
                .projectId(projectId)
                .filters(spanFilters)
                .traceId(traceId)
                .type(type)
                .uuidFromTime(instantToUUIDMapper.toLowerBound(startTime))
                .uuidToTime(instantToUUIDMapper.toUpperBound(endTime))
                .sortingFields(List.of())
                .searchText(StringUtils.trimToNull(search))
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取Span统计，条件 '{}'，工作区 '{}'", searchCriteria, workspaceId);

        ProjectStats projectStats = spanService.getStats(searchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取Span统计，条件 '{}'，数量 '{}'，工作区 '{}'", searchCriteria,
                projectStats.stats().size(), workspaceId);

        return Response.ok(projectStats).build();
    }

    @GET
    @Path("/feedback-scores/names")
    @Operation(operationId = "findFeedbackScoreNames", summary = "查找反馈评分名称", description = "查找反馈评分名称", responses = {
            @ApiResponse(responseCode = "200", description = "反馈评分资源", content = @Content(schema = @Schema(implementation = FeedbackScoreNames.class)))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response findFeedbackScoreNames(@QueryParam("project_id") UUID projectId,
            @QueryParam("type") SpanType type) {

        if (projectId == null) {
            throw new BadRequestException("project_id must be provided");
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据项目ID '{}' 查找反馈评分名称，工作区 '{}'",
                projectId, workspaceId);
        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getSpanFeedbackScoreNames(projectId, type)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已找到反馈评分名称 '{}'，项目ID '{}'，工作区 '{}'",
                feedbackScoreNames.scores().size(), projectId, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }

    @POST
    @Path("/search")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "searchSpans", summary = "搜索Span", description = "搜索Span", responses = {
            @ApiResponse(responseCode = "200", description = "Span流或处理过程中的错误", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    Span.class,
                    ErrorMessage.class
            }), maxItems = 2000))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @JsonView(View.Public.class)
    @RateLimited(value = "search_spans:{workspaceId}", shouldAffectWorkspaceLimit = false, shouldAffectUserGeneralLimit = false)
    public ChunkedOutput<JsonNode> searchSpans(
            @RequestBody(content = @Content(schema = @Schema(implementation = SpanSearchStreamRequest.class))) @NotNull @Valid SpanSearchStreamRequest request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();
        var visibility = requestContext.get().getVisibility();

        validateProjectNameAndProjectId(request.projectName(), request.projectId());
        validateTimeRangeParameters(request.fromTime(), request.toTime());

        log.info("流式传输Span搜索结果，条件 '{}'，工作区 '{}'", request, workspaceId);
        var criteria = SpanSearchCriteria.builder()
                .lastReceivedSpanId(request.lastRetrievedId())
                .truncate(request.truncate())
                .traceId(request.traceId())
                .type(request.type())
                .projectName(request.projectName())
                .projectId(request.projectId())
                .filters(filtersFactory.validateFilter(request.filters()))
                .exclude(request.exclude())
                .sortingFields(List.of())
                .uuidFromTime(instantToUUIDMapper.toLowerBound(request.fromTime()))
                .uuidToTime(instantToUUIDMapper.toUpperBound(request.toTime()))
                .build();

        projectService.resolveProjectIdAndVerifyVisibility(request.projectId(), request.projectName())
                .contextWrite(ctx -> setRequestContext(ctx, workspaceId, userName, visibility))
                .block();

        var items = spanService.search(request.limit(), criteria)
                .contextWrite(ctx -> setRequestContext(ctx, workspaceId, userName, visibility));

        return streamer.getOutputStream(items,
                () -> log.info("已流式传输Span搜索结果，条件 '{}'，工作区 '{}'", request, workspaceId));
    }

    @POST
    @Path("/{id}/comments")
    @Operation(operationId = "addSpanComment", summary = "添加Span评论", description = "添加Span评论", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/spans/{spanId}/comments/{commentId}", schema = @Schema(implementation = String.class))})})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response addSpanComment(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("为Span '{}' 添加评论，工作区 '{}'", id, workspaceId);

        var commentId = commentService.create(id, comment, CommentDAO.EntityType.SPAN)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(commentId)).build();
        log.info("已添加评论 '{}'，Span ID '{}'，工作区 '{}'", comment.id(), id,
                workspaceId);

        return Response.created(uri).entity(new CreateCommentResponse(commentId)).build();
    }

    @GET
    @Path("/{spanId}/comments/{commentId}")
    @Operation(operationId = "getSpanComment", summary = "获取Span评论", description = "获取Span评论", responses = {
            @ApiResponse(responseCode = "200", description = "评论资源", content = @Content(schema = @Schema(implementation = Comment.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    public Response getSpanComment(@PathParam("commentId") @NotNull UUID commentId,
            @PathParam("spanId") @NotNull UUID spanId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取Span评论，评论ID '{}'，工作区 '{}'", commentId, workspaceId);

        Comment comment = commentService.get(spanId, commentId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取Span评论 '{}'，工作区 '{}'", comment.id(), workspaceId);

        return Response.ok(comment).build();
    }

    @PATCH
    @Path("/comments/{commentId}")
    @Operation(operationId = "updateSpanComment", summary = "根据ID更新Span评论", description = "根据ID更新Span评论", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "404", description = "未找到")})
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response updateSpanComment(@PathParam("commentId") UUID commentId,
            @RequestBody(content = @Content(schema = @Schema(implementation = Comment.class))) @NotNull @Valid Comment comment) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("更新Span评论，评论ID '{}'，工作区 '{}'", commentId, workspaceId);

        commentService.update(commentId, comment)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已更新Span评论，评论ID '{}'，工作区 '{}'", commentId, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/comments/delete")
    @Operation(operationId = "deleteSpanComments", summary = "删除Span评论", description = "删除Span评论", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RequiredPermissions(WorkspaceUserPermission.TRACE_SPAN_THREAD_ANNOTATE)
    public Response deleteSpanComments(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("删除Span评论，评论IDs '{}'，工作区 '{}'", batchDelete.ids(), workspaceId);

        commentService.delete(batchDelete)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已删除Span评论，评论IDs '{}'，工作区 '{}'", batchDelete.ids(), workspaceId);

        return Response.noContent().build();
    }

}
