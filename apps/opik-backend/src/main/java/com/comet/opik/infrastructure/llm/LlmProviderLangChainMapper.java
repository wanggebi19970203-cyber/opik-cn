package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmErrorMessage;
import com.comet.opik.infrastructure.llm.gemini.GeminiErrorObject;
import com.comet.opik.infrastructure.llm.openai.OpenAiErrorMessage;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterErrorMessage;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.video.Video;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import dev.langchain4j.model.openai.internal.shared.Usage;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.dropwizard.util.Throwables;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;

@Mapper
public interface LlmProviderLangChainMapper {
    Logger log = LoggerFactory.getLogger(LlmProviderLangChainMapper.class);

    String ERR_UNEXPECTED_ROLE = "unexpected role '%s'";
    String ERR_ROLE_MSG_TYPE_MISMATCH = "role and message instance are not matching, role: '%s', instance: '%s'";

    LlmProviderLangChainMapper INSTANCE = Mappers.getMapper(LlmProviderLangChainMapper.class);
    String CANNOT_BE_NULL_OR_EMPTY = "Message content cannot be null or empty";

    default ChatMessage toChatMessage(@NonNull Message message) {
        if (!List.of(Role.ASSISTANT, Role.USER, Role.SYSTEM).contains(message.role())) {
            throw new BadRequestException(ERR_UNEXPECTED_ROLE.formatted(message.role()));
        }

        switch (message.role()) {
            case ASSISTANT -> {
                if (message instanceof AssistantMessage assistantMessage) {
                    validateMessageContent(assistantMessage.content());
                    return AiMessage.from(assistantMessage.content());
                }
            }
            case USER -> {
                if (message instanceof UserMessage userMessage) {
                    validateMessageContent(userMessage.content().toString());
                    return dev.langchain4j.data.message.UserMessage.from(userMessage.content().toString());
                } else if (message instanceof OpikUserMessage opikUserMessage) {
                    return convertOpikUserMessage(opikUserMessage);
                }
            }
            case SYSTEM -> {
                if (message instanceof SystemMessage systemMessage) {
                    validateMessageContent(systemMessage.content());
                    return dev.langchain4j.data.message.SystemMessage.from(systemMessage.content());
                }
            }
        }

        throw new BadRequestException(ERR_ROLE_MSG_TYPE_MISMATCH.formatted(message.role(),
                message.getClass().getSimpleName()));
    }

    private void validateMessageContent(String content) {
        if (StringUtils.isBlank(content)) {
            throw new BadRequestException(CANNOT_BE_NULL_OR_EMPTY);
        }
    }

    /**
     * 将 OpikUserMessage 转换为公共 API UserMessage。
     * OpikUserMessage 支持多模态内容（文本、图片、视频等），
     * 其 content 可以是 String 或 List&lt;OpikContent&gt;。
     */
    private dev.langchain4j.data.message.UserMessage convertOpikUserMessage(OpikUserMessage opikUserMessage) {
        if (opikUserMessage.content() instanceof String stringContent) {
            validateMessageContent(stringContent);
            return dev.langchain4j.data.message.UserMessage.from(stringContent);
        } else if (opikUserMessage.content() instanceof List<?> contentList) {
            // 将 OpikContent 列表转换为公共 API Content 列表
            List<Content> publicApiContents = new java.util.ArrayList<>();
            for (Object item : contentList) {
                if (item instanceof OpikContent opikContent) {
                    publicApiContents.add(convertOpikContent(opikContent));
                }
            }
            return dev.langchain4j.data.message.UserMessage.from(publicApiContents);
        }
        throw new BadRequestException("Invalid OpikUserMessage content type");
    }

    /**
     * 将 OpikContent 转换为公共 API Content。
     */
    private Content convertOpikContent(OpikContent opikContent) {
        return switch (opikContent.type()) {
            case TEXT -> TextContent.from(opikContent.text());
            case IMAGE_URL -> {
                if (opikContent.imageUrl() != null) {
                    yield ImageContent.from(opikContent.imageUrl().getUrl());
                }
                throw new BadRequestException("Image URL is null");
            }
            case VIDEO_URL -> {
                if (opikContent.videoUrl() != null) {
                    String videoUrl = opikContent.videoUrl().url();
                    var videoBuilder = Video.builder()
                            .url(URI.create(videoUrl));

                    // 如果提供了显式 mimeType 就使用它
                    String mimeType = opikContent.videoUrl().mimeType();

                    // 仅当未提供 mimeType 且 URL 没有文件扩展名时才检测 MIME 类型
                    // （LangChain4j 可以从扩展名自动检测 MIME 类型）
                    if (mimeType == null && !VideoMimeTypeUtils.hasVideoFileExtension(videoUrl)) {
                        mimeType = VideoMimeTypeUtils.detectMimeTypeFromHttpHead(videoUrl);
                    }

                    if (mimeType != null) {
                        videoBuilder.mimeType(mimeType);
                        log.debug("设置 mimeType '{}' 用于视频 URL: '{}'", mimeType,
                                videoUrl.substring(0, Math.min(60, videoUrl.length())));
                    }
                    yield new VideoContent(videoBuilder.build());
                }
                throw new BadRequestException("Video URL is null");
            }
            case AUDIO_URL -> {
                if (opikContent.audioUrl() != null) {
                    yield AudioContent.from(opikContent.audioUrl().url());
                }
                throw new BadRequestException("Audio URL is null");
            }
            case AUDIO -> throw new BadRequestException("Audio content not yet supported in conversion");
            case FILE -> throw new BadRequestException("File content not yet supported in conversion");
        };
    }

    @Mapping(expression = "java(request.model())", target = "model")
    @Mapping(source = "response", target = "choices", qualifiedByName = "mapToChoices")
    @Mapping(source = "response", target = "usage", qualifiedByName = "mapToUsage")
    @Mapping(source = "response", target = "id", qualifiedByName = "mapToId")
    ChatCompletionResponse toChatCompletionResponse(
            @NonNull ChatCompletionRequest request, @NonNull ChatResponse response);

    @Named("mapToChoices")
    default List<ChatCompletionChoice> mapToChoices(@NonNull ChatResponse response) {
        return List.of(ChatCompletionChoice.builder()
                .message(AssistantMessage.builder().content(response.aiMessage().text()).build())
                .build());
    }

    @Named("mapToId")
    default String mapToId(@NonNull ChatResponse response) {
        return Optional.ofNullable(response.metadata())
                .map(ChatResponseMetadata::id)
                .orElse(null);
    }

    @Named("mapToUsage")
    default Usage mapToUsage(@NonNull ChatResponse response) {
        return Usage.builder()
                .promptTokens(response.tokenUsage().inputTokenCount())
                .completionTokens(response.tokenUsage().outputTokenCount())
                .totalTokens(response.tokenUsage().totalTokenCount())
                .build();
    }

    default List<ChatMessage> mapMessages(ChatCompletionRequest request) {
        return request.messages().stream().map(this::toChatMessage).toList();
    }

    default Optional<ErrorMessage> getGeminiErrorObject(@NonNull Throwable throwable, @NonNull Logger log) {
        return getErrorMessage(throwable, log, GeminiErrorObject.class);
    }

    default Optional<ErrorMessage> getCustomLlmErrorObject(@NonNull Throwable throwable, @NonNull Logger log) {
        return getErrorMessage(throwable, log, CustomLlmErrorMessage.class);
    }

    private <E, T extends LlmProviderError<E>> Optional<ErrorMessage> getErrorMessage(Throwable throwable, Logger log,
            Class<T> errorType) {
        Optional<String> errorJson = extractErrorJson(throwable);

        String failToGetErrorMessage = "无法解析 %s 消息".formatted(errorType.getSimpleName());

        if (errorJson.isEmpty()) {
            log.warn(failToGetErrorMessage, throwable);
            return Optional.empty();
        }

        return parseError(log, errorJson.get(), errorType).map(LlmProviderError::toErrorMessage);
    }

    private <E, T extends LlmProviderError<E>> Optional<T> parseError(Logger log, String jsonPart, Class<T> errorType) {

        String failToGetErrorMessage = "无法解析 %s 消息".formatted(errorType.getSimpleName());

        try {
            var error = JsonUtils.readValue(jsonPart, errorType);
            if (error.error() == null) {
                return Optional.empty();
            }
            return Optional.of(error);
        } catch (UncheckedIOException e) {
            log.warn(failToGetErrorMessage, e);
            return Optional.empty();
        }
    }

    private boolean findError(Throwable t) {
        return t.getMessage() != null && t.getMessage().contains("{");
    }

    private Optional<String> extractErrorJson(Throwable throwable) {
        return Throwables.findThrowableInChain(this::findError, throwable)
                .map(Throwable::getMessage)
                .map(message -> message.substring(message.indexOf('{')));
    }

    /**
     * OpenRouter 上报数值型 {@code error.code}（由 {@link OpenRouterErrorMessage} 映射）；
     * OpenAI 兼容的 provider 上报字符串型 {@code error.code}（由
     * {@link OpenAiErrorMessage} 映射）。仅当 code 为数值型时返回 {@code true}。
     */
    private boolean hasNumericErrorCode(String errorJson) {
        try {
            return JsonUtils.getJsonNodeFromString(errorJson).path("error").path("code").isNumber();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 将上游 LLM 错误负载解析为 {@link ErrorMessage}。OpenRouter 和 OpenAI 共享
     * 相同的错误信封，但在 {@code code} 类型上不同 —— OpenRouter 是数值型 HTTP 状态，
     * OpenAI 是字符串型 code。仅当负载确实携带数值型 code 时才尝试 OpenRouter 模型，
     * 这样字符串型 code 的负载绝不会经过 OpenRouter 的 {@code Integer code}
     * （否则每次 provider 出错都会抛出并记录一条 {@code InvalidFormatException}）。OpenAI
     * 是所有其他情况的回退。顺序绝不能无条件对调：OpenAI 会把
     * OpenRouter 的数值型 code 静默地强转进它的 String 字段，并把状态降级为 500。
     */
    default Optional<ErrorMessage> getErrorObject(@NonNull Throwable throwable, @NonNull Logger log) {

        if (extractErrorJson(throwable).filter(this::hasNumericErrorCode).isPresent()) {
            Optional<ErrorMessage> openRouterError = getErrorMessage(throwable, log, OpenRouterErrorMessage.class);

            if (openRouterError.isPresent()) {
                return openRouterError;
            }
        }

        return getErrorMessage(throwable, log, OpenAiErrorMessage.class);
    }
}
