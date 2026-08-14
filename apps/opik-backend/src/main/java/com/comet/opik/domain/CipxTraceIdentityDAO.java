package com.comet.opik.domain;

import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * 从 cipx trace 写入 cipx_trace_identities 表。由 trace 创建/更新事件异步触发
 * （身份信息可能随 trace 更新而到达或变化）；从不读取 traces 或
 * cipx_trace_identities 表。身份字段在 Java 中从 metadata 解析
 * （{@link TraceIdentityRow#from}）。普通 INSERT，依靠 ReplacingMergeTree 按排序键合并；
 * last_updated_at 留给列 DEFAULT now64(6)。project_id 必须非空，因此
 * 空行会被丢弃。
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxTraceIdentityDAO {

    /** 由 trace 的 metadata 构造的 cipx_trace_identities 行。 */
    @Builder(toBuilder = true)
    public record TraceIdentityRow(
            @NonNull String traceId,
            @NonNull String projectId,
            @NonNull Instant startTime,
            @NonNull String userUuid,
            @NonNull String userEmail,
            @NonNull String userDisplayName,
            @NonNull String repository,
            @NonNull String sessionId,
            @NonNull String harness,
            int schemaVersion,
            @NonNull String billingMode,
            @NonNull String plan,
            @NonNull String planUsageStatus,
            @NonNull String organizationType,
            @NonNull String seatTier,
            @NonNull String billingType,
            @NonNull String branch,
            @NonNull String headShaStart,
            @NonNull String headShaEnd,
            boolean dirty,
            int commitsInTrace,
            int filesAdded,
            int filesDeleted,
            int linesAdded,
            int linesDeleted) {

        public static TraceIdentityRow from(UUID traceId, UUID projectId, JsonNode metadata, Instant startTime) {
            JsonNode session = metadata.path("cipx").path("session");
            JsonNode identity = session.path("identity");
            JsonNode repository = session.path("repository");
            String userUuid = identity.path("user_uuid").asText("");
            if (userUuid.isEmpty()) {
                userUuid = identity.path("user_id").asText("");
            }
            return TraceIdentityRow.builder()
                    .traceId(traceId.toString())
                    .projectId(projectId != null ? projectId.toString() : "")
                    .startTime(startTime)
                    .userUuid(userUuid)
                    .userEmail(identity.path("email").asText(""))
                    .userDisplayName(identity.path("display_name").asText(""))
                    .repository(repository.path("remote").asText(""))
                    .sessionId(session.path("session_id").asText(""))
                    .harness(session.path("harness").asText(""))
                    .schemaVersion(session.path("schema_version").asInt(0))
                    .billingMode(identity.path("billing_mode").asText(""))
                    .plan(identity.path("plan").asText(""))
                    .planUsageStatus(identity.path("plan_usage_status").asText(""))
                    .organizationType(identity.path("organization_type").asText(""))
                    .seatTier(identity.path("seat_tier").asText(""))
                    .billingType(identity.path("billing_type").asText(""))
                    .branch(repository.path("branch").asText(""))
                    .headShaStart(repository.path("head_sha").asText(""))
                    .headShaEnd(repository.path("head_sha_end").asText(""))
                    .dirty(repository.path("dirty").asBoolean(false))
                    .commitsInTrace(repository.path("commits_in_trace").asInt(0))
                    .filesAdded(repository.path("files_added").asInt(0))
                    .filesDeleted(repository.path("files_deleted").asInt(0))
                    .linesAdded(repository.path("lines_added").asInt(0))
                    .linesDeleted(repository.path("lines_deleted").asInt(0))
                    .build();
        }
    }

    // 每行一个元组（对应 SpanDAO.BULK_INSERT）。start_time 从 Java 绑定（来源
    // trace 已存储的开始时间）。
    private static final String INSERT = """
            INSERT INTO cipx_trace_identities
                (workspace_id, project_id, trace_id, start_time, user_uuid,
                 user_email, user_display_name, repository, session_id, harness, schema_version,
                 billing_mode, plan, plan_usage_status, organization_type, seat_tier, billing_type,
                 branch, head_sha_start, head_sha_end, dirty, commits_in_trace,
                 files_added, files_deleted, lines_added, lines_deleted)
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (
                        :workspace_id,
                        :project_id<item.index>,
                        :trace_id<item.index>,
                        :start_time<item.index>,
                        :user_uuid<item.index>,
                        :user_email<item.index>,
                        :user_display_name<item.index>,
                        :repository<item.index>,
                        :session_id<item.index>,
                        :harness<item.index>,
                        :schema_version<item.index>,
                        :billing_mode<item.index>,
                        :plan<item.index>,
                        :plan_usage_status<item.index>,
                        :organization_type<item.index>,
                        :seat_tier<item.index>,
                        :billing_type<item.index>,
                        :branch<item.index>,
                        :head_sha_start<item.index>,
                        :head_sha_end<item.index>,
                        :dirty<item.index>,
                        :commits_in_trace<item.index>,
                        :files_added<item.index>,
                        :files_deleted<item.index>,
                        :lines_added<item.index>,
                        :lines_deleted<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Mono<Long> upsert(@NonNull List<TraceIdentityRow> rows, @NonNull String workspaceId,
            @NonNull String userName) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(rows, workspaceId, userName, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> insert(List<TraceIdentityRow> rows, String workspaceId, String userName,
            Connection connection) {
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(rows.size());
        ST template = getSTWithLogComment(INSERT, "insert_cipx_trace_identities", workspaceId, userName, rows.size());
        template.add("items", queryItems);
        Statement statement = connection.createStatement(template.render());

        // 位置绑定：驱动程序通过语句参数列表的线性 indexOf 来解析命名绑定
        // （每条语句为二次复杂度），而 bind(int) 是直接数组写入。索引
        // 遵循占位符在渲染后的 SQL 中的首次出现顺序：workspace_id 在 0 处出现一次
        // （重复项去重），然后每行元组按模板顺序有 25 个参数。
        statement.bind(0, workspaceId);
        int index = 1;
        for (TraceIdentityRow row : rows) {
            statement.bind(index++, row.projectId())
                    .bind(index++, row.traceId())
                    .bind(index++, ClickHouseDateTimeFormat.formatNanos(row.startTime()))
                    .bind(index++, row.userUuid())
                    .bind(index++, row.userEmail())
                    .bind(index++, row.userDisplayName())
                    .bind(index++, row.repository())
                    .bind(index++, row.sessionId())
                    .bind(index++, row.harness())
                    .bind(index++, row.schemaVersion())
                    .bind(index++, row.billingMode())
                    .bind(index++, row.plan())
                    .bind(index++, row.planUsageStatus())
                    .bind(index++, row.organizationType())
                    .bind(index++, row.seatTier())
                    .bind(index++, row.billingType())
                    .bind(index++, row.branch())
                    .bind(index++, row.headShaStart())
                    .bind(index++, row.headShaEnd())
                    .bind(index++, row.dirty())
                    .bind(index++, row.commitsInTrace())
                    .bind(index++, row.filesAdded())
                    .bind(index++, row.filesDeleted())
                    .bind(index++, row.linesAdded())
                    .bind(index++, row.linesDeleted());
        }

        return statement.execute();
    }
}
