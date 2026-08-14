package com.comet.opik.domain.experiments.aggregations;

import lombok.Builder;

import java.util.Set;
import java.util.UUID;

/**
 * 用于收窄聚合/非聚合实验计数的条件，该计数决定实验查询中哪些分支会被渲染。
 * <p>
 * {@code projectId} 仅收窄非聚合（原始回退）计数。没有它时计数是工作空间级别的，因此工作空间中任意位置的
 * 单个非聚合实验都会让该工作空间中每个请求都保留原始分支，即使所请求的项目中没有任何实验需要它。
 * <p>
 * 实验归属于某个项目，要么通过其自身的 {@code project_id}，要么通过其条目所引用的 trace 所属的项目——
 * 原始分支对二者的拼接进行匹配。大多数实验不携带 {@code project_id}，因此由 trace 派生的一侧不能丢弃；
 * 而相当一部分实验携带一个其 trace 都不指向的 {@code project_id}，因此 {@code project_id} 一侧也不能
 * 丢弃。因此两者对于计数的正确性都是必需的：少算会丢弃原始分支，并从响应中静默地遗漏匹配的实验。
 */
@Builder(toBuilder = true)
public record AggregationBranchCountsCriteria(
        Set<UUID> experimentIds,
        UUID datasetId,
        UUID id,
        Set<UUID> idsList,
        UUID projectId) {

    public static AggregationBranchCountsCriteria empty() {
        return AggregationBranchCountsCriteria.builder().build();
    }
}
