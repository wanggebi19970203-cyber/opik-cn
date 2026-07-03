package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.OllamaConnectionTestResponse;
import com.comet.opik.api.OllamaInstanceBaseUrlRequest;
import com.comet.opik.api.OllamaModel;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.OllamaService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Path("/v1/private/ollama")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Ollama", description = "Ollama提供商配置端点，支持OpenAI兼容API。")
public class OllamaResource {

    private final @NonNull OllamaService ollamaService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull OpikConfiguration config;

    private boolean isOllamaEnabled() {
        return config.getServiceToggles() != null
                && config.getServiceToggles().isOllamaProviderEnabled();
    }

    @POST
    @Path("/test-connection")
    @Operation(summary = "测试Ollama实例连接", description = "验证提供的Ollama URL是否可达。"
            + "URL可以带或不带 /v1 后缀（例如 http://localhost:11434 或 http://localhost:11434/v1）。"
            + "连接测试时将自动移除 /v1 后缀。"
            + "推理时请使用带 /v1 后缀的URL。", responses = {
                    @ApiResponse(responseCode = "200", description = "连接测试成功", content = @Content(schema = @Schema(implementation = OllamaConnectionTestResponse.class))),
                    @ApiResponse(responseCode = "422", description = "无法处理的内容 - 无效的URL格式", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "502", description = "连接测试失败 - Ollama实例不可达", content = @Content(schema = @Schema(implementation = OllamaConnectionTestResponse.class))),
                    @ApiResponse(responseCode = "503", description = "Ollama提供商已禁用", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
            })
    public Response testConnection(
            @NotNull @Valid OllamaInstanceBaseUrlRequest request) {
        if (!isOllamaEnabled()) {
            log.warn("Ollama provider is disabled, returning 503");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new io.dropwizard.jersey.errors.ErrorMessage(
                            Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                            "Ollama provider is disabled"))
                    .build();
        }

        log.info("Testing Ollama connection for workspace '{}'",
                requestContext.get().getWorkspaceName());

        OllamaConnectionTestResponse response = ollamaService.testConnection(request.baseUrl(), request.apiKey())
                .block();

        if (response != null && !response.connected()) {
            return Response.status(Response.Status.BAD_GATEWAY).entity(response).build();
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/models")
    @Operation(summary = "列出可用的Ollama模型", description = "获取Ollama实例上可用的模型列表。"
            + "URL可以带或不带 /v1 后缀（例如 http://localhost:11434 或 http://localhost:11434/v1）。"
            + "模型发现时将自动移除 /v1 后缀。"
            + "实际LLM推理时，请使用带 /v1 后缀的URL以访问OpenAI兼容端点。", responses = {
                    @ApiResponse(responseCode = "200", description = "模型获取成功", content = @Content(array = @ArraySchema(schema = @Schema(implementation = OllamaModel.class)))),
                    @ApiResponse(responseCode = "422", description = "无法处理的内容 - 无效的URL格式", content = @Content(schema = @Schema(implementation = ErrorMessage.class))),
                    @ApiResponse(responseCode = "500", description = "获取模型失败"),
                    @ApiResponse(responseCode = "503", description = "Ollama提供商已禁用", content = @Content(schema = @Schema(implementation = ErrorMessage.class)))
            })
    public Response listModels(
            @NotNull @Valid OllamaInstanceBaseUrlRequest request) {
        if (!isOllamaEnabled()) {
            log.warn("Ollama provider is disabled, returning 503");
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                    .entity(new io.dropwizard.jersey.errors.ErrorMessage(
                            Response.Status.SERVICE_UNAVAILABLE.getStatusCode(),
                            "Ollama provider is disabled"))
                    .build();
        }

        log.info("Fetching Ollama models for workspace '{}'",
                requestContext.get().getWorkspaceName());

        List<OllamaModel> models = ollamaService.listModels(request.baseUrl(), request.apiKey())
                .block();
        return Response.ok(models).build();
    }
}
