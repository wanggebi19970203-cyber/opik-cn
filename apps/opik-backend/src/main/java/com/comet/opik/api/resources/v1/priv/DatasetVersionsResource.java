package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionDiff;
import com.comet.opik.api.DatasetVersionRestore;
import com.comet.opik.api.DatasetVersionRetrieveRequest;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.DatasetVersionUpdate;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.inject.Inject;
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

/**
 * 数据集版本操作的子资源。
 * 处理 /datasets/{id}/versions 下的所有端点
 */
@Slf4j
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DatasetVersionsResource {
    private final @NonNull UUID datasetId;
    private final @NonNull DatasetVersionService versionService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull FeatureFlags featureFlags;

    @GET
    @Operation(operationId = "listDatasetVersions", summary = "列出数据集版本", description = "获取数据集的分页版本列表，按创建时间排序（最新优先）", responses = {
            @ApiResponse(responseCode = "200", description = "数据集版本列表", content = @Content(schema = @Schema(implementation = DatasetVersionPage.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView(DatasetVersion.View.Public.class)
    public Response listVersions(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Listing versions for dataset '{}', page '{}', size '{}' on workspace '{}'", datasetId, page, size,
                workspaceId);
        DatasetVersionPage versionPage = versionService.getVersions(datasetId, page, size);
        log.info("Found '{}' versions for dataset '{}' on workspace '{}'", versionPage.total(), datasetId,
                workspaceId);

        return Response.ok(versionPage).build();
    }

    @POST
    @Path("/retrieve")
    @Operation(operationId = "retrieveDatasetVersion", summary = "根据名称获取数据集版本", description = "通过版本名称获取特定版本（如 'v1'、'v373'）。对于大型数据集，此方式比分页遍历所有版本更高效。", responses = {
            @ApiResponse(responseCode = "200", description = "数据集版本", content = @Content(schema = @Schema(implementation = DatasetVersion.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "版本未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @RateLimited
    @JsonView(DatasetVersion.View.Public.class)
    public Response retrieveVersion(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetVersionRetrieveRequest.class))) @Valid @NotNull DatasetVersionRetrieveRequest request) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving version '{}' for dataset '{}' on workspace '{}'", request.versionName(), datasetId,
                workspaceId);
        DatasetVersion version = versionService.getVersionByName(datasetId, request.versionName());
        log.info("Found version '{}' for dataset '{}' on workspace '{}'", request.versionName(), datasetId,
                workspaceId);

        return Response.ok(version).build();
    }

    @PATCH
    @Path("/hash/{versionHash}")
    @Operation(operationId = "updateDatasetVersion", summary = "更新数据集版本", description = "更新数据集版本的变更描述和/或添加新标签", responses = {
            @ApiResponse(responseCode = "200", description = "版本更新成功", content = @Content(schema = @Schema(implementation = DatasetVersion.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到 - 版本未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "冲突 - 标签已存在", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    @JsonView(DatasetVersion.View.Public.class)
    public Response updateVersion(
            @PathParam("versionHash") String versionHash,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetVersionUpdate.class))) @Valid @NotNull DatasetVersionUpdate request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating version '{}' for dataset '{}' on workspace '{}'", versionHash, datasetId, workspaceId);
        DatasetVersion version = versionService.updateVersion(datasetId, versionHash, request);
        log.info("Updated version '{}' for dataset '{}' on workspace '{}'", versionHash, datasetId, workspaceId);

        return Response.ok(version).build();
    }

    @POST
    @Path("/hash/{versionHash}/tags")
    @Operation(operationId = "createVersionTag", summary = "创建版本标签", description = "为特定数据集版本添加标签以便于引用（如 'baseline'、'v1.0'、'production'）", responses = {
            @ApiResponse(responseCode = "204", description = "标签创建成功"),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到 - 版本未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "冲突 - 标签已存在", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    public Response createTag(
            @PathParam("versionHash") String versionHash,
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetVersionTag.class))) @Valid @NotNull DatasetVersionTag tag) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating tag '{}' for version '{}' of dataset '{}' on workspace '{}'", tag.tag(), versionHash,
                datasetId, workspaceId);
        versionService.createTag(datasetId, versionHash, tag);
        log.info("Created tag '{}' for version '{}' of dataset '{}' on workspace '{}'", tag.tag(), versionHash,
                datasetId, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{versionHash}/tags/{tag}")
    @Operation(operationId = "deleteVersionTag", summary = "删除版本标签", description = "从数据集版本中移除标签。版本本身不会被删除，仅移除标签引用。", responses = {
            @ApiResponse(responseCode = "204", description = "标签删除成功"),
    })
    @RateLimited
    public Response deleteTag(
            @PathParam("versionHash") String versionHash,
            @PathParam("tag") String tag) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting tag '{}' for version '{}' from dataset '{}' on workspace '{}'", tag, versionHash, datasetId,
                workspaceId);
        versionService.deleteTag(datasetId, tag);
        log.info("Deleted tag '{}' for version '{}' from dataset '{}' on workspace '{}'", tag, versionHash, datasetId,
                workspaceId);

        return Response.noContent().build();
    }

    @GET
    @Path("/diff")
    @Operation(operationId = "compareDatasetVersions", summary = "比较最新版本与草稿", description = "比较最新已提交的数据集版本与当前草稿状态。此端点提供自上次版本提交以来所做更改的洞察。比较计算最新版本快照与当前草稿之间的新增、修改、删除和未更改项。", responses = {
            @ApiResponse(responseCode = "200", description = "差异计算成功", content = @Content(schema = @Schema(implementation = DatasetVersionDiff.class))),
            @ApiResponse(responseCode = "404", description = "版本未找到")})
    @RateLimited
    public Response compareVersions() {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Comparing latest version with draft for dataset='{}', workspace='{}'",
                datasetId, workspaceId);

        var diff = versionService.compareVersions(datasetId, DatasetVersionService.LATEST_TAG, null);

        log.info(
                "Computed diff for dataset='{}', from='latest', to='draft': stats='{}'", datasetId,
                diff.statistics());

        return Response.ok(diff).build();
    }

    @POST
    @Path("/restore")
    @Operation(operationId = "restoreDatasetVersion", summary = "将数据集恢复到指定版本", description = "通过创建一个从指定版本复制项目的新版本，将数据集恢复到之前的版本状态。如果该版本已是最新版本，则原样返回（无操作）。", responses = {
            @ApiResponse(responseCode = "200", description = "版本恢复成功", content = @Content(schema = @Schema(implementation = DatasetVersion.class))),
            @ApiResponse(responseCode = "404", description = "版本未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))})
    @RateLimited
    @JsonView(DatasetVersion.View.Public.class)
    public Response restoreVersion(
            @RequestBody(content = @Content(schema = @Schema(implementation = DatasetVersionRestore.class))) @Valid @NotNull DatasetVersionRestore request) {
        featureFlags.checkDatasetVersioningEnabled();

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Restoring dataset '{}' to version '{}' on workspace '{}'", datasetId, request.versionRef(),
                workspaceId);
        DatasetVersion version = versionService.restoreVersion(datasetId, request.versionRef())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();
        log.info("Restored dataset '{}' to version '{}' on workspace '{}'", datasetId, request.versionRef(),
                workspaceId);

        return Response.ok(version).build();
    }
}
