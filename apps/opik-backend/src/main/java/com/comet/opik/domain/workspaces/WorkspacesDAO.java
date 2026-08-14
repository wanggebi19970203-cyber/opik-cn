package com.comet.opik.domain.workspaces;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Optional;

public interface WorkspacesDAO {

    /**
     * 原子化的 NULL → timestamp 转换。当此调用方把某现有行的 {@code first_trace_reported_at} 从 NULL 翻转为
     * {@code :reportedAt} 时返回 1；如果没有行存在或该列已非空，则返回 0。与 {@link #insertFirstTrace}
     * 配对使用以处理行缺失的情况。
     */
    @SqlUpdate("""
            UPDATE workspaces
            SET first_trace_reported_at = :reportedAt,
                last_updated_by = :userName
            WHERE id = :id AND first_trace_reported_at IS NULL
            """)
    int updateFirstTraceIfNull(@Bind("id") String id,
            @Bind("reportedAt") Instant reportedAt,
            @Bind("userName") String userName);

    /**
     * 普通 INSERT（非 upsert）。遇到重复键时抛出异常——调用方捕获后读取现有行的首条 trace 状态，
     * 以判断自己是否为首个写入者。
     */
    @SqlUpdate("""
            INSERT INTO workspaces (id, first_trace_reported_at, created_by, last_updated_by)
            VALUES (:id, :reportedAt, :userName, :userName)
            """)
    void insertFirstTrace(@Bind("id") String id,
            @Bind("reportedAt") Instant reportedAt,
            @Bind("userName") String userName);

    /**
     * 返回工作空间的 legacy-feedback-scores 标志。当工作空间行尚不存在时返回 {@code Optional.empty()}——
     * 调用方将其视为 TRUE（安全包含 UNION），与列默认值一致。该列没有写入者，因此在相关表退役之前，
     * 它一直保持 {@code TRUE} 默认值，旧版 {@code feedback_scores} UNION 也始终被包含。
     */
    @SqlQuery("SELECT has_legacy_scores FROM workspaces WHERE id = :id")
    Optional<Boolean> findHasLegacyScores(@Bind("id") String id);
}
