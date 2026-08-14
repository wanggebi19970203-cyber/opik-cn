package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.query.QuerySettings;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.CLICKHOUSE_HEALTH_CHECK_TIMEOUT;
import static com.comet.opik.infrastructure.db.DatabaseAnalyticsModule.READ_ONLY_FREE_FORM_SQL_CLICKHOUSE_CLIENT;

/**
 * 通过 v2 HTTP 客户端探测 Agent Insights 只读自由格式 SQL ClickHouse 用户。
 *
 * <p>由 {@code ollieEnabled} 门控：当该功能关闭时，探针会报告健康
 * 而不查询 ClickHouse，因此配置错误的只读用户绝不会为不使用该功能的
 * 环境阻断整体就绪状态。
 */
@Singleton
public class ClickHouseReadOnlyFreeFormSqlHealthCheck extends AbstractClickHouseHealthCheck {

    private final boolean enabled;

    @Inject
    public ClickHouseReadOnlyFreeFormSqlHealthCheck(
            @NonNull @Named(READ_ONLY_FREE_FORM_SQL_CLICKHOUSE_CLIENT) Client readOnlyClient,
            @NonNull @Named(CLICKHOUSE_HEALTH_CHECK_TIMEOUT) Duration healthCheckTimeout,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles) {
        super(readOnlyClient, healthCheckTimeout, "clickhouse-readonly-freeform-sql");
        this.enabled = serviceToggles.isOllieEnabled();
    }

    @Override
    protected Result check() {
        if (!enabled) {
            return Result.healthy("Agent Insights queries disabled");
        }
        return super.check();
    }

    /**
     * Agent Insights 只读自由格式 SQL 用户在 production settings profile 下运行
     * （参见 {@code provision_agent_insights_readonly_user.sh}）：{@code readonly=1}，并且只有
     * {@code SQL_workspace_id} / {@code SQL_project_id} 被标记为 {@code CHANGEABLE_IN_READONLY}。
     * 这个显式白名单会让 ClickHouse 拒绝任何其他按调用设置变更 ——
     * 包括 {@code max_execution_time}。因此探针绝不能携带任何按调用设置；
     * 调用方一侧的 {@code future.get(healthCheckTimeout)} 就是截止时间。
     */
    @Override
    protected QuerySettings newQuerySettings() {
        return null;
    }
}
