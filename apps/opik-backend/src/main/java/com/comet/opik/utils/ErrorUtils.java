package com.comet.opik.utils;

import com.clickhouse.client.ClickHouseException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

@UtilityClass
@Slf4j
public class ErrorUtils {

    /**
     * 当 throwable 是 ClickHouse 以 BAD_ARGUMENTS "Unable to parse JSONPath" 错误
     * 拒绝畸形的 JSON 路径时返回 true。调用方把它视为空结果，而不是
     * 暴露 500。周围的措辞因 ClickHouse 版本而异（例如
     * "JSONPath. (BAD_ARGUMENTS)" 对比 "JSONPath: In scope ... (BAD_ARGUMENTS)"），因此这里
     * 匹配稳定的子串而不是精确短语。
     */
    public static boolean isMalformedJsonPath(Throwable e) {
        return e instanceof ClickHouseException && e.getMessage() != null
                && e.getMessage().contains("Unable to parse JSONPath")
                && e.getMessage().contains("BAD_ARGUMENTS");
    }

    /**
     * 恢复一次其过滤器携带了 ClickHouse 无法解析的 JSON 路径的读取，产出
     * {@code defaultValue} 而不是暴露 500。ClickHouse 在分析查询时会拒绝这样的路径，
     * 因此失败在任何行被读取之前就到达，与数据无关；
     * 空结果才是诚实的答案。所有其他错误都原样传播。
     * <p>
     * 过滤器键本来就被构建为可解析的，因此这里只捕获调用方编写的、
     * 通过构建但在服务器端仍被拒绝的表达式。
     */
    public static <T> Mono<T> handleMalformedJsonPath(@NonNull Throwable e, @NonNull T defaultValue) {
        if (isMalformedJsonPath(e)) {
            log.info("过滤器使用了 ClickHouse 无法解析的 JSON 路径，返回空结果");
            return Mono.just(defaultValue);
        }
        return Mono.error(e);
    }

    public static NotFoundException failWithNotFound(@NonNull String entity, @NonNull String id) {
        String message = "%s id: %s not found".formatted(entity, id);
        return failWithNotFound(message);
    }

    public static NotFoundException failWithNotFoundName(@NonNull String entity, @NonNull String name) {
        String message = "%s name: %s not found".formatted(entity, name);
        return failWithNotFound(message);
    }

    public static NotFoundException failWithNotFound(@NonNull String entity, @NonNull UUID id) {
        return failWithNotFound(entity, id.toString());
    }

    public static NotFoundException failWithNotFound(@NonNull String message) {
        log.info(message);
        return new NotFoundException(message);
    }
}
