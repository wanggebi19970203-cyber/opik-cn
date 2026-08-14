package com.comet.opik.infrastructure.llm.vertexai;

import com.google.cloud.vertexai.VertexAI;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

// 持有 VertexAI 并关闭它；langchain4j 模型做不到（它的双参构造函数把句柄置空，因此它的 close() 是空操作）。
@Slf4j
class CloseableVertexAiChatModel implements ChatModel, AutoCloseable {

    private final @NonNull ChatModel delegate;
    private final @NonNull VertexAI vertexAI;

    CloseableVertexAiChatModel(@NonNull ChatModel delegate, @NonNull VertexAI vertexAI) {
        this.delegate = delegate;
        this.vertexAI = vertexAI;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        return delegate.chat(chatRequest);
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
            log.warn("关闭 Vertex AI 客户端失败", e);
        }
        // 对称性：今天是无操作，但委托对象是唯一能释放它可能拥有的资源的东西。
        try {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception e) {
            log.warn("关闭委托的 Vertex AI 模型失败", e);
        }
    }

    VertexAI vertexAI() {
        return vertexAI;
    }
}
