package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Path("/v1/private/llm/models")
@Produces(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "LLM Models", description = "LLM模型注册表资源")
public class LlmModelsResource {

    private final @NonNull LlmModelRegistryService registryService;

    @GET
    @Operation(operationId = "getLlmModels", summary = "获取LLM模型", description = "获取按提供商分组的已支持LLM模型列表", responses = {
            @ApiResponse(responseCode = "200", description = "按提供商分组的LLM模型", content = @Content(schema = @Schema(implementation = Map.class)))})
    public Response getModels() {
        return Response.ok()
                .entity(registryService.getRegistry())
                .build();
    }
}
