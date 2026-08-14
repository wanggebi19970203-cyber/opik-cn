package com.comet.opik.domain.workspaces;

import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(WorkspacesServiceImpl.class)
public interface WorkspacesService {

    /**
     * 仅对把 {@code first_trace_reported_at} 从 NULL 转换过来的写入者返回 {@code true}。{@code userName}
     * 会被记录在审计列中（调用方传入创建该 trace 的用户）。
     */
    boolean markFirstTraceReported(String workspaceId, String userName);

    /**
     * 返回工作空间在旧版 {@code feedback_scores} ClickHouse 表中是否有数据，从持久化的
     * {@code has_legacy_scores} 列读取。在 bounded-elastic worker 上运行阻塞的 JDBI 查找；当行尚不存在时
     * 默认返回 {@code true}，任何错误时也默认 {@code true}，这样降级的状态数据库不会破坏统计端点。
     * 供 trace/span 统计查询使用，以决定是否 UNION 旧版 {@code feedback_scores} 表。
     */
    Mono<Boolean> hasLegacyScores(String workspaceId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspacesServiceImpl implements WorkspacesService {

    private static final String SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION = "23000";

    private final @NonNull TransactionTemplate transactionTemplate;

    /**
     * 先 UPDATE 后 INSERT，单一事务。此处不能使用带 ROW_COUNT 检查的单语句 upsert，因为 Connector/J 默认
     * {@code useAffectedRows=false}（{@code CLIENT_FOUND_ROWS=on}），这会让“匹配但未改变”的 upsert 返回
     * {@code 1}——与全新插入无法区分。拆分为两个原语可以保持检测的明确性。
     *
     * <p>INSERT 上的重复键意味着在我们 UPDATE 和 INSERT 之间，一个并发的首条 trace 写入者创建了该行
     * （这是插入 {@code workspaces} 的唯一路径）。随后重试 UPDATE-if-null 只会在列仍为 NULL 时翻转它；
     * 由于另一个写入者已经设置了它，此调用方返回 {@code false}，因此恰好只有一个调用方报告首条 trace。</p>
     */
    @Override
    public boolean markFirstTraceReported(@NonNull String workspaceId, @NonNull String userName) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            var now = Instant.now();
            if (dao.updateFirstTraceIfNull(workspaceId, now, userName) > 0) {
                return true;
            }
            try {
                dao.insertFirstTrace(workspaceId, now, userName);
                return true;
            } catch (UnableToExecuteStatementException exception) {
                if (exception.getCause() instanceof SQLException sql
                        && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sql.getSQLState())) {
                    return dao.updateFirstTraceIfNull(workspaceId, now, userName) > 0;
                }
                throw exception;
            }
        });
    }

    @Override
    public Mono<Boolean> hasLegacyScores(@NonNull String workspaceId) {
        if (StringUtils.isBlank(workspaceId)) {
            return Mono.just(true);
        }
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findHasLegacyScores(workspaceId))
                .orElse(true))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(throwable -> {
                    log.warn("解析工作空间 '{}' 的 has_legacy_scores 失败，默认返回 true",
                            workspaceId, throwable);
                    return Mono.just(true);
                });
    }
}
