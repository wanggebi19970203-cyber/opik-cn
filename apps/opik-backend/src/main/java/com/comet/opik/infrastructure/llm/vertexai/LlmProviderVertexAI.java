package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class LlmProviderVertexAI implements LlmProviderService {

    private final @NonNull VertexAIClientGenerator llmProviderClientGenerator;
    private final @NonNull LlmProviderClientApiConfig config;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        try (var client = llmProviderClientGenerator.newVertexAIClient(config, request)) {
            ChatResponse response = client.chat(getChatMessages(request));
            return LlmProviderLangChainMapper.INSTANCE.toChatCompletionResponse(request, response);
        }
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {

        Schedulers.boundedElastic()
                .schedule(() -> {
                    CloseableVertexAiStreamingChatModel client;
                    try {
                        client = llmProviderClientGenerator.newVertexAIStreamingClient(config, request);
                    } catch (Exception e) {
                        handleError.accept(e);
                        handleClose.run();
                        return;
                    }

                    // 一旦流终止（onComplete/onError）就恰好释放一次客户端的 GAX 线程
                    // —— 绝不在本任务返回时释放，因为那是在第一个 token 之前。
                    var closed = new AtomicBoolean(false);
                    Runnable closeOnce = () -> {
                        if (closed.compareAndSet(false, true)) {
                            client.close();
                        }
                    };
                    // 消费者恰好得到一个终止信号；抛异常的处理器会被记录日志，而不会向上传播。
                    var terminalReached = new AtomicBoolean(false);
                    Runnable handleCloseAndRelease = () -> {
                        terminalReached.set(true);
                        try {
                            handleClose.run();
                        } catch (Exception e) {
                            log.warn("Vertex AI 流关闭处理器失败", e);
                        } finally {
                            closeOnce.run();
                        }
                    };
                    Consumer<Throwable> handleErrorAndRelease = throwable -> {
                        terminalReached.set(true);
                        try {
                            handleError.accept(throwable);
                        } catch (Exception e) {
                            log.warn("Vertex AI 流错误处理器失败", e);
                        } finally {
                            closeOnce.run();
                        }
                    };

                    try {
                        List<ChatMessage> chatMessages = getChatMessages(request);
                        client.chat(chatMessages,
                                new ChunkedResponseHandler(handleMessage, handleCloseAndRelease, handleErrorAndRelease,
                                        request.model()));
                    } catch (Exception e) {
                        if (terminalReached.compareAndSet(false, true)) {
                            // 在任何终止信号之前的同步失败 —— 传递错误并关闭流。
                            try {
                                handleError.accept(e);
                            } catch (Exception ex) {
                                log.warn("Vertex AI 流错误处理器失败", ex);
                            }
                            try {
                                handleClose.run();
                            } catch (Exception ex) {
                                log.warn("Vertex AI 流关闭处理器失败", ex);
                            }
                        } else {
                            log.warn("Vertex AI 流在一个终止回调已经运行之后失败", e);
                        }
                        closeOnce.run();
                    }
                });
    }

    private List<ChatMessage> getChatMessages(ChatCompletionRequest request) {
        List<ChatMessage> chatMessages = LlmProviderLangChainMapper.INSTANCE.mapMessages(request);

        // 这是对 Vertex AI API 的变通：它要求请求中至少有一条用户或 AI 消息。
        if (chatMessages.stream().noneMatch(chatMessage -> chatMessage.type() == ChatMessageType.AI
                || chatMessage.type() == ChatMessageType.USER)) {
            var newMessages = new ArrayList<ChatMessage>();
            newMessages.add(AiMessage.from("User message:")); // 向列表添加一条空用户消息，因为必须至少有一条用户或 AI 消息
            newMessages.addAll(chatMessages);
            chatMessages = newMessages;
        }

        return chatMessages;
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {

    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getGeminiErrorObject(throwable, log);
    }
}
