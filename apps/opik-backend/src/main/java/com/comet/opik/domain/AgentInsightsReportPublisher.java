package com.comet.opik.domain;

import com.comet.opik.infrastructure.AgentInsightsReportConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.redis.RedisStreamUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RStreamReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.UUID;

/**
 * 将 Agent Insights 报告触发请求发布到 Redis 流，使报告订阅者能够
 * 以有界的并发执行它们。手动 {@code /trigger} 端点和每日 cron
 * 都通过这里入队，因此实际的（可能较慢的）报告调用永远不会在请求或扫描
 * 线程上运行，并且消费者组会限制进行中的扇出数量。
 */
@Slf4j
@Singleton
public class AgentInsightsReportPublisher {

    private final @NonNull RedissonReactiveClient redisson;
    private final @NonNull AgentInsightsReportConfig config;
    private final @NonNull ServiceTogglesConfig serviceToggles;
    private final @NonNull IdGenerator idGenerator;

    @Inject
    public AgentInsightsReportPublisher(@NonNull RedissonReactiveClient redisson,
            @NonNull @Config("agentInsightsReport") AgentInsightsReportConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles,
            @NonNull IdGenerator idGenerator) {
        this.redisson = redisson;
        this.config = config;
        this.serviceToggles = serviceToggles;
        this.idGenerator = idGenerator;
    }

    /**
     * 将报告触发请求入队，并在消息进入流之后返回生成的报告 ID，
     * 或者在发布被禁用时以空值完成。
     */
    public Mono<String> enqueue(@NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull Instant periodStart, @NonNull Instant periodEnd, @NonNull String triggerSource) {

        if (!serviceToggles.isOllieEnabled()) {
            log.debug("Agent Insights 已禁用，忽略项目 '{}' 的触发", projectId);
            return Mono.empty();
        }

        String reportId = idGenerator.generateId().toString();
        var message = AgentInsightsReportMessage.builder()
                .reportId(reportId)
                .projectId(projectId)
                .workspaceId(workspaceId)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .triggerSource(triggerSource)
                .build();

        // DEBUG：每日扫描会为每个启用的项目入队一次，因此 INFO 仅用于生命周期事件。
        log.debug("发布 Agent Insights 报告触发：reportId='{}'、project='{}'、workspace='{}'",
                reportId, projectId, workspaceId);

        return Mono.defer(() -> {
            RStreamReactive<String, AgentInsightsReportMessage> stream = redisson.getStream(
                    config.getStreamName(), config.getCodec());

            return stream.add(RedisStreamUtils.buildAddArgs(
                    AgentInsightsReportConfig.PAYLOAD_FIELD, message, config))
                    .map(streamMessageId -> reportId)
                    .doOnError(throwable -> log.error(
                            "发布 Agent Insights 报告触发失败：reportId='{}'、project='{}'",
                            reportId, projectId, throwable));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
