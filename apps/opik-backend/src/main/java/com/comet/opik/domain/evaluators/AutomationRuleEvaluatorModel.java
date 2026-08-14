package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.api.evaluators.ProjectReference;
import org.jdbi.v3.json.Json;

import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

public sealed interface AutomationRuleEvaluatorModel<T> extends AutomationRuleModel permits
        LlmAsJudgeAutomationRuleEvaluatorModel, TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel,
        TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel,
        UserDefinedMetricPythonAutomationRuleEvaluatorModel,
        SpanLlmAsJudgeAutomationRuleEvaluatorModel,
        SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel {

    @Json
    T code();

    AutomationRuleEvaluatorType type();

    @Override
    default AutomationRule.AutomationRuleAction action() {
        return AutomationRule.AutomationRuleAction.EVALUATOR;
    }

    /**
     * 使用新的项目 ID 重建此模型。
     * 每个具体实现都使用其 Lombok 生成的构建器来提供此方法。
     *
     * @param projectIds 新的项目 ID 集合
     * @return 带有更新后项目 ID 的新实例
     */
    AutomationRuleEvaluatorModel<?> withProjectIds(Set<UUID> projectIds);

    /**
     * 使用新的触发范围重建此模型。
     * 由服务层使用，将 null 范围默认为 PRODUCTION 以保持向后兼容。
     *
     * @param triggerScope 要设置的触发范围
     * @return 带有更新后触发范围的新实例
     */
    AutomationRuleEvaluatorModel<?> withTriggerScope(EvalTriggerScope triggerScope);

    /**
     * 使用丰富的项目详情重建此模型。
     * 每个具体实现都使用其 Lombok 生成的构建器来提供此方法。
     *
     * @param projectId 旧版项目 ID（用于向后兼容）
     * @param projectName 旧版项目名称（用于向后兼容）
     * @param projects 项目引用的有序集合
     * @return 带有更新后项目详情的新实例
     */
    AutomationRuleEvaluatorModel<?> withProjectDetails(
            UUID projectId,
            String projectName,
            SortedSet<ProjectReference> projects);
}
