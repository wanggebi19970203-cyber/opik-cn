package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import dev.langchain4j.data.message.ChatMessageType;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Mapper
interface AutomationModelEvaluatorMapper {

    AutomationModelEvaluatorMapper INSTANCE = Mappers.getMapper(AutomationModelEvaluatorMapper.class);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorLlmAsJudge map(LlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorTraceThreadLlmAsJudge map(TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorUserDefinedMetricPython map(UserDefinedMetricPythonAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython map(
            TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorSpanLlmAsJudge map(SpanLlmAsJudgeAutomationRuleEvaluatorModel model);

    @Mapping(target = "id", expression = "java(model.id())")
    @Mapping(target = "projectId", expression = "java(model.projectId())")
    @Mapping(target = "projectName", expression = "java(model.projectName())")
    @Mapping(target = "projects", expression = "java(model.projects())")
    @Mapping(target = "name", expression = "java(model.name())")
    @Mapping(target = "samplingRate", expression = "java(model.samplingRate())")
    @Mapping(target = "enabled", expression = "java(model.enabled())")
    @Mapping(target = "triggerScope", expression = "java(model.triggerScope())")
    @Mapping(target = "createdAt", expression = "java(model.createdAt())")
    @Mapping(target = "createdBy", expression = "java(model.createdBy())")
    @Mapping(target = "lastUpdatedAt", expression = "java(model.lastUpdatedAt())")
    @Mapping(target = "lastUpdatedBy", expression = "java(model.lastUpdatedBy())")
    @Mapping(target = "code", expression = "java(map(model.code()))")
    @Mapping(target = "filters", expression = "java(mapFilters(model))")
    AutomationRuleEvaluatorSpanUserDefinedMetricPython map(
            SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel model);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    LlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorLlmAsJudge dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorTraceThreadLlmAsJudge dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    UserDefinedMetricPythonAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorUserDefinedMetricPython dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel map(
            AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    SpanLlmAsJudgeAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorSpanLlmAsJudge dto);

    @Mapping(target = "filters", expression = "java(map(dto.getFilters()))")
    SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel map(AutomationRuleEvaluatorSpanUserDefinedMetricPython dto);

    AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode map(LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode detail);

    AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode map(
            TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode code);

    AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode map(
            UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode code);

    AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode map(
            TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode code);

    AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode map(
            SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode code);

    AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode map(
            SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode code);

    LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode map(AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode code);

    TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode map(
            AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode code);

    UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode map(
            AutomationRuleEvaluatorUserDefinedMetricPython.UserDefinedMetricPythonCode code);

    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode map(
            AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TraceThreadUserDefinedMetricPythonCode code);

    SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode map(
            AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode code);

    SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode map(
            AutomationRuleEvaluatorSpanUserDefinedMetricPython.SpanUserDefinedMetricPythonCode code);

    default <T extends Filter> List<T> mapFilters(AutomationRuleEvaluatorModel<?> model) {
        if (StringUtils.isBlank(model.filters())) {
            return List.of();
        }

        return switch (model) {
            case LlmAsJudgeAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), TraceFilter.LIST_TYPE_REFERENCE);
            case UserDefinedMetricPythonAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), TraceFilter.LIST_TYPE_REFERENCE);
            case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), TraceThreadFilter.LIST_TYPE_REFERENCE);
            case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), TraceThreadFilter.LIST_TYPE_REFERENCE);
            case SpanLlmAsJudgeAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), SpanFilter.LIST_TYPE_REFERENCE);
            case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel ignored ->
                (List<T>) JsonUtils.readValue(model.filters(), SpanFilter.LIST_TYPE_REFERENCE);
        };
    }

    default String map(List<? extends Filter> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        return JsonUtils.writeValueAsString(filters);
    }

    /**
     * 将 LlmAsJudgeMessage 映射为 LlmAsJudgeCodeMessage 以用于数据库存储。
     * 处理既可以是 String（content 字段）也可以是 List（contentArray 字段）的内容。
     */
    default LlmAsJudgeCodeMessage map(LlmAsJudgeMessage message) {
        if (message == null) {
            return null;
        }

        String contentString;
        if (message.isStringContent()) {
            // 简单字符串内容
            contentString = message.content();
        } else if (message.isStructuredContent()) {
            // 结构化内容（数组），序列化为 JSON
            contentString = JsonUtils.writeValueAsString(message.contentArray());
        } else {
            // 两者都为空
            contentString = null;
        }

        return new LlmAsJudgeCodeMessage(message.role().toString(), contentString);
    }

    /**
     * 将 LlmAsJudgeMessage 列表映射为 LlmAsJudgeCodeMessage 列表。
     */
    default List<LlmAsJudgeCodeMessage> mapMessages(List<LlmAsJudgeMessage> messages) {
        if (messages == null) {
            return null;
        }
        return messages.stream()
                .map(this::map)
                .collect(Collectors.toList());
    }

    /**
     * 将数据库中的 LlmAsJudgeCodeMessage 映射为 API 的 LlmAsJudgeMessage。
     * 处理内容字段——它可能以普通字符串或 JSON 数组形式存储。
     */
    default LlmAsJudgeMessage map(LlmAsJudgeCodeMessage codeMessage) {
        if (codeMessage == null) {
            return null;
        }

        String contentString = codeMessage.content();
        ChatMessageType role = ChatMessageType.valueOf(codeMessage.role());

        if (contentString == null) {
            return LlmAsJudgeMessage.builder()
                    .role(role)
                    .build();
        } else if (contentString.trim().startsWith("[")) {
            // 这是一个 JSON 数组，反序列化为 List<LlmAsJudgeMessageContent>
            try {
                // 先反序列化为原始列表，以处理潜在的 LinkedHashMap 问题
                List<?> rawList = JsonUtils.getMapper().readValue(
                        contentString,
                        JsonUtils.getMapper().getTypeFactory().constructCollectionType(
                                List.class,
                                Object.class));

                // 将每个元素转换为 LlmAsJudgeMessageContent
                List<LlmAsJudgeMessageContent> contentArray = rawList.stream()
                        .map(this::convertToMessageContent)
                        .toList();

                return LlmAsJudgeMessage.builder()
                        .role(role)
                        .contentArray(contentArray)
                        .build();
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to deserialize message content from JSON", e);
            }
        } else {
            // 这是一个普通字符串
            return LlmAsJudgeMessage.builder()
                    .role(role)
                    .content(contentString)
                    .build();
        }
    }

    /**
     * 将反序列化的对象（LlmAsJudgeMessageContent 或 Map）转换为 LlmAsJudgeMessageContent。
     * 这处理了 Jackson 将 JSON 对象反序列化为 LinkedHashMap 的情况。
     */
    private LlmAsJudgeMessageContent convertToMessageContent(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent content) {
            return content;
        }

        if (obj instanceof Map<?, ?> map) {
            return LlmAsJudgeMessageContent.builder()
                    .type((String) map.get("type"))
                    .text((String) map.get("text"))
                    .imageUrl(map.get("image_url") != null
                            ? convertToImageUrl(map.get("image_url"))
                            : null)
                    .videoUrl(map.get("video_url") != null
                            ? convertToVideoUrl(map.get("video_url"))
                            : null)
                    .audioUrl(map.get("audio_url") != null
                            ? convertToAudioUrl(map.get("audio_url"))
                            : null)
                    .build();
        }

        throw new IllegalStateException("Unexpected content part type: " + obj.getClass());
    }

    private LlmAsJudgeMessageContent.ImageUrl convertToImageUrl(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent.ImageUrl imageUrl) {
            return imageUrl;
        }

        if (obj instanceof Map<?, ?> map) {
            return LlmAsJudgeMessageContent.ImageUrl.builder()
                    .url((String) map.get("url"))
                    .detail((String) map.get("detail"))
                    .build();
        }

        throw new IllegalStateException("Unexpected image_url type: " + obj.getClass());
    }

    private LlmAsJudgeMessageContent.VideoUrl convertToVideoUrl(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent.VideoUrl videoUrl) {
            return videoUrl;
        }

        if (obj instanceof Map<?, ?> map) {
            return LlmAsJudgeMessageContent.VideoUrl.builder()
                    .url((String) map.get("url"))
                    .build();
        }

        throw new IllegalStateException("Unexpected video_url type: " + obj.getClass());
    }

    private LlmAsJudgeMessageContent.AudioUrl convertToAudioUrl(Object obj) {
        if (obj instanceof LlmAsJudgeMessageContent.AudioUrl audioUrl) {
            return audioUrl;
        }

        if (obj instanceof Map<?, ?> map) {
            return LlmAsJudgeMessageContent.AudioUrl.builder()
                    .url((String) map.get("url"))
                    .build();
        }

        throw new IllegalStateException("Unexpected audio_url type: " + obj.getClass());
    }

    /**
     * 将 LlmAsJudgeCodeMessage 列表映射为 LlmAsJudgeMessage 列表。
     */
    default List<LlmAsJudgeMessage> mapCodeMessages(List<LlmAsJudgeCodeMessage> codeMessages) {
        if (codeMessages == null) {
            return null;
        }
        return codeMessages.stream()
                .map(this::map)
                .collect(Collectors.toList());
    }
}
