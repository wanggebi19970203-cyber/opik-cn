package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.TraceFilter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;

@SuperBuilder(toBuilder = true)
@Data
@EqualsAndHashCode(callSuper = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public final class AutomationRuleEvaluatorLlmAsJudge extends AutomationRuleEvaluator<LlmAsJudgeCode, TraceFilter> {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LlmAsJudgeCode(
            @JsonView({
                    View.Public.class, View.Write.class}) @NotNull LlmAsJudgeModelParameters model,
            @JsonView({View.Public.class, View.Write.class}) @NotNull List<LlmAsJudgeMessage> messages,
            @JsonView({View.Public.class, View.Write.class}) @NotNull Map<String, String> variables,
            @JsonView({View.Public.class, View.Write.class}) @NotNull List<LlmAsJudgeOutputSchema> schema,
            @JsonView({View.Public.class, View.Write.class}) @Positive BigDecimal maxCostUsd) {

        // 可选的单次评估花费上限，为事后追加；该重载保留了先前的
        // 位置参数形态以便继续工作（调用方获得 maxCostUsd = null = 无限制）。
        public LlmAsJudgeCode(LlmAsJudgeModelParameters model, List<LlmAsJudgeMessage> messages,
                Map<String, String> variables, List<LlmAsJudgeOutputSchema> schema) {
            this(model, messages, variables, schema, null);
        }
    }

    @ConstructorProperties({"id", "projectId", "projectName", "projects", "projectIds", "name", "samplingRate",
            "enabled", "triggerScope", "filters", "code",
            "createdAt",
            "createdBy",
            "lastUpdatedAt", "lastUpdatedBy"})
    public AutomationRuleEvaluatorLlmAsJudge(UUID id, UUID projectId, String projectName,
            SortedSet<ProjectReference> projects,
            Set<UUID> projectIds,
            @NotBlank String name,
            float samplingRate,
            boolean enabled,
            EvalTriggerScope triggerScope,
            List<TraceFilter> filters,
            @NotNull LlmAsJudgeCode code, Instant createdAt, String createdBy, Instant lastUpdatedAt,
            String lastUpdatedBy) {
        super(id, projectId, projectName, projects, projectIds, name, samplingRate, enabled, triggerScope, filters,
                code,
                createdAt, createdBy,
                lastUpdatedAt,
                lastUpdatedBy);
    }

    /**
     * 两个用途：
     * - 使多态 T 类型的 code 可用于序列化。
     * - 为 Open API 和 Fern 提供具体的 T 类型。
     */
    @JsonView({View.Public.class, View.Write.class})
    @Override
    public List<TraceFilter> getFilters() {
        return super.getFilters();
    }

    /**
     * 两个用途：
     * - 使多态 T 类型的 code 可用于序列化。
     * - 为 Open API 和 Fern 提供具体的 T 类型。
     */
    @JsonView({View.Public.class, View.Write.class})
    @Override
    public LlmAsJudgeCode getCode() {
        return super.getCode();
    }

    @Override
    public AutomationRuleEvaluatorType getType() {
        return AutomationRuleEvaluatorType.LLM_AS_JUDGE;
    }

}
