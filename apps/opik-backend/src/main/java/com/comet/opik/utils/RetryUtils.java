package com.comet.opik.utils;

import jakarta.ws.rs.ProcessingException;
import lombok.Getter;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.NoHttpResponseException;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.io.InterruptedIOException;
import java.net.SocketException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

@Slf4j
@UtilityClass
public class RetryUtils {

    public static RetryBackoffSpec handleConnectionError() {
        return Retry.backoff(3, Duration.ofMillis(100))
                .doBeforeRetry(retrySignal -> log.warn("因以下原因重试: {}", retrySignal.failure().getMessage()))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure())
                .filter(throwable -> {
                    log.debug("为重试进行过滤: {}", throwable.getMessage());

                    return SocketException.class.isAssignableFrom(throwable.getClass())
                            || (throwable instanceof IllegalStateException
                                    && throwable.getMessage().contains("Connection pool shut down"));
                });
    }

    public static RetryBackoffSpec handleOnDeadLocks() {
        return Retry.backoff(5, Duration.ofMillis(250))
                .maxBackoff(Duration.ofSeconds(2))
                .jitter(0.5) // 添加抖动以减少惊群效应
                .doBeforeRetry(retrySignal -> log.warn("因数据库死锁而重试",
                        retrySignal.failure()))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure())
                .filter(throwable -> {
                    log.debug("因数据库死锁为重试进行过滤", throwable);

                    return isDatabaseDeadlock(throwable);
                });
    }

    @Getter
    public static class RetryableHttpException extends RuntimeException {
        private final int statusCode;

        public RetryableHttpException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public RetryableHttpException(String message, int statusCode, Throwable cause) {
            super(message, cause);
            this.statusCode = statusCode;
        }
    }

    /**
     * 处理瞬时 HTTP 错误（如超时和连接问题）的重试规范。
     *
     * @param maxAttempts 最大重试次数。
     * @param minBackoff  两次重试之间的最小退避时长。
     * @param maxBackoff  两次重试之间的最大退避时长。
     * @return 已配置的 RetryBackoffSpec 实例。
     */
    public static RetryBackoffSpec handleHttpErrors(int maxAttempts, Duration minBackoff, Duration maxBackoff) {
        return Retry.backoff(maxAttempts, minBackoff)
                .maxBackoff(maxBackoff)
                .doBeforeRetry(retrySignal -> log.warn("因以下原因重试: {}", retrySignal.failure().getMessage()))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> retrySignal.failure())
                .filter(throwable -> {
                    log.debug("为重试进行过滤: {}", throwable.getMessage());
                    return isRetriableException(throwable);
                });
    }

    private static boolean isDatabaseDeadlock(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        String className = throwable.getClass().getName();
        String message = throwable.getMessage();

        // 检查 MySQL 事务回滚异常（死锁）
        boolean isMySQLDeadlock = "com.mysql.cj.jdbc.exceptions.MySQLTransactionRollbackException".equals(className)
                && message != null && message.contains("Deadlock found");

        // 如果不是直接匹配，递归检查 cause
        return isMySQLDeadlock || (throwable.getCause() != null && isDatabaseDeadlock(throwable.getCause()));
    }

    private static boolean isRetriableException(Throwable throwable) {
        return switch (throwable) {
            // 网络和超时瞬时错误
            case SocketException ex -> true;
            case TimeoutException ex -> true;
            case InterruptedIOException ex -> true;
            // HTTP 客户端瞬时错误（服务器关闭连接、暂时不可用）
            case NoHttpResponseException ex -> true;
            case RetryableHttpException ex -> true;
            // 包装的异常：递归检查 cause 以查找瞬时错误
            case ProcessingException ex -> {
                var cause = ex.getCause();
                yield cause != null && isRetriableException(cause);
            }
            default -> false;
        };
    }

}
