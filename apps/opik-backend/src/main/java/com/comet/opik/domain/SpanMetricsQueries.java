package com.comet.opik.domain;

/**
 * 用于基于 span 的指标的共享 ClickHouse 查询片段。span 过滤 CTE（反馈分数去重 + span 过滤）
 * 对于按项目（{@link ProjectMetricsDAO}）和工作区级别（{@link WorkspaceMetricsDAO}）的聚合是完全相同的；
 * 唯一的区别是项目谓词，它通过 {@link #spanFilteredPrefix(String)} 注入，
 * 以便两个 DAO 在 CTE 变化时保持同步。
 * <p>
 * {@code spans} 扫描上的每个 {@code id} 范围边界都带有一个平行的 {@code toMonday(id_at)} 边界：这是
 * id 范围的严格推论，它扫描相同的行，但一旦 {@code spans} 被分区，就会启用周分区裁剪，
 * 而优化器无法通过 {@code UUIDv7ToDateTime} 推断出这一点。
 */
final class SpanMetricsQueries {

    private SpanMetricsQueries() {
    }

    // %s 占位符是项目谓词："project_id = :project_id"（单个项目）或
    // "project_id IN :project_ids"（一组已解析的项目）。workspace_id 始终单独绑定。
    private static final String SPAN_FILTERED_PREFIX_TEMPLATE = """
            WITH feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       source_queue_id
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND %s
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time<endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time<endif>
                    UNION ALL
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           author,
                           source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND %s
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time<endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time<endif>
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
             ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ),
            <if(feedback_scores_empty_filters)>
             fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            ),
            <endif>
            spans_filtered AS (
                SELECT
                    id,
                    UUIDv7ToDateTime(toUUID(id)) as span_time,
                    duration,
                    usage,
                    error_info,
                    total_estimated_cost
                    <if(group_expression)>,
                    project_id,
                    name,
                    tags,
                    metadata,
                    model,
                    provider,
                    type
                    <endif>
                FROM (
                    SELECT
                        id,
                        duration,
                        usage,
                        error_info,
                        total_estimated_cost
                        <if(group_expression)>,
                        project_id,
                        name,
                        tags,
                        metadata,
                        model,
                        provider,
                        type
                        <endif>
                    FROM spans FINAL
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = spans.id
                    <endif>
                    WHERE workspace_id = :workspace_id
                    AND %s
                    <if(uuid_from_time)> AND id >= :uuid_from_time
                    AND toMonday(id_at) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'))<endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time
                    AND toMonday(id_at) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'))<endif>
                    <if(span_filters)> AND <span_filters> <endif>
                    <if(span_feedback_scores_filters)>
                    AND id in (
                        SELECT
                            entity_id
                        FROM (
                            SELECT *
                            FROM feedback_scores_final
                            ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                            LIMIT 1 BY entity_id, name
                        )
                        GROUP BY entity_id
                        HAVING <span_feedback_scores_filters>
                    )
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    AND fsc.feedback_scores_count = 0
                    <endif>
                ) AS t
            )
            """;

    static String spanFilteredPrefix(String projectPredicate) {
        return SPAN_FILTERED_PREFIX_TEMPLATE.formatted(projectPredicate, projectPredicate, projectPredicate);
    }

    // 去重后的 span token 用量键名。%s 占位符是项目谓词："project_id = :project_id"（单个
    // 项目，{@link ProjectMetricsDAO}）或 "project_id IN :project_ids"（一组已解析的集合，{@link WorkspaceMetricsDAO}）；
    // workspace_id 始终单独绑定。共享此片段，以确保两个调用方不会产生偏差。
    private static final String TOKEN_USAGE_NAMES_TEMPLATE = """
            SELECT DISTINCT name
            FROM (
                SELECT usage
                FROM spans final
                WHERE workspace_id = :workspace_id
                AND %s
            )
            ARRAY JOIN
                mapKeys(usage) AS name,
                mapValues(usage) AS value
            WHERE value > 0
            SETTINGS log_comment = '<log_comment>';
            """;

    static String tokenUsageNames(String projectPredicate) {
        return TOKEN_USAGE_NAMES_TEMPLATE.formatted(projectPredicate);
    }
}
