package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.DeleteIdsHolder;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentBatchUpdate;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentExecutionResponse;
import com.comet.opik.api.ExperimentGroupAggregationsResponse;
import com.comet.opik.api.ExperimentGroupCriteria;
import com.comet.opik.api.ExperimentGroupResponse;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemBulkRecord;
import com.comet.opik.api.ExperimentItemBulkUpload;
import com.comet.opik.api.ExperimentItemStreamRequest;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.ExperimentItemsDelete;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.FeedbackDefinition;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.IdsHolder;
import com.comet.opik.api.filter.ExperimentFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.grouping.ExperimentGroupingFactory;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.api.resources.v1.priv.validate.ExperimentItemBulkValidator;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.ExperimentExecutionService;
import com.comet.opik.domain.ExperimentItemBulkIngestionService;
import com.comet.opik.domain.ExperimentItemSearchCriteria;
import com.comet.opik.domain.ExperimentItemService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
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
import org.glassfish.jersey.server.ChunkedOutput;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/experiments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Experiments", description = "实验资源")
public class ExperimentsResource {

    private final @NonNull ExperimentService experimentService;
    private final @NonNull ExperimentItemService experimentItemService;
    private final @NonNull FeedbackScoreService feedbackScoreService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Streamer streamer;
    private final @NonNull ExperimentSortingFactory sortingFactory;
    private final @NonNull WorkspaceMetadataService workspaceMetadataService;
    private final @NonNull ExperimentItemBulkIngestionService experimentItemBulkIngestionService;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull ExperimentGroupingFactory groupingFactory;
    private final @NonNull ExperimentExecutionService experimentExecutionService;

    @GET
    @Operation(operationId = "findExperiments", summary = "查找实验", description = "查找实验列表", responses = {
            @ApiResponse(responseCode = "200", description = "实验资源", content = @Content(schema = @Schema(implementation = Experiment.ExperimentPage.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.EXPERIMENT_VIEW)
    @JsonView(Experiment.View.Public.class)
    public Response find(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("datasetId") UUID datasetId,
            @QueryParam("optimization_id") UUID optimizationId,
            @QueryParam("types") String typesQueryParam,
            @QueryParam("name") @Schema(description = "按名称过滤实验（部分匹配，不区分大小写）") String name,
            @QueryParam("dataset_deleted") boolean datasetDeleted,
            @QueryParam("prompt_id") UUID promptId,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("project_deleted") boolean projectDeleted,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters,
            @QueryParam("experiment_ids") @Schema(description = "按实验ID列表过滤实验") String experimentIds,
            @QueryParam("force_sorting") @DefaultValue("false") @Schema(description = "即使超过端点结果集限制也强制排序。可能导致查询变慢") boolean forceSorting) {

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        var metadata = workspaceMetadataService.getExperimentMetadata(
                requestContext.get().getWorkspaceId(), datasetId)
                .block();
        if (!forceSorting && !sortingFields.isEmpty() && metadata.cannotUseDynamicSorting()) {
            sortingFields = List.of();
        }

        var experimentFilters = filtersFactory.newFilters(filters, ExperimentFilter.LIST_TYPE_REFERENCE);

        var types = Optional.ofNullable(typesQueryParam)
                .map(queryParam -> ParamsValidator.get(queryParam, ExperimentType.class, "types"))
                .orElse(null);

        var experimentIdsParsed = Optional.ofNullable(experimentIds)
                .filter(param -> !param.isBlank())
                .map(ParamsValidator::getIds)
                .orElse(null);

        var experimentSearchCriteria = ExperimentSearchCriteria.builder()
                .datasetId(datasetId)
                .name(name)
                .entityType(EntityType.TRACE)
                .datasetDeleted(datasetDeleted)
                .promptId(promptId)
                .projectId(projectId)
                .projectDeleted(projectDeleted)
                .sortingFields(sortingFields)
                .optimizationId(optimizationId)
                .types(types)
                .filters(experimentFilters)
                .experimentIds(experimentIdsParsed)
                .build();

        log.info("根据条件 '{}' 查找实验，页码 '{}'，每页大小 '{}'", experimentSearchCriteria, page, size);
        var experiments = experimentService.find(page, size, experimentSearchCriteria)
                .map(experimentPage -> {
                    if (!forceSorting && metadata.cannotUseDynamicSorting()) {
                        return experimentPage.toBuilder().sortableBy(List.of()).build();
                    }
                    return experimentPage;
                })
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已找到实验，条件 '{}'，数量 '{}'，页码 '{}'，每页大小 '{}'",
                experimentSearchCriteria, experiments.size(), page, size);

        return Response.ok().entity(experiments).build();
    }

    @GET
    @Path("/groups")
    @Operation(operationId = "findExperimentGroups", summary = "查找实验分组", description = "按指定字段分组查找实验", responses = {
            @ApiResponse(responseCode = "200", description = "实验分组", content = @Content(schema = @Schema(implementation = ExperimentGroupResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response findGroups(
            @QueryParam("groups") String groupsQueryParam,
            @QueryParam("types") String typesQueryParam,
            @QueryParam("name") @Schema(description = "按名称过滤实验（部分匹配，不区分大小写）") String name,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("project_deleted") Boolean projectDeleted,
            @QueryParam("filters") String filters) {

        // 使用GroupingFactory解析和验证groups参数
        List<GroupBy> groups = groupingFactory.newGrouping(groupsQueryParam);

        // 解析可选参数
        var types = Optional.ofNullable(typesQueryParam)
                .map(queryParam -> ParamsValidator.get(queryParam, ExperimentType.class, "types"))
                .orElse(null);

        var experimentFilters = filtersFactory.newFilters(filters, ExperimentFilter.LIST_TYPE_REFERENCE);

        var experimentGroupCriteria = ExperimentGroupCriteria.builder()
                .groups(groups)
                .name(name)
                .types(types)
                .filters(experimentFilters)
                .projectId(projectId)
                .projectDeleted(projectDeleted)
                .build();

        log.info("根据条件 '{}' 查找实验分组", experimentGroupCriteria);
        var groupResponse = experimentService.findGroups(experimentGroupCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已找到实验分组，顶层分组总数: {}", groupResponse.content().size());

        return Response.ok().entity(groupResponse).build();
    }

    @GET
    @Path("/groups/aggregations")
    @Operation(operationId = "findExperimentGroupsAggregations", summary = "查找带聚合的实验分组", description = "按指定字段分组查找实验并包含聚合指标", responses = {
            @ApiResponse(responseCode = "200", description = "带聚合的实验分组", content = @Content(schema = @Schema(implementation = ExperimentGroupAggregationsResponse.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response findGroupsAggregations(
            @QueryParam("groups") String groupsQueryParam,
            @QueryParam("types") String typesQueryParam,
            @QueryParam("name") @Schema(description = "按名称过滤实验（部分匹配，不区分大小写）") String name,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("project_deleted") @Schema(description = "按已删除项目过滤实验") Boolean projectDeleted,
            @QueryParam("filters") String filters) {

        // 使用GroupingFactory解析和验证groups参数
        List<GroupBy> groups = groupingFactory.newGrouping(groupsQueryParam);

        // 解析可选参数
        var types = Optional.ofNullable(typesQueryParam)
                .map(queryParam -> ParamsValidator.get(queryParam, ExperimentType.class, "types"))
                .orElse(null);

        var experimentFilters = filtersFactory.newFilters(filters, ExperimentFilter.LIST_TYPE_REFERENCE);

        var experimentGroupCriteria = ExperimentGroupCriteria.builder()
                .groups(groups)
                .name(name)
                .types(types)
                .filters(experimentFilters)
                .projectId(projectId)
                .projectDeleted(projectDeleted)
                .build();

        log.info("根据条件 '{}' 查找实验分组聚合", experimentGroupCriteria);
        var groupAggregationsResponse = experimentService.findGroupsAggregations(experimentGroupCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已找到实验分组聚合，顶层分组总数: {}",
                groupAggregationsResponse.content().size());

        return Response.ok().entity(groupAggregationsResponse).build();
    }

    @GET
    @Path("/{id}")
    @Operation(operationId = "getExperimentById", summary = "根据ID获取实验", description = "根据ID获取实验", responses = {
            @ApiResponse(responseCode = "200", description = "实验资源", content = @Content(schema = @Schema(implementation = Experiment.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RequiredPermissions(WorkspaceUserPermission.EXPERIMENT_VIEW)
    @JsonView(Experiment.View.Public.class)
    public Response get(@PathParam("id") UUID id) {

        log.info("根据ID '{}' 获取实验", id);
        var experiment = experimentService.getById(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已获取实验，ID '{}'，数据集ID '{}'", experiment.id(), experiment.datasetId());
        return Response.ok().entity(experiment).build();
    }

    @POST
    @Operation(operationId = "createExperiment", summary = "创建实验", description = "创建新实验", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/experiments/{id}", schema = @Schema(implementation = String.class))})})
    @RequiredPermissions(WorkspaceUserPermission.EXPERIMENT_CREATE)
    @RateLimited
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = Experiment.class))) @JsonView(Experiment.View.Write.class) @NotNull @Valid Experiment experiment,
            @Context UriInfo uriInfo) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("创建实验，ID '{}'，名称 '{}'，数据集名称 '{}'，工作区 '{}'",
                experiment.id(), experiment.name(), experiment.datasetName(), workspaceId);
        var id = experimentService.create(experiment)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        log.info("已创建实验，ID '{}'，名称 '{}'，数据集名称 '{}'，工作区 '{}'",
                id, experiment.name(), experiment.datasetName(), workspaceId);
        return Response.created(uri).build();
    }

    @PATCH
    @Path("/{id}")
    @Operation(operationId = "updateExperiment", summary = "根据ID更新实验", description = "根据ID更新实验", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response update(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentUpdate.class))) @NotNull @Valid ExperimentUpdate experimentUpdate) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("更新实验，ID '{}'，工作区 '{}'", id, workspaceId);
        experimentService.update(id, experimentUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已更新实验，ID '{}'，工作区 '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @PATCH
    @Path("/batch")
    @Operation(operationId = "batchUpdateExperiments", summary = "批量更新实验", description = "批量更新多个实验", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    public Response batchUpdate(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentBatchUpdate.class))) @Valid @NotNull ExperimentBatchUpdate batchUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("批量更新 '{}' 个实验，工作区 '{}'", batchUpdate.ids().size(), workspaceId);

        experimentService.batchUpdate(batchUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已批量更新 '{}' 个实验，工作区 '{}'", batchUpdate.ids().size(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteExperimentsById", summary = "根据ID删除实验", description = "根据ID删除实验", responses = {
            @ApiResponse(responseCode = "204", description = "无内容")})
    public Response deleteExperimentsById(
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteIdsHolder.class))) @NotNull @Valid DeleteIdsHolder request) {

        log.info("删除实验，数量 '{}'", request.ids());
        experimentService.delete(request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已删除实验，数量 '{}'", request.ids());
        return Response.noContent().build();
    }

    @POST
    @Path("/finish")
    @Operation(operationId = "finishExperiments", summary = "完成实验", description = "完成实验并触发告警事件", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RateLimited
    public Response finishExperiments(
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteIdsHolder.class))) @NotNull @Valid IdsHolder request) {

        log.info("完成实验，数量 '{}'", request.ids().size());
        experimentService.finishExperiments(request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已完成实验，数量 '{}'", request.ids().size());

        return Response.noContent().build();
    }

    @POST
    @Path("/stream")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "streamExperiments", summary = "流式传输实验", description = "流式传输实验数据", responses = {
            @ApiResponse(responseCode = "200", description = "实验流或处理过程中的错误", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    Experiment.class,
                    ErrorMessage.class
            }), maxItems = 2000)))
    })
    @JsonView(Experiment.View.Public.class)
    public ChunkedOutput<JsonNode> streamExperiments(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentStreamRequest.class))) @NotNull @Valid ExperimentStreamRequest request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();
        log.info("流式传输实验，条件 '{}'，工作区 '{}'，用户名 '{}'", request, workspaceId, userName);
        var experiments = experimentService.get(request)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId));
        var stream = streamer.getOutputStream(experiments);
        log.info("已流式传输实验，条件 '{}'，工作区 '{}'，用户名 '{}'", request, workspaceId, userName);
        return stream;
    }

    // 实验条目资源

    @GET
    @Path("/items/{id}")
    @Operation(operationId = "getExperimentItemById", summary = "根据ID获取实验条目", description = "根据ID获取实验条目", responses = {
            @ApiResponse(responseCode = "200", description = "实验条目资源", content = @Content(schema = @Schema(implementation = ExperimentItem.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @JsonView(ExperimentItem.View.Public.class)
    public Response getExperimentItem(@PathParam("id") UUID id) {

        log.info("根据ID '{}' 获取实验条目", id);
        var experimentItem = experimentItemService.get(id)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取实验条目，ID '{}'，实验ID '{}'，数据集条目ID '{}'，追踪ID '{}'",
                experimentItem.id(),
                experimentItem.experimentId(),
                experimentItem.datasetItemId(),
                experimentItem.traceId());
        return Response.ok().entity(experimentItem).build();
    }

    @POST
    @Path("/items/stream")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "streamExperimentItems", summary = "流式传输实验条目", description = "流式传输实验条目", responses = {
            @ApiResponse(responseCode = "200", description = "实验条目流或处理过程中的错误", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    ExperimentItem.class,
                    ErrorMessage.class
            }), maxItems = 2000)))
    })
    public ChunkedOutput<JsonNode> streamExperimentItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentItemStreamRequest.class))) @NotNull @Valid ExperimentItemStreamRequest request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        var userName = requestContext.get().getUserName();
        log.info("流式传输实验条目，条件 '{}'，工作区 '{}'", request, workspaceId);
        var criteria = ExperimentItemSearchCriteria.builder()
                .experimentName(request.experimentName())
                .limit(request.limit())
                .lastRetrievedId(request.lastRetrievedId())
                .truncate(request.truncate())
                .projectName(request.projectName())
                .build();
        var items = experimentItemService.getExperimentItems(criteria)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId));
        var stream = streamer.getOutputStream(items);
        log.info("已流式传输实验条目，条件 '{}'，工作区 '{}'", request, workspaceId);
        return stream;
    }

    @POST
    @Path("/items")
    @Operation(operationId = "createExperimentItems", summary = "创建实验条目", description = "创建实验条目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容")})
    @RateLimited
    @UsageLimited
    public Response createExperimentItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentItemsBatch.class))) @NotNull @Valid ExperimentItemsBatch request) {

        // 在可重试操作之前为没有ID的条目生成ID
        Set<ExperimentItem> newRequest = request.experimentItems()
                .stream()
                .map(item -> {
                    if (item.id() == null) {
                        return item.toBuilder().id(idGenerator.generateId()).build();
                    }
                    return item;
                }).collect(Collectors.toSet());

        log.info("创建实验条目，数量 '{}'", newRequest.size());
        experimentItemService.create(newRequest)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();
        log.info("已创建实验条目，数量 '{}'", newRequest.size());
        return Response.noContent().build();
    }

    @POST
    @Path("/items/delete")
    @Operation(operationId = "deleteExperimentItems", summary = "删除实验条目", description = "删除实验条目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    public Response deleteExperimentItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentItemsDelete.class))) @NotNull @Valid ExperimentItemsDelete request) {

        log.info("删除实验条目，数量 '{}'", request.ids().size());
        experimentItemService.delete(request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已删除实验条目，数量 '{}'", request.ids().size());
        return Response.noContent().build();
    }

    @PUT
    @Path("/items/bulk")
    @Operation(operationId = "experimentItemsBulk", summary = "批量记录实验条目", description = "批量记录实验条目，包含追踪、Span和反馈评分。"
            +
            "最大请求大小为4MB。", responses = {
                    @ApiResponse(responseCode = "204", description = "无内容"),
                    @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = com.comet.opik.api.error.ErrorMessage.class))),
            })
    @RateLimited
    @UsageLimited
    public Response experimentItemsBulk(
            @RequestBody(content = @Content(schema = @Schema(implementation = ExperimentItemBulkUpload.class))) @NotNull @Valid @JsonView(ExperimentItemBulkUpload.View.ExperimentItemBulkWriteView.class) ExperimentItemBulkUpload request) {

        log.info("批量记录实验条目，数量 '{}'，实验ID '{}'", request.items().size(),
                request.experimentId());

        List<ExperimentItemBulkRecord> items = request.items()
                .stream()
                .map(item -> ExperimentItemBulkMapper.addIdsIfRequired(idGenerator, item))
                .map(item -> {
                    ExperimentItemBulkValidator.validate(item);
                    return item;
                })
                .toList();

        Experiment experiment = Experiment.builder()
                .id(request.experimentId())
                .datasetName(request.datasetName())
                .name(request.experimentName())
                .projectName(request.projectName())
                .build();

        // 服务层解析项目（显式project_name，否则从现有实验或数据集派生，否则使用默认项目），并报告使用了哪个已弃用的回退。
        ExperimentItemBulkIngestionService.ProjectFallback fallback = experimentItemBulkIngestionService
                .ingest(experiment, request.projectName(), items)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();

        log.info("已批量记录实验条目，数量 '{}'，实验ID '{}'", request.items().size(),
                request.experimentId());

        Response.ResponseBuilder responseBuilder = Response.noContent();

        // 将已弃用的隐式回退作为X-Opik-Deprecation头返回（在请求线程上，因此可以安全地设置/读取请求范围的回退消息——与其他资源相同的机制）。
        switch (fallback) {
            case DATASET -> {
                requestContext.get().setWorkspaceFallbackFor("Dataset", request.datasetName());
                responseBuilder.header(RequestContext.WORKSPACE_FALLBACK_HEADER,
                        requestContext.get().getWorkspaceFallbackMessage());
            }
            case DEFAULT -> responseBuilder.header(RequestContext.WORKSPACE_FALLBACK_HEADER,
                    ("project_name could not be resolved; traces without a project were placed in the default "
                            + "project '%s'. This fallback is deprecated, please provide project_name.")
                            .formatted(ProjectService.DEFAULT_PROJECT));
            case NONE -> {
                // 无弃用信息
            }
        }

        return responseBuilder.build();
    }

    @GET
    @Path("/feedback-scores/names")
    @Operation(operationId = "findFeedbackScoreNames", summary = "查找反馈评分名称", description = "查找反馈评分名称", responses = {
            @ApiResponse(responseCode = "200", description = "反馈评分资源", content = @Content(schema = @Schema(implementation = FeedbackScoreNames.class)))
    })
    @JsonView({FeedbackDefinition.View.Public.class})
    public Response findFeedbackScoreNames(
            @QueryParam("experiment_ids") String experimentIdsQueryParam,
            @QueryParam("project_id") UUID projectId) {

        var experimentIds = Optional.ofNullable(experimentIdsQueryParam)
                .map(ParamsValidator::getIds)
                .orElse(Collections.emptySet());

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据实验IDs '{}' 和项目ID '{}' 查找反馈评分名称，工作区 '{}'",
                experimentIds, projectId, workspaceId);
        FeedbackScoreNames feedbackScoreNames = feedbackScoreService
                .getExperimentsFeedbackScoreNames(experimentIds, projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("已找到反馈评分名称 '{}'，实验IDs '{}'，项目ID '{}'，工作区 '{}'",
                feedbackScoreNames.scores().size(), experimentIds, projectId, workspaceId);

        return Response.ok(feedbackScoreNames).build();
    }

    @POST
    @Path("/execute")
    @Operation(operationId = "executeExperiment", summary = "创建并执行实验", description = "为每个提示变体创建实验并异步处理所有数据集条目", responses = {
            @ApiResponse(responseCode = "202", description = "实验已创建并开始处理", content = @Content(schema = @Schema(implementation = ExperimentExecutionResponse.class))),
    })
    @RequiredPermissions(WorkspaceUserPermission.EXPERIMENT_VIEW)
    public Response execute(@NotNull @Valid ExperimentExecutionRequest request) {
        var context = requestContext.get();
        var workspaceId = context.getWorkspaceId();
        var userName = context.getUserName();

        log.info("为数据集 '{}' 执行实验，工作区 '{}'，提示数量 '{}'",
                request.datasetName(), workspaceId, request.prompts().size());

        var response = experimentExecutionService.createAndExecute(request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.status(Response.Status.ACCEPTED)
                .entity(response)
                .build();
    }

}
