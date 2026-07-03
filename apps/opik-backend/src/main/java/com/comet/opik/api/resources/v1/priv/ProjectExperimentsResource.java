package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.filter.ExperimentFilter;
import com.comet.opik.api.filter.FiltersFactory;
import com.comet.opik.api.resources.v1.priv.validate.ParamsValidator;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.EntityType;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.workspaces.WorkspaceMetadataService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.RequiredPermissions;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.fasterxml.jackson.annotation.JsonView;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.utils.AsyncUtils.setRequestContext;

@Path("/v1/private/projects/{projectId}/experiments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Projects", description = "项目相关资源")
public class ProjectExperimentsResource {

    private final @NonNull ExperimentService experimentService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull ExperimentSortingFactory sortingFactory;
    private final @NonNull WorkspaceMetadataService workspaceMetadataService;
    private final @NonNull FiltersFactory filtersFactory;

    @GET
    @Operation(operationId = "findExperimentsByProject", summary = "按项目查找实验", description = "查找指定项目下的实验", responses = {
            @ApiResponse(responseCode = "200", description = "实验分页", content = @Content(schema = @Schema(implementation = Experiment.ExperimentPage.class))),
            @ApiResponse(responseCode = "400", description = "请求错误", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
    })
    @RequiredPermissions(WorkspaceUserPermission.EXPERIMENT_VIEW)
    @JsonView(Experiment.View.Public.class)
    public Response find(
            @PathParam("projectId") UUID projectId,
            @QueryParam("page") @Min(1) @DefaultValue("1") int page,
            @QueryParam("size") @Min(1) @DefaultValue("10") int size,
            @QueryParam("datasetId") UUID datasetId,
            @QueryParam("optimization_id") UUID optimizationId,
            @QueryParam("types") String typesQueryParam,
            @QueryParam("name") @Schema(description = "按名称过滤实验（部分匹配，不区分大小写）") String name,
            @QueryParam("dataset_deleted") boolean datasetDeleted,
            @QueryParam("sorting") String sorting,
            @QueryParam("filters") String filters,
            @QueryParam("experiment_ids") @Schema(description = "按实验 ID 列表过滤实验") String experimentIds,
            @QueryParam("force_sorting") @DefaultValue("false") @Schema(description = "即使超出端点结果集限制也强制排序，可能导致查询变慢") boolean forceSorting) {

        List<SortingField> sortingFields = sortingFactory.newSorting(sorting);

        var metadata = workspaceMetadataService.getExperimentMetadata(
                requestContext.get().getWorkspaceId(), datasetId)
                .block();
        if (!forceSorting && !sortingFields.isEmpty() && metadata.cannotUseDynamicSorting()) {
            sortingFields = List.of();
        }

        var experimentFilters = filtersFactory.newFilters(filters, ExperimentFilter.LIST_TYPE_REFERENCE);

        var types = Optional.ofNullable(typesQueryParam)
                .map(queryParam -> ParamsValidator.get(queryParam, ExperimentType.class, "types"))
                .orElse(null);

        var experimentIdsParsed = Optional.ofNullable(experimentIds)
                .filter(param -> !param.isBlank())
                .map(ParamsValidator::getIds)
                .orElse(null);

        var experimentSearchCriteria = ExperimentSearchCriteria.builder()
                .datasetId(datasetId)
                .name(name)
                .entityType(EntityType.TRACE)
                .datasetDeleted(datasetDeleted)
                .projectId(projectId)
                .sortingFields(sortingFields)
                .optimizationId(optimizationId)
                .types(types)
                .filters(experimentFilters)
                .experimentIds(experimentIdsParsed)
                .build();

        log.info("Finding experiments by project '{}', page '{}', size '{}'", projectId, page, size);
        var experiments = experimentService.find(page, size, experimentSearchCriteria)
                .map(experimentPage -> {
                    if (!forceSorting && metadata.cannotUseDynamicSorting()) {
                        return experimentPage.toBuilder().sortableBy(List.of()).build();
                    }
                    return experimentPage;
                })
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Found experiments by project '{}', count '{}', page '{}', size '{}'",
                projectId, experiments.size(), page, size);
        return Response.ok().entity(experiments).build();
    }

}
