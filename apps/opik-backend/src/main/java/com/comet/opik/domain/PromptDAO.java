package com.comet.opik.domain;

import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersionLink;
import com.comet.opik.api.RecentActivity;
import com.comet.opik.infrastructure.db.PromptVersionColumnMapper;
import com.comet.opik.infrastructure.db.PromptVersionLinkRowMapper;
import com.comet.opik.infrastructure.db.SetFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMap;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 提示词数据访问对象（DAO）接口。
 * 提供提示词的 CRUD 操作、版本管理、批量查询和迁移支持等功能。
 * 使用 JDBI 框架实现 SQL 映射。
 *
 * @see Prompt
 * @see PromptVersionLink
 */
@RegisterColumnMapper(PromptVersionColumnMapper.class)
@RegisterConstructorMapper(Prompt.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(SetFlatArgumentFactory.class)
@RegisterColumnMapper(SetFlatArgumentFactory.class)
public interface PromptDAO {

    /**
     * 检查是否存在 V1（工作区范围）提示词，排除已知的演示名称。
     * MySQL 的 utf8mb4_unicode_ci 排序规则使 NOT IN 比较不区分大小写，
     * 因此仅大小写不同的演示名称变体会被自动排除。
     *
     * @param workspaceId    工作区ID
     * @param demoPromptNames 演示提示词名称列表
     * @return 如果存在非演示的 V1 提示词则返回 true
     */
    @SqlQuery("""
            SELECT EXISTS(
                SELECT 1 FROM prompts
                WHERE workspace_id = :workspaceId AND project_id IS NULL
                AND name NOT IN (<demoPromptNames>)
            )""")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    boolean hasVersion1Prompts(
            @Bind("workspaceId") String workspaceId, @BindList("demoPromptNames") List<String> demoPromptNames);

    /**
     * 保存新的提示词记录。
     *
     * @param workspaceId 工作区ID
     * @param prompt      提示词对象
     */
    @SqlUpdate("INSERT INTO prompts (id, name, description, created_by, last_updated_by, workspace_id, project_id, tags, template_structure) "
            +
            "VALUES (:bean.id, :bean.name, :bean.description, :bean.createdBy, :bean.lastUpdatedBy, :workspace_id, :bean.projectId, :bean.tags, :bean.templateStructure)")
    void save(@Bind("workspace_id") String workspaceId, @BindMethods("bean") Prompt prompt);

    /**
     * 根据ID查找提示词，包含版本数量统计、最新版本和可选的请求版本信息。
     *
     * @param id          提示词ID
     * @param workspaceId 工作区ID
     * @param maskId      可选的掩码版本ID
     * @param environment 可选的环境名称
     * @return 提示词对象，包含版本信息
     */
    @SqlQuery("""
            WITH pv_for_prompt AS (
                SELECT pv.*
                FROM prompt_versions pv
                WHERE pv.prompt_id = :id AND pv.workspace_id = :workspace_id
            ), active_envs AS (
                SELECT pve.version_id, pve.environment
                FROM prompt_version_envs pve
                INNER JOIN pv_for_prompt pfp ON pfp.id = pve.version_id
                WHERE pve.workspace_id = :workspace_id AND pve.ended_at IS NULL
            ), ver_envs AS (
                SELECT version_id, JSON_ARRAYAGG(environment) AS environments
                FROM active_envs
                GROUP BY version_id
            )
            SELECT
                p.*,
                (
                    SELECT COUNT(pfp.id)
                    FROM pv_for_prompt pfp
                    WHERE pfp.version_type = 'prompt_version'
                ) AS version_count,
                (
                    SELECT JSON_OBJECT(
                        'id', pfp.id,
                        'prompt_id', pfp.prompt_id,
                        'commit', pfp.commit,
                        'version_number', pfp.version_number,
                        'template', pfp.template,
                        'metadata', pfp.metadata,
                        'change_description', pfp.change_description,
                        'type', pfp.type,
                        'version_type', pfp.version_type,
                        'environments', ve.environments,
                        'tags', pfp.tags,
                        'created_at', pfp.created_at,
                        'created_by', pfp.created_by,
                        'last_updated_at', pfp.last_updated_at,
                        'last_updated_by', pfp.last_updated_by
                    )
                    FROM pv_for_prompt pfp
                    LEFT JOIN ver_envs ve ON ve.version_id = pfp.id
                    WHERE pfp.version_type = 'prompt_version'
                    ORDER BY pfp.id DESC
                    LIMIT 1
                ) AS latest_version
                <if(mask_id || environment)>
                ,
                (
                    SELECT JSON_OBJECT(
                        'id', pfp.id,
                        'prompt_id', pfp.prompt_id,
                        'commit', pfp.commit,
                        'version_number', pfp.version_number,
                        'template', pfp.template,
                        'metadata', pfp.metadata,
                        'change_description', pfp.change_description,
                        'type', pfp.type,
                        'version_type', pfp.version_type,
                        'environments', ve.environments,
                        'tags', pfp.tags,
                        'created_at', pfp.created_at,
                        'created_by', pfp.created_by,
                        'last_updated_at', pfp.last_updated_at,
                        'last_updated_by', pfp.last_updated_by
                    )
                    FROM pv_for_prompt pfp
                    LEFT JOIN ver_envs ve ON ve.version_id = pfp.id
                    WHERE 1=1
                    <if(mask_id)> AND pfp.id = :mask_id AND pfp.version_type = 'mask' <endif>
                    <if(environment)> AND pfp.id IN (SELECT version_id FROM active_envs WHERE environment = :environment) AND pfp.version_type = 'prompt_version' <endif>
                ) AS requested_version
                <endif>
            FROM prompts p
            WHERE p.id = :id
            AND p.workspace_id = :workspace_id
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Prompt findById(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId,
            @Define("mask_id") @Bind("mask_id") UUID maskId,
            @Define("environment") @Bind("environment") String environment);

    /**
     * 根据ID查找提示词（简化版本，不指定掩码和环境）。
     *
     * @param id          提示词ID
     * @param workspaceId 工作区ID
     * @return 提示词对象
     */
    default Prompt findById(UUID id, String workspaceId) {
        return findById(id, workspaceId, null, null);
    }

    /**
     * 根据ID查找提示词（指定掩码版本）。
     *
     * @param id          提示词ID
     * @param workspaceId 工作区ID
     * @param maskId      掩码版本ID
     * @return 提示词对象
     */
    default Prompt findById(UUID id, String workspaceId, UUID maskId) {
        return findById(id, workspaceId, maskId, null);
    }

    /**
     * 分页查询提示词列表，支持按名称模糊搜索、项目过滤、自定义排序和过滤条件。
     *
     * @param name         可选的名称搜索关键词
     * @param workspaceId  工作区ID
     * @param projectId    可选的项目ID
     * @param offset       分页偏移量
     * @param limit        每页数量
     * @param sortingFields 可选的排序字段
     * @param filters      可选的过滤条件
     * @param filterMapping 过滤条件参数映射
     * @return 提示词列表
     */
    @SqlQuery("""
            SELECT
                *
            FROM (
                SELECT
                  p.*,
                  (
                    SELECT COUNT(pv.id)
                      FROM prompt_versions pv
                     WHERE pv.workspace_id = p.workspace_id
                     AND pv.prompt_id = p.id
                     AND pv.version_type = 'prompt_version'
                  ) AS version_count
                FROM prompts AS p
                WHERE workspace_id = :workspace_id
                <if(name)> AND name like concat('%', :name, '%') <endif>
                <if(project_id)> AND project_id = :project_id <endif>
            ) AS prompt_full
            <if(filters)> WHERE <filters> <endif>
            ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC
            LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Prompt> find(@Define("name") @Bind("name") String name, @Bind("workspace_id") String workspaceId,
            @Define("project_id") @Bind("project_id") UUID projectId,
            @Bind("offset") int offset, @Bind("limit") int limit,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("""
            WITH selected_prompts AS (
            	SELECT
                  p.*,
                  (
                    SELECT COUNT(pv.id)
                      FROM prompt_versions pv
                      WHERE pv.workspace_id = p.workspace_id
                      AND pv.prompt_id = p.id
                      AND pv.version_type = 'prompt_version'
                  ) AS version_count
            	FROM prompts AS p
            	WHERE workspace_id = :workspace_id
            	<if(ids)> AND id IN (<ids>) <endif>
            ), pv_ranked AS (
                SELECT pv.*,
                    ROW_NUMBER() OVER (PARTITION BY pv.prompt_id ORDER BY pv.id DESC) AS rn
                FROM prompt_versions pv
                WHERE pv.workspace_id = :workspace_id AND pv.version_type = 'prompt_version'
                <if(ids)> AND pv.prompt_id IN (<ids>) <endif>
            ), active_envs AS (
                SELECT pve.version_id, pve.environment
                FROM prompt_version_envs pve
                INNER JOIN pv_ranked pvr ON pvr.id = pve.version_id AND pvr.rn = 1
                WHERE pve.workspace_id = :workspace_id AND pve.ended_at IS NULL
            ), ver_envs AS (
                SELECT version_id, JSON_ARRAYAGG(environment) AS environments
                FROM active_envs
                GROUP BY version_id
            ), latest_versions AS (
            	SELECT
              JSON_OBJECT(
                'id', pvr.id,
                'prompt_id', pvr.prompt_id,
                'commit', pvr.commit,
                'version_number', pvr.version_number,
                'template', pvr.template,
                'metadata', pvr.metadata,
                'change_description', pvr.change_description,
                'type', pvr.type,
                'version_type', pvr.version_type,
                'environments', ve.environments,
                'tags', pvr.tags,
                'created_at', pvr.created_at,
                'created_by', pvr.created_by,
                'last_updated_at', pvr.last_updated_at,
                'last_updated_by', pvr.last_updated_by
              ) AS latest_version,
              pvr.prompt_id
              FROM pv_ranked pvr
              LEFT JOIN ver_envs ve ON ve.version_id = pvr.id
              WHERE pvr.rn = 1
            )
            SELECT sp.*, lv.latest_version
            FROM selected_prompts sp
            LEFT JOIN latest_versions lv
            ON sp.id = lv.prompt_id
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    /**
     * 根据ID集合批量查找提示词，包含每个提示词的最新版本信息。
     *
     * @param ids         提示词ID集合
     * @param workspaceId 工作区ID
     * @return 提示词列表，包含最新版本信息
     */
    List<Prompt> findByIds(@Define("ids") @BindList("ids") Set<UUID> ids, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
             SELECT
                count(id)
             FROM (
                SELECT
                  p.*,
                  (
                    SELECT COUNT(pv.id)
                      FROM prompt_versions pv
                     WHERE pv.workspace_id = p.workspace_id
                     AND pv.prompt_id = p.id
                     AND pv.version_type = 'prompt_version'
                  ) AS version_count
                FROM prompts AS p
                WHERE workspace_id = :workspace_id
                <if(name)> AND name like concat('%', :name, '%') <endif>
                <if(project_id)> AND project_id = :project_id <endif>
            ) AS prompt_full
            <if(filters)> WHERE <filters> <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    /**
     * 统计符合过滤条件的提示词数量。
     *
     * @param name         可选的名称搜索关键词
     * @param workspaceId  工作区ID
     * @param projectId    可选的项目ID
     * @param filters      可选的过滤条件
     * @param filterMapping 过滤条件参数映射
     * @return 符合条件的提示词数量
     */
    long count(@Define("name") @Bind("name") String name, @Bind("workspace_id") String workspaceId,
            @Define("project_id") @Bind("project_id") UUID projectId,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    /**
     * 根据名称查找提示词。
     *
     * @param name        提示词名称
     * @param workspaceId 工作区ID
     * @param projectId   可选的项目ID
     * @return 提示词对象，不存在时返回 null
     */
    @SqlQuery("SELECT * FROM prompts WHERE name = :name AND workspace_id = :workspace_id" +
            " <if(project_id)> AND project_id = :project_id <endif>")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Prompt findByName(@Bind("name") String name, @Bind("workspace_id") String workspaceId,
            @Define("project_id") @Bind("project_id") UUID projectId);

    /**
     * 更新提示词的名称、描述和标签。
     *
     * @param workspaceId   工作区ID
     * @param updatedPrompt 更新后的提示词对象
     * @param tags          可选的标签集合，为 null 时保留原标签
     * @return 实际更新的记录数
     */
    @SqlUpdate("UPDATE prompts SET name = :bean.name, description = :bean.description, last_updated_by = :bean.lastUpdatedBy, "
            +
            " tags = COALESCE(:tags, tags) " +
            " WHERE id = :bean.id AND workspace_id = :workspace_id")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    int update(@Bind("workspace_id") String workspaceId, @BindMethods("bean") Prompt updatedPrompt,
            @Bind("tags") Set<String> tags);

    /**
     * 根据ID删除单个提示词。
     *
     * @param id          提示词ID
     * @param workspaceId 工作区ID
     * @return 实际删除的记录数
     */
    @SqlUpdate("DELETE FROM prompts WHERE id = :id AND workspace_id = :workspace_id")
    int delete(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    /**
     * 根据ID集合批量删除提示词。
     *
     * @param ids         提示词ID集合
     * @param workspaceId 工作区ID
     */
    @SqlUpdate("DELETE FROM prompts WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    /**
     * 更新提示词的最后更新时间和最后更新者。
     *
     * @param id            提示词ID
     * @param workspaceId   工作区ID
     * @param lastUpdatedBy 最后更新者用户名
     */
    @SqlUpdate("UPDATE prompts SET last_updated_by = :lastUpdatedBy, last_updated_at = CURRENT_TIMESTAMP(6) WHERE id = :id AND workspace_id = :workspaceId")
    void updateLastUpdatedAt(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlQuery("""
            WITH pv_for_commit AS (
                SELECT pv.*
                FROM prompt_versions pv
                WHERE pv.commit = :commit AND pv.workspace_id = :workspace_id AND pv.version_type = 'prompt_version'
            ), active_envs AS (
                SELECT pve.version_id, pve.environment
                FROM prompt_version_envs pve
                INNER JOIN pv_for_commit pvc ON pvc.id = pve.version_id
                WHERE pve.workspace_id = :workspace_id AND pve.ended_at IS NULL
            ), ver_envs AS (
                SELECT version_id, JSON_ARRAYAGG(environment) AS environments
                FROM active_envs
                GROUP BY version_id
            )
            SELECT
                p.*,
                (
                    SELECT COUNT(pv2.id)
                    FROM prompt_versions pv2
                    WHERE pv2.prompt_id = p.id
                    AND pv2.workspace_id = p.workspace_id
                    AND pv2.version_type = 'prompt_version'
                ) AS version_count,
                JSON_OBJECT(
                    'id', pvc.id,
                    'prompt_id', pvc.prompt_id,
                    'commit', pvc.commit,
                    'version_number', pvc.version_number,
                    'template', pvc.template,
                    'metadata', pvc.metadata,
                    'change_description', pvc.change_description,
                    'type', pvc.type,
                    'version_type', pvc.version_type,
                    'environments', ve.environments,
                    'tags', pvc.tags,
                    'created_at', pvc.created_at,
                    'created_by', pvc.created_by,
                    'last_updated_at', pvc.last_updated_at,
                    'last_updated_by', pvc.last_updated_by
                ) AS requested_version
            FROM pv_for_commit pvc
            INNER JOIN prompts p ON pvc.prompt_id = p.id AND p.workspace_id = pvc.workspace_id
            LEFT JOIN ver_envs ve ON ve.version_id = pvc.id
            """)
    /**
     * 根据提交哈希查找提示词及其对应版本信息。
     *
     * @param commit      提交哈希值
     * @param workspaceId 工作区ID
     * @return 包含指定提交版本的提示词列表
     */
    List<Prompt> findByCommit(@Bind("commit") String commit, @Bind("workspace_id") String workspaceId);

    @SqlQuery("""
            SELECT p.id AS prompt_id, p.name AS prompt_name,
                   pv.version_number, pv.created_at, pv.created_by
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id AND p.workspace_id = pv.workspace_id
            WHERE pv.workspace_id = :workspace_id
              AND p.project_id = :project_id
              AND pv.version_type = 'prompt_version'
            ORDER BY pv.id DESC
            LIMIT :limit
            """)
    @RegisterConstructorMapper(value = RecentActivity.RecentPromptVersion.class)
    /**
     * 查找指定项目下最近的提示词版本，用于最近活动展示。
     *
     * @param workspaceId 工作区ID
     * @param projectId   项目ID
     * @param limit       返回结果的最大数量
     * @return 最近的提示词版本列表
     */
    List<RecentActivity.RecentPromptVersion> findRecentPromptVersionsByProjectId(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("limit") int limit);

    @SqlQuery("""
            SELECT
                pv.id AS prompt_version_id,
                pv.commit,
                p.id,
                p.name
            FROM prompt_versions pv
            INNER JOIN prompts p ON pv.prompt_id = p.id
            WHERE pv.commit IN (<commits>)
            AND pv.workspace_id = :workspace_id
            AND pv.version_type = 'prompt_version'
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    @RegisterRowMapper(PromptVersionLinkRowMapper.class)
    /**
     * 根据提交哈希集合查找关联的提示词版本链接信息。
     *
     * @param commits     提交哈希集合
     * @param workspaceId 工作区ID
     * @return 提示词版本链接列表
     */
    List<PromptVersionLink> findPromptsByCommits(
            @Define("commits") @BindList("commits") Collection<String> commits,
            @Bind("workspace_id") String workspaceId);

    /**
     * 查询每个工作区的孤立提示词数量，用于提示词项目迁移资格扫描。
     * 返回至少有一个非演示提示词且 {@code project_id IS NULL} 的工作区，
     * 按数量从小到大排序，以便单次循环可以处理低容量工作区。演示提示词通过
     * {@link DemoData#PROMPTS} 排除（utf8mb4_unicode_ci 不区分大小写，
     * 因此单个规范条目涵盖所有大小写变体）。可选的 {@code excluded_workspace_ids}
     * 绑定参数将迁移的环境变量排除列表和持久化的陷阱列表合并为一个查询。
     *
     * @param limit               返回结果的最大数量
     * @param demoPromptNames     演示提示词名称列表
     * @param excludedWorkspaceIds 需要排除的工作区ID集合
     * @return 符合条件的工作区列表，按孤立提示词数量升序排列
     */
    @SqlQuery("""
            SELECT
                workspace_id,
                COUNT(*) AS prompts_count
            FROM prompts
            WHERE project_id IS NULL
                AND name NOT IN (<demo_prompt_names>)
                <if(excluded_workspace_ids)> AND workspace_id NOT IN (<excluded_workspace_ids>) <endif>
            GROUP BY workspace_id
            ORDER BY prompts_count ASC
            LIMIT :limit
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    @RegisterConstructorMapper(EligiblePromptWorkspace.class)
    List<EligiblePromptWorkspace> findEligiblePromptWorkspaces(
            @Bind("limit") int limit,
            @Define("demo_prompt_names") @BindList("demo_prompt_names") List<String> demoPromptNames,
            @Define("excluded_workspace_ids") @BindList(value = "excluded_workspace_ids", onEmpty = BindList.EmptyHandling.NULL_VALUE) Set<String> excludedWorkspaceIds);

    /**
     * 返回单个工作区中最多 {@code :limit} 个孤立的非演示提示词ID。
     * 该上限限制了每个工作区每轮循环的内存使用量以及下游分类查询中
     * ClickHouse {@code IN} 列表的大小。孤立提示词数量超过上限的工作区
     * 将在后续循环中逐步处理——资格扫描会重复发现它们，直到没有剩余。
     *
     * @param workspaceId     工作区ID
     * @param demoPromptNames 演示提示词名称列表
     * @param limit           返回结果的最大数量
     * @return 孤立提示词ID列表
     */
    @SqlQuery("""
            SELECT id
            FROM prompts
            WHERE workspace_id = :workspace_id
                AND project_id IS NULL
                AND name NOT IN (<demo_prompt_names>)
            LIMIT :limit
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<UUID> findOrphanPromptIds(
            @Bind("workspace_id") String workspaceId,
            @Define("demo_prompt_names") @BindList("demo_prompt_names") List<String> demoPromptNames,
            @Bind("limit") int limit);

    /**
     * 幂等的批量赋值操作。{@code project_id IS NULL} 谓词是并发保护条件——
     * 已被并发用户写入设置为其他值的记录会被保留，迁移任务重复运行时对已赋值的
     * 记录不会产生影响（空操作）。表结构中 {@code last_updated_at} 字段上的
     * {@code ON UPDATE CURRENT_TIMESTAMP(6)} 会自动更新行的时间戳。
     *
     * @param workspaceId 工作区ID
     * @param promptIds   需要赋值的提示词ID集合
     * @param projectId   要设置的项目ID
     * @param userName    操作用户名
     * @return 实际更新的记录数
     */
    @SqlUpdate("""
            UPDATE prompts
            SET project_id = :project_id,
                last_updated_by = :user_name
            WHERE workspace_id = :workspace_id
                AND id IN (<prompt_ids>)
                AND project_id IS NULL
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    int batchSetProjectId(
            @Bind("workspace_id") String workspaceId,
            @Define("prompt_ids") @BindList("prompt_ids") Set<UUID> promptIds,
            @Bind("project_id") UUID projectId,
            @Bind("user_name") String userName);

}
