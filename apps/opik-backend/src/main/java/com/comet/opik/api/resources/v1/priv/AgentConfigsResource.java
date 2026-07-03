package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentConfigCreate;
import com.comet.opik.api.AgentConfigEnvSetByName;
import com.comet.opik.api.AgentConfigEnvUpdate;
import com.comet.opik.api.AgentConfigRemoveValues;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.AgentBlueprint;
import com.comet.opik.domain.AgentConfig;
import com.comet.opik.domain.AgentConfigService;
import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
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

import java.net.URI;
import java.util.UUID;

@Path("/v1/private/agent-configs")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Agent Configs", description = "智能体配置管理")
public class AgentConfigsResource {

    private final @NonNull AgentConfigService agentConfigService;

    @POST
    @Path("/blueprints")
    @JsonView(AgentConfig.View.Write.class)
    @Operation(operationId = "createAgentConfig", summary = "创建带初始蓝图的优化器配置", description = "创建带初始蓝图的新优化器配置。如果项目已有配置则失败。", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/agent-configs/blueprints/{blueprint_id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "400", description = "错误请求（例如不允许MASK类型）", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "409", description = "冲突（配置已存在）", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response createAgentConfig(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigCreate.class))) @NotNull @Valid AgentConfigCreate request,
            @Context UriInfo uriInfo) {

        log.info("Creating config for project '{}'", request.projectName());

        AgentBlueprint createdBlueprint = agentConfigService.createConfig(request).block();

        log.info("Created config with blueprint '{}' for project '{}'", createdBlueprint.id(), request.projectName());

        URI location = uriInfo.getAbsolutePathBuilder()
                .path(createdBlueprint.id().toString())
                .build();

        return Response.created(location)
                .entity(createdBlueprint)
                .build();
    }

    @POST
    @Path("/blueprints/projects/{project_id}/masks/{mask_id}")
    @JsonView(AgentConfig.View.Write.class)
    @Operation(operationId = "createBlueprintFromMask", summary = "从掩码创建蓝图", description = "通过在项目最新蓝图上应用掩码的更改来创建新蓝图。", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/agent-configs/blueprints/{blueprint_id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "404", description = "未找到（无配置或掩码未找到）", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response createBlueprintFromMask(
            @PathParam("project_id") UUID projectId,
            @PathParam("mask_id") UUID maskId,
            @Context UriInfo uriInfo) {

        log.info("Creating blueprint from mask '{}' for project '{}'", maskId, projectId);

        AgentBlueprint blueprint = agentConfigService.createBlueprintFromMask(projectId, maskId).block();

        log.info("Created blueprint '{}' from mask '{}' for project '{}'", blueprint.id(), maskId, projectId);

        URI location = uriInfo.getBaseUriBuilder()
                .path("v1/private/agent-configs/blueprints")
                .path(blueprint.id().toString())
                .build();

        return Response.created(location)
                .build();
    }

    @POST
    @Path("/blueprints/remove-keys")
    @Operation(operationId = "removeConfigKeys", summary = "移除配置参数", description = "通过创建关闭指定键的新蓝图来移除配置参数。如果无需更改则返回204（幂等）。", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/agent-configs/blueprints/{blueprint_id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "204", description = "无需更改（无配置或键已被移除）"),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response removeConfigKeys(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigRemoveValues.class))) @NotNull @Valid AgentConfigRemoveValues request,
            @Context UriInfo uriInfo) {

        String projectIdentifier = request.projectId() != null
                ? request.projectId().toString()
                : request.projectName();
        log.info("Removing config keys for project '{}'", projectIdentifier);

        AgentBlueprint blueprint = agentConfigService.removeConfigKeys(request).block();

        if (blueprint == null) {
            log.info("No config keys to remove for project '{}'", projectIdentifier);
            return Response.noContent().build();
        }

        log.info("Removed config keys, created blueprint '{}' for project '{}'", blueprint.id(),
                projectIdentifier);

        URI location = uriInfo.getBaseUriBuilder()
                .path("v1/private/agent-configs/blueprints")
                .path(blueprint.id().toString())
                .build();

        return Response.created(location)
                .build();
    }

    @PATCH
    @Path("/blueprints")
    @JsonView(AgentConfig.View.Write.class)
    @Operation(operationId = "updateAgentConfig", summary = "向现有配置添加蓝图", description = "向现有优化器配置添加新蓝图。如果项目尚无配置则失败。", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/agent-configs/blueprints/{blueprint_id}", schema = @Schema(implementation = String.class))}),
            @ApiResponse(responseCode = "404", description = "未找到（项目无配置）", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response updateAgentConfig(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigCreate.class))) @NotNull @Valid AgentConfigCreate request,
            @Context UriInfo uriInfo) {

        log.info("Adding blueprint to config for project '{}'", request.projectName());

        AgentBlueprint blueprint = agentConfigService.updateConfig(request).block();

        log.info("Added blueprint '{}' to config for project '{}'", blueprint.id(), request.projectName());

        URI location = uriInfo.getAbsolutePathBuilder()
                .path(blueprint.id().toString())
                .build();

        return Response.created(location)
                .build();
    }

    @GET
    @Path("/blueprints/latest/projects/{project_id}")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getLatestBlueprint", summary = "获取最新蓝图", description = "获取项目的最新蓝图", responses = {
            @ApiResponse(responseCode = "200", description = "蓝图已获取", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getLatestBlueprint(
            @PathParam("project_id") UUID projectId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving latest blueprint for project '{}'", projectId);

        AgentBlueprint blueprint = agentConfigService.getLatestBlueprint(projectId, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/blueprints/{blueprint_id}")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getBlueprintById", summary = "根据ID获取蓝图", description = "根据ID获取特定蓝图", responses = {
            @ApiResponse(responseCode = "200", description = "蓝图已获取", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintById(
            @PathParam("blueprint_id") UUID blueprintId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving blueprint '{}'", blueprintId);

        AgentBlueprint blueprint = agentConfigService.getBlueprintById(blueprintId, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/blueprints/projects/{project_id}/names/{name}")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getBlueprintByName", summary = "根据名称获取蓝图", description = "根据项目内的名称获取特定蓝图", responses = {
            @ApiResponse(responseCode = "200", description = "蓝图已获取", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintByName(
            @PathParam("name") String name,
            @PathParam("project_id") UUID projectId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving blueprint by name '{}' for project '{}'", name, projectId);

        AgentBlueprint blueprint = agentConfigService.getBlueprintByName(projectId, name, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/blueprints/environments/{env_name}/projects/{project_id}")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getBlueprintByEnv", summary = "根据环境获取蓝图", description = "获取与特定环境关联的蓝图", responses = {
            @ApiResponse(responseCode = "200", description = "蓝图已获取", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintByEnv(
            @PathParam("env_name") String envName,
            @PathParam("project_id") UUID projectId,
            @QueryParam("mask_id") UUID maskId) {

        log.info("Retrieving blueprint by environment '{}' for project '{}'", envName, projectId);

        AgentBlueprint blueprint = agentConfigService.getBlueprintByEnv(projectId, envName, maskId);

        return Response.ok(blueprint).build();
    }

    @GET
    @Path("/blueprints/{blueprint_id}/deltas")
    @JsonView(AgentConfig.View.Public.class)
    @Operation(operationId = "getDeltaById", summary = "根据蓝图ID获取增量", description = "仅获取特定蓝图引入的更改（增量）", responses = {
            @ApiResponse(responseCode = "200", description = "增量已获取", content = @Content(schema = @Schema(implementation = AgentBlueprint.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getDeltaById(@PathParam("blueprint_id") UUID blueprintId) {

        log.info("Retrieving delta for blueprint '{}'", blueprintId);

        AgentBlueprint delta = agentConfigService.getDeltaById(blueprintId);

        return Response.ok(delta).build();
    }

    @POST
    @Path("/blueprints/environments")
    @Operation(operationId = "createOrUpdateEnvs", summary = "创建或更新环境", description = "创建或更新环境到蓝图的映射", responses = {
            @ApiResponse(responseCode = "204", description = "环境已更新"),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response createOrUpdateEnvs(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigEnvUpdate.class))) @NotNull @Valid AgentConfigEnvUpdate request) {

        log.info("Creating or updating environments for project '{}'", request.projectId());

        agentConfigService.createOrUpdateEnvs(request).block();

        return Response.noContent().build();
    }

    @PUT
    @Path("/blueprints/environments/{env_name}/projects/{project_id}")
    @Operation(operationId = "setEnvByBlueprintName", summary = "根据蓝图名称设置环境", description = "将环境指向按名称标识的蓝图", responses = {
            @ApiResponse(responseCode = "204", description = "环境已更新"),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response setEnvByBlueprintName(
            @PathParam("env_name") String envName,
            @PathParam("project_id") UUID projectId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentConfigEnvSetByName.class))) @NotNull @Valid AgentConfigEnvSetByName request) {

        log.info("Setting environment '{}' to blueprint '{}' for project '{}'",
                envName, request.blueprintName(), projectId);

        agentConfigService.setEnvByBlueprintName(projectId, envName, request.blueprintName()).block();

        return Response.noContent().build();
    }

    @DELETE
    @Path("/blueprints/environments/{env_name}/projects/{project_id}")
    @Operation(operationId = "deleteEnv", summary = "删除环境", description = "通过设置ended_at时间戳软删除环境", responses = {
            @ApiResponse(responseCode = "204", description = "环境已删除"),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response deleteEnv(
            @PathParam("env_name") String envName,
            @PathParam("project_id") UUID projectId) {

        log.info("Deleting environment '{}' for project '{}'", envName, projectId);

        agentConfigService.deleteEnv(projectId, envName);

        return Response.noContent().build();
    }

    @GET
    @Path("/blueprints/history/projects/{project_id}")
    @JsonView(AgentConfig.View.History.class)
    @Operation(operationId = "getBlueprintHistory", summary = "获取蓝图历史", description = "获取项目的分页蓝图历史", responses = {
            @ApiResponse(responseCode = "200", description = "历史已获取", content = @Content(schema = @Schema(implementation = AgentBlueprint.BlueprintPage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getBlueprintHistory(
            @PathParam("project_id") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size) {

        log.info("Retrieving blueprint history for project '{}', page {}, size {}", projectId, page, size);

        AgentBlueprint.BlueprintPage historyPage = agentConfigService.getHistory(projectId, page, size);

        return Response.ok(historyPage).build();
    }
}
