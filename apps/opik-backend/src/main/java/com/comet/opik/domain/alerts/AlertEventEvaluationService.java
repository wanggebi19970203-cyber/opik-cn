package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.Guardrail;
import com.comet.opik.api.events.webhooks.AlertEvent;
import com.comet.opik.domain.AlertService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AlertEventEvaluationService {

    private final @NonNull AlertService alertService;
    private final @NonNull AlertBucketService alertBucketService;
    private final @NonNull IdGenerator idGenerator;

    public void evaluateAlertEvent(@NonNull AlertEvent alertEvent) {
        log.debug("评估告警事件 {}", alertEvent);
        alertService.findAllByWorkspaceAndEventTypes(alertEvent.workspaceId(), Set.of(alertEvent.eventType()))
                .forEach(alert -> {
                    if (isValidForAlert(alertEvent, alert)) {
                        log.debug("告警 {} 匹配事件 {}", alert.id(), alertEvent);

                        String eventId = idGenerator.generateId().toString();
                        alertBucketService
                                .addEventToBucket(alert.id(), alertEvent.workspaceId(), alertEvent.workspaceName(),
                                        alertEvent.eventType(), eventId,
                                        JsonUtils.writeValueAsString(alertEvent.payload()),
                                        alertEvent.userName())
                                .block();
                    }
                });

    }

    private boolean isValidForAlert(AlertEvent alertEvent, Alert alert) {
        return switch (alertEvent.eventType()) {
            case PROMPT_CREATED, PROMPT_COMMITTED, PROMPT_DELETED, EXPERIMENT_FINISHED ->
                isWithinProjectScope(alertEvent, alert);
            case TRACE_GUARDRAILS_TRIGGERED ->
                isWithinProjectScope(alertEvent, alert) && matchesGuardrailTypeFilter(alertEvent, alert);
            default -> false;
        };
    }

    /**
     * 将防护规则告警限制到其触发器上配置的防护规则类型。只要任一匹配事件类型的
     * 触发器接受该事件，告警就会触发：没有 {@code filter:guardrail_type} 配置的
     * 触发器接受任意防护规则类型；带过滤的触发器只接受其配置的类型（按 OR 合并）。
     * 按触发器逐个评估，这样未过滤的触发器不会被兄弟的过滤触发器所收窄。
     */
    private boolean matchesGuardrailTypeFilter(AlertEvent alertEvent, Alert alert) {
        List<AlertTrigger> guardrailTriggers = CollectionUtils.emptyIfNull(alert.triggers()).stream()
                .filter(trigger -> trigger.eventType() == alertEvent.eventType())
                .toList();

        Set<String> failedTypes = failedGuardrailTypes(alertEvent.payload());
        if (failedTypes == null) {
            // 意外的负载结构 —— 不要静默丢弃该事件。
            return true;
        }

        return guardrailTriggers.stream().anyMatch(trigger -> {
            Set<String> configuredTypes = configuredGuardrailTypes(trigger);
            // 此触发器上无过滤器 → 对任意防护规则类型触发。
            return configuredTypes.isEmpty()
                    || failedTypes.stream().anyMatch(configuredTypes::contains);
        });
    }

    /**
     * 告警事件负载中存在的防护规则类型名称集合，当负载无法检查时为 {@code null}
     * （这样调用方可以选择放开而不是丢弃该事件）。
     */
    private Set<String> failedGuardrailTypes(Object payload) {
        if (!(payload instanceof List<?> guardrails)) {
            return null;
        }
        return guardrails.stream()
                .filter(Guardrail.class::isInstance)
                .map(guardrail -> ((Guardrail) guardrail).name())
                .filter(Objects::nonNull)
                .map(guardrailType -> guardrailType.name())
                .collect(Collectors.toSet());
    }

    /** 单个触发器的 {@code filter:guardrail_type} 配置中所配置的防护规则类型名称。 */
    private Set<String> configuredGuardrailTypes(AlertTrigger trigger) {
        return CollectionUtils.emptyIfNull(trigger.triggerConfigs()).stream()
                .filter(config -> config.type() == AlertTriggerConfigType.FILTER_GUARDRAIL_TYPE)
                .map(config -> config.configValue() != null
                        ? config.configValue().get(AlertTriggerConfig.GUARDRAIL_TYPES_CONFIG_KEY)
                        : null)
                .filter(StringUtils::isNotBlank)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(type -> type.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private boolean isWithinProjectScope(AlertEvent alertEvent, Alert alert) {
        // 没有项目 ID 的事件是工作区级别的（例如提示词事件）—— 绕过项目范围
        if (alertEvent.projectId() == null) {
            return true;
        }

        // 只检查其 eventType 与传入事件匹配的触发器
        var matchingTriggerConfigs = CollectionUtils.isNotEmpty(alert.triggers())
                ? alert.triggers().stream()
                        .filter(t -> t.eventType() == alertEvent.eventType())
                        .filter(t -> CollectionUtils.isNotEmpty(t.triggerConfigs()))
                        .flatMap(t -> t.triggerConfigs().stream())
                        .toList()
                : List.<AlertTriggerConfig>of();

        var projectIds = AlertScopeUtils.collectProjectIds(alert.projectId(), matchingTriggerConfigs);

        if (projectIds.isEmpty()) {
            // 未定义项目范围 —— 告警适用于所有项目
            return true;
        }

        return projectIds.contains(alertEvent.projectId());
    }
}
