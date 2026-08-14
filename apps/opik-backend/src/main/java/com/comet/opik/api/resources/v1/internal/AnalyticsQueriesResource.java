package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AnalyticsQueryRequest;
import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.FreeFormSqlQueryService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.ratelimit.RateLimited;
import com.google.common.base.Throwables;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.UUID;
import java.util.concurrent.CompletionException;

/**
 * Internal, authenticated endpoint that runs Ollie-generated read-only SQL against ClickHouse, bounded to the
 * caller's workspace and the requested project. Authentication is required only to derive the bounding
 * {@code workspace_id} ({@code project_id} comes from the body). Gated behind the {@code ollieEnabled}
 * toggle: when off it returns {@code 501 Not Implemented} and performs no ClickHouse access.
 *
 * <p>The caller's final query must return exactly one column named {@code result}, produced via
 * {@code toJSONString(...)}.
 */
@Path("/v1/internal/analytics-queries")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "系统分析查询", description = "运行Agent Insights自由形式SQL的内部端点")
public class AnalyticsQueriesResource {

    private final @NonNull FreeFormSqlQueryService freeFormSqlQueryService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles;

    @POST
    @Path("/projects/{projectId}")
    @Operation(operationId = "executeAnalyticsQuery", summary = "执行Agent Insights自由形式SQL", description = "在调用者的工作区和请求的项目范围内执行Ollie生成的只读SQL查询。当Agent Insights开关关闭时返回501。", responses = {
            @ApiResponse(responseCode = "200", description = "查询结果", content = @Content(schema = @Schema(implementation = AnalyticsQueryResponse.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "422", description = "无法处理的内容", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "501", description = "Agent Insights查询未启用")})
    @RateLimited
    public Response executeQuery(@PathParam("projectId") @NotNull UUID projectId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AnalyticsQueryRequest.class))) @NotNull @Valid AnalyticsQueryRequest request) {

        if (!serviceToggles.isOllieEnabled()) {
            return Response.status(Response.Status.NOT_IMPLEMENTED).build();
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("为工作区 '{}'、项目 '{}' 执行Agent Insights自由形式SQL", workspaceId, projectId);

        // 服务保持异步（ClickHouse v2客户端）；在此处终止，这是最后的责任时刻，因为Dropwizard
        // 不是响应式的。join()将任何失败包装在CompletionException中——解包以便映射的WebApplicationException
        // （及其HTTP状态）能原样传递到JAX-RS异常处理。
        try {
            AnalyticsQueryResponse response = freeFormSqlQueryService
                    .executeQuery(workspaceId, projectId, request.query())
                    .join();
            return Response.ok(response).build();
        } catch (CompletionException e) {
            Throwables.throwIfUnchecked(e.getCause());
            throw e;
        }
    }
}
