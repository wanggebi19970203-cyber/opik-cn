package com.comet.opik.infrastructure.bi;

import com.comet.opik.api.resources.v1.jobs.AgentInsightsReportJob;
import com.comet.opik.api.resources.v1.jobs.ClickHousePartitionMetricsJob;
import com.comet.opik.api.resources.v1.jobs.DatasetVersionItemsTotalMigrationJob;
import com.comet.opik.api.resources.v1.jobs.ExperimentDenormalizationJob;
import com.comet.opik.api.resources.v1.jobs.LocalRunnerReaperJob;
import com.comet.opik.api.resources.v1.jobs.MetricsAlertJob;
import com.comet.opik.api.resources.v1.jobs.OptimizationStalledReaperJob;
import com.comet.opik.api.resources.v1.jobs.ProjectLastUpdatedFlushJob;
import com.comet.opik.api.resources.v1.jobs.RetentionCatchUpJob;
import com.comet.opik.api.resources.v1.jobs.RetentionEstimationJob;
import com.comet.opik.api.resources.v1.jobs.RetentionSlidingWindowJob;
import com.comet.opik.api.resources.v1.jobs.StreamConsumerReaperJob;
import com.comet.opik.api.resources.v1.jobs.TraceThreadsClosingJob;
import com.comet.opik.infrastructure.ExperimentDenormalizationConfig;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.LocalRunnerConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.OptimizationStalledReaperConfig;
import com.comet.opik.infrastructure.PartitionMetricsConfig;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.RetentionConfig;
import com.comet.opik.infrastructure.StreamConsumerReaperConfig;
import com.comet.opik.infrastructure.TraceThreadConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryRefreshJob;
import com.google.inject.Injector;
import io.dropwizard.jobs.GuiceJobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.impl.matchers.GroupMatcher;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycleListener;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.GuiceyLifecycleEvent;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.InjectorPhaseEvent;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@RequiredArgsConstructor
public class OpikGuiceyLifecycleEventListener implements GuiceyLifecycleListener {

    // 此事件不能依赖认证
    private final AtomicReference<Injector> injector = new AtomicReference<>();

    private final AtomicReference<GuiceJobManager> guiceJobManager = new AtomicReference<>();

    @Override
    public void onEvent(GuiceyLifecycleEvent event) {

        switch (event.getType()) {
            case GuiceyLifecycle.ApplicationRun -> installJobScheduler(event);
            case GuiceyLifecycle.ApplicationStarted -> {
                reportInstallationsIfNeeded();
                setupDailyJob();
                setTraceThreadsClosingJob();
                setMetricsAlertJob();
                setAgentInsightsReportJob();
                setExperimentDenormalizationJob();
                setProjectLastUpdatedFlushJob();
                setLocalRunnerReaperJob();
                setStreamConsumerReaperJob();
                setOptimizationStalledReaperJob();
                setRetentionJobs();
                setPartitionMetricsJob();
                setLlmModelRegistryRefreshJob();
                scheduleDatasetVersionItemsTotalMigrationJobIfEnabled();
            }

            case GuiceyLifecycle.ApplicationShutdown -> shutdownJobManagerScheduler();
        }
    }

    private void reportInstallationsIfNeeded() {
        var installationReportService = injector.get().getInstance(InstallationReportService.class);

        installationReportService.reportInstallation();
    }

    private void installJobScheduler(GuiceyLifecycleEvent event) {
        if (event instanceof InjectorPhaseEvent injectorEvent) {
            injector.set(injectorEvent.getInjector());

            log.info("正在安装作业...");
            guiceJobManager.set(injector.get().getInstance(GuiceJobManager.class));
            log.info("作业已安装。");
        }
    }

    private void setupDailyJob() {

        var usageReportConfig = injector.get().getInstance(OpikConfiguration.class).getUsageReport();

        if (!usageReportConfig.isEnabled()) {
            disableJob();
        } else {
            runReportIfNeeded();
        }
    }

    private void setTraceThreadsClosingJob() {
        TraceThreadConfig traceThreadConfig = injector.get().getInstance(OpikConfiguration.class)
                .getTraceThreadConfig();

        if (!traceThreadConfig.isEnabled()) {
            log.info("Trace 线程关闭作业已禁用，跳过作业设置");
            return;
        }

        scheduleRepeatingJob(TraceThreadsClosingJob.class,
                traceThreadConfig.getCloseTraceThreadJobInterval().toJavaDuration(), null);
    }

    private void setExperimentDenormalizationJob() {
        ExperimentDenormalizationConfig denormConfig = injector.get().getInstance(OpikConfiguration.class)
                .getExperimentDenormalization();

        if (!denormConfig.isEnabled()) {
            log.info("实验反规范化作业已禁用，跳过作业设置");
            return;
        }

        scheduleRepeatingJob(ExperimentDenormalizationJob.class,
                denormConfig.getJobInterval().toJavaDuration(), null);
    }

    private void setMetricsAlertJob() {
        var webhookConfig = injector.get().getInstance(OpikConfiguration.class).getWebhook();

        if (webhookConfig == null || webhookConfig.getMetrics() == null) {
            log.warn("未找到 Webhook 指标配置，跳过指标告警作业设置");
            return;
        }

        scheduleRepeatingJob(MetricsAlertJob.class,
                webhookConfig.getMetrics().getFixedDelay().toJavaDuration(),
                webhookConfig.getMetrics().getInitialDelay().toJavaDuration());
    }

    private void setProjectLastUpdatedFlushJob() {
        ProjectLastUpdatedFlushConfig flushConfig = injector.get().getInstance(OpikConfiguration.class)
                .getProjectLastUpdatedFlush();

        if (!flushConfig.isEnabled() || !flushConfig.isJobEnabled()) {
            log.info("项目最近更新刷新作业已禁用，跳过作业设置");
            return;
        }

        scheduleRepeatingJob(ProjectLastUpdatedFlushJob.class,
                flushConfig.getJobInterval().toJavaDuration(), null);
    }

    private void setLocalRunnerReaperJob() {
        LocalRunnerConfig localRunnerConfig = injector.get().getInstance(OpikConfiguration.class).getLocalRunner();

        scheduleRepeatingJob(LocalRunnerReaperJob.class,
                localRunnerConfig.getReaperJobInterval().toJavaDuration(), null);
    }

    private void setStreamConsumerReaperJob() {
        StreamConsumerReaperConfig reaperConfig = injector.get().getInstance(OpikConfiguration.class)
                .getStreamConsumerReaper();

        if (!reaperConfig.enabled()) {
            log.info("流消费者回收器作业已禁用，跳过作业设置");
            return;
        }

        scheduleRepeatingJob(StreamConsumerReaperJob.class,
                reaperConfig.jobInterval().toJavaDuration(),
                reaperConfig.startupDelay().toJavaDuration());
    }

    private void setOptimizationStalledReaperJob() {
        OptimizationStalledReaperConfig reaperConfig = injector.get().getInstance(OpikConfiguration.class)
                .getOptimizationStalledReaper();

        if (!reaperConfig.enabled()) {
            log.info("Optimization 停滞回收器作业已禁用，跳过作业设置");
            return;
        }

        scheduleRepeatingJob(OptimizationStalledReaperJob.class,
                reaperConfig.jobInterval().toJavaDuration(),
                reaperConfig.startupDelay().toJavaDuration());
    }

    private void setRetentionJobs() {
        RetentionConfig retentionConfig = injector.get().getInstance(OpikConfiguration.class).getRetention();

        if (!retentionConfig.isEnabled()) {
            log.info("保留作业已禁用，跳过作业设置");
            return;
        }

        scheduleRepeatingJob(RetentionSlidingWindowJob.class, retentionConfig.getInterval(), null);

        if (retentionConfig.getCatchUp().isEnabled()) {
            scheduleRepeatingJob(RetentionEstimationJob.class,
                    Duration.ofMinutes(retentionConfig.getCatchUp().getEstimationIntervalMinutes()), null);
            scheduleRepeatingJob(RetentionCatchUpJob.class,
                    retentionConfig.getCatchUp().getCatchUpInterval(), null);
        } else {
            log.info("保留追补作业已禁用，跳过估算和追补作业设置");
        }
    }

    private void setPartitionMetricsJob() {
        PartitionMetricsConfig partitionMetricsConfig = injector.get().getInstance(OpikConfiguration.class)
                .getPartitionMetrics();

        if (!partitionMetricsConfig.isEnabled()) {
            log.info("ClickHouse 分区指标作业已禁用，跳过作业设置");
            return;
        }

        scheduleRepeatingJob(ClickHousePartitionMetricsJob.class,
                partitionMetricsConfig.getInterval().toJavaDuration(), null);
    }

    private void setAgentInsightsReportJob() {
        var serviceToggles = injector.get().getInstance(OpikConfiguration.class).getServiceToggles();

        if (!serviceToggles.isOllieEnabled()) {
            log.info("Agent Insights 已禁用，跳过报告作业设置");
            return;
        }

        var reportConfig = injector.get().getInstance(OpikConfiguration.class).getAgentInsightsReport();
        scheduleCronJob(AgentInsightsReportJob.class, reportConfig.getSchedule());
    }

    private void scheduleCronJob(Class<? extends org.quartz.Job> jobClass, String cronExpression) {
        var jobDetail = JobBuilder.newJob(jobClass)
                .storeDurably()
                .build();

        var trigger = TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                        .inTimeZone(java.util.TimeZone.getTimeZone(java.time.ZoneOffset.UTC)))
                .build();

        try {
            var scheduler = getScheduler();
            scheduler.addJob(jobDetail, false);
            scheduler.scheduleJob(trigger);
            log.info("'{}' 已使用 cron '{}' 成功调度", jobClass.getSimpleName(), cronExpression);
        } catch (SchedulerException e) {
            log.error("调度 '{}' 失败", jobClass.getSimpleName(), e);
        }
    }

    private void scheduleRepeatingJob(Class<? extends org.quartz.Job> jobClass, Duration interval,
            Duration initialDelay) {
        var jobDetail = JobBuilder.newJob(jobClass)
                .storeDurably()
                .build();

        var triggerBuilder = TriggerBuilder.newTrigger()
                .forJob(jobDetail)
                .withSchedule(
                        org.quartz.SimpleScheduleBuilder.simpleSchedule()
                                .withIntervalInMilliseconds(interval.toMillis())
                                .repeatForever());

        if (initialDelay != null && !initialDelay.isZero()) {
            triggerBuilder.startAt(java.util.Date.from(java.time.Instant.now().plus(initialDelay)));
        } else {
            triggerBuilder.startNow();
        }

        try {
            var scheduler = getScheduler();
            scheduler.addJob(jobDetail, false);
            scheduler.scheduleJob(triggerBuilder.build());
            log.info("'{}' 已使用间隔 '{}' 成功调度", jobClass.getSimpleName(), interval);
        } catch (SchedulerException e) {
            log.error("调度 '{}' 失败", jobClass.getSimpleName(), e);
        }
    }

    private void runReportIfNeeded() {
        JobKey key = JobKey.jobKey(DailyUsageReportJob.class.getName());

        try {
            var scheduler = getScheduler();
            var trigger = TriggerBuilder.newTrigger().startNow().forJob(key).build();
            scheduler.scheduleJob(trigger);
            log.info("每日用量报告已启用，在启动期间运行作业。");
        } catch (SchedulerException e) {
            log.error("调度作业 '{}' 失败", key, e);
        }
    }

    private void disableJob() {
        log.info("每日用量报告已禁用，正在注销作业。");

        var scheduler = getScheduler();

        var jobKey = new JobKey(DailyUsageReportJob.class.getName());

        try {
            if (scheduler.checkExists(jobKey)) {
                var deleted = scheduler.deleteJob(jobKey);
                log.info("作业 '{}' 已注销。已删除: {}", jobKey, deleted);
            } else {
                log.info("作业 '{}' 未找到。", jobKey);
            }
        } catch (SchedulerException e) {
            log.error("注销作业 '{}' 失败", jobKey, e);
        }
    }

    private Scheduler getScheduler() {
        return guiceJobManager.get().getScheduler();
    }

    private void shutdownJobManagerScheduler() {
        var jobManager = guiceJobManager.get();
        if (jobManager == null) {
            log.info("GuiceJobManager 实例已清空，无需关闭");
            return;
        }
        var scheduler = jobManager.getScheduler();
        try {
            log.info("正在尝试从调度器中删除所有作业...");
            scheduler.deleteJobs(scheduler.getJobKeys(GroupMatcher.anyGroup()).stream().toList());
            log.info("作业已删除");
        } catch (SchedulerException exception) {
            log.warn("调度器关闭期间删除作业出错", exception);
        }
        try {
            log.info("正在尝试关闭调度器...");
            scheduler.shutdown(false); // 不等待作业完成
            log.info("调度器关闭完成");
        } catch (SchedulerException exception) {
            log.warn("关闭调度器出错", exception);
        }
        guiceJobManager.set(null);
        log.info("已清空 GuiceJobManager 实例");
    }

    private void setLlmModelRegistryRefreshJob() {
        LlmModelRegistryConfig registryConfig = injector.get().getInstance(OpikConfiguration.class)
                .getLlmModelRegistry();

        if (!registryConfig.isRemoteEnabled()) {
            log.info("LLM 模型注册表远程刷新已禁用，跳过作业设置");
            return;
        }

        scheduleRepeatingJob(LlmModelRegistryRefreshJob.class,
                Duration.ofSeconds(registryConfig.getRefreshIntervalSeconds()), null);
    }

    /**
     * 如果启用，则调度数据集版本 items_total 迁移作业。
     * <p>
     * 这是一个在应用启动后以可配置延迟运行的一次性迁移作业。
     * 该作业会计算并更新由 Liquibase 迁移创建的数据集版本的 items_total 字段。
     * 成功完成后，通过在配置中设置
     * {@code datasetVersioningMigration.itemsTotalEnabled: false} 来禁用该作业。
     */
    private void scheduleDatasetVersionItemsTotalMigrationJobIfEnabled() {
        var config = injector.get().getInstance(OpikConfiguration.class).getDatasetVersioningMigration();

        if (config == null || !config.isItemsTotalEnabled()) {
            log.info("数据集版本 items_total 迁移作业已禁用");
            return;
        }

        try {
            Duration startupDelay = Duration.ofSeconds(config.getItemsTotalStartupDelaySeconds());

            var jobDetail = JobBuilder.newJob(DatasetVersionItemsTotalMigrationJob.class)
                    .storeDurably()
                    .build();

            // 调度作业在启动延迟后运行一次
            var trigger = TriggerBuilder.newTrigger()
                    .forJob(jobDetail)
                    .startAt(java.util.Date.from(java.time.Instant.now().plus(startupDelay)))
                    .build();

            var scheduler = getScheduler();
            scheduler.addJob(jobDetail, false);
            scheduler.scheduleJob(trigger);

            log.info("数据集版本 items_total 迁移作业已使用启动延迟 '{}' 成功调度",
                    startupDelay);
            log.info("该作业将运行一次。成功完成后，通过设置 " +
                    "datasetVersioningMigration.itemsTotalEnabled: false 来禁用它");
        } catch (SchedulerException e) {
            log.error("调度数据集版本 items_total 迁移作业失败", e);
        } catch (Exception e) {
            log.error("设置数据集版本 items_total 迁移作业时发生意外错误", e);
        }
    }

}
