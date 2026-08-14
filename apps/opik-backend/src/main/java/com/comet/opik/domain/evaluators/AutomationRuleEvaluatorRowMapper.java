package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.api.evaluators.ProjectReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode;
import static com.comet.opik.domain.evaluators.TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode;
import static com.comet.opik.domain.evaluators.UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode;

/**
 * AutomationRuleEvaluatorModel 的自定义行映射器，处理：
 * 1. 旧版 project_id 回退（针对多项目支持之前创建的规则）
 * 2. 类型特定的 code 字段解析
 * 3. 稍后由服务层丰富的字段（projectId、projectName、projects）
 */
public class AutomationRuleEvaluatorRowMapper implements RowMapper<AutomationRuleEvaluatorModel<?>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public AutomationRuleEvaluatorModel<?> map(ResultSet rs, StatementContext ctx) throws SQLException {
        // 从 ResultSet 中提取公共字段
        UUID id = UUID.fromString(rs.getString("id"));
        String name = rs.getString("name");
        Float samplingRate = rs.getFloat("sampling_rate");
        boolean enabled = rs.getBoolean("enabled");
        String triggerScopeStr = rs.getString("trigger_scope");
        EvalTriggerScope triggerScope = triggerScopeStr != null ? EvalTriggerScope.fromString(triggerScopeStr) : null;
        String filters = rs.getString("filters");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        String createdBy = rs.getString("created_by");
        Instant lastUpdatedAt = rs.getTimestamp("last_updated_at").toInstant();
        String lastUpdatedBy = rs.getString("last_updated_by");

        // 旧版回退：如果规则是在多项目支持之前创建的，
        // 它会有 project_id 但 automation_rule_projects 连接表中没有条目。
        // 我们用旧版值初始化 projectIds 作为回退。
        // 服务层会在可用时用连接表数据替换它。
        Set<UUID> projectIds = new HashSet<>();
        String legacyProjectIdStr = rs.getString("legacy_project_id");
        if (legacyProjectIdStr != null) {
            projectIds.add(UUID.fromString(legacyProjectIdStr));
        }

        // 这些字段将由服务层丰富：
        // - projectId：从第一个项目派生（用于向后兼容）
        // - projectName：从第一个项目派生（用于向后兼容）
        // - projects：由 projectIds + 获取到的名称构建的 ProjectReference 的 SortedSet
        UUID projectId = null;
        String projectName = null;
        SortedSet<ProjectReference> projects = null;

        // 解析类型特定的 code 字段
        AutomationRuleEvaluatorType type = AutomationRuleEvaluatorType.fromString(rs.getString("type"));
        String codeJson = rs.getString("code");

        try {
            JsonNode codeNode = OBJECT_MAPPER.readTree(codeJson);

            // 根据评估器类型构建相应的模型类型
            return switch (type) {
                case LLM_AS_JUDGE -> LlmAsJudgeAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectId(projectId)
                        .projectName(projectName)
                        .projectIds(projectIds)
                        .projects(projects)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .triggerScope(triggerScope)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode, LlmAsJudgeCode.class))
                        .createdAt(createdAt)
                        .createdBy(createdBy)
                        .lastUpdatedAt(lastUpdatedAt)
                        .lastUpdatedBy(lastUpdatedBy)
                        .build();

                case USER_DEFINED_METRIC_PYTHON -> UserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectId(projectId)
                        .projectName(projectName)
                        .projectIds(projectIds)
                        .projects(projects)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .triggerScope(triggerScope)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode, UserDefinedMetricPythonCode.class))
                        .createdAt(createdAt)
                        .createdBy(createdBy)
                        .lastUpdatedAt(lastUpdatedAt)
                        .lastUpdatedBy(lastUpdatedBy)
                        .build();

                case TRACE_THREAD_LLM_AS_JUDGE -> TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectId(projectId)
                        .projectName(projectName)
                        .projectIds(projectIds)
                        .projects(projects)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .triggerScope(triggerScope)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode, TraceThreadLlmAsJudgeCode.class))
                        .createdAt(createdAt)
                        .createdBy(createdBy)
                        .lastUpdatedAt(lastUpdatedAt)
                        .lastUpdatedBy(lastUpdatedBy)
                        .build();

                case TRACE_THREAD_USER_DEFINED_METRIC_PYTHON ->
                    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                            .id(id)
                            .projectId(projectId)
                            .projectName(projectName)
                            .projectIds(projectIds)
                            .projects(projects)
                            .name(name)
                            .samplingRate(samplingRate)
                            .enabled(enabled)
                            .triggerScope(triggerScope)
                            .filters(filters)
                            .code(OBJECT_MAPPER.treeToValue(codeNode, TraceThreadUserDefinedMetricPythonCode.class))
                            .createdAt(createdAt)
                            .createdBy(createdBy)
                            .lastUpdatedAt(lastUpdatedAt)
                            .lastUpdatedBy(lastUpdatedBy)
                            .build();

                case SPAN_LLM_AS_JUDGE -> SpanLlmAsJudgeAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectId(projectId)
                        .projectName(projectName)
                        .projectIds(projectIds)
                        .projects(projects)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .triggerScope(triggerScope)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode, SpanLlmAsJudgeCode.class))
                        .createdAt(createdAt)
                        .createdBy(createdBy)
                        .lastUpdatedAt(lastUpdatedAt)
                        .lastUpdatedBy(lastUpdatedBy)
                        .build();

                case SPAN_USER_DEFINED_METRIC_PYTHON ->
                    SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                            .id(id)
                            .projectId(projectId)
                            .projectName(projectName)
                            .projectIds(projectIds)
                            .projects(projects)
                            .name(name)
                            .samplingRate(samplingRate)
                            .enabled(enabled)
                            .triggerScope(triggerScope)
                            .filters(filters)
                            .code(OBJECT_MAPPER.treeToValue(codeNode, SpanUserDefinedMetricPythonCode.class))
                            .createdAt(createdAt)
                            .createdBy(createdBy)
                            .lastUpdatedAt(lastUpdatedAt)
                            .lastUpdatedBy(lastUpdatedBy)
                            .build();
            };

        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to parse automation rule evaluator code", e);
        }
    }
}
