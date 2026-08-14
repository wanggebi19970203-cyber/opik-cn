package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/**
 * 通过 {@code Content-Length} 在读取请求体之前、且在认证之前（较低的 {@code @Priority}）
 * 以 413 拒绝超大请求。设计上是全局的（不限定路径），因此它会限制每个携带 Content-Length
 * 的请求；分块请求没有该头，会在这里直接通过，但解析期间仍受 {@code maxDocumentLength} 约束。
 */
@Slf4j
@Priority(Priorities.HEADER_DECORATOR)
public class RequestSizeLimitFilter implements ContainerRequestFilter {

    private final JacksonConfig jacksonConfig;
    private final IngestionSizeGuardMetrics sizeGuardMetrics;
    private final Provider<RequestContext> requestContext;

    @Inject
    public RequestSizeLimitFilter(@Config JacksonConfig jacksonConfig,
            IngestionSizeGuardMetrics sizeGuardMetrics, Provider<RequestContext> requestContext) {
        this.jacksonConfig = jacksonConfig;
        this.sizeGuardMetrics = sizeGuardMetrics;
        this.requestContext = requestContext;
    }

    @Override
    public void filter(ContainerRequestContext containerRequestContext) {
        String contentLengthHeader = containerRequestContext.getHeaderString(HttpHeaders.CONTENT_LENGTH);

        // 没有 Content-Length（分块）：此处无法检查；解析时请求体仍受 maxDocumentLength 约束。
        if (contentLengthHeader == null) {
            return;
        }

        // 以 long 解析，而不是 jakarta 的 int getLength()（超过约 2GB 时会返回 -1，从而放过最大的请求体）。
        // 存在但非法的 Content-Length 属于畸形的帧格式 -> 以 400 快速失败关闭。
        long contentLength;
        try {
            contentLength = Long.parseLong(contentLengthHeader.trim());
        } catch (NumberFormatException e) {
            abort(containerRequestContext, Response.Status.BAD_REQUEST, "Invalid Content-Length header");
            return;
        }

        if (contentLength < 0) {
            abort(containerRequestContext, Response.Status.BAD_REQUEST, "Invalid Content-Length header");
            return;
        }

        long maxRequestSizeBytes = jacksonConfig.getMaxRequestSizeBytes();
        if (contentLength > maxRequestSizeBytes) {
            log.warn("拒绝请求，Content-Length '{}' 字节超过了限制 '{}' 字节",
                    contentLength, maxRequestSizeBytes);
            sizeGuardMetrics.recordRequestSizeRejection(containerRequestContext.getUriInfo(), requestContext);
            abort(containerRequestContext, Response.Status.REQUEST_ENTITY_TOO_LARGE,
                    "Request body exceeds the maximum allowed size of %d bytes".formatted(maxRequestSizeBytes));
        }
    }

    private void abort(ContainerRequestContext containerRequestContext, Response.Status status, String message) {
        containerRequestContext.abortWith(Response.status(status)
                .entity(new ErrorMessage(status.getStatusCode(), message))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .build());
    }
}
