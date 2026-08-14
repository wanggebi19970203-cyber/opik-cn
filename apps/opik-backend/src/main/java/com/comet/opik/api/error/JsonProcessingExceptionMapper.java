package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

@Slf4j
public class JsonProcessingExceptionMapper implements ExceptionMapper<JsonProcessingException> {

    private final IngestionSizeGuardMetrics sizeGuardMetrics;
    private final Provider<RequestContext> requestContext;
    private final Provider<UriInfo> uriInfo;

    @Inject
    public JsonProcessingExceptionMapper(IngestionSizeGuardMetrics sizeGuardMetrics,
            Provider<RequestContext> requestContext, Provider<UriInfo> uriInfo) {
        this.sizeGuardMetrics = sizeGuardMetrics;
        this.requestContext = requestContext;
        this.uriInfo = uriInfo;
    }

    @Override
    public Response toResponse(JsonProcessingException exception) {
        // StreamConstraintsException（在绑定期常被 Jackson 包装）是流读取限制在解析中途触发的表现。
        // getThrowableList 是循环安全的，因此无需手动设界。大小限制
        // （文档/字符串长度）→ 413；结构限制（嵌套/数字）或其他情况 → 400。
        StreamConstraintsException streamConstraint = ExceptionUtils.getThrowableList(exception).stream()
                .filter(StreamConstraintsException.class::isInstance)
                .map(StreamConstraintsException.class::cast)
                .findFirst()
                .orElse(null);

        Response.Status status;
        String clientMessage;
        if (streamConstraint != null) {
            sizeGuardMetrics.recordStreamConstraintRejection(streamConstraint, uriInfo.get(), requestContext);
            String guard = IngestionSizeGuardMetrics.classifyStreamConstraint(streamConstraint);
            if (IngestionSizeGuardMetrics.GUARD_DOCUMENT_LENGTH.equals(guard)
                    || IngestionSizeGuardMetrics.GUARD_STRING_LENGTH.equals(guard)) {
                log.debug("摄取大小守卫拒绝了请求", exception); // 预期情况；已在指标中记录
                status = Response.Status.REQUEST_ENTITY_TOO_LARGE;
                clientMessage = "Request payload exceeds the maximum allowed size."; // 已脱敏；详情见日志
            } else {
                // 结构限制（嵌套/数字深度），而非超大小 → 400；保持通用描述，
                // 因为该消息还包含 Jackson 的内部信息。
                log.info("拒绝违反 JSON 结构限制的请求，工作区 '{}'",
                        ErrorMetricsResolver.workspaceId(requestContext));
                status = Response.Status.BAD_REQUEST;
                clientMessage = "Unable to process JSON. The request exceeds an allowed structural limit.";
            }
        } else {
            // 仅记录脱敏摘要：异常消息携带调用者自身的请求体内容（可能是 PII），
            // 因此只记录类型 + 工作区，绝不记录异常本身（SKILL.md "Never Log PII"）。
            log.info("工作区 '{}' 的反序列化异常：{}",
                    ErrorMetricsResolver.workspaceId(requestContext), exception.getClass().getSimpleName());
            status = Response.Status.BAD_REQUEST;
            // 保留解析器细节（调用者自身的载荷，而非内部限制）—— 这是许多端点依赖的长期约定；
            // 不要将该分支通用化（曾导致约 8 个测试套件失败）。
            clientMessage = "Unable to process JSON. " + exception.getMessage();
        }

        // 强制使用 JSON：摄取端点会协商非 JSON 类型（例如 OTel protobuf），
        // 而 ErrorMessage 没有对应的写入器，因此若未显式指定类型，错误会序列化失败并表现为 500
        // （与 InvalidUUIDExceptionMapper 和 RequestSizeLimitFilter 一致）。
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorMessage(status.getStatusCode(), clientMessage))
                .build();
    }
}
