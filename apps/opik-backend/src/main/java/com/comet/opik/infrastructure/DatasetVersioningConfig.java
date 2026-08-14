package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.concurrent.TimeUnit;

/**
 * 数据集版本化写入路径的运行时配置。
 *
 * <p>参见 OPIK-6674：当 SELECT 落在某个尚未可见源版本分区的 ClickHouse 副本上时，
 * {@code dataset_item_versions} 表上的 {@code COPY_VERSION_ITEMS} 和 {@code EDIT_ITEM_VIA_SELECT_INSERT}
 * INSERT...SELECT 查询可能返回零行。重试守卫会在写入零行时带退避重新执行该操作，并在尝试耗尽后以
 * 类型化异常失败，而不是静默提交被截断的状态。
 *
 * <p>{@code lockLease} 是每个数据集的分布式锁租约，用于串行化 读取最新 &rarr; 创建版本 &rarr; 翻转最新
 * 的序列，使并行上传无法在可变的 'latest' 指针上产生竞态（OPIK-7264）。
 *
 * <p>默认值位于 {@code config.yml} / {@code config-test.yml}，不在这里。
 */
@Builder(toBuilder = true)
public record DatasetVersioningConfig(
        @Valid @NotNull ZeroRowsRetry zeroRowsRetry,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS) //
        @MaxDuration(value = 10, unit = TimeUnit.MINUTES) Duration lockLease) {

    @Builder(toBuilder = true)
    public record ZeroRowsRetry(
            @Min(0) @Max(10) int maxAttempts,
            @NotNull @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS) //
            @MaxDuration(value = 1, unit = TimeUnit.MINUTES) Duration minBackoff,
            @NotNull @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS) //
            @MaxDuration(value = 1, unit = TimeUnit.MINUTES) Duration maxBackoff) {
    }
}
