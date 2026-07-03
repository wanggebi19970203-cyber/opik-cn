package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.OllieReport.OllieReportPage;
import com.comet.opik.api.OllieReport.ReportCompleteRequest;
import com.comet.opik.api.ReportPreference;
import com.comet.opik.domain.ReportService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/projects/{projectId}/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Reports", description = "Ollie 每日报告管理")
public class ReportsResource {

    private final @NonNull ReportService reportService;
    private final @NonNull Provider<RequestContext> requestContext;

    @POST
    @Path("/generate")
    @Operation(operationId = "generateReport", summary = "触发报告生成", description = "创建待处理报告并通过编排器触发异步生成。", responses = {
            @ApiResponse(responseCode = "202", description = "报告生成已触发", content = @Content(schema = @Schema(implementation = GenerateReportResponse.class))),
            @ApiResponse(responseCode = "401", description = "未授权"),
            @ApiResponse(responseCode = "403", description = "禁止访问")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response generateReport(@PathParam("projectId") UUID projectId) {
        UUID reportId = reportService.generateReport(projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        if (reportId == null) {
            return Response.status(Response.Status.NOT_IMPLEMENTED)
                    .entity(Map.of("error", "报告生成功能未配置"))
                    .build();
        }

        return Response.accepted(new GenerateReportResponse(reportId)).build();
    }

    @POST
    @Path("/{reportId}/complete")
    @Operation(operationId = "completeReport", summary = "完成报告生成", description = "Ollie 的回调接口，用于在生成后更新报告状态和内容。", responses = {
            @ApiResponse(responseCode = "204", description = "报告已更新"),
            @ApiResponse(responseCode = "404", description = "报告未找到")
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response completeReport(
            @PathParam("projectId") UUID projectId,
            @PathParam("reportId") UUID reportId,
            @Valid ReportCompleteRequest request) {

        reportService.updateReport(projectId, reportId, request.status(), request.content(),
                request.sessionId(), request.recommendedActions())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.noContent().build();
    }

    @GET
    @Operation(operationId = "getReports", summary = "获取项目报告", description = "返回分页报告列表，最新的在前。", responses = {
            @ApiResponse(responseCode = "200", description = "报告分页", content = @Content(schema = @Schema(implementation = OllieReportPage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getReports(
            @PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @Max(100) @DefaultValue("10") int size) {

        var reports = reportService.getReports(projectId, page, size)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok(reports).build();
    }

    @GET
    @Path("/preferences")
    @Operation(operationId = "getReportPreference", summary = "获取报告偏好设置", description = "返回项目的报告偏好设置，若未设置则返回 null。", responses = {
            @ApiResponse(responseCode = "200", description = "报告偏好设置或 null", content = @Content(schema = @Schema(implementation = ReportPreference.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response getPreference(@PathParam("projectId") UUID projectId) {
        var preference = reportService.getPreference(projectId)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok(preference).build();
    }

    @PUT
    @Path("/preferences")
    @Operation(operationId = "updateReportPreference", summary = "更新报告偏好设置", description = "启用或禁用项目的每日报告生成。", responses = {
            @ApiResponse(responseCode = "200", description = "已更新的偏好设置", content = @Content(schema = @Schema(implementation = ReportPreference.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.PROJECT_DATA_VIEW)
    public Response updatePreference(
            @PathParam("projectId") UUID projectId,
            @Valid ReportPreference preference) {

        var updated = reportService.updatePreference(projectId, preference)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        return Response.ok(updated).build();
    }

    @Schema(description = "报告生成触发响应")
    private record GenerateReportResponse(UUID reportId) {
    }
}
