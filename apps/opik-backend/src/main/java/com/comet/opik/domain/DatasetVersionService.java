package com.comet.opik.domain;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersion.DatasetVersionPage;
import com.comet.opik.api.DatasetVersionCreate;
import com.comet.opik.api.DatasetVersionDiff;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.DatasetVersionUpdate;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.FilterUtils.generateUuidPool;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DatasetVersionServiceImpl.class)
public interface DatasetVersionService {

    String LATEST_TAG = "latest";

    // 序列化数据集上所有创建版本的写入者的分布式锁名称（OPIK-7264）。
    // 由 DatasetItemService（save/patch/delete/applyDeltaChanges）和这里的恢复路径共享。
    String DATASET_VERSION_LOCK = "DatasetVersion";

    // 错误消息模板
    String ERROR_VERSION_HASH_EXISTS = "Version hash collision detected for dataset '%s'";
    String ERROR_TAG_EXISTS = "Tag already exists for this dataset, tag='%s'";
    String ERROR_CANNOT_DELETE_LATEST_TAG = "Cannot delete '%s' tag - it is automatically managed";
    String ERROR_VERSION_HASH_NOT_FOUND = "Version with hash not found hash='%s' datasetId='%s'";
    String ERROR_VERSION_NOT_FOUND = "Version not found for dataset hash='%s' datasetId='%s'";
    String ERROR_LATEST_MOVED = "Concurrent modification detected for dataset '%s'; the latest version changed during this operation. Please retry.";

    /**
     * 检索指定数据集的分页版本列表，按创建时间排序（最新在前）。
     *
     * @param datasetId 数据集的唯一标识符
     * @param page 页码（从 1 开始，必须 >= 1）
     * @param size 每页版本数（必须 >= 1）
     * @return 包含数据集版本及其关联标签和元数据的分页
     * @throws IllegalArgumentException 如果 page 或 size 小于 1
     */
    DatasetVersionPage getVersions(UUID datasetId, int page, int size);

    /**
     * 为现有数据集版本添加标签，以便于引用。
     *
     * @param datasetId 数据集的唯一标识符
     * @param versionHash 要打标签的版本哈希
     * @param tag 要创建的标签（例如 "baseline"、"production"）
     * @throws NotFoundException 如果未找到具有指定哈希的版本
     * @throws ConflictException 如果该数据集的标签已存在
     */
    void createTag(UUID datasetId, String versionHash, DatasetVersionTag tag);

    /**
     * 从数据集版本删除标签。
     * <p>
     * 注意：'latest' 标签不能删除，因为它由系统自动管理。
     * 尝试删除它会导致 BadRequestException。
     *
     * @param datasetId 数据集的唯一标识符
     * @param tag 要删除的标签名称
     * @throws NotFoundException 如果标签不存在
     * @throws ClientErrorException 如果尝试删除 'latest' 标签
     */
    void deleteTag(UUID datasetId, String tag);

    /**
     * 更新现有数据集版本的 change_description 和/或添加新标签。
     * <p>
     * 此操作：
     * <ul>
     *   <li>如果提供了 change_description 则更新它</li>
     *   <li>如果提供了新标签则将其添加到版本</li>
     * </ul>
     *
     * @param datasetId 数据集的唯一标识符
     * @param versionHash 要更新的版本哈希
     * @param request 包含可选 change_description 和 tags_to_add 的更新请求
     * @return 更新后的数据集版本
     * @throws NotFoundException 如果未找到具有指定哈希的版本
     * @throws ConflictException 如果任何标签已存在于该数据集
     */
    DatasetVersion updateVersion(UUID datasetId, String versionHash, DatasetVersionUpdate request);

    /**
     * 将版本标识符（哈希或标签）解析为版本 ID。
     * <p>
     * 此方法先尝试按哈希查找版本，如果按哈希未找到，则按标签查找。
     *
     * @param workspaceId 请求的工作区 ID
     * @param datasetId 数据集的唯一标识符
     * @param hashOrTag 版本哈希或标签名称
     * @return 匹配版本的 UUID
     * @throws NotFoundException 如果未找到具有给定哈希或标签的版本
     */
    UUID resolveVersionId(String workspaceId, UUID datasetId, String hashOrTag);

    DatasetVersionDiff compareVersions(UUID datasetId, String fromHashOrTag, String toHashOrTag);

    /**
     * 获取数据集的最新版本。
     * 从 RequestContext 不可用的响应式上下文中调用是安全的。
     *
     * @param datasetId 数据集 ID
     * @param workspaceId 工作区 ID
     * @return 包含最新版本的 Optional，如果没有版本则为空
     */
    Optional<DatasetVersion> getLatestVersion(UUID datasetId, String workspaceId);

    /**
     * 仅返回最新版本的 UUID。使用 dataset_version_tags 上的主键查找，
     * 避免了 {@link #getLatestVersion} 中的行编号 CTE，因此无论数据集累积了
     * 多少版本，成本都保持 O(1)。
     */
    Optional<UUID> getLatestVersionId(UUID datasetId, String workspaceId);

    /**
     * 按 ID 获取特定版本。
     *
     * @param workspaceId 工作区 ID
     * @param datasetId 数据集 ID
     * @param versionId 版本 ID
     * @return 版本
     * @throws NotFoundException 如果未找到版本
     */
    DatasetVersion getVersionById(String workspaceId, UUID datasetId, UUID versionId);

    /**
     * 按版本名称（例如 'v1'、'v373'）获取特定版本。
     *
     * @param datasetId 数据集 ID
     * @param versionName 版本名称（例如 'v1'、'v373'）
     * @return 版本
     * @throws NotFoundException 如果未找到版本
     */
    DatasetVersion getVersionByName(UUID datasetId, String versionName);

    /**
     * 按 ID 获取多个版本。
     *
     * @param versionIds 要检索的版本 ID 集合
     * @param workspaceId 工作区 ID
     * @return 版本列表（如果没有找到版本则可能为空）
     */
    List<DatasetVersion> findByIds(Collection<UUID> versionIds, String workspaceId);

    /**
     * 检查给定版本 ID 是否是数据集的最新版本。
     * 从 RequestContext 不可用的响应式上下文中调用是安全的。
     *
     * @param workspaceId 工作区 ID
     * @param datasetId 数据集 ID
     * @param versionId 要检查的版本 ID
     * @return 如果 versionId 是最新版本则为 true，否则为 false
     */
    boolean isLatestVersion(String workspaceId, UUID datasetId, UUID versionId);

    /**
     * 检查数据集是否有任何版本。
     * 从 RequestContext 不可用的响应式上下文中调用是安全的。
     */
    boolean hasVersions(String workspaceId, UUID datasetId);

    /**
     * 查找批次组的最近版本。当多个版本共享一个
     * batch_group_id（并发写入下可能发生）时，返回最新的那个。
     * 用于支持多个 API 调用共享同一 batch_group_id 的 SDK 批量操作。
     *
     * @param batchGroupId 要搜索的批次组 ID
     * @param datasetId 数据集 ID
     * @param workspaceId 工作区 ID
     * @return 如果找到则包含该批次组最新版本的 Optional，否则为空
     */
    Optional<DatasetVersion> findByBatchGroupId(UUID batchGroupId, UUID datasetId, String workspaceId);

    /**
     * 从应用增量变更的结果创建新版本。
     * 在条目已写入版本表之后调用。
     *
     * @param datasetId 数据集 ID
     * @param newVersionId 新版本的 ID
     * @param itemsTotal 新版本中的条目总数
     * @param baseVersionId 基础版本 ID（用于差异计算）
     * @param tags 新版本的可选标签
     * @param changeDescription 变更的可选描述
     * @param evaluators 版本的可选默认评估器
     * @param executionPolicy 版本的可选默认执行策略
     * @param batchGroupId SDK 批量操作的可选批次组 ID
     * @param enforceLatestCas 当为 {@code true} 且 {@code baseVersionId} 非 null 时，'latest'
     *        标签会针对 {@code baseVersionId} 进行 compare-and-swap：如果并发写入者已经
     *        移动了 'latest'，则抛出可重试的 409 而不是覆盖它。传 {@code false}
     *        则无条件翻转——用于第一个版本，或有意从非最新基础
     *        分支出去的调用方（例如 override=true 的 applyDeltaChanges）。
     * @param workspaceId 工作区 ID（从响应式上下文调用时必需）
     * @param userName 用户名（从响应式上下文调用时必需）
     * @return 创建的版本
     */
    DatasetVersion createVersionFromDelta(UUID datasetId, UUID newVersionId, int itemsTotal,
            UUID baseVersionId, List<String> tags, String changeDescription,
            List<EvaluatorItem> evaluators, ExecutionPolicy executionPolicy,
            boolean clearExecutionPolicy,
            UUID batchGroupId, boolean enforceLatestCas, String workspaceId, String userName);

    /**
     * 通过创建新版本将数据集恢复到之前的版本状态。
     * <p>
     * 此操作在版本化条目表内将条目直接从源版本复制到新版本，
     * 完全绕过草稿表。
     * <ul>
     *   <li>如果该版本就是最新版本，则原样返回（no-op）</li>
     *   <li>否则，创建新版本并从源版本复制条目</li>
     *   <li>计算上一个最新版本与新版本之间的差异统计</li>
     * </ul>
     *
     * @param datasetId 数据集的唯一标识符
     * @param versionRef 要从中恢复的版本哈希或标签
     * @return 发出恢复版本的 Mono（如果是最新则返回现有版本，如果不是最新则返回新版本）
     * @throws NotFoundException 如果未找到版本
     */
    Mono<DatasetVersion> restoreVersion(UUID datasetId, String versionRef);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetVersionServiceImpl implements DatasetVersionService {

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull DatasetItemDAO datasetItemDAO;
    private final @NonNull DatasetItemVersionDAO datasetItemVersionDAO;
    private final @NonNull LockService lockService;
    private final @NonNull @Config OpikConfiguration config;

    @Override
    public DatasetVersionPage getVersions(@NonNull UUID datasetId, int page, int size) {
        Preconditions.checkArgument(page >= 1, "Page must be greater than or equal to 1");
        Preconditions.checkArgument(size >= 1, "Size must be greater than or equal to 1");

        log.info("获取数据集 '{}' 的版本，页码 '{}'，每页大小 '{}'", datasetId, page, size);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            int offset = (page - 1) * size;
            var versions = dao.findByDatasetId(datasetId, workspaceId, size, offset);
            var total = dao.countByDatasetId(datasetId, workspaceId);

            return new DatasetVersionPage(versions, page, size, total);
        });
    }

    private Optional<DatasetVersion> getVersionByTag(@NonNull String workspaceId, @NonNull UUID datasetId,
            @NonNull String tag) {
        log.info("按标签获取数据集 '{}' 的版本，标签 '{}'", datasetId, tag);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByTag(datasetId, tag, workspaceId);
        });
    }

    @Override
    public Optional<DatasetVersion> getLatestVersion(@NonNull UUID datasetId, @NonNull String workspaceId) {
        return getVersionByTag(workspaceId, datasetId, LATEST_TAG);
    }

    @Override
    public Optional<UUID> getLatestVersionId(@NonNull UUID datasetId, @NonNull String workspaceId) {
        return template.inTransaction(READ_ONLY, handle -> handle.attach(DatasetVersionDAO.class)
                .findVersionIdByTag(datasetId, LATEST_TAG, workspaceId));
    }

    @Override
    public Optional<DatasetVersion> findByBatchGroupId(@NonNull UUID batchGroupId, @NonNull UUID datasetId,
            @NonNull String workspaceId) {
        log.info("按 batch_group_id '{}' 为数据集 '{}' 查找版本", batchGroupId, datasetId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findLatestByBatchGroupId(batchGroupId, datasetId, workspaceId);
        });
    }

    @Override
    public DatasetVersion getVersionById(@NonNull String workspaceId, @NonNull UUID datasetId,
            @NonNull UUID versionId) {
        log.info("按 ID '{}' 为数据集 '{}' 获取版本", versionId, datasetId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findById(versionId, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            ERROR_VERSION_NOT_FOUND.formatted(versionId.toString(), datasetId)));
        });
    }

    @Override
    public DatasetVersion getVersionByName(@NonNull UUID datasetId, @NonNull String versionName) {
        log.info("按名称 '{}' 为数据集 '{}' 获取版本", versionName, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByVersionName(datasetId, versionName, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            "Version '%s' not found for dataset '%s'".formatted(versionName, datasetId)));
        });
    }

    @Override
    public List<DatasetVersion> findByIds(@NonNull Collection<UUID> versionIds, @NonNull String workspaceId) {
        if (CollectionUtils.isEmpty(versionIds)) {
            return List.of();
        }

        log.info("按 ID 查找 '{}' 个版本", versionIds.size());

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findByIds(versionIds, workspaceId);
        });
    }

    @Override
    public boolean hasVersions(@NonNull String workspaceId, @NonNull UUID datasetId) {
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.countByDatasetId(datasetId, workspaceId) > 0;
        });
    }

    @Override
    public boolean isLatestVersion(@NonNull String workspaceId, @NonNull UUID datasetId, @NonNull UUID versionId) {
        return getLatestVersion(datasetId, workspaceId)
                .map(latest -> latest.id().equals(versionId))
                .orElse(false);
    }

    @Override
    public DatasetVersion createVersionFromDelta(@NonNull UUID datasetId, @NonNull UUID newVersionId,
            int itemsTotal, UUID baseVersionId, List<String> tags, String changeDescription,
            List<EvaluatorItem> evaluators, ExecutionPolicy executionPolicy,
            boolean clearExecutionPolicy,
            UUID batchGroupId, boolean enforceLatestCas, @NonNull String workspaceId, @NonNull String userName) {

        log.info(
                "从增量创建数据集 '{}' 的版本，newVersionId '{}'、itemsTotal '{}'、baseVersionId '{}'、batchGroupId '{}'",
                datasetId, newVersionId, itemsTotal, baseVersionId, batchGroupId);

        String versionHash = CommitUtils.getCommit(newVersionId);

        return template.inTransaction(WRITE, handle -> {
            var datasetVersionDAO = handle.attach(DatasetVersionDAO.class);

            // 针对基础版本（如果存在）计算差异统计
            DatasetVersionDiffStats diffStats;
            if (baseVersionId != null) {
                diffStats = calculateDiffStatistics(datasetId, baseVersionId, newVersionId,
                        workspaceId, userName);
            } else {
                // 第一个版本——所有条目都是“新增”
                diffStats = new DatasetVersionDiffStats(itemsTotal, 0, 0, 0);
            }

            log.info("数据集 '{}' 的增量差异：added='{}'、modified='{}'、deleted='{}'、unchanged='{}'",
                    datasetId, diffStats.itemsAdded(), diffStats.itemsModified(),
                    diffStats.itemsDeleted(), diffStats.itemsUnchanged());

            // 创建版本记录
            var version = DatasetVersionMapper.INSTANCE.toDatasetVersion(
                    newVersionId, datasetId, versionHash,
                    itemsTotal,
                    diffStats.itemsAdded(),
                    diffStats.itemsModified(),
                    diffStats.itemsDeleted(),
                    DatasetVersionCreate.builder()
                            .tags(tags)
                            .changeDescription(changeDescription)
                            .evaluators(evaluators)
                            .executionPolicy(executionPolicy)
                            .build(),
                    userName);

            EntityConstraintHandler.handle(() -> {
                if (baseVersionId != null) {
                    datasetVersionDAO.insertWithBaseVersion(version, baseVersionId, clearExecutionPolicy, workspaceId);
                } else {
                    datasetVersionDAO.insert(version, workspaceId);
                }
                return version;
            }).withError(() -> new EntityAlreadyExistsException(
                    new ErrorMessage(List.of(ERROR_VERSION_HASH_EXISTS.formatted(datasetId)))));

            log.info("已创建哈希为 '{}' 的版本（数据集 '{}'）", versionHash, datasetId);

            // 如果提供了 batch_group_id 则关联
            if (batchGroupId != null) {
                datasetVersionDAO.updateBatchGroupId(newVersionId, batchGroupId, workspaceId, userName);
                log.info("已将 batch_group_id '{}' 与版本 '{}' 关联（数据集 '{}'）",
                        batchGroupId, versionHash, datasetId);
            }

            // 将 'latest' 标签翻转到新版本。在锁下从当前最新版本分支出去的调用方
            // 传 enforceLatestCas=true，以便翻转针对该基础版本进行 compare-and-swap；
            // 有意从非最新基础版本分支出去的调用方（override 的 applyDeltaChanges）
            // 传 false 以选择退出。
            UUID casBase = enforceLatestCas ? baseVersionId : null;
            flipLatestTag(datasetVersionDAO, datasetId, newVersionId, casBase, userName, workspaceId);
            log.info("已将 '{}' 标签添加到版本 '{}'（数据集 '{}'）", LATEST_TAG, versionHash, datasetId);

            // 从请求添加自定义标签
            insertTags(datasetVersionDAO, datasetId, newVersionId, tags, userName, workspaceId);

            return datasetVersionDAO.findById(newVersionId, workspaceId).orElseThrow();
        });
    }

    /**
     * 在单个批量操作中为数据集版本插入多个标签。
     * 插入前过滤掉空白标签和重复标签。
     *
     * @throws EntityAlreadyExistsException 如果任何标签已存在于该数据集
     */
    private void insertTags(DatasetVersionDAO dao, UUID datasetId, UUID versionId,
            Collection<String> tags, String userName, String workspaceId) {
        if (CollectionUtils.isEmpty(tags)) {
            return;
        }

        List<String> validTags = tags.stream()
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();

        if (validTags.isEmpty()) {
            return;
        }

        EntityConstraintHandler.handle(() -> {
            dao.insertTags(datasetId, validTags, versionId, userName, workspaceId);
            return null;
        }).withError(() -> new EntityAlreadyExistsException(
                new ErrorMessage(List.of("One or more tags already exist for this dataset"))));

        log.info("已添加 '{}' 个标签到数据集 '{}' 的版本", validTags.size(), datasetId);
    }

    @Override
    public void createTag(@NonNull UUID datasetId, @NonNull String versionHash,
            @NonNull DatasetVersionTag tagRequest) {
        log.info("创建标签，tag='{}'、version='{}'、dataset='{}'", tagRequest.tag(), versionHash, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            // 按哈希查找版本
            var version = dao.findByHash(datasetId, versionHash, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            ERROR_VERSION_HASH_NOT_FOUND.formatted(versionHash, datasetId)));

            // 插入标签
            EntityConstraintHandler.handle(() -> {
                dao.insertTag(datasetId, tagRequest.tag(), version.id(), userName, workspaceId);
                return null;
            }).withError(() -> new EntityAlreadyExistsException(
                    new ErrorMessage(List.of(ERROR_TAG_EXISTS.formatted(tagRequest.tag())))));

            return null;
        });

        log.info("已创建标签，tag='{}'、version='{}'、dataset='{}'", tagRequest.tag(), versionHash, datasetId);
    }

    @Override
    public void deleteTag(@NonNull UUID datasetId, @NonNull String tag) {
        log.info("删除标签，tag='{}'、dataset='{}'", tag, datasetId);

        // 防止删除 'latest' 标签——它是自动管理的
        if (LATEST_TAG.equals(tag)) {
            throw new ClientErrorException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity(new ErrorMessage(
                                    List.of(ERROR_CANNOT_DELETE_LATEST_TAG.formatted(LATEST_TAG))))
                            .build());
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            dao.deleteTag(datasetId, tag, workspaceId);
            return null;
        });

        log.info("已删除标签，tag='{}'、dataset='{}'", tag, datasetId);
    }

    @Override
    public DatasetVersion updateVersion(@NonNull UUID datasetId, @NonNull String versionHash,
            @NonNull DatasetVersionUpdate request) {
        log.info("更新版本，hash='{}'、dataset='{}'", versionHash, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            // 按哈希查找版本
            var version = dao.findByHash(datasetId, versionHash, workspaceId)
                    .orElseThrow(() -> new NotFoundException(
                            ERROR_VERSION_HASH_NOT_FOUND.formatted(versionHash, datasetId)));

            // 如果提供了 change_description 则更新
            if (request.changeDescription() != null) {
                dao.updateChangeDescription(version.id(), request.changeDescription(), userName, workspaceId);
                log.info("已更新版本 '{}' 的 change_description（数据集 '{}'）", versionHash, datasetId);
            }

            // 如果提供了新标签则添加
            insertTags(dao, datasetId, version.id(), request.tagsToAdd(), userName, workspaceId);

            log.info("已更新版本，hash='{}'、dataset='{}'", versionHash, datasetId);
            return dao.findById(version.id(), workspaceId).orElseThrow();
        });
    }

    @Override
    public UUID resolveVersionId(@NonNull String workspaceId, @NonNull UUID datasetId, @NonNull String hashOrTag) {
        log.info("解析版本 ID，hashOrTag='{}'、dataset='{}'", hashOrTag, datasetId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            return dao.findVersionIdByHash(datasetId, hashOrTag, workspaceId)
                    .or(() -> dao.findVersionIdByTag(datasetId, hashOrTag, workspaceId))
                    .orElseThrow(() -> new NotFoundException(
                            ERROR_VERSION_NOT_FOUND.formatted(hashOrTag, datasetId)));
        });
    }

    @Override
    public DatasetVersionDiff compareVersions(@NonNull UUID datasetId, @NonNull String fromHashOrTag,
            String toHashOrTag) {

        log.info("比较版本：from='{}'、to='{}'、dataset='{}'", fromHashOrTag, toHashOrTag, datasetId);

        String workspaceId = requestContext.get().getWorkspaceId();

        // 解析 'from' 和 'to' 版本 ID
        UUID fromVersionId = resolveVersionId(workspaceId, datasetId, fromHashOrTag);
        UUID toVersionId = Optional.ofNullable(toHashOrTag)
                .map(hashOrTag -> resolveVersionId(workspaceId, datasetId, hashOrTag))
                .orElse(null);
        var stats = calculateDiffStatistics(datasetId, fromVersionId, toVersionId);

        String toVersionLabel = toHashOrTag != null ? toHashOrTag : "draft";

        log.info("已计算差异：from='{}'、to='{}'、added='{}'、modified='{}'、deleted='{}'、unchanged='{}'",
                fromHashOrTag, toVersionLabel,
                stats.itemsAdded(), stats.itemsModified(),
                stats.itemsDeleted(), stats.itemsUnchanged());

        return DatasetVersionDiff.builder()
                .fromVersion(fromHashOrTag)
                .toVersion(toVersionLabel)
                .statistics(stats)
                .build();
    }

    private Mono<List<DatasetItemIdAndHash>> getItems(UUID datasetId, UUID versionId, String userName,
            String workspaceId) {
        return datasetItemVersionDAO.getItemIdsAndHashes(datasetId, versionId)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .collectList();
    }

    private DatasetVersionDiffStats calculateDiffStatistics(UUID datasetId, UUID fromVersionId, UUID toVersionId) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();
        return calculateDiffStatistics(datasetId, fromVersionId, toVersionId, workspaceId, userName);
    }

    private DatasetVersionDiffStats calculateDiffStatistics(UUID datasetId, UUID fromVersionId, UUID toVersionId,
            String workspaceId, String userName) {
        var fromItems = datasetItemVersionDAO.getItemIdsAndHashes(datasetId, fromVersionId)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .collectList()
                .block();

        var toItems = getItems(datasetId, toVersionId, userName, workspaceId).block();

        return calculateDiffStatistics(fromItems, toItems);
    }

    /**
     * 计算两个条目列表（按 ID 和哈希标识）之间的差异统计。
     * 按 itemId 比较条目，并检测新增、删除、修改和未更改的条目。
     */
    private static DatasetVersionDiffStats calculateDiffStatistics(List<DatasetItemIdAndHash> fromItems,
            List<DatasetItemIdAndHash> toItems) {

        log.debug("计算差异：fromItems count='{}'、toItems count='{}'", fromItems.size(), toItems.size());

        // 构建映射以便按 itemId 高效查找
        var fromMap = fromItems.stream()
                .collect(Collectors.toMap(DatasetItemIdAndHash::itemId, item -> item));

        var toMap = toItems.stream()
                .collect(Collectors.toMap(DatasetItemIdAndHash::itemId, item -> item));

        var fromIds = fromMap.keySet();
        var toIds = toMap.keySet();

        // 计算新增条目（在 'to' 中但不在 'from' 中）
        var addedIds = CollectionUtils.subtract(toIds, fromIds);
        int added = addedIds.size();

        // 计算删除条目（在 'from' 中但不在 'to' 中）
        var deletedIds = CollectionUtils.subtract(fromIds, toIds);
        int deleted = deletedIds.size();

        // 计算修改和未更改的条目（两个版本中都存在的条目）
        var commonIds = CollectionUtils.intersection(fromIds, toIds);
        int modified = 0;
        int unchanged = 0;

        for (UUID itemId : commonIds) {
            var fromItem = fromMap.get(itemId);
            var toItem = toMap.get(itemId);

            // 比较数据哈希
            boolean dataChanged = fromItem.dataHash() != toItem.dataHash();

            // 将标签作为集合比较（顺序无关）
            boolean tagsChanged = !toTagSet(fromItem.tags()).equals(toTagSet(toItem.tags()));

            boolean evaluatorsChanged = fromItem.evaluatorsHash() != toItem.evaluatorsHash();
            boolean executionPolicyChanged = fromItem.executionPolicyHash() != toItem.executionPolicyHash();
            boolean descriptionChanged = fromItem.descriptionHash() != toItem.descriptionHash();

            if (dataChanged || tagsChanged || evaluatorsChanged || executionPolicyChanged || descriptionChanged) {
                modified++;
            } else {
                unchanged++;
            }
        }

        log.info("已计算差异：added='{}'、modified='{}'、deleted='{}'、unchanged='{}'",
                added, modified, deleted, unchanged);

        return new DatasetVersionDiffStats(added, modified, deleted, unchanged);
    }

    /**
     * 将标签集合转换为非 null 的 Set 以进行顺序无关的比较。
     * 如果输入为 null，则返回空集合。
     *
     * @param tags 要转换的标签集合，可为 null
     * @return 包含标签的 Set，如果输入为 null 则为空集合
     */
    private static Set<String> toTagSet(Set<String> tags) {
        return Optional.ofNullable(tags).orElseGet(Set::of);
    }

    @Override
    public Mono<DatasetVersion> restoreVersion(@NonNull UUID datasetId, @NonNull String versionRef) {
        log.info("将数据集 '{}' 恢复到版本 '{}'", datasetId, versionRef);

        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // 在与其他创建版本的写入者相同的按数据集锁下串行化恢复，以便
        // 并发的条目写入不会在恢复中途移动 'latest' 并与翻转发生竞争（OPIK-7264）。
        Mono<DatasetVersion> restore = Mono
                .fromCallable(() -> buildRestoreContext(datasetId, versionRef, workspaceId, userName))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(context -> {
                    if (context.isLatestVersion) {
                        log.info("版本 '{}' 已是数据集 '{}' 的最新版本，原样返回",
                                versionRef, datasetId);
                        return Mono.just(context.sourceVersion);
                    }
                    return createRestoredVersion(datasetId, versionRef, context);
                });

        Duration lockLease = config.getDatasetVersioning().lockLease().toJavaDuration();
        return lockService.executeWithLockCustomExpire(
                new LockService.Lock(datasetId, DATASET_VERSION_LOCK), restore, lockLease);
    }

    private RestoreContext buildRestoreContext(UUID datasetId, String versionRef,
            String workspaceId, String userName) {
        UUID sourceVersionId = resolveVersionId(workspaceId, datasetId, versionRef);

        DatasetVersion sourceVersion = template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);
            return dao.findById(sourceVersionId, workspaceId).orElseThrow(
                    () -> new NotFoundException(ERROR_VERSION_NOT_FOUND.formatted(versionRef, datasetId)));
        });

        Optional<DatasetVersion> latestVersion = getLatestVersion(datasetId, workspaceId);
        boolean isLatestVersionFlag = latestVersion.isPresent()
                && latestVersion.get().id().equals(sourceVersionId);

        return new RestoreContext(sourceVersionId, sourceVersion, latestVersion.orElse(null),
                isLatestVersionFlag, workspaceId, userName);
    }

    private Mono<DatasetVersion> createRestoredVersion(UUID datasetId, String versionRef, RestoreContext context) {
        log.info("通过复制版本 '{}' 的条目为数据集 '{}' 创建新版本", versionRef, datasetId);

        UUID newVersionId = idGenerator.generateId();
        String newVersionHash = CommitUtils.getCommit(newVersionId);

        return copyItemsToNewVersion(datasetId, context, newVersionId)
                .flatMap(copiedCount -> {
                    log.info("已复制 '{}' 个条目（从版本 '{}' 到新版本 '{}'，数据集 '{}'）",
                            copiedCount, versionRef, newVersionHash, datasetId);
                    return createRestoredVersionMetadata(datasetId, versionRef, context,
                            newVersionId, newVersionHash, copiedCount.intValue());
                });
    }

    private Mono<Long> copyItemsToNewVersion(UUID datasetId, RestoreContext context, UUID newVersionId) {
        // 根据源版本条目数生成 UUID 池
        int sourceItemCount = context.sourceVersion.itemsTotal();
        List<UUID> uuids = generateUuidPool(idGenerator, sourceItemCount);

        return datasetItemVersionDAO
                .copyVersionItems(datasetId, context.sourceVersionId, datasetId, newVersionId, null, uuids)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, context.userName)
                        .put(RequestContext.WORKSPACE_ID, context.workspaceId));
    }

    private Mono<DatasetVersion> createRestoredVersionMetadata(UUID datasetId, String versionRef,
            RestoreContext context, UUID newVersionId, String newVersionHash, int itemsTotal) {
        return Mono.fromCallable(() -> template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DatasetVersionDAO.class);

            DatasetVersionDiffStats diffStats = calculateRestoreDiffStats(datasetId, context, newVersionId);
            log.info("数据集 '{}' 的恢复差异：added='{}'、modified='{}'、deleted='{}'、unchanged='{}'",
                    datasetId, diffStats.itemsAdded(), diffStats.itemsModified(),
                    diffStats.itemsDeleted(), diffStats.itemsUnchanged());

            var version = DatasetVersionMapper.INSTANCE.toDatasetVersion(
                    newVersionId, datasetId, newVersionHash, itemsTotal,
                    diffStats.itemsAdded(), diffStats.itemsModified(), diffStats.itemsDeleted(),
                    DatasetVersionCreate.builder()
                            .changeDescription("Restored from version: " + versionRef)
                            .evaluators(context.sourceVersion.evaluators())
                            .executionPolicy(context.sourceVersion.executionPolicy())
                            .build(),
                    context.userName);

            insertVersionAndUpdateTags(dao, datasetId, version, newVersionId, context);

            log.info("已创建恢复版本 '{}'（数据集 '{}'）", newVersionHash, datasetId);
            return dao.findById(newVersionId, context.workspaceId).orElseThrow();
        })).subscribeOn(Schedulers.boundedElastic());
    }

    private DatasetVersionDiffStats calculateRestoreDiffStats(UUID datasetId, RestoreContext context,
            UUID newVersionId) {
        if (context.previousLatestVersion == null) {
            return new DatasetVersionDiffStats(0, 0, 0, 0);
        }
        return calculateDiffStatistics(datasetId, context.previousLatestVersion.id(),
                newVersionId, context.workspaceId, context.userName);
    }

    private void insertVersionAndUpdateTags(DatasetVersionDAO dao, UUID datasetId,
            DatasetVersion version, UUID newVersionId, RestoreContext context) {
        EntityConstraintHandler.handle(() -> {
            dao.insert(version, context.workspaceId);
            return version;
        }).withError(() -> new EntityAlreadyExistsException(
                new ErrorMessage(List.of(ERROR_VERSION_HASH_EXISTS.formatted(datasetId)))));

        UUID casBase = Optional.ofNullable(context.previousLatestVersion)
                .map(DatasetVersion::id)
                .orElse(null);
        flipLatestTag(dao, datasetId, newVersionId, casBase, context.userName, context.workspaceId);
    }

    /**
     * 将 'latest' 标签移动到 {@code newVersionId}。当 {@code casBase} 非 null 时，翻转会
     * 针对它进行 compare-and-swap：仅当旧标签仍指向 {@code casBase} 时才移除，
     * 否则说明并发写入者已经移动了 'latest'，我们会以可重试的 409 中止，而不是
     * 静默覆盖其版本（OPIK-7264 兜底）。当 {@code casBase} 为 null 时则无条件翻转
     * （第一个版本，或自行管理并发的调用方）。
     */
    private void flipLatestTag(DatasetVersionDAO dao, UUID datasetId, UUID newVersionId, UUID casBase,
            String userName, String workspaceId) {
        if (casBase != null) {
            int swapped = dao.deleteTagIfVersion(datasetId, LATEST_TAG, casBase, workspaceId);
            if (swapped != 1) {
                log.warn(
                        "检测到并发的 'latest' 移动：CAS 失败。workspaceId='{}'、datasetId='{}'、casBase='{}'、newVersionId='{}'",
                        workspaceId, datasetId, casBase, newVersionId);
                throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                        .entity(new ErrorMessage(List.of(ERROR_LATEST_MOVED.formatted(datasetId))))
                        .build());
            }
        } else {
            dao.deleteTag(datasetId, LATEST_TAG, workspaceId);
        }
        dao.insertTag(datasetId, LATEST_TAG, newVersionId, userName, workspaceId);
    }

    private record RestoreContext(UUID sourceVersionId, DatasetVersion sourceVersion,
            DatasetVersion previousLatestVersion, boolean isLatestVersion, String workspaceId, String userName) {
    }
}
