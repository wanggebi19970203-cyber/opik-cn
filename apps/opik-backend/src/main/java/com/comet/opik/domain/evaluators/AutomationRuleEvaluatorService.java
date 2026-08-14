package com.comet.opik.domain.evaluators;

import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.api.evaluators.ProjectReference;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.AutomationRuleEvaluatorSortingFactory;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.cache.CacheEvict;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.LogItem.LogPage;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluator.AutomationRuleEvaluatorPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AutomationRuleEvaluatorServiceImpl.class)
public interface AutomationRuleEvaluatorService {

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> T save(T automationRuleEvaluator,
            @NonNull Set<UUID> projectIds,
            @NonNull String workspaceId, @NonNull String userName);

    void update(@NonNull UUID id, @NonNull Set<UUID> projectIds, @NonNull String workspaceId, @NonNull String userName,
            AutomationRuleEvaluatorUpdate<?, ?> automationRuleEvaluator);

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> T findById(@NonNull UUID id, Set<UUID> projectIds,
            @NonNull String workspaceId);

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findByIds(@NonNull Set<UUID> ids,
            Set<UUID> projectIds,
            @NonNull String workspaceId);

    void delete(@NonNull Set<UUID> ids, Set<UUID> projectIds, @NonNull String workspaceId);

    AutomationRuleEvaluatorPage find(int page, int size,
            @NonNull AutomationRuleEvaluatorSearchCriteria searchCriteria,
            @NonNull String workspaceId,
            @NonNull List<String> sortableBy);

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findAll(@NonNull UUID projectId,
            @NonNull String workspaceId);

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findAll(@NonNull UUID projectId,
            @NonNull String workspaceId, AutomationRuleEvaluatorType type);

    Mono<LogPage> getLogs(LogCriteria criteria);

    void evictCache(String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AutomationRuleEvaluatorServiceImpl implements AutomationRuleEvaluatorService {

    private static final String EVALUATOR_ALREADY_EXISTS = "AutomationRuleEvaluator already exists";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull AutomationRuleEvaluatorLogsDAO logsDAO;
    private final @NonNull OpikConfiguration opikConfiguration;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull AutomationRuleEvaluatorSortingFactory sortingFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull ProjectService projectService;

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "'*-' + $workspaceId + '-*'", keyUsesPatternMatching = true)
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> T save(@NonNull T inputRuleEvaluator,
            @NonNull Set<UUID> projectIds, @NonNull String workspaceId, @NonNull String userName) {

        UUID id = idGenerator.generateId();
        IdGenerator.validateVersion(id, "AutomationRuleEvaluator");
        // projectIds 在持久化时不进行存在性检查，因此强制使用 v7 以避免存储孤立的 v4 id。
        projectIds.forEach(projectId -> idGenerator.validateIdNotInFutureIfPresent(projectId, "project"));

        // 双字段同步：第一个 projectId 会成为旧版 project_id 字段
        UUID primaryProjectId = projectIds.isEmpty() ? null : projectIds.iterator().next();

        var savedEvaluator = template.inTransaction(WRITE, handle -> {
            var evaluatorsDAO = handle.attach(AutomationRuleEvaluatorDAO.class);
            var projectsDAO = handle.attach(AutomationRuleProjectsDAO.class);
            var ruleDAO = handle.attach(AutomationRuleDAO.class);

            // 当名称与同一项目中的现有规则冲突时，自动为名称添加后缀（OPIK-7371）。
            // 名称在数据库层面并不唯一，否则重新运行 SDK 脚本会创建在 UI 中无法区分的规则。
            String requestedName = inputRuleEvaluator.getName();
            String uniqueName = resolveUniqueName(ruleDAO, requestedName, projectIds, workspaceId, null);

            AutomationRuleEvaluatorModel<?> evaluator = switch (inputRuleEvaluator) {
                case AutomationRuleEvaluatorLlmAsJudge llmAsJudge -> {
                    var definition = llmAsJudge.toBuilder()
                            .id(id)
                            .name(uniqueName)
                            .projectId(primaryProjectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorUserDefinedMetricPython userDefinedMetricPython -> {
                    if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                        throw new ServerErrorException("Python evaluator is disabled", 501);
                    }
                    var definition = userDefinedMetricPython.toBuilder()
                            .id(id)
                            .name(uniqueName)
                            .projectId(primaryProjectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorTraceThreadLlmAsJudge traceThreadLlmAsJudge -> {
                    var definition = traceThreadLlmAsJudge.toBuilder()
                            .id(id)
                            .name(uniqueName)
                            .projectId(primaryProjectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython userDefinedMetricPython -> {
                    if (!opikConfiguration.getServiceToggles().isTraceThreadPythonEvaluatorEnabled()) {
                        throw new ServerErrorException("Python evaluator is disabled", 501);
                    }
                    var definition = userDefinedMetricPython.toBuilder()
                            .id(id)
                            .name(uniqueName)
                            .projectId(primaryProjectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorSpanLlmAsJudge spanLlmAsJudge -> {
                    var definition = spanLlmAsJudge.toBuilder()
                            .id(id)
                            .name(uniqueName)
                            .projectId(primaryProjectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorSpanUserDefinedMetricPython spanUserDefinedMetricPython -> {
                    if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                        throw new ServerErrorException("Python evaluator is disabled", 501);
                    }
                    var definition = spanUserDefinedMetricPython.toBuilder()
                            .id(id)
                            .name(uniqueName)
                            .projectId(primaryProjectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
            };

            if (evaluator.triggerScope() == null) {
                evaluator = evaluator.withTriggerScope(EvalTriggerScope.PRODUCTION);
            }

            try {
                log.debug("正在创建 {} AutomationRuleEvaluator，id 为 '{}'，projectIds 为 '{}'，workspaceId 为 '{}'",
                        evaluator.type(), id, evaluator.projectIds(), workspaceId);

                evaluatorsDAO.saveBaseRule(evaluator, workspaceId);
                evaluatorsDAO.saveEvaluator(evaluator);

                // 保存项目关联
                log.debug("正在保存 {} 个规则 '{}' 的项目关联", projectIds.size(), id);
                projectsDAO.saveRuleProjects(id, projectIds, workspaceId);

                return evaluator;
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.info(EVALUATOR_ALREADY_EXISTS, e);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(EVALUATOR_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }
        });

        logSuffixApplied(inputRuleEvaluator.getName(), savedEvaluator.name(), workspaceId);

        return findById(savedEvaluator.id(), savedEvaluator.projectIds(), workspaceId);
    }

    /**
     * 解析在目标项目内空闲的名称，冲突时追加 {@code -N} 后缀（OPIK-7371）。
     * 创建和更新共用此方法，使后缀规则不会在两者之间产生漂移。
     * {@code excludeRuleId} 在创建时为 null；在更新时它是正在被编辑的规则，
     * 因此不会被视为与其自身当前名称冲突。
     */
    private String resolveUniqueName(AutomationRuleDAO ruleDAO, String requestedName, Set<UUID> projectIds,
            String workspaceId, UUID excludeRuleId) {
        // 仅获取与请求前缀共享的名称，使候选集合保持较小。
        Set<String> candidates = ruleDAO.findCandidateNames(projectIds, workspaceId,
                AutomationRuleNames.likePrefix(requestedName), excludeRuleId);
        return AutomationRuleNames.generateUniqueName(requestedName, candidates);
    }

    // 在写事务提交后（而非事务内）记录日志，这样回滚的写入绝不会留下误导性的日志行。
    // 值跟在固定文本之后，使前缀在生产环境中保持可 grep。
    private void logSuffixApplied(String requestedName, String appliedName, String workspaceId) {
        if (appliedName != null && !appliedName.equals(requestedName)) {
            log.info("自动化规则名称在项目范围内已存在，已使用新名称存储："
                    + "requestedName '{}'、appliedName '{}'、workspaceId '{}'",
                    requestedName, appliedName, workspaceId);
        }
    }

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "'*-' + $workspaceId + '-*'", keyUsesPatternMatching = true)
    public void update(@NonNull UUID id, @NonNull Set<UUID> projectIds, @NonNull String workspaceId,
            @NonNull String userName, @NonNull AutomationRuleEvaluatorUpdate<?, ?> evaluatorUpdate) {

        projectIds.forEach(projectId -> idGenerator.validateIdNotInFutureIfPresent(projectId, "project"));

        log.debug("正在更新 AutomationRuleEvaluator，id 为 '{}'，projectIds 为 '{}'，workspaceId 为 '{}'", id,
                projectIds,
                workspaceId);
        String requestedName = evaluatorUpdate.getName();
        String appliedName = template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var projectsDAO = handle.attach(AutomationRuleProjectsDAO.class);
            var ruleDAO = handle.attach(AutomationRuleDAO.class);

            try {
                String filtersJson = AutomationModelEvaluatorMapper.INSTANCE.map(evaluatorUpdate.getFilters());

                // 仅在真正重命名时才解析唯一名称。非名称编辑（采样率、enabled、
                // filters）绝不能重命名规则，即使项目中已存在同名规则（例如旧版重复项）。
                // 该守卫保留在 resolveUniqueName 之外，因为它特定于更新场景 ——
                // 创建时没有可比较的当前名称（OPIK-7371）。
                String currentName = ruleDAO.findNameById(id, workspaceId).orElse(null);
                String uniqueName = Objects.equals(requestedName, currentName)
                        ? requestedName
                        : resolveUniqueName(ruleDAO, requestedName, projectIds, workspaceId, id);

                // 更新基础规则（项目关联在联结表中单独处理）
                var triggerScope = evaluatorUpdate.getTriggerScope() != null
                        ? evaluatorUpdate.getTriggerScope()
                        : EvalTriggerScope.PRODUCTION;
                int resultBase = dao.updateBaseRule(id, workspaceId, uniqueName,
                        evaluatorUpdate.getSamplingRate(), evaluatorUpdate.isEnabled(),
                        triggerScope, filtersJson);

                // 更新联结表中的项目关联
                projectsDAO.deleteByRuleIds(Set.of(id), workspaceId);
                projectsDAO.saveRuleProjects(id, projectIds, workspaceId);

                // 清除旧版 project_id 字段以防止出现陈旧数据
                dao.clearLegacyProjectId(id, workspaceId);

                AutomationRuleEvaluatorModel<?> modelUpdate = switch (evaluatorUpdate) {
                    case AutomationRuleEvaluatorUpdateLlmAsJudge evaluatorUpdateLlmAsJudge ->
                        LlmAsJudgeAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE.map(evaluatorUpdateLlmAsJudge.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    case AutomationRuleEvaluatorUpdateUserDefinedMetricPython evaluatorUpdateUserDefinedMetricPython -> {
                        if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                            throw new ServerErrorException("Python evaluator is disabled", 501);
                        }
                        yield UserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateUserDefinedMetricPython.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    }
                    case AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge evaluatorUpdateTraceThreadLlmAsJudge ->
                        TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateTraceThreadLlmAsJudge.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    case AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython evaluatorUpdateTraceThreadUserDefinedMetricPython -> {
                        if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                            throw new ServerErrorException("Python evaluator is disabled", 501);
                        }
                        yield TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateTraceThreadUserDefinedMetricPython.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    }
                    case AutomationRuleEvaluatorUpdateSpanLlmAsJudge evaluatorUpdateSpanLlmAsJudge ->
                        SpanLlmAsJudgeAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateSpanLlmAsJudge.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    case AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython evaluatorUpdateSpanUserDefinedMetricPython -> {
                        if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                            throw new ServerErrorException("Python evaluator is disabled", 501);
                        }
                        yield SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateSpanUserDefinedMetricPython.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    }
                };

                int resultEval = dao.updateEvaluator(id, modelUpdate);

                if (resultEval == 0 || resultBase == 0) {
                    throw newNotFoundException();
                }

                return uniqueName;
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.info(EVALUATOR_ALREADY_EXISTS);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(EVALUATOR_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }
        });

        logSuffixApplied(requestedName, appliedName, workspaceId);
    }

    @Override
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> T findById(@NonNull UUID id,
            Set<UUID> projectIds,
            @NonNull String workspaceId) {
        log.debug("正在查找 AutomationRuleEvaluator，id 为 '{}'，projectIds 为 '{}'，workspaceId 为 '{}'", id,
                projectIds,
                workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var singleIdSet = Collections.singleton(id);
            var criteria = AutomationRuleEvaluatorCriteria.builder().ids(singleIdSet).build();
            List<AutomationRuleEvaluatorModel<?>> models = findRulesWithProjects(dao, workspaceId, projectIds,
                    criteria, null, null, Map.of(), null, null);

            // 为向后兼容，用项目名称丰富模型
            List<AutomationRuleEvaluatorModel<?>> enrichedModels = enrichWithProjectNames(models, workspaceId);

            return enrichedModels.stream()
                    .findFirst()
                    .map(ruleEvaluator -> (AutomationRuleEvaluator<?, ?>) switch (ruleEvaluator) {
                        case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                        case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                        case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel traceThreadLlmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadLlmAsJudge);
                        case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel traceThreadUserDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadUserDefinedMetricPython);
                        case SpanLlmAsJudgeAutomationRuleEvaluatorModel spanLlmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(spanLlmAsJudge);
                        case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel spanUserDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(spanUserDefinedMetricPython);
                    })
                    .map(evaluator -> (T) evaluator)
                    .orElseThrow(this::newNotFoundException);
        });
    }

    @Override
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findByIds(@NonNull Set<UUID> ids,
            Set<UUID> projectIds,
            @NonNull String workspaceId) {
        log.debug("正在查找 AutomationRuleEvaluators，ids 为 '{}'，projectIds 为 '{}'，workspaceId 为 '{}'", ids,
                projectIds, workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var criteria = AutomationRuleEvaluatorCriteria.builder().ids(ids).build();
            List<AutomationRuleEvaluatorModel<?>> models = findRulesWithProjects(dao, workspaceId, projectIds,
                    criteria, null, null, Map.of(), null, null);

            // 为向后兼容，用项目名称丰富模型
            List<AutomationRuleEvaluatorModel<?>> enrichedModels = enrichWithProjectNames(models, workspaceId);

            return enrichedModels.stream()
                    .map(ruleEvaluator -> (AutomationRuleEvaluator<?, ?>) switch (ruleEvaluator) {
                        case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                        case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                        case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel traceThreadLlmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadLlmAsJudge);
                        case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel traceThreadUserDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadUserDefinedMetricPython);
                        case SpanLlmAsJudgeAutomationRuleEvaluatorModel spanLlmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(spanLlmAsJudge);
                        case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel spanUserDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(spanUserDefinedMetricPython);
                    })
                    .map(evaluator -> (T) evaluator)
                    .toList();
        });
    }

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "'*-' + $workspaceId + '-*'", keyUsesPatternMatching = true)
    public void delete(@NonNull Set<UUID> ids, Set<UUID> projectIds, @NonNull String workspaceId) {
        if (ids.isEmpty()) {
            log.info("删除 AutomationRuleEvaluator：ids 列表为空，直接返回");
            return;
        }

        log.debug("正在删除 ids 为 {} 的 AutomationRuleEvaluators，projectIds 为 '{}'，workspaceId 为 '{}'", ids,
                projectIds, workspaceId);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var projectsDAO = handle.attach(AutomationRuleProjectsDAO.class);
            dao.deleteEvaluatorsByIds(workspaceId, ids);
            projectsDAO.deleteByRuleIds(ids, workspaceId);
            dao.deleteBaseRules(ids, workspaceId);
            return null;
        });
    }

    private NotFoundException newNotFoundException() {
        String message = "AutomationRuleEvaluator not found";
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    @Override
    public AutomationRuleEvaluatorPage find(int pageNum, int size,
            @NonNull AutomationRuleEvaluatorSearchCriteria searchCriteria,
            @NonNull String workspaceId,
            @NonNull List<String> sortableBy) {

        log.debug("正在按 searchCriteria '{}' 在 workspaceId '{}' 中查找 AutomationRuleEvaluators",
                searchCriteria, workspaceId);

        String filtersSQL = Optional.ofNullable(searchCriteria.filters())
                .flatMap(f -> filterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.AUTOMATION_RULE_EVALUATOR))
                .orElse(null);

        Map<String, Object> filterMapping = Optional.ofNullable(searchCriteria.filters())
                .map(filterQueryBuilder::toStateSQLMapping)
                .orElse(Map.of());

        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(
                searchCriteria.sortingFields(),
                sortingFactory.getFieldMapping());

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var criteria = AutomationRuleEvaluatorCriteria.builder()
                    .id(searchCriteria.id())
                    .name(searchCriteria.name())
                    .filters(searchCriteria.filters())
                    .build();
            var total = dao.findCount(workspaceId, searchCriteria.projectId(), criteria);
            var offset = (pageNum - 1) * size;

            List<AutomationRuleEvaluatorModel<?>> models = findRulesWithProjects(dao, workspaceId,
                    searchCriteria.projectId(), criteria, sortingFieldsSql, filtersSQL, filterMapping, offset, size);

            // 为向后兼容，用项目名称丰富模型
            List<AutomationRuleEvaluatorModel<?>> enrichedModels = enrichWithProjectNames(models, workspaceId);

            List<AutomationRuleEvaluator<?, ?>> automationRuleEvaluators = List.copyOf(
                    enrichedModels.stream()
                            .map(evaluator -> switch (evaluator) {
                                case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                                case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                                case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel traceThreadLlmAsJudge ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadLlmAsJudge);
                                case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel traceThreadUserDefinedMetricPython ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadUserDefinedMetricPython);
                                case SpanLlmAsJudgeAutomationRuleEvaluatorModel spanLlmAsJudge ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(spanLlmAsJudge);
                                case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel spanUserDefinedMetricPython ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(spanUserDefinedMetricPython);
                            })
                            .toList());

            log.info("找到 {} 个符合 searchCriteria '{}' 的 AutomationRuleEvaluators", automationRuleEvaluators.size(),
                    searchCriteria);
            return AutomationRuleEvaluatorPage.builder()
                    .page(pageNum)
                    .size(automationRuleEvaluators.size())
                    .total(total)
                    .content(automationRuleEvaluators)
                    .sortableBy(sortableBy)
                    .build();
        });
    }

    /**
     * 查找某个项目的所有自动化规则评估器。
     * <p>
     * <strong>警告：</strong>不要向此方法添加 {@code @Cacheable} 注解。
     * 此方法委托给已具备缓存的 3 参数 {@link #findAll(UUID, String, AutomationRuleEvaluatorType)}。
     * 在此处添加 {@code @Cacheable} 会创建嵌套的缓存操作，进而导致嵌套的
     * {@code Mono.block()} 调用，引发 reactor 线程违规和 Redis 超时异常。
     * </p>
     *
     * @param projectId 项目 ID
     * @param workspaceId 工作空间 ID
     * @param <E> 实体类型
     * @param <F> 过滤器类型
     * @param <T> 自动化规则评估器类型
     * @return 自动化规则评估器列表
     */
    @Override
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findAll(
            @NonNull UUID projectId, @NonNull String workspaceId) {
        return findAll(projectId, workspaceId, null);
    }

    @Override
    @Cacheable(name = "automation_rule_evaluators_find_all", key = "$projectId + '-' + $workspaceId + '-' + ($type != null ? $type : 'all')", returnType = AutomationRuleEvaluator.class, wrapperType = List.class)
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findAll(
            @NonNull UUID projectId, @NonNull String workspaceId, AutomationRuleEvaluatorType type) {
        log.info("正在查找 AutomationRuleEvaluators，projectId '{}'、workspaceId '{}'、type '{}'", projectId,
                workspaceId, type);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var criteria = AutomationRuleEvaluatorCriteria.builder().type(type).build();
            var results = findRulesWithProjects(dao, workspaceId, projectId, criteria);
            log.debug("找到 {} 个评估器，projectId '{}'、workspaceId '{}'、type '{}'",
                    results.size(), projectId, workspaceId, type);

            // 为向后兼容，用项目名称丰富模型
            List<AutomationRuleEvaluatorModel<?>> enrichedModels = enrichWithProjectNames(results, workspaceId);

            return enrichedModels
                    .stream()
                    .map(evaluator -> switch (evaluator) {
                        case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                        case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                        case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel traceThreadLlmAsJudge ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadLlmAsJudge);
                        case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel traceThreadUserDefinedMetricPython ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadUserDefinedMetricPython);
                        case SpanLlmAsJudgeAutomationRuleEvaluatorModel spanLlmAsJudge ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(spanLlmAsJudge);
                        case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel spanUserDefinedMetricPython ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(spanUserDefinedMetricPython);
                    })
                    .toList();
        });
    }

    @Override
    public Mono<LogPage> getLogs(@NonNull LogCriteria criteria) {
        return logsDAO.findLogs(criteria)
                .collectList()
                .map(logs -> LogPage.builder()
                        .content(logs)
                        .page(Optional.ofNullable(criteria.page()).orElse(1))
                        .total(logs.size())
                        .size(logs.size())
                        .build());
    }

    private List<AutomationRuleEvaluatorModel<?>> findRulesWithProjects(
            AutomationRuleEvaluatorDAO dao,
            String workspaceId,
            Set<UUID> projectIds,
            AutomationRuleEvaluatorCriteria criteria,
            String sortingFields,
            String filters,
            Map<String, Object> filterMapping,
            Integer offset,
            Integer limit) {

        // 查询 1：获取不带项目数据的分页规则（无重复）
        var rules = dao.findRulesWithoutProjects(workspaceId, projectIds, criteria.action(), criteria.type(),
                criteria.ids(), criteria.id(), criteria.name(), sortingFields, filters, filterMapping, offset, limit);

        if (rules.isEmpty()) {
            return List.of();
        }

        // 查询 2：批量获取这些规则的项目关联
        var ruleIds = rules.stream().map(AutomationRuleEvaluatorModel::id).toList();
        var projectMappings = dao.findProjectMappings(ruleIds, workspaceId);

        // 将项目 ID 合并到规则中，并带旧版回退（业务逻辑）
        return rules.stream()
                .<AutomationRuleEvaluatorModel<?>>map(rule -> {
                    var projectsFromJunction = projectMappings.getOrDefault(rule.id(), Set.of());

                    // 旧版回退：如果联结表为空但规则带有旧版 project_id，
                    // 则保留旧版值（由行映射器设置）
                    if (projectsFromJunction.isEmpty() && !rule.projectIds().isEmpty()) {
                        // 规则创建于多项目支持之前，使用旧版值
                        return rule;
                    }

                    // 使用联结表数据（新规则/已更新规则）
                    return rule.withProjectIds(projectsFromJunction);
                })
                .toList();
    }

    private List<AutomationRuleEvaluatorModel<?>> findRulesWithProjects(
            AutomationRuleEvaluatorDAO dao,
            String workspaceId,
            UUID projectId,
            AutomationRuleEvaluatorCriteria criteria,
            String sortingFields,
            String filters,
            Map<String, Object> filterMapping,
            Integer offset,
            Integer limit) {
        // 向后兼容：将单个 projectId 转换为集合
        return findRulesWithProjects(dao, workspaceId,
                Optional.ofNullable(projectId).map(Set::of).orElse(null),
                criteria, sortingFields, filters, filterMapping, offset, limit);
    }

    private List<AutomationRuleEvaluatorModel<?>> findRulesWithProjects(
            AutomationRuleEvaluatorDAO dao,
            String workspaceId,
            UUID projectId,
            AutomationRuleEvaluatorCriteria criteria) {
        return findRulesWithProjects(dao, workspaceId, projectId, criteria, null, null, Map.of(), null, null);
    }

    /**
     * 用根据其 projectId 解析出的项目名称来丰富 AutomationRuleEvaluatorModel 列表。
     * 这通过填充旧版 projectName 字段来支持向后兼容。
     *
     * @param models 要丰富的模型
     * @param workspaceId 用于获取项目的工作空间 ID
     * @return 已填充 projectName 的丰富后的模型
     */
    private List<AutomationRuleEvaluatorModel<?>> enrichWithProjectNames(
            List<AutomationRuleEvaluatorModel<?>> models,
            String workspaceId) {

        if (models.isEmpty()) {
            return models;
        }

        // 记录传入的模型以便调试
        models.forEach(model -> log.debug(
                "丰富前的模型 - id：'{}'、projectId：'{}'、projectIds：'{}'",
                model.id(), model.projectId(), model.projectIds()));

        // 从所有模型的 projectIds 集合中提取唯一的项目 ID
        Set<UUID> allProjectIds = models.stream()
                .flatMap(model -> model.projectIds().stream())
                .collect(java.util.stream.Collectors.toSet());

        if (allProjectIds.isEmpty()) {
            return models;
        }

        // 使用 ProjectService 获取项目名称（确保逻辑一致性和前向兼容性）
        Map<UUID, String> projectNameMap = projectService.findIdToNameByIds(workspaceId, allProjectIds);

        // 记录丰富详情
        log.debug("获取到 '{}' 个项目名称，对应 '{}' 个项目 ID", projectNameMap.size(), allProjectIds.size());

        // 为每个模型丰富其项目名称
        List<AutomationRuleEvaluatorModel<?>> enrichedModels = models.stream()
                .<AutomationRuleEvaluatorModel<?>>map(model -> enrichModelWithProjectName(model, projectNameMap))
                .toList();

        // 记录丰富后的模型以便调试
        enrichedModels.forEach(model -> log.debug("丰富后的模型 - id：'{}'、projectId：'{}'",
                model.id(), model.projectId()));

        return enrichedModels;
    }

    /**
     * 用项目引用丰富单个 AutomationRuleEvaluatorModel。
     *
     * @param model 要丰富的模型
     * @param projectNameMap projectId 到 projectName 的映射
     * @return 丰富后的模型
     */
    private AutomationRuleEvaluatorModel<?> enrichModelWithProjectName(
            AutomationRuleEvaluatorModel<?> model,
            Map<UUID, String> projectNameMap) {

        if (model.projectIds().isEmpty()) {
            log.debug("跳过规则 '{}' 的丰富 - 未分配任何项目", model.id());
            return model;
        }

        // 构建 ProjectReference 对象的 SortedSet（唯一、按名称字母顺序排序）
        SortedSet<ProjectReference> projects = model.projectIds().stream()
                .map(id -> {
                    String name = projectNameMap.get(id);
                    if (name == null) {
                        log.warn("未找到 projectId '{}' 在规则 '{}' 中的项目名称", id, model.id());
                        return null;
                    }
                    return new ProjectReference(id, name);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));

        log.debug("已为规则 '{}' 丰富 {} 个项目", model.id(), projects.size());

        // 为向后兼容：从第一个项目推导旧版字段
        UUID projectId = projects.isEmpty() ? null : projects.first().projectId();
        String projectName = projects.isEmpty() ? null : projects.first().projectName();

        // 使用多态方法，用项目和旧版字段更新模型
        return model.withProjectDetails(projectId, projectName, projects);
    }

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "'*-' + $workspaceId + '-*'", keyUsesPatternMatching = true)
    public void evictCache(@NonNull String workspaceId) {
        log.debug("已驱逐工作空间的自动化规则缓存，workspaceId='{}'", workspaceId);
    }
}
