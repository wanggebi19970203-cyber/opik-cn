package com.comet.opik.domain.optimization;

import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.OptimizationLogsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RListReactive;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * 用于将优化日志从 Redis 同步到 S3 的服务。
 * <p>
 * Python 后端在优化执行期间会把日志写入 Redis。
 * 该服务负责：
 * 1. 定期将日志从 Redis 同步到 S3（由定时任务调用）
 * 2. 在优化完成时终结日志（按需）
 * <p>
 * Redis 键结构（由 Python 后端设置）：
 * - 日志：opik:logs:{workspace_id}:{optimization_id}（原始日志行的 LIST）
 * - 元数据：opik:logs:{workspace_id}:{optimization_id}:meta（带时间戳的 HASH）
 * <p>
 * S3 键结构：
 * - logs/{workspace_id}/{optimization_id}.log
 */
@ImplementedBy(OptimizationLogSyncServiceImpl.class)
public interface OptimizationLogSyncService {

    /**
     * 优化日志元数据的 Redis 键模式。
     * 供 OptimizationLogFlusherJob 用于扫描活动中的优化。
     */
    String META_KEY_PATTERN = "opik:logs:*:meta";

    /**
     * 优化日志的 S3 键模式。
     * 格式：logs/optimization-studio/{workspace_id}/{optimization_id}.log.gz
     */
    String S3_KEY_PATTERN = "logs/optimization-studio/%s/%s.log.gz";

    /**
     * 为某个优化的日志文件格式化 S3 键。
     *
     * @param workspaceId    工作空间 ID
     * @param optimizationId 优化 ID
     * @return S3 键路径
     */
    static String formatS3Key(String workspaceId, UUID optimizationId) {
        return String.format(S3_KEY_PATTERN, workspaceId, optimizationId);
    }

    /**
     * 如果自上次刷新以来有新日志，则将日志从 Redis 同步到 S3。
     * 使用分布式锁来防止跨实例重复工作。
     *
     * @param workspaceId    工作空间 ID
     * @param optimizationId 优化 ID
     * @return 同步完成后（或没有新日志时跳过）完成的 Mono
     */
    Mono<Void> syncLogsToS3(@NonNull String workspaceId, @NonNull UUID optimizationId);

    /**
     * 为已完成的优化终结日志。
     * 这会把 Redis 中剩余的任何日志刷到 S3，并删除 Redis 键。
     *
     * @param workspaceId    工作空间 ID
     * @param optimizationId 优化 ID
     * @return 日志终结完成后完成的 Mono
     */
    Mono<Void> finalizeLogsOnCompletion(@NonNull String workspaceId, @NonNull UUID optimizationId);

    /**
     * 把单条系统生成的日志行追加到某优化的 Redis 日志流，使其与 worker 自身的日志一起在 UI 中呈现。
     * 当后端（而不是 worker）终止一次运行时（例如停滞运行回收器），需要记录一个人类可读的原因时使用——
     * 对于 worker 从未启动的运行，否则根本没有任何日志。它会递增 {@code last_append_ts}，让定期刷新器和
     * 完成终结都能把该行同步到 S3。尽力而为：绝不使调用方失败。
     *
     * @param workspaceId    工作空间 ID
     * @param optimizationId 优化 ID
     * @param message        要追加的日志行（无需尾部换行）
     * @return 该行追加完成后（或日志被禁用时立即）完成的 Mono
     */
    Mono<Void> appendSystemLogLine(@NonNull String workspaceId, @NonNull UUID optimizationId,
            @NonNull String message);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OptimizationLogSyncServiceImpl implements OptimizationLogSyncService {

    private static final String REDIS_LOG_KEY_PATTERN = "opik:logs:%s:%s";
    private static final String REDIS_META_KEY_PATTERN = "opik:logs:%s:%s:meta";
    private static final String LOCK_KEY_PATTERN = "opik:lock:logs:%s:%s";

    private static final String META_LAST_APPEND_TS = "last_append_ts";
    private static final String META_LAST_FLUSH_TS = "last_flush_ts";

    private static final String CONTENT_TYPE_GZIP = "application/gzip";

    /**
     * 用于保存日志时间戳信息以供决策日志的记录。
     */
    private record LogTimestamps(long lastAppend, long lastFlush) {
    }

    private final @NonNull RedissonReactiveClient redisClient;
    private final @NonNull FileService fileService;
    private final @NonNull LockService lockService;
    private final @NonNull @Config("optimizationLogs") OptimizationLogsConfig config;

    @Override
    @WithSpan
    public Mono<Void> syncLogsToS3(@NonNull String workspaceId, @NonNull UUID optimizationId) {
        if (!config.isEnabled()) {
            log.debug("优化日志同步已禁用");
            return Mono.empty();
        }

        String logKey = formatLogKey(workspaceId, optimizationId);
        String metaKey = formatMetaKey(workspaceId, optimizationId);

        // 首先检查是否有新日志（快速路径，无需锁）
        return getLogTimestamps(metaKey)
                .flatMap(timestamps -> {
                    long lastAppend = timestamps.lastAppend;
                    long lastFlush = timestamps.lastFlush;
                    boolean hasNew = lastAppend > lastFlush;

                    if (!hasNew) {
                        return Mono.empty();
                    }

                    // 获取锁并执行同步
                    return executeWithLock(workspaceId, optimizationId,
                            doSyncToS3(workspaceId, optimizationId, logKey, metaKey, false));
                });
    }

    // 终结后设置在 Redis 键上的 TTL（1 小时）- 允许捕获迟到的日志
    private static final long FINALIZATION_TTL_SECONDS = 3600;

    @Override
    @WithSpan
    public Mono<Void> appendSystemLogLine(@NonNull String workspaceId, @NonNull UUID optimizationId,
            @NonNull String message) {
        if (!config.isEnabled()) {
            log.debug("优化日志同步已禁用，跳过系统日志行");
            return Mono.empty();
        }

        String logKey = formatLogKey(workspaceId, optimizationId);
        String metaKey = formatMetaKey(workspaceId, optimizationId);

        RListReactive<String> logList = redisClient.getList(logKey, StringCodec.INSTANCE);
        RMapReactive<String, String> metaMap = redisClient.getMap(metaKey, StringCodec.INSTANCE);

        // 追加该行并递增 last_append_ts，这样定期刷新器和完成终结都会把它当作新内容并同步到 S3。
        return logList.add(message)
                .then(metaMap.put(META_LAST_APPEND_TS, String.valueOf(System.currentTimeMillis())))
                .doOnSuccess(__ -> log.info("已为优化 '{}' 追加系统日志行", optimizationId))
                .onErrorResume(error -> {
                    log.warn("为优化 '{}' 追加系统日志行失败", optimizationId, error);
                    return Mono.empty();
                })
                .then();
    }

    @Override
    @WithSpan
    public Mono<Void> finalizeLogsOnCompletion(@NonNull String workspaceId, @NonNull UUID optimizationId) {
        if (!config.isEnabled()) {
            log.debug("优化日志同步已禁用");
            return Mono.empty();
        }

        log.info("正在终结工作空间 '{}' 中优化 '{}' 的日志", optimizationId, workspaceId);

        String logKey = formatLogKey(workspaceId, optimizationId);
        String metaKey = formatMetaKey(workspaceId, optimizationId);

        // 将日志同步到 S3 并缩短 TTL（不删除 - 允许捕获迟到的日志）
        return executeWithLock(workspaceId, optimizationId,
                doSyncToS3AndReduceTTL(workspaceId, optimizationId, logKey, metaKey));
    }

    /**
     * 将日志同步到 S3，并将 Redis 键的 TTL 缩短为 1 小时。
     * 我们不会立即删除，以允许定期刷新器任务捕获任何迟到的日志。
     */
    private Mono<Void> doSyncToS3AndReduceTTL(String workspaceId, UUID optimizationId,
            String logKey, String metaKey) {
        return doSyncToS3(workspaceId, optimizationId, logKey, metaKey, true);
    }

    /**
     * 终结后将 Redis 键的 TTL 缩短为 1 小时。
     * 这允许迟到的日志仍能被定期刷新器捕获。
     */
    private Mono<Void> reduceRedisTTL(String logKey, String metaKey, UUID optimizationId) {
        return Mono.zip(
                redisClient.getKeys().expire(logKey, FINALIZATION_TTL_SECONDS, TimeUnit.SECONDS),
                redisClient.getKeys().expire(metaKey, FINALIZATION_TTL_SECONDS, TimeUnit.SECONDS))
                .doOnSuccess(__ -> log.info("已将优化 '{}' 的 Redis 键 TTL 缩短为 1 小时", optimizationId))
                .then();
    }

    /**
     * 从元数据中获取日志时间戳以确定是否需要同步。
     * 使用 HMGET 在单次 Redis 调用中获取两个时间戳。
     */
    private Mono<LogTimestamps> getLogTimestamps(String metaKey) {
        // 使用 StringCodec，因为 Python 存储纯文本值
        RMapReactive<String, String> metaMap = redisClient.getMap(metaKey, StringCodec.INSTANCE);

        // 使用 getAll (HMGET) 在单次 Redis 调用中获取两个时间戳
        return metaMap.getAll(Set.of(META_LAST_APPEND_TS, META_LAST_FLUSH_TS))
                .map(values -> {
                    long lastAppend = parseLong(values.getOrDefault(META_LAST_APPEND_TS, "0"));
                    long lastFlush = parseLong(values.getOrDefault(META_LAST_FLUSH_TS, "0"));
                    return new LogTimestamps(lastAppend, lastFlush);
                })
                .defaultIfEmpty(new LogTimestamps(0, 0));
    }

    /**
     * 执行实际同步：从 Redis 读取日志，上传到 S3，更新元数据。
     *
     * @param isFinalize 如果为 true，同步后缩短 Redis 键的 TTL（用于终结）
     */
    private Mono<Void> doSyncToS3(String workspaceId, UUID optimizationId,
            String logKey, String metaKey, boolean isFinalize) {

        // 为日志列表和元数据映射使用 StringCodec，因为 Python 存储纯文本值
        RListReactive<String> logList = redisClient.getList(logKey, StringCodec.INSTANCE);
        RMapReactive<String, String> metaMap = redisClient.getMap(metaKey, StringCodec.INSTANCE);

        return logList.readAll()
                .flatMap(logs -> {
                    if (logs == null || logs.isEmpty()) {
                        log.debug("在 Redis 中未找到优化 '{}' 的日志", optimizationId);
                        return Mono.empty();
                    }

                    // 用换行符连接所有日志行
                    String logContent = String.join("\n", logs);
                    String s3Key = OptimizationLogSyncService.formatS3Key(workspaceId, optimizationId);

                    // 使用 gzip 压缩日志
                    byte[] compressedLogs = compressGzip(logContent);
                    log.info("正在将优化 '{}' 的 '{}' 行日志（{} 字节 -> {} 字节 gzip 后）上传到 S3",
                            logs.size(), logContent.length(), compressedLogs.length, optimizationId);

                    // 上传到 S3（阻塞调用被包装到调度器中）
                    Mono<Void> uploadAndUpdate = Mono.fromCallable(() -> {
                        fileService.upload(s3Key, compressedLogs, CONTENT_TYPE_GZIP);
                        return true;
                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(updateLastFlushTimestamp(metaMap, optimizationId));

                    // 如果正在终结，还要缩短 Redis 键的 TTL
                    if (isFinalize) {
                        return uploadAndUpdate.then(reduceRedisTTL(logKey, metaKey, optimizationId));
                    }
                    return uploadAndUpdate;
                });
    }

    /**
     * 在 S3 上传成功后更新元数据中的 last_flush_ts。
     */
    private Mono<Void> updateLastFlushTimestamp(RMapReactive<String, String> metaMap, UUID optimizationId) {
        String now = String.valueOf(System.currentTimeMillis());
        return metaMap.put(META_LAST_FLUSH_TS, now)
                .doOnSuccess(__ -> log.debug("已更新优化 '{}' 的 last_flush_ts", optimizationId))
                .then();
    }

    /**
     * 使用分布式锁执行操作。
     */
    private Mono<Void> executeWithLock(String workspaceId, UUID optimizationId, Mono<Void> action) {
        String lockKey = formatLockKey(workspaceId, optimizationId);
        Duration lockDuration = config.getLockTimeout();

        return lockService.lockUsingToken(new LockService.Lock(lockKey), lockDuration)
                .flatMap(acquired -> {
                    if (!acquired) {
                        log.debug("无法获取优化 '{}' 的锁，另一个实例正在同步",
                                optimizationId);
                        return Mono.empty();
                    }

                    return action
                            .doFinally(__ -> lockService.unlockUsingToken(new LockService.Lock(lockKey)).subscribe());
                });
    }

    private static String formatLogKey(String workspaceId, UUID optimizationId) {
        return String.format(REDIS_LOG_KEY_PATTERN, workspaceId, optimizationId);
    }

    private static String formatMetaKey(String workspaceId, UUID optimizationId) {
        return String.format(REDIS_META_KEY_PATTERN, workspaceId, optimizationId);
    }

    private static String formatLockKey(String workspaceId, UUID optimizationId) {
        return String.format(LOCK_KEY_PATTERN, workspaceId, optimizationId);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static byte[] compressGzip(String content) {
        try (var baos = new ByteArrayOutputStream();
                var gzip = new GZIPOutputStream(baos)) {
            gzip.write(content.getBytes(StandardCharsets.UTF_8));
            gzip.finish();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to compress logs with gzip", e);
        }
    }
}
