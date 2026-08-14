from typing import Dict, Iterable, List, Optional, Set, TYPE_CHECKING
from uuid import UUID

from opik.api_objects import span, trace

if TYPE_CHECKING:
    from langchain_core.tracers.schemas import Run


class RunStateStore:
    """为 :class:`OpikTracer` 维护每个运行（run）的簿记状态。

    维护从 LangChain run id 到 tracer 为其创建的 span/trace 数据的映射。
    trace 所有权——即 tracer 是必须终结某个 trace，还是将其留给创建者——
    由同一份 trace 数据映射推导得出（参见 :meth:`owns_trace`），而非单独跟踪。

    所有这些状态都限定在进行中（in-flight）的 run 范围内。:meth:`release_run_tree`
    会丢弃属于某个已结束的根 run 子树的所有条目，因此跨多次调用复用的
    长生命周期 tracer（即文档所述的“构建一次，每次 invoke 时传入”模式）在
    内存中保持平稳，而不会随进程生命周期不断增长并保留已缓存的
    prompt/completion 负载。
    """

    def __init__(self) -> None:
        self._span_data_by_run_id: Dict[UUID, span.SpanData] = {}
        self._trace_data_by_run_id: Dict[UUID, trace.TraceData] = {}
        self._skipped_langgraph_root_run_ids: Set[UUID] = set()

    def save_span_data(self, run_id: UUID, span_data: span.SpanData) -> None:
        self._span_data_by_run_id[run_id] = span_data

    def save_trace_data(self, run_id: UUID, trace_data: trace.TraceData) -> None:
        self._trace_data_by_run_id[run_id] = trace_data

    def get_span_data(self, run_id: UUID) -> Optional[span.SpanData]:
        return self._span_data_by_run_id.get(run_id)

    def get_trace_data(self, run_id: UUID) -> Optional[trace.TraceData]:
        return self._trace_data_by_run_id.get(run_id)

    def spans_for_trace(self, trace_id: str) -> Iterable[span.SpanData]:
        return [
            span_data
            for span_data in self._span_data_by_run_id.values()
            if span_data.trace_id == trace_id
        ]

    def link_child_run_to_parent_trace(
        self, child_run_id: UUID, parent_run_id: UUID, trace_id: str
    ) -> None:
        """将子 run 指向其父 run 的 trace 数据。

        当父 run 自身没有 trace 数据（例如仅以 span 形式存在的流式重启根节点）时，
        会退回到按 ``trace_id`` 查找；若两者都找不到，则保持子 run 未关联。
        """
        trace_data = self._trace_data_by_run_id.get(parent_run_id)
        if trace_data is None:
            trace_data = self._find_trace_data_by_trace_id(trace_id)

        if trace_data is not None:
            self._trace_data_by_run_id[child_run_id] = trace_data

    def _find_trace_data_by_trace_id(self, trace_id: str) -> Optional[trace.TraceData]:
        for trace_data in self._trace_data_by_run_id.values():
            if trace_data.id == trace_id:
                return trace_data
        return None

    def mark_skipped_langgraph_root(self, run_id: UUID) -> None:
        self._skipped_langgraph_root_run_ids.add(run_id)

    def is_skipped_langgraph_root(self, run_id: UUID) -> bool:
        return run_id in self._skipped_langgraph_root_run_ids

    def owns_trace(self, trace_id: str) -> bool:
        """判断该 tracer 是否创建了该 trace（从而必须终结它）。

        tracer 未创建的 trace——例如外部的 ``@track`` trace、分布式头（distributed
        header）trace 或调用方提供的 trace——永远不会出现在 trace 数据映射中，
        因此本方法返回 False，tracer 也会对其置之不理。
        """
        return any(
            trace_data.id == trace_id
            for trace_data in self._trace_data_by_run_id.values()
        )

    def is_empty(self) -> bool:
        return not (
            self._span_data_by_run_id
            or self._trace_data_by_run_id
            or self._skipped_langgraph_root_run_ids
        )

    def release_run_tree(self, root_run: "Run") -> None:
        """丢弃某个已结束的根 run 及其整个子树的所有状态。

        LangChain 会将整个子树挂载到根 run 上，因此遍历它即可得到本存储
        为其建立簿记的每个 run id。
        """
        pending_runs: List["Run"] = [root_run]
        while pending_runs:
            current_run = pending_runs.pop()
            pending_runs.extend(current_run.child_runs)
            run_id = current_run.id

            self._span_data_by_run_id.pop(run_id, None)
            self._trace_data_by_run_id.pop(run_id, None)
            self._skipped_langgraph_root_run_ids.discard(run_id)
