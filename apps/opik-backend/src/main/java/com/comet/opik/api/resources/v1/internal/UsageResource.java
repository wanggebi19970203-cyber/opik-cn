package com.comet.opik.api.resources.v1.internal;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.api.UsageByWorkspaceProjectUserResponse;
import com.comet.opik.domain.DatasetService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Path("/v1/internal/usage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @jakarta.inject.Inject)
@Tag(name = "System usage", description = "系统使用情况相关资源")
public class UsageResource {

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ExperimentService experimentService;
    private final @NonNull DatasetService datasetService;

    @GET
    @Path("/workspace-trace-counts")
    @Operation(operationId = "getTracesCountForWorkspaces", summary = "获取所有可用工作区前一天的追踪记录数量", description = "获取所有可用工作区前一天的追踪记录数量", responses = {
            @ApiResponse(responseCode = "200", description = "追踪记录计数响应资源", content = @Content(schema = @Schema(implementation = TraceCountResponse.class)))})
    public Response getTracesCountForWorkspaces() {
        return traceService.countTracesPerWorkspace()
                .map(tracesCountResponse -> Response.ok(tracesCountResponse).build())
                .block();
    }

    @GET
    @Path("/workspace-span-counts")
    @Operation(operationId = "getSpansCountForWorkspaces", summary = "获取所有可用工作区前一天的跨度记录数量", description = "获取所有可用工作区前一天的跨度记录数量", responses = {
            @ApiResponse(responseCode = "200", description = "跨度记录计数响应资源", content = @Content(schema = @Schema(implementation = SpansCountResponse.class)))})
    public Response getSpansCountForWorkspaces() {
        return spanService.countSpansPerWorkspace()
                .map(spansCountResponse -> Response.ok(spansCountResponse).build())
                .block();
    }

    @GET
    @Path("/workspace-span-counts-breakdown")
    @Operation(operationId = "getSpansCountBreakdownForWorkspaces", summary = "获取按工作区、项目和用户分组的前一天跨度记录数量", description = "获取按工作区、项目和用户分组的前一天跨度记录数量", responses = {
            @ApiResponse(responseCode = "200", description = "按工作区、项目和用户的使用量响应资源", content = @Content(schema = @Schema(implementation = UsageByWorkspaceProjectUserResponse.class)))})
    public Response getSpansCountBreakdownForWorkspaces() {
        return spanService.getSpanBreakdownPerWorkspace()
                .map(breakdownResponse -> Response.ok(breakdownResponse).build())
                .block();
    }

    @GET
    @Path("/bi-traces")
    @Operation(operationId = "getTracesBiInfo", summary = "获取BI事件的追踪记录信息", description = "按用户和工作区获取BI事件的追踪记录信息", responses = {
            @ApiResponse(responseCode = "200", description = "追踪记录BI信息响应资源", content = @Content(schema = @Schema(implementation = BiInformationResponse.class)))})
    public Response getTracesBiInfo() {
        return traceService.getTraceBIInformation()
                .map(traceBiInfoResponse -> Response.ok(traceBiInfoResponse).build())
                .block();
    }

    @GET
    @Path("/bi-experiments")
    @Operation(operationId = "getExperimentBiInfo", summary = "获取BI事件的实验信息", description = "按用户和工作区获取BI事件的实验信息", responses = {
            @ApiResponse(responseCode = "200", description = "实验BI信息响应资源", content = @Content(schema = @Schema(implementation = BiInformationResponse.class)))})
    public Response getExperimentBiInfo() {
        return experimentService.getExperimentBIInformation()
                .map(experimentBiInfoResponse -> Response.ok(experimentBiInfoResponse).build())
                .block();
    }

    @GET
    @Path("/bi-datasets")
    @Operation(operationId = "getDatasetBiInfo", summary = "获取BI事件的数据集信息", description = "按用户和工作区获取BI事件的数据集信息", responses = {
            @ApiResponse(responseCode = "200", description = "数据集BI信息响应资源", content = @Content(schema = @Schema(implementation = BiInformationResponse.class)))})
    public Response getDatasetBiInfo() {
        return Response.ok(datasetService.getDatasetBIInformation()).build();
    }

    @GET
    @Path("/bi-spans")
    @Operation(operationId = "getSpansBiInfo", summary = "获取BI事件的跨度记录信息", description = "按用户和工作区获取BI事件的跨度记录信息", responses = {
            @ApiResponse(responseCode = "200", description = "跨度记录BI信息响应资源", content = @Content(schema = @Schema(implementation = BiInformationResponse.class)))})
    public Response getSpansBiInfo() {
        return spanService.getSpanBIInformation()
                .map(spanBiInfoResponse -> Response.ok(spanBiInfoResponse).build())
                .block();
    }
}
