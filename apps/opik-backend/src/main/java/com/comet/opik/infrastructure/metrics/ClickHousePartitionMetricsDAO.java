package com.comet.opik.infrastructure.metrics;

import com.comet.opik.utils.template.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 支持分区健康可观测性指标（OPIK-6904，第 11.1 节）的只读 ClickHouse DAO。
 * 从 {@code system.parts} 获取每个（表，分区）的大小、行、part 和活动数据，
 * 并从表本身获取轻量删除（LWD 掩码）行计数。一切都只针对 {@code active} parts，
 * 并限定在分析数据库范围内。
 *
 * <p>这是基础设施可观测性，不是应用领域：它报告的是存储引擎的内部状态，
 * 而非任何业务实体。
 */
@ImplementedBy(ClickHousePartitionMetricsDAOImpl.class)
public interface ClickHousePartitionMetricsDAO {

    @Builder(toBuilder = true)
    record PartitionStat(String table, String partition, long parts, long rows, long bytes,
            long maxPartBytes, long lastActivityEpochSeconds) {
    }

    @Builder(toBuilder = true)
    record LwdStat(String table, String partition, long lwdRows) {
    }

    Mono<List<PartitionStat>> getPartitionStats();

    Mono<List<LwdStat>> getLwdRowCounts(List<String> tables);
}

@Slf4j
@Singleton
class ClickHousePartitionMetricsDAOImpl implements ClickHousePartitionMetricsDAO {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z0-9_]+");

    /**
     * 对活跃 part 按（表，分区）聚合。所有聚合都被转换为 Int64，这样
     * r2dbc 驱动会把它们映射为 {@code Long}（原始的 {@code sum}/{@code count} 产生 UInt64）。
     * 分区键使用 {@code partition_id}，从而与 LWD 查询使用的
     * {@code _partition_id} 虚拟列对齐。
     */
    private static final String PARTITION_STATS_SQL = """
            SELECT
                table AS table_name,
                partition_id AS partition_id,
                toInt64(count()) AS parts,
                toInt64(sum(rows)) AS rows,
                toInt64(sum(bytes_on_disk)) AS bytes,
                toInt64(max(bytes_on_disk)) AS max_part_bytes,
                toInt64(toUnixTimestamp(max(modification_time))) AS last_activity
            FROM system.parts
            WHERE database = :database_name AND active
            GROUP BY table, partition_id
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * 单个表按分区的 LWD 掩码行计数。{@code apply_deleted_mask = 0}
     * 禁用隐式过滤，这样被掩码的行对计数可见；没有被掩码行的分区
     * 会从结果中缺失（形成 0 序列）。只读取近乎恒定的
     * {@code _row_exists} 列，使全表扫描保持廉价，尽管会触及每一行
     * （在最大的生产表上约 1.3 秒，由单个分布式锁持有者每个间隔运行一次）。
     *
     * <p>这必须以后端读写 ClickHouse 用户执行。{@code apply_deleted_mask}
     * 是按查询的设置，而 {@code readonly = 1} 用户无法更改它 —— 否则查询会
     * 以 {@code Code 164 (READONLY)} 失败。该设置是强制的，不是可选的：没有它，
     * 被掩码的行会被过滤掉，计数永远为 0。
     *
     * <p>{@code max_execution_time} 和 {@code priority} 限制了扫描，这样它绝不会与
     * 客户查询负载争抢，即使面对病态庞大的未来分区。表名在
     * 被校验为纯标识符之后由模板引擎渲染，因此它绝不会成为注入向量。
     */
    private static final String LWD_ROWS_SQL = """
            SELECT
                _partition_id AS partition_id,
                toInt64(count()) AS lwd_rows
            FROM <table>
            WHERE _row_exists = 0
            GROUP BY _partition_id
            SETTINGS apply_deleted_mask = 0, max_execution_time = 30, priority = 100, log_comment = '<log_comment>'
            """;

    private final ConnectionFactory connectionFactory;
    private final String databaseName;

    @Inject
    public ClickHousePartitionMetricsDAOImpl(
            @NonNull ConnectionFactory connectionFactory,
            @NonNull @Named("Database Analytics Database Name") String databaseName) {
        this.connectionFactory = connectionFactory;
        this.databaseName = databaseName;
    }

    @Override
    public Mono<List<PartitionStat>> getPartitionStats() {
        String query = TemplateUtils.newST(PARTITION_STATS_SQL)
                .add("log_comment", "partition_metrics_partition_stats")
                .render();
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(query)
                        .bind("database_name", databaseName)
                        .execute())
                .flatMap(result -> result.map((row, rowMetadata) -> PartitionStat.builder()
                        .table(row.get("table_name", String.class))
                        .partition(row.get("partition_id", String.class))
                        .parts(row.get("parts", Long.class))
                        .rows(row.get("rows", Long.class))
                        .bytes(row.get("bytes", Long.class))
                        .maxPartBytes(row.get("max_part_bytes", Long.class))
                        .lastActivityEpochSeconds(row.get("last_activity", Long.class))
                        .build()))
                .collectList();
    }

    @Override
    public Mono<List<LwdStat>> getLwdRowCounts(@NonNull List<String> tables) {
        return Flux.fromIterable(tables)
                .filter(this::isValidTable)
                .flatMap(table -> {
                    String query = TemplateUtils.newST(LWD_ROWS_SQL)
                            .add("table", table)
                            .add("log_comment", "partition_metrics_lwd_rows")
                            .render();
                    return Mono.from(connectionFactory.create())
                            .flatMapMany(connection -> connection.createStatement(query).execute())
                            .flatMap(result -> result.map((row, rowMetadata) -> LwdStat.builder()
                                    .table(table)
                                    .partition(row.get("partition_id", String.class))
                                    .lwdRows(row.get("lwd_rows", Long.class))
                                    .build()));
                })
                .collectList();
    }

    private boolean isValidTable(String table) {
        if (table != null && IDENTIFIER.matcher(table).matches()) {
            return true;
        }
        log.warn("跳过无效的 LWD 表名: '{}'", table);
        return false;
    }
}
