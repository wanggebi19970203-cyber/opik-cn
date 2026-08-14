package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.OptimizationService;
import com.comet.opik.infrastructure.OptimizationStalledReaperConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.jobs.Job;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.InterruptableJob;
import org.quartz.JobExecutionContext;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

/**
 * 定期将停滞的 Optimization Studio 运行转为 {@code ERROR}（OPIK-7159）。
 * <p>
 * Studio 运行的状态只会由 Python 优化器 worker 回调 API 来推进。
 * 如果 worker 从未运行（worker 宕机、Redis/队列不可达、作业丢失）或在能够上报之前崩溃，
 * 运行将永远冻结在 {@code INITIALIZED}（或 {@code RUNNING}）状态，且不会暴露任何错误。
 * 此回收器是与环境无关的安全网，保证运行永远不会无限期卡住。
 * 它根据两个独立条件之一选择一个非终结态的运行，并要求
 * {@link OptimizationService#reconcileStalledStudioOptimizations} 以明确的原因将其标记为失败：
 * <ul>
 * <li><b>无活跃度</b>——行的 {@code last_updated_at}、最新试验实验的
 * {@code created_at} 与最新实验条目的 {@code created_at}（OPIK-7459）三者中最新的一个，
 * 早于该运行所卡住的对应状态的 {@code initializedTimeout} / {@code runningTimeout}。</li>
 * <li><b>超过硬上限</b>——运行开始于 {@code runningHardTimeout} 之前，<em>即使</em>
 * 试验和条目的写入仍在到达也会被回收。仅靠活跃度规则会削弱上面断言的
 * “永不会无限期卡住”保证，因为一个持续写入行却从不报告终结状态的僵尸 worker
 * 会永远满足该规则；这个上限正是用来恢复该保证的。</li>
 * </ul>
 * 这两个条件在任何地方都按此顺序检查，且上限优先：{@code OptimizationService} 对
 * 达到硬上限的运行短路其更新前的活动否决，并将上限作为原因上报。
 * <p>
 * 一个持有到过期的分布式锁确保每个周期只有一个实例进行协调。
 */
@Slf4j
@Singleton
@DisallowConcurrentExecution
public class OptimizationStalledReaperJob extends Job implements InterruptableJob {

    private static final Lock JOB_LOCK = new Lock("optimization_stalled_reaper:lock");

    private final OptimizationService optimizationService;
    private final LockService lockService;
    private final OptimizationStalledReaperConfig config;

    private final AtomicBoolean interrupted = new AtomicBoolean(false);

    /** 跟踪进行中的响应式过程，以便 {@link #interrupt()} 可以将其释放。 */
    private final AtomicReference<Disposable> currentExecution = new AtomicReference<>();

    // 显式 @Inject 构造函数（而非 Lombok 的 onConstructor_）：同类的回收器作业
    // （StreamConsumerReaperJob、LocalRunnerReaperJob）将 @Config 限定符声明在
    // 显式构造函数参数上，因为 Quartz/Guice 对 Lombok 传播的 @Config 限定符的实例化
    // 在这里一直很脆弱。请与它们保持一致（审查者：thiagohora）。
    @Inject
    public OptimizationStalledReaperJob(@NonNull OptimizationService optimizationService,
            @NonNull LockService lockService,
            @NonNull @Config("optimizationStalledReaper") OptimizationStalledReaperConfig config) {
        this.optimizationService = optimizationService;
        this.lockService = lockService;
        this.config = config;
    }

    @Override
    public void doJob(JobExecutionContext context) {
        if (interrupted.get()) {
            log.info("优化停滞回收器作业在执行前被中断，跳过");
            return;
        }

        var subscription = lockService.bestEffortLock(
                JOB_LOCK,
                Mono.defer(() -> {
                    if (interrupted.get()) {
                        log.info("优化停滞回收器在处理前被中断，跳过");
                        return Mono.empty();
                    }
                    return optimizationService.reconcileStalledStudioOptimizations(
                            config.initializedTimeout().toJavaDuration(),
                            config.runningTimeout().toJavaDuration(),
                            config.runningHardTimeout().toJavaDuration(),
                            config.lookbackMargin().toJavaDuration(),
                            config.batchSize(),
                            config.candidateScanFactor())
                            .doOnSuccess(count -> {
                                if (count > 0) {
                                    log.warn("优化停滞回收器已将 '{}' 个停滞的 Studio 运行标记为 ERROR",
                                            count);
                                } else {
                                    log.debug("优化停滞回收器未发现停滞的 Studio 运行");
                                }
                            });
                }),
                Mono.fromRunnable(() -> log.debug(
                        "无法获取优化停滞回收器的锁，另一个实例正在运行")),
                config.lockDuration().toJavaDuration(),
                Duration.ZERO,
                true) // holdUntilExpiry：防止在下一个周期前跨实例重复运行
                // 出错后继续，使周期性作业保持存活。
                .onErrorResume(throwable -> {
                    if (interrupted.get()) {
                        log.warn("优化停滞回收器被中断", throwable);
                    } else {
                        log.error("优化停滞回收器失败", throwable);
                    }
                    return Mono.empty();
                })
                .doFinally(signal -> currentExecution.set(null))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        currentExecution.set(subscription);
    }

    @Override
    public void interrupt() {
        interrupted.set(true);
        log.info("优化停滞回收器作业被中断");
        var execution = currentExecution.get();
        if (execution != null && !execution.isDisposed()) {
            execution.dispose();
            log.info("优化停滞回收器作业已成功中断");
        }
    }
}
