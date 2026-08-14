package com.comet.opik.infrastructure.llm.vertexai;

import com.google.cloud.vertexai.VertexAI;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

// CloseableVertexAiChatModel 的流式对应物：持有 VertexAI 并关闭它（调用方在流终止时关闭）。
@Slf4j
class CloseableVertexAiStreamingChatModel implements StreamingChatModel, AutoCloseable {

    private final @NonNull StreamingChatModel delegate;
    private final @NonNull VertexAI vertexAI;

    CloseableVertexAiStreamingChatModel(@NonNull StreamingChatModel delegate, @NonNull VertexAI vertexAI) {
        this.delegate = delegate;
        this.vertexAI = vertexAI;
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        delegate.chat(chatRequest, handler);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    // 尽力而为：关闭失败绝不能在一个本应成功的调用上暴露出来。
    @Override
    public void close() {
        try {
            vertexAI.close();
        } catch (Exception e) {
            log.warn("关闭 Vertex AI 流式客户端失败", e);
        }
        // 流式委托对象持有一个按实例的执行器；只有它的 close() 才能关闭它。
        try {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.warn("关闭委托的 Vertex AI 流式模型失败", e);
        }
    }

    VertexAI vertexAI() {
        return vertexAI;
    }
}
