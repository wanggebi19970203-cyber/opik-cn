package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueBatch;
import com.comet.opik.api.AnnotationQueueSearchCriteria;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.LockResponse;
import com.comet.opik.api.Project;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 注解队列服务接口
 * 提供注解队列的创建、查询、更新和删除等操作
 */
@ImplementedBy(AnnotationQueueServiceImpl.class)
public interface AnnotationQueueService {

    /**
     * 创建单个注解队列
     * @param annotationQueue 注解队列信息
     * @return 创建的注解队列ID
     */
    Mono<UUID> create(AnnotationQueue annotationQueue);

    /**
     * 批量创建注解队列
     * @param batch 批量创建请求
     * @return 成功创建的注解队列数量
     */
    Mono<Integer> createBatch(AnnotationQueueBatch batch);

    /**
     * 根据ID查找注解队列
     * @param id 注解队列ID
     * @return 注解队列信息
     */
    Mono<AnnotationQueue> findById(@NonNull UUID id);

    /**
     * 更新注解队列
     * @param id 注解队列ID
     * @param updateRequest 更新请求
     * @return 更新操作的结果
     */
    Mono<Void> update(@NonNull UUID id, @NonNull AnnotationQueueUpdate updateRequest);

    /**
     * 分页查询注解队列
     * @param page 页码
     * @param size 每页大小
     * @param searchCriteria 查询条件
     * @return 注解队列分页结果
     */
    Mono<AnnotationQueue.AnnotationQueuePage> find(int page, int size, AnnotationQueueSearchCriteria searchCriteria);

    /**
     * 向注解队列添加项目
     * @param queueId 注解队列ID
     * @param itemIds 项目ID集合
     * @return 成功添加的项目数量
     */
    Mono<Long> addItems(UUID queueId, Set<UUID> itemIds);

    /**
     * 从注解队列移除项目
     * @param queueId 注解队列ID
     * @param itemIds 项目ID集合
     * @return 成功移除的项目数量
     */
    Mono<Long> removeItems(UUID queueId, Set<UUID> itemIds);

    /**
     * 批量删除注解队列
     * @param ids 注解队列ID集合
     * @return 成功删除的注解队列数量
     */
    Mono<Long> deleteBatch(Set<UUID> ids);

    /**
     * 尝试锁定注解队列中的项目
     * @param queueId 注解队列ID
     * @param itemId 项目ID
     * @return 锁定响应
     */
    Mono<LockResponse> tryLockItem(UUID queueId, UUID itemId);
}

/**
 * 注解队列服务实现类
 * 实现注解队列的CRUD操作、批量处理和项目锁定功能
 */
@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
class AnnotationQueueServiceImpl implements AnnotationQueueService {

    /** 注解队列数据访问对象 */
    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    /** 注解队列项目锁定服务 */
    private final @NonNull AnnotationQueueItemLockService lockService;
    /** ID生成器 */
    private final @NonNull IdGenerator idGenerator;
    /** 项目服务 */
    private final @NonNull ProjectService projectService;

    /**
     * 创建单个注解队列
     * @param annotationQueue 注解队列信息
     * @return 创建的注解队列ID
     */
    @Override
    public Mono<UUID> create(AnnotationQueue annotationQueue) {
        annotationQueue = prepareAnnotationQueue(annotationQueue);

        return annotationQueueDAO.createBatch(List.of(annotationQueue))
                .thenReturn(annotationQueue.id())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 批量创建注解队列
     * @param batch 批量创建请求
     * @return 成功创建的注解队列数量
     */
    @Override
    @WithSpan
    public Mono<Integer> createBatch(@NonNull AnnotationQueueBatch batch) {
        log.info("创建包含 '{}' 个项目的注解队列批次", batch.annotationQueues().size());

        // 生成ID并准备注解队列
        List<AnnotationQueue> processedQueues = batch.annotationQueues().stream()
                .map(this::prepareAnnotationQueue)
                .toList();

        return annotationQueueDAO.createBatch(processedQueues)
                .thenReturn(processedQueues.size())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 根据ID查找注解队列
     * @param id 注解队列ID
     * @return 注解队列信息
     */
    @Override
    @WithSpan
    public Mono<AnnotationQueue> findById(@NonNull UUID id) {
        log.debug("根据ID '{}' 查找注解队列", id);

        return annotationQueueDAO.findById(id)
                .switchIfEmpty(Mono.error(createNotFoundError(id)))
                .flatMap(this::enhanceWithProjectName)
                .doOnSuccess(queue -> log.debug("找到ID为 '{}' 的注解队列", id))
                .doOnError(error -> log.info("未找到ID为 '{}' 的注解队列", id));
    }

    /**
     * 更新注解队列
     * @param id 注解队列ID
     * @param updateRequest 更新请求
     * @return 更新操作的结果
     */
    public Mono<Void> update(@NonNull UUID id, @NonNull AnnotationQueueUpdate updateRequest) {
        log.info("更新ID为 '{}' 的注解队列", id);

        return IdGenerator
                .validateVersionAsync(id, "AnnotationQueue")
                .then(Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    return annotationQueueDAO.findQueueInfoById(id)
                            .switchIfEmpty(Mono.error(createNotFoundError(id)))
                            .flatMap(queueInfo -> {
                                Mono<Void> updateMono = annotationQueueDAO.update(id, updateRequest);
                                if (updateRequest.annotatorsPerItem() == null) {
                                    return updateMono;
                                }
                                int delta = updateRequest.annotatorsPerItem() - queueInfo.annotatorsPerItem();
                                return updateMono
                                        .then(lockService.updateCapacity(workspaceId, id, delta));
                            });
                }));
    }

    /**
     * 分页查询注解队列
     * @param page 页码
     * @param size 每页大小
     * @param searchCriteria 查询条件
     * @return 注解队列分页结果
     */
    @Override
    @WithSpan
    public Mono<AnnotationQueue.AnnotationQueuePage> find(int page, int size,
            AnnotationQueueSearchCriteria searchCriteria) {
        log.info("根据 '{}' 查询注解队列，页码 '{}'，每页大小 '{}'", searchCriteria, page, size);

        return annotationQueueDAO.find(page, size, searchCriteria)
                .flatMap(this::enhancePageWithProjectNames)
                .doOnSuccess(result -> log.debug("根据 '{}' 查询注解队列，数量 '{}'，页码 '{}'，每页大小 '{}'",
                        searchCriteria, result.content().size(), page, size))
                .doOnError(error -> log.info("根据 '{}' 查询注解队列失败", searchCriteria, error));
    }

    /**
     * 向注解队列添加项目
     * @param queueId 注解队列ID
     * @param itemIds 项目ID集合
     * @return 成功添加的项目数量
     */
    @WithSpan
    @Override
    public Mono<Long> addItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            log.debug("项目ID列表为空，直接返回");
            return Mono.just(0L);
        }

        return annotationQueueDAO.findQueueInfoById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.addItems(queueId, itemIds, queue.projectId()))
                .doOnSuccess(addedCount -> log.debug("成功向ID为 '{}' 的注解队列添加 '{}' 个项目",
                        addedCount, queueId))
                .doOnError(error -> log.info("向ID为 '{}' 的注解队列添加项目失败", queueId, error));
    }

    /**
     * 从注解队列移除项目
     * @param queueId 注解队列ID
     * @param itemIds 项目ID集合
     * @return 成功移除的项目数量
     */
    @Override
    @WithSpan
    public Mono<Long> removeItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            log.debug("项目ID列表为空，直接返回");
            return Mono.just(0L);
        }

        return annotationQueueDAO.findQueueInfoById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.removeItems(queueId, itemIds, queue.projectId()))
                .doOnSuccess(removedCount -> log.debug(
                        "成功从ID为 '{}' 的注解队列移除 '{}' 个项目", removedCount, queueId))
                .doOnError(error -> log.info("从ID为 '{}' 的注解队列移除项目失败", queueId,
                        error));
    }

    /**
     * 批量删除注解队列
     * @param ids 注解队列ID集合
     * @return 成功删除的注解队列数量
     */
    @Override
    @WithSpan
    public Mono<Long> deleteBatch(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.debug("注解队列ID列表为空，直接返回");
            return Mono.just(0L);
        }

        log.info("删除包含 '{}' 个项目的注解队列批次", ids.size());

        return annotationQueueDAO.deleteBatch(ids)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(deletedCount -> log.debug("成功删除 '{}' 个注解队列", deletedCount))
                .doOnError(error -> log.info("删除注解队列批次失败", error));
    }

    /**
     * 尝试锁定注解队列中的项目
     * @param queueId 注解队列ID
     * @param itemId 项目ID
     * @return 锁定响应
     */
    @Override
    @WithSpan
    public Mono<LockResponse> tryLockItem(@NonNull UUID queueId, @NonNull UUID itemId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return annotationQueueDAO.findById(queueId)
                    .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                    .flatMap(queue -> annotationQueueDAO.getDistinctAnnotatorCount(
                            itemId, queue.projectId(),
                            queue.scope().getValue(),
                            queueId,
                            queue.feedbackDefinitionNames())
                            .map(scoredCount -> Map.entry(queue, scoredCount)))
                    .flatMap(entry -> lockService.tryLock(
                            workspaceId, queueId, itemId, userName,
                            entry.getKey().annotatorsPerItem(),
                            entry.getValue(),
                            entry.getKey().lockTimeoutSeconds()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 为注解队列填充项目名称
     * @param annotationQueue 注解队列信息
     * @return 包含项目名称的注解队列信息
     */
    private Mono<AnnotationQueue> enhanceWithProjectName(AnnotationQueue annotationQueue) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            List<Project> projects = projectService.findByIds(workspaceId, Set.of(annotationQueue.projectId()));
            if (projects.isEmpty()) {
                log.warn("未找到注解队列 '{}' 对应的项目，项目ID '{}'",
                        annotationQueue.id(), annotationQueue.projectId());
                return Mono.just(annotationQueue);
            }

            String projectName = projects.getFirst().name();
            return Mono.just(annotationQueue.toBuilder()
                    .projectName(projectName)
                    .build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 为分页结果中的注解队列填充项目名称
     * @param page 注解队列分页结果
     * @return 包含项目名称的注解队列分页结果
     */
    private Mono<AnnotationQueue.AnnotationQueuePage> enhancePageWithProjectNames(
            AnnotationQueue.AnnotationQueuePage page) {
        if (page.content().isEmpty()) {
            return Mono.just(page);
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // 提取所有唯一的项目ID
            Set<UUID> projectIds = page.content().stream()
                    .map(AnnotationQueue::projectId)
                    .collect(Collectors.toSet());

            // 创建项目ID到项目名称的映射
            Map<UUID, String> projectIdToNameMap = projectService.findIdToNameByIds(workspaceId, projectIds);

            // 为所有注解队列填充项目名称
            List<AnnotationQueue> enhancedQueues = page.content().stream()
                    .map(queue -> {
                        String projectName = projectIdToNameMap.get(queue.projectId());
                        if (projectName == null) {
                            log.warn("未找到注解队列 '{}' 对应的项目，项目ID '{}'",
                                    queue.id(), queue.projectId());
                        }
                        return queue.toBuilder()
                                .projectName(projectName)
                                .build();
                    })
                    .toList();

            // 返回增强后的分页结果
            return Mono.just(page.toBuilder()
                    .content(enhancedQueues)
                    .build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 准备注解队列数据，生成ID并设置默认值
     * @param annotationQueue 原始注解队列信息
     * @return 处理后的注解队列信息
     */
    private AnnotationQueue prepareAnnotationQueue(AnnotationQueue annotationQueue) {
        UUID id = annotationQueue.id() == null ? idGenerator.generateId() : annotationQueue.id();
        IdGenerator.validateVersion(id, "AnnotationQueue");

        log.debug("准备注解队列，ID '{}'，名称 '{}'，项目 '{}'",
                id, annotationQueue.name(), annotationQueue.projectId());

        return annotationQueue.toBuilder()
                .id(id)
                .commentsEnabled(annotationQueue.commentsEnabled() != null ? annotationQueue.commentsEnabled() : false)
                .feedbackDefinitionNames(annotationQueue.feedbackDefinitionNames() != null
                        ? annotationQueue.feedbackDefinitionNames()
                        : List.of())
                .build();
    }

    /**
     * 创建未找到异常
     * @param id 注解队列ID
     * @return NotFoundException 异常对象
     */
    private NotFoundException createNotFoundError(UUID id) {
        var message = "未找到注解队列: '%s'".formatted(id);
        log.info(message);
        return new NotFoundException(message);
    }
}
