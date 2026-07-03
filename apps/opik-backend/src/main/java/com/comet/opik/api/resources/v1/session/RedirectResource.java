package com.comet.opik.api.resources.v1.session;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.RedirectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

@Path("/v1/session/redirect")
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "重定向", description = "SDK生成链接的重定向")
public class RedirectResource {

    private final @NonNull RedirectService redirectService;

    @GET
    @Path("/projects")
    @Operation(operationId = "projectsRedirect", summary = "创建项目重定向URL", description = "创建项目重定向URL", responses = {
            @ApiResponse(responseCode = "303", description = "重定向"),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    public Response projectsRedirect(@QueryParam("trace_id") @NotNull UUID traceId,
            @QueryParam("workspace_name") String workspaceName,
            @QueryParam("path") @NotNull String path) {
        return Response
                .seeOther(URI.create(
                        redirectService.projectRedirectUrl(traceId, workspaceName,
                                new String(Base64.getUrlDecoder().decode(path), StandardCharsets.UTF_8))))
                .build();
    }

    @GET
    @Path("/datasets")
    @Operation(operationId = "datasetsRedirect", summary = "创建数据集重定向URL", description = "创建数据集重定向URL", responses = {
            @ApiResponse(responseCode = "303", description = "重定向"),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    public Response datasetsRedirect(@QueryParam("dataset_id") @NotNull UUID datasetId,
            @QueryParam("workspace_name") String workspaceName,
            @QueryParam("path") @NotNull String path) {
        return Response
                .seeOther(URI.create(
                        redirectService.datasetRedirectUrl(datasetId, workspaceName,
                                new String(Base64.getUrlDecoder().decode(path), StandardCharsets.UTF_8))))
                .build();
    }

    @GET
    @Path("/experiments")
    @Operation(operationId = "experimentsRedirect", summary = "创建实验重定向URL", description = "创建实验重定向URL", responses = {
            @ApiResponse(responseCode = "303", description = "重定向"),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    public Response experimentsRedirect(@QueryParam("dataset_id") @NotNull UUID datasetId,
            @QueryParam("experiment_id") @NotNull UUID experimentId,
            @QueryParam("workspace_name") String workspaceName,
            @QueryParam("path") @NotNull String path) {
        return Response
                .seeOther(URI.create(redirectService.experimentsRedirectUrl(datasetId, experimentId, workspaceName,
                        new String(Base64.getUrlDecoder().decode(path), StandardCharsets.UTF_8))))
                .build();
    }

    @GET
    @Path("/optimizations")
    @Operation(operationId = "optimizationsRedirect", summary = "创建优化重定向URL", description = "创建优化重定向URL", responses = {
            @ApiResponse(responseCode = "303", description = "重定向"),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = io.dropwizard.jersey.errors.ErrorMessage.class)))
    })
    public Response optimizationsRedirect(@QueryParam("dataset_id") @NotNull UUID datasetId,
            @QueryParam("optimization_id") @NotNull UUID optimizationId,
            @QueryParam("workspace_name") String workspaceName,
            @QueryParam("path") @NotNull String path) {
        return Response
                .seeOther(URI.create(redirectService.optimizationsRedirectUrl(datasetId, optimizationId, workspaceName,
                        new String(Base64.getUrlDecoder().decode(path), StandardCharsets.UTF_8))))
                .build();
    }
}
