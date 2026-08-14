package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.AgentInsightsJobService;
import com.comet.opik.domain.AgentInsightsMetrics;
import com.comet.opik.domain.AgentInsightsReportClient;
import com.comet.opik.domain.AgentInsightsReportMessage;
import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * 消费排队的 Agent Insights 报告触发器（由 {@code AgentInsightsReportPublisher} 发布），并
 * 通过 {@link AgentInsightsReportClient} 执行实际的报告调用。Redis 消费组会限制
 * 并发运行次数，因此手动 {@code /trigger} 端点和每日定时扫描都不会阻塞在该调用上。
 */
@Slf4j
@EagerSingleton
public class AgentInsightsReportSubscriber extends BaseRedisSubscriber<AgentInsightsReportMessage> {

    private static final String METRICS_NAMESPACE = "opik";
    private static final String METRICS_BASE_NAME = "agent_insights_report";

    private final AgentInsightsReportConfig config;
    private final ServiceTogglesConfig serviceToggles;
    private final AgentInsightsReportClient reportClient;
    private final AgentInsightsJobService jobService;

    @Inject
    public AgentInsightsReportSubscriber(
            @NonNull @Config("agentInsightsReport") AgentInsightsReportConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull RedissonReactiveClient redisson,
            @NonNull AgentInsightsReportClient reportClient,
            @NonNull AgentInsightsJobService jobService) {
        super(config, redisson, AgentInsightsReportConfig.PAYLOAD_FIELD, METRICS_NAMESPACE, METRICS_BASE_NAME);
        this.config = config;
        this.serviceToggles = serviceToggles;
        this.reportClient = reportClient;
        this.jobService = jobService;
    }

    @Override
    public void start() {
        if (isDisabled()) {
            return;
        }
        log.info("正在启动 Agent Insights 报告订阅者，配置：streamName='{}', consumerGroupName='{}', "
                + "batchSize='{}'", config.getStreamName(), config.getConsumerGroupName(),
                config.getConsumerBatchSize());
        super.start();
    }

    @Override
    public void stop() {
        if (isDisabled()) {
            return;
        }
        log.info("正在停止 Agent Insights 报告订阅者");
        super.stop();
    }

    @Override
    protected Mono<Void> processEvent(@NonNull AgentInsightsReportMessage message) {
        log.info("正在处理 Agent Insights 报告触发器：reportId='{}', project='{}', workspace='{}'",
                message.reportId(), message.projectId(), message.workspaceId());

        // 将 null（在 triggerSource 出现之前入队的遗留消息）默认为定时扫描。
        String triggerSource = message.triggerSource() != null
                ? message.triggerSource()
                : AgentInsightsMetrics.SCHEDULED;

        return Mono.fromRunnable(() -> reportClient.triggerAgentInsights(message.reportId(), message.projectId(),
                message.workspaceId(), message.periodStart(), message.periodEnd(), triggerSource))
                .subscribeOn(Schedulers.boundedElastic())
                .then()
                .doOnSuccess(unused -> {
                    // 基类会将每条消息记录为成功，因为 processEvent 会吞掉失败
                    // （至多一次丢弃），因此其 processing-errors 指标永远不会因触发失败而触发。
                    // 此计数器为平台触发调用恢复了成功/失败的结果信号。
                    AgentInsightsMetrics.REPORTS_TRIGGERED.add(1, AgentInsightsMetrics.OUTCOME_SUCCESS);
                    log.info("已触发 Agent Insights 报告：reportId='{}', project='{}'",
                            message.reportId(), message.projectId());
                })
                .onErrorResume(throwable -> {
                    AgentInsightsMetrics.REPORTS_TRIGGERED.add(1, AgentInsightsMetrics.OUTCOME_FAILURE);
                    // 有意采用至多一次语义：报告生成是一个非幂等的副作用（Ollie
                    // 计算 + 面向用户的报告），且触发器在下游不会去重，因此
                    // 重新投递会导致同一份报告运行两次。我们在失败时确认（记录日志 + 完成）而不是
                    // 重新抛出；该运行会被丢弃（尽力而为的每日简报）而非重复执行。
                    // 如果下游添加了去重，reportId 会作为幂等键携带在消息上。
                    log.error("触发 Agent Insights 报告失败，正在丢弃 reportId='{}', project='{}'",
                            message.reportId(), message.projectId(), throwable);
                    // Ollie 从未运行，因此无法自行上报此情况：记录 "did not start" 以便 UI 停止。
                    markDidNotStart(message, throwable);
                    return Mono.empty();
                });
    }

    private void markDidNotStart(AgentInsightsReportMessage message, Throwable throwable) {
        try {
            jobService.markRunFailed(message.workspaceId(), message.projectId(), "did_not_start",
                    throwable.getMessage());
        } catch (Exception e) {
            log.warn("未能记录 reportId='{}', project='{}' 的运行失败",
                    message.reportId(), message.projectId(), e);
        }
    }

    private boolean isDisabled() {
        if (!serviceToggles.isOllieEnabled()) {
            log.info("Agent Insights 已禁用，跳过报告订阅者的生命周期操作");
            return true;
        }
        return false;
    }
}
