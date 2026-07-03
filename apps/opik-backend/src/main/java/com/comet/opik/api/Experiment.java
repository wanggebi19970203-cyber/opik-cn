package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.utils.ValidationUtils.NULL_OR_NOT_BLANK;

@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record Experiment(
        @JsonView({
                Experiment.View.Public.class, Experiment.View.Write.class}) UUID id,
        /* 需要确保 datasetName 在公开写视图中不为 null 或空白。
        但同时允许在公开读视图中为 null。否则，Python SDK 生成的客户端类
        在实验关联的数据集被删除时会抛出验证错误。
        参见：https://comet-ml.atlassian.net/browse/OPIK-4632 */
        @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) @NotBlank @Schema(nullable = true, requiredMode = Schema.RequiredMode.NOT_REQUIRED) String datasetName,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID datasetId,
        @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) @Schema(description = "项目ID。同时提供 project_name 时，以 project_id 为准。") UUID projectId,
        @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) @Schema(description = "项目名称。如果项目不存在则自动创建。提供 project_id 时忽略此参数。") @Pattern(regexp = NULL_OR_NOT_BLANK, message = "must not be blank") String projectName,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) String name,
        @Schema(implementation = JsonListString.class) @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) JsonNode metadata,
        @Valid @Size(max = 50, message = "Cannot have more than 50 tags") @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) Set<@NotBlank(message = "Tag must not be blank") @Size(max = 100, message = "Tag cannot exceed 100 characters") String> tags,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) ExperimentType type,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) EvaluationMethod evaluationMethod,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) UUID optimizationId,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<FeedbackScoreAverage> feedbackScores,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<Comment> comments,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Long traceCount,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Long datasetItemCount,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) PercentageValues duration,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) BigDecimal totalEstimatedCost,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) BigDecimal totalEstimatedCostAvg,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Map<String, Double> usage,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant lastUpdatedAt,
        @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) ExperimentStatus status,
        @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) List<@NotNull @Valid ExperimentScore> experimentScores,
        @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) @Schema(deprecated = true) PromptVersionLink promptVersion,
        @JsonView({Experiment.View.Public.class, Experiment.View.Write.class}) List<PromptVersionLink> promptVersions,
        @JsonView({Experiment.View.Public.class,
                Experiment.View.Write.class}) @Schema(description = "此实验关联的数据集版本ID。创建时未提供，实验将自动关联到最新版本。") UUID datasetVersionId,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "此实验关联的数据集版本摘要。") DatasetVersionSummary datasetVersionSummary,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "测试套件实验的通过率（0.0-1.0）。常规实验为 null。") BigDecimal passRate,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "测试套件实验中通过的条目数。常规实验为 null。") Long passedCount,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "测试套件实验的条目总数。常规实验为 null。") Long totalCount,
        @JsonView({
                Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "测试套件实验中每个断言的平均通过率。常规实验为 null。") List<AssertionScoreAverage> assertionScores) {

    @Builder(toBuilder = true)
    public record ExperimentPage(
            @JsonView(Experiment.View.Public.class) int page,
            @JsonView(Experiment.View.Public.class) int size,
            @JsonView(Experiment.View.Public.class) long total,
            @JsonView(Experiment.View.Public.class) List<Experiment> content,
            @JsonView(Experiment.View.Public.class) List<String> sortableBy)
            implements
                Page<Experiment> {
        public static Experiment.ExperimentPage empty(int page, List<String> sortableBy) {
            return new Experiment.ExperimentPage(page, 0, 0, List.of(), sortableBy);
        }
    }

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PromptVersionLink(@JsonView({
            Experiment.View.Public.class, Experiment.View.Write.class}) @NotNull UUID id,
            @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String commit,
            @JsonView({
                    Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "顺序版本号，格式为 v<N>；蒙版版本为 null") String versionNumber,
            @JsonView({Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID promptId,
            @JsonView({
                    Experiment.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String promptName) {
    }

    public static class View {
        public static class Write {
        }

        public static class Public {
        }
    }
}
