package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.infrastructure.db.EvalTriggerScopeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(EvalTriggerScopeColumnMapper.class)
@RegisterRowMapper(AutomationRuleRowMapper.class)
public interface AutomationRuleDAO {

    @SqlUpdate("INSERT INTO automation_rules(id, workspace_id, `action`, name, sampling_rate, enabled, trigger_scope, filters) "
            +
            "VALUES (:rule.id, :workspaceId, :rule.action, :rule.name, :rule.samplingRate, :rule.enabled, :rule.triggerScope, :rule.filters)")
    void saveBaseRule(@BindMethods("rule") AutomationRuleModel rule, @Bind("workspaceId") String workspaceId);

    /**
     * 返回给定项目中以 {@code namePrefix} 开头的现有规则名称，用于为冲突名称自动添加后缀
     * （OPIK-7371）。通过联结表按项目限定范围（这是 AutomationRuleProjectMigration 回填之后的
     * 权威关联）；旧版 {@code project_id} 列被有意不使用（更新时会被置为 null）。
     * {@code excludeRuleId}（可选）会跳过单个规则，使其自身名称在更新时不被视为自冲突。
     * 调用方必须传入经 {@link AutomationRuleNames#likePrefix(String)} 转义的前缀，
     * 使名称中的 LIKE 元字符被按字面量匹配。最终的精确匹配在 Java 中对返回的候选集合完成。
     * <p>
     * 实际约束此查询的是<em>项目</em>过滤器，而非名称前缀。在一个工作空间含 5 万条规则的
     * MySQL 8.4 上实测：对于典型项目（约 100 条规则），优化器从 {@code automation_rule_projects}
     * 的 {@code project_id} 开始驱动，并通过主键到达 {@code automation_rules}，因此名称只是
     * 一个残余过滤器。迁移 000092 中新增的 {@code (workspace_id, name)} 索引在该场景和倾斜场景
     * （单个项目持有 2 万条规则，优化器改为通过 {@code automation_rules_idx} 扫描约 2.5 万行）
     * 中均<em>未被</em>选中。强制使用该索引确实能产生更好的执行计划（覆盖范围扫描、行数减半），
     * 因此该索引可用但目前处于惰性状态 —— 在依赖它之前请参阅 OPIK-7371 的评审线程。
     * <p>
     * 假设（乐观假设，按 OPIK-7371）：联结表回填已完成，因此罕见的未回填旧版规则（无联结行）
     * 可能会被遗漏 —— 退化为重复名称，而非错误；并且同名的并发创建在没有数据库约束的情况下
     * 存在竞态。
     */
    @SqlQuery("""
            SELECT DISTINCT rule.name
            FROM automation_rules rule
            JOIN automation_rule_projects arp ON rule.id = arp.rule_id
            WHERE rule.workspace_id = :workspaceId
            AND arp.project_id IN (<projectIds>)
            AND rule.name LIKE concat(:namePrefix, '%') ESCAPE '!'
            <if(excludeRuleId)> AND rule.id != :excludeRuleId <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Set<String> findCandidateNames(
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId,
            @Bind("namePrefix") String namePrefix,
            @Define("excludeRuleId") @Bind("excludeRuleId") UUID excludeRuleId);

    /**
     * 返回规则当前存储的名称，若规则不存在则返回空。用于在更新时对非名称编辑
     * 完全跳过名称解析（OPIK-7371）。
     * <p>
     * 有意采用单列投影而非完整规则负载：注册的 {@link AutomationRuleRowMapper}
     * 会按 {@code action} 分派到 {@link AutomationRuleEvaluatorModel}，因此若返回完整规则，
     * 每次更新都需要联结 {@code automation_rule_evaluators} 并反序列化 {@code code} 负载
     * （完整的 LLM 评委提示词），仅仅为了读取这一列。
     */
    @SqlQuery("SELECT name FROM automation_rules WHERE id = :id AND workspace_id = :workspaceId")
    Optional<String> findNameById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            UPDATE automation_rules
            SET name = :name,
                sampling_rate = :samplingRate,
                enabled = :enabled,
                trigger_scope = :triggerScope,
                filters = :filters
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    int updateBaseRule(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("name") String name,
            @Bind("samplingRate") float samplingRate,
            @Bind("enabled") boolean enabled,
            @Bind("triggerScope") EvalTriggerScope triggerScope,
            @Bind("filters") String filters);

    /**
     * 清除旧版 project_id 字段以防止出现陈旧数据。
     * 当项目从联结表中被移除时应调用此方法。
     */
    @SqlUpdate("UPDATE automation_rules SET project_id = NULL WHERE id = :id AND workspace_id = :workspaceId")
    int clearLegacyProjectId(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            DELETE FROM automation_rules
            WHERE workspace_id = :workspaceId
            <if(ids)> AND id IN (<ids>) <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    void deleteBaseRules(
            @Define("ids") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "ids") Set<UUID> ids,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT COUNT(DISTINCT rule.id)
            FROM automation_rules rule
            <if(projectIds)>
            LEFT JOIN automation_rule_projects arp ON rule.id = arp.rule_id
            <endif>
            WHERE rule.workspace_id = :workspaceId
            <if(projectIds)> AND arp.project_id IN (<projectIds>) <endif>
            <if(action)> AND rule.`action` = :action <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(
            @Define("projectIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "projectIds") Set<UUID> projectIds,
            @Bind("workspaceId") String workspaceId,
            @Define("action") @Bind("action") AutomationRule.AutomationRuleAction action);
}
