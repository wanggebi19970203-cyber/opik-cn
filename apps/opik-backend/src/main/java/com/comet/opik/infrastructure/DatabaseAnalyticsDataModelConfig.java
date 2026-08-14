package com.comet.opik.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Builder;

/**
 * 分析数据库模式状态的开关（trace 列非空迁移）。
 *
 * <p>{@code traceColumnsNonNullable}：当 {@code traces} 表仍有 {@code Nullable(...)} 列时（默认 {@code false}），
 * trace 写入会为缺失的 {@code end_time}/{@code ttft} 绑定 {@code null}。一旦这些列被替换为带哨兵默认值的非空列，
 * 就把此开关设为 {@code true}，使写入改为绑定哨兵值（{@code end_time}→纪元，{@code ttft}→{@code NaN}）——
 * 因为 {@code null} 绑定会被非空列拒绝。该标志同时控制读取（哨兵→{@code null}），因此在它为 {@code false} 时，
 * 合法的纪元结束时间会原样往返，而不是被读作 {@code null}。请与切换的 EXCHANGE 步骤同步翻转此开关。</p>
 *
 * <p>{@code spanColumnsNonNullable}：{@code traceColumnsNonNullable} 的 {@code spans} 对应项，为
 * {@code spans.end_time}→纪元 和 {@code spans.duration}/{@code spans.ttft}→{@code NaN} 控制相同的哨兵接线。当
 * {@code spans} 表仍有 {@code Nullable(...)} 列时默认 {@code false}；一旦这些列被替换为带哨兵默认值的非空列，
 * 请与 Slice 3 的 EXCHANGE 同步设为 {@code true}。它独立于 trace 标志，因此两次切换可以分别翻转。</p>
 *
 * <p>{@code traceDeletionEventsCaptureEnabled}：为 {@code true} 时，trace 删除还会把被删除的 id 记录到
 * {@code deletion_events_local} 桥接表中，使它们能在表复制后存活。部署时保持 {@code false}，在 trace 回填开始后
 * 再打开，使捕获恰好覆盖“回填到切换”这一窗口。</p>
 *
 * <p>{@code spanDeletionEventsCaptureEnabled}：{@code traceDeletionEventsCaptureEnabled} 的 {@code spans} 对应项。
 * Span 没有独立的删除，因此它把 trace 删除级联（{@code SpanService.deleteByTraceIds}）移除的 span id 以
 * {@code source_table = spans} 捕获到桥接表中，使它们能在 {@code spans} 表复制后存活。部署时保持 {@code false}，
 * 在 span 回填开始后独立于 trace 标志再打开。</p>
 *
 * <p>{@code deletionEventsInsertBatchSize}：每次向桥接表 {@code INSERT} 的行数（trace 和 span 捕获共用）。
 * 单个删除批次携带的 id 数量可能远超 ClickHouse 驱动在单条语句中可靠绑定的数量（每行 5 列），因此插入被拆分为
 * 该大小的块。它被限定为正值，以便配置错误时在启动阶段就失败而不是悄然禁用捕获，并限定一个合理的上限，
 * 使每条语句的绑定数量保持在安全范围内。</p>
 *
 * <p>{@code tracesDistributedWrapEnabled}：trace 切换的最后一步分片就绪操作，把 {@code traces} 包装为基于
 * {@code traces_local} 分片的 {@code Distributed} 表。{@code Distributed} 表支持 {@code SELECT} 和 {@code INSERT}，
 * 但<b>不</b>支持变更（{@code DELETE FROM <distributed>} → 错误码 36；{@code ALTER ... DELETE} → 错误码 48），
 * 因此一旦包装生效，每条变更路径都必须指向本地分片。部署时保持 {@code false}（并且当 {@code traces} 仍是
 * {@code MergeTree} 时，删除可以直接执行）；在应用 {@code Distributed} 包装
 * （{@code exchange_and_wrap.sh --with-wrap} / {@code --wrap-only}）时同步设为 {@code true}。当它为 {@code true} 时，
 * {@code TraceDAO} 会将其删除/保留变更路由到 {@code traces_local}，而读取和插入继续走 Distributed {@code traces}。
 * <b>按变更类型的一般规则：</b>行变更（{@code DELETE}）以及 {@code MATERIALIZE COLUMN} / {@code ADD INDEX} /
 * {@code MODIFY TTL} 只针对 {@code traces_local}——Distributed {@code traces} 会拒绝它们（错误码 36/48），因此一旦
 * 出错会响亮地失败；{@code ADD}/{@code DROP}/{@code MODIFY COLUMN} 必须同时应用到 <b>两者</b> {@code traces_local}
 * 和 Distributed {@code traces}（包装器将其作为纯元数据接受，只针对 {@code traces_local} 会让包装器缺少该列，
 * 读取会以错误码 47 失败）。</p>
 */
@Builder(toBuilder = true)
public record DatabaseAnalyticsDataModelConfig(
        boolean traceColumnsNonNullable,
        boolean spanColumnsNonNullable,
        boolean traceDeletionEventsCaptureEnabled,
        boolean spanDeletionEventsCaptureEnabled,
        @Min(1) @Max(2_000) int deletionEventsInsertBatchSize,
        boolean tracesDistributedWrapEnabled) {
}
