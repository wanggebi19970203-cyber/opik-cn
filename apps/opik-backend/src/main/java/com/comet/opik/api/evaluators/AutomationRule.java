package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.Arrays;
import java.util.SortedSet;
import java.util.UUID;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "action", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AutomationRuleEvaluator.class, name = "evaluator")
})
@Schema(name = "AutomationRule", discriminatorProperty = "action", discriminatorMapping = {
        @DiscriminatorMapping(value = "evaluator", schema = AutomationRuleEvaluator.class)
})
public sealed interface AutomationRule permits AutomationRuleEvaluator {

    UUID getId();

    // 双字段架构，用于向后兼容
    UUID getProjectId(); // 遗留字段 - 从第一个项目派生
    String getProjectName(); // 遗留字段 - 从第一个项目派生
    SortedSet<ProjectReference> getProjects(); // 主字段（唯一，按名称字母顺序排序）

    String getName();

    AutomationRuleAction getAction();
    float getSamplingRate();
    boolean isEnabled();
    EvalTriggerScope getTriggerScope();

    Instant getCreatedAt();
    String getCreatedBy();
    Instant getLastUpdatedAt();
    String getLastUpdatedBy();

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    enum AutomationRuleAction {

        EVALUATOR("evaluator");

        @JsonValue
        private final String action;

        public static AutomationRule.AutomationRuleAction fromString(String action) {
            return Arrays.stream(values())
                    .filter(v -> v.action.equals(action)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown rule type: " + action));
        }
    }
}
