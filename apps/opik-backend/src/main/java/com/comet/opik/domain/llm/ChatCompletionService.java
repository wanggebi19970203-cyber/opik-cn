package com.comet.opik.domain.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.utils.ChunkedOutputHandlers;
import com.google.common.base.Throwables;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.internal.RetryUtils;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import static jakarta.ws.rs.core.Response.Status.Family.familyOf;

@Singleton
@Slf4j
public class ChatCompletionService {
    public static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";
    public static final String UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER = "Unsupported feature for the selected LLM provider";
    public static final String ERROR_EMPTY_MESSAGES = "messages cannot be empty";

    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmProviderFactory llmProviderFactory;
    private final RetryUtils.RetryPolicy retryPolicy;

    @Inject
    public ChatCompletionService(
            @NonNull @Config LlmProviderClientConfig llmProviderClientConfig,
            @NonNull LlmProviderFactory llmProviderFactory) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.llmProviderFactory = llmProviderFactory;
        this.retryPolicy = newRetryPolicy();
    }

    public ChatCompletionResponse create(@NonNull ChatCompletionRequest rawRequest, @NonNull String workspaceId) {
        // 必须是 final 或事实上 final 才能用于 lambda
        var request = MessageContentNormalizer.normalizeRequest(rawRequest);

        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());
        llmProviderClient.validateRequest(request);

        ChatCompletionResponse chatCompletionResponse;
        try {
            log.info("创建聊天补全, workspaceId '{}', model '{}'", workspaceId, request.model());
            chatCompletionResponse = retryPolicy.withRetry(
                    () -> failFastOnUnsupportedFeature(() -> llmProviderClient.generate(request, workspaceId)));
        } catch (RuntimeException runtimeException) {
            failIfUnsupportedFeature(runtimeException);

            Optional<ErrorMessage> providerError = llmProviderClient.getLlmProviderError(runtimeException);

            providerError
                    .ifPresent(llmProviderError -> failHandlingLLMProviderError(runtimeException, llmProviderError));

            log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            throw new InternalServerErrorException(buildDetailedErrorMessage(runtimeException), runtimeException);
        }

        log.info("已创建聊天补全, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chatCompletionResponse;
    }

    public void createAndStreamResponse(
            @NonNull ChatCompletionRequest rawRequest,
            @NonNull String workspaceId,
            @NonNull ChunkedOutputHandlers handlers) {
        var request = MessageContentNormalizer.normalizeRequest(rawRequest);

        log.info("创建并流式输出聊天补全, workspaceId '{}', model '{}'", workspaceId, request.model());

        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());
        var errorHandler = getErrorHandler(handlers, llmProviderClient);

        try {
            llmProviderClient.generateStream(
                    request,
                    workspaceId,
                    handlers::handleMessage,
                    handlers::handleClose,
                    errorHandler);
        } catch (UnsupportedFeatureException unsupportedFeature) {
            // 流式客户端只有一种契约：HTTP 200 并在流内传递错误。VertexAI 和 Gemini 已经通过在它们自己的
            // boundedElastic 任务中捕获一切来保证这一点，但 OpenAiResponses、OpenAI、CustomLlm 和 Anthropic 是
            // 内联运行的，因此在提供商介入之前抛出的不支持特性否则会以 HTTP 状态码的形式逃逸，从而只对这些提供商
            // 破坏该契约。这里按精确类型捕获而不是 RuntimeException：此调用没有重试策略包裹，所以这些异常未经包装
            // 到达，其他所有异常则继续原样传播到资源层。BadRequestException 有意不捕获：
            // LlmProviderAnthropic.generateStream 会内联校验消息并抛出它，那必须保持为真正的 HTTP 400。
            errorHandler.accept(unsupportedFeature);
            return;
        }

        log.info("已创建并流式输出聊天补全, workspaceId '{}', model '{}'", workspaceId,
                request.model());
    }

    public ChatResponse scoreTrace(@NonNull ChatRequest chatRequest,
            @NonNull LlmAsJudgeModelParameters modelParameters,
            @NonNull String workspaceId) {
        var languageModelClient = llmProviderFactory.getLanguageModel(workspaceId, modelParameters);

        ChatResponse chatResponse;
        try {
            log.info("使用模型 '{}' 发起聊天并期待结构化响应, workspaceId '{}'",
                    modelParameters.name(), workspaceId);
            chatResponse = retryPolicy
                    .withRetry(() -> failFastOnUnsupportedFeature(() -> languageModelClient.chat(chatRequest)));
            log.info("使用模型 '{}' 完成聊天并期待结构化响应, workspaceId '{}'",
                    modelParameters.name(), workspaceId);
            return chatResponse;
        } catch (RuntimeException runtimeException) {
            failIfUnsupportedFeature(runtimeException);

            LlmProviderService provider = llmProviderFactory.getService(workspaceId, modelParameters.name());

            Optional<ErrorMessage> providerError = provider.getLlmProviderError(runtimeException);

            providerError
                    .ifPresent(llmProviderError -> failHandlingLLMProviderError(runtimeException, llmProviderError));

            log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            throw new InternalServerErrorException(buildDetailedErrorMessage(runtimeException), runtimeException);
        } finally {
            // 关闭 Vertex 客户端（跨重试复用）以释放其 GAX 线程；其他提供商会自行回收。
            if (languageModelClient instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    log.warn("关闭语言模型客户端失败", e);
                }
            }
        }
    }

    /**
     * {@link UnsupportedFeatureException} 继承自 {@code LangChain4jException}，而不是 {@code NonRetriableException}，
     * 因此 {@code RetryPolicy.withRetry} 会把它当作任何瞬时失败一样对待，在一个永远不可能成功的调用上耗尽整个
     * 重试预算（外加其退避延迟）。把它作为 {@link NonRetriableException} 重新抛出，可以让 {@code withRetry} 在
     * 第一次尝试时就放弃，同时保持真正瞬时的提供商错误仍可重试。原始异常被保留为 cause，因此下游的
     * {@link #failIfUnsupportedFeature} 仍能识别它。
     */
    private <T> T failFastOnUnsupportedFeature(Callable<T> action) throws Exception {
        try {
            return action.call();
        } catch (RuntimeException runtimeException) {
            if (findUnsupportedFeature(runtimeException).isPresent()) {
                throw new NonRetriableException(runtimeException);
            }
            throw runtimeException;
        }
    }

    /**
     * 当请求要求的能力未被所选提供商实现时，langchain4j 会抛出 {@link UnsupportedFeatureException}——例如针对
     * Vertex AI Gemini 的 {@code ToolChoice.REQUIRED}。提供商从未被触达，因此 {@code getLlmProviderError} 没有
     * 可映射的内容，该调用过去表现为 500。这在两方面具有误导性：服务端并没有任何东西失败，而且无论重试多少次
     * 都不可能成功。把它报告为 400，让客户端得到一个可操作的错误，也让在线评分消费者将其视为终结性错误，而不是
     * 在它上面耗尽重试预算。
     */
    private void failIfUnsupportedFeature(RuntimeException runtimeException) {
        var unsupportedFeature = findUnsupportedFeature(runtimeException);
        if (unsupportedFeature.isEmpty()) {
            return;
        }

        var message = buildUnsupportedFeatureMessage(unsupportedFeature.get());
        // 记录日志时不带 throwable：这是一个可预期的、确定性的客户端错误，而在生产流量规模下，每次拒绝都记录
        // 一个堆栈跟踪会淹没真正的提供商失败。
        log.warn(message);
        // 消息作为 ErrorMessage 实体携带，而不仅仅放在异常上：Jersey 通过其 Response 渲染
        // WebApplicationException，因此只带消息的构造函数会返回一个无实体的 400，调用方将永远不知道是哪个能力
        // 被拒绝了。
        throw new BadRequestException(
                message,
                Response.status(Response.Status.BAD_REQUEST)
                        .entity(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(), message))
                        .build(),
                runtimeException);
    }

    /**
     * 基于 {@link UnsupportedFeatureException} 自身的消息构建，而不是基于链的根因，这样客户端能被告知是哪个能力
     * 被拒绝，链中更深层的任何内容都不会泄漏到响应中。
     */
    private String buildUnsupportedFeatureMessage(UnsupportedFeatureException unsupportedFeature) {
        String detail = unsupportedFeature.getMessage();
        return StringUtils.isNotBlank(detail)
                ? UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER + ": " + detail
                : UNSUPPORTED_FEATURE_CALLING_LLM_PROVIDER;
    }

    /**
     * 遍历 cause 链，因此无论异常是裸抛出、被提供商客户端包裹，还是被 {@link #failFastOnUnsupportedFeature}
     * 重新抛出，都能匹配。{@code ExceptionUtils} 会在第一个已访问过的 throwable 处停止，因此自引用的 cause 链会
     * 终止而不是循环。
     */
    private Optional<UnsupportedFeatureException> findUnsupportedFeature(Throwable throwable) {
        return ExceptionUtils.getThrowableList(throwable).stream()
                .filter(UnsupportedFeatureException.class::isInstance)
                .map(UnsupportedFeatureException.class::cast)
                .findFirst();
    }

    private void failHandlingLLMProviderError(RuntimeException runtimeException, ErrorMessage llmProviderError) {
        log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);

        if (familyOf(llmProviderError.getCode()) == Response.Status.Family.CLIENT_ERROR) {
            throw new ClientErrorException(llmProviderError.getMessage(), llmProviderError.getCode());
        }

        throw new ServerErrorException(llmProviderError.getMessage(), llmProviderError.getCode());
    }

    private RetryUtils.RetryPolicy newRetryPolicy() {
        var retryPolicyBuilder = RetryUtils.retryPolicyBuilder();
        Optional.ofNullable(llmProviderClientConfig.getMaxAttempts()).ifPresent(retryPolicyBuilder::maxRetries);
        Optional.ofNullable(llmProviderClientConfig.getJitterScale()).ifPresent(retryPolicyBuilder::jitterScale);
        Optional.ofNullable(llmProviderClientConfig.getBackoffExp()).ifPresent(retryPolicyBuilder::backoffExp);
        return retryPolicyBuilder.delayMillis(llmProviderClientConfig.getDelayMillis()).build();
    }

    private Consumer<Throwable> getErrorHandler(ChunkedOutputHandlers handlers, LlmProviderService llmProviderClient) {
        return throwable -> {
            // 在提供商错误映射器之前检查，使分类与 create() 和 scoreTrace() 保持一致：如果提供商封装错误与
            // 不支持的特性同时发生，确定性的能力失败优先。
            var unsupportedFeature = findUnsupportedFeature(throwable);
            if (unsupportedFeature.isPresent()) {
                var message = buildUnsupportedFeatureMessage(unsupportedFeature.get());
                log.warn(message);
                handlers.handleError(new ErrorMessage(Response.Status.BAD_REQUEST.getStatusCode(), message));
                return;
            }

            Optional<ErrorMessage> providerError = llmProviderClient.getLlmProviderError(throwable);

            if (providerError.isPresent()) {
                log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);
                handlers.handleError(providerError.get());
            } else {

                if (throwable instanceof BadRequestException userMessage) {
                    log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, userMessage);
                    handlers.handleError(
                            new ErrorMessage(userMessage.getResponse().getStatus(), userMessage.getMessage()));
                    return;
                }

                log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);

                var errorMessage = new ErrorMessage(buildDetailedErrorMessage(throwable));
                handlers.handleError(errorMessage);
            }
        };
    }

    /**
     * 通过组合基础错误消息与异常详情来构建详细的错误消息。
     * 从异常链中提取有意义的错误信息。
     *
     * @param throwable 要从中提取详情的异常
     * @return 组合基础消息与异常详情的详细错误消息
     */
    private String buildDetailedErrorMessage(Throwable throwable) {
        String exceptionDetails = extractErrorDetails(throwable);
        if (StringUtils.isNotBlank(exceptionDetails)) {
            return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER + ": " + exceptionDetails;
        }
        return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
    }

    /**
     * 从异常链中提取有意义的错误详情。
     * 遍历异常链以找到信息量最大的错误消息，优先选择根因而非包装异常。
     * 为常见异常类型提供用户友好的消息。
     *
     * @param throwable 要从中提取详情的异常
     * @return 提取出的错误详情，如果没有找到有意义的详情则为 null
     */
    private String extractErrorDetails(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        Throwable rootCause = Throwables.getRootCause(throwable);

        // 使用最具体的异常（如果有根因则使用根因）
        Throwable exceptionToHandle = (rootCause != throwable) ? rootCause : throwable;

        // 根据异常类型提供用户友好的消息
        return switch (exceptionToHandle) {
            case ConnectException connectException -> {
                // 如果可用，从异常消息中提取主机/URL
                String message = connectException.getMessage();
                if (message != null && message.contains("Connection refused")) {
                    yield "Service is unreachable. Please check the provider URL.";
                }
                yield "Service is unreachable: " + message;
            }
            case ClosedChannelException closedChannelException ->
                "Service is unreachable. Please check the provider URL.";
            default -> {
                // 对于其他异常，使用异常消息
                String message = exceptionToHandle.getMessage();
                if (StringUtils.isNotBlank(message)) {
                    yield message;
                }
                // 如果没有消息，则回退到异常类名
                yield exceptionToHandle.getClass().getSimpleName();
            }
        };
    }
}
