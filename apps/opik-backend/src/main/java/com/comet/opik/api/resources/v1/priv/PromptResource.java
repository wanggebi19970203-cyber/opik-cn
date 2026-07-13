package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Environment;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.Prompt.PromptPage;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersion.PromptVersionPage;
import com.comet.opik.api.PromptVersionBatchUpdate;
import com.comet.opik.api.PromptVersionCommitsRequest;
import com.comet.opik.api.PromptVersionEnvironmentUpdate;
import com.comet.opik.api.PromptVersionIdsRequest;
import com.comet.opik.api.PromptVersionLink;
import com.comet.opik.api.PromptVersionRetrieve;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.filter.PromptFilter;
import com.comet.opik.api.filter.PromptVersionFilter;
import com.comet.opik.api.sorting.SortingFactoryPromptVersions;
import com.comet.opik.api.sorting.SortingFactoryPrompts;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.PromptService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.comet.opik.utils.ValidationUtils;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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

import java.util.List;
import java.util.UUID;

@Path("/v1/private/prompts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Prompts", description = "提示词资源")
public class PromptResource {

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull PromptService promptService;
    private final @NonNull SortingFactoryPrompts sortingFactory;
    private final @NonNull SortingFactoryPromptVersions sortingFactoryPromptVersions;
    private final @NonNull FiltersFactory filtersFactory;

    @POST
    @Operation(operationId = "createPrompt", summary = "创建提示词", description = "创建提示词", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/prompts/{promptId}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),

    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_CREATE)
    public Response createPrompt(
            @RequestBody(content = @Content(schema = @Schema(implementation = Prompt.class))) @JsonView(Prompt.View.Write.class) @Valid Prompt prompt,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating prompt with name '{}', on workspace_id '{}'", prompt.name(), workspaceId);
        prompt = promptService.create(prompt);
        log.info("Prompt created with id '{}' name '{}', on workspace_id '{}'", prompt.id(), prompt.name(),
                workspaceId);

        var resourceUri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(prompt.id())).build();

        return Response.created(resourceUri).build();
    }

    @GET
    @Operation(operationId = "getPrompts", summary = "获取提示词列表", description = "获取提示词列表", responses = {
            @ApiResponse(responseCode = "200", description = "成功", content = @Content(schema = @Schema(implementation = PromptPage.class))),
    })
    @JsonView({Prompt.View.Public.class})
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_VIEW)
    public Response getPrompts(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("name") @Schema(description = "按名称过滤提示词（部分匹配，不区分大小写）") String name,
            @QueryParam("project_id") UUID projectId,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompts by name '{}' on workspace_id '{}', page '{}', size '{}'", name, workspaceId, page,
                size);

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        var promptFilters = filtersFactory.newFilters(filters, PromptFilter.LIST_TYPE_REFERENCE);
        PromptPage promptPage = promptService.find(name, projectId, page, size, sortingFields, promptFilters);

        log.info("Got prompts by name '{}', count '{}' on workspace_id '{}', count '{}'", name, promptPage.size(),
                workspaceId, promptPage.size());

        return Response.ok(promptPage).build();
    }

    @GET
    @Path("{id}")
    @Operation(operationId = "getPromptById", summary = "按 ID 获取提示词", description = "按 ID 获取提示词；当提供 mask_id 或 environment 时，requestedVersion 将填充为解析后的版本。mask_id 和 environment 互斥。", responses = {
            @ApiResponse(responseCode = "200", description = "提示词资源", content = @Content(schema = @Schema(implementation = Prompt.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView({Prompt.View.Detail.class})
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_VIEW)
    public Response getPromptById(@PathParam("id") UUID id,
            @Parameter(description = "可选的 mask 版本 ID；设置后 requestedVersion 为该 ID 对应的 mask 行") @QueryParam("mask_id") UUID maskId,
            @Parameter(description = "可选的环境名称；设置后 requestedVersion 为该提示词映射到该环境的版本") @QueryParam("environment") @Pattern(regexp = Environment.NAME_PATTERN, message = Environment.NAME_PATTERN_MESSAGE) @Size(max = 150, message = "cannot exceed 150 characters") String environment) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompt by id '{}', mask_id '{}', environment '{}' on workspace_id '{}'", id, maskId,
                environment, workspaceId);

        Prompt prompt = promptService.getById(id, maskId, environment);

        log.info("Got prompt by id '{}', mask_id '{}', environment '{}' on workspace_id '{}'", id, maskId,
                environment, workspaceId);

        return Response.ok(prompt).build();
    }

    @PUT
    @Path("{id}")
    @Operation(operationId = "updatePrompt", summary = "更新提示词", description = "更新提示词", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_EDIT)
    public Response updatePrompt(
            @PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = Prompt.class))) @JsonView(Prompt.View.Updatable.class) @Valid Prompt prompt) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Updating prompt with id '{}' on workspace_id '{}'", id, workspaceId);
        promptService.update(id, prompt);
        log.info("Updated prompt with id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @DELETE
    @Path("{id}")
    @Operation(operationId = "deletePrompt", summary = "删除提示词", description = "删除提示词", responses = {
            @ApiResponse(responseCode = "204", description = "无内容")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_DELETE)
    public Response deletePrompt(@PathParam("id") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting prompt by id '{}' on workspace_id '{}'", id, workspaceId);
        promptService.delete(id);
        log.info("Deleted prompt by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.noContent().build();
    }

    @POST
    @Path("/delete")
    @Operation(operationId = "deletePromptsBatch", summary = "批量删除提示词", description = "批量删除提示词", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
    })
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_DELETE)
    public Response deletePromptsBatch(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = BatchDelete.class))) @Valid BatchDelete batchDelete) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting prompts by ids, count '{}', on workspace_id '{}'", batchDelete.ids().size(), workspaceId);
        promptService.delete(batchDelete.ids());
        log.info("Deleted prompts by ids, count '{}', on workspace_id '{}'", batchDelete.ids().size(), workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/versions/retrieve-by-ids")
    @Operation(operationId = "retrievePromptVersionsByIds", summary = "按 ID 批量获取提示词版本", description = "按 ID 批量获取提示词版本。通常由 UI 用于解析 mask 覆盖层。", responses = {
            @ApiResponse(responseCode = "200", description = "成功", content = @Content(array = @ArraySchema(schema = @Schema(implementation = PromptVersion.class)))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @JsonView({PromptVersion.View.Detail.class})
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_VIEW)
    public Response retrievePromptVersionsByIds(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = PromptVersionIdsRequest.class))) @Valid PromptVersionIdsRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Retrieving prompt versions by ids, count '{}', on workspace_id '{}'",
                request.ids().size(), workspaceId);

        List<PromptVersion> versions = promptService.retrieveVersionsByIds(request.ids());

        log.info("Retrieved prompt versions by ids, requested '{}', returned '{}', on workspace_id '{}'",
                request.ids().size(), versions.size(), workspaceId);

        return Response.ok(versions).build();
    }

    @POST
    @Path("/retrieve-by-commits")
    @Operation(operationId = "getPromptsByCommits", summary = "按提交获取提示词", description = "按提示词版本提交获取提示词", responses = {
            @ApiResponse(responseCode = "200", description = "成功", content = @Content(array = @ArraySchema(schema = @Schema(implementation = PromptVersionLink.class)))),
    })
    @JsonView({Prompt.View.Public.class})
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_VIEW)
    public Response getPromptsByCommits(
            @NotNull @RequestBody(content = @Content(schema = @Schema(implementation = PromptVersionCommitsRequest.class))) @Valid PromptVersionCommitsRequest request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompts by commits, count '{}', on workspace_id '{}'",
                request.commits().size(), workspaceId);

        var prompts = promptService.getByCommits(request.commits());

        log.info("Got prompts by commits, count '{}', on workspace_id '{}'",
                prompts.size(), workspaceId);

        return Response.ok(prompts).build();
    }

    @GET
    @Path("/by-commit/{commit}")
    @Operation(operationId = "getPromptByCommit", summary = "按提交获取提示词", description = "按提交获取提示词", responses = {
            @ApiResponse(responseCode = "200", description = "成功", content = @Content(schema = @Schema(implementation = Prompt.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView({Prompt.View.Detail.class})
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_VIEW)
    public Response getPromptByCommit(
            @PathParam("commit") @Pattern(regexp = ValidationUtils.COMMIT_PATTERN) String commit) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompt by commit '{}' on workspace_id '{}'", commit, workspaceId);

        Prompt result = promptService.getByCommit(commit);

        log.info("Got prompt by commit '{}' on workspace_id '{}'", commit, workspaceId);

        return Response.ok(result).build();
    }

    @POST
    @Path("/versions")
    @Operation(operationId = "createPromptVersion", summary = "创建提示词版本", description = "创建提示词版本", responses = {
            @ApiResponse(responseCode = "200", description = "成功", content = @Content(schema = @Schema(implementation = PromptVersion.class))),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "冲突", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_EDIT)
    @JsonView({PromptVersion.View.Detail.class})
    public Response createPromptVersion(
            @RequestBody(content = @Content(schema = @Schema(implementation = CreatePromptVersion.class))) @JsonView({
                    PromptVersion.View.Detail.class}) @Valid CreatePromptVersion promptVersion) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Creating prompt version commit '{}' on workspace_id '{}'", promptVersion.version().commit(),
                workspaceId);

        var createdVersion = promptService.createPromptVersion(promptVersion);

        log.info("Created prompt version commit '{}'  with id '{}' on workspace_id '{}'",
                promptVersion.version().commit(), createdVersion.id(), workspaceId);

        return Response.ok(createdVersion).build();
    }

    @GET
    @Path("/{id}/versions")
    @Operation(operationId = "getPromptVersions", summary = "获取提示词版本列表", description = "获取提示词版本列表", responses = {
            @ApiResponse(responseCode = "200", description = "成功", content = @Content(schema = @Schema(implementation = PromptVersionPage.class))),
    })
    @JsonView({PromptVersion.View.Public.class})
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_VIEW)
    public Response getPromptVersions(@PathParam("id") UUID id,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @Parameter(description = "在模板或变更描述字段中搜索的文本") @QueryParam("search") String search,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Getting prompt versions by id '{}' on workspace_id '{}', page '{}', size '{}'",
                id, workspaceId, page, size);
        var sortingFields = sortingFactoryPromptVersions.newSorting(sorting);
        var versionFilters = filtersFactory.newFilters(filters, PromptVersionFilter.LIST_TYPE_REFERENCE);
        var promptVersionPage = promptService.getVersionsByPromptId(
                id, search, page, size, sortingFields, versionFilters);
        log.info("Got prompt versions by id '{}' on workspace_id '{}', count '{}'",
                id, workspaceId, promptVersionPage.size());
        return Response.ok(promptVersionPage).build();
    }

    @GET
    @Path("/versions/{versionId}")
    @Operation(operationId = "getPromptVersionById", summary = "按 ID 获取提示词版本", description = "按 ID 获取提示词版本", responses = {
            @ApiResponse(responseCode = "200", description = "提示词版本资源", content = @Content(schema = @Schema(implementation = PromptVersion.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView({PromptVersion.View.Detail.class})
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_VIEW)
    public Response getPromptVersionById(@PathParam("versionId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompt version by id '{}' on workspace_id '{}'", id, workspaceId);

        PromptVersion promptVersion = promptService.getVersionById(id);

        log.info("Got prompt version by id '{}' on workspace_id '{}'", id, workspaceId);

        return Response.ok(promptVersion).build();
    }

    @GET
    @Path("/{promptId}/versions/by-number/{versionNumber}")
    @Operation(operationId = "getPromptVersionByNumber", summary = "按序号获取提示词版本", description = "按给定提示词的顺序编号 v<N> 获取提示词版本。", responses = {
            @ApiResponse(responseCode = "200", description = "提示词版本资源", content = @Content(schema = @Schema(implementation = PromptVersion.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView({PromptVersion.View.Detail.class})
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_VIEW)
    public Response getPromptVersionByNumber(@PathParam("promptId") UUID promptId,
            @PathParam("versionNumber") @Pattern(regexp = "v\\d+", message = "must match v<N>") @Size(max = 10, message = "cannot exceed 10 characters") String versionNumber) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Getting prompt version by prompt_id '{}', version_number '{}' on workspace_id '{}'",
                promptId, versionNumber, workspaceId);

        PromptVersion promptVersion = promptService.getVersionByNumber(promptId, versionNumber);

        log.info("Got prompt version by prompt_id '{}', version_number '{}' on workspace_id '{}'",
                promptId, versionNumber, workspaceId);

        return Response.ok(promptVersion).build();
    }

    @PATCH
    @Path("/versions")
    @Operation(operationId = "updatePromptVersions", summary = "更新提示词版本", description = """
            更新一个或多个提示词版本。

            注意：提示词版本在设计上是不可变的。
            仅可更新组织属性，如标签等。
            核心属性（如模板和元数据）在创建后不可修改。

            PATCH 语义：
            - 非空值更新字段
            - null 值保留现有字段值（不变）
            - 空值显式清除字段
            """, responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_EDIT)
    public Response updatePromptVersions(
            @RequestBody(content = @Content(schema = @Schema(implementation = PromptVersionBatchUpdate.class))) @Valid @NotNull PromptVersionBatchUpdate request) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Updating prompt versions on workspaceId '{}', size '{}', mergeTags '{}'",
                workspaceId, request.ids().size(), request.mergeTags());
        var updatedCount = promptService.updateVersions(request);
        log.info("Successfully updated prompt versions on workspaceId '{}', size '{}', mergeTags '{}'",
                workspaceId, updatedCount, request.mergeTags());
        return Response.noContent().build();
    }

    @PATCH
    @Path("/versions/{versionId}/environments")
    @Operation(operationId = "setPromptVersionEnvironment", summary = "设置提示词版本环境", description = """
            设置或清除提示词版本所属的环境。
            设置非空环境会原子性地转移所有权：同一提示词中该环境的前一个所有者将在同一事务中清除其环境。
            设置 null 将清除该版本的环境。
            环境必须已存在于工作空间注册表中；未知名称将返回 404。
            """, responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_EDIT)
    public Response setPromptVersionEnvironment(@PathParam("versionId") UUID versionId,
            @RequestBody(content = @Content(schema = @Schema(implementation = PromptVersionEnvironmentUpdate.class))) @Valid @NotNull PromptVersionEnvironmentUpdate request) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Setting environments '{}' on prompt version '{}' on workspace_id '{}'",
                request.environments(), versionId, workspaceId);
        promptService.setVersionEnvironment(versionId, request.environments());
        log.info("Successfully set environments '{}' on prompt version '{}' on workspace_id '{}'",
                request.environments(), versionId, workspaceId);
        return Response.noContent().build();
    }

    @POST
    @Path("/versions/retrieve")
    @Operation(operationId = "retrievePromptVersion", summary = "检索提示词版本", description = "检索提示词版本", responses = {
            @ApiResponse(responseCode = "200", description = "成功", content = @Content(schema = @Schema(implementation = PromptVersion.class))),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
    })
    @JsonView({PromptVersion.View.Detail.class})
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_VIEW)
    public Response retrievePromptVersion(
            @RequestBody(content = @Content(schema = @Schema(implementation = PromptVersionRetrieve.class))) @Valid PromptVersionRetrieve request) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info(
                "Retrieving prompt name '{}' with commit '{}', environment '{}', version_number '{}' on workspace_id '{}'",
                request.name(), request.commit(), request.environment(), request.versionNumber(), workspaceId);

        PromptVersion promptVersion = promptService.retrievePromptVersion(
                request.name(), request.commit(), request.environment(), request.versionNumber(),
                request.projectName());

        log.info(
                "Retrieved prompt name '{}' with commit '{}', environment '{}', version_number '{}' on workspace_id '{}'",
                request.name(), request.commit(), request.environment(), request.versionNumber(), workspaceId);

        var responseBuilder = Response.ok(promptVersion);
        String fallbackMessage = requestContext.get().getWorkspaceFallbackMessage();
        if (fallbackMessage != null) {
            responseBuilder.header(RequestContext.WORKSPACE_FALLBACK_HEADER, fallbackMessage);
        }
        return responseBuilder.build();
    }

    @POST
    @Path("/{promptId}/versions/{versionId}/restore")
    @Operation(operationId = "restorePromptVersion", summary = "恢复提示词版本", description = "通过使用指定版本的内容创建新版本来恢复提示词版本", responses = {
            @ApiResponse(responseCode = "200", description = "成功", content = @Content(schema = @Schema(implementation = PromptVersion.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
    })
    @RateLimited
    @RequiredPermissions(WorkspaceUserPermission.PROMPT_EDIT)
    @JsonView({PromptVersion.View.Detail.class})
    public Response restorePromptVersion(@PathParam("promptId") UUID promptId, @PathParam("versionId") UUID versionId) {

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Restoring prompt version with id '{}' for prompt id '{}' on workspace_id '{}'",
                versionId, promptId, workspaceId);

        PromptVersion restoredVersion = promptService.restorePromptVersion(promptId, versionId);

        log.info("Successfully restored prompt version with id '{}' for prompt id '{}' on workspace_id '{}'",
                versionId, promptId, workspaceId);

        return Response.ok(restoredVersion).build();
    }

}
