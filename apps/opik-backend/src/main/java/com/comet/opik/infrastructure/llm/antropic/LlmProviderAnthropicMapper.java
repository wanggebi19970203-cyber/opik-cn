package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.domain.llm.MessageContentNormalizer;
import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.api.AnthropicImageContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessageContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicRole;
import dev.langchain4j.model.anthropic.internal.api.AnthropicTextContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import dev.langchain4j.model.openai.internal.shared.Usage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Mapper
interface LlmProviderAnthropicMapper {
    LlmProviderAnthropicMapper INSTANCE = Mappers.getMapper(LlmProviderAnthropicMapper.class);

    Logger LOG = LoggerFactory.getLogger(LlmProviderAnthropicMapper.class);

    // Anthropic 要求 max_tokens；当调用方没有设置时应用默认值（Playground 默认
    // 配置、没有显式上限的 SDK）。4096 对应 langchain4j 在 judge 路径上的默认值。
    int DEFAULT_MAX_COMPLETION_TOKENS = 4096;

    @Mapping(source = "response", target = "choices", qualifiedByName = "mapToChoices")
    @Mapping(source = "usage", target = "usage", qualifiedByName = "mapToUsage")
    ChatCompletionResponse toResponse(@NonNull AnthropicCreateMessageResponse response);

    @Mapping(source = "content", target = "message")
    @Mapping(source = "response.stopReason", target = "finishReason")
    ChatCompletionChoice toChoice(@NonNull AnthropicContent content, @NonNull AnthropicCreateMessageResponse response);

    @Mapping(source = "text", target = "content")
    AssistantMessage toAssistantMessage(@NonNull AnthropicContent content);

    @Mapping(expression = "java(request.model())", target = "model")
    @Mapping(expression = "java(Boolean.TRUE.equals(request.stream()))", target = "stream")
    @Mapping(source = "request", target = "temperature", qualifiedByName = "resolveTemperature")
    @Mapping(source = "request", target = "topP", qualifiedByName = "resolveTopP")
    @Mapping(expression = "java(request.stop())", target = "stopSequences")
    @Mapping(source = "request", target = "maxTokens", qualifiedByName = "resolveMaxTokens")
    @Mapping(source = "request", target = "messages", qualifiedByName = "mapToMessages")
    @Mapping(source = "request", target = "system", qualifiedByName = "mapToSystemMessages")
    AnthropicCreateMessageRequest toCreateMessageRequest(@NonNull ChatCompletionRequest request);

    @Named("resolveTemperature")
    default Double resolveTemperature(@NonNull ChatCompletionRequest request) {
        return samplingParamsAllowed(request) ? request.temperature() : null;
    }

    @Named("resolveTopP")
    default Double resolveTopP(@NonNull ChatCompletionRequest request) {
        if (!samplingParamsAllowed(request)) {
            return null;
        }
        // Anthropic 建议不要同时发送 temperature 和 top_p；两者都设置时以 temperature 为准。
        return request.temperature() != null ? null : request.topP();
    }

    /**
     * Anthropic 对自适应思考模型（claude-sonnet-5、claude-opus-4-7/4-8）以及
     * 请求启用了扩展思考的任何情况，会以 400 拒绝采样参数（temperature/top_p）。
     * 在服务端门控它们，这样 API 创建的请求 —— 它们绕过 OPIK-6244 的 FE 净化器 —— 就不会
     * 失败。与 {@code AnthropicClientGenerator} 中的 judge 路径逻辑一致。
     */
    private boolean samplingParamsAllowed(ChatCompletionRequest request) {
        return AnthropicModelName.supportsSamplingParams(request.model()) && !thinkingEnabled(request);
    }

    /**
     * 仅当请求的 {@code custom_parameters.thinking.type} 是除 {@code "disabled"} 以外的
     * 显式、非空值时，扩展思考才算启用 —— 因此 {@code "enabled"}、{@code "adaptive"} 以及任何
     * 未来的类型都会关闭采样参数门控，而缺失/空白的类型（或缺块）则保持不变。
     */
    private boolean thinkingEnabled(ChatCompletionRequest request) {
        if (request.customParameters() == null
                || !(request.customParameters().get("thinking") instanceof Map<?, ?> thinking)) {
            return false;
        }
        return thinking.get("type") instanceof String type
                && StringUtils.isNotBlank(type)
                && !"disabled".equalsIgnoreCase(type);
    }

    @Named("resolveMaxTokens")
    default Integer resolveMaxTokens(@NonNull ChatCompletionRequest request) {
        if (request.maxCompletionTokens() != null) {
            return request.maxCompletionTokens();
        }
        LOG.info("模型 '{}' 的 Anthropic 请求没有 maxCompletionTokens；默认使用 {}",
                request.model(), DEFAULT_MAX_COMPLETION_TOKENS);
        return DEFAULT_MAX_COMPLETION_TOKENS;
    }

    @Named("mapToChoices")
    default List<ChatCompletionChoice> mapToChoices(@NonNull AnthropicCreateMessageResponse response) {
        if (response.content == null || response.content.isEmpty()) {
            return List.of();
        }
        return response.content.stream().map(content -> toChoice(content, response)).toList();
    }

    @Named("mapToUsage")
    default Usage mapToUsage(AnthropicUsage usage) {
        if (usage == null) {
            return null;
        }

        return Usage.builder()
                .promptTokens(usage.inputTokens)
                .completionTokens(usage.outputTokens)
                .totalTokens(usage.inputTokens + usage.outputTokens)
                .build();
    }

    @Named("mapToMessages")
    default List<AnthropicMessage> mapToMessages(@NonNull ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> List.of(Role.ASSISTANT, Role.USER).contains(message.role()))
                .map(this::mapToAnthropicMessage).toList();
    }

    @Named("mapToSystemMessages")
    default List<AnthropicTextContent> mapToSystemMessages(@NonNull ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == Role.SYSTEM)
                .map(this::mapToSystemMessage).toList();
    }

    default AnthropicMessage mapToAnthropicMessage(@NonNull Message message) {
        return switch (message) {
            case AssistantMessage assistantMessage -> AnthropicMessage.builder()
                    .role(AnthropicRole.ASSISTANT)
                    .content(List.of(new AnthropicTextContent(assistantMessage.content())))
                    .build();
            case OpikUserMessage opikUserMessage -> AnthropicMessage.builder()
                    .role(AnthropicRole.USER)
                    .content(toAnthropicMessageContents(opikUserMessage.content()))
                    .build();
            case UserMessage userMessage -> AnthropicMessage.builder()
                    .role(AnthropicRole.USER)
                    .content(List.of(toAnthropicMessageContent(userMessage.content())))
                    .build();
            default -> throw new BadRequestException("unexpected message role: " + message.role());
        };
    }

    /**
     * 将 OpikUserMessage 内容转换为 Anthropic 消息内容列表。
     * 同时处理字符串内容和结构化的多模态内容（文本、图片等）。
     */
    default List<AnthropicMessageContent> toAnthropicMessageContents(@NonNull Object rawContent) {
        // 如果是字符串，返回单个文本内容（若非空）
        if (rawContent instanceof String stringContent) {
            if (StringUtils.isNotBlank(stringContent)) {
                return List.of(new AnthropicTextContent(stringContent));
            }
            // 空字符串 - 返回空列表（Anthropic 会拒绝空文本块）
            return List.of();
        }

        // 如果是 OpikContent 列表，转换每一项
        if (rawContent instanceof List<?> contentList) {
            return contentList.stream()
                    .filter(OpikContent.class::isInstance)
                    .map(OpikContent.class::cast)
                    .map(opikContent -> switch (opikContent.type()) {
                        case TEXT -> new AnthropicTextContent(opikContent.text());
                        case IMAGE_URL -> AnthropicImageContent.fromUrl(opikContent.imageUrl().getUrl());
                        case VIDEO_URL -> new AnthropicTextContent("[Video: " + opikContent.videoUrl().url() + "]");
                        default -> null;
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }

        // 回退：扁平化为字符串
        var content = MessageContentNormalizer.flattenContent(rawContent);
        if (StringUtils.isNotBlank(content)) {
            return List.of(new AnthropicTextContent(content));
        }
        return List.of();
    }

    default AnthropicMessageContent toAnthropicMessageContent(@NonNull Object rawContent) {
        var content = MessageContentNormalizer.flattenContent(rawContent);
        return new AnthropicTextContent(content);
    }

    default AnthropicTextContent mapToSystemMessage(@NonNull Message message) {
        if (message.role() != Role.SYSTEM) {
            throw new BadRequestException("expecting only system role, got: " + message.role());
        }

        return new AnthropicTextContent(((SystemMessage) message).content());
    }
}
