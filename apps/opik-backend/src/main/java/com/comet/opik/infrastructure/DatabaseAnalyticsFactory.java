package com.comet.opik.infrastructure;

import com.clickhouse.client.api.Client;
import com.google.common.base.Splitter;
import io.dropwizard.util.Duration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Data
public class DatabaseAnalyticsFactory {

    private static final String URL_TEMPLATE = "r2dbc:clickhouse:%s://%s:%s@%s:%d/%s%s";
    private static final String CUSTOM_HTTP_PARAMS_KEY = "custom_http_params";
    private static final String ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS = "async_insert_busy_timeout_max_ms";
    private static final String ASYNC_INSERT_BUSY_TIMEOUT_MIN_MS = "async_insert_busy_timeout_min_ms";
    private static final String ASYNC_INSERT_MAX_DATA_SIZE = "async_insert_max_data_size";
    private static final String KEY_VALUE_FORMAT = "%s=%s";

    // 只按第一个 `=` 拆分每个 `&`/`,` 分块——值本身可能包含 `=`，
    // 例如 `custom_http_params=max_query_size=100000000,async_insert=1`。
    private static final Splitter KV_SPLITTER = Splitter.on('=').trimResults().limit(2);
    private static final Splitter.MapSplitter TOP_LEVEL_SPLITTER = Splitter.on('&')
            .trimResults().omitEmptyStrings().withKeyValueSeparator(KV_SPLITTER);
    private static final Splitter.MapSplitter CUSTOM_HTTP_PARAMS_SPLITTER = Splitter.on(',')
            .trimResults().omitEmptyStrings().withKeyValueSeparator(KV_SPLITTER);

    private @NotNull Protocol protocol;
    private @NotBlank String host;
    private int port;
    private @NotBlank String username;
    private @NotNull String password;
    private @NotBlank String databaseName;
    private String queryParameters;

    /**
     * {@code async_insert_busy_timeout_max_ms} 的可选覆盖值（毫秒）。设置后会应用到 {@code custom_http_params}
     * 链——覆盖已有值，或在缺失时添加；未设置时 {@code queryParameters} / ClickHouse 服务端的值保持不变。
     * 在 {@code async_insert_use_adaptive_busy_timeout=1} 时，这是自适应缓冲窗口的上限。
     */
    private @Min(1) Integer asyncInsertBusyTimeoutMaxMs;

    /**
     * {@code async_insert_busy_timeout_min_ms} 的可选覆盖值（毫秒）——自适应缓冲窗口的下限。
     * 语义与 {@link #asyncInsertBusyTimeoutMaxMs} 相同。
     */
    private @Min(1) Integer asyncInsertBusyTimeoutMinMs;

    /**
     * {@code async_insert_max_data_size} 的可选覆盖值（字节）——触发异步插入刷新的缓冲大小。
     * 语义与 {@link #asyncInsertBusyTimeoutMaxMs} 相同。更大的值在高吞吐摄入下会产生更少、更大的分区，
     * 代价是占用更多缓冲内存。
     */
    private @Min(1) Long asyncInsertMaxDataSize;

    private Duration healthCheckTimeout = Duration.seconds(1);

    // 可选的套接字超时，仅在设置时于 buildClient() 中应用（null = 库默认值 0/无超时）。
    private Duration clientSocketTimeout;

    /**
     * 控制 {@code clickhouse-cluster} 健康检查。单分片 / 非 Distributed 部署时关闭；
     * 仅对 Distributed（Hyperscale）拓扑打开，因为该拓扑下集群定义必须对每个节点可见。
     */
    private boolean clusterHealthCheckEnabled;

    /**
     * 控制 {@code clickhouse-cold-storage-disk} 健康检查。没有分层存储的部署（OSS Docker）时关闭；
     * 仅在 {@code cold_s3} S3 磁盘激活后打开。
     */
    private boolean coldStorageDiskHealthCheckEnabled;

    public ConnectionFactory build() {
        var queryParametersOverrides = getQueryParametersOverrides(queryParameters);
        var options = queryParametersOverrides == null ? "" : "?%s".formatted(queryParametersOverrides);
        var url = URL_TEMPLATE.formatted(protocol.getValue(), username, password, host, port, databaseName, options);
        return ConnectionFactories.get(url);
    }

    /**
     * 构建批量插入路径使用的 ClickHouse V2 HTTP {@link Client}
     * （参见 {@code ExperimentAggregatesDAO.insertExperimentItemAggregates}）。
     *
     * <p>凭据、主机、端口、数据库和 {@code queryParameters} 与 {@link #build()} 保持一致，
     * 使两个客户端共享单一事实来源。连接池、超时和其他驱动级行为有意保持库默认值，除非通过
     * {@code queryParameters} 显式设置（例如 {@code compress=1}、{@code health_check_interval=2000}）。
     *
     * <p>{@code queryParameters} 解析：顶层的 {@code &} 分隔键通过
     * {@link Client.Builder#setOption(String, String)} 应用为驱动选项；{@code custom_http_params} 的值是
     * 逗号分隔的 ClickHouse 服务端设置列表，通过 {@link Client.Builder#serverSetting(String, String)} 应用。
     * 这与 R2DBC 约定一致：顶层持有驱动标志，{@code custom_http_params} 携带服务端 {@code SETTINGS ...} 负载。
     */
    public Client buildClient() {
        var builder = new Client.Builder()
                .addEndpoint("%s://%s:%d/".formatted(protocol.getValue(), host, port))
                .setUsername(username)
                .setPassword(password)
                .setDefaultDatabase(databaseName)
                .compressClientRequest(true)
                .compressServerResponse(true)
                // 真正的非阻塞：没有它，Client.query() 会在调用线程上同步执行 HTTP 往返并返回一个已完成的
                // future，使 Mono.fromFuture() 失去意义。开启 async 后，工作运行在 v2 客户端的执行器上，
                // future 会真正延迟到响应返回。
                .useAsyncRequests(true);

        // 只有服务端设置（custom_http_params 的内容）会被转发给 v2 客户端。
        // 顶层驱动选项（compress=1、health_check_interval、auto_discovery、failover）
        // 是 R2DBC 特有的，无法对应到 v2 驱动接口；连接池、超时和压缩由上面的 v2 Client.Builder 方法负责。
        // parseQueryParameters() 仍会返回值，供测试/可观测性使用。
        var parsed = parseQueryParameters(getQueryParametersOverrides(queryParameters));
        parsed.serverSettings().forEach(builder::serverSetting);

        if (clientSocketTimeout != null) {
            builder.setSocketTimeout(clientSocketTimeout.toMilliseconds(), ChronoUnit.MILLIS);
        }

        return builder.build();
    }

    /**
     * 返回已应用每个已设置的 {@link #configurableServerSettings()} 覆盖值的 {@code queryParameters}，
     * 应用到 {@code custom_http_params}——覆盖已有值，或在缺失时添加（包括 {@code queryParameters} 为空白时）。
     * 未设置任何覆盖字段时原样返回。
     */
    private String getQueryParametersOverrides(String queryParameters) {
        var overrides = configurableServerSettings();
        if (overrides.isEmpty()) {
            return queryParameters;
        }
        var parsed = parseQueryParameters(queryParameters);
        var serverSettings = new LinkedHashMap<>(parsed.serverSettings());
        serverSettings.putAll(overrides);
        return serialize(parsed.driverOptions(), serverSettings);
    }

    /**
     * 来自专用配置字段的服务端设置，字段已设置时才包含。每个都由
     * {@link #getQueryParametersOverrides(String)} 应用到 {@code custom_http_params}——覆盖已有值，或在缺失时
     * 添加——因此未设置的字段会让 {@code queryParameters} / ClickHouse 服务端的值保持不变。
     */
    private Map<String, String> configurableServerSettings() {
        var overrides = new LinkedHashMap<String, String>();
        if (asyncInsertBusyTimeoutMaxMs != null) {
            overrides.put(ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS, String.valueOf(asyncInsertBusyTimeoutMaxMs));
        }
        if (asyncInsertBusyTimeoutMinMs != null) {
            overrides.put(ASYNC_INSERT_BUSY_TIMEOUT_MIN_MS, String.valueOf(asyncInsertBusyTimeoutMinMs));
        }
        if (asyncInsertMaxDataSize != null) {
            overrides.put(ASYNC_INSERT_MAX_DATA_SIZE, String.valueOf(asyncInsertMaxDataSize));
        }
        return overrides;
    }

    private String serialize(Map<String, String> driverOptions, Map<String, String> serverSettings) {
        var topLevel = driverOptions.entrySet().stream()
                .map(this::formatEntry)
                .collect(Collectors.toCollection(ArrayList::new));
        if (!serverSettings.isEmpty()) {
            var customHttpParams = serverSettings.entrySet().stream()
                    .map(this::formatEntry)
                    .collect(Collectors.joining(","));
            topLevel.add(formatEntry(CUSTOM_HTTP_PARAMS_KEY, customHttpParams));
        }
        return String.join("&", topLevel);
    }

    private String formatEntry(Map.Entry<String, String> entry) {
        return formatEntry(entry.getKey(), entry.getValue());
    }

    private String formatEntry(String key, String value) {
        return KEY_VALUE_FORMAT.formatted(key, value);
    }

    /**
     * 把一个 {@code queryParameters} 字符串拆分为两个映射：
     * <ul>
     *   <li>{@code driverOptions} — 顶层 {@code &} 分隔条目，用于
     *       {@link Client.Builder#setOption(String, String)}。</li>
     *   <li>{@code serverSettings} — {@code custom_http_params=...} 的内容，即逗号分隔的
     *       ClickHouse 服务端设置列表（用于 {@link Client.Builder#serverSetting(String, String)}）。</li>
     * </ul>
     */
    static ParsedQueryParameters parseQueryParameters(String queryParameters) {
        if (StringUtils.isBlank(queryParameters)) {
            return new ParsedQueryParameters(Map.of(), Map.of());
        }
        Map<String, String> driverOptions = new LinkedHashMap<>();
        Map<String, String> serverSettings = new LinkedHashMap<>();
        TOP_LEVEL_SPLITTER.split(queryParameters).forEach((key, value) -> {
            if (CUSTOM_HTTP_PARAMS_KEY.equals(key)) {
                serverSettings.putAll(CUSTOM_HTTP_PARAMS_SPLITTER.split(value));
                return;
            }
            driverOptions.put(key, value);
        });
        return new ParsedQueryParameters(driverOptions, serverSettings);
    }

    record ParsedQueryParameters(Map<String, String> driverOptions, Map<String, String> serverSettings) {
    }

    @RequiredArgsConstructor
    @Getter
    public enum Protocol {
        HTTP("http"),
        HTTPS("https"),
        ;

        private final String value;
    }
}
