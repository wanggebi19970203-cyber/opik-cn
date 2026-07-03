package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.api.retention.RetentionRule.RetentionRulePage;
import com.comet.opik.domain.retention.RetentionRuleService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.fasterxml.jackson.annotation.JsonView;
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

@Path("/v1/private/retention/rules")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Retention Rules", description = "数据保留规则管理")
public class RetentionRulesResource {

    private final @NonNull RetentionRuleService service;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Operation(operationId = "createRetentionRule", summary = "创建保留规则", description = "创建新的保留规则。自动停用同一作用域下任何现有的活动规则。", responses = {
            @ApiResponse(responseCode = "201", description = "已创建", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/retention/rules/{ruleId}", schema = @Schema(implementation = String.class))}, content = @Content(schema = @Schema(implementation = RetentionRule.class)))
    })
    @JsonView(RetentionRule.View.Public.class)
    @RateLimited
    public Response createRule(
            @RequestBody(content = @Content(schema = @Schema(implementation = RetentionRule.class))) @JsonView(RetentionRule.View.Write.class) @NotNull @Valid RetentionRule rule,
            @Context UriInfo uriInfo) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("为工作区 '{}' 中的项目 '{}' 创建保留规则",
                rule.projectId(), workspaceId);

        RetentionRule created = service.create(rule);

        log.info("已在工作区 '{}' 中创建保留规则 '{}'", created.id(), workspaceId);

        URI uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(created.id().toString())).build();
        return Response.created(uri).entity(created).build();
    }

    @GET
    @Operation(operationId = "findRetentionRules", summary = "查找保留规则", description = "列出调用者工作区的保留规则。默认仅返回活动规则。", responses = {
            @ApiResponse(responseCode = "200", description = "保留规则分页结果", content = @Content(schema = @Schema(implementation = RetentionRulePage.class)))
    })
    @JsonView(RetentionRule.View.Public.class)
    public Response findRules(
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("include_inactive") @DefaultValue("false") boolean includeInactive) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("在工作区 '{}' 中查找保留规则，页码 '{}'，每页大小 '{}'，包含停用规则 '{}'",
                workspaceId, page, size, includeInactive);

        RetentionRulePage rulePage = service.find(page, size, includeInactive);

        log.info("在工作区 '{}' 中找到 '{}' 条保留规则", rulePage.total(), workspaceId);
        return Response.ok(rulePage).build();
    }

    @GET
    @Path("/{ruleId}")
    @Operation(operationId = "getRetentionRuleById", summary = "根据ID获取保留规则", description = "根据ID获取指定的保留规则", responses = {
            @ApiResponse(responseCode = "200", description = "保留规则", content = @Content(schema = @Schema(implementation = RetentionRule.class))),
            @ApiResponse(responseCode = "404", description = "保留规则未找到")
    })
    @JsonView(RetentionRule.View.Public.class)
    public Response getRuleById(@PathParam("ruleId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("在工作区 '{}' 中查找保留规则 '{}'", id, workspaceId);

        RetentionRule rule = service.findById(id);

        log.info("在工作区 '{}' 中找到保留规则 '{}'", id, workspaceId);
        return Response.ok().entity(rule).build();
    }

    @DELETE
    @Path("/{ruleId}")
    @Operation(operationId = "deactivateRetentionRule", summary = "停用保留规则", description = "软停用保留规则（设置enabled=false）。规则不会被硬删除以保留审计记录。", responses = {
            @ApiResponse(responseCode = "204", description = "无内容"),
            @ApiResponse(responseCode = "404", description = "保留规则未找到")
    })
    public Response deactivateRule(@PathParam("ruleId") UUID id) {

        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("在工作区 '{}' 中停用保留规则 '{}'", id, workspaceId);

        service.deactivate(id);

        log.info("已在工作区 '{}' 中停用保留规则 '{}'", id, workspaceId);
        return Response.noContent().build();
    }
}
