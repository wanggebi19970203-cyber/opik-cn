package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.concurrent.TimeUnit;

/**
 * 停滞的 Optimization Studio 运行回收器（OPIK-7159）的配置。
 * <p>
 * Studio 运行的状态只会由 Python 优化器工作进程回调 API 来推进
 * （mark_running / mark_completed / mark_error）。如果工作进程从未运行（工作进程宕机、Redis/队列
 * 不可达、任务丢失）或在能上报之前崩溃，那么没有任何东西会把运行从后端在创建时设置的
 * {@code INITIALIZED} 值转移走，它就会永远空转而不会暴露任何错误。
 * 这个回收器是与环境无关的安全网：它会定期找出已经在一个非终止状态上停滞超过阈值一段时间的
 * studio 运行，并把它们转移到 {@code ERROR} 并附带原因，从而让运行永远不会无限期地停滞。
 *
 * @param enabled 是否调度回收器任务。
 * @param startupDelay 启动后第一次回收器运行之前的延迟，用于在
 *        工作进程仍在预热并消化积压时避免误报。
 * @param jobInterval 回收器运行的频率。每个周期只有一个实例运行（带持有到过期的
 *        分布式锁），因此查询成本可以忽略不计。
 * @param initializedTimeout 一个停留在 {@code INITIALIZED} 且超过该时长没有任何 <em>活动</em>
 *        的运行会被转移到 {@code ERROR}。工作进程应当在接手任务后的几秒内调用 mark_running，
 *        所以这个值可以很短 —— 但它被保持在明显高于正常队列延迟的水平，以避免杀掉一个只是
 *        在积压后面等待的运行。注意这只约束无状态变更的那一侧：一个从未离开 {@code INITIALIZED}
 *        但在写入 trial 或 item 的运行，会被 {@code runningTimeout} 所管辖的同一个进度否决所豁免，
 *        该否决是在 {@code runningTimeout} 窗口上度量的（OPIK-7459）—— 一次失败的 mark_running
 *        回调绝不能杀掉一个实际上正在评估的运行。
 * @param runningTimeout 一个非终止运行如果超过该时长没有表现出任何 <em>活动</em>，
 *        就会被转移到 {@code ERROR}。活动是从运行进度推导出的活性（OPIK-7459）：取
 *        该行的 {@code last_updated_at}、最新 trial 实验的 {@code created_at}、
 *        以及最新实验 item 的 {@code created_at} 中最新者 —— 一个健康的运行会持续创建 trial
 *        实验，并在一个 trial 内为每个被评估的数据集 item 创建一个实验 item，所以这个值不再
 *        需要超过工作进程的最大执行超时（{@code OPTSTUDIO_EXECUTION_TIMEOUT}，默认 6h），
 *        可以小于一小时。它也是 {@code INITIALIZED} 候选者的活动窗口，而不仅仅是 {@code RUNNING} 的。
 *        它必须高于的是进度信号之间最长的合法间隔，而这个间隔不是稳态的 item 节奏，而是运行的
 *        <em>起步阶段</em>：在 mark_running 和第一个 trial 实验之间，工作进程会获取并采样数据集
 *        （最多 {@code OPTSTUDIO_DATASET_SAMPLES} 条），并且对于 GEPA 还要构建基线，不写入任何东西。
 *        如果把它设得低于这个阶段，一个慢但还活着的运行会在产生任何 trial 之前就被回收；
 *        {@code @MinDuration} 下限守护了这一病态端点（与
 *        {@link #isLockDurationBelowJobInterval()} 相同的快速失败意图）。
 * @param runningHardTimeout 非终止运行（{@code INITIALIZED} 或
 *        {@code RUNNING}）的绝对上限，从运行被创建时开始度量，
 *        即使在 trial/item 写入仍在到达时也会被回收。这保留了 OPIK-7459 之前
 *        “运行永远不会无限期停滞”的保证，以应对一个不断产生行却从不上报终止状态的僵尸工作进程
 *        —— 包括那些运行从未离开 {@code INITIALIZED} 的情况，这就是为什么该上限也覆盖这个状态。
 *        必须超过工作进程的最大执行超时（{@code OPTSTUDIO_EXECUTION_TIMEOUT}，默认 6h）再加上一个缓冲
 *        —— {@code @MinDuration} 下限被固定到这个 6h 默认值 —— 并且绝不能低于
 *        {@link #runningTimeout()}（由 {@link #isRunningHardTimeoutAtLeastRunningTimeout()} 强制）。
 *        从创建时间而不是从 {@code last_updated_at} 度量是刻意的：每次对该行的写入都会刷新该列，
 *        因此一次元数据 PATCH 或一次 SDK 重新 upsert 都会无限期地推迟这个兜底。
 * @param lookbackMargin 加到 {@code max(initializedTimeout, runningTimeout, runningHardTimeout)} 上，
 *        用来确定回收器扫描的 {@code last_updated_at >= now - window} 下限。它纯粹是
 *        回收器停机保险：一个恰好在回收器不可用之前停滞的运行，只要回收器停机时间少于这个
 *        余量，就会在回收器恢复后仍然被捕获。它不需要很大就能找到新的停滞（那些总是在超时
 *        之内），所以只要把它设得和预期的最长回收器中断一样大即可 —— 更短的余量也会收紧
 *        skip-index 粒度的裁剪。把 {@code runningHardTimeout} 折叠进最大值正是让下限安全的原因：
 *        由于 {@code lookback >= runningHardTimeout}，任何尚未超过上限的运行都比下限更新，
 *        所以它永远不会被扫描漏掉。
 * @param lockDuration 锁的 TTL，持有到过期，在它过期之前抑制其他实例进行调和。
 *        必须保持在 {@link #jobInterval()} 之下（锁持有到过期，所以一个
 *        lockDuration &gt;= jobInterval 会让每隔一个被调度的 tick 变成空操作，并悄悄把
 *        有效节奏减半）。把运行标记为 {@code ERROR} 是幂等的，所以跨实例的偶尔重叠无害。
 * @param batchSize 每个周期调和的最大停滞运行数，因此大量积压会被分摊到
 *        多个周期排空，而不是一次性突发处理。
 * @param candidateScanFactor {@link #batchSize()} 的倍数，界定回收器查询的两个活性探针
 *        所扇出的候选集。如果没有这个界限，该集合就是所有非终止且其行在
 *        {@code runningTimeout} 内没有变化的 studio 运行 —— 而由于 {@code last_updated_at} 只会在
 *        状态变化时推进，这会包括每个超过超时时长的 <em>健康</em> 在途运行，因此探针
 *        成本会随舰队规模而非配置规模增长。之所以是倍数而不是
 *        {@code batchSize} 本身，是因为排序把最陈旧的放在最前面，而一个健康的长运行会
 *        和一个死运行排在一起（这正是整个进度否决设计的前提）：在恰好为 {@code batchSize} 时，
 *        活运行可能把死运行挤出每一轮。调高它会以成比例更高的探针成本扩大查询覆盖范围；
 *        把它调向 1 则会重新引入这种饿死风险。
 */
@Builder(toBuilder = true)
public record OptimizationStalledReaperConfig(
        boolean enabled,
        @NotNull @MinDuration(value = 0, unit = TimeUnit.SECONDS) @MaxDuration(value = 30, unit = TimeUnit.MINUTES) Duration startupDelay,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 6, unit = TimeUnit.HOURS) Duration jobInterval,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 24, unit = TimeUnit.HOURS) Duration initializedTimeout,
        @NotNull @MinDuration(value = 5, unit = TimeUnit.MINUTES) @MaxDuration(value = 7, unit = TimeUnit.DAYS) Duration runningTimeout,
        @NotNull @MinDuration(value = 6, unit = TimeUnit.HOURS) @MaxDuration(value = 30, unit = TimeUnit.DAYS) Duration runningHardTimeout,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.HOURS) @MaxDuration(value = 30, unit = TimeUnit.DAYS) Duration lookbackMargin,
        @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES) @MaxDuration(value = 1, unit = TimeUnit.HOURS) Duration lockDuration,
        @Min(1) @Max(10_000) int batchSize,
        @Min(1) @Max(1_000) int candidateScanFactor) {

    /**
     * 在启动时就强制 {@link #lockDuration()} &lt; {@link #jobInterval()} 这一不变量，而不仅仅是
     * 用文档说明它：锁持有到过期，所以 lockDuration &gt;= jobInterval 会让每隔一个被调度的
     * tick 变成空操作，并悄悄把有效节奏减半。
     */
    @JsonIgnore
    @AssertTrue(message = "optimizationStalledReaper.lockDuration must be less than jobInterval") public boolean isLockDurationBelowJobInterval() {
        return lockDuration == null || jobInterval == null
                || lockDuration.toMilliseconds() < jobInterval.toMilliseconds();
    }

    /**
     * 硬上限绝不能低于进度超时 —— 否则这个忽略进度信号的上限会在基于进度的检查
     * 有机会发言之前就回收掉健康的运行。
     */
    @JsonIgnore
    @AssertTrue(message = "optimizationStalledReaper.runningHardTimeout must not be less than runningTimeout") public boolean isRunningHardTimeoutAtLeastRunningTimeout() {
        return runningHardTimeout == null || runningTimeout == null
                || runningHardTimeout.toMilliseconds() >= runningTimeout.toMilliseconds();
    }
}
