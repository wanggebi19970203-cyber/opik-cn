package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.EvalTriggerScope;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public sealed interface AutomationRuleModel
        permits AutomationRuleEvaluatorModel {

    UUID id();
    UUID projectId(); // 旧版单项目字段，用于向后兼容（由 projectIds 派生）
    Set<UUID> projectIds(); // 新的多项目支持
    String name();

    Float samplingRate();
    boolean enabled();
    EvalTriggerScope triggerScope();
    String filters();

    Instant createdAt();
    String createdBy();
    Instant lastUpdatedAt();
    String lastUpdatedBy();

    AutomationRule.AutomationRuleAction action();
}
