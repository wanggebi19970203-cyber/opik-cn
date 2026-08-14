package com.comet.opik.domain.llm;

import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ContentType;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@UtilityClass
public class MessageContentNormalizer {

    public static final String IMAGE_PLACEHOLDER_START = "<<<image>>>";
    public static final String IMAGE_PLACEHOLDER_END = "<<</image>>>";
    private static final Pattern IMAGE_PLACEHOLDER_PATTERN = Pattern.compile(
            Pattern.quote(IMAGE_PLACEHOLDER_START) + "(.*?)" + Pattern.quote(IMAGE_PLACEHOLDER_END),
            Pattern.DOTALL);

    public ChatCompletionRequest normalizeRequest(@NonNull ChatCompletionRequest request) {
        boolean allowStructuredContent = ModelCapabilities.supportsVision(request.model());
        return normalizeRequest(request, allowStructuredContent);
    }

    private ChatCompletionRequest normalizeRequest(ChatCompletionRequest request, boolean allowStructuredContent) {
        if (CollectionUtils.isEmpty(request.messages())) {
            return request;
        }

        if (allowStructuredContent) {
            // 对于支持视觉的模型：把带有图片标签的字符串内容展开为结构化内容
            return expandImagePlaceholders(request);
        }

        // 对于非视觉模型：把结构化内容扁平化为字符串
        var needsNormalization = request.messages().stream()
                .anyMatch(message -> (message instanceof UserMessage userMessage
                        && !(userMessage.content() instanceof String))
                        || (message instanceof OpikUserMessage opikUserMessage
                                && !(opikUserMessage.content() instanceof String)));

        if (!needsNormalization) {
            return request;
        }

        var normalizedMessages = new ArrayList<Message>(request.messages().size());
        for (var message : request.messages()) {
            if (message instanceof OpikUserMessage opikUserMessage) {
                normalizedMessages.add(normalizeOpikUserMessage(opikUserMessage));
            } else if (message instanceof UserMessage userMessage) {
                normalizedMessages.add(normalizeUserMessage(userMessage));
            } else {
                normalizedMessages.add(message);
            }
        }

        var builder = ChatCompletionRequest.builder().from(request);
        builder.messages(normalizedMessages);
        return builder.build();
    }

    /**
     * 对于支持视觉的模型：把带有图片占位符的字符串内容转换为结构化内容。
     * 示例："text\n<<<image>>>url<<</image>>>" 变为
     * [{type: "text", text: "text"}, {type: "image_url", image_url: {url: "url"}}]
     */
    private ChatCompletionRequest expandImagePlaceholders(ChatCompletionRequest request) {
        var needsExpansion = request.messages().stream()
                .anyMatch(message -> (message instanceof UserMessage userMessage
                        && userMessage.content() instanceof String content
                        && content.contains(IMAGE_PLACEHOLDER_START))
                        || (message instanceof OpikUserMessage opikUserMessage
                                && opikUserMessage.content() instanceof String opikContent
                                && opikContent.contains(IMAGE_PLACEHOLDER_START)));

        if (!needsExpansion) {
            return request;
        }

        var expandedMessages = new ArrayList<Message>(request.messages().size());
        for (var message : request.messages()) {
            if (message instanceof OpikUserMessage opikUserMessage
                    && opikUserMessage.content() instanceof String content) {
                expandedMessages.add(expandOpikUserMessage(opikUserMessage, content));
            } else if (message instanceof UserMessage userMessage && userMessage.content() instanceof String content) {
                expandedMessages.add(expandUserMessage(userMessage, content));
            } else {
                expandedMessages.add(message);
            }
        }

        var builder = ChatCompletionRequest.builder().from(request);
        builder.messages(expandedMessages);
        return builder.build();
    }

    /**
     * 把包含图片占位符的字符串内容的 UserMessage 展开为结构化内容。
     * 解析图片占位符并创建一个 Content 对象列表（text 和 image_url）。
     */
    private UserMessage expandUserMessage(UserMessage userMessage, String content) {
        var matcher = IMAGE_PLACEHOLDER_PATTERN.matcher(content);

        if (!matcher.find()) {
            // 未找到图片占位符，原样返回
            return userMessage;
        }

        // 重置匹配器以从头开始
        matcher.reset();

        var contentList = new ArrayList<Content>();
        var lastIndex = 0;

        while (matcher.find()) {
            // 在图片占位符之前添加文本内容
            if (matcher.start() > lastIndex) {
                var textSegment = content.substring(lastIndex, matcher.start());
                appendTextContent(contentList, textSegment);
            }

            // 提取并添加图片 URL
            var url = matcher.group(1).trim();
            if (!url.isEmpty()) {
                // 防御性处理：对每一个到达聊天补全的图片 URL 都执行，无论其来源如何。
                // 模板渲染本身不再转义（OPIK-7354），因此现在实体只会来自已经以转义形式存储的 URL。
                var unescapedUrl = StringEscapeUtils.unescapeHtml4(url);
                contentList.add(Content.builder()
                        .type(ContentType.IMAGE_URL)
                        .imageUrl(ImageUrl.builder().url(unescapedUrl).build())
                        .build());
            }

            lastIndex = matcher.end();
        }

        // 添加最后一个图片占位符之后的任何剩余文本
        if (lastIndex < content.length()) {
            var trailingText = content.substring(lastIndex);
            appendTextContent(contentList, trailingText);
        }

        // 构建展开后的 UserMessage
        var builder = UserMessage.builder();
        if (userMessage.name() != null) {
            builder.name(userMessage.name());
        }
        builder.content(contentList);

        return builder.build();
    }

    /**
     * 如果文本不为空白，则将其追加到内容列表中。
     */
    private void appendTextContent(List<Content> contentList, String textSegment) {
        if (StringUtils.isNotBlank(textSegment)) {
            contentList.add(Content.builder()
                    .type(ContentType.TEXT)
                    .text(textSegment)
                    .build());
        }
    }

    public static String flattenContent(@NonNull Object rawContent) {
        if (rawContent instanceof String str) {
            return str;
        }

        if (rawContent instanceof List<?> list) {
            var builder = new StringBuilder();
            for (var item : list) {
                if (item instanceof Content content) {
                    builder.append(renderContent(content));
                }
            }
            return builder.toString().trim();
        }

        return String.valueOf(rawContent);
    }

    private Message normalizeUserMessage(UserMessage userMessage) {
        var flattened = flattenContent(userMessage.content());
        var builder = UserMessage.builder();

        if (userMessage.name() != null) {
            builder.name(userMessage.name());
        }

        builder.content(flattened);
        return builder.build();
    }

    /**
     * 通过把结构化内容扁平化为字符串来规范化 OpikUserMessage。
     */
    private Message normalizeOpikUserMessage(OpikUserMessage opikUserMessage) {
        // 如果内容已经是字符串，原样返回
        if (opikUserMessage.content() instanceof String) {
            return opikUserMessage;
        }

        // 如果内容是列表，将其扁平化为字符串
        var flattened = flattenContent(opikUserMessage.content());
        return OpikUserMessage.builder()
                .name(opikUserMessage.name())
                .content(flattened)
                .build();
    }

    /**
     * 把包含图片占位符的字符串内容的 OpikUserMessage 展开为结构化内容。
     */
    private OpikUserMessage expandOpikUserMessage(OpikUserMessage opikUserMessage, String content) {
        var matcher = IMAGE_PLACEHOLDER_PATTERN.matcher(content);

        if (!matcher.find()) {
            // 未找到图片占位符，原样返回
            return opikUserMessage;
        }

        // 重置匹配器以从头开始
        matcher.reset();

        var builder = OpikUserMessage.builder();
        if (opikUserMessage.name() != null) {
            builder.name(opikUserMessage.name());
        }

        var lastIndex = 0;

        while (matcher.find()) {
            // 在图片占位符之前添加文本内容
            if (matcher.start() > lastIndex) {
                var textSegment = content.substring(lastIndex, matcher.start());
                if (StringUtils.isNotBlank(textSegment)) {
                    builder.addText(textSegment);
                }
            }

            // 提取并添加图片 URL
            var url = matcher.group(1).trim();
            if (!url.isEmpty()) {
                var unescapedUrl = StringEscapeUtils.unescapeHtml4(url);
                builder.addImageUrl(unescapedUrl);
            }

            lastIndex = matcher.end();
        }

        // 添加最后一个图片占位符之后的任何剩余文本
        if (lastIndex < content.length()) {
            var trailingText = content.substring(lastIndex);
            if (StringUtils.isNotBlank(trailingText)) {
                builder.addText(trailingText);
            }
        }

        return builder.build();
    }

    private String renderContent(Content content) {
        var type = content.type();
        if (type == null) {
            return "";
        }

        var normalized = type.name().toLowerCase();
        return switch (normalized) {
            case "text" -> StringUtils.isBlank(content.text()) ? "" : content.text();
            case "image_url" -> renderImagePlaceholder(content.imageUrl());
            default -> {
                log.warn("规范化期间跳过未知内容类型: '{}'", normalized);
                yield "";
            }
        };
    }

    private String renderImagePlaceholder(ImageUrl imageUrl) {
        if (imageUrl == null || StringUtils.isBlank(imageUrl.getUrl())) {
            return "";
        }

        return String.format("%s%s%s", IMAGE_PLACEHOLDER_START, imageUrl.getUrl(), IMAGE_PLACEHOLDER_END);
    }
}
