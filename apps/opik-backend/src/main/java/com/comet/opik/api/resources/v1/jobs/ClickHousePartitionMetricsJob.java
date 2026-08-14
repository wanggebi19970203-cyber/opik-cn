package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.infrastructure.PartitionMetricsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.LwdStat;
import com.comet.opik.infrastructure.metrics.ClickHousePartitionMetricsDAO.PartitionStat;
import io.dropwizard.jobs.Job;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToLongFunction;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Quartz 作业，将 ClickHouse 分区健康指标（OPIK-6904，第 11.1 节）作为
 * OpenTelemetry gauge 发布到 {@code opik.clickhouse.partition.*} 命名空间下。
 *
 * <p>这些是集群全局指标，因此必须由单个实例负责轮询：{@code doJob}
 * 获取一个分布式锁（持有到下一个周期），从 {@link ClickHousePartitionMetricsDAO}
 * 刷新内存快照，并且已注册的可观测 gauge 在每次 OTel 采集时上报该快照。
 * 未能获取到锁的实例会清空其快照从而停止上报——这样每个 (table, partition)
 * 只保留一个序列，并让过期分区从 Prometheus 中消失，而不是残留为陈旧值。
 */
@Singleton
@Slf4j
@DisallowConcurrentExecution
public class ClickHousePartitionMetricsJob extends Job implements InterruptableJob {

    private static final Lock RUN_LOCK = new Lock("clickhouse:partition_metrics_lock");

    private static final AttributeKey<String> TABLE_KEY = stringKey("table");
    private static final AttributeKey<String> PARTITION_KEY = stringKey("partition");

    @Builder(toBuilder = true)
    private record Snapshot(List<PartitionStat> partitionStats, List<LwdStat> lwdStats) {
        private static final Snapshot EMPTY = Snapshot.builder()
                .partitionStats(List.of())
                .lwdStats(List.of())
                .build();
    }

    private final ClickHousePartitionMetricsDAO partitionMetricsDAO;
    private final LockService lockService;
    private final PartitionMetricsConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.EMPTY);
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    @Inject
    public ClickHousePartitionMetricsJob(
            @NonNull ClickHousePartitionMetricsDAO partitionMetricsDAO,
            @NonNull LockService lockService,
            @NonNull @Config("partitionMetrics") PartitionMetricsConfig config) {
        this.partitionMetricsDAO = partitionMetricsDAO;
        this.lockService = lockService;
        this.config = config;

        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.clickhouse");

        // 每个表聚合为一个序列（分区数量是唯一非按分区的指标）。
        meter.gaugeBuilder("opik.clickhouse.partition.count").ofLongs()
                .setDescription("Number of active partitions per table")
                .buildWithCallback(measurement -> {
                    Map<String, Set<String>> partitionsByTable = new HashMap<>();
                    for (PartitionStat stat : snapshot.get().partitionStats()) {
                        partitionsByTable.computeIfAbsent(stat.table(), key -> new HashSet<>()).add(stat.partition());
                    }
                    partitionsByTable.forEach((table, partitions) -> measurement.record(partitions.size(),
                            Attributes.of(TABLE_KEY, table)));
                });

        // 每个 (table, partition) 的序列来源于 system.parts。
        registerPartitionGauge(meter, "opik.clickhouse.partition.size_bytes",
                "Total size on disk of active parts per partition", PartitionStat::bytes);
        registerPartitionGauge(meter, "opik.clickhouse.partition.max_part_size_bytes",
                "Largest single active part size per partition (max by table = largest active part)",
                PartitionStat::maxPartBytes);
        registerPartitionGauge(meter, "opik.clickhouse.partition.parts",
                "Number of active parts per partition", PartitionStat::parts);
        registerPartitionGauge(meter, "opik.clickhouse.partition.rows",
                "Total physical rows (including LWD-masked) of active parts per partition", PartitionStat::rows);
        registerPartitionGauge(meter, "opik.clickhouse.partition.last_activity_seconds",
                "Unix timestamp of the most recent part modification per partition",
                PartitionStat::lastActivityEpochSeconds);

        // 每个 (table, partition) 的序列来源于 LWD 掩码扫描。
        registerLwdGauge(meter, "opik.clickhouse.partition.lwd_rows",
                "Number of lightweight-deleted (masked) rows per partition", LwdStat::lwdRows);
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("ClickHouse 分区指标作业在执行前被中断，跳过");
            return;
        }

        // 延迟执行，以便 DAO 调用（及其查询渲染）仅在持有锁之后才运行——
        // bestEffortLock 只在获取到许可之后才订阅此 Mono。LWD 扫描是
        // 昂贵且易出错的一环（需要读写 CH 用户的全表掩码扫描）；其
        // 失败绝不能拖累廉价可靠的 system.parts gauge，因此它降级为空
        // 列表，LWD gauge 只是停止上报，直到下一次成功轮询。
        Mono<Void> refresh = Mono.defer(() -> {
            if (interrupted.get()) {
                return Mono.empty();
            }
            Mono<List<LwdStat>> lwdRowCounts = partitionMetricsDAO.getLwdRowCounts(config.getLwdTables())
                    .onErrorResume(e -> {
                        log.warn("ClickHouse 分区指标：LWD 行数扫描失败，"
                                + "分区 gauge 仍会刷新", e);
                        return Mono.just(List.of());
                    });
            return Mono
                    .zip(partitionMetricsDAO.getPartitionStats(), lwdRowCounts)
                    .doOnNext(this::updateSnapshot)
                    .then();
        });

        var subscription = lockService.bestEffortLock(
                RUN_LOCK,
                refresh,
                Mono.fromRunnable(() -> {
                    log.debug(
                            "ClickHouse 分区指标：另一个实例持有轮询锁，正在清空快照");
                    snapshot.set(Snapshot.EMPTY);
                }),
                config.getInterval().toJavaDuration(),
                Duration.ZERO,
                true) // holdUntilExpiry：每个周期只有一个实例轮询
                .onErrorResume(throwable -> {
                    if (interrupted.get()) {
                        log.warn("ClickHouse 分区指标轮询被中断", throwable);
                    } else {
                        log.error("ClickHouse 分区指标轮询失败", throwable);
                    }
                    return Mono.empty();
                })
                .doFinally(signal -> currentExecution.set(null))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        currentExecution.set(subscription);
    }

    private void updateSnapshot(Tuple2<List<PartitionStat>, List<LwdStat>> result) {
        snapshot.set(Snapshot.builder()
                .partitionStats(result.getT1())
                .lwdStats(result.getT2())
                .build());
        log.debug("ClickHouse 分区指标已刷新：'{}' 个分区，'{}' 个 LWD 分区",
                result.getT1().size(), result.getT2().size());
    }

    private void registerPartitionGauge(Meter meter, String name, String description,
            ToLongFunction<PartitionStat> extractor) {
        meter.gaugeBuilder(name).ofLongs()
                .setDescription(description)
                .buildWithCallback(measurement -> snapshot.get().partitionStats()
                        .forEach(stat -> measurement.record(extractor.applyAsLong(stat),
                                attributes(stat.table(), stat.partition()))));
    }

    private void registerLwdGauge(Meter meter, String name, String description,
            ToLongFunction<LwdStat> extractor) {
        meter.gaugeBuilder(name).ofLongs()
                .setDescription(description)
                .buildWithCallback(measurement -> snapshot.get().lwdStats()
                        .forEach(stat -> measurement.record(extractor.applyAsLong(stat),
                                attributes(stat.table(), stat.partition()))));
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("ClickHouse 分区指标作业被中断");
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
        }
    }

    private static Attributes attributes(String table, String partition) {
        return Attributes.of(TABLE_KEY, table, PARTITION_KEY, partition);
    }
}
