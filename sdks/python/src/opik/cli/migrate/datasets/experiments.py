"""用于 ``opik migrate dataset``（Slice 3）的实验 + trace/span 级联。

读取引用了正在迁移数据集的源实验，并在目标项目下重新创建它们，
重新发送它们的 trace 和 span，使用 Slice 2 填充的映射重写外键字段。

**级联复制语义。** 与 Slice 1 的数据集复制一样，这是复制而非移动。
源实验连同其所有 trace 和 span 原封不动地保留在原始项目中；
目标项目获得全新的实验（新 id），它们引用目标数据集/版本/项目的
id，并携带独立的 trace+span 数据副本。希望清理源端的用户会在
验证目标端之后使用 ``--delete-source``（不在本 Slice 范围内）。

**跨项目跟随。** ``find_experiments(dataset_id=...)`` 在 REST 层是
项目无关的，因此每个引用源数据集的实验都会级联到 ``--to-project``，
无论它最初位于哪个项目。这是 epic 的“基线跟随”默认行为，永远不会
产生悬空引用，但当源数据集被多个项目中的实验引用时，会产生重复项。
Slice 4 (OPIK-6417) 在此行为之上增加了检测与报告。

重建期间的外键重映射：

  source dataset_id         -> dest_dataset_id                  (Slice 1)
  source dataset_version_id -> plan.version_remap[old]          (Slice 2)
  source dataset_item_id    -> plan.item_id_remap[old]          (Slice 2)
  source trace_id           -> built here as traces copy        (本 Slice)
  source project_id         -> target_project_name              (本 Slice)
  source optimization_id    -> plan.optimization_id_remap[old]  (Slice 5)

在目标实验上被剥离的字段（符合 epic 讨论中 Jacques 的“剥离链接”策略；
否则这些指针会悬空）：

  prompt_versions  -- prompt 实体在 v1 中不级联（epic 的开放问题）

span 与其父 trace 一起级联；通过导入路径中的
``sort_spans_topologically`` 保持树的顺序，因此在处理子 span 时，
``parent_span_id`` 重映射条目始终存在。

单实验失败会停止级联（审计日志的 ``failed`` 条目通过
``ExperimentCascadeResult`` 捕获部分进度）；单项目缺失 trace /
缺失项目的条件按跳过计数累加，而不是视为失败，与 Slice 1/2 的
``skipped_items`` 语义一致。
"""

from __future__ import annotations

import logging
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Literal, Optional, Set, Tuple, cast

import opik
import opik.id_helpers as id_helpers_module
from opik.api_objects import rest_helpers, rest_stream_parser
from opik.api_objects.experiment import experiment_item, rest_operations
from opik.rest_api import OpikApi
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types.experiment_item_public import ExperimentItemPublic
from opik.rest_api.types.experiment_public import ExperimentPublic
from opik.rest_api.types.span_public import SpanPublic
from opik.rest_api.types.trace_public import TracePublic
from opik.types import (
    BatchAssertionResultDict,
    BatchFeedbackScoreDict,
    ErrorInfoDict,
)

from ...imports.experiment import ExperimentData, recreate_experiment
from ...imports.utils import sort_spans_topologically
from ..audit import AuditLog
from ..checkpoint import MigrationCheckpoint
from ..errors import ExperimentCascadeError

LOGGER = logging.getLogger(__name__)

# 外层 + 内层进度回调形状在所有数据集级联阶段（version_replay、
# optimizations、experiments）共享 —— 单一事实来源见
# ``datasets/_progress.py``。从此模块重新导出，使现有的
# ``from .experiments import ProgressCallback`` 调用点继续可用。
from ._progress import InnerProgressCallback, ProgressCallback  # noqa: E402, F401

# 关于实验级联使用这些回调的说明：
#
# - 外层 ``ProgressCallback`` 在每个实验开始前触发一次；
#   ``label="done"`` 表示 ``completed == total`` 的最终 tick。
#
# - 内层 ``InnerProgressCallback`` 在单个实验内 tick，因此外层实验级
#   进度条不会变成每个实验只前进一次的冻结读数。``total`` 是 THIS
#   实验的内层步数（每个实验重新计算，因为实验之间的 trace 数量可能
#   差异巨大）。``label`` 描述刚完成的步骤（例如
#   ``"trace 47/150"``、``"spans for trace 47/150"``、``"flush"``、
#   ``"recreate"``）。执行器将其渲染在嵌套的 Rich 进度条上；测试使用
#   它来断言级联在每个读/写阶段都进行 tick。


class _InnerProgress:
    """跨单个实验工作阶段驱动 ``InnerProgressCallback`` 的小型适配器。

    级联会为 THIS 实验预先计算总步数（对于 ``N`` 个 trace 通常为
    ``2N + 固定开销`` —— 每次读取 trace tick 一次，每次获取 span tick
    一次，外加读取项目 / flush / 记录分数 / 记录断言 / 重建的少量步骤）。
    每次调用 ``tick(label)`` 都会递增计数器，并用最新的标签触发回调，
    使 UI 即使在算法工作尚未推进时也能平滑更新（例如执行器的 Rich
    进度条会在每次回调时重绘）。

    当回调为 None 时为空操作，因此从测试中传入
    ``inner_progress_callback=None`` 可保持级联机制不变。
    """

    def __init__(self, callback: Optional[InnerProgressCallback], total: int) -> None:
        self._callback = callback
        self._total = max(total, 1)
        self._completed = 0

    def tick(self, label: str) -> None:
        if self._callback is None:
            return
        self._completed = min(self._completed + 1, self._total)
        self._callback(self._completed, self._total, label)

    def finish(self, label: str = "done") -> None:
        """完成时将进度条强制置为 100% —— 防止总数计算错误
        （例如某些 trace 已在先前实验的重映射中，因而被跳过，
        导致我们 tick 的次数少于估计值）。"""
        if self._callback is None:
            return
        self._completed = self._total
        self._callback(self._completed, self._total, label)


_EXPERIMENT_PAGE_SIZE = 100

# 级联批量读取 trace/span 时每个请求的页大小。SDK 默认的 2000
# （配合级联为往返保真而设置的 ``truncate=False``）使每次请求的读取
# 变成洪流：在一次大型客户迁移（OPIK-7152）中，后端容器因 RSS（约 3 GiB）
# 被 OOM 杀死，RSS 由跟踪 SELECT 吞吐的原生/堆外读取缓冲区驱动 ——
# 而非 JVM 堆（堆峰值 931 MiB，上限为 3.2 GiB）。CH SELECT 运行速率为
# 293 万行/秒，而 INSERT 仅为 511 行/秒，因此读路径是元凶；写路径已经
# 批处理，无需在此设上限。每页 500 条记录在不让请求数膨胀的前提下
# 约束了读取洪流（按观察到的平均 span 约 25 KB 计，约 12.5 MB/页；
# 单个 span 最大约 4.5 MB）（一个 2.7k span 的实验约 5-6 页）。如果某页
# 仍因连接/超时错误而断开套接字，SDK 的自适应收缩会进一步将其减半 ——
# 这是针对恰好聚积了多个多 MB span 的页面的反应式保护。
MIGRATION_SEARCH_PAGE_SIZE = 500

# 限制每条记录的 ``sample_source_ids`` 列表，使病态的“所有项目都缺失”
# 情况不会撑爆审计 JSON。计数始终完整记录；样本只是为了给操作员
# 足够的面包屑来调查少数出问题的源 id。
_SKIP_SAMPLE_LIMIT = 20

# 通过 ``search_spans(from_time, to_time)`` 批量获取 span 时，围绕实验
# trace 开始/结束时间的缓冲。迟到的 span（流式处理器是异步的；绑定到
# trace 的 span 可能在 trace 自身的 ``end_time`` 之后才落地）以及各 SDK
# 客户端之间的时钟偏差是这个缓冲的动机。5 分钟覆盖了常见情况，同时
# 不会因同一项目中的并发活动而膨胀为过度获取。span 落地时间超过 trace
# 窗口 5 分钟以上的 trace 作为已知边界情况被接受（在批量读取结束时
# 记录为零桶警告）。
_SPAN_BULK_WINDOW_BUFFER = timedelta(minutes=5)


@dataclass
class ExperimentCascadeResult:
    """一次完整实验级联的结果。

    聚合计数写入审计日志的 ``cascade_experiments`` 条目；
    ``trace_id_remap`` 也会暂存到 plan 上，使 Slice 4（优化级联）
    在重映射优化级 trace 引用时能够复用该映射。
    """

    trace_id_remap: Dict[str, str] = field(default_factory=dict)
    experiments_migrated: int = 0
    experiments_skipped: int = 0
    traces_migrated: int = 0
    spans_migrated: int = 0
    trace_comments_migrated: int = 0
    span_comments_migrated: int = 0
    items_skipped_missing_trace: int = 0
    items_skipped_missing_item: int = 0
    # 携带了 ``optimization_id`` 但在 ``optimization_id_remap`` 中没有条目
    # 的源实验 —— 防御性计数器；当 ``CascadeOptimizations`` 按 planner
    # 保证的顺序先运行时，应始终为零。非零值表示存在规划缺陷
    # （例如动作顺序被破坏），而不是用户可见的失败。
    experiments_with_orphan_optimization_id: int = 0
    # 用于审计日志的按实验跳过原因。每条记录为
    # ``{"id": ..., "name": ..., "reason": ...}``；只保留最近的失败，
    # 以保持审计 JSON 有界。
    skipped_experiments: List[Dict[str, Any]] = field(default_factory=list)


def cascade_experiments(
    client: opik.Opik,
    rest_client: OpikApi,
    *,
    source_dataset_id: str,
    target_dataset_name: str,
    target_project_name: str,
    target_dataset_id: Optional[str] = None,
    version_remap: Dict[str, str],
    item_id_remap: Dict[str, str],
    optimization_id_remap: Optional[Dict[str, str]] = None,
    audit: AuditLog,
    checkpoint: Optional[MigrationCheckpoint] = None,
    progress_callback: Optional[ProgressCallback] = None,
    inner_progress_callback: Optional[InnerProgressCallback] = None,
) -> ExperimentCascadeResult:
    """枚举引用 ``source_dataset_id`` 的源实验，并在目标端重新创建
    每一个，同时携带 trace+span。

    源端读取（``get_spans_by_project``）按 ``project_id`` 逐个实验限定范围
    —— 每次迭代从 ``source_experiment.project_id`` 派生 —— 因为实验始终
    是项目级范围的（与数据集不同，数据集可以是工作区级范围的），而引用
    同一源数据集的跨项目实验合理地存在于不同的项目中。

    ``progress_callback(completed, total, label)`` 在每个实验之前触发一次，
    使调用方能够驱动进度条；其形状与 ``version_replay.replay_all_versions``
    使用的一致。``completed`` 和 ``total`` 在整个实验集上是绝对的
    （包括先前运行已完成的实验），因此恢复运行的进度条从正确的百分比
    开始，而不是从 0 开始。

    当提供 ``checkpoint``（OPIK-7168）时，级联会恢复被中断的迁移：
    源 id 已在检查点完成集合中的实验会被跳过，而先前运行遗留为
    ``in_flight`` 的实验，其部分目标数据（trace/span + 可能孤立的
    实验行）会在重新迁移之前被删除。检查点在每个实验完成后刷新，
    并在完全成功后由调用方负责删除。``target_dataset_id`` 仅用于该
    恢复清理（用于在目标数据集下按名称查找孤立的目标实验）。

    Returns
    -------
    ExperimentCascadeResult
        聚合的级联结果，在处理每个源实验时原地更新。字段：

        - ``trace_id_remap`` -- 源 trace id -> 新生成的目标 trace id。
          由执行器暂存到 ``plan.trace_id_remap`` 上，使 Slice 4（优化级联）
          在重映射优化级 trace 引用时能够复用该映射。
        - ``experiments_migrated`` / ``experiments_skipped`` -- 按实验的
          计数器。仅当 ``recreate_experiment`` 返回 ``False`` 时实验才被
          “跳过”（如所有项目都缺失 trace 映射的退化情况）；致命错误会
          抛出 ``ExperimentCascadeError``。
        - ``traces_migrated`` / ``spans_migrated`` -- 按实体的计数器，
          聚合所有已处理源实验。
        - ``trace_comments_migrated`` / ``span_comments_migrated`` --
          通过专用单评论写入端点重新发送的评论计数器（评论在
          trace/span 写载荷上是只读的，因此它们作为写后的后续 POST
          跟随级联，而不是随批量写入一起发送）。
        - ``items_skipped_missing_trace`` / ``items_skipped_missing_item``
          -- 按实验项目的跳过计数器，在重建调用之后通过将每个源项目的
          ``trace_id`` / ``dataset_item_id`` 与重映射对比来累加。
        - ``skipped_experiments`` -- 用于审计日志的有界
          ``{"id", "name", "reason"}`` 条目列表。
    """
    result = ExperimentCascadeResult()

    # 默认使用空重映射，使尚未采用新 kwarg 的调用方（较旧的测试、
    # 临时调用）行为与之前一致：任何源 ``optimization_id`` 都会落入
    # 孤立路径，该字段从目标载荷中省略。
    if optimization_id_remap is None:
        optimization_id_remap = {}

    source_experiments = list(_list_source_experiments(rest_client, source_dataset_id))
    total = len(source_experiments)

    if total == 0:
        LOGGER.info(
            "没有实验引用数据集 %s；级联为空操作。",
            source_dataset_id,
        )
        return result

    if checkpoint is not None:
        checkpoint.total_experiments = total
        # 在开始之前清理先前运行中断的实验，使其部分目标数据不会
        # 以重复形式留存。后端在重新迁移时会生成全新 id 而非覆盖，
        # 因此这一客户端侧清理是保持恢复无损的唯一手段。
        _cleanup_in_flight_experiment(client, rest_client, checkpoint)

    # ``already_done`` 锚定进度条的绝对完成计数，使恢复运行从正确的
    # 百分比开始。它统计 THIS 源集合中检查点已标记为完成的实验
    # （而非原始检查点大小，后者在源于两次运行之间发生变化时可能
    # 包含已不存在的 id）。
    already_done = (
        sum(1 for e in source_experiments if checkpoint.is_completed(e.id or ""))
        if checkpoint is not None
        else 0
    )
    processed = already_done

    for index, experiment in enumerate(source_experiments):
        if experiment.id is None:
            # 防御性：后端应始终返回 id；如果没有，则视为级联致命，
            # 因为没有它就无法枚举项目。
            raise ExperimentCascadeError(
                f"后端在位置 {index} 返回了没有 id 的实验：{experiment!r}"
            )

        if checkpoint is not None and checkpoint.is_completed(experiment.id):
            # 已在先前的运行中迁移 —— 跳过，不再重复任何工作。
            continue

        label = experiment.name or experiment.id or f"<experiment[{index}]>"
        if progress_callback is not None:
            progress_callback(processed, total, label)

        if checkpoint is not None:
            checkpoint.mark_in_flight(
                experiment.id,
                experiment_name=experiment.name,
                dest_dataset_id=target_dataset_id,
            )
            checkpoint.flush()

        cascade_one_experiment(
            client,
            rest_client,
            source_experiment=experiment,
            target_dataset_name=target_dataset_name,
            target_project_name=target_project_name,
            version_remap=version_remap,
            item_id_remap=item_id_remap,
            optimization_id_remap=optimization_id_remap,
            result=result,
            audit=audit,
            checkpoint=checkpoint,
            inner_progress_callback=inner_progress_callback,
        )

        if checkpoint is not None:
            checkpoint.mark_completed(experiment.id)
            checkpoint.flush()
        processed += 1

    if progress_callback is not None:
        progress_callback(total, total, "done")

    return result


def _cleanup_in_flight_experiment(
    client: opik.Opik,
    rest_client: OpikApi,
    checkpoint: MigrationCheckpoint,
) -> None:
    """删除先前运行中断的实验所遗留的部分目标数据，使重新迁移它时
    不会产生重复项。

    后端在重新迁移时不会级联删除 —— ``recreate_experiment`` 每次运行都会
    生成全新的实验/trace/span id，而不是按名称覆盖 —— 因此 SDK 在客户端
    侧移除部分副本：

    1. 删除记录的目标 trace（``traces.delete_traces``）；后端会级联删除
       它们的 span，因此无需单独删除 span。
    2. 按其记录的 id 删除目标实验行。级联在创建行之前生成并检查点记录
       ``dest_experiment_id``，因此清理针对的是那个确切的实验 —— 而绝不会
       是同名的同级实验（目标数据集中名称不唯一）。当中断发生在实验行
       创建之前时，``dest_experiment_id`` 为 ``None``，此时没有需要删除的
       内容。

    当没有进行中的实验时为空操作。清理完成后清除进行中记录并刷新，
    使清理*过程中*发生的崩溃不会对已被删除的 id 重复执行删除。
    """
    in_flight = checkpoint.in_flight
    if in_flight is None:
        return

    LOGGER.info(
        "正在恢复迁移：在重新迁移之前清理中断实验 %s (%s) 的部分数据。",
        in_flight.source_experiment_id,
        in_flight.experiment_name,
    )

    if in_flight.dest_trace_ids:
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.traces.delete_traces(ids=in_flight.dest_trace_ids),
            operation_name="delete_traces (resume cleanup)",
        )

    if in_flight.dest_experiment_id is not None:
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.experiments.delete_experiments_by_id(
                ids=[in_flight.dest_experiment_id]
            ),
            operation_name="delete_experiments_by_id (resume cleanup)",
        )

    checkpoint.in_flight = None
    checkpoint.flush()


def cascade_one_experiment(
    client: opik.Opik,
    rest_client: OpikApi,
    *,
    source_experiment: ExperimentPublic,
    target_dataset_name: str,
    target_project_name: str,
    version_remap: Dict[str, str],
    item_id_remap: Dict[str, str],
    optimization_id_remap: Optional[Dict[str, str]] = None,
    result: ExperimentCascadeResult,
    audit: Optional[AuditLog] = None,
    checkpoint: Optional[MigrationCheckpoint] = None,
    inner_progress_callback: Optional[InnerProgressCallback] = None,
) -> None:
    """迁移一个源实验：读取项目 -> 复制 trace + span ->
    通过 ``imports.experiment.recreate_experiment`` 重建实验。

    当提供 ``checkpoint`` 时，为此实验生成的目标 trace id 会记录在
    检查点的进行中记录上，并在 trace/span 复制之后立即刷新到磁盘。
    如果进程随后在 ``recreate_experiment`` 完成之前被杀死，下一次运行
    的恢复清理可以删除这些 trace（并通过后端级联删除其 span），
    使重新迁移不会重复它们。

    源项目派生自 ``source_experiment.project_id`` —— 实验在后端始终是
    项目级范围的，因此 ``find_experiments(dataset_id=...)`` 返回的每个
    实验都携带非空的 ``project_id``（即使数据集本身是工作区级范围的）。
    使用按实验的 ``project_id`` —— 而不是贯通传递单个数据集级项目 ——
    意味着跨项目实验（即位于源数据集项目之外的实验）能从正确的范围
    读取其 trace / span。

    原地更新 ``result``。
    """
    experiment_id = source_experiment.id
    experiment_name = source_experiment.name
    assert experiment_id is not None  # 已在调用方收窄

    source_dataset_id = source_experiment.dataset_id
    if not source_dataset_id:
        raise ExperimentCascadeError(
            f"源实验 {experiment_id} 没有 dataset_id；"
            "find_dataset_items_with_experiment_items 需要"
            "数据集 id 来枚举实验的项目。"
        )

    source_project_id = source_experiment.project_id
    if not source_project_id:
        # 防御性：后端绝不应返回无项目的实验（实验始终是项目级范围的）。
        # 如果它确实返回了，清晰地失败，而不是让 ``get_spans_by_project``
        # 以晦涩的消息 400。
        raise ExperimentCascadeError(
            f"源实验 {experiment_id} 没有 project_id；"
            "级联需要 project_id 来限定 span 读取的范围。"
        )

    # 源端读取走 Compare 视图（而非 ``stream_experiment_items`` Public 视图），
    # 因为我们需要每个项目的 ``assertion_results``，而只有 Compare 视图
    # 会暴露它。trace 级断言结果随后在 _copy_traces_and_spans 中通过
    # ``store_assertions_batch`` 在目标端重新发送。
    items = _read_source_experiment_items(
        rest_client,
        source_dataset_id=source_dataset_id,
        source_experiment_id=experiment_id,
    )
    if not items:
        # 没有项目的实验是退化的，但仍可重建；我们仍重建它，
        # 使用户能在目标端看到该行。
        LOGGER.info(
            "实验 %s (%s) 没有项目；正在重建为空实验。",
            experiment_id,
            experiment_name,
        )

    # 收集我们计划迁移的项目的不同源 trace id，以及按 trace id 为键的
    # assertion_results（一个 trace 可以跨项目携带多个断言结果，尽管
    # 典型的 1:1 形状是一个项目 -> 一个 trace）。
    source_trace_ids: Set[str] = {item.trace_id for item in items if item.trace_id}
    assertion_results_by_source_trace: Dict[str, List[Any]] = {}
    for item in items:
        if item.trace_id and item.assertion_results:
            assertion_results_by_source_trace.setdefault(item.trace_id, []).extend(
                item.assertion_results
            )

    # 内层进度总数 = 1（读取项目，刚完成）
    # + 1（通过 search_traces(filter=experiment_id) 批量读取 trace）
    # + N（每个 trace 的发送 tick；写入由流式处理器批处理，因此每个
    #     tick 都在内存中，但给用户提供动态感）
    # + 1（刷新 trace）+ 1（记录 trace 反馈）+ 1（记录断言）
    # + 1（通过 search_spans(from_time, to_time) 批量读取 span）
    # + N（来自内存桶的每个 trace 的 span 发送 tick）
    # + 1（刷新 span + 记录 span 反馈）
    # + 1（重建）
    # = 2N + 8。我们使用的 trace 计数是 SET 大小（去重后）—— 而非
    # ``len(items)`` —— 以避免对共享同一 trace 的项目重复计数。
    # ``_InnerProgress`` 会在 ``total`` 处截断超出部分，因此过时的估计值
    # （例如幂等跳过移除了 trace）不会把进度条推到超过 100%。
    inner_total = 1 + 1 + 2 * len(source_trace_ids) + 6
    inner = _InnerProgress(inner_progress_callback, inner_total)
    inner.tick(label="read items")

    # ``_copy_traces_and_spans`` 会在将这些 trace 刷新到后端*之前*把
    # 目标 trace id 记录到检查点并刷新到磁盘 —— 因此从 trace 刷新到
    # span 复制阶段的任何崩溃都会为下一次运行的恢复清理留下已记录的 id。
    # 在此处记录（调用返回之后）会使该窗口处于未覆盖状态。
    (
        traces_copied,
        spans_copied,
        trace_comments_copied,
        span_comments_copied,
    ) = _copy_traces_and_spans(
        client,
        rest_client,
        source_experiment_id=experiment_id,
        source_experiment_name=experiment_name,
        source_trace_ids=source_trace_ids,
        source_project_id=source_project_id,
        target_project_name=target_project_name,
        trace_id_remap=result.trace_id_remap,
        assertion_results_by_source_trace=assertion_results_by_source_trace,
        inner_progress=inner,
        checkpoint=checkpoint,
    )
    result.traces_migrated += traces_copied
    result.spans_migrated += spans_copied
    result.trace_comments_migrated += trace_comments_copied
    result.span_comments_migrated += span_comments_copied

    # 构建 recreate_experiment 消费的 ExperimentData 载荷。
    # 只有外键字段会落到目标 ExperimentItem 上 —— 其余按项目的保真内容
    # （input/output/feedback_scores/assertion_results/等）在后端是只读的，
    # 并从底层的 trace + span + assertion 实体重建（级联在
    # _copy_traces_and_spans 中复制了这些实体）。
    experiment_data = _build_experiment_data(
        source_experiment,
        items,
        optimization_id_remap=optimization_id_remap or {},
        result=result,
    )

    target_version_id = version_remap.get(source_experiment.dataset_version_id or "")

    # 在此处生成目标实验 id（而非让 ``create_experiment`` 生成），以便在
    # 行创建*之前*将其记录到检查点。恢复随后在清理时删除那个确切的
    # 行，而不是按名称匹配 —— 实验名称在目标数据集中不唯一，因此名称
    # 匹配可能删除无关的同级实验。
    dest_experiment_id = id_helpers_module.generate_id()
    if checkpoint is not None:
        checkpoint.record_dest_experiment_id(dest_experiment_id)
        checkpoint.flush()

    recreated = recreate_experiment(
        client=client,
        experiment_data=experiment_data,
        project_name=target_project_name,
        trace_id_map=result.trace_id_remap,
        dataset_item_id_map=item_id_remap,
        target_project_name=target_project_name,
        target_dataset_name=target_dataset_name,
        target_dataset_version_id=target_version_id,
        experiment_id=dest_experiment_id,
    )
    # 将内层进度条快进到 100%，使执行器的嵌套 Rich 进度条干净地结束，
    # 即使我们预先计算的 ``inner_total`` 略微偏高或偏低（例如幂等跳过
    # 移除了此实验的 trace，导致 tick 次数减少）。
    inner.finish(label="recreated" if recreated else "skipped")

    if recreated:
        result.experiments_migrated += 1
    else:
        result.experiments_skipped += 1
        result.skipped_experiments.append(
            {
                "id": experiment_id,
                "name": source_experiment.name,
                "reason": "recreate_experiment returned False",
            }
        )
        _record_skip(
            audit,
            reason="experiment_recreate_returned_false",
            experiment_id=experiment_id,
            experiment_name=source_experiment.name,
            count=1,
            sample_source_ids=[experiment_id],
        )

    # 累加重建调用之后可见的按项目跳过计数。``recreate_experiment``
    # 会打印自己的跳过计数但不返回它们；我们通过将源项目与重映射条目
    # 对比来推断两个映射缺失总数，使级联级审计计数器保持准确。
    #
    # 按 (experiment, reason) 的审计记录在最后发出，附带有问题的源 id
    # （上限为 ``_SKIP_SAMPLE_LIMIT``），使 CLI 能以机器可读的明细响亮地
    # 失败 —— 参见 OPIK-6599。在收集期间就截断按原因的样本列表 ——
    # ``_record_skip`` 反正也会切片，但尽早修剪能在病态情况下
    # （例如 1 万个项目都缺失同一重映射）约束峰值内存。``count`` 来自
    # 始终完整递增的计数器，因此即使样本被截断，审计记录也携带真实总数。
    missing_trace_count = 0
    missing_item_count = 0
    missing_trace_sample: List[str] = []
    missing_item_sample: List[str] = []
    for item in items:
        if item.trace_id and item.trace_id not in result.trace_id_remap:
            result.items_skipped_missing_trace += 1
            missing_trace_count += 1
            if len(missing_trace_sample) < _SKIP_SAMPLE_LIMIT:
                missing_trace_sample.append(item.trace_id)
        if item.dataset_item_id and item.dataset_item_id not in item_id_remap:
            result.items_skipped_missing_item += 1
            missing_item_count += 1
            if len(missing_item_sample) < _SKIP_SAMPLE_LIMIT:
                missing_item_sample.append(item.dataset_item_id)

    if missing_trace_count:
        _record_skip(
            audit,
            reason="items_missing_trace_remap",
            experiment_id=experiment_id,
            experiment_name=source_experiment.name,
            count=missing_trace_count,
            sample_source_ids=missing_trace_sample,
        )
    if missing_item_count:
        _record_skip(
            audit,
            reason="items_missing_dataset_item_remap",
            experiment_id=experiment_id,
            experiment_name=source_experiment.name,
            count=missing_item_count,
            sample_source_ids=missing_item_sample,
        )


def _record_skip(
    audit: Optional[AuditLog],
    *,
    reason: str,
    experiment_id: str,
    experiment_name: Optional[str],
    count: int,
    sample_source_ids: List[str],
) -> None:
    """向审计日志追加一条按 (experiment, reason) 的 ``skip`` 记录。

    样本 id 上限为 ``_SKIP_SAMPLE_LIMIT``，使病态的跳过（例如 1 万个
    项目丢失了其 dataset_item_id 重映射）不会撑爆审计 JSON。``count``
    始终是完整总数，使机器可读的消费者可以跨记录求和。

    当 ``audit`` 为 ``None`` 时为空操作 —— 保持未传入审计日志的测试和
    临时调用像之前一样工作。
    """
    if audit is None:
        return
    audit.record(
        type="skip",
        status="skipped",
        details={
            "reason": reason,
            "experiment_id": experiment_id,
            "experiment_name": experiment_name,
            "count": count,
            "sample_source_ids": sample_source_ids[:_SKIP_SAMPLE_LIMIT],
        },
    )


def _list_source_experiments(
    rest_client: OpikApi, source_dataset_id: str
) -> List[ExperimentPublic]:
    """翻页遍历 ``find_experiments(dataset_id=...)`` 直到耗尽。

    停留在 ``rest_client`` 上而非 ``client.get_dataset_experiments``：
    高层包装只接受 ``dataset_name``，这需要每次调用都做名称查找。
    级联改为以 plan 中稳定的源 ``dataset_id`` 为键 —— 使其与源名称解耦，
    源名称在 OPIK-7162 交接中会在运行的最后被重命名为 ``<name>_v1``。
    ``rest_client`` 调用直接接受 ``dataset_id``。
    """
    collected: List[ExperimentPublic] = []
    page = 1
    while True:
        response = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.experiments.find_experiments(
                dataset_id=source_dataset_id,
                page=page,
                size=_EXPERIMENT_PAGE_SIZE,
            )
        )
        page_content = response.content or []
        collected.extend(page_content)
        if len(page_content) < _EXPERIMENT_PAGE_SIZE:
            break
        page += 1
    return collected


def _read_source_experiment_items(
    rest_client: OpikApi,
    *,
    source_dataset_id: str,
    source_experiment_id: str,
) -> List[experiment_item.ExperimentItemContent]:
    """通过 Compare 视图读取一个源实验的所有项目。

    经由高层辅助函数
    ``api_objects.experiment.rest_operations.find_experiment_items_for_dataset``，
    它内部会对 ``datasets.find_dataset_items_with_experiment_items`` 进行分页
    （PAGE_SIZE=100），展开每页按数据集项目的 ``experiment_items`` 列表，
    并返回 ``ExperimentItemContent`` 数据类，其 ``assertion_results``
    已规范化为 ``List[AssertionResultDict]``。

    这里的正确读取形状是 Compare 视图（而非 Public 的
    ``stream_experiment_items``），因为只有 Compare 会暴露
    ``assertion_results``，级联需要它在目标端以新的 trace id 为作用域
    通过 ``client.log_assertion_results`` 重新发送。

    ``max_results`` 是辅助函数在调用方一侧的“到 N 条结果即停”旋钮，
    专为不想获取长尾的分页/边输入边搜索 UI 设计。迁移按契约是无损的
    —— 静默截断任何实验的项目都会破坏目标端 —— 因此我们传入
    ``sys.maxsize``，让辅助函数底层的分页遍历源实验的每一页。
    同样地，``truncate=False`` 使按项目的 Compare 载荷保持完整保真
    （级联目前只消费 ``id`` / ``trace_id`` / ``dataset_item_id`` /
    ``assertion_results``，因此后端侧的截断标志不会影响正确性，但
    转发 ``False`` 与先前的调用形状一致，并为级联将来消费可截断字段
    预留了空间）。

    底层端点以 JSON 数组字符串（而非逗号分隔值或列表）接受
    ``experiment_ids``，对另外两种形式会返回 400；辅助函数在内部
    处理 JSON 编码。
    """
    return rest_operations.find_experiment_items_for_dataset(
        rest_client=rest_client,
        dataset_id=source_dataset_id,
        experiment_ids=[source_experiment_id],
        max_results=sys.maxsize,
        truncate=False,
    )


def _discover_trace_projects(
    rest_client: OpikApi,
    *,
    source_experiment_name: str,
    fallback_project_id: str,
) -> Dict[str, Set[str]]:
    """将每个源 trace_id 映射到其 trace 实际所在的项目。

    跨项目实验在后端是合法的：``experiment_items`` 行可以引用与实验
    自身项目不同的项目中的 trace。后端在写入时从 ``traces.project_id``
    填充 ``experiment_items.project_id``（参见
    ``ExperimentItemService.populateProjectIdFromTraces``），而
    ``streamExperimentItems`` 会在每行上暴露该字段（已在 staging 上验证
    —— Compare 视图端点通过其 ``@JsonView`` 注解省略了 ``project_id``，
    但 stream-experiment-items 端点没有视图限制，会包含它）。

    我们流式读取实验的项目，按实际 project_id 对源 trace_ids 分组，
    并返回 ``{project_id: {trace_ids}}``。级联随后为每个不同的项目发出
    一次 ``search_traces`` 和一次 ``search_spans`` —— 实际上通常为 1
    （``opik.evaluate(...)`` 会共置），但合法的跨项目设置在不为每个
    trace 回退到 ``get_trace_content`` 的情况下保持无损。

    ``project_id`` 为 ``None`` 的项目（防御性；后端的从 trace 填充步骤
    不应留下空值，但 schema 允许）会路由到 ``fallback_project_id`` ——
    实验自身的项目。这与提交 ``7e0f9a8bb`` 中按 trace 回退的旧行为一致：
    ``trace.project_id or source_project_id``。
    """
    trace_ids_by_project: Dict[str, Set[str]] = {}

    def _fetch_page(batch_size: int, last_retrieved_id: Optional[str]) -> Any:
        return rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            operation_name="stream_experiment_items (project discovery)",
            rest_callable=lambda: list(
                rest_client.experiments.stream_experiment_items(
                    experiment_name=source_experiment_name,
                    limit=batch_size,
                    last_retrieved_id=last_retrieved_id,
                    truncate=True,
                )
            ),
        )

    items = rest_stream_parser.read_and_parse_full_stream(
        read_source=_fetch_page,
        max_results=sys.maxsize,
        parsed_item_class=ExperimentItemPublic,
    )

    for item in items:
        trace_id = item.trace_id
        if not trace_id:
            continue
        project_id = item.project_id or fallback_project_id
        trace_ids_by_project.setdefault(project_id, set()).add(trace_id)

    return trace_ids_by_project


def _copy_traces_and_spans(
    client: opik.Opik,
    rest_client: OpikApi,
    *,
    source_experiment_id: str,
    source_experiment_name: str,
    source_trace_ids: Set[str],
    source_project_id: str,
    target_project_name: str,
    trace_id_remap: Dict[str, str],
    assertion_results_by_source_trace: Optional[Dict[str, List[Any]]] = None,
    inner_progress: Optional["_InnerProgress"] = None,
    checkpoint: Optional[MigrationCheckpoint] = None,
) -> tuple[int, int, int, int]:
    """通过高层 Opik 客户端的流式处理器基础设施，在
    ``target_project_name`` 下重新发送 trace + span。

    写入路径为 ``client.__internal_api__trace__``（trace）、
    ``client._streamer.put(CreateSpanMessage(...))``（span）、
    ``client.log_traces_feedback_scores`` / ``client.log_spans_feedback_scores``
    （反馈分数）以及 ``client.log_assertion_results``（断言）。
    流式处理器跨消息批量处理，并在内部处理重试/背压；写路径上无需手动
    包裹 ``rest_helpers.ensure_rest_api_call_respecting_rate_limit``。

    span 使用直接的 ``_streamer.put(CreateSpanMessage(...))`` 而非
    ``client.span(...)``，因为公共的 ``client.span(usage=...)`` 路径会
    调用 ``helpers.add_usage_to_metadata``，把 ``usage`` 合并到
    ``metadata["usage"]`` 中。这是面向用户的新 span 写侧便利功能，但与本
    级联的往返元数据保真契约冲突：源 span 的 ``usage`` 在自己的字段中，
    且 ``metadata`` 与之分离，我们需要两者都原封不动地往返。流式处理器的
    ``CreateSpanMessage`` 将它们作为独立字段接受，并逐字序列化。

    trace 读取现在每个实验走一次 ``client.search_traces(filter=
    "experiment_id=...", truncate=False)`` 调用 —— 后端将
    ``TraceField.EXPERIMENT_ID`` 作为一等过滤器暴露，因此一次分页读取即可
    返回链接到此实验的所有 trace。这是针对按 trace 的“30 次后暂停”速率
    限制模式的按实验修复。span 读取仍按 trace（``SpanField`` 上没有
    ``experiment_id`` 过滤器）；批量读取的收益仅体现在 trace 上。

    为每个复制的 trace 在 ``trace_id_remap`` 中原位填充一条记录。
    返回 ``(traces_copied, spans_copied, trace_comments_copied,
    span_comments_copied)`` 用于计数聚合。

    评论从与批量 trace/span 读取相同的 ``TracePublic.comments`` /
    ``SpanPublic.comments`` 载荷中读取（无需额外获取），并在目标
    trace/span 落地后通过专用的单评论写入端点重新发送。通过原地迭代
    源列表来保持顺序：后端按 ``createdAt`` 排序返回评论，且 POST 是
    串行的，因此目标端的读取顺序匹配。

    ``source_project_id`` 仅是一个防御性回退，用于单个源 trace 的
    ``project_id`` 字段为 null 的情况。按 trace 的 span 读取使用 trace
    自身的 ``project_id`` —— span 与其父 trace 位于同一项目，这可能与
    实验的项目不同（后端不对实验的 trace 强制单项目不变性）。
    """
    if not source_trace_ids:
        return 0, 0, 0, 0

    # 跳过已复制的 trace（幂等重试，以及极少发生的跨实验共享 trace）。
    new_source_ids = [tid for tid in source_trace_ids if tid not in trace_id_remap]
    if not new_source_ids:
        return 0, 0, 0, 0

    source_to_new_trace: Dict[str, str] = {}
    project_id_to_name_cache: Dict[str, str] = {}

    # 阶段 1a：发现此实验的 trace 实际位于哪些项目。后端允许
    # ``experiment_items`` 行引用与实验自身项目不同的项目中的 trace
    # （合法但罕见；``opik.evaluate(...)`` 始终共置）。我们通过
    # ``streamExperimentItems`` 流式读取实验的项目 —— 每行携带在写入时
    # 从 trace 实际项目填充的 ``project_id`` —— 并按项目分组，从而为每个
    # 不同的项目发出一次 ``search_traces``。单项目实验（常见情况）仍只
    # 产生一次读取；跨项目实验保持无损。
    traces_by_project: Dict[str, Set[str]] = _discover_trace_projects(
        rest_client,
        source_experiment_name=source_experiment_name,
        fallback_project_id=source_project_id,
    )

    # 阶段 1b：通过 ``search_traces(filter="experiment_id=...")`` 批量获取
    # 源 trace —— 每个不同的项目一次 HTTP 读取。后端将
    # ``TraceField.EXPERIMENT_ID`` 作为一等过滤器（通过
    # ``experiment_items`` 连接），但外层 SQL 会钳制
    # ``project_id = :project_id`` —— 因此过滤器会与项目范围相与。
    # 按项目循环覆盖每个 trace，包括跨项目的。
    #
    # ``truncate=False`` 是往返保真所必需的：SDK 包装默认 ``True``，
    # 它会把 input/output/metadata 中的内联 base64 图像数据替换为占位符
    # ``"[image]"``。我们需要保留原始字节。
    #
    # ``max_results=sys.maxsize`` 让包装的内部分页
    # （通过 ``last_retrieved_id`` 游标，PAGE_SIZE=2000）遍历每一页；
    # 该上限是包装在调用方一侧的“到 N 即停”UI 旋钮，而非安全限制
    # —— 迁移必须无损。
    source_traces_by_id: Dict[str, TracePublic] = {}
    for project_id in traces_by_project:
        project_name = _resolve_project_name(
            client, project_id=project_id, cache=project_id_to_name_cache
        )
        bulk_traces = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda pn=project_name: client.search_traces(
                project_name=pn,
                filter_string=f'experiment_id = "{source_experiment_id}"',
                max_results=sys.maxsize,
                truncate=False,
                max_batch_size=MIGRATION_SEARCH_PAGE_SIZE,
            ),
            operation_name="search_traces (experiment cascade)",
        )
        for t in bulk_traces:
            if t.id is not None:
                source_traces_by_id[t.id] = t
    if inner_progress is not None:
        inner_progress.tick(
            label=f"fetched {len(source_traces_by_id)} traces in bulk "
            f"({len(traces_by_project)} project{'s' if len(traces_by_project) != 1 else ''})"
        )

    # 防御性回退：如果 Compare 视图项目中的任何 trace_ids 在批量搜索响应中
    # 缺失（罕见 —— 若 experiment_items 表一致则不应发生），则回退到按
    # trace 的 ``get_trace_content``，使正确性优先于吞吐量。即使连接过滤器
    # 存在边界情况的遗漏，这也能保持级联无损。
    missing_ids = [tid for tid in new_source_ids if tid not in source_traces_by_id]
    if missing_ids:
        LOGGER.warning(
            "search_traces(experiment_id=%s) 返回了 %d 个 trace，但 Compare "
            "视图项目期望 %d 个；正在对 %d 个缺失 id 回退到 get_trace_content。",
            source_experiment_id,
            len(source_traces_by_id),
            len(new_source_ids),
            len(missing_ids),
        )
        for tid in missing_ids:
            try:
                fetched = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
                    lambda sid=tid: client.get_trace_content(id=sid),
                    operation_name="get_trace_content (fallback)",
                )
            except ApiError as err:
                if err.status_code == 404:
                    # 实验项目引用的 trace 已被删除。它已经不存在了 ——
                    # 没有可复制的数据 —— 因此跳过它并继续，而不是中止
                    # 整个迁移（OPIK-7344）。将它排除在
                    # ``source_traces_by_id`` 之外意味着下面的发送循环
                    # 也会跳过它。
                    LOGGER.warning(
                        "实验 %s 引用的 trace %s 已不存在（被删除）；"
                        "正在跳过它。目标实验将省略此 trace。",
                        source_experiment_id,
                        tid,
                    )
                    continue
                raise
            source_traces_by_id[tid] = fetched

    # 阶段 1b：发送目标 trace。``source="experiment"`` 与 opik.evaluate(...)
    # 在源端写入的内容一致（公共的 client.trace() 会覆盖为 source="sdk"，
    # 深层对比会标记出差异）。
    total_traces = len(new_source_ids)
    for index, source_trace_id in enumerate(new_source_ids, start=1):
        source_trace = source_traces_by_id.get(source_trace_id)
        if source_trace is None:
            # 该 trace 被实验项目引用，但无法获取（已删除 —— 参见上面容忍
            # 404 的回退）。跳过发送；下游的 span/反馈/评论复制以
            # ``source_to_new_trace`` 为键，因此被跳过的 id 不会在那里出现。
            continue
        new_trace_id = id_helpers_module.generate_id()
        source_to_new_trace[source_trace_id] = new_trace_id

        client.__internal_api__trace__(
            id=new_trace_id,
            name=source_trace.name,
            start_time=source_trace.start_time,
            end_time=source_trace.end_time,
            input=source_trace.input,
            output=source_trace.output,
            metadata=source_trace.metadata,
            tags=source_trace.tags,
            error_info=_to_error_info_dict(source_trace.error_info),
            thread_id=source_trace.thread_id,
            project_name=target_project_name,
            source=getattr(source_trace, "source", None) or "experiment",
            environment=source_trace.environment,
        )
        if inner_progress is not None:
            inner_progress.tick(label=f"trace {index}/{total_traces}")

    trace_id_remap.update(source_to_new_trace)
    # 统计实际发送的 trace，而非被引用的 —— 上面被跳过的已删除 trace
    # （404 回退）从未进入 ``source_to_new_trace``。
    traces_copied = len(source_to_new_trace)

    # 将新生成的目标 trace id 记录到检查点，并在下面的后端刷新*之前*
    # 刷新到磁盘。顺序就是全部意义所在：一旦 ``client.flush()`` 持久化了
    # 这些 trace，任何之后时刻的崩溃（OOM SIGKILL）—— 包括整个 span
    # 复制阶段 —— 都必须能在磁盘上找到这些 id，使下一次运行的恢复清理
    # 能够删除它们。在 ``_copy_traces_and_spans`` 返回之后才记录（旧做法）
    # 会使该窗口处于未覆盖状态，并在恢复时重复 trace。
    if checkpoint is not None and source_to_new_trace:
        checkpoint.record_dest_trace_ids(list(source_to_new_trace.values()))
        checkpoint.flush()

    # 在写入附着于 trace 的记录（反馈分数、断言结果、span）之前刷新
    # trace。流式处理器在刷新窗口内批量写入，不保证顺序；如果后端尚未
    # 持久化 trace，引用该 trace 的断言可能会失败。
    client.flush()
    if inner_progress is not None:
        inner_progress.tick(label="flushed traces")

    # 通过高层批量 API 重新发送 trace 级反馈分数。
    _log_trace_feedback_scores(
        client,
        source_traces_by_id=source_traces_by_id,
        source_to_new_trace=source_to_new_trace,
        target_project_name=target_project_name,
    )
    if inner_progress is not None:
        inner_progress.tick(label="logged trace feedback scores")

    # 重新发送按 trace 的断言结果。对于未读取它们的调用方（常规数据集路径）
    # 跳过。
    if assertion_results_by_source_trace:
        _log_trace_assertion_results(
            client,
            assertion_results_by_source_trace=assertion_results_by_source_trace,
            source_to_new_trace=source_to_new_trace,
            target_project_name=target_project_name,
        )
    if inner_progress is not None:
        inner_progress.tick(label="logged assertion results")

    # 通过专用的单评论写入端点重新发送 trace 评论。``TracePublic.comments``
    # 随批量 trace 读取一起携带，因此无需额外的源端获取。评论在 trace
    # 写载荷上是只读的（无法随 ``__internal_api__trace__`` 一起携带），
    # 因此我们在 trace 刷新之后一次一条地 POST —— 生产环境所有工作区合计
    # 约 4k 条评论，每条评论的成本可接受。
    trace_comments_copied = _copy_trace_comments(
        rest_client,
        source_traces_by_id=source_traces_by_id,
        source_to_new_trace=source_to_new_trace,
    )

    # 阶段 2a：为实验批量获取 span，实验的 trace 所在的每个不同项目调用
    # 一次。``search_spans``（与 ``search_traces`` 一样）在外层 SQL 上钳制
    # ``project_id``，因此单次调用无法覆盖跨项目实验。我们复用 trace
    # 批量读取之前计算的 ``traces_by_project`` 映射。
    #
    # 每个按项目的调用使用从 THAT 项目的 trace 开始/结束时间戳派生的
    # ``[from_time, to_time]`` 窗口，然后在客户端按
    # ``trace_id in <该项目的 trace_ids>`` 过滤，使同一项目 + 时间窗口中
    # 并发活动产生的 span 被丢弃。
    #
    # 我们下沉到 Fern 方法，因为高层的 ``client.search_spans`` 包装不暴露
    # ``from_time`` / ``to_time``。这是战术性 Fern 用法，仅限于这一处批量
    # 读取。
    spans_by_trace_id = _bulk_fetch_spans_for_experiment(
        client,
        source_traces_by_id=source_traces_by_id,
        traces_by_project=traces_by_project,
        project_id_to_name_cache=project_id_to_name_cache,
        expected_trace_ids=set(source_to_new_trace.keys()),
    )
    if inner_progress is not None:
        total_spans_in_bulk = sum(len(s) for s in spans_by_trace_id.values())
        inner_progress.tick(
            label=f"fetched {total_spans_in_bulk} spans in bulk "
            f"({len(traces_by_project)} project{'s' if len(traces_by_project) != 1 else ''})"
        )

    # 阶段 2b：从内存桶中按 trace 发送目标 span。与之前相同的拓扑排序 +
    # 按 trace 的 span_id_remap 逻辑；唯一的变化是 ``source_spans`` 的来源
    # 从按 trace 的 REST 调用改为字典查找。
    #
    # ``span_id_remaps_by_trace`` 保存每个 trace 的按 trace 重映射
    # （源 span id -> 目标 span id），使评论级联的后续步骤能针对正确的
    # 目标 span id 来 POST 源 span 评论。按 trace 的作用域是正确的：
    # span id 只在树内冲突，而 ``comments`` 附着于 span，因此该映射
    # 从不需要跨树。
    spans_emitted = 0
    span_feedback_scores: List[BatchFeedbackScoreDict] = []
    span_id_remaps_by_trace: Dict[str, Dict[str, str]] = {}
    span_trace_count = len(source_to_new_trace)
    for index, (source_trace_id, new_trace_id) in enumerate(
        source_to_new_trace.items(), start=1
    ):
        per_trace_count, per_trace_fbs, per_trace_span_remap = _emit_spans_for_trace(
            client,
            source_spans=spans_by_trace_id.get(source_trace_id, []),
            new_trace_id=new_trace_id,
            target_project_name=target_project_name,
        )
        spans_emitted += per_trace_count
        span_feedback_scores.extend(per_trace_fbs)
        span_id_remaps_by_trace[source_trace_id] = per_trace_span_remap
        if inner_progress is not None:
            inner_progress.tick(label=f"spans for trace {index}/{span_trace_count}")

    # 在其反馈分数之前刷新 span（后端会拒绝实体 id 尚不存在的分数）。
    client.flush()

    if span_feedback_scores:
        client.log_spans_feedback_scores(
            scores=span_feedback_scores, project_name=target_project_name
        )
    if inner_progress is not None:
        inner_progress.tick(label="flushed spans + logged span feedback scores")

    # 通过专用的单评论写入端点重新发送 span 评论，在 span 刷新之后进行，
    # 使目标 span id 已持久化。与 trace 评论形状相同：``SpanPublic.comments``
    # 随批量 span 读取一起携带（无需额外获取），每条评论的 POST 都用
    # 速率限制辅助函数包裹。
    span_comments_copied = _copy_span_comments(
        rest_client,
        spans_by_trace_id=spans_by_trace_id,
        span_id_remaps_by_trace=span_id_remaps_by_trace,
    )

    return traces_copied, spans_emitted, trace_comments_copied, span_comments_copied


def _log_trace_feedback_scores(
    client: opik.Opik,
    *,
    source_traces_by_id: Dict[str, TracePublic],
    source_to_new_trace: Dict[str, str],
    target_project_name: str,
) -> None:
    """通过 ``client.log_traces_feedback_scores`` 在目标项目下重新发送
    按 trace 的反馈分数。

    从每个源 trace 的读取载荷中读取 ``feedback_scores``（在 trace 复制期间
    已获取，无需额外往返），并以目标 trace id 为键重写它们。高层 API
    处理批处理 + 流式路由。

    对没有反馈分数的 trace 为空操作。
    """
    batch: List[BatchFeedbackScoreDict] = []
    for source_trace_id, new_trace_id in source_to_new_trace.items():
        source = source_traces_by_id.get(source_trace_id)
        if source is None or not source.feedback_scores:
            continue
        for score in source.feedback_scores:
            entry: BatchFeedbackScoreDict = {
                "id": new_trace_id,
                "project_name": target_project_name,
                "name": score.name,
                "value": score.value,
            }
            if score.reason is not None:
                entry["reason"] = score.reason
            if score.category_name is not None:
                entry["category_name"] = score.category_name
            batch.append(entry)

    if not batch:
        return

    client.log_traces_feedback_scores(scores=batch, project_name=target_project_name)


def _log_trace_assertion_results(
    client: opik.Opik,
    *,
    assertion_results_by_source_trace: Dict[str, List[Any]],
    source_to_new_trace: Dict[str, str],
    target_project_name: str,
) -> None:
    """通过 ``client.log_assertion_results`` 重新发送按 trace 的断言结果。

    断言结果不是 ``ExperimentItem`` 上的字段（后端在写入时会丢弃该字段
    —— 它在 Compare 视图上是只读的，由底层断言结果实体表计算得出）。
    它们通过专用的断言结果摄取端点写入，高层客户端将其暴露为
    ``log_assertion_results`` —— 与其他写入一样经过流式处理器。

    对于 Slice 3，我们只能通过 Compare 视图的
    ``ExperimentItemCompare.assertion_results`` 在项目上看到断言结果；
    这些是源端拥有的 trace 级写入。我们以新的目标 trace id 为作用域
    重新发送它们，使目标 ``ExperimentItemCompare`` 在相同位置以相同形状
    暴露它们。

    读取形状 ``AssertionResultCompare`` 携带 ``value`` / ``passed`` /
    ``reason``；``client.log_assertion_results`` 接受带有 ``id``（= trace id）、
    ``name``、``status``（"passed" | "failed"）、``reason`` 的字典。映射为：

      AssertionResultCompare.value  <->  log_assertion_results.name
      AssertionResultCompare.passed <->  log_assertion_results.status
                                          ("passed" | "failed")
      AssertionResultCompare.reason <->  log_assertion_results.reason
    """
    batch: List[BatchAssertionResultDict] = []
    for source_trace_id, results in assertion_results_by_source_trace.items():
        new_trace_id = source_to_new_trace.get(source_trace_id)
        if not new_trace_id:
            # 该 trace 未被复制（例如先前的幂等跳过）；没有可重映射的目标。
            continue
        for ar in results:
            # ``ar`` 是 ``AssertionResultCompare``；为在测试中传入类字典
            # 替身的调用方做防御性 .get。
            value = (
                getattr(ar, "value", None)
                if not isinstance(ar, dict)
                else ar.get("value")
            )
            passed = (
                getattr(ar, "passed", None)
                if not isinstance(ar, dict)
                else ar.get("passed")
            )
            reason = (
                getattr(ar, "reason", None)
                if not isinstance(ar, dict)
                else ar.get("reason")
            )
            if value is None or passed is None:
                # 跳过退化条目；后端写入会拒绝缺少必需 ``name``/``status``
                # 字段的项目。
                continue
            # ``status`` 在 ``BatchAssertionResultDict`` 上是
            # ``Literal["passed", "failed"]``；显式三元表达式保持 mypy 的
            # 收窄不变（裸的 ``str`` 会拓宽并失败）。
            status: Literal["passed", "failed"] = "passed" if passed else "failed"
            entry: BatchAssertionResultDict = {
                "id": new_trace_id,
                "project_name": target_project_name,
                "name": value,
                "status": status,
            }
            if reason is not None:
                entry["reason"] = reason
            batch.append(entry)

    if not batch:
        return

    client.log_assertion_results(
        assertion_results=batch, project_name=target_project_name
    )


def _copy_trace_comments(
    rest_client: OpikApi,
    *,
    source_traces_by_id: Dict[str, TracePublic],
    source_to_new_trace: Dict[str, str],
) -> int:
    """将每个源 trace 的评论重新发送到目标 trace 上。

    ``TracePublic.comments`` 随批量 trace 读取一起携带（无需额外源端获取）。
    评论在 trace 写载荷上是只读的，因此无法随 ``__internal_api__trace__``
    一起携带；我们在目标 trace 落地后，通过专用的单评论写入端点一次一条
    地 POST。

    通过原地迭代源 ``comments`` 列表来保持顺序：后端按 ``createdAt`` 排序
    返回评论，且 POST 是串行的，因此目标端的读取顺序匹配。

    返回复制的评论总数（所有 trace 之和）。
    """
    copied = 0
    for source_trace_id, new_trace_id in source_to_new_trace.items():
        source_trace = source_traces_by_id.get(source_trace_id)
        if source_trace is None or not source_trace.comments:
            continue
        for comment in source_trace.comments:
            rest_helpers.ensure_rest_api_call_respecting_rate_limit(
                lambda nt=new_trace_id, text=comment.text: (
                    rest_client.traces.add_trace_comment(id_=nt, text=text)
                ),
                operation_name="add_trace_comment (experiment cascade)",
            )
            copied += 1
    return copied


def _copy_span_comments(
    rest_client: OpikApi,
    *,
    spans_by_trace_id: Dict[str, List[SpanPublic]],
    span_id_remaps_by_trace: Dict[str, Dict[str, str]],
) -> int:
    """将每个源 span 的评论重新发送到目标 span 上。

    与 ``_copy_trace_comments`` 形状相同：``SpanPublic.comments`` 随批量
    span 读取一起携带，评论在目标 span 落地后通过专用的单评论写入端点
    POST。

    目标 span id 来自 ``_emit_spans_for_trace`` 中填充的按 trace
    ``span_id_remap``。按 trace 的作用域是正确的 —— span id 只在树内
    冲突，因此该重映射从不需要跨树。

    返回复制的评论总数（所有 span 之和）。
    """
    copied = 0
    for source_trace_id, source_spans in spans_by_trace_id.items():
        span_id_remap = span_id_remaps_by_trace.get(source_trace_id, {})
        if not span_id_remap:
            continue
        for source_span in source_spans:
            if not source_span.comments:
                continue
            new_span_id = span_id_remap.get(source_span.id or "")
            if new_span_id is None:
                # 防御性：我们写入的每个 span 都会产生一条重映射条目，
                # 因此只有当批量获取返回了我们未写入的 span 时才会走到
                # 这里（例如级联的期望 id 过滤器丢弃了它）。跳过而非崩溃。
                continue
            for comment in source_span.comments:
                rest_helpers.ensure_rest_api_call_respecting_rate_limit(
                    lambda ns=new_span_id, text=comment.text: (
                        rest_client.spans.add_span_comment(id_=ns, text=text)
                    ),
                    operation_name="add_span_comment (experiment cascade)",
                )
                copied += 1
    return copied


def _resolve_project_name(
    client: opik.Opik,
    *,
    project_id: str,
    cache: Dict[str, str],
) -> str:
    """将 project_id 转换为 project_name，并带缓存。

    ``client.search_traces`` 和 ``client.search_spans`` 接受
    ``project_name``（底层后端端点两者都接受，但 SDK 只暴露
    ``project_name``）。级联手头有来自 ``source_experiment.project_id``
    和按 trace ``trace.project_id`` 的 ``project_id``；解析一次并缓存意味着
    每个实验对每个不同项目最多付出一次 ``client.get_project(id=...)``
    （通常为 1 次）。
    """
    cached = cache.get(project_id)
    if cached is not None:
        return cached
    project = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: client.get_project(id=project_id),
        operation_name="get_project (project_id -> name resolution)",
    )
    name = project.name
    cache[project_id] = name
    return name


def _compute_span_time_window(
    traces: Dict[str, TracePublic],
) -> Optional[Tuple[datetime, datetime]]:
    """从一批 trace 推导出 ``(from_time, to_time)`` 窗口。

    该窗口从 ``min(start_time) - buffer`` 跨越到
    ``max(end_time, last_updated_at) + buffer``，使一次批量
    ``search_spans(from_time=…, to_time=…)`` 调用能在一个往返中获取这些
    trace 所辖的所有 span。``last_updated_at`` 是 ``end_time`` 缺失时的回退
    （trace 从未干净地完成，或后端的形状与预期不同）。

    当没有任何 trace 具有可用时间戳时返回 ``None`` —— 调用方应将其视为
    “无时间界限”，并把 ``from_time`` / ``to_time`` 传为 ``None``（后端随后
    返回项目中所有匹配的 span，调用方已在客户端按 ``trace_id`` 过滤）。
    """
    starts: List[datetime] = []
    ends: List[datetime] = []
    for trace in traces.values():
        if trace.start_time is not None:
            starts.append(_as_aware(trace.start_time))
        upper = trace.end_time or trace.last_updated_at
        if upper is not None:
            ends.append(_as_aware(upper))
    if not starts and not ends:
        return None
    # 如果只有一侧有值，则将缺失的一侧锚定到它，使窗口仍有界。
    earliest = min(starts) if starts else min(ends)
    latest = max(ends) if ends else max(starts)
    return (earliest - _SPAN_BULK_WINDOW_BUFFER, latest + _SPAN_BULK_WINDOW_BUFFER)


def _as_aware(value: datetime) -> datetime:
    """将 naive datetime 强制转换为 UTC 感知的 datetime。

    后端时间戳始终是 UTC；根据版本的不同，SDK 的线格式类型有时会将它们
    反序列化为 naive datetime。对混合的 naive/aware datetime 调用
    ``min`` / ``max`` 会抛出 TypeError，因此我们在边界处统一规范化一次。
    """
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value


def _bulk_fetch_spans_for_experiment(
    client: opik.Opik,
    *,
    source_traces_by_id: Dict[str, TracePublic],
    traces_by_project: Dict[str, Set[str]],
    project_id_to_name_cache: Dict[str, str],
    expected_trace_ids: Set[str],
) -> Dict[str, List[SpanPublic]]:
    """批量获取 ``expected_trace_ids`` 所辖的每个 span。

    实验的 trace 所在的每个不同项目调用一次 ``client.search_spans``。
    后端的 ``search_spans`` 在外层 SQL 上钳制 ``project_id = :project_id``，
    因此单次调用只能覆盖一个项目中的 trace。``traces_by_project``（先前
    从 ``streamExperimentItems`` 计算得出）告诉我们每个 trace 实际位于
    何处，因此此循环能无损地覆盖跨项目实验。

    每个按项目的调用使用从 THAT 项目的 trace 开始/结束时间戳派生的
    ``filter_string="start_time >= … AND start_time <= …"`` 子句。后端过滤器
    语法通过 ``SpanField.START_TIME`` 上的日期时间过滤器运算符接受边界。
    同一项目 + 时间窗口中并发活动产生的 span 会被客户端的
    ``span.trace_id in expected_trace_ids`` 过滤器丢弃。

    返回覆盖 ``expected_trace_ids`` 中每个 id 的字典 ``{trace_id: [spans]}``。
    缺失条目以空列表形式存在，使调用方能检测零桶情况并记录日志。没有
    span 的 trace 是合法情况（没有 LLM 调用，没有子操作），因此我们对
    空桶不报错 —— 只记录日志。

    替换了先前的 ``N × search_spans(trace_id=…)`` 循环，后者在具有严格
    ``search_spans:{workspaceId}`` 桶的工作区上，对大型实验会产生
    “30 次后暂停”的节流模式。
    """
    spans_by_trace: Dict[str, List[SpanPublic]] = {
        tid: [] for tid in expected_trace_ids
    }

    for project_id, trace_ids_in_project in traces_by_project.items():
        project_name = _resolve_project_name(
            client, project_id=project_id, cache=project_id_to_name_cache
        )

        # 将时间窗口收窄到仅 THIS 项目的 trace —— 缩小与实验无关的
        # 并发活动造成的过度获取。
        per_project_traces = {
            tid: source_traces_by_id[tid]
            for tid in trace_ids_in_project
            if tid in source_traces_by_id
        }
        # 下面的两个分支原本会落入无界、无过滤的
        # ``search_spans(filter_string=None, max_results=sys.maxsize)`` ——
        # 一次会在大项目上让客户端 OOM 的全项目读取（OPIK-7344）。
        # 改为跳过 + 警告 + 继续：此桶中的目标 trace 仍会被复制，
        # 只是没有 span。针对无时间戳情况的有界按 trace 回退推迟到
        # OPIK-7343（一旦 span 读取按 ``trace_id IN (...)`` 限定作用域，
        # 无界读取就从结构上消失了）。
        if not per_project_traces:
            # 此桶引用的每个 trace 都缺失于实验已获取的 trace ——
            # 实验项目仍携带的过期/已删除引用。没有可获取的内容。
            LOGGER.warning(
                "跳过项目 %s 的 span 读取：所有 %d 个被引用的 "
                "trace_id 都已过期/已删除（缺失于实验已获取的 trace）。"
                "这避免了一次无界的全项目 span 读取。",
                project_id,
                len(trace_ids_in_project),
            )
            continue

        window = _compute_span_time_window(per_project_traces)
        if window is None:
            # 此桶的 trace 存在，但都不携带 start/end/last_updated 时间戳，
            # 因此无法为读取设定边界。
            LOGGER.warning(
                "跳过项目 %s 的 span 读取：%d 个 trace 没有可用于限定读取的"
                "时间戳。目标 trace 将在没有 span 的情况下被复制。"
                "这避免了一次无界的全项目 span 读取。",
                project_id,
                len(per_project_traces),
            )
            continue

        from_time, to_time = window
        # 带显式 ``Z`` UTC 后缀的 ISO 8601 匹配后端过滤器语法的日期时间
        # 字面量格式（参见 ``client.search_spans`` 的 search_spans docstring：
        # “使用 ISO 8601 格式，例如 '2024-01-01T00:00:00Z'”）。
        # ``SpanField.END_TIME`` 也可过滤，但对两个边界都使用 ``start_time``
        # 能保持过滤器 AST 简单，并让后端的主键范围扫描保持紧凑。
        filter_string = (
            f'start_time >= "{_to_iso_z(from_time)}" '
            f'AND start_time <= "{_to_iso_z(to_time)}"'
        )

        all_spans = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            operation_name="search_spans (experiment bulk read)",
            rest_callable=lambda pn=project_name, fs=filter_string: client.search_spans(
                project_name=pn,
                filter_string=fs,
                max_results=sys.maxsize,
                truncate=False,
                max_batch_size=MIGRATION_SEARCH_PAGE_SIZE,
            ),
        )

        for span in all_spans:
            tid = span.trace_id
            # 双重过滤：必须是实验期望的 trace_ids 之一，且必须属于此项目
            # 的子集（外层 ``project_name`` 过滤器已经限制了返回的 span，
            # 但这里再检查一次可使契约显式化，并防御任何后端怪异行为）。
            if (
                tid is not None
                and tid in spans_by_trace
                and tid in trace_ids_in_project
            ):
                spans_by_trace[tid].append(span)

    # 暴露零桶 trace。可能是合法的（源 trace 上确实没有 span），也可能是
    # 批量窗口漏掉了它们。我们目前不区分 —— 目标 trace 仍会被复制，
    # 只是没有 span —— 但我们会记录日志，使操作员能发现大规模遗漏。
    empty_bucket_ids = [tid for tid, spans in spans_by_trace.items() if not spans]
    if empty_bucket_ids:
        LOGGER.warning(
            "实验的批量 span 读取对 %d/%d 个期望的 trace_ids 返回了零个 span。"
            "要么这些 trace 确实没有 span，要么 [from_time, to_time] 窗口"
            "漏掉了迟到的 span。目标 trace 仍会被复制，只是没有其 span。"
            "缺失的 trace_ids 示例：%s",
            len(empty_bucket_ids),
            len(expected_trace_ids),
            empty_bucket_ids[:5],
        )

    return spans_by_trace


def _to_iso_z(value: datetime) -> str:
    """将 UTC datetime 格式化为后端过滤器语法的日期时间字面量。

    后端期望形如 ``"2024-01-01T00:00:00Z"`` 的字符串。Python 的
    ``isoformat()`` 对 aware UTC datetime 会生成
    ``"2024-01-01T00:00:00+00:00"``；我们将偏移量替换为 ``Z`` 后缀，
    使过滤器解析器无需额外转换即可接受。存在微秒时保留微秒。
    """
    iso = value.astimezone(timezone.utc).isoformat()
    # 对 aware UTC datetime 调用 ``.isoformat()`` 会以 ``"+00:00"`` 结尾。
    if iso.endswith("+00:00"):
        iso = iso[: -len("+00:00")] + "Z"
    return iso


def _to_error_info_dict(error_info: Any) -> Optional[ErrorInfoDict]:
    """将线格式的 ``ErrorInfoPublic``（或已经是字典）转换为流式处理器 /
    高层 API 所期望的 ``ErrorInfoDict`` TypedDict 形状。

    当没有错误信息时返回 ``None``，使调用方可以原样传递而无需处理 nil。
    在边界处 ``cast``，因为运行时形状（``exception_type`` + ``traceback``
    加上可选的 ``message``）已经匹配 TypedDict 的必需键 —— 后端写入总是
    填充它们 —— 但 mypy 无法从泛型字典 / ``model_dump`` 调用中推断出
    这一点。
    """
    if error_info is None:
        return None
    if isinstance(error_info, dict):
        return cast(ErrorInfoDict, error_info)
    dump = getattr(error_info, "model_dump", None)
    if dump is not None:
        return cast(ErrorInfoDict, dump(exclude_none=True))
    return cast(ErrorInfoDict, dict(getattr(error_info, "__dict__", {})))


def _emit_spans_for_trace(
    client: opik.Opik,
    *,
    source_spans: List[SpanPublic],
    new_trace_id: str,
    target_project_name: str,
) -> Tuple[int, List[BatchFeedbackScoreDict], Dict[str, str]]:
    """在保持父树的前提下生成新 id，并通过直接的
    ``client._streamer.put(CreateSpanMessage(...))`` 调用发送目标 span。
    返回 ``(spans_emitted, span_feedback_scores, span_id_remap)``。

    ``span_id_remap``（源 span id -> 目标 span id）是评论级联后续步骤
    所需要的，使其能针对正确的目标 span id 来 POST 每个源 span 的
    ``comments``。

    ``source_spans`` 是 ONE 个 trace 的预取桶，由
    ``_bulk_fetch_spans_for_experiment`` 中的实验级批量读取填充。先前通过
    ``search_spans(trace_id=…)`` 按 trace 获取；批量读取重构将其移出，
    以在整个实验上摊销速率限制成本。

    为何绕过 ``client.span(...)``：公共方法经过
    ``span_client.create_span()``，它会调用
    ``helpers.add_usage_to_metadata`` —— 一个面向用户的便利功能，对新的
    写入把 ``usage`` 合并进 ``metadata["usage"]``。级联需要严格的往返元数据
    保真（源将 ``usage`` 和 ``metadata`` 作为不同字段，合并会使目标端
    产生偏差）。流式处理器的 ``CreateSpanMessage`` 将 ``usage`` 和
    ``metadata`` 作为独立字段接受并逐字序列化，因此直接构建消息能同时
    保留两者。

    span_id 重映射保持按 trace —— 在 trace 树内，父必须先于子，且 span id
    只在树内冲突。

    返回 span 级反馈分数，供调用方在 span 刷新后通过
    ``client.log_spans_feedback_scores`` 批量发送。
    """
    from opik import datetime_helpers
    from opik.message_processing import messages

    if not source_spans:
        return 0, [], {}

    # 拓扑顺序：父先于子，因此处理每个子节点时，其 parent_span_id 重映射
    # 条目始终已填充。``sort_spans_topologically`` 操作字典；我们通过
    # model_dump 转换后再转回。
    span_dicts = [span.model_dump() for span in source_spans]
    span_dicts = sort_spans_topologically(span_dicts)

    span_id_remap: Dict[str, str] = {}
    feedback_scores: List[BatchFeedbackScoreDict] = []
    spans_emitted = 0
    for span_dict in span_dicts:
        original_id = span_dict.get("id")
        new_span_id = id_helpers_module.generate_id()
        if original_id:
            span_id_remap[original_id] = new_span_id

        original_parent = span_dict.get("parent_span_id")
        new_parent = span_id_remap.get(original_parent) if original_parent else None

        # 直接构建 CreateSpanMessage；绕过 span_client.create_span 的
        # add_usage_to_metadata 合并。从 SpanPublic -> CreateSpanMessage
        # 逐字段映射。
        msg = messages.CreateSpanMessage(
            span_id=new_span_id,
            trace_id=new_trace_id,
            project_name=target_project_name,
            parent_span_id=new_parent,
            name=span_dict.get("name"),
            type=span_dict.get("type") or "general",
            start_time=span_dict.get("start_time")
            or datetime_helpers.local_timestamp(),
            end_time=span_dict.get("end_time"),
            input=span_dict.get("input"),
            output=span_dict.get("output"),
            metadata=span_dict.get("metadata"),
            tags=span_dict.get("tags"),
            usage=span_dict.get("usage"),
            model=span_dict.get("model"),
            provider=span_dict.get("provider"),
            error_info=_to_error_info_dict(span_dict.get("error_info")),
            total_cost=span_dict.get("total_estimated_cost"),
            last_updated_at=span_dict.get("last_updated_at"),
            source=span_dict.get("source") or "experiment",
            environment=span_dict.get("environment"),
        )
        client._streamer.put(msg)
        spans_emitted += 1

        # 收集以新 span id 为键的按 span 反馈分数，使调用方能在 span 刷新后
        # 通过 log_spans_feedback_scores 批量发送它们。
        for score in span_dict.get("feedback_scores") or []:
            entry: BatchFeedbackScoreDict = {
                "id": new_span_id,
                "project_name": target_project_name,
                "name": score["name"],
                "value": score["value"],
            }
            if score.get("reason") is not None:
                entry["reason"] = score["reason"]
            if score.get("category_name") is not None:
                entry["category_name"] = score["category_name"]
            feedback_scores.append(entry)

    return spans_emitted, feedback_scores, span_id_remap


def _build_experiment_data(
    source: ExperimentPublic,
    items: List[experiment_item.ExperimentItemContent],
    *,
    optimization_id_remap: Dict[str, str],
    result: ExperimentCascadeResult,
) -> ExperimentData:
    """将 REST ``ExperimentPublic`` + 项目适配为 ``recreate_experiment``
    消费的 ``ExperimentData`` 数据类。

    磁盘导出形状将实验元数据存储为与后端 schema 字段名匹配的扁平字典；
    我们在此镜像该形状，使 ``recreate_experiment`` 能逐字读取 ``type`` /
    ``evaluation_method`` / ``optimization_id`` / ``tags`` / ``metadata`` /
    ``dataset_name`` 等字段。

    ``optimization_id`` 通过 ``optimization_id_remap``（由先前的
    ``CascadeOptimizations`` 动作填充）重新指向目标优化 id。如果源实验
    携带 ``optimization_id`` 但重映射中没有条目，则省略该字段以避免悬空
    指针写入，并为审计递增 ``experiments_with_orphan_optimization_id``
    —— 这仅在 planner 顺序被破坏时发生。

    按项目的载荷只携带外键字段。后端的 ``ExperimentItem`` 写视图只接受
    ``id`` / ``experiment_id`` / ``dataset_item_id`` / ``trace_id``
    （加上 ``project_name``）；在 Compare 视图上暴露的其余每个按项目字段
    （``input`` / ``output`` / ``feedback_scores`` / ``assertion_results`` /
    ``execution_policy`` / ``description`` / ``status`` / ``usage`` /
    ``total_estimated_cost`` / ``duration``）是只读的，并由底层的
    trace + span + assertion-result 实体计算/聚合。级联确保这些底层实体
    在目标端被正确填充（trace + span 连同反馈分数一起复制；断言结果通过
    以新 trace id 为作用域的专用 ``assertion_results.store_assertions_batch``
    端点复制）；其余部分由后端在读取时暴露。
    """
    experiment_dict: Dict[str, Any] = {
        "id": source.id,
        "name": source.name,
        "dataset_name": source.dataset_name,
        "dataset_id": source.dataset_id,
        "dataset_version_id": source.dataset_version_id,
        "metadata": source.metadata,
        "tags": source.tags,
        "type": source.type if source.type else "regular",
        "evaluation_method": (
            source.evaluation_method if source.evaluation_method else "dataset"
        ),
    }

    source_optimization_id = source.optimization_id
    if source_optimization_id:
        destination_optimization_id = optimization_id_remap.get(source_optimization_id)
        if destination_optimization_id:
            experiment_dict["optimization_id"] = destination_optimization_id
        else:
            # 防御性：CascadeOptimizations 在 CascadeExperiments 之前运行，
            # 因此重映射应始终包含此 id。大声记录日志并省略该字段，
            # 而不是写入悬空外键。
            LOGGER.warning(
                "源实验 %s 携带的 optimization_id %s 在 optimization_id_remap "
                "中没有条目；正在从目标载荷中省略。planner 顺序可能已被破坏。",
                source.id,
                source_optimization_id,
            )
            result.experiments_with_orphan_optimization_id += 1

    items_dicts = [
        {
            "id": item.id,
            "trace_id": item.trace_id,
            "dataset_item_id": item.dataset_item_id,
        }
        for item in items
    ]
    return ExperimentData(experiment=experiment_dict, items=items_dicts)
