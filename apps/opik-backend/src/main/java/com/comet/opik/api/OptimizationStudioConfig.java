package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.List;

/**
 * Optimization Studio 运行的配置。
 * 这表示从前端发送的用于创建 Studio 优化的完整负载。
 *
 * opikApiKey 仅供内部使用：在服务端从请求头中填充，绝不会序列化给客户端。
 * 云部署需要它，因为它将用于代表用户自动化 SDK。
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OptimizationStudioConfig(
        @NotBlank String datasetName,
        @NotNull @Valid StudioPrompt prompt,
        @NotNull @Valid StudioLlmModel llmModel,
        @NotNull @Valid StudioEvaluation evaluation,
        @NotNull @Valid StudioOptimizer optimizer,
        @JsonIgnore String opikApiKey) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioPrompt(
            @NotEmpty @Valid List<StudioMessage> messages) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioMessage(
            @NotBlank String role,
            @NotBlank String content) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioLlmModel(
            @NotBlank String model,
            JsonNode parameters) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioEvaluation(
            @NotEmpty @Valid List<StudioMetric> metrics) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioMetric(
            @NotBlank String type,
            JsonNode parameters) {
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StudioOptimizer(
            @NotBlank String type,
            JsonNode parameters) {
    }
}
