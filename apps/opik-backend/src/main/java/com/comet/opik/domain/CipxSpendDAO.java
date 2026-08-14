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
 * 从 cipx LLM 调用 span 写入 cipx_spends 表：仅包含 span 级别的调用数据（模型 + 用量
 * 计数器）；block 通过 {@link CipxSpendBlockDAO} 落入 cipx_spend_blocks。由 span 创建事件
 * 异步触发；从不读取 spans 或 cipx_spends 表。cipx 字段在 Java 中从 metadata 解析
 * （{@link SpanRow#from}）；监听器只传递它已经筛选为 cipx 的行。
 *
 * <p>这是一个普通 INSERT：摄取是只创建的（cipx 数据在创建事件上即完整且
 * 不可变），因此 ReplacingMergeTree 仅作为对重放事件的防护——重放
 * 会产生相同的排序键，并在合并时去重。last_updated_at 留给列
 * DEFAULT now64(6)。project_id 必须非空，行才能落在正确的键下——调用方
 * 在调用 insert 之前必须丢弃空行（监听器会这样做）。
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxSpendDAO {

    /** 由 span 的 metadata 构造的 cipx_spends 行。 */
    @Builder(toBuilder = true)
    public record SpanRow(
            @NonNull String spanId,
            @NonNull String traceId,
            @NonNull String projectId,
            @NonNull Instant startTime,
            @NonNull String model,
            long uInput,
            long uCacheRead,
            long uCacheCreation,
            long uCacheCreation5m,
            long uCacheCreation1h,
            long uOutput,
            @NonNull String effort,
            @NonNull String thinkingType,
            long maxTokens,
            @NonNull String contextManagement,
            @NonNull String speed) {

        public static SpanRow from(UUID spanId, UUID traceId, UUID projectId, JsonNode metadata, Instant startTime) {
            JsonNode call = metadata.path("cipx").path("call");
            JsonNode usage = call.path("usage");
            JsonNode cacheCreation = usage.path("cache_creation");
            JsonNode config = call.path("config");
            return SpanRow.builder()
                    .spanId(spanId.toString())
                    .traceId(traceId.toString())
                    .projectId(projectId != null ? projectId.toString() : "")
                    .startTime(startTime)
                    .model(call.path("model").asText(""))
                    .uInput(usage.path("input_tokens").asLong(0))
                    .uCacheRead(usage.path("cache_read_input_tokens").asLong(0))
                    .uCacheCreation(usage.path("cache_creation_input_tokens").asLong(0))
                    .uCacheCreation5m(cacheCreation.path("ephemeral_5m_input_tokens").asLong(0))
                    .uCacheCreation1h(cacheCreation.path("ephemeral_1h_input_tokens").asLong(0))
                    .uOutput(usage.path("output_tokens").asLong(0))
                    .effort(config.path("effort").asText(""))
                    .thinkingType(config.path("thinking_type").asText(""))
                    .maxTokens(config.path("max_tokens").asLong(0))
                    .contextManagement(config.path("context_management").asText(""))
                    .speed(config.path("speed").asText(""))
                    .build();
        }
    }

    // 每行一个元组（对应 SpanDAO.BULK_INSERT）。start_time 从 Java 绑定（来源
    // span 已存储的开始时间）。
    private static final String INSERT = """
            INSERT INTO cipx_spends
                (workspace_id, project_id, trace_id, span_id, start_time, model,
                 u_input, u_cache_read, u_cache_creation, u_cache_creation_5m, u_cache_creation_1h, u_output,
                 effort, thinking_type, max_tokens, context_management, speed)
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (
                        :workspace_id,
                        :project_id<item.index>,
                        :trace_id<item.index>,
                        :span_id<item.index>,
                        :start_time<item.index>,
                        :model<item.index>,
                        :u_input<item.index>,
                        :u_cache_read<item.index>,
                        :u_cache_creation<item.index>,
                        :u_cache_creation_5m<item.index>,
                        :u_cache_creation_1h<item.index>,
                        :u_output<item.index>,
                        :effort<item.index>,
                        :thinking_type<item.index>,
                        :max_tokens<item.index>,
                        :context_management<item.index>,
                        :speed<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Mono<Long> insert(@NonNull List<SpanRow> rows, @NonNull String workspaceId, @NonNull String userName) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(rows, workspaceId, userName, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> insert(List<SpanRow> rows, String workspaceId, String userName,
            Connection connection) {
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(rows.size());
        ST template = getSTWithLogComment(INSERT, "insert_cipx_spends", workspaceId, userName, rows.size());
        template.add("items", queryItems);
        Statement statement = connection.createStatement(template.render());

        // 位置绑定：驱动程序通过语句参数列表的线性 indexOf 来解析命名绑定
        // （每条语句为二次复杂度），而 bind(int) 是直接数组写入。索引
        // 遵循占位符在渲染后的 SQL 中的首次出现顺序：workspace_id 在 0 处出现一次
        // （重复项去重），然后每行元组按模板顺序有 16 个参数。
        statement.bind(0, workspaceId);
        int index = 1;
        for (SpanRow row : rows) {
            statement.bind(index++, row.projectId())
                    .bind(index++, row.traceId())
                    .bind(index++, row.spanId())
                    .bind(index++, ClickHouseDateTimeFormat.formatNanos(row.startTime()))
                    .bind(index++, row.model())
                    .bind(index++, row.uInput())
                    .bind(index++, row.uCacheRead())
                    .bind(index++, row.uCacheCreation())
                    .bind(index++, row.uCacheCreation5m())
                    .bind(index++, row.uCacheCreation1h())
                    .bind(index++, row.uOutput())
                    .bind(index++, row.effort())
                    .bind(index++, row.thinkingType())
                    .bind(index++, row.maxTokens())
                    .bind(index++, row.contextManagement())
                    .bind(index++, row.speed());
        }

        return statement.execute();
    }
}
