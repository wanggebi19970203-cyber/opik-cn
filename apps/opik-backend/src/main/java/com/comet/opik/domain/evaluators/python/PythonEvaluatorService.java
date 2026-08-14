package com.comet.opik.domain.evaluators.python;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.RetryUtils;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest.ChatMessage;

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class PythonEvaluatorService {

    private static final String URL_TEMPLATE = "%s/v1/private/evaluators/python";
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final @NonNull RetriableHttpClient client;
    private final @NonNull OpikConfiguration config;

    public Mono<List<PythonScoreResult>> evaluate(@NonNull String code, Map<String, Object> data) {
        Preconditions.checkArgument(MapUtils.isNotEmpty(data), "Argument 'data' must not be empty");
        var request = PythonEvaluatorRequest.builder()
                .code(code)
                .data(data)
                .build();

        return executeWithRetry(Entity.json(request));
    }

    public Mono<List<PythonScoreResult>> evaluateThread(@NonNull String code, List<ChatMessage> context) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(context), "Argument 'context' must not be empty");
        TraceThreadPythonEvaluatorRequest request = TraceThreadPythonEvaluatorRequest.builder()
                .code(code)
                .data(context)
                .build();

        return executeWithRetry(Entity.json(request));
    }

    private Mono<List<PythonScoreResult>> executeWithRetry(Entity<?> body) {
        var pythonConfig = config.getPythonEvaluator();
        var request = RetriableHttpClient.Request.<List<PythonScoreResult>>builder()
                .requestFunction(client -> client.target(URL_TEMPLATE.formatted(pythonConfig.getUrl())))
                .retryPolicy(RetryUtils.handleHttpErrors(pythonConfig.getMaxRetryAttempts(),
                        pythonConfig.getMinRetryDelay().toJavaDuration(),
                        pythonConfig.getMaxRetryDelay().toJavaDuration()))
                .body(body)
                .connectTimeout(pythonConfig.getConnectTimeout().toJavaDuration())
                .readTimeout(pythonConfig.getReadTimeout().toJavaDuration())
                .responseFunction(this::processResponse)
                .build();
        return client.executePostWithRetry(request);
    }

    private List<PythonScoreResult> processResponse(Response response) {
        int statusCode = response.getStatus();
        Response.StatusType statusInfo = response.getStatusInfo();

        if (statusInfo.getFamily() == Response.Status.Family.SUCCESSFUL) {
            // 在读取前先缓冲：响应会在响应式链下游的 boundedElastic 线程上被消费，此后底层连接可能已被回收，
            // readEntity 会返回 null。null 响应体（或 null 分数）必须表现为一个干净的错误，
            // 而不是 NullPointerException。
            var body = response.hasEntity() && response.bufferEntity()
                    ? response.readEntity(PythonEvaluatorResponse.class)
                    : null;
            if (body == null || body.scores() == null) {
                throw new InternalServerErrorException(
                        "Python evaluation returned HTTP '%s' with an empty or unparseable response body"
                                .formatted(statusCode));
            }
            return body.scores();
        }

        String errorMessage = extractErrorMessage(response);

        if (statusCode == 400) {
            throw new BadRequestException(errorMessage);
        }

        throw new InternalServerErrorException(
                "Python evaluation failed (HTTP '%s'): %s".formatted(statusCode, errorMessage));
    }

    private String extractErrorMessage(Response response) {
        if (response.hasEntity() && response.bufferEntity()) {
            try {
                var errorResponse = response.readEntity(PythonEvaluatorErrorResponse.class);
                if (errorResponse != null && StringUtils.isNotBlank(errorResponse.error())) {
                    return StringUtils.truncate(errorResponse.error(), MAX_ERROR_MESSAGE_LENGTH);
                }
            } catch (RuntimeException parseErrorResponse) {
                // 当响应体不是结构化错误格式时属于预期情况；回退到原始响应体。
                log.debug("解析结构化错误响应失败，回退到解析字符串", parseErrorResponse);
            }

            // 当结构化错误缺失/为空时回退到原始响应体，以免后端细节丢失
            // （例如 python-backend 的 "can't be evaluated:" 带有空消息）。响应体是被评估的用户指标自身的错误，
            // 我们希望把它暴露出来；限制其长度，避免过大的负载使抛出的异常消息膨胀。
            try {
                var body = response.readEntity(String.class);
                if (StringUtils.isNotBlank(body)) {
                    return StringUtils.truncate(body, MAX_ERROR_MESSAGE_LENGTH);
                }
            } catch (RuntimeException parseStringResponse) {
                log.warn("读取错误响应体失败", parseStringResponse);
            }
        }

        return "Unknown error during Python evaluation";
    }

}
