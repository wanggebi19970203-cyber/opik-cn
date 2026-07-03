package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.Webhook;
import com.comet.opik.api.WebhookExamples;
import com.comet.opik.api.WebhookTestResult;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.events.webhooks.WebhookEvent;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.resources.v1.events.webhooks.WebhookHttpClient;
import com.comet.opik.api.sorting.SortingFactoryAlerts;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.utils.RetryUtils;
import com.fasterxml.uuid.Generators;
import com.google.inject.ImplementedBy;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.jdbi.v3.core.Handle;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.resources.v1.events.webhooks.pagerduty.PagerDutyWebhookPayloadMapper.ROUTING_KEY_METADATA_KEY;
import static com.comet.opik.api.resources.v1.events.webhooks.slack.AlertPayloadAdapter.deserializeEventPayload;
import static com.comet.opik.api.resources.v1.events.webhooks.slack.AlertPayloadAdapter.prepareWebhookPayload;
import static com.comet.opik.api.resources.v1.events.webhooks.slack.AlertPayloadAdapter.webhookEventPayloadPerType;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.setRequestContext;

/**
 * 告警服务接口，定义告警的增删改查及Webhook测试等操作。
 * <p>
 * 该接口由 {@link AlertServiceImpl} 实现。
 */
@ImplementedBy(AlertServiceImpl.class)
public interface AlertService {

    /**
     * 创建告警
     *
     * @param alert 告警对象
     * @return 告警ID
     */
    UUID create(Alert alert);

    /**
     * 更新告警
     *
     * @param id    告警ID
     * @param alert 告警对象
     */
    void update(UUID id, Alert alert);

    /**
     * 分页查询告警列表（无项目过滤）
     *
     * @param page         页码
     * @param size         每页大小
     * @param sortingFields 排序字段列表
     * @param filters      过滤条件列表
     * @return 告警分页结果
     */
    Alert.AlertPage find(int page, int size, List<SortingField> sortingFields, List<? extends Filter> filters);

    /**
     * 分页查询告警列表（支持项目过滤）
     *
     * @param page         页码
     * @param size         每页大小
     * @param sortingFields 排序字段列表
     * @param filters      过滤条件列表
     * @param projectId    项目ID（可为null）
     * @return 告警分页结果
     */
    Alert.AlertPage find(int page, int size, List<SortingField> sortingFields, List<? extends Filter> filters,
            UUID projectId);

    /**
     * 根据ID获取告警
     *
     * @param id 告警ID
     * @return 告警对象
     */
    Alert getById(UUID id);

    /**
     * 根据工作空间和事件类型获取所有告警
     *
     * @param workspaceId 工作空间ID
     * @param eventTypes  事件类型集合
     * @return 告警列表
     */
    List<Alert> findAllByWorkspaceAndEventTypes(String workspaceId, Set<AlertEventType> eventTypes);

    /**
     * 根据ID和工作空间获取告警
     *
     * @param id          告警ID
     * @param workspaceId 工作空间ID
     * @return 告警对象
     */
    Alert getByIdAndWorkspace(UUID id, String workspaceId);

    /**
     * 批量删除告警
     *
     * @param ids 告警ID集合
     */
    void deleteBatch(Set<UUID> ids);

    /**
     * 测试Webhook连接
     *
     * @param alert 告警对象
     * @return 测试结果
     */
    WebhookTestResult testWebhook(Alert alert);

    /**
     * 获取Webhook示例
     *
     * @param alertType 告警类型
     * @return Webhook示例对象
     */
    WebhookExamples getWebhookExamples(AlertType alertType);
}

/**
 * 告警服务实现类，提供告警的完整生命周期管理。
 * <p>
 * 包括告警的创建、更新、查询、删除，以及Webhook配置和测试功能。
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AlertServiceImpl implements AlertService {

    /** 告警已存在错误信息 */
    private static final String ALERT_ALREADY_EXISTS = "Alert already exists";

    /** 告警未找到错误信息 */
    private static final String ALERT_NOT_FOUND = "Alert not found";

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull SortingFactoryAlerts sortingFactory;
    private final @NonNull WebhookHttpClient webhookHttpClient;

    /**
     * 测试用载荷映射表，按事件类型存储对应的JSON测试数据。
     * 用于Webhook测试时生成模拟的事件载荷。
     */
    private final static EnumMap<AlertEventType, String> TEST_PAYLOAD = new EnumMap<>(Map.of(
            AlertEventType.TRACE_ERRORS,
            """
                    {
                      "event_type": "TRACE_ERRORS",
                      "metric_name": "trace:errors",
                      "metric_value": "15",
                      "threshold": "10",
                      "window_seconds": "3600",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """,
            AlertEventType.TRACE_FEEDBACK_SCORE,
            """
                    {
                      "event_type": "TRACE_FEEDBACK_SCORE",
                      "metric_name": "trace:feedback_score",
                      "metric_value": "0.7500",
                      "threshold": "0.5",
                      "window_seconds": "3600",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """,
            AlertEventType.TRACE_THREAD_FEEDBACK_SCORE,
            """
                    {
                      "event_type": "TRACE_THREAD_FEEDBACK_SCORE",
                      "metric_name": "trace_thread:feedback_score",
                      "metric_value": "0.7500",
                      "threshold": "0.5",
                      "window_seconds": "3600",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """,
            AlertEventType.PROMPT_CREATED,
            """
                    {
                      "id": "0198c90a-46ca-70e2-944d-cac10720ab66",
                      "name": "Opik SDK Assistant - System Prompt",
                      "description": "System prompt for Opik SDK assistant to help users with technical questions",
                      "tags": ["system", "assistant"],
                      "created_at": "2025-08-27T10:00:00Z",
                      "created_by": "test-user",
                      "last_updated_at": "2025-08-27T10:00:00Z",
                      "last_updated_by": "test-user"
                    }
                    """,
            AlertEventType.PROMPT_COMMITTED,
            """
                    {
                      "id": "0198c90a-46c6-78ef-90d9-62ed986afb80",
                      "prompt_id": "0198c90a-46ca-70e2-944d-cac10720ab66",
                      "commit": "986afb80",
                      "template": "You are an Opik expert and know how to explain Comet SDK concepts in simple terms. Keep the answers short and don't try to make up answers that you don't know.",
                      "type": "mustache",
                      "metadata": {
                        "version": "1.0",
                        "model": "gpt-4"
                      },
                      "created_at": "2025-08-27T10:00:00Z",
                      "created_by": "test-user"
                    }
                    """,
            AlertEventType.PROMPT_DELETED,
            """
                    [
                        {
                          "id": "0198c90a-46ca-70e2-944d-cac10720ab66",
                          "name": "Old System Prompt",
                          "description": "Deprecated system prompt that is no longer in use",
                          "tags": ["deprecated"],
                          "created_at": "2025-07-15T10:00:00Z",
                          "created_by": "test-user",
                          "last_updated_at": "2025-08-27T10:00:00Z",
                          "last_updated_by": "test-user",
                          "latest_version": {
                              "id": "0198c90a-46c6-78ef-90d9-62ed986afb80",
                              "commit": "986afb80",
                              "template": "You are an Opik expert and know how to explain Comet SDK concepts in simple terms. Keep the answers short and don't try to make up answers that you don't know.",
                              "type": "mustache",
                              "created_at": "2025-08-27T10:00:00Z",
                              "created_by": "test-user"
                          }
                        }
                    ]
                    """,
            AlertEventType.TRACE_GUARDRAILS_TRIGGERED,
            """
                    [
                        {
                          "id": "0198ec7e-e999-7537-bbbb-fc5db24face8",
                          "entity_id": "0198ec7e-e844-7537-aaaa-fc5db24face7",
                          "project_id": "0198ec68-6e06-7253-a20b-d35c9252b9ba",
                          "project_name": "Demo Project",
                          "name": "PII",
                          "result": "failed",
                          "details": {
                            "detected_entities": ["EMAIL", "PHONE_NUMBER"],
                            "message": "PII detected in response: email address and phone number"
                          }
                        }
                    ]
                    """,
            AlertEventType.EXPERIMENT_FINISHED,
            """
                    [
                        {
                                "id": "0198c90e-3884-7fe6-9236-168acd26d4bb",
                                "name": "opik-assistant-v1",
                                "dataset_id": "0198c909-9294-7d3a-a3c2-7511f46a9ef0",
                                "metadata": {
                                    "model": "gpt-4o-mini",
                                    "prompts": [
                                        "You are an instructor for technical executives that want to extract value of AI models.\\n        If you know the answer to the question, respond by stating that it is possible to do what is being asked,\\n        but without going into technical details on how to do it.\\n        Make sure you include in your answer:\\n        - A description of the lifecycle of a machine learning model\\n        - Where in this lifecycle the current question is relevant\\n        - The business benefits of implementing the provided answer\\n        - An estimation of the time and cost of implementing the provided answer"
                                    ]
                                },
                                "type": "regular"
                            }
                    ]
                    """,
            AlertEventType.TRACE_COST,
            """
                    {
                      "event_type": "TRACE_COST",
                      "metric_name": "trace:cost",
                      "metric_value": "150.75",
                      "threshold": "100.00",
                      "window_seconds": "3600",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """,
            AlertEventType.TRACE_LATENCY,
            """
                    {
                      "event_type": "TRACE_LATENCY",
                      "metric_name": "trace:latency",
                      "metric_value": "5250.5000",
                      "threshold": "5",
                      "window_seconds": "1800",
                      "project_ids": "0198ec68-6e06-7253-a20b-d35c9252b9ba,0198ec68-6e06-7253-a20b-d35c9252b9bb",
                      "project_names": "Demo Project,Default Project"
                    }
                    """));

    /** 虚拟告警示例对象，用于生成Webhook测试载荷 */
    private static final Alert DUMMY_ALERT = Alert.builder()
            .id(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"))
            .name("Example Alert")
            .enabled(true)
            .webhook(Webhook.builder()
                    .build())
            .metadata(Map.of(ROUTING_KEY_METADATA_KEY, "example-routing-key"))
            .build();

    /** Webhook示例缓存，按告警类型索引 */
    private static final Map<AlertType, WebhookExamples> WEBHOOK_EXAMPLES = prepareWebhookPayloadExamples();

    /**
     * 创建告警。
     * <p>
     * 验证告警配置的有效性，生成必要的ID，然后持久化告警及其关联的Webhook和触发器。
     *
     * @param alert 告警对象
     * @return 创建的告警ID
     * @throws EntityAlreadyExistsException 如果同名告警已存在
     */
    @Override
    public UUID create(@NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        validateNoProjectScopeConflict(alert);
        validateGroupIndices(alert);
        var newAlert = prepareAlert(alert, userName, workspaceId);

        return EntityConstraintHandler
                .handle(() -> saveAlert(newAlert, workspaceId))
                .withError(this::newAlertConflict);
    }

    /**
     * 更新告警。
     * <p>
     * 注意：当前接口同时用于更新告警/Webhook以及创建/更新/删除触发器和触发器配置，
     * 未来应拆分为独立的接口。
     *
     * @param id    告警ID
     * @param alert 更新后的告警对象
     */
    //TODO: 当前接口用于更新告警/Webhook以及创建/更新/删除触发器和触发器配置，未来应拆分为独立的接口
    @Override
    public void update(@NonNull UUID id, @NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // 确保告警存在，不存在则抛出NotFoundException
        var existingAlert = getById(id);

        validateNoProjectScopeConflict(alert);
        validateGroupIndices(alert);

        alert = alert.toBuilder()
                .createdBy(existingAlert.createdBy())
                .createdAt(existingAlert.createdAt())
                .build();

        // 准备更新后的告警对象（保留相同ID）
        var newAlert = prepareAlert(alert, userName, workspaceId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            // 删除现有告警及其所有关联实体（触发器、触发器配置、Webhook）
            deleteBatch(handle, Set.of(id));

            // 保存更新后的告警
            saveAlert(handle, newAlert, workspaceId);

            return null;
        });
    }

    /**
     * 分页查询告警列表（无项目过滤）
     */
    @Override
    public Alert.AlertPage find(int page, int size, List<SortingField> sortingFields, List<? extends Filter> filters) {
        return find(page, size, sortingFields, filters, null);
    }

    /**
     * 分页查询告警列表（支持项目过滤）
     */
    @Override
    public Alert.AlertPage find(int page, int size, List<SortingField> sortingFields, List<? extends Filter> filters,
            UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        String filtersSQL = Optional.ofNullable(filters)
                .flatMap(f -> FilterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.ALERT))
                .orElse(null);

        Map<String, Object> filterMapping = Optional.ofNullable(filters)
                .map(filterQueryBuilder::toStateSQLMapping)
                .orElse(Map.of());

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            AlertDAO alertDAO = handle.attach(AlertDAO.class);

            long total = alertDAO.count(workspaceId, filtersSQL, filterMapping, projectId);

            var offset = (page - 1) * size;

            List<Alert> content = alertDAO.find(workspaceId, offset, size, sortingFieldsSql, filtersSQL,
                    filterMapping, projectId);

            return Alert.AlertPage.builder()
                    .page(page)
                    .size(content.size())
                    .content(content)
                    .total(total)
                    .sortableBy(sortingFactory.getSortableFields())
                    .build();
        });
    }

    /**
     * 根据ID获取告警（使用当前请求上下文的工作空间）
     */
    @Override
    public Alert getById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        return getByIdAndWorkspace(id, workspaceId);
    }

    /**
     * 根据工作空间和事件类型获取所有告警。
     * <p>
     * 结果会被缓存，缓存键为工作空间ID和事件类型的组合。
     *
     * @param workspaceId 工作空间ID
     * @param eventTypes  事件类型集合
     * @return 告警列表
     */
    @Override
    @Cacheable(name = "alert_find_all_per_workspace", key = "$workspaceId +'-'+ $eventTypes", returnType = Alert.class, wrapperType = List.class)
    public List<Alert> findAllByWorkspaceAndEventTypes(String workspaceId, @NonNull Set<AlertEventType> eventTypes) {
        log.info("Fetching all enabled alerts for workspace '{}', eventTypes '{}'", workspaceId, eventTypes);
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            AlertDAO alertDAO = handle.attach(AlertDAO.class);

            Set<String> eventTypeValues = eventTypes.stream()
                    .map(AlertEventType::getValue)
                    .collect(Collectors.toSet());

            return alertDAO.findByWorkspaceAndEventTypes(workspaceId, eventTypeValues);
        });
    }

    /**
     * 根据ID和工作空间获取告警
     *
     * @throws NotFoundException 如果告警不存在
     */
    @Override
    public Alert getByIdAndWorkspace(@NonNull UUID id, @NonNull String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            AlertDAO alertDAO = handle.attach(AlertDAO.class);

            Alert alert = alertDAO.findById(id, workspaceId);

            if (alert == null) {
                throw new NotFoundException(ALERT_NOT_FOUND);
            }

            return alert;
        });
    }

    /**
     * 批量删除告警
     */
    @Override
    public void deleteBatch(@NonNull Set<UUID> ids) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            deleteBatch(handle, ids);
            return null;
        });
    }

    /**
     * 测试Webhook连接。
     * <p>
     * 构建测试载荷并发送Webhook请求，返回测试结果（成功/失败及状态码）。
     *
     * @param alert 告警对象
     * @return Webhook测试结果
     */
    @Override
    public WebhookTestResult testWebhook(@NonNull Alert alert) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        var event = prepareWebhookPayload(mapAlertToWebhookEvent(alert, workspaceId));

        return Mono.defer(() -> webhookHttpClient.sendWebhook(event))
                .contextWrite(ctx -> setRequestContext(ctx, userName, workspaceId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(responseBody -> {
                    log.info("Successfully sent webhook: id='{}', type='{}', url='{}', response='{}'",
                            event.getId(), event.getEventType(), event.getUrl(), responseBody);

                    return WebhookTestResult.builder()
                            .status(WebhookTestResult.Status.SUCCESS)
                            .statusCode(200) // 成功时默认返回200
                            .requestBody(event.getJsonPayload())
                            .errorMessage(null)
                            .build();
                })
                .onErrorResume(throwable -> {
                    log.error("Failed to send webhook: id='{}', type='{}', url='{}', error='{}'",
                            event.getId(), event.getEventType(), event.getUrl(), throwable.getMessage(), throwable);

                    // 从RetryableHttpException中提取状态码（如果可用）
                    int statusCode = (throwable instanceof RetryUtils.RetryableHttpException rhe)
                            ? rhe.getStatusCode()
                            : 0;

                    return Mono.just(WebhookTestResult.builder()
                            .status(WebhookTestResult.Status.FAILURE)
                            .statusCode(statusCode)
                            .requestBody(event.getJsonPayload())
                            .errorMessage(throwable.getMessage())
                            .build());
                })
                .block();
    }

    /**
     * 获取指定告警类型的Webhook示例
     */
    @Override
    public WebhookExamples getWebhookExamples(@NonNull AlertType alertType) {
        return WEBHOOK_EXAMPLES.get(alertType);
    }

    /**
     * 将告警对象映射为Webhook事件。
     * <p>
     * 用于Webhook测试时生成模拟的事件数据。
     *
     * @param alert       告警对象
     * @param workspaceId 工作空间ID
     * @return Webhook事件对象
     */
    private static WebhookEvent<Map<String, Object>> mapAlertToWebhookEvent(Alert alert, String workspaceId) {
        String eventId = Generators.timeBasedEpochGenerator().generate().toString();
        var eventType = CollectionUtils.isEmpty(alert.triggers())
                ? AlertEventType.TRACE_ERRORS
                : alert.triggers().getFirst().eventType();
        List<String> eventIds = List.of("0198ec7e-e844-7537-aaaa-fc5db24fb547");
        var alertId = alert.id() == null ? UUID.fromString("0198ec7e-e844-7537-aaaa-fc5dd35fb547") : alert.id();

        Map<String, Object> payload = Map.of(
                "alertId", alertId,
                "alertName", alert.name(),
                "eventType", eventType.getValue(),
                "eventIds", eventIds,
                "userNames", List.of("test-user"),
                "metadata", List.of(TEST_PAYLOAD.get(eventType)),
                "eventCount", eventIds.size(),
                "aggregationType", "consolidated",
                "message", String.format("Alert '%s': %d %s events aggregated",
                        alert.name(), eventIds.size(), eventType.getValue()));

        return WebhookEvent.<Map<String, Object>>builder()
                .id(eventId)
                .url(alert.webhook().url())
                .eventType(eventType)
                .alertType(Optional.ofNullable(alert.alertType()).orElse(AlertType.GENERAL))
                .alertId(alertId)
                .alertName(StringUtils.isBlank(alert.name()) ? "Test Alert" : alert.name())
                .alertMetadata(Optional.ofNullable(alert.metadata()).orElse(Map.of()))
                .payload(payload)
                .headers(Optional.ofNullable(alert.webhook().headers()).orElse(Map.of()))
                .secret(alert.webhook().secretToken())
                .maxRetries(1)
                .workspaceId(workspaceId)
                .workspaceName("demo_workspace_name")
                .createdAt(Instant.now())
                .build();
    }

    /**
     * 在事务中批量删除告警
     */
    private void deleteBatch(Handle handle, Set<UUID> ids) {
        String workspaceId = requestContext.get().getWorkspaceId();
        AlertDAO alertDAO = handle.attach(AlertDAO.class);
        alertDAO.delete(ids, workspaceId);
    }

    /**
     * 在新事务中保存告警
     */
    private UUID saveAlert(Alert alert, String workspaceId) {
        return transactionTemplate.inTransaction(WRITE, handle -> saveAlert(handle, alert, workspaceId));
    }

    /**
     * 在现有事务中保存告警及其关联实体（Webhook、触发器、触发器配置）
     *
     * @param handle      事务句柄
     * @param alert       告警对象
     * @param workspaceId 工作空间ID
     * @return 告警ID
     */
    private UUID saveAlert(Handle handle, Alert alert, String workspaceId) {

        AlertDAO alertDAO = handle.attach(AlertDAO.class);
        alertDAO.save(workspaceId, alert, alert.webhook().id());

        WebhookDAO webhookDAO = handle.attach(WebhookDAO.class);
        webhookDAO.save(workspaceId, alert.webhook());

        // 保存触发器及其配置
        if (CollectionUtils.isNotEmpty(alert.triggers())) {
            AlertTriggerDAO alertTriggerDAO = handle.attach(AlertTriggerDAO.class);
            alertTriggerDAO.saveBatch(alert.triggers());

            List<AlertTriggerConfig> triggerConfigs = alert.triggers().stream()
                    .filter(trigger -> CollectionUtils.isNotEmpty(trigger.triggerConfigs()))
                    .flatMap(trigger -> trigger.triggerConfigs().stream())
                    .toList();

            if (CollectionUtils.isNotEmpty(triggerConfigs)) {
                AlertTriggerConfigDAO alertTriggerConfigDAO = handle.attach(AlertTriggerConfigDAO.class);
                alertTriggerConfigDAO.saveBatch(triggerConfigs);
            }
        }

        return alert.id();
    }

    /**
     * 创建告警冲突异常
     */
    private EntityAlreadyExistsException newAlertConflict() {
        return new EntityAlreadyExistsException(new ErrorMessage(HttpStatus.SC_CONFLICT, ALERT_ALREADY_EXISTS));
    }

    /**
     * 验证触发器配置中的group_index字段。
     * <p>
     * 规则：
     * - group_index必须为非负数
     * - scope:project类型的触发器配置不允许设置group_index（它作为全局前置条件，而非布尔表达式的一部分）
     *
     * @param alert 告警对象
     * @throws BadRequestException 如果验证失败
     */
    private static void validateGroupIndices(Alert alert) {
        if (alert.triggers() == null) {
            return;
        }
        for (AlertTrigger trigger : alert.triggers()) {
            if (trigger.triggerConfigs() == null) {
                continue;
            }
            for (AlertTriggerConfig config : trigger.triggerConfigs()) {
                Integer groupIndex = config.groupIndex();
                if (groupIndex == null) {
                    continue;
                }
                if (groupIndex < 0) {
                    throw new BadRequestException(
                            "'group_index' must be non-negative, got '%d'".formatted(groupIndex));
                }
                if (config.type() == AlertTriggerConfigType.SCOPE_PROJECT) {
                    throw new BadRequestException(
                            "'group_index' must be null for 'scope:project' trigger configs; it applies as a global precondition, not as part of the boolean expression.");
                }
            }
        }
    }

    /**
     * 验证项目ID与scope:project触发器配置之间不存在冲突。
     * <p>
     * 规则：不能同时提供project_id和scope:project类型的触发器配置。
     * 设置project_id即可，系统会自动创建scope配置。
     *
     * @param alert 告警对象
     * @throws BadRequestException 如果同时存在冲突的配置
     */
    private static void validateNoProjectScopeConflict(Alert alert) {
        if (alert.projectId() == null || alert.triggers() == null) {
            return;
        }
        boolean hasScopeProjectConfig = alert.triggers().stream()
                .filter(trigger -> trigger.triggerConfigs() != null)
                .flatMap(trigger -> trigger.triggerConfigs().stream())
                .anyMatch(config -> AlertTriggerConfigType.SCOPE_PROJECT.equals(config.type()));
        if (hasScopeProjectConfig) {
            throw new BadRequestException(
                    "Cannot provide both 'project_id' and a 'scope:project' trigger config. Set 'project_id' only — the system creates the scope config automatically.");
        }
    }

    /**
     * 准备告警对象，生成必要的ID并设置默认值。
     * <p>
     * 处理内容：
     * - 为告警和Webhook生成或验证UUID
     * - 设置默认的enabled状态为true
     * - 设置默认的alertType为GENERAL
     * - 准备触发器及其配置
     *
     * @param alert       原始告警对象
     * @param userName    当前用户名
     * @param workspaceId 工作空间ID
     * @return 准备好的告警对象
     */
    private Alert prepareAlert(Alert alert, String userName, String workspaceId) {

        UUID id = alert.id() == null ? idGenerator.generateId() : alert.id();
        IdGenerator.validateVersion(id, "Alert");

        UUID webhookId = alert.webhook().id() == null ? idGenerator.generateId() : alert.webhook().id();
        IdGenerator.validateVersion(webhookId, "Webhook");

        Webhook webhook = alert.webhook()
                .toBuilder()
                .id(webhookId)
                .name("Webhook for alert " + alert.id()) // 前端未使用此字段
                .createdBy(Optional.ofNullable(alert.createdBy()).orElse(userName))
                .createdAt(alert.createdAt()) // 新建时为null，更新时不为null
                .lastUpdatedBy(userName)
                .build();

        // 准备触发器（生成ID）
        List<AlertTrigger> preparedTriggers = null;
        if (alert.triggers() != null) {
            preparedTriggers = alert.triggers().stream()
                    .map(trigger -> prepareTrigger(trigger, userName, id, alert))
                    .toList();
        }

        return alert.toBuilder()
                .id(id)
                .enabled(alert.enabled() != null ? alert.enabled() : true) // 仅在未显式设置时默认为true
                .alertType(alert.alertType() != null ? alert.alertType() : AlertType.GENERAL) // 未提供时默认为GENERAL
                .webhook(webhook)
                .triggers(preparedTriggers)
                .createdBy(Optional.ofNullable(alert.createdBy()).orElse(userName))
                .lastUpdatedBy(userName)
                .workspaceId(workspaceId)
                .build();
    }

    /**
     * 准备触发器对象，生成ID并关联到告警。
     *
     * @param trigger  原始触发器对象
     * @param userName 当前用户名
     * @param alertId  关联的告警ID
     * @param alert    告警对象（用于获取创建时间）
     * @return 准备好的触发器对象
     */
    private AlertTrigger prepareTrigger(AlertTrigger trigger, String userName, UUID alertId, Alert alert) {
        UUID triggerId = trigger.id() == null ? idGenerator.generateId() : trigger.id();
        IdGenerator.validateVersion(triggerId, "Alert Trigger");

        List<AlertTriggerConfig> preparedConfigs = null;
        if (trigger.triggerConfigs() != null) {
            preparedConfigs = trigger.triggerConfigs().stream()
                    .map(config -> prepareTriggerConfig(config, userName, triggerId, alert))
                    .toList();
        }

        return trigger.toBuilder()
                .id(triggerId)
                .alertId(alertId)
                .triggerConfigs(preparedConfigs)
                .createdBy(Optional.ofNullable(trigger.createdBy()).orElse(userName))
                .createdAt(alert.createdAt()) // 新建时为null，更新时不为null
                .build();
    }

    /**
     * 准备触发器配置对象，生成ID并关联到触发器。
     *
     * @param config    原始触发器配置对象
     * @param userName  当前用户名
     * @param triggerId 关联的触发器ID
     * @param alert     告警对象（用于获取创建时间）
     * @return 准备好的触发器配置对象
     */
    private AlertTriggerConfig prepareTriggerConfig(AlertTriggerConfig config, String userName, UUID triggerId,
            Alert alert) {
        UUID triggerConfigId = config.id() == null ? idGenerator.generateId() : config.id();
        IdGenerator.validateVersion(triggerConfigId, "Alert Trigger Config");

        return config.toBuilder()
                .id(triggerConfigId)
                .alertTriggerId(triggerId)
                .createdBy(Optional.ofNullable(config.createdBy()).orElse(userName))
                .createdAt(alert.createdAt()) // 新建时为null，更新时不为null
                .lastUpdatedBy(userName)
                .build();
    }

    /**
     * 准备所有告警类型的Webhook载荷示例。
     * <p>
     * 遍历所有AlertType和EventType组合，为每种组合生成对应的Webhook示例数据。
     *
     * @return 按告警类型索引的Webhook示例映射
     */
    private static Map<AlertType, WebhookExamples> prepareWebhookPayloadExamples() {
        Map<AlertType, WebhookExamples> result = new HashMap<>();

        Arrays.stream(AlertType.values())
                .forEach(alertType -> {
                    Map<AlertEventType, Object> examples = new HashMap<>();

                    Arrays.stream(AlertEventType.values())
                            .forEach(eventType -> {
                                var alert = DUMMY_ALERT.toBuilder()
                                        .alertType(alertType)
                                        .triggers(List.of(
                                                AlertTrigger.builder()
                                                        .eventType(eventType)
                                                        .build()))
                                        .build();

                                var webhookEvent = deserializeEventPayload(
                                        mapAlertToWebhookEvent(alert, "demo-workspace-id"));
                                examples.put(eventType, webhookEventPayloadPerType(webhookEvent));
                            });

                    result.put(alertType, WebhookExamples.builder()
                            .responseExamples(examples)
                            .build());
                });

        return result;
    }
}
