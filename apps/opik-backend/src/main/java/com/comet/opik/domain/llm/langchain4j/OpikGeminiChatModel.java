package com.comet.opik.domain.llm.langchain4j;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 自定义 Gemini 聊天模型，将 VideoContent 转换为 ImageContent。
 * Gemini 的 API 把视频当作图片处理，因此我们在发送给模型之前先转换视频 URL。
 */
@Slf4j
@RequiredArgsConstructor
public class OpikGeminiChatModel implements ChatModel {

    private final @NonNull ChatModel delegate;

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        // 转换消息：如果任何 UserMessage 含有 VideoContent，则转换为 ImageContent
        List<ChatMessage> convertedMessages = convertMessagesForGemini(chatRequest.messages());

        // 用转换后的消息创建新请求
        ChatRequest convertedRequest = ChatRequest.builder()
                .messages(convertedMessages)
                .parameters(chatRequest.parameters())
                .build();

        return delegate.chat(convertedRequest);
    }

    /**
     * 为 Gemini 转换消息：VideoContent -> ImageContent
     */
    private List<ChatMessage> convertMessagesForGemini(List<ChatMessage> messages) {
        return messages.stream()
                .map(this::convertMessageForGemini)
                .collect(Collectors.toList());
    }

    /**
     * 转换单条消息，处理 UserMessages 中的 VideoContent。
     */
    private ChatMessage convertMessageForGemini(ChatMessage message) {
        // 只有 UserMessage 可以含有 VideoContent
        if (!(message instanceof UserMessage)) {
            return message;
        }

        UserMessage userMessage = (UserMessage) message;

        // 纯文本消息 - 无需转换
        if (userMessage.hasSingleText()) {
            return message;
        }

        // 多内容消息 - 检查是否含有视频
        boolean hasVideo = userMessage.contents().stream()
                .anyMatch(content -> content instanceof VideoContent);

        if (!hasVideo) {
            // 没有视频，原样返回
            return message;
        }

        // 有视频 - 将 VideoContent 转换为 ImageContent
        List<Content> convertedContents = new ArrayList<>();
        for (Content content : userMessage.contents()) {
            if (content instanceof VideoContent videoContent) {
                // Gemini 把视频当作图片处理。一个 Video 要么携带 url，要么携带内联 base64，绝不会两者都有：
                // 由 MinIO 暂存的附件以 base64 形式到达，因此在这里读取 url() 会抛出异常。
                var video = videoContent.video();
                var imageBuilder = Image.builder();
                if (video.url() != null) {
                    var url = video.url().toString();
                    log.debug("正在为 Gemini 将 VideoContent 转换为 ImageContent: {}",
                            url.substring(0, Math.min(50, url.length())));
                    imageBuilder.url(video.url());
                } else if (StringUtils.isNotEmpty(video.base64Data())) {
                    log.debug("正在为 Gemini 将内联 VideoContent 转换为 ImageContent");
                    imageBuilder.base64Data(video.base64Data());
                } else {
                    // 没有可转换的内容；原样透传，而不是构建一个空图片。
                    convertedContents.add(content);
                    continue;
                }
                if (video.mimeType() != null) {
                    imageBuilder.mimeType(video.mimeType());
                }
                convertedContents.add(ImageContent.from(imageBuilder.build()));
            } else {
                // 音频、文件和文本保持不变——丢弃它们会悄然丢失输入
                convertedContents.add(content);
            }
        }

        return UserMessage.from(convertedContents);
    }
}
