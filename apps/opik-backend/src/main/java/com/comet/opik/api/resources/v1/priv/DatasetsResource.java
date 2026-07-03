package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.CreateDatasetItemsFromSpansRequest;
import com.comet.opik.api.CreateDatasetItemsFromTracesRequest;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetExpansion;
import com.comet.opik.api.DatasetExpansionResponse;
import com.comet.opik.api.DatasetExportJob;
import com.comet.opik.api.DatasetExportStatus;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemBatchUpdate;
import com.comet.opik.api.DatasetItemChanges;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.JsonUploadFormat;
import com.comet.opik.api.PageColumns;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.filter.DatasetFilter;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.CsvDatasetExportService;
import com.comet.opik.domain.CsvDatasetItemProcessor;
import com.comet.opik.domain.DatasetCriteria;
import com.comet.opik.domain.DatasetExpansionService;
import com.comet.opik.domain.DatasetItemSearchCriteria;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.DemoData;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.JsonDatasetItemProcessor;
import com.comet.opik.domain.Streamer;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.bi.AnalyticsService;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.FileNameUtils;
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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
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
import org.glassfish.jersey.media.multipart.ContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.glassfish.jersey.server.ChunkedOutput;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static com.comet.opik.api.Dataset.DatasetPage;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;
import static org.apache.commons.collections4.CollectionUtils.emptyIfNull;

@Path("/v1/private/datasets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Datasets", description = "数据集资源")
public class DatasetsResource {

    private final @NonNull DatasetService service;
    private final @NonNull DatasetItemService itemService;
    private final @NonNull DatasetExpansionService expansionService;
    private final @NonNull DatasetVersionService versionService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull FiltersFactory filtersFactory;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Streamer streamer;
    private final @NonNull SortingFactoryDatasets sortingFactory;
    private final @NonNull CsvDatasetItemProcessor csvProcessor;
    private final @NonNull JsonDatasetItemProcessor jsonProcessor;
    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull CsvDatasetExportService csvExportService;
    private final @NonNull AnalyticsService analyticsService;

    @GET
    @Path("/{id}")
    @Operation(operationId = "getDatasetById", summary = "根据ID获取数据集", description = "根据ID获取数据集", responses = {
            @ApiResponse(responseCode = "200", description = "数据集资源", content = @Content(schema = @Schema(implementation = Dataset.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.DATASET_VIEW)
    @JsonView(Dataset.View.Public.class)
    public Response getDatasetById(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据ID '{}' 查找数据集，工作区 '{}'", id, workspaceId);
        Dataset dataset = service.findById(id);
        log.info("已找到数据集，ID '{}'，工作区 '{}'", id, workspaceId);

        return Response.ok().entity(dataset).build();
    }

    @GET
    @Operation(operationId = "findDatasets", summary = "查找数据集", description = "查找数据集列表", responses = {
            @ApiResponse(responseCode = "200", description = "数据集资源", content = @Content(schema = @Schema(implementation = DatasetPage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.DATASET_VIEW)
    @JsonView(Dataset.View.Public.class)
    public Response findDatasets(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("with_experiments_only") boolean withExperimentsOnly,
            @QueryParam("with_optimizations_only") boolean withOptimizationsOnly,
            @QueryParam("prompt_id") UUID promptId,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("name") @Schema(description = "按名称过滤数据集（部分匹配，不区分大小写）") String name,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters) {

        var queryFilters = filtersFactory.newFilters(filters, DatasetFilter.LIST_TYPE_REFERENCE);

        var criteria = DatasetCriteria.builder()
                .name(name)
                .withExperimentsOnly(withExperimentsOnly)
                .promptId(promptId)
                .projectId(projectId)
                .withOptimizationsOnly(withOptimizationsOnly)
                .filters(queryFilters)
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据条件 '{}' 查找数据集，排序方式: {}，工作区 '{}'", criteria, sorting, workspaceId);
        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        DatasetPage datasetPage = service.find(page, size, criteria, sortingFields);
        log.info("已找到数据集，条件 '{}'，排序方式: {}，数量 '{}'，工作区 '{}'", criteria, sorting,
                datasetPage.size(), workspaceId);

        var builder = Response.ok(datasetPage);
        return builder.build();
    }

    @POST
    @Operation(operationId = "createDataset", summary = "创建数据集", description = "创建新数据集", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/api/v1/private/datasets/{id}", schema = @Schema(implementation = String.class))
            })
    })
    @RequiredPermissions(WorkspaceUserPermission.DATASET_CREATE)
    @RateLimited
    public Response createDataset(
            @RequestBody(content = @Content(schema = @Schema(implementation = Dataset.class))) @JsonView(Dataset.View.Write.class) @NotNull @Valid Dataset dataset,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("创建数据集，名称 '{}'，工作区 '{}'", dataset.name(), workspaceId);
        Dataset savedDataset = service.save(dataset);
        log.info("已创建数据集，名称 '{}'，ID '{}'，工作区 '{}'", savedDataset.name(),
                savedDataset.id(), workspaceId);

        if (savedDataset.type() == DatasetType.TEST_SUITE
                && !DemoData.DATASETS.contains(savedDataset.name())) {
            analyticsService.trackEvent("opik_eval_suite_created", Map.of(
                    "eval_suite_id", savedDataset.id().toString(),
                    "eval_suite_name", savedDataset.name(),
                    "project_id", String.valueOf(savedDataset.projectId())));
        }

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(savedDataset.id().toString())).build();
        return Response.created(uri).build();
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updateDataset", summary = "根据ID更新数据集", description = "根据ID更新数据集", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RequiredPermissions(WorkspaceUserPermission.DATASET_EDIT)
    @RateLimited
    public Response updateDataset(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetUpdate.class))) @NotNull @Valid DatasetUpdate datasetUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("更新数据集，ID '{}'，工作区 '{}'", id, workspaceId);
        service.update(id, datasetUpdate);
        log.info("已更新数据集，ID '{}'，工作区 '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}")
    @Operation(operationId = "deleteDataset", summary = "根据ID删除数据集", description = "根据ID删除数据集", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RequiredPermissions(WorkspaceUserPermission.DATASET_DELETE)
    public Response deleteDataset(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("删除数据集，ID '{}'，工作区 '{}'", id, workspaceId);
        service.delete(id);
        log.info("已删除数据集，ID '{}'，工作区 '{}'", id, workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deleteDatasetByName", summary = "根据名称删除数据集", description = "根据名称删除数据集", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RequiredPermissions(WorkspaceUserPermission.DATASET_DELETE)
    public Response deleteDatasetByName(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetIdentifier.class))) @NotNull @Valid DatasetIdentifier identifier) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据名称 '{}' 删除数据集，工作区 '{}'", identifier.datasetName(), workspaceId);
        service.delete(identifier);
        log.info("已删除数据集，名称 '{}'，工作区 '{}'", identifier.datasetName(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete-batch")
    @Operation(operationId = "deleteDatasetsBatch", summary = "批量删除数据集", description = "批量删除数据集", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RequiredPermissions(WorkspaceUserPermission.DATASET_DELETE)
    public Response deleteDatasetsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @NotNull @Valid BatchDelete batchDelete) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("批量删除数据集，数量 '{}'，工作区 '{}'", batchDelete.ids().size(), workspaceId);
        service.delete(batchDelete.ids());
        log.info("已批量删除数据集，数量 '{}'，工作区 '{}'", batchDelete.ids().size(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/retrieve")
    @Operation(operationId = "getDatasetByIdentifier", summary = "根据名称获取数据集", description = "根据名称获取数据集", responses = {
            @ApiResponse(responseCode = "200", description = "数据集资源", content = @Content(schema = @Schema(implementation = Dataset.class))),
    })
    @JsonView(Dataset.View.Public.class)
    public Response getDatasetByIdentifier(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetIdentifier.class))) @NotNull @Valid DatasetIdentifier identifier) {

        String workspaceId = requestContext.get().getWorkspaceId();
        Visibility visibility = requestContext.get().getVisibility();

        log.info("根据名称 '{}' 查找数据集，项目名称 '{}'，工作区 '{}'", identifier.datasetName(),
                identifier.projectName(), workspaceId);
        Dataset dataset = service.findByNameDetailed(identifier, visibility);
        log.info("已找到数据集，名称 '{}'，ID '{}'，工作区 '{}'", identifier.datasetName(), dataset.id(),
                workspaceId);

        var responseBuilder = Response.ok(dataset);
        String fallbackMessage = requestContext.get().getWorkspaceFallbackMessage();
        if (fallbackMessage != null) {
            responseBuilder.header(RequestContext.WORKSPACE_FALLBACK_HEADER, fallbackMessage);
        }
        return responseBuilder.build();
    }

    @POST
    @Path("/{id}/expansions")
    @Operation(operationId = "expandDataset", summary = "使用合成样本扩展数据集", description = "基于现有数据模式使用LLM生成合成数据集样本", responses = {
            @ApiResponse(responseCode = "200", description = "生成的合成样本", content = @Content(schema = @Schema(implementation = DatasetExpansionResponse.class)))
    })
    @RateLimited
    public Response expandDataset(
            @PathParam("id") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetExpansion.class))) @JsonView(DatasetExpansion.View.Write.class) @NotNull @Valid DatasetExpansion request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("扩展数据集，ID '{}'，工作区 '{}'", datasetId, workspaceId);
        var response = expansionService.expandDataset(datasetId, request);
        log.info("已扩展数据集，ID '{}'，工作区 '{}'，总样本数 '{}'",
                datasetId, workspaceId, response.totalGenerated());
        return Response.ok(response).build();
    }

    // 数据集条目资源

    @GET
    @Path("/items/{itemId}")
    @Operation(operationId = "getDatasetItemById", summary = "根据ID获取数据集条目", description = "根据ID获取数据集条目", responses = {
            @ApiResponse(responseCode = "200", description = "数据集条目资源", content = @Content(schema = @Schema(implementation = DatasetItem.class)))
    })
    @JsonView(DatasetItem.View.Public.class)
    public Response getDatasetItemById(@PathParam("itemId") @NotNull UUID itemId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("根据ID '{}' 查找数据集条目，工作区 '{}'", itemId, workspaceId);
        DatasetItem datasetItem = itemService.get(itemId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已找到数据集条目，ID '{}'，工作区 '{}'", itemId, workspaceId);

        return Response.ok(datasetItem).build();
    }

    @PATCH
    @Path("/items/batch")
    @Operation(operationId = "batchUpdateDatasetItems", summary = "批量更新数据集条目", description = "批量更新多个数据集条目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))})
    @RateLimited
    public Response batchUpdate(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemBatchUpdate.class))) @Valid @NotNull DatasetItemBatchUpdate batchUpdate) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("批量更新数据集条目，工作区 '{}'，IDs数量 '{}'，过滤条件数量 '{}'", workspaceId,
                emptyIfNull(batchUpdate.ids()).size(), emptyIfNull(batchUpdate.filters()).size());

        itemService.batchUpdate(batchUpdate)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已批量更新数据集条目，工作区 '{}'，IDs数量 '{}'，过滤条件数量 '{}'", workspaceId,
                emptyIfNull(batchUpdate.ids()).size(), emptyIfNull(batchUpdate.filters()).size());

        return Response.noContent().build();
    }

    @PATCH
    @Path("/items/{itemId}")
    @Operation(operationId = "patchDatasetItem", summary = "根据ID部分更新数据集条目", description = "根据ID部分更新数据集条目，仅更新提供的字段", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "404", description = "数据集条目未找到")
    })
    @RateLimited
    public Response patchDatasetItem(
            @PathParam("itemId") @NotNull UUID itemId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItem.class))) @JsonView(DatasetItem.View.Write.class) @NotNull DatasetItem item) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("部分更新数据集条目，ID '{}'，工作区 '{}'", itemId, workspaceId);
        itemService.patch(itemId, item)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();
        log.info("已部分更新数据集条目，ID '{}'，工作区 '{}'", itemId, workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/items")
    @Operation(operationId = "getDatasetItems", summary = "获取数据集条目", description = "获取数据集条目列表", responses = {
            @ApiResponse(responseCode = "200", description = "数据集条目资源", content = @Content(schema = @Schema(implementation = DatasetItem.DatasetItemPage.class)))
    })
    @JsonView(DatasetItem.View.Public.class)
    public Response getDatasetItems(
            @PathParam("id") UUID id,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("version") @Schema(description = "版本哈希或标签，用于获取特定数据集版本") String version,
            @QueryParam("filters") String filters,
            @QueryParam("truncate") @Schema(description = "截断输入、输出或元数据中包含的图片") boolean truncate) {

        var queryFilters = filtersFactory.newFilters(filters, DatasetItemFilter.LIST_TYPE_REFERENCE);
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info(
                "根据ID '{}' 查找数据集条目，版本 '{}'，页码 '{}'，每页大小 '{}'，过滤条件 '{}'，工作区 '{}'",
                id, version, page, size, filters, workspaceId);

        var datasetItemSearchCriteria = DatasetItemSearchCriteria.builder()
                .datasetId(id)
                .experimentIds(Set.of()) // 普通数据集条目使用空集合
                .filters(queryFilters)
                .entityType(EntityType.TRACE)
                .truncate(truncate)
                .versionHashOrTag(version)
                .build();

        var datasetItemPage = itemService.getItems(page, size, datasetItemSearchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已找到数据集条目，ID '{}'，数量 '{}'，页码 '{}'，每页大小 '{}'，工作区 '{}'", id,
                datasetItemPage.content().size(), page, size, workspaceId);

        return Response.ok(datasetItemPage).build();
    }

    @POST
    @Path("/items/stream")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    @Operation(operationId = "streamDatasetItems", summary = "流式传输数据集条目", description = "流式传输数据集条目", responses = {
            @ApiResponse(responseCode = "200", description = "数据集条目流或处理过程中的错误", content = @Content(array = @ArraySchema(schema = @Schema(anyOf = {
                    DatasetItem.class,
                    ErrorMessage.class
            }), maxItems = 2000)))
    })
    public ChunkedOutput<JsonNode> streamDatasetItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemStreamRequest.class))) @NotNull @Valid DatasetItemStreamRequest request,
            @Context HttpServletResponse httpResponse) {
        var ctxSnapshot = requestContext.get();
        var workspaceId = ctxSnapshot.getWorkspaceId();

        // 抑制未检查的类型转换警告，因为我们已经传递了DatasetItemFilter引用给newFilters
        @SuppressWarnings("unchecked")
        List<DatasetItemFilter> queryFilters = Optional.ofNullable((List<DatasetItemFilter>) filtersFactory.newFilters(
                request.filters(), DatasetItemFilter.LIST_TYPE_REFERENCE)).orElse(List.of());

        log.info("流式传输数据集条目，数据集 '{}'，项目名称 '{}'，工作区 '{}'",
                request.datasetName(), request.projectName(), workspaceId);

        try {
            service.resolveDatasetByName(DatasetIdentifier.builder()
                    .datasetName(request.datasetName())
                    .projectName(request.projectName())
                    .build());

            var items = itemService.getItems(request, queryFilters)
                    .contextWrite(ctx -> setRequestContext(ctx, ctxSnapshot));

            ChunkedOutput<JsonNode> outputStream = streamer.getOutputStream(items);

            log.info("已流式传输数据集条目，数据集 '{}'，项目名称 '{}'，工作区 '{}'",
                    request.datasetName(), request.projectName(), workspaceId);

            String fallbackMessage = requestContext.get().getWorkspaceFallbackMessage();
            if (fallbackMessage != null) {
                httpResponse.addHeader(RequestContext.WORKSPACE_FALLBACK_HEADER, fallbackMessage);
            }

            return outputStream;
        } catch (NotFoundException ex) {
            // 可见性检查失败，返回空流以避免暴露数据集的存在
            log.info("数据集条目流为空，数据集 '{}'，项目名称 '{}'，工作区 '{}'",
                    request.datasetName(), request.projectName(), workspaceId);

            return streamer.getOutputStream(Flux.empty());
        }
    }

    /**
     * OPIK-6696: copy_from_* 坐标允许调用者将继承行的读取源固定到特定的（数据集，版本）对，
     * 避免在链式版本写入尚未复制的目标时出现多副本读写窗口问题。
     */
    @PUT
    @Path("/items")
    @Operation(operationId = "createOrUpdateDatasetItems", summary = "创建/更新数据集条目", description = """
            根据数据集条目ID创建或更新数据集条目。
            每个条目的'id'字段是稳定的标识符和更新插入键。
            提供ID以更新现有条目，或省略ID以创建新条目。

            同时设置'copy_from_dataset_id'和'copy_from_version_id'以从提供的（数据集，版本）对读取继承行，
            而不是从目标的先前版本读取。当这些字段为null时，继承行从目标的先前版本读取。""", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RateLimited
    public Response createDatasetItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemBatch.class))) @JsonView({
                    DatasetItem.View.Write.class}) @NotNull @Valid DatasetItemBatch batch) {

        // 在可重试操作之前为没有ID的条目生成ID
        List<DatasetItem> items = batch.items().stream().map(item -> {
            if (item.id() == null) {
                return item.toBuilder().id(idGenerator.generateId()).build();
            }
            return item;
        }).toList();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info(
                "批量创建数据集条目，数据集ID '{}'，数据集名称 '{}'，数量 '{}'，批次组ID '{}'，工作区 '{}'",
                batch.datasetId(), batch.datasetName(), batch.items().size(), batch.batchGroupId(), workspaceId);

        DatasetItemBatch batchWithIds = batch.toBuilder()
                .items(items)
                .build();

        itemService.save(batchWithIds)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();
        log.info(
                "已批量保存数据集条目，数据集ID '{}'，数据集名称 '{}'，数量 '{}'，批次组ID '{}'，工作区 '{}'",
                batch.datasetId(), batch.datasetName(), batch.items().size(), batch.batchGroupId(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{dataset_id}/items/from-traces")
    @Operation(operationId = "createDatasetItemsFromTraces", summary = "从追踪创建数据集条目", description = "从追踪创建带有丰富元数据的数据集条目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RateLimited
    public Response createDatasetItemsFromTraces(
            @PathParam("dataset_id") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateDatasetItemsFromTracesRequest.class))) @NotNull @Valid CreateDatasetItemsFromTracesRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("从追踪创建数据集条目，数据集 '{}'，追踪数量 '{}'，工作区 '{}'",
                datasetId, request.traceIds().size(), workspaceId);

        itemService.createFromTraces(datasetId, request.traceIds(), request.enrichmentOptions(),
                request.evaluators(), request.executionPolicy())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();

        log.info("已从追踪创建数据集条目，数据集 '{}'，追踪数量 '{}'，工作区 '{}'",
                datasetId, request.traceIds().size(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/{dataset_id}/items/from-spans")
    @Operation(operationId = "createDatasetItemsFromSpans", summary = "从Span创建数据集条目", description = "从Span创建带有丰富元数据的数据集条目", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RateLimited
    public Response createDatasetItemsFromSpans(
            @PathParam("dataset_id") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = CreateDatasetItemsFromSpansRequest.class))) @NotNull @Valid CreateDatasetItemsFromSpansRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("从Span创建数据集条目，数据集 '{}'，Span数量 '{}'，工作区 '{}'",
                datasetId, request.spanIds().size(), workspaceId);

        itemService.createFromSpans(datasetId, request.spanIds(), request.enrichmentOptions(),
                request.evaluators(), request.executionPolicy())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .retryWhen(RetryUtils.handleConnectionError())
                .block();

        log.info("已从Span创建数据集条目，数据集 '{}'，Span数量 '{}'，工作区 '{}'",
                datasetId, request.spanIds().size(), workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/items/from-csv")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(operationId = "createDatasetItemsFromCsv", summary = "从CSV文件创建数据集条目", description = "从上传的CSV文件创建数据集条目。CSV第一行应包含表头。处理以异步批次方式进行。", responses = {
            @ApiResponse(responseCode = "202", description = "已接受 - CSV处理已开始"),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @RateLimited
    public Response createDatasetItemsFromCsv(
            @FormDataParam("file") @NotNull InputStream fileInputStream,
            @FormDataParam("dataset_id") @NotNull UUID datasetId) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        Visibility visibility = requestContext.get().getVisibility();

        log.info("CSV上传请求，数据集 '{}'，工作区 '{}'", datasetId, workspaceId);

        csvProcessor.processUploadedCsv(fileInputStream, datasetId, workspaceId, userName, visibility);

        log.info("CSV上传已接受，数据集 '{}'，工作区 '{}'，异步处理中", datasetId,
                workspaceId);

        return Response.status(Response.Status.ACCEPTED).build();
    }

    @POST
    @Path("/items/from-json")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Operation(operationId = "createDatasetItemsFromJson", summary = "从JSON文件创建数据集条目", description = """
            从上传的JSON或JSONL文件创建数据集条目。JSON文件必须包含顶级对象数组。
            JSONL文件每个非空行包含一个JSON对象；不支持多行JSON对象。
            保留键（id, source, description, tags, evaluators, execution_policy）会被提取到对应的DatasetItem字段中；
            所有剩余的键构成条目的数据映射并保留其JSON类型。
            要将数据集条目链接到特定的追踪或Span，请使用专用的/items/from-traces或/items/from-spans端点。
            处理以异步批次方式进行。启用数据集版本控制时，提供的ID用作更新插入键。""", responses = {
            @ApiResponse(responseCode = "202", description = "已接受 - JSON处理已开始"),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @RateLimited
    public Response createDatasetItemsFromJson(
            @FormDataParam("file") @NotNull InputStream fileInputStream,
            @FormDataParam("dataset_id") @NotNull UUID datasetId,
            @FormDataParam("format") @NotNull JsonUploadFormat format) {

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        Visibility visibility = requestContext.get().getVisibility();

        log.info("JSON上传请求，数据集 '{}'，工作区 '{}'，格式 '{}'",
                datasetId, workspaceId, format);

        jsonProcessor.processUploadedJson(fileInputStream, datasetId, workspaceId, userName, visibility, format);

        log.info("JSON上传已接受，数据集 '{}'，工作区 '{}'，异步处理中", datasetId,
                workspaceId);

        return Response.status(Response.Status.ACCEPTED).build();
    }

    /**
     * OPIK-6696: copy_from_* 坐标允许调用者将继承行（和通过SELECT-INSERT编辑）的读取源固定到特定的（数据集，版本）对，
     * 避免在链式版本写入尚未复制的目标时出现多副本读写窗口问题。
     */
    @POST
    @Path("/{id}/items/changes")
    @Operation(operationId = "applyDatasetItemChanges", summary = "应用数据集条目变更", description = """
            对数据集版本应用增量变更（添加、编辑、删除）并进行冲突检测。

            此端点：
            - 创建包含应用变更的新版本
            - 验证baseVersion是否匹配最新版本（除非override=true）
            - 如果baseVersion过时且未设置override，则返回409冲突

            使用`override=true`查询参数强制创建版本，即使baseVersion过时。

            在请求体中同时设置'copy_from_dataset_id'和'copy_from_version_id'以从提供的（数据集，版本）对读取继承行，
            而不是从目标的先前版本读取。当这些字段为null时，继承行从目标的先前版本读取。
            """, responses = {
            @ApiResponse(responseCode = "201", description = "版本创建成功", content = @Content(schema = @Schema(implementation = DatasetVersion.class))),
            @ApiResponse(responseCode = "400", description = "请求参数错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "数据集或版本未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "版本冲突 - baseVersion不是最新版本", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @RateLimited
    @JsonView(DatasetVersion.View.Public.class)
    public Response applyDatasetItemChanges(
            @PathParam("id") UUID datasetId,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemChanges.class))) @NotNull @Valid DatasetItemChanges changes,
            @QueryParam("override") @DefaultValue("false") boolean override) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("应用数据集条目变更，数据集 '{}'，基础版本 '{}'，覆盖 '{}'，工作区 '{}'",
                datasetId, changes.baseVersion(), override, workspaceId);

        DatasetVersion newVersion = itemService.applyDeltaChanges(datasetId, changes, override)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();

        log.info("已应用变更到数据集 '{}'，创建版本 '{}'，工作区 '{}'",
                datasetId, newVersion.versionHash(), workspaceId);

        // 构建指向新创建版本的Location头
        String location = String.format("/v1/private/datasets/%s/versions/%s",
                datasetId, newVersion.id());

        return Response.status(Response.Status.CREATED)
                .entity(newVersion)
                .header("Location", location)
                .build();
    }

    @POST
    @Path("/items/delete")
    @Operation(operationId = "deleteDatasetItems", summary = "删除数据集条目", description = """
            使用以下两种模式之一删除数据集条目：
            1. **按ID删除**：提供'item_ids'以按ID删除特定条目
            2. **按过滤条件删除**：提供'dataset_id'和可选的'filters'以删除匹配条件的条目

            使用过滤条件时，空的'filters'数组将删除指定数据集中的所有条目。
            """, responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求错误 - 参数无效或字段冲突"),
    })
    @RequiredPermissions(WorkspaceUserPermission.DATASET_DELETE)
    public Response deleteDatasetItems(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetItemsDelete.class))) @NotNull @Valid DatasetItemsDelete request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info(
                "删除数据集条目，工作区 '{}'，IDs数量 '{}'，数据集ID '{}'，过滤条件数量 '{}'，批次组ID '{}'",
                workspaceId,
                emptyIfNull(request.itemIds()).size(),
                request.datasetId(),
                emptyIfNull(request.filters()).size(),
                request.batchGroupId());

        itemService.delete(request.itemIds(), request.datasetId(), request.filters(), request.batchGroupId())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info(
                "已删除数据集条目，工作区 '{}'，IDs数量 '{}'，数据集ID '{}'，过滤条件数量 '{}'，批次组ID '{}'",
                workspaceId,
                emptyIfNull(request.itemIds()).size(),
                request.datasetId(),
                emptyIfNull(request.filters()).size(),
                request.batchGroupId());

        return Response.noContent().build();
    }

    @GET
    @Path("/{id}/items/experiments/items")
    @Operation(operationId = "findDatasetItemsWithExperimentItems", summary = "查找包含实验条目的数据集条目", description = "查找包含实验条目的数据集条目", responses = {
            @ApiResponse(responseCode = "200", description = "数据集条目资源", content = @Content(schema = @Schema(implementation = DatasetItem.DatasetItemPage.class)))
    })
    @JsonView(ExperimentItem.View.Compare.class)
    public Response findDatasetItemsWithExperimentItems(
            @PathParam("id") UUID datasetId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("experiment_ids") @NotNull String experimentIdsQueryParam,
            @QueryParam("filters") String filters,
            @QueryParam("sorting") String sorting,
            @QueryParam("search") String search,
            @QueryParam("truncate") @Schema(description = "截断输入、输出或元数据中包含的图片") boolean truncate) {

        var experimentIds = ParamsValidator.getIds(experimentIdsQueryParam);

        if (experimentIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(),
                            "experiment_ids cannot be empty"))
                    .build();
        }

        var queryFilters = filtersFactory.newFilters(filters, ExperimentsComparisonFilter.LIST_TYPE_REFERENCE);

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        var datasetItemSearchCriteria = DatasetItemSearchCriteria.builder()
                .datasetId(datasetId)
                .experimentIds(experimentIds)
                .filters(queryFilters)
                .sortingFields(sortingFields)
                .search(search)
                .entityType(EntityType.TRACE)
                .truncate(truncate)
                .versionHashOrTag(null) // 当存在experimentIds时，服务层将解析为实验的版本
                .build();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("查找包含实验条目的数据集条目，条件 '{}'，页码 '{}'，每页大小 '{}'，工作区 '{}'",
                datasetItemSearchCriteria, page, size, workspaceId);

        var datasetItemPage = itemService.getItems(page, size, datasetItemSearchCriteria)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info(
                "已找到包含实验条目的数据集条目，条件 '{}'，数量 '{}'，页码 '{}'，每页大小 '{}'，工作区 '{}'",
                datasetItemSearchCriteria, datasetItemPage.content().size(), page, size, workspaceId);
        return Response.ok(datasetItemPage).build();
    }

    @Timed
    @GET
    @Path("/{id}/items/experiments/items/stats")
    @Operation(operationId = "getDatasetExperimentItemsStats", summary = "获取数据集的实验条目统计", description = "获取数据集的实验条目统计信息", responses = {
            @ApiResponse(responseCode = "200", description = "实验条目统计资源", content = @Content(schema = @Schema(implementation = com.comet.opik.api.ProjectStats.class)))
    })
    @JsonView({com.comet.opik.api.ProjectStats.ProjectStatItem.View.Public.class})
    @SuppressWarnings("unchecked")
    public Response getDatasetExperimentItemsStats(
            @PathParam("id") UUID datasetId,
            @QueryParam("experiment_ids") @NotNull String experimentIdsQueryParam,
            @QueryParam("filters") String filters) {

        var experimentIds = ParamsValidator.getIds(experimentIdsQueryParam);

        if (experimentIds.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(),
                            "experiment_ids cannot be empty"))
                    .build();
        }

        List<ExperimentsComparisonFilter> queryFilters = (List<ExperimentsComparisonFilter>) filtersFactory
                .newFilters(filters, ExperimentsComparisonFilter.LIST_TYPE_REFERENCE);

        log.info("获取实验条目统计，数据集 '{}'，实验 '{}'，过滤条件 '{}'",
                datasetId, experimentIds, filters);
        var stats = itemService.getExperimentItemsStats(datasetId, experimentIds, queryFilters)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已获取实验条目统计，数据集 '{}'，实验 '{}'，数量 '{}'", datasetId,
                experimentIds, stats.stats().size());
        return Response.ok(stats).build();
    }

    @GET
    @Path("/{id}/items/experiments/items/output/columns")
    @Operation(operationId = "getDatasetItemsOutputColumns", summary = "获取数据集条目输出列", description = "获取数据集条目输出列", responses = {
            @ApiResponse(responseCode = "200", description = "数据集条目输出列", content = @Content(schema = @Schema(implementation = PageColumns.class)))
    })
    public Response getDatasetItemsOutputColumns(
            @PathParam("id") @NotNull UUID datasetId,
            @QueryParam("experiment_ids") String experimentIdsQueryParam) {

        var experimentIds = Optional.ofNullable(experimentIdsQueryParam)
                .filter(Predicate.not(String::isEmpty))
                .map(ParamsValidator::getIds)
                .orElse(null);

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("查找追踪输出列，数据集ID '{}'，实验IDs '{}'，工作区 '{}'",
                datasetId, experimentIds, workspaceId);

        PageColumns columns = itemService.getOutputColumns(datasetId, experimentIds)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已找到追踪输出列，数据集ID '{}'，实验IDs '{}'，工作区 '{}'",
                datasetId, experimentIds, workspaceId);

        return Response.ok(columns).build();
    }

    /**
     * 数据集版本操作的子资源定位器。
     * 将/{id}/versions下的所有请求委托给DatasetVersionsResource。
     *
     * @param datasetId 路径参数中的数据集ID
     * @return 为此数据集配置的DatasetVersionsResource新实例
     */
    @Path("/{id}/versions")
    public DatasetVersionsResource versions(@PathParam("id") UUID datasetId) {
        return new DatasetVersionsResource(datasetId, versionService, requestContext, featureFlags);
    }

    // 数据集导出资源

    @POST
    @Path("/{id}/export")
    @Operation(operationId = "startDatasetExport", summary = "启动数据集CSV导出", description = "为数据集启动异步CSV导出作业。立即返回作业详情供轮询。", responses = {
            @ApiResponse(responseCode = "202", description = "导出作业已创建", content = @Content(schema = @Schema(implementation = DatasetExportJob.class))),
            @ApiResponse(responseCode = "200", description = "现有导出作业进行中", content = @Content(schema = @Schema(implementation = DatasetExportJob.class)))
    })
    @JsonView(DatasetExportJob.View.Public.class)
    @RateLimited
    public Response startDatasetExport(@PathParam("id") @NotNull UUID datasetId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("启动CSV导出，数据集 '{}'，工作区 '{}'", datasetId, workspaceId);

        // 验证数据集是否存在
        service.findById(datasetId);

        DatasetExportJob job = csvExportService.startExport(datasetId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("导出作业 '{}' 已创建/找到，数据集 '{}'，工作区 '{}'", job.id(), datasetId,
                workspaceId);

        // 如果创建了新作业（PENDING状态）返回202，如果找到现有作业返回200
        var status = job.status() == DatasetExportStatus.PENDING
                ? Response.Status.ACCEPTED
                : Response.Status.OK;

        return Response.status(status).entity(job).build();
    }

    @GET
    @Path("/export-jobs/{jobId}")
    @Operation(operationId = "getDatasetExportJob", summary = "获取数据集导出作业状态", description = "获取数据集导出作业的当前状态", responses = {
            @ApiResponse(responseCode = "200", description = "导出作业详情", content = @Content(schema = @Schema(implementation = DatasetExportJob.class))),
            @ApiResponse(responseCode = "404", description = "导出作业未找到")
    })
    @JsonView(DatasetExportJob.View.Public.class)
    public Response getDatasetExportJob(@PathParam("jobId") @NotNull UUID jobId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取导出作业 '{}'，工作区 '{}'", jobId, workspaceId);

        DatasetExportJob job = csvExportService.getJob(jobId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已找到导出作业 '{}'，状态 '{}'，工作区 '{}'", jobId, job.status(), workspaceId);

        return Response.ok(job).build();
    }

    @PUT
    @Path("/export-jobs/{jobId}/mark-viewed")
    @Operation(operationId = "markDatasetExportJobViewed", summary = "标记数据集导出作业为已查看", description = "通过设置viewed_at时间戳将数据集导出作业标记为已查看。用于跟踪用户是否已查看失败作业的错误消息。此操作是幂等的。", responses = {
            @ApiResponse(responseCode = "204", description = "作业已标记为已查看"),
            @ApiResponse(responseCode = "404", description = "导出作业未找到")
    })
    public Response markDatasetExportJobViewed(@PathParam("jobId") @NotNull UUID jobId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("标记导出作业 '{}' 为已查看，工作区 '{}'", jobId, workspaceId);

        csvExportService.markJobAsViewed(jobId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已标记导出作业 '{}' 为已查看，工作区 '{}'", jobId, workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("/export-jobs")
    @Operation(operationId = "getDatasetExportJobs", summary = "获取所有数据集导出作业", description = "获取工作区的所有导出作业。用于在页面刷新后恢复导出面板状态。", responses = {
            @ApiResponse(responseCode = "200", description = "导出作业列表", content = @Content(array = @ArraySchema(schema = @Schema(implementation = DatasetExportJob.class))))
    })
    @JsonView(DatasetExportJob.View.Public.class)
    public Response getDatasetExportJobs() {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("获取导出作业，工作区 '{}'", workspaceId);

        var jobs = csvExportService.findAllJobs()
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("已找到 '{}' 个导出作业，工作区 '{}'", jobs.size(), workspaceId);

        return Response.ok(jobs).build();
    }

    @GET
    @Path("/export-jobs/{jobId}/download")
    @Produces("text/csv")
    @Operation(operationId = "downloadDatasetExport", summary = "下载数据集导出文件", description = "下载已完成导出作业的CSV文件。此端点代理文件下载以避免暴露内部存储URL。", responses = {
            @ApiResponse(responseCode = "200", description = "CSV文件内容", content = @Content(schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "导出作业未准备好下载"),
            @ApiResponse(responseCode = "404", description = "导出作业未找到")
    })
    public Response downloadDatasetExport(@PathParam("jobId") @NotNull UUID jobId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("下载导出文件，作业 '{}'，工作区 '{}'", jobId, workspaceId);

        // 获取作业以提取数据集名称用于文件名
        DatasetExportJob job = csvExportService.getJob(jobId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        var inputStream = csvExportService.downloadExport(jobId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        // 从数据集名称生成文件名，或回退到作业ID
        String filename = FileNameUtils.buildDatasetExportFilename(job.datasetName(), jobId);

        log.info("已完成导出作业下载，作业 '{}'，工作区 '{}'", jobId, workspaceId);

        // 使用Jersey的ContentDisposition安全构建头（处理编码）
        ContentDisposition contentDisposition = ContentDisposition.type("attachment")
                .fileName(filename)
                .build();

        return Response.ok(inputStream)
                .header("Content-Disposition", contentDisposition.toString())
                .header("Content-Type", "text/csv")
                .build();
    }
}
