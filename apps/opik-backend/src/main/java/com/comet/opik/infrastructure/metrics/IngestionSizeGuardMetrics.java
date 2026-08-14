package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ENDPOINT_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.UNKNOWN;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_NAME_KEY;

/**
 * 为被服务端摄入大小守卫（OPIK-7333/7334）拒绝的请求发出
 * {@code ingestion_size_guard_rejections_total}，按 {@code component}、{@code guard}、
 * {@code endpoint} 以及 {@code workspace_id}/{@code workspace_name} 打标签。这些拒绝
 * 永远不会到达 {@link HttpErrorMetrics}，因此它们需要自己的计数器。
 */
@Singleton
public class IngestionSizeGuardMetrics {

    private static final String METRIC_NAMESPACE = "ingestion_size_guard";

    public static final AttributeKey<String> GUARD_KEY = AttributeKey.stringKey("guard");
    public static final AttributeKey<String> COMPONENT_KEY = AttributeKey.stringKey("component");

    public static final String GUARD_REQUEST_SIZE = "request_size";
    public static final String GUARD_DOCUMENT_LENGTH = "document_length";
    public static final String GUARD_STRING_LENGTH = "string_length";
    public static final String GUARD_UNCLASSIFIED = "unclassified"; // 未分类回退

    public static final String COMPONENT_REQUEST_FILTER = "request_filter";
    public static final String COMPONENT_JSON_PARSER = "json_parser";

    private final LongCounter rejectionCounter;

    public IngestionSizeGuardMetrics() {
        Meter meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.rejectionCounter = meter
                .counterBuilder("%s_rejections_total".formatted(METRIC_NAMESPACE))
                .setDescription("Ingestion requests rejected by a server-side size guard, by component "
                        + "(request_filter|json_parser), guard (request_size|document_length|string_length), "
                        + "endpoint and workspace.")
                .build();
    }

    public void recordRequestSizeRejection(UriInfo uriInfo, Provider<RequestContext> requestContext) {
        record(COMPONENT_REQUEST_FILTER, GUARD_REQUEST_SIZE, ErrorMetricsResolver.endpoint(uriInfo),
                ErrorMetricsResolver.workspaceId(requestContext),
                ErrorMetricsResolver.workspaceName(requestContext));
    }

    public void recordStreamConstraintRejection(StreamConstraintsException exception, UriInfo uriInfo,
            Provider<RequestContext> requestContext) {
        record(COMPONENT_JSON_PARSER, classifyStreamConstraint(exception), ErrorMetricsResolver.endpoint(uriInfo),
                ErrorMetricsResolver.workspaceId(requestContext),
                ErrorMetricsResolver.workspaceName(requestContext));
    }

    public void record(String component, String guard, String endpoint, String workspaceId, String workspaceName) {
        var resolvedWorkspaceId = StringUtils.defaultIfBlank(workspaceId, UNKNOWN);
        rejectionCounter.add(1, Attributes.builder()
                .put(COMPONENT_KEY, StringUtils.defaultIfBlank(component, UNKNOWN))
                .put(GUARD_KEY, StringUtils.defaultIfBlank(guard, GUARD_UNCLASSIFIED))
                .put(ENDPOINT_KEY, StringUtils.defaultIfBlank(endpoint, UNKNOWN))
                .put(WORKSPACE_ID_KEY, resolvedWorkspaceId)
                .put(WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, resolvedWorkspaceId))
                .build());
    }

    // 通过 Jackson 的消息文本（它暴露的唯一信号）来分类：版本相关，由
    // IngestionSizeGuardMetricsTest 守护；Jackson 升级时需重新检查。无法识别 -> 未分类。
    public static String classifyStreamConstraint(StreamConstraintsException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return GUARD_UNCLASSIFIED;
        }
        if (message.contains("getMaxDocumentLength") || message.contains("Document length")) {
            return GUARD_DOCUMENT_LENGTH;
        }
        if (message.contains("getMaxStringLength") || message.contains("String value length")) {
            return GUARD_STRING_LENGTH;
        }
        return GUARD_UNCLASSIFIED;
    }
}
