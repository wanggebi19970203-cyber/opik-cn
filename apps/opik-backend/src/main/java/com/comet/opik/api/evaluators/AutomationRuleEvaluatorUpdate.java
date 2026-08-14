package com.comet.opik.api.evaluators;

import com.comet.opik.api.filter.Filter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.experimental.SuperBuilder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@AllArgsConstructor
@SuperBuilder(toBuilder = true)
@Data
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateUserDefinedMetricPython.class, name = AutomationRuleEvaluatorType.Constants.USER_DEFINED_METRIC_PYTHON),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_LLM_AS_JUDGE),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython.class, name = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateSpanLlmAsJudge.class, name = AutomationRuleEvaluatorType.Constants.SPAN_LLM_AS_JUDGE),
        @JsonSubTypes.Type(value = AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython.class, name = AutomationRuleEvaluatorType.Constants.SPAN_USER_DEFINED_METRIC_PYTHON)
})
@Schema(name = "AutomationRuleEvaluatorUpdate", discriminatorProperty = "type", discriminatorMapping = {
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.LLM_AS_JUDGE, schema = AutomationRuleEvaluatorUpdateLlmAsJudge.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.USER_DEFINED_METRIC_PYTHON, schema = AutomationRuleEvaluatorUpdateUserDefinedMetricPython.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_LLM_AS_JUDGE, schema = AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON, schema = AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.SPAN_LLM_AS_JUDGE, schema = AutomationRuleEvaluatorUpdateSpanLlmAsJudge.class),
        @DiscriminatorMapping(value = AutomationRuleEvaluatorType.Constants.SPAN_USER_DEFINED_METRIC_PYTHON, schema = AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython.class)
})
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public abstract sealed class AutomationRuleEvaluatorUpdate<T, E extends Filter> implements AutomationRuleUpdate
        permits AutomationRuleEvaluatorUpdateLlmAsJudge, AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge,
        AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython,
        AutomationRuleEvaluatorUpdateUserDefinedMetricPython,
        AutomationRuleEvaluatorUpdateSpanLlmAsJudge, AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython {

    // 长度上限与 automation_rules.name 的 VARCHAR(150) 列相匹配，因此过长的重命名会在
    // API 边界处被拒绝，而不是导致更新失败（OPIK-7371）。
    @NotBlank @Size(max = 150, message = "cannot exceed 150 characters") private final String name;

    private final float samplingRate;

    @Builder.Default
    private final boolean enabled = true;

    @Builder.Default
    private final EvalTriggerScope triggerScope = EvalTriggerScope.PRODUCTION;

    @JsonIgnore
    @Builder.Default
    private final List<E> filters = List.of();

    @JsonIgnore
    // @Valid 会将 bean 校验级联到多态 code 记录中，从而使嵌套约束
    // （例如 maxCostUsd 上的 @Positive）在更新时得到执行，与创建路径保持一致。
    @NotNull @Valid private final T code;

    // 双字段向后兼容架构：
    // - project_id：旧版单一项目字段（为向后兼容而为可空）
    // - project_ids：新的多项目字段（新规则必需）
    // 服务层保持两个字段同步，以实现无缝迁移

    @Schema(description = "Primary project ID (legacy field, maintained for backwards compatibility)")
    private final UUID projectId;

    @Schema(description = "Multiple project IDs (new field for multi-project support)")
    private final Set<UUID> projectIds;

    public abstract AutomationRuleEvaluatorType getType();

    public abstract <C extends AutomationRuleEvaluatorUpdate<T, E>, B extends AutomationRuleEvaluatorUpdate.AutomationRuleEvaluatorUpdateBuilder<T, E, C, B>> AutomationRuleEvaluatorUpdate.AutomationRuleEvaluatorUpdateBuilder<T, E, C, B> toBuilder();

    @Override
    @NotNull public AutomationRule.AutomationRuleAction getAction() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }

}
