package com.comet.opik.domain;

import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.infrastructure.FilterUtils;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.ErrorInfo.ERROR_INFO_TYPE;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.ExperimentDAO.getFeedbackScores;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.JsonUtils.getJsonNodeOrDefault;
import static com.comet.opik.utils.JsonUtils.getStringOrDefault;

@ImplementedBy(OptimizationDAOImpl.class)
public interface OptimizationDAO {

    record OptimizationSummary(UUID datasetId, long optimizationCount, Instant mostRecentOptimizationAt) {
        public static OptimizationSummary empty(UUID datasetId) {
            return new OptimizationSummary(datasetId, 0, null);
        }
    }

    /**
     * 一个 Studio 优化，其最新状态为非终态且早于 reaper 阈值，
     * 即因为 worker 从未推进它而卡住。携带 {@code workspaceId}，以便 reconciler
     * 能够初始化更新该行并终结其日志所需的工作区上下文（OPIK-7159）。
     */
    @Builder(toBuilder = true)
    record StalledOptimization(@NonNull UUID id, @NonNull String workspaceId, @NonNull OptimizationStatus status) {
    }

    Mono<Void> upsert(Optimization optimization);

    Mono<Optimization> getById(UUID id);

    Mono<List<DatasetEventInfoHolder>> getOptimizationDatasetIds(Set<UUID> ids);

    Mono<Long> delete(Set<UUID> ids);

    Flux<DatasetLastOptimizationCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds);

    /**
     * @param clearErrorInfo 置空 {@code error_info} 列，而不是将其向前携带。仅当
     *                       worker 报告取代了平台检测到的失败（而非 worker 报告该失败）时才为 true：
     *                       记录的原因描述的是一个实际上仍存活的运行，而且没有别的东西会清除该列。
     *                       有意不设计为重载——双参数便捷形式会让调用方（或测试桩）静默地漏掉这个决定。
     */
    Mono<Long> update(UUID id, OptimizationUpdate update, boolean clearErrorInfo);

    Mono<Long> updateDatasetDeleted(Set<UUID> datasetIds);

    Mono<Optimization.OptimizationPage> find(int page, int size, @NonNull OptimizationSearchCriteria searchCriteria);

    Flux<OptimizationSummary> findOptimizationSummaryByDatasetIds(Set<UUID> datasetIds);

    Flux<StalledOptimization> findStalledStudioOptimizations(Duration initializedTimeout, Duration runningTimeout,
            Duration runningHardTimeout, Duration lookbackMargin, int limit, int candidateScanFactor);

    Mono<Boolean> hasRecentStudioActivity(UUID optimizationId, Duration window);

    /**
     * 某个运行的最新状态 + 行时间戳，直接取自 {@code optimizations} 表。reaper 的
     * 更新前重读必须使用它而非 {@link #getById}（带实验/trace/评分 join 的完整 {@code FIND}）：
     * reaper 只需要这两个字段，而且它的存活判定必须与 {@code FIND} 对相关数据的映射解耦——
     * {@code FIND} 曾经会静默地丢弃某个运行，其试验项引用了一个仍未完成的 trace
     * （正是 worker 在试验中途被杀掉后留下的状态；由 OPIK-7459 e2e 发现，在 {@code FIND} 的 NaN 防护中修复），
     * 而空的重读会让 reaper 在每个周期都跳过该运行，复活这个作业存在的目的就是要防止的永恒转圈。
     * 这个裸读使得未来任何 {@code FIND} 回归都无法再次破坏 reaper。
     */
    @Builder(toBuilder = true)
    record OptimizationStatusSnapshot(@NonNull OptimizationStatus status, @NonNull Instant lastUpdatedAt,
            @NonNull Instant startedAt) {
    }

    Mono<OptimizationStatusSnapshot> getStatusSnapshotById(UUID id);

    /**
     * 仅优化行本身——不做实验/trace/评分 join，因此聚合字段
     * （{@code numTrials}、评分、时长、成本）保持为 null。当 {@link #getById} 的完整 {@code FIND}
     * 再次无法映射该运行时的写路径回退（参见 {@link #getStatusSnapshotById}）：
     * 状态更新绝不能因相关数据而被阻塞。
     */
    Mono<Optimization> getRowById(UUID id);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OptimizationDAOImpl implements OptimizationDAO {

    /**
     * 最新行版本卡在非终态、且超过 reaper 阈值的 Studio 运行
     * （OPIK-7159 / OPIK-7459）。按“无存活”或“超过硬上限”两种条件之一进行选择；
     * 这两个条件各自的含义以及为何两者都要存在，参见 {@code OptimizationStalledReaperJob}。
     *
     * <p>存活度取行的 {@code last_updated_at}、最新试验实验的
     * {@code created_at} 和最新实验项的 {@code created_at} 三者中的最新值。{@code last_updated_at}
     * 仅在状态变化时推进，因此另外两者才是让健康的长期运行保持存活的关键：一次试验
     * 最多评估 {@code OPTSTUDIO_DATASET_SAMPLES} 个项，并且可持续数小时，所以仅凭试验创建
     * 会在试验中途产生误报。因为 {@code HAVING} 已经要求行时间戳超过阈值，
     * 存活度就归结为 {@code active_optimizations} 反连接。
     *
     * <p>SQL 不会告诉你、且一旦改动就会破坏查询的事情：
     * <ul>
     * <li>状态/超时谓词必须留在 {@code HAVING} 中——在去重之上、位于
     * {@code WHERE} 之外。放在 {@code WHERE} 中时它们引用聚合函数，ClickHouse 会抛出
     * {@code ILLEGAL_AGGREGATION}；在去重之上也正是阻止运行在达到终态后，
     * 再从陈旧版本中被选中的原因。</li>
     * <li>嵌套的 {@code (workspace_id, experiment_id) IN (SELECT ... FROM candidate_trials)} 是
     * 承重结构。外层 {@code IN} 已使其在正确性上冗余，但它是唯一能
     * 防止项探测扫描部署中每一行近期 {@code experiment_items} 的东西。不要将它简化掉。</li>
     * <li>ClickHouse 内联 {@code WITH} 子查询而不是物化它们，因此被引用
     * N 次的 CTE 会被求值 N 次。一个 tick 会聚合 {@code optimizations} 3 次、扫描
     * {@code experiments} 2 次、{@code experiment_items} 1 次。这是有意保留的：受下方 id 集合限制，
     * 重复的工作量相对 5 分钟节奏而言可以忽略不计。</li>
     * <li>两个探测都按 <em>id 集合</em>限定范围，而不是按时间下限：{@code experiments} 按
     * {@code (workspace_id, optimization_id) IN candidates}（通过
     * {@code idx_experiments_optimization_id}，migration 000069 解析）限定，{@code experiment_items} 按
     * {@code (workspace_id, experiment_id) IN candidate_trials} 限定，后者即主键前缀。
     * {@code created_at} 比较是残余谓词——它们是存活度语义，一旦读取受键限定后就不产生成本。
     * 元组形式也正是让两个探测保持工作区精确的原因。</li>
     * <li>没有 {@code experiments.type} 过滤，不像 {@link #FIND} 的 {@code experiment_candidates}，
     * 后者排除了 {@code 'mini-batch'} / {@code 'mutation'}。那种排除是展示性的；这里唯一的问题
     * 是 worker 是否还在写任何东西。GEPA 在运行的大部分时间里都在记录
     * {@code 'mini-batch'} 评估，所以过滤它们会让健康的运行看起来像是静默的——并连同丢掉
     * 项级别的信号，因为项是通过此扫描的 id 触达的。</li>
     * <li>上限读取 {@code created_at}，而不是 {@code last_updated_at}：对行的每次写入
     * 都会刷新后者，因此 metadata PATCH 或 SDK 重新 upsert 会永远推迟兜底。
     * 它用 {@code argMax(created_at, last_updated_at)} 而非 {@code min(created_at)}，因为旧
     * 版本会一直留在 {@code ReplacingMergeTree} 中——{@code min} 会永远返回第一次
     * 尝试的开始时间，因此在已有 id 下重启的运行会一出生就超过上限。
     * 它启用的重启重置参见 {@code OptimizationService} 中的 upsert 路径。残余的
     * 暴露，已接受：对于在此分支发布前创建的运行，获胜版本携带的
     * {@code created_at} 已被更早的重新 upsert 向前重盖，因此其上限晚于
     * 真实开始时间开始。那只会推迟一次回收，并且一旦重新 upsert 保留该列后就不会再发生。</li>
     * <li>{@code dataset_id} 不在 {@code GROUP BY} 中，尽管它在排序键中：
     * {@code getOrCreateDataset} 按数据集 <em>名称</em> 解析，因此命名了不同数据集的重新 upsert
     * 会写入去重永远不会合并的行，而按完整键分组会把该运行以
     * 相互独立的状态输出两次——让 reaper 基于陈旧的半边将存活的运行置为 ERROR。</li>
     * <li>硬上限分支由 {@code latest_status IN ('initialized', 'running')} 守卫，
     * 而不是提升为裸的顶层 {@code OR}：没有守卫时，每个仅仅完成时间早于上限的
     * 运行都会成为候选，并把真正的卡住挤到 {@code LIMIT} 之外。</li>
     * <li>{@code latest_status} 和 {@code started_at} 来自对元组的一次 {@code argMax}，
     * 而不是对单独列做两次。{@code last_updated_at} 是 {@code DateTime64(6)}，所以两个版本可能在其上并列，
     * 而两个独立的 {@code argMax} 调用各自可以自由选择不同的物理行——
     * 把某个版本的状态和另一个版本的开始时刻组合在一起。一个聚合不会自相矛盾。</li>
     * <li>两个 {@code ORDER BY} 都以 {@code id ASC} 结尾。没有唯一的最终键时，排序在
     * 并列情况下是不稳定的，因此有界前缀在两次遍历之间可能不同——一组并列的健康
     * 行可能不断填满它、被存活度否决丢弃，并无限期地饿死其后的卡住运行。</li>
     * <li>两个 {@code ORDER BY} 都先把硬上限的运行放在前面，然后才按 {@code latest_updated_at} 排序。
     * 仅按时间戳排序看起来自然，却恰恰颠倒了那个承载
     * “永不死锁”保证的分支的优先级：metadata PATCH 或 SDK 重新 upsert 会刷新
     * {@code last_updated_at}，因此仍在接收写入的僵尸运行会排在最后——不像
     * 软超时候选会随时间进入正确位置——永不前进。那样它就可能被下方
     * 的边界从每次遍历中截断掉。</li>
     * </ul>
     */
    private static final String FIND_STALLED_STUDIO_OPTIMIZATIONS = """
            WITH candidates AS (
                SELECT
                    workspace_id,
                    id,
                    argMax(tuple(status, created_at), last_updated_at).1 AS latest_status,
                    argMax(tuple(status, created_at), last_updated_at).2 AS started_at,
                    max(last_updated_at) AS latest_updated_at
                FROM optimizations
                WHERE studio_config != ''
                  AND greaterOrEquals(last_updated_at, subtractSeconds(now64(6), :lookback_seconds))
                GROUP BY workspace_id, id
                HAVING (latest_status IN ('initialized', 'running')
                        AND less(started_at, subtractSeconds(now64(6), :running_hard_timeout_seconds)))
                    OR (latest_status = 'initialized'
                        AND less(latest_updated_at, subtractSeconds(now64(6), :initialized_timeout_seconds)))
                    OR (latest_status = 'running'
                        AND less(latest_updated_at, subtractSeconds(now64(6), :running_timeout_seconds)))
                ORDER BY less(started_at, subtractSeconds(now64(6), :running_hard_timeout_seconds)) DESC,
                         latest_updated_at ASC,
                         id ASC
                LIMIT :candidate_limit
            ), candidate_trials AS (
                SELECT
                    workspace_id,
                    id,
                    optimization_id,
                    created_at
                FROM experiments
                WHERE (workspace_id, optimization_id) IN (SELECT workspace_id, toString(id) FROM candidates)
            ), active_optimizations AS (
                SELECT
                    workspace_id,
                    optimization_id
                FROM candidate_trials
                WHERE greaterOrEquals(created_at, subtractSeconds(now64(6), :running_timeout_seconds))
                   OR (workspace_id, id) IN (
                       SELECT workspace_id, experiment_id
                       FROM experiment_items
                       WHERE (workspace_id, experiment_id) IN (SELECT workspace_id, id FROM candidate_trials)
                         AND greaterOrEquals(created_at, subtractSeconds(now64(6), :running_timeout_seconds))
                   )
            )
            SELECT
                id,
                workspace_id,
                latest_status AS status
            FROM candidates
            WHERE less(started_at, subtractSeconds(now64(6), :running_hard_timeout_seconds))
               OR (workspace_id, toString(id)) NOT IN (
                   SELECT workspace_id, optimization_id FROM active_optimizations
               )
            ORDER BY less(started_at, subtractSeconds(now64(6), :running_hard_timeout_seconds)) DESC,
                     latest_updated_at ASC,
                     id ASC
            LIMIT :limit
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * 按 id 取最新行版本，无 join——参见 {@link #getRowById}。列集合与
     * {@link #mapRowColumns} 读取的完全一致，因此未来加入重量级列也无法静默地扩大这次读取。
     */
    private static final String GET_RAW_BY_ID = """
            SELECT
                id,
                name,
                dataset_id,
                project_id,
                objective_name,
                status,
                metadata,
                studio_config,
                error_info,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by
            FROM optimizations
            WHERE workspace_id = :workspace_id
              AND id = :id
            ORDER BY last_updated_at DESC
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * 供 reaper 使用的裸状态/时间戳重读——参见 {@link #getStatusSnapshotById}。别名
     * 有意与源列名不同：{@code max(last_updated_at) AS last_updated_at}
     * 会让 CH 26.3 分析器把 {@code argMax} 的排序参数解析为该别名（聚合中套聚合，
     * ILLEGAL_AGGREGATION）。
     *
     * <p>{@code latest_status} 和 {@code started_at} 在这里必须解析出与
     * {@link #FIND_STALLED_STUDIO_OPTIMIZATIONS} 相同的值，否则 {@code isPastHardCap} 可能会对
     * 集群查询按软超时选中的某个运行触发——绕过存活度否决，并对一个并未超时的
     * 运行报告“超过了最长运行时间”。有两件事保证这一点，且两者都是承重的：
     * <ul>
     * <li>从获胜版本读取。集群查询在其回溯下限内的版本上聚合，
     * 而这里在所有版本上聚合，但 {@code >=} 下限不可能丢掉携带最大
     * {@code last_updated_at} 的版本，因此两次 {@code argMax} 调用选中的是同一个。这里有意不设下限——
     * 它毫无收益，还可能对某行已老化出窗口的运行返回空结果，
     * 而调用方无法将其与“不再卡住”区分开。</li>
     * <li>相同的 {@code studio_config != ''} 谓词。没有它，两者会在不同的
     * 版本集合上聚合，而不仅是不同的窗口：生产 ClickHouse 没有 read-your-own-writes，所以
     * 看到空 {@code existing} 的 SDK 重新 upsert 会写入一个 {@code studio_config} 为空的
     * 最新版本。集群查询排除该版本并选择更旧的一个；未过滤的
     * 快照会选中它，导致两个字段都不一致。</li>
     * </ul>
     */
    private static final String GET_STATUS_SNAPSHOT = """
            SELECT
                argMax(tuple(status, created_at), last_updated_at).1 AS latest_status,
                argMax(tuple(status, created_at), last_updated_at).2 AS started_at,
                max(last_updated_at) AS latest_updated_at
            FROM optimizations
            WHERE workspace_id = :workspace_id
              AND id = :id
              AND studio_config != ''
            GROUP BY id
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * reaper 查询存活度探测的单运行、工作区范围镜像：该优化在窗口内
     * 是否写入了试验实验或实验项？用作更新前重读守卫（OPIK-7459）——集群范围的
     * reaper 查询与 ERROR 更新并非原子操作，因此在此期间落地的试验或
     * 项必须否决该状态转换，正如状态重读否决终态竞争一样。与集群查询相同的 id 集合限定、
     * 但宽度仅为一个运行：{@code trials} CTE 位于
     * {@code (workspace_id, optimization_id)} 之后——主键的工作区前缀加上
     * {@code optimization_id} 上的 {@code minmax} 索引（migration 000069）——而项探测位于
     * {@code (workspace_id, experiment_id) IN trials} 之后，后者是 {@code experiment_items} 主键
     * 前缀。两者都不需要 {@code created_at} 索引；时间戳是残余谓词。按此运行的试验
     * （而非仅按工作区）限定项的范围，正是让繁忙工作区中无关的
     * 项流量不进扫描的原因。与集群查询一样，{@code trials} 被内联两次（它自身的
     * {@code FROM} 加上嵌套的项 {@code IN}），因此每次调用会扫描 {@code experiments} 两次；
     * 该调用只对尚未超过硬上限的候选发生。
     */
    private static final String HAS_RECENT_STUDIO_ACTIVITY = """
            WITH trials AS (
                SELECT
                    workspace_id,
                    id,
                    created_at
                FROM experiments
                WHERE workspace_id = :workspace_id
                  AND optimization_id = :optimization_id
            )
            SELECT 1
            FROM trials
            WHERE greaterOrEquals(created_at, subtractSeconds(now64(6), :window_seconds))
               OR (workspace_id, id) IN (
                   SELECT workspace_id, experiment_id
                   FROM experiment_items
                   WHERE (workspace_id, experiment_id) IN (SELECT workspace_id, id FROM trials)
                     AND greaterOrEquals(created_at, subtractSeconds(now64(6), :window_seconds))
               )
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * 每个单元格都必须保持为普通绑定占位符：这是 {@code FORMAT Values} 插入，元组单元格中的任何
     * 函数表达式（{@code COALESCE}、{@code parseDateTime64BestEffortOrNull}、
     * {@code now64}）都会触发 ClickHouse 的快路径解析器——插入仍会成功，但每一行
     * 都会静默地使 {@code system.errors} 代码 26 / 27 / 43 / 70 递增并写入 pod stderr
     * （OPIK-5694，参见 {@link ClickHouseDateTimeFormat}）。因此两个 {@code DateTime64(9, 'UTC')} 时间戳
     * 都在 Java 中通过 {@link ClickHouseDateTimeFormat#formatNanos} 格式化，而原先由
     * {@code now64()} 提供的列 DEFAULT 也在 Java 中替换——{@code Instant.toString()}
     * 不行，因为它的 {@code T}/{@code Z} 形式正是快路径所拒绝的。
     */
    private static final String UPSERT = """
            INSERT INTO optimizations (
                id,
                dataset_id,
                name,
                workspace_id,
                project_id,
                objective_name,
                status,
                metadata,
                studio_config,
                error_info,
                created_by,
                last_updated_by,
                last_updated_at,
                created_at
            )
            VALUES (
                :id,
                :dataset_id,
                :name,
                :workspace_id,
                :project_id,
                :objective_name,
                :status,
                :metadata,
                :studio_config,
                :error_info,
                :created_by,
                :last_updated_by,
                :last_updated_at,
                :created_at
            )
            ;
            """;

    /**
     * 本文件中的查询不携带任何 SQL 注释；推理都写在这里，按 CTE 名称索引，
     * 与其他 DAO 一样。保持这种方式，不仅是为了风格一致：查询中仅含
     * {@code --} 的一行会破坏 r2dbc 驱动的占位符扫描器
     * （{@code ClickHouseParameterizedQuery}），从该行起它将停止识别 {@code :params}，因此
     * 其后每个占位符都会原样到达 ClickHouse，语句因
     * {@code Code: 62 Syntax error ... :workspace_id} 而失败——仅一次纯注释的改动就能让每个请求都 500。
     * {@code -- } 后跟任意字符是安全的，但尾随空格离被格式化器抹掉只有一步之遥。
     * 在查询之外写文档可以消除这一隐患，而不是绕着它小心翼翼地走。
     * <p>
     * 带标签的成本 CTE（从 {@code optimization_tagged_trace_ids} 起）有意在
     * {@link #FIND_WITHOUT_EXPERIMENTS} 中重复一份，而不是通过一个模板化常量共享：共享需要一个
     * 每个调用点都必须记得设置的标志，而忘记设置会静默地重复计算试验支出。两份
     * 副本由 {@code OptimizationsResourceTest.GetOptimizerById} 中的
     * {@code findAndGetById__*} 测试保持同步，这些测试各自断言列表和 {@code getById}
     * 报告相同的数字——改动其中一份它们就会失败。
     * <p>
     * 这些 CTE 内读取的 spans 按逻辑 span 键 {@code (workspace_id, project_id, id)} 去重，
     * 按存储排序键顺序读取行，因此每个键的最新写入获胜。{@code parent_span_id} 被
     * 有意同时排除在排序元组和 {@code LIMIT 1 BY} 之外，与自 OPIK-7750（#7764）以来的每次 spans
     * 读取保持一致。该列在多次写入之间可变，因此若排在
     * {@code last_updated_at} 之前，它会按最大 parent 而非最新写入来挑选获胜者，并且
     * 在分组内，一个以不同 parent 重新摄取的 span 会保留为两行，其成本
     * 会两次进入这个求和。这两半都由
     * {@code findAndGetById__whenTaggedSpanIsRewrittenUnderAnotherParent__spendIsChargedOnce} 固定住。
     * <p>
     * 注意该键中的作用域：因为 {@code project_id} 是它的一部分，而上方的聚合仅按
     * {@code trace_id} 键控，所以一个写在两个项目下的 span id 会两次通过去重并被求和
     * 两次。这一暴露是既有的，与此处的 {@code experiment_durations} 以及
     * {@code ExperimentDAO} 共享；它有意不在本查询中单独修复，因为只对带标签的
     * 一半去重会让试验那一半重复计费，使同一数字的两半对“规范的每 trace 成本
     * 是什么”产生分歧。OPIK-7691 在所有站点统一覆盖它。
     * <p>
     * 此查询返回的任何数值列都绝不能是 NaN/Inf：行映射器将它们读取为
     * {@code BigDecimal}，{@code BigDecimal.valueOf(NaN)} 会抛出异常，而 clickhouse-r2dbc 驱动
     * 会吞掉映射器异常并静默丢弃该行——该运行随后在 getById 中 404，
     * 并从 find 中消失。两个 float 来源在非有限值可能进入的地方都做了防护：
     * {@code duration_p50}（对零个已完成 trace 的分位数会产生 NaN——worker 在
     * 试验中途被杀掉后留下的状态，OPIK-7459）和 {@code experiment_scores_parsed.value}
     * （JSON 解析，因此输入不受限）。成本是 Decimal，不可能非有限。
     *
     * <p>评分值用 {@code toFloat64OrNull} 解析，而不是 {@code CAST(... AS Float64)}：
     * 该列保存原始 JSON，旧的或外部的写入方可能塑形不同，而
     * <em>具名</em>条目中的非数值会使 {@code CAST} 抛出 {@code CANNOT_PARSE_TEXT}，
     * 让整个端点 500。{@code toFloat64OrNull} 从不抛出，并且
     * {@code isFinite(NULL)} 是 NULL——在 {@code WHERE} 中为假——因此无法解析和非有限的条目
     * 都同样被丢弃。结果再被 {@code assumeNotNull} 包裹，使聚合后的 map 保持为
     * {@code Map(String, Float64)}：{@code getScoresAggregation} 会对每个值调用
     * {@code doubleValue()}，而可空的 map 值会重新引入这篇 javadoc 所说的
     * “吞掉映射器异常导致的行丢失”。{@code WHERE} 已保证该值非空。
     *
     * <p><b>{@code optimization_tagged_trace_ids}</b> 是候选扫描：曾将其中一个优化 id
     * 作为标签携带过的每个 trace。有意做成超集，因为权威
     * 检查在 {@code optimization_tagged_traces} 中对每个 trace 的最新版本运行，所以被后续更新移除的
     * 标签会停止计数。有意不设 {@code created_at} 边界：该列在优化行的重写之间
     * 不稳定，重置会静默地从总数中丢掉优化器内部 trace。没有 {@code project_id} 的
     * 优化早于该列存在，因此其成本仅计试验。
     *
     * <p>它有意 <em>不</em>做的两件事，两者都是在生产上实测而非推测。它不做
     * {@code DISTINCT}：该 CTE 只作为 {@code IN} 集合消费，其自身会去重，
     * 因此 distinct 遍是纯开销（列表 p50 1338 -> 1145 ms，CPU 2198 -> 1910 ms，峰值
     * 内存持平）。并且它不再把 {@code project_id} 限制在范围内的优化上：在
     * 生产规模下该裁剪无论做不做都免费（534 vs 539 ms，峰值内存相同），因此
     * {@code arrayExists} 标签测试被保留为唯一条件。不要用针对 id 的
     * {@code groupArray} 的 {@code hasAny} 替换该测试，实测其延迟为 22 倍、CPU 为 38 倍。
     *
     * <p>对任何推演“多次命名同一个 CTE 的成本”的人的提示：ClickHouse 对每个引用
     * <em>逐个</em>求值，而 {@code EXPLAIN} 无法显示这一点，因为 {@code IN (SELECT ... FROM
     * cte)} 集合是急切构建的，只显示为 {@code trace_id in N-element set}。因此数计划
     * 节点会低估重复次数。一个仅引用次数不同的孤立探测从
     * 2.45 M -> 4.90 M 行、214 -> 398 MiB。
     *
     * <p><b>{@code optimization_tagged_traces}</b> 选择带优化 id 标签但
     * 未关联任何实验项的 trace：优化器内部的 LLM 调用（GEPA 反思、候选
     * 生成），其支出属于运行的总成本，尽管它不属于任何试验（OPIK-7521）。
     * {@code experiment_item} 行尚未可见的试验 trace 会通过此分支
     * 而非 {@code experiment_durations} 计数，并在关联落地后转移过去；无论哪种方式
     * 它都恰好计数一次，因此摄取竞争无法对运行重复计费。
     *
     * <p>它的实验项排除按 {@code (trace, owning optimization)} 键控，而不是仅按 trace。
     * 它的存在是为了阻止一个同时把其运行 id 作为标签携带的试验 trace 被
     * 计费两次——一次通过 {@code experiment_durations}、一次在这里——因此它只能对拥有该试验的
     * 运行触发。仅按 {@code trace_id} 排除会丢掉一个带运行 X 标签的 trace，
     * 只因为它碰巧是运行 Y 的试验，而由于范围内实验的集合在
     * 列表（每个匹配过滤器的优化）与 {@code getById}（一个）之间不同，两者会对
     * 同一运行报告不同的总数。这就是为什么元组测试放在 {@code ARRAY JOIN} 之后、
     * 即 {@code tag} 可用之处，而不是作为子查询中更廉价的 {@code trace_id} 预过滤。
     *
     * <p>{@code project_id} 有意不从该 CTE 中投影出去：那会把一个 trace 拆成
     * 每个曾写入它的项目一行，而成本 join 仅按 {@code trace_id} 键控，
     * 从而把同一支出计费两次。结果是在候选 CTE 中留下了一处作用域依赖：
     * 它裁剪到范围内优化的项目，因此带运行 X 标签但存储在另一个运行项目中的
     * trace 会被列表找到、而不会被 {@code getById} 找到。优化器不会产生
     * 这种形状，而去掉项目边界会把候选扫描变成工作区范围。一个专门的
     * 归属列（OPIK-7691）才是最终解决之道。
     *
     * <p><b>{@code optimization_tagged_costs}</b> 在 {@code project_id} 上裁剪 spans 扫描，因为
     * {@code trace_id} 只是第三主键列。该项目集合来自
     * {@code optimization_final} 而非任一 trace CTE：ClickHouse 按文本替换 CTE，
     * 因此在那里命名一个 trace CTE 会重新运行其标签扫描。相比之下读取
     * {@code optimizations} 很便宜，而候选项目的超集正是前缀裁剪所需的全部；
     * 权威过滤是旁边的 {@code trace_id IN}。
     *
     * <p>在 {@link #FIND_WITHOUT_EXPERIMENTS} 中，同样的两个 trace CTE 出现时没有实验项
     * 排除，在那里没必要，因为该投影仅在范围内没有任何实验时才会被选中，
     * 并且它末尾的 {@code ifNull} 在无任何归属时产生非空零，
     * 与 {@code FIND} 一致——后者对空组的 {@code sum()} 返回 0 而非
     * NULL。该 {@code ifNull} 同时覆盖 {@code join_use_nulls} 的两种模式。
     */
    private static final String FIND = """
            WITH optimization_final AS (
                SELECT
                    *
                FROM (
                    SELECT *
                    FROM optimizations
                    WHERE workspace_id = :workspace_id
                    <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                    <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                    <if(id)>AND id = :id <endif>
                    <if(project_id)>AND project_id = :project_id <endif>
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, dataset_id, id
                )
                WHERE 1=1
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            ), experiments_final AS (
                SELECT
                    id,
                    optimization_id,
                    experiment_scores,
                    metadata AS experiment_metadata,
                    created_at AS experiment_created_at,
                    type AS experiment_type
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND optimization_id IN (SELECT id FROM optimization_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_items_final AS (
                SELECT
                    DISTINCT
                        experiment_id,
                        trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = :entity_type
                      AND workspace_id = :workspace_id
                      AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                    UNION ALL
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           author,
                           source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = :entity_type
                      AND workspace_id = :workspace_id
                      AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(fs_avg.name),
                        groupArray(fs_avg.avg_value)
                    ) AS feedback_scores
                FROM (
                    SELECT
                        et.experiment_id,
                        fs.name,
                        avg(fs.value) AS avg_value
                    FROM experiment_items_final as et
                    LEFT JOIN (
                        SELECT
                            name,
                            entity_id AS trace_id,
                            value
                        FROM feedback_scores_final
                    ) fs ON fs.trace_id = et.trace_id
                    GROUP BY et.experiment_id, fs.name
                    HAVING length(fs.name) > 0
                ) as fs_avg
                GROUP BY experiment_id
            ), experiment_scores_parsed AS (
                SELECT
                    e.id AS experiment_id,
                    JSON_VALUE(score, '$.name') AS name,
                    assumeNotNull(toFloat64OrNull(JSON_VALUE(score, '$.value'))) AS value
                FROM experiments_final AS e
                ARRAY JOIN JSONExtractArrayRaw(e.experiment_scores) AS score
                WHERE e.experiment_scores != '' AND e.experiment_scores != '[]'
                  AND length(JSON_VALUE(score, '$.name')) > 0
                  AND isFinite(toFloat64OrNull(JSON_VALUE(score, '$.value')))
            ), experiment_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) AS experiment_scores
                FROM experiment_scores_parsed
                GROUP BY experiment_id
            ), experiment_durations AS (
                SELECT
                    ei.experiment_id,
                    count(DISTINCT ei.trace_id) AS trace_count,
                    if(
                        isFinite(arrayElement(quantiles(0.5)(t.duration), 1)),
                        arrayElement(quantiles(0.5)(t.duration), 1),
                        NULL
                    ) AS duration_p50,
                    sum(s.total_estimated_cost) AS total_estimated_cost
                FROM experiment_items_final ei
                LEFT JOIN (
                    SELECT id, if(isNaN(duration), NULL, duration) AS duration
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    AND project_id IN (SELECT DISTINCT project_id FROM traces WHERE workspace_id = :workspace_id AND id IN (SELECT trace_id FROM experiment_items_final))
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, id
                ) AS t ON ei.trace_id = t.id
                LEFT JOIN (
                    SELECT trace_id, sum(total_estimated_cost) AS total_estimated_cost
                    FROM (
                        SELECT workspace_id, project_id, trace_id, id, total_estimated_cost, last_updated_at
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        AND trace_id IN (SELECT trace_id FROM experiment_items_final)
                        AND project_id IN (SELECT DISTINCT project_id FROM traces WHERE workspace_id = :workspace_id AND id IN (SELECT trace_id FROM experiment_items_final))
                        ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY workspace_id, project_id, id
                    )
                    GROUP BY trace_id
                ) AS s ON t.id = s.trace_id
                GROUP BY ei.experiment_id
            ), experiment_candidates AS (
                SELECT
                    ef.id AS experiment_id,
                    ef.optimization_id,
                    ef.experiment_created_at,
                    if(
                        JSONHas(ef.experiment_metadata, 'candidate_id') AND JSONExtractString(ef.experiment_metadata, 'candidate_id') != '',
                        JSONExtractString(ef.experiment_metadata, 'candidate_id'),
                        toString(ef.id)
                    ) AS candidate_id
                FROM experiments_final ef
                WHERE ef.experiment_type NOT IN ('mini-batch', 'mutation')
            ), objective_scores_per_experiment AS (
                SELECT
                    ef.optimization_id,
                    esp.experiment_id,
                    esp.value AS objective_score
                FROM experiment_scores_parsed esp
                INNER JOIN experiments_final ef ON esp.experiment_id = ef.id
                INNER JOIN optimization_final o ON ef.optimization_id = o.id
                WHERE esp.name = o.objective_name
            ), candidate_metrics AS (
                SELECT
                    ec.optimization_id AS optim_id,
                    ec.candidate_id,
                    sum(ospe.objective_score * ed.trace_count)
                        / nullIf(sumIf(ed.trace_count, isNotNull(ospe.objective_score)), 0)
                        AS weighted_score,
                    sum(ed.duration_p50 / 1000.0 * ed.trace_count)
                        / nullIf(sumIf(ed.trace_count, isNotNull(ed.duration_p50)), 0)
                        AS weighted_duration,
                    sum(ed.total_estimated_cost)
                        / nullIf(sum(ed.trace_count), 0)
                        AS per_trace_cost,
                    min(ec.experiment_created_at) AS earliest_created_at
                FROM experiment_candidates ec
                LEFT JOIN objective_scores_per_experiment ospe
                    ON ec.experiment_id = ospe.experiment_id
                    AND ec.optimization_id = ospe.optimization_id
                LEFT JOIN experiment_durations ed ON ec.experiment_id = ed.experiment_id
                GROUP BY ec.optimization_id, ec.candidate_id
            ), candidate_rollup AS (
                SELECT
                    optim_id AS optimization_id,
                    maxIf(weighted_score, isNotNull(weighted_score)) AS best_score,
                    argMinIf(weighted_duration, tuple(-weighted_score, earliest_created_at),
                        isNotNull(weighted_score)) AS best_duration,
                    argMinIf(per_trace_cost, tuple(-weighted_score, earliest_created_at),
                        isNotNull(weighted_score)) AS best_cost,
                    argMin(weighted_score, earliest_created_at) AS baseline_score,
                    argMin(weighted_duration, earliest_created_at) AS baseline_duration,
                    argMin(per_trace_cost, earliest_created_at) AS baseline_cost
                FROM candidate_metrics
                GROUP BY optim_id
            ), optimization_tagged_trace_ids AS (
                SELECT id, project_id
                FROM traces
                WHERE workspace_id = :workspace_id
                AND arrayExists(x -> x IN (SELECT toString(id) FROM optimization_final), tags)
            ), optimization_tagged_traces AS (
                SELECT DISTINCT
                    tag AS optimization_id_str,
                    trace_id
                FROM (
                    SELECT id AS trace_id, project_id, tags
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT id FROM optimization_tagged_trace_ids)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, id
                )
                ARRAY JOIN tags AS tag
                WHERE tag IN (SELECT toString(id) FROM optimization_final)
                AND (toString(trace_id), tag) NOT IN (
                    SELECT toString(ei.trace_id), ef.optimization_id
                    FROM experiment_items_final ei
                    INNER JOIN experiments_final ef ON ei.experiment_id = ef.id
                )
            ), optimization_tagged_costs AS (
                SELECT
                    ott.optimization_id_str AS optimization_id_str,
                    sum(s.total_estimated_cost) AS total_estimated_cost
                FROM optimization_tagged_traces ott
                INNER JOIN (
                    SELECT trace_id, sum(total_estimated_cost) AS total_estimated_cost
                    FROM (
                        SELECT workspace_id, project_id, trace_id, id, total_estimated_cost, last_updated_at
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        AND project_id IN (SELECT project_id FROM optimization_final WHERE notEmpty(project_id))
                        AND trace_id IN (SELECT trace_id FROM optimization_tagged_traces)
                        ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY workspace_id, project_id, id
                    )
                    GROUP BY trace_id
                ) AS s ON s.trace_id = ott.trace_id
                GROUP BY ott.optimization_id_str
            ), optimization_costs AS (
                SELECT
                    optimization_id,
                    sum(cost) AS total_optimization_cost
                FROM (
                    SELECT
                        ef2.optimization_id AS optimization_id,
                        ed2.total_estimated_cost AS cost
                    FROM experiments_final ef2
                    LEFT JOIN experiment_durations ed2 ON ef2.id = ed2.experiment_id
                    UNION ALL
                    SELECT
                        otc.optimization_id_str AS optimization_id,
                        otc.total_estimated_cost AS cost
                    FROM optimization_tagged_costs otc
                )
                GROUP BY optimization_id
            )
            SELECT
                o.*,
                o.id as id,
                COUNT(DISTINCT e.id) FILTER (WHERE e.id != '') AS num_trials,
                maxMap(fs.feedback_scores) AS feedback_scores,
                maxMap(es.experiment_scores) AS experiment_scores,
                any(bc.best_score) AS best_objective_score,
                any(bc.baseline_score) AS baseline_objective_score,
                any(bc.best_duration) AS best_duration,
                any(bc.best_cost) AS best_cost,
                any(bc.baseline_duration) AS baseline_duration,
                any(bc.baseline_cost) AS baseline_cost,
                any(oc.total_optimization_cost) AS total_optimization_cost
            FROM optimization_final AS o
            LEFT JOIN experiments_final AS e ON o.id = e.optimization_id
            LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            LEFT JOIN experiment_scores_agg AS es ON e.id = es.experiment_id
            LEFT JOIN candidate_rollup AS bc ON o.id = bc.optimization_id
            LEFT JOIN optimization_costs AS oc ON o.id = oc.optimization_id
            GROUP BY o.*
            ORDER BY o.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            ;
            """;

    /**
     * 范围内的任何优化是否有实验？有意只应用直接的列过滤器，
     * 而省略收窄性的那些（{@code name}、{@code dataset_deleted}、{@code studio_only}、{@code filters}），
     * 因此这里考虑的优化集合是 {@code optimization_final} 的超集。这使否定
     * 答案变得保守：如果这里什么都找不到，收窄后的集合也什么都找不到。
     */
    private static final String HAS_EXPERIMENTS_FOR_DIRECT_FILTERS = """
            SELECT 1 AS has_experiments
            FROM experiments
            WHERE workspace_id = :workspace_id
            AND optimization_id IN (
                SELECT id
                FROM optimizations
                WHERE workspace_id = :workspace_id
                <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                <if(project_id)>AND project_id = :project_id <endif>
            )
            LIMIT 1
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * 选择该投影的检查作为独立语句运行，因此在两次读取之间插入的实验
     * 会让聚合在这一单次响应中保持这些空输入值。本系统中的读取通过副本滞后
     * 已经是最终一致的，因此这落在既有行为之内，并在下一次请求时自我纠正，
     * 而无需共享的读取边界。
     * <p>
     * 当范围内没有优化有实验时所用的 {@link #FIND} 投影。{@link #FIND} 中除
     * {@code total_optimization_cost} 之外的每个聚合都派生自
     * {@code experiments_final}，因此在没有实验时它们都塌缩为各自的空输入值，十五个 CTE 的
     * 管道读不到任何有用的东西。下面的字面量重现这些值及其确切的声明类型。
     * <p>
     * {@code total_optimization_cost} 是例外，必须真正计算（OPIK-7521）：它还要对
     * 按标签归属的优化器内部 trace 求和，这些 trace 无需任何实验即存在。在候选生成
     * 期间死掉的运行有零个实验和非零支出，而在这里硬编码零会让
     * 运行列表与运行页面不一致——{@link #getById(UUID)} 总是走 {@link #FIND} 路径。下面的
     * 三个 CTE 是 {@link #FIND} 的带标签成本管道减去实验项排除，后者
     * 在这里没必要，因为该投影仅在不存在可关联 trace 的实验时才会被选中。
     * 让它们与 {@link #FIND} 保持同步——关于它们为何重复、漂移时哪个测试会失败，
     * 参见该字段的注释。
     * 当范围内没有优化携带 {@code project_id}（即该列存在之前写入的每一行）时，它们什么都读不到。
     */
    private static final String FIND_WITHOUT_EXPERIMENTS = """
            WITH optimization_final AS (
                SELECT
                    *
                FROM (
                    SELECT *
                    FROM optimizations
                    WHERE workspace_id = :workspace_id
                    <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                    <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                    <if(id)>AND id = :id <endif>
                    <if(project_id)>AND project_id = :project_id <endif>
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, dataset_id, id
                )
                WHERE 1=1
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            ), optimization_tagged_trace_ids AS (
                SELECT id, project_id
                FROM traces
                WHERE workspace_id = :workspace_id
                AND arrayExists(x -> x IN (SELECT toString(id) FROM optimization_final), tags)
            ), optimization_tagged_traces AS (
                SELECT DISTINCT
                    tag AS optimization_id_str,
                    trace_id
                FROM (
                    SELECT id AS trace_id, project_id, tags
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT id FROM optimization_tagged_trace_ids)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, project_id, id
                )
                ARRAY JOIN tags AS tag
                WHERE tag IN (SELECT toString(id) FROM optimization_final)
            ), optimization_costs AS (
                SELECT
                    ott.optimization_id_str AS optimization_id,
                    sum(s.total_estimated_cost) AS total_optimization_cost
                FROM optimization_tagged_traces ott
                INNER JOIN (
                    SELECT trace_id, sum(total_estimated_cost) AS total_estimated_cost
                    FROM (
                        SELECT workspace_id, project_id, trace_id, id, total_estimated_cost, last_updated_at
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        AND project_id IN (SELECT project_id FROM optimization_final WHERE notEmpty(project_id))
                        AND trace_id IN (SELECT trace_id FROM optimization_tagged_traces)
                        ORDER BY (workspace_id, project_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY workspace_id, project_id, id
                    )
                    GROUP BY trace_id
                ) AS s ON s.trace_id = ott.trace_id
                GROUP BY ott.optimization_id_str
            )
            SELECT
                o.*,
                o.id as id,
                toUInt64(0) AS num_trials,
                CAST(map(), 'Map(String, Float64)') AS feedback_scores,
                CAST(map(), 'Map(String, Float64)') AS experiment_scores,
                CAST(NULL, 'Nullable(Float64)') AS best_objective_score,
                CAST(NULL, 'Nullable(Float64)') AS baseline_objective_score,
                CAST(NULL, 'Nullable(Float64)') AS best_duration,
                CAST(NULL, 'Nullable(Decimal(38, 12))') AS best_cost,
                CAST(NULL, 'Nullable(Float64)') AS baseline_duration,
                CAST(NULL, 'Nullable(Decimal(38, 12))') AS baseline_cost,
                CAST(ifNull(oc.total_optimization_cost, toDecimal128(0, 12)), 'Decimal(38, 12)') AS total_optimization_cost
            FROM optimization_final AS o
            LEFT JOIN optimization_costs AS oc ON o.id = oc.optimization_id
            ORDER BY o.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String COUNT = """
            SELECT
                COUNT(id) as count
            FROM (
                SELECT
                    id
                FROM (
                    SELECT *
                    FROM optimizations
                    WHERE workspace_id = :workspace_id
                    <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                    <if(dataset_ids)>AND dataset_id IN :dataset_ids <endif>
                    <if(id)>AND id = :id <endif>
                    <if(project_id)>AND project_id = :project_id <endif>
                    ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY workspace_id, dataset_id, id
                )
                WHERE 1=1
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                <if(studio_only)>AND studio_config != ''<endif>
                <if(filters)>AND <filters><endif>
            )
            ;
            """;

    private static final String FIND_OPTIMIZATIONS_DATASET_IDS = """
            SELECT
                distinct dataset_id
            FROM optimizations
            WHERE workspace_id = :workspace_id
            <if(experiment_ids)> AND id IN :experiment_ids <endif>
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            ;
            """;

    private static final String DELETE_BY_IDS = """
            DELETE FROM optimizations
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String UPDATE_BY_ID = """
            INSERT INTO optimizations (
            	id, dataset_id, name, workspace_id, project_id, objective_name, status, metadata, created_at, created_by, last_updated_by, studio_config, error_info
            )
            SELECT
                id,
                dataset_id,
                <if(name)> :name <else> name <endif> as name,
                workspace_id,
                project_id,
                objective_name,
                <if(status)> :status <else> status <endif> as status,
                <if(metadata)> :metadata <else> metadata <endif> as metadata,
                created_at,
                created_by,
                :user_name as last_updated_by,
                studio_config,
                <if(clear_error_info)> '' <elseif(error_info)> :error_info <else> error_info <endif> as error_info
            FROM optimizations
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    private static final String SET_DATASET_DELETED_TO_TRUE_BY_DATASET_ID = """
            INSERT INTO optimizations (
            	id, dataset_id, name, workspace_id, project_id, objective_name, status, metadata, created_at, created_by, last_updated_at, last_updated_by, dataset_deleted, studio_config, error_info
            )
            SELECT
                id,
                dataset_id,
                name as name,
                workspace_id,
                project_id,
                objective_name,
                status as status,
                metadata,
                created_at,
                created_by,
                last_updated_at,
                last_updated_by,
                true as dataset_deleted,
                studio_config,
                error_info
            FROM optimizations
            WHERE workspace_id = :workspace_id
            AND dataset_id IN :dataset_ids
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 by id
            ;
            """;

    private static final String FIND_MOST_RECENT_CREATED_OPTIMIZATION_BY_DATASET_IDS = """
            SELECT
            	dataset_id,
            	max(created_at) as created_at
            FROM (
                SELECT
                    id,
                    dataset_id,
                    created_at
                FROM optimizations
                WHERE dataset_id IN :dataset_ids
            	AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY dataset_id
            ;
            """;

    private static final String FIND_OPTIMIZATION_SUMMARY_BY_DATASET_IDS = """
            SELECT
            	dataset_id,
            	count(distinct id) as optimization_count,
            	max(last_updated_at) as most_recent_optimization_at
            FROM (
                SELECT
                    id,
                    dataset_id,
                    last_updated_at
                FROM optimizations
                WHERE dataset_id IN :dataset_ids
            	AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY dataset_id
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;

    @Override
    public Mono<Void> upsert(@NonNull Optimization optimization) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> upsert(optimization, connection))
                .then();
    }

    @Override
    public Mono<Optimization> getById(@NonNull UUID id) {
        var template = TemplateUtils.newST(FIND);
        template.add("id", id.toString());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> get(
                        template.render(), connection,
                        statement -> statement.bind("id", id)))
                .flatMap(this::mapToDto)
                .singleOrEmpty();
    }

    @Override
    public Mono<List<DatasetEventInfoHolder>> getOptimizationDatasetIds(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = TemplateUtils.newST(FIND_OPTIMIZATIONS_DATASET_IDS);
                    template.add("experiment_ids", ids);
                    var statement = connection.createStatement(template.render());
                    statement.bind("experiment_ids", ids);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(this::mapDatasetId)
                .collectList();
    }

    @Override
    public Mono<Long> delete(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("正在按 ids 删除优化，数量 '{}'", ids.size());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> delete(ids, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("已按 ids 删除优化，数量 '{}'", ids.size());
                    }
                });
    }

    @Override
    public Flux<DatasetLastOptimizationCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetIds), "Argument 'datasetIds' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(FIND_MOST_RECENT_CREATED_OPTIMIZATION_BY_DATASET_IDS);
                    statement.bind("dataset_ids", datasetIds);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new DatasetLastOptimizationCreated(
                        row.get("dataset_id", UUID.class),
                        row.get("created_at", Instant.class))));
    }

    @Override
    public Mono<Long> update(@NonNull UUID id, @NonNull OptimizationUpdate update, boolean clearErrorInfo) {
        log.info("按 id '{}' 更新优化", id);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> update(id, update, clearErrorInfo, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("已按 id '{}' 更新优化", id);
                    }
                });
    }

    @Override
    public Mono<Long> updateDatasetDeleted(@NonNull Set<UUID> datasetIds) {
        log.info("将 datasetIds '{}' 的优化 dataset_deleted 设为 true", datasetIds);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> updateDatasetDeleted(datasetIds, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("将 datasetIds '{}' 的优化 dataset_deleted 设为 true 已完成", datasetIds);
                    }
                });
    }

    @Override
    public Mono<Optimization.OptimizationPage> find(int page, int size,
            @NonNull OptimizationSearchCriteria searchCriteria) {
        return getCount(searchCriteria)
                .filter(totalCount -> totalCount > 0)
                .flatMap(totalCount -> hasExperimentsForDirectFilters(searchCriteria)
                        .flatMap(hasExperiments -> find(page, size, totalCount, searchCriteria, hasExperiments)))
                .defaultIfEmpty(Optimization.OptimizationPage.empty(page, List.of()));
    }

    private Mono<Boolean> hasExperimentsForDirectFilters(OptimizationSearchCriteria searchCriteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = FilterUtils.getSTWithLogComment(HAS_EXPERIMENTS_FOR_DIRECT_FILTERS,
                            "has_optimization_experiments", workspaceId, userName, "");

                    bindScopeTemplateParams(template, searchCriteria);

                    Statement statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId);

                    bindScopeQueryParams(searchCriteria, statement);

                    return Flux.from(statement.execute());
                }))
                .flatMap(result -> result.map(row -> row.get("has_experiments", Integer.class)))
                .hasElements();
    }

    @Override
    public Flux<OptimizationSummary> findOptimizationSummaryByDatasetIds(@NonNull Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Flux.empty();
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(FIND_OPTIMIZATION_SUMMARY_BY_DATASET_IDS);

                    statement.bind("dataset_ids", datasetIds);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new OptimizationSummary(
                        row.get("dataset_id", UUID.class),
                        row.get("optimization_count", Long.class),
                        row.get("most_recent_optimization_at", Instant.class))));
    }

    private Mono<Long> getCount(OptimizationSearchCriteria searchCriteria) {
        var template = TemplateUtils.newST(COUNT);

        bindTemplateParams(template, searchCriteria);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(template.render());

                    bindQueryParams(searchCriteria, statement, false);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map(row -> row.get("count", Long.class)))
                .reduce(Long::sum);
    }

    private Mono<Optimization.OptimizationPage> find(int page, int size, long total,
            OptimizationSearchCriteria searchCriteria, boolean hasExperiments) {
        var offset = (page - 1) * size;

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = hasExperiments
                            ? TemplateUtils.newST(FIND)
                            : FilterUtils.getSTWithLogComment(FIND_WITHOUT_EXPERIMENTS,
                                    "find_optimizations_without_experiments", workspaceId, userName, "");

                    bindTemplateParams(template, searchCriteria);

                    template.add("limit", size);
                    template.add("offset", offset);

                    Statement statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("limit", size)
                            .bind("offset", offset);

                    // entity_type 仅由 FIND 声明；快速路径省略了使用它的反馈评分 CTE，
                    // 而绑定一个渲染后查询不包含的参数会使语句失败。
                    bindQueryParams(searchCriteria, statement, hasExperiments);

                    return Flux.from(statement.execute());
                }))
                .flatMap(this::mapToDto)
                .collectList()
                .map(optimizations -> new Optimization.OptimizationPage(page, optimizations.size(), total,
                        optimizations, List.of()));
    }

    /**
     * 按身份而非属性选择哪些优化在范围内的条件子集。
     * 与 {@link #HAS_EXPERIMENTS_FOR_DIRECT_FILTERS} 共享，后者只声明这些占位符——绑定一个
     * 渲染后查询不包含的参数会使语句失败，因此两者必须保持同步。
     */
    private void bindScopeTemplateParams(ST template, OptimizationSearchCriteria searchCriteria) {

        Optional.ofNullable(searchCriteria.datasetId())
                .ifPresent(datasetId -> template.add("dataset_id", datasetId));

        Optional.ofNullable(searchCriteria.datasetIds())
                .filter(ids -> !ids.isEmpty())
                .ifPresent(datasetIds -> template.add("dataset_ids", datasetIds));

        Optional.ofNullable(searchCriteria.projectId())
                .ifPresent(projectId -> template.add("project_id", projectId));
    }

    private void bindTemplateParams(ST template, OptimizationSearchCriteria searchCriteria) {

        bindScopeTemplateParams(template, searchCriteria);

        Optional.ofNullable(searchCriteria.datasetDeleted())
                .ifPresent(datasetDeleted -> template.add("dataset_deleted", datasetDeleted.toString()));

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> template.add("name", name));

        Optional.ofNullable(searchCriteria.studioOnly())
                .filter(Boolean.TRUE::equals)
                .ifPresent(studioOnly -> template.add("studio_only", "true"));

        Optional.ofNullable(searchCriteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.OPTIMIZATION))
                .ifPresent(optimizationFilters -> template.add("filters", optimizationFilters));

        Optional.ofNullable(searchCriteria.entityType())
                .ifPresent(entityType -> template.add("entity_type", EntityType.TRACE.getType()));
    }

    private void bindScopeQueryParams(OptimizationSearchCriteria searchCriteria, Statement statement) {

        Optional.ofNullable(searchCriteria.datasetId())
                .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));

        Optional.ofNullable(searchCriteria.datasetIds())
                .filter(ids -> !ids.isEmpty())
                .ifPresent(datasetIds -> statement.bind("dataset_ids", datasetIds));

        Optional.ofNullable(searchCriteria.projectId())
                .ifPresent(projectId -> statement.bind("project_id", projectId.toString()));
    }

    private void bindQueryParams(OptimizationSearchCriteria searchCriteria, Statement statement, boolean isFindQuery) {

        bindScopeQueryParams(searchCriteria, statement);

        Optional.ofNullable(searchCriteria.datasetDeleted())
                .ifPresent(datasetDeleted -> statement.bind("dataset_deleted", datasetDeleted));

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> statement.bind("name", name));

        Optional.ofNullable(searchCriteria.filters())
                .ifPresent(filters -> filterQueryBuilder.bind(statement, filters, FilterStrategy.OPTIMIZATION));

        if (isFindQuery) {
            Optional.ofNullable(searchCriteria.entityType())
                    .ifPresent(entityType -> statement.bind("entity_type", EntityType.TRACE.getType()));
        }
    }

    private Publisher<? extends Result> upsert(Optimization optimization, Connection connection) {

        var statement = connection.createStatement(UPSERT)
                .bind("id", optimization.id())
                .bind("dataset_id", optimization.datasetId())
                .bind("name", optimization.name())
                .bind("project_id", optimization.projectId() != null ? optimization.projectId().toString() : "")
                .bind("objective_name", optimization.objectiveName())
                .bind("status", optimization.status().getValue())
                .bind("metadata", getStringOrDefault(optimization.metadata()))
                .bind("error_info",
                        optimization.errorInfo() != null ? JsonUtils.writeValueAsString(optimization.errorInfo()) : "");

        if (optimization.studioConfig() != null) {
            try {
                String studioConfigJson = JsonUtils.writeValueAsString(optimization.studioConfig());
                statement.bind("studio_config", studioConfigJson);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to serialize studio_config for optimization: '%s'".formatted(optimization.id()), e);
            }
        } else {
            statement.bindNull("studio_config", String.class);
        }

        // 两个时间戳都绑定为规范的 ClickHouse 字面量，原先由 now64() 提供的列 DEFAULT
        // 在这里替换——参见 UPSERT 的 javadoc（OPIK-5694）。这两列
        // 精度不同，必须相应格式化：migration 000026 把
        // last_updated_at 收窄为 DateTime64(6)，而 created_at 保持为 (9)。把 9 位字面量写入
        // (6) 列会重新触发那篇 javadoc 存在的目的就是要避免的 FORMAT Values 解析路径；SpanDAO 的
        // last_updated_at 绑定是微秒形式的先例。
        statement.bind("last_updated_at",
                ClickHouseDateTimeFormat.formatMicros(
                        optimization.lastUpdatedAt() != null ? optimization.lastUpdatedAt() : Instant.now()));

        // created_at 过去不在 INSERT 中，因此列 DEFAULT 会在每次
        // 重新 upsert 时重新盖章：运行的创建时间不断前漂，卡住运行 reaper 的硬上限
        // （以它为基准测量）就可能被非状态变更的写入无限期推迟。
        // 服务在重新 upsert 时带入现有行的值（OPIK-7459）。
        statement.bind("created_at",
                ClickHouseDateTimeFormat.formatNanos(
                        optimization.createdAt() != null ? optimization.createdAt() : Instant.now()));

        return makeFluxContextAware((userName, workspaceId) -> {
            log.info("正在插入优化，id '{}'，datasetId '{}'，datasetName '{}'，workspaceId '{}'",
                    optimization.id(), optimization.datasetId(), optimization.datasetName(), workspaceId);
            statement.bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        });
    }

    private Publisher<? extends Result> get(String query, Connection connection, Function<Statement, Statement> bind) {
        var statement = connection.createStatement(query)
                .bind("entity_type", EntityType.TRACE.getType());
        return makeFluxContextAware(bindWorkspaceIdToFlux(bind.apply(statement)));
    }

    private Publisher<Optimization> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> mapRowColumns(row).toBuilder()
                .feedbackScores(getFeedbackScores(row, "feedback_scores"))
                .experimentScores(getFeedbackScores(row, "experiment_scores"))
                .numTrials(row.get("num_trials", Long.class))
                .baselineObjectiveScore(getFiniteBigDecimal(row, "baseline_objective_score"))
                .bestObjectiveScore(getFiniteBigDecimal(row, "best_objective_score"))
                .baselineDuration(getFiniteBigDecimal(row, "baseline_duration"))
                .bestDuration(getFiniteBigDecimal(row, "best_duration"))
                .baselineCost(row.get("baseline_cost", BigDecimal.class))
                .bestCost(row.get("best_cost", BigDecimal.class))
                .totalOptimizationCost(row.get("total_optimization_cost", BigDecimal.class))
                .build());
    }

    /**
     * 将 {@code Nullable(Float64)} 聚合读取为 {@code BigDecimal}，把任何非有限值映射为
     * {@code null}，而不是让它进入驱动的 {@code BigDecimal} 转换。
     *
     * <p>这是 {@link #FIND} 在 SQL 中所做同一防御的映射器侧一半，它放在这里
     * 是因为失败实际发生在映射器处，而且失败之严重与原因完全不成比例：
     * {@code BigDecimal.valueOf(NaN)} 抛出 {@code NumberFormatException}，
     * clickhouse-r2dbc 将其重新抛出为误导性的 {@code NoSuchElementException}，而
     * {@code ClickHouseResult.map} 会捕获每个映射器异常、记录它，并<em>静默丢弃该
     * 行</em>——因此一个非有限单元格会让整个运行 404，并将其从分页列表中抹去
     * （OPIK-7459）。{@code FIND} 防护了非有限值可能 <em>进入</em> 的两处
     * （{@code duration_p50}、JSON 解析的评分），但这里读取的列是由
     * {@code candidate_metrics} 中的除法和求和从那些值 <em>派生</em> 出来的，因此将来在那里加入的
     * 任何可能溢出到 +/-Inf 的运算都会以同样不可见的方式重开同一类 bug。在
     * 边界处防护，使行丢失模式无论查询在上游做什么都不可达。
     *
     * <p>有限路径有意通过 {@code BigDecimal.class} 重新读取，而不是转换
     * {@code Double} 本身，因此值的标度和表示与该防护存在之前驱动
     * 产生的逐字节一致。两次读取都命中一个已解码的内存单元格。成本是
     * {@code Decimal} 且不可能非有限，因此它们保留直接读取。
     */
    private static BigDecimal getFiniteBigDecimal(Row row, String column) {
        Double value = row.get(column, Double.class);
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return row.get(column, BigDecimal.class);
    }

    /** 映射 {@code optimizations} 表的普通列——除 FIND 的计算聚合之外的一切。 */
    private Optimization mapRowColumns(Row row) {
        OptimizationStudioConfig studioConfig = null;
        String studioConfigJson = row.get("studio_config", String.class);
        if (StringUtils.isNotEmpty(studioConfigJson)) {
            try {
                studioConfig = JsonUtils.readValue(studioConfigJson, OptimizationStudioConfig.class);
            } catch (UncheckedIOException e) {
                log.error("反序列化优化的 studio_config 失败：'{}'",
                        row.get("id", UUID.class), e);
            }
        }

        ErrorInfo errorInfo = null;
        String errorInfoJson = row.get("error_info", String.class);
        if (StringUtils.isNotBlank(errorInfoJson)) {
            try {
                errorInfo = JsonUtils.readValue(errorInfoJson, ERROR_INFO_TYPE);
            } catch (UncheckedIOException e) {
                log.error("反序列化优化的 error_info 失败：'{}'",
                        row.get("id", UUID.class), e);
            }
        }

        String projectIdStr = row.get("project_id", String.class);
        UUID projectId = StringUtils.isNotBlank(projectIdStr) ? UUID.fromString(projectIdStr) : null;

        return Optimization.builder()
                .id(row.get("id", UUID.class))
                .name(row.get("name", String.class))
                .datasetId(row.get("dataset_id", UUID.class))
                .projectId(projectId)
                .objectiveName(row.get("objective_name", String.class))
                .status(OptimizationStatus.fromString(row.get("status", String.class)))
                .metadata(getJsonNodeOrDefault(row.get("metadata", String.class)))
                .studioConfig(studioConfig)
                .errorInfo(errorInfo)
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build();
    }

    private Publisher<DatasetEventInfoHolder> mapDatasetId(Result result) {
        return result.map((row, rowMetadata) -> new DatasetEventInfoHolder(row.get("dataset_id", UUID.class), null));
    }

    private Flux<? extends Result> delete(Set<UUID> ids, Connection connection) {

        var statement = connection.createStatement(DELETE_BY_IDS)
                .bind("ids", ids.toArray(UUID[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Flux<? extends Result> update(UUID id, OptimizationUpdate update, boolean clearErrorInfo,
            Connection connection) {
        var template = buildUpdateTemplate(update, clearErrorInfo);

        var statement = createUpdateStatement(id, update, clearErrorInfo, connection, template.render());

        return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement));
    }

    private Flux<? extends Result> updateDatasetDeleted(Set<UUID> datasetIds, Connection connection) {
        Statement statement = connection.createStatement(SET_DATASET_DELETED_TO_TRUE_BY_DATASET_ID);
        statement.bind("dataset_ids", datasetIds);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private ST buildUpdateTemplate(OptimizationUpdate update, boolean clearErrorInfo) {
        var template = TemplateUtils.newST(UPDATE_BY_ID);

        Optional.ofNullable(update.name())
                .ifPresent(name -> template.add("name", name));

        Optional.ofNullable(update.status())
                .ifPresent(status -> template.add("status", status.getValue()));

        if (clearErrorInfo) {
            template.add("clear_error_info", true);
        } else {
            Optional.ofNullable(update.errorInfo())
                    .ifPresent(errorInfo -> template.add("error_info", errorInfo));
        }

        // 缺席时，SELECT 将现有 metadata 列原样向前携带。存在时，
        // update.metadata() 已经是完整的合并对象（参见 OptimizationService.update）——新的
        // ReplacingMergeTree 版本必须携带完整 metadata，绝不能是增量。
        Optional.ofNullable(update.metadata())
                .ifPresent(metadata -> template.add("metadata", true));

        return template;
    }

    private Statement createUpdateStatement(UUID id, OptimizationUpdate update, boolean clearErrorInfo,
            Connection connection, String sql) {
        Statement statement = connection.createStatement(sql);

        Optional.ofNullable(update.name())
                .ifPresent(name -> statement.bind("name", name));

        Optional.ofNullable(update.status())
                .ifPresent(status -> statement.bind("status", status.getValue()));

        if (!clearErrorInfo) {
            Optional.ofNullable(update.errorInfo())
                    .ifPresent(errorInfo -> statement.bind("error_info", JsonUtils.writeValueAsString(errorInfo)));
        }

        Optional.ofNullable(update.metadata())
                .ifPresent(metadata -> statement.bind("metadata", getStringOrDefault(metadata)));

        statement.bind("id", id);

        return statement;
    }

    @Override
    public Flux<StalledOptimization> findStalledStudioOptimizations(@NonNull Duration initializedTimeout,
            @NonNull Duration runningTimeout, @NonNull Duration runningHardTimeout, @NonNull Duration lookbackMargin,
            int limit, int candidateScanFactor) {
        // 查询扫描回退多远（让 minmax 跳数索引裁剪颗粒的 last_updated_at FLOOR）：
        // 最大超时加上配置的 reaper 停机余量，因此在正常运行中
        // 该下限纯粹是扫描边界、绝不是覆盖缺口——仅当 reaper 停机时间长于该余量时，
        // 某运行的最后一次状态变更才会早于此值，此时该运行不会被回收
        // （已记录的取舍，review: thiagohora）。
        long lookbackSeconds = Math.max(Math.max(initializedTimeout.toSeconds(), runningTimeout.toSeconds()),
                runningHardTimeout.toSeconds()) + lookbackMargin.toSeconds();
        // 两个存活度探测所扇出的 CTE 的上限。没有它，`candidates` 就是“每一行
        // 在 runningTimeout 内未变化的非终态 studio 运行”——而因为
        // last_updated_at 仅在状态变化时推进，这包括了每一个早于超时的健康在途
        // 运行，因此探测的成本会随集群规模而非配置缩放。有意做成批大小的倍数
        // 而非批大小本身：排序把最陈旧的放在最前，而健康的长期运行会与死掉的运行排在一起
        // （这正是整个功能的前提），因此恰好为 `limit` 的上限可能让存活运行
        // 在每次遍历中把死掉的挤出去。有了乘数，饿死一个死运行需要其前面有同样多
        // 同时存活的陈旧运行，而存活运行最终会转为终态并完全退出
        // CTE。该乘数可由运营调优
        // （OPTIMIZATION_STALLED_REAPER_CANDIDATE_SCAN_FACTOR），因此部署可以在不发布的情况下
        // 权衡探测成本与查询的覆盖范围（review: thiagohora）。
        int candidateLimit = limit * candidateScanFactor;
        var details = "initializedTimeoutSeconds=%d, runningTimeoutSeconds=%d, runningHardTimeoutSeconds=%d, lookbackSeconds=%d, limit=%d, candidateLimit=%d"
                .formatted(initializedTimeout.toSeconds(), runningTimeout.toSeconds(),
                        runningHardTimeout.toSeconds(), lookbackSeconds, limit, candidateLimit);
        var template = FilterUtils.getSTWithLogComment(FIND_STALLED_STUDIO_OPTIMIZATIONS,
                "find_stalled_studio_optimizations", "", "", details);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("initialized_timeout_seconds", initializedTimeout.toSeconds())
                            .bind("running_timeout_seconds", runningTimeout.toSeconds())
                            .bind("running_hard_timeout_seconds", runningHardTimeout.toSeconds())
                            .bind("lookback_seconds", lookbackSeconds)
                            .bind("candidate_limit", candidateLimit)
                            .bind("limit", limit);
                    return Flux.from(statement.execute());
                })
                .flatMap(result -> result.map((row, metadata) -> StalledOptimization.builder()
                        .id(row.get("id", UUID.class))
                        .workspaceId(row.get("workspace_id", String.class))
                        .status(OptimizationStatus.fromString(row.get("status", String.class)))
                        .build()));
    }

    @Override
    public Mono<OptimizationStatusSnapshot> getStatusSnapshotById(@NonNull UUID id) {
        var template = FilterUtils.getSTWithLogComment(GET_STATUS_SNAPSHOT,
                "get_optimization_status_snapshot", "", "", "id=%s".formatted(id));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("id", id);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, metadata) -> OptimizationStatusSnapshot.builder()
                        .status(OptimizationStatus.fromString(row.get("latest_status", String.class)))
                        .lastUpdatedAt(row.get("latest_updated_at", Instant.class))
                        .startedAt(row.get("started_at", Instant.class))
                        .build()))
                .singleOrEmpty();
    }

    @Override
    public Mono<Optimization> getRowById(@NonNull UUID id) {
        var template = FilterUtils.getSTWithLogComment(GET_RAW_BY_ID,
                "get_optimization_row_by_id", "", "", "id=%s".formatted(id));
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("id", id);
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, metadata) -> mapRowColumns(row)))
                .singleOrEmpty();
    }

    @Override
    public Mono<Boolean> hasRecentStudioActivity(@NonNull UUID optimizationId, @NonNull Duration window) {
        var details = "optimizationId=%s, windowSeconds=%d".formatted(optimizationId, window.toSeconds());
        var template = FilterUtils.getSTWithLogComment(HAS_RECENT_STUDIO_ACTIVITY,
                "has_recent_studio_activity", "", "", details);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("optimization_id", optimizationId)
                            .bind("window_seconds", window.toSeconds());
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> Flux.from(result.map((row, metadata) -> true)))
                .hasElements();
    }
}
