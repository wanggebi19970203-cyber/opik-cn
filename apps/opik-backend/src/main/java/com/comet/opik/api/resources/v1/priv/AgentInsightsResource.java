package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.AgentInsightsIssue;
import com.comet.opik.api.AgentInsightsIssueSeverity;
import com.comet.opik.api.AgentInsightsIssueStatus;
import com.comet.opik.api.AgentInsightsIssueUpdate;
import com.comet.opik.api.AgentInsightsIssueWithDetails;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.AgentInsightsIssueSortingFactory;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.AgentInsightsIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.validateDateRangeParameters;

@Path("/v1/private/agent-insights")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@Timed
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Agent Insights", description = "智能体洞察报告结果")
public class AgentInsightsResource {

    private final @NonNull AgentInsightsIssueService agentInsightsIssueService;
    private final @NonNull AgentInsightsIssueSortingFactory sortingFactory;

    @GET
    @Path("/issues")
    @Operation(operationId = "findAgentInsightsIssues", summary = "查询智能体洞察问题列表", description = "返回在请求时间窗口内至少有一条详情记录的问题分页列表，指标在时间窗口内聚合", responses = {
            @ApiResponse(responseCode = "200", description = "问题分页", content = @Content(schema = @Schema(implementation = AgentInsightsIssue.AgentInsightsIssuePage.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response findIssues(
            @QueryParam("project_id") @NotNull UUID projectId,
            @QueryParam("from_date") LocalDate fromDate,
            @QueryParam("to_date") LocalDate toDate,
            @QueryParam("status") AgentInsightsIssueStatus status,
            @QueryParam("severity") AgentInsightsIssueSeverity severity,
            @QueryParam("sorting") String sorting,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @Max(100) @DefaultValue("10") int size) {

        validateDateRangeParameters(fromDate, toDate);

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);
        AgentInsightsIssue.AgentInsightsIssuePage issuesPage = agentInsightsIssueService.findIssues(
                projectId, fromDate, toDate, status, severity, sortingFields, page, size);

        return Response.ok(issuesPage).build();
    }

    @GET
    @Path("/issues/{issue_id}")
    @Operation(operationId = "getAgentInsightsIssueById", summary = "根据ID获取智能体洞察问题", description = "返回问题及其在请求时间窗口内的每日明细", responses = {
            @ApiResponse(responseCode = "200", description = "问题详情", content = @Content(schema = @Schema(implementation = AgentInsightsIssueWithDetails.class))),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response getIssueById(
            @PathParam("issue_id") UUID issueId,
            @QueryParam("project_id") @NotNull UUID projectId,
            @QueryParam("from_date") LocalDate fromDate,
            @QueryParam("to_date") LocalDate toDate) {

        validateDateRangeParameters(fromDate, toDate);

        AgentInsightsIssueWithDetails issue = agentInsightsIssueService.getIssue(issueId, projectId, fromDate,
                toDate);

        return Response.ok(issue).build();
    }

    @POST
    @Path("/issues")
    @Operation(operationId = "reportAgentInsightsIssues", summary = "存储智能体洞察报告结果", description = "在单个事务中更新插入指定报告日期的检测问题及其每日指标。此端点不会修改问题状态。", responses = {
            @ApiResponse(responseCode = "204", description = "报告已存储"),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "项目未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response reportIssues(
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentInsightsReport.class))) @NotNull @Valid AgentInsightsReport report) {

        agentInsightsIssueService.reportIssues(report);

        return Response.noContent().build();
    }

    @PATCH
    @Path("/issues/{issue_id}")
    @Operation(operationId = "updateAgentInsightsIssue", summary = "更新智能体洞察问题状态", description = "推动问题在其生命周期中流转：已开启、已解决或已关闭", responses = {
            @ApiResponse(responseCode = "204", description = "问题已更新"),
            @ApiResponse(responseCode = "400", description = "错误请求", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "404", description = "未找到", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
            @ApiResponse(responseCode = "401", description = "未授权", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    public Response updateIssue(
            @PathParam("issue_id") UUID issueId,
            @RequestBody(content = @Content(schema = @Schema(implementation = AgentInsightsIssueUpdate.class))) @NotNull @Valid AgentInsightsIssueUpdate update) {

        agentInsightsIssueService.updateStatus(issueId, update);

        return Response.noContent().build();
    }
}
