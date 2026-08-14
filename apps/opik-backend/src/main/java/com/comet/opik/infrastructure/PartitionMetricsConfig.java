package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Data
public class PartitionMetricsConfig {

    @JsonProperty
    private boolean enabled;

    /**
     * 分区健康轮询的运行频率。轮询会读取 {@code system.parts} 元数据，并为每个 LWD 表
     * 执行一次轻量的 {@code _row_exists} 掩码扫描；在大型集群上，一个完整周期的成本是
     * 几秒钟加上几 GiB 高度可压缩的列读取，因此 5 分钟是一个安全的默认值。
     * 分布式锁确保每个间隔只有一个实例进行轮询。
     */
    @NotNull @JsonProperty
    @MinDuration(value = 30, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration interval;

    /**
     * 逗号分隔的表，用于通过 {@code SELECT count() WHERE _row_exists = 0 SETTINGS apply_deleted_mask = 0}
     * 扫描轻量删除（LWD 掩码）行。限制在高流量的追加/删除表上，以避免每个周期都扫描小型配置表。
     *
     * <p>之所以存成标量而不是 YAML 列表，是为了让它能从逗号分隔的环境变量覆盖中干净地绑定
     * （Dropwizard 会在解析前把 {@code ${...}} 替换进原始 YAML，因此逗号分隔的环境变量值无法
     * 绑定到 {@code List}）。{@link #getLwdTables()} 会拆分、去除空白并丢弃空项；各个名称会在
     * 插值处被校验为纯标识符。
     */
    @NotNull @JsonProperty
    private String lwdTables;

    /** 派生值：解析、去除空白、无空项后的 LWD 表列表。 */
    public List<String> getLwdTables() {
        return Arrays.stream(lwdTables.split(","))
                .map(String::strip)
                .filter(table -> !table.isEmpty())
                .toList();
    }
}
