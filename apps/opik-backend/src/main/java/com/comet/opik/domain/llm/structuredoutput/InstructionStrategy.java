package com.comet.opik.domain.llm.structuredoutput;

import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class InstructionStrategy implements StructuredOutputStrategy {

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.getMapper();
    private static final String INSTRUCTION = """
            IMPORTANT:
            You must respond with ONLY a single valid JSON object that conforms to the structure of the example below.
            Pay attention to the field names and data types (boolean, integer, double).
            Do NOT include any other text, explanation, or markdown formatting.
            The JSON object should look like this:
            %s

            Here are the descriptions for each field:
            %s""";

    @Override
    public ChatRequest.Builder apply(
            @NonNull ChatRequest.Builder chatRequestBuilder,
            @NonNull List<ChatMessage> messages,
            @NonNull List<LlmAsJudgeOutputSchema> schema) {
        if (messages.isEmpty()) {
            return chatRequestBuilder;
        }

        String instruction = "\n\n"
                + INSTRUCTION.formatted(generateJsonExample(schema), generateJsonDescriptions(schema));

        // 创建一个可变副本来操作
        List<ChatMessage> modifiableMessages = new ArrayList<>(messages);

        int lastUserMessageIndex = -1;
        for (int i = modifiableMessages.size() - 1; i >= 0; i--) {
            if (modifiableMessages.get(i) instanceof UserMessage) {
                lastUserMessageIndex = i;
                break;
            }
        }

        if (lastUserMessageIndex != -1) {
            UserMessage userMessage = (UserMessage) modifiableMessages.get(lastUserMessageIndex);
            UserMessage modifiedUserMessage;

            // 检查这是否是一条简单文本消息（原有行为）
            if (userMessage.contents() == null || userMessage.contents().isEmpty()) {
                // 简单文本消息：使用 singleText()
                String newContent = userMessage.singleText() + instruction;
                modifiedUserMessage = UserMessage.from(newContent);
            } else {
                // 多模态消息：提取文本部分、追加指令并重建
                List<Content> originalContents = new ArrayList<>(userMessage.contents());

                // 查找并连接所有文本内容
                String allTextContent = originalContents.stream()
                        .filter(content -> content instanceof TextContent)
                        .map(content -> ((TextContent) content).text())
                        .collect(Collectors.joining("\n"));

                // 将指令追加到文本
                String newTextContent = allTextContent + instruction;

                // 重建消息：保留所有非文本内容，用更新后的文本替换原文本
                List<Content> newContents = new ArrayList<>();
                newContents.add(TextContent.from(newTextContent));

                // 添加所有非文本内容（图片、视频等）
                originalContents.stream()
                        .filter(content -> !(content instanceof TextContent))
                        .forEach(newContents::add);

                modifiedUserMessage = UserMessage.from(newContents);
            }

            // 移除原始用户消息
            modifiableMessages.remove(lastUserMessageIndex);
            // 将修改后的用户消息添加到末尾
            modifiableMessages.add(modifiedUserMessage);

            // 用新的消息列表更新请求构建器
            chatRequestBuilder.messages(modifiableMessages);
        }

        return chatRequestBuilder;
    }

    private String generateJsonExample(List<LlmAsJudgeOutputSchema> schema) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        for (LlmAsJudgeOutputSchema scoreDefinition : schema) {
            ObjectNode scoreNode = root.putObject(scoreDefinition.name());
            switch (scoreDefinition.type()) {
                case BOOLEAN :
                    scoreNode.put("score", true);
                    break;
                case INTEGER :
                    scoreNode.put("score", 1);
                    break;
                case DOUBLE :
                    scoreNode.put("score", 1.0);
                    break;
            }
            scoreNode.put("reason", "A brief explanation for the score.");
        }

        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error generating JSON example", e);
        }
    }

    private String generateJsonDescriptions(List<LlmAsJudgeOutputSchema> schema) {
        return schema.stream()
                .map(scoreDefinition -> String.format(
                        "- %s: %s",
                        scoreDefinition.name(),
                        scoreDefinition.description()))
                .collect(Collectors.joining("\n"));
    }
}
