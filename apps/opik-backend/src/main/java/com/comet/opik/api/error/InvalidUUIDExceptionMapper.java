package com.comet.opik.api.error;

import com.comet.opik.infrastructure.metrics.UuidValidationMetrics;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;

import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

/**
 * 将 {@link InvalidUUIDException} 映射为 HTTP 400，并记录带有所匹配路由和 {@code mode=reject} 标签的拒绝率指标。
 * 计数器本身位于 {@link UuidValidationMetrics}（{@code opik.ingestion.uuid_v7.rejected} 的中心所有者）；
 * 该映射器只负责提供路由并委托处理。
 *
 * <p>{@link ResourceInfo} 是请求作用域的，因此通过 {@link Provider} 惰性获取
 * （直接注入会导致该单例在请求之外无法构造）。查找之所以能成功，
 * 是因为摄取资源在请求线程上阻塞，因此映射发生在请求作用域内。
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class InvalidUUIDExceptionMapper implements ExceptionMapper<InvalidUUIDException> {

    private static final String UNKNOWN_ROUTE = "unknown";

    private final Provider<ResourceInfo> resourceInfo;
    private final UuidValidationMetrics uuidValidationMetrics;

    @Override
    public Response toResponse(@NonNull InvalidUUIDException exception) {
        var httpRoute = getHttpRoute();
        log.info("已拒绝摄取 ID，httpRoute：'{}'，原因：'{}'，错误消息：'{}'",
                httpRoute, exception.getReason().getValue(), exception.getMessage());
        uuidValidationMetrics.recordReject(exception.getReason().getValue(), httpRoute);
        // 强制使用 JSON：摄取端点会协商其他内容类型（例如 OTel 端点使用 protobuf），
        // 这些类型没有错误实体的写入器 —— 否则 400 会序列化失败并表现为 500
        return Response.status(BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorMessage(BAD_REQUEST.getStatusCode(), "Invalid UUID for id", exception.getMessage()))
                .build();
    }

    private String getHttpRoute() {
        try {
            var info = resourceInfo.get();
            return info == null ? UNKNOWN_ROUTE : route(info.getResourceClass(), info.getResourceMethod());
        } catch (RuntimeException exception) {
            log.warn("提取 HTTP 路由失败，回退到 '{}'", UNKNOWN_ROUTE, exception);
            return UNKNOWN_ROUTE;
        }
    }

    /**
     * 根据资源类 + 方法的 {@code @Path} 模板（不含具体 ID）构建匹配的路由，
     * 以保持指标低基数 —— 例如 {@code /v1/private/traces/batch}。
     *
     * <p>对于缺少 {@code @Path} 的情况，{@link #pathValue} 返回 {@code ""}（绝不会是 {@code null}）；
     * 随后两次处理会合并重复的斜杠并去掉结尾的斜杠。当两者都没有 {@code @Path} 时，
     * 拼接结果会折叠为 {@code ""}，因此可以走到 {@link String#isEmpty()} 回退到
     * {@link #UNKNOWN_ROUTE} 的分支。
     */
    private String route(Class<?> resourceClass, Method resourceMethod) {
        var classPath = pathValue(resourceClass);
        var methodPath = pathValue(resourceMethod);
        var route = "%s/%s".formatted(classPath, methodPath)
                .replaceAll("/+", "/")
                .replaceAll("/$", "");
        return route.isEmpty() ? UNKNOWN_ROUTE : route;
    }

    private String pathValue(Class<?> resourceClass) {
        var path = resourceClass == null ? null : resourceClass.getAnnotation(Path.class);
        return path == null ? "" : path.value();
    }

    private String pathValue(Method resourceMethod) {
        var path = resourceMethod == null ? null : resourceMethod.getAnnotation(Path.class);
        return path == null ? "" : path.value();
    }
}
