import functools
import logging
from concurrent import futures
from typing import List, Optional, TYPE_CHECKING

from opik.message_processing.batching import sequence_splitter
from opik.message_processing import messages, streamer
from opik.rest_api import client as rest_api_client
from opik.rest_api import types as rest_api_types
from . import bulk_converters, bulk_item, experiment_item, experiments_client
from .. import constants, helpers, rest_helpers
from ...api_objects.prompt import base_prompt
from ... import exceptions

if TYPE_CHECKING:
    from opik.evaluation.metrics import score_result

LOGGER = logging.getLogger(__name__)


def _raise_on_oversized_items(
    rest_items: List[
        rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView
    ],
) -> None:
    """拒绝自身就无法放入单个请求的项目。

    ``split_into_batches`` 会把超大项目单独放入一个批次而不是丢弃它，
    这会发送一个后端必定以 422 拒绝的请求。在此处失败则会指明有问题的项目。

    该界限是包含式的，与 ``split_into_batches`` 保持一致：一个大小恰好
    等于限制的项目本身就已占满一个批次，没有为请求信封留出空间。
    """
    failure_reasons = [
        f"items[{index}] 大小为 {size_MB:.1f}MB，达到或超过了 "
        f"{constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB}MB 的每请求限制"
        for index, size_MB in (
            (index, sequence_splitter.get_payload_size_MB(item))
            for index, item in enumerate(rest_items)
        )
        if size_MB >= constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB
    ]

    if failure_reasons:
        raise exceptions.ValidationError(
            prefix="batch_upload_items", failure_reasons=failure_reasons
        )


class Experiment:
    def __init__(
        self,
        id: str,
        name: Optional[str],
        dataset_name: str,
        rest_client: rest_api_client.OpikApi,
        streamer: streamer.Streamer,
        experiments_client: experiments_client.ExperimentsClient,
        prompts: Optional[List[base_prompt.BasePrompt]] = None,
        tags: Optional[List[str]] = None,
        project_name: Optional[str] = None,
    ) -> None:
        self._id = id
        self._name = name
        self._dataset_name = dataset_name
        self._rest_client = rest_client
        self._prompts = prompts
        self._streamer = streamer
        self._experiments_client = experiments_client
        self._tags = tags
        self._project_name = project_name

    @property
    def project_name(self) -> Optional[str]:
        return self._project_name

    @property
    def id(self) -> str:
        return self._id

    @property
    def dataset_name(self) -> str:
        return self._dataset_name

    @property
    def name(self) -> str:
        if self._name is not None:
            return self._name

        name = self._rest_client.experiments.get_experiment_by_id(id=self.id).name
        self._name = name

        return name

    @property
    def tags(self) -> Optional[List[str]]:
        return self._tags

    @property
    def prompts(self) -> Optional[List[base_prompt.BasePrompt]]:
        return self._prompts

    @functools.cached_property
    def dataset_id(self) -> str:
        return self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=self._dataset_name
        ).id

    @property
    def experiments_rest_client(self) -> rest_api_client.ExperimentsClient:
        return self._rest_client.experiments

    def get_experiment_data(self) -> rest_api_types.experiment_public.ExperimentPublic:
        return self._rest_client.experiments.get_experiment_by_id(id=self.id)

    def insert(
        self,
        experiment_items_references: List[experiment_item.ExperimentItemReferences],
    ) -> None:
        """
        通过链接现有的 trace 和数据集项目创建新的实验项目。

        Args:
            experiment_items_references: ExperimentItemReferences 对象列表，
                包含要链接在一起的 trace id 和数据集项目 id。

        Returns:
            None
        """

        experiment_item_messages = [
            messages.ExperimentItemMessage(
                id=helpers.generate_id(),
                experiment_id=self.id,
                dataset_item_id=item.dataset_item_id,
                trace_id=item.trace_id,
                project_name=item.project_name,
                execution_policy=item.execution_policy,
            )
            for item in experiment_items_references
        ]

        # 拆分为批次发送给流处理器
        batches = sequence_splitter.split_into_batches(
            experiment_item_messages,
            max_length=constants.FEEDBACK_SCORES_MAX_BATCH_SIZE,
        )

        for batch in batches:
            create_experiment_items_batch_message = (
                messages.CreateExperimentItemsBatchMessage(batch=batch)
            )
            self._streamer.put(create_experiment_items_batch_message)

    def _bulk_upload_batch_with_retry(
        self,
        batch: List[rest_api_types.ExperimentItemBulkRecordExperimentItemBulkWriteView],
        project_name: Optional[str],
    ) -> None:
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: self._rest_client.experiments.experiment_items_bulk(
                experiment_id=self.id,
                experiment_name=self.name,
                dataset_name=self.dataset_name,
                project_name=project_name,
                items=batch,
            ),
            operation_name="experiment_items_bulk",
        )
        LOGGER.debug(
            "成功发送大小为 %d 的实验项目批量批次", len(batch)
        )

    def batch_upload_items(
        self,
        items: List[bulk_item.ExperimentItemBulkRecord],
        project_name: Optional[str] = None,
        num_threads: int = 1,
    ) -> None:
        """
        将实验项目连同它们的 trace、span 和反馈分数一起上传。

        与 :meth:`insert`（仅将已存在的 trace 链接到数据集项目）不同，
        此方法会在同一个请求中创建 trace 和 span。

        项目会先经过前置校验，拆分为遵守后端每请求 1000 项和 4MB 限制的
        批次，并在遇到速率限制（HTTP 429）时自动重试后发送。

        如果某个批次失败，异常会向上传播，剩余的批次不会被发送，导致实验
        处于部分填充状态。速率限制重试会重新发送完全相同的负载，因此绝不会
        重复任何内容。不过，再次调用此方法会为任何没有 id 的 trace 或 span
        铸造新的 id，这会重复第一次调用已成功写入的内容——如果你打算重试
        一次失败的上传，请为你传入的 trace 和 span 设置 ``id``。

        Args:
            items: 要上传的实验项目。每个项目必须恰好提供
                ``evaluate_task_result`` 或 ``trace`` 其中之一。
            project_name: 为从提供 ``evaluate_task_result`` 的项目自动创建的
                trace 指定的项目。默认为实验的项目；空白字符串按未设置处理。
                设置后，每个项目级的 ``trace.project_name`` 都必须与它匹配。
            num_threads: 并发上传的批次数。默认为 1（顺序）。增大它以吞吐量
                换取顺序性和更高的被限流概率。上限为批次数以及
                ``constants.EXPERIMENT_ITEMS_BULK_MAX_THREADS``。

        Returns:
            None

        Raises:
            opik.exceptions.ValidationError: 如果有任何项目校验失败、某个
                项目过大无法放入单个请求，或 ``num_threads`` 小于 1。
        """
        if num_threads < 1:
            raise exceptions.ValidationError(
                prefix="batch_upload_items",
                failure_reasons=[f"num_threads 必须至少为 1，实际为 {num_threads}"],
            )

        if not items:
            return

        resolved_project_name = (
            project_name if project_name is not None else self._project_name
        )
        # 后端用 @Pattern(NULL_OR_NOT_BLANK) 注解 project_name，因此空白字符串
        # 会被直接拒绝，而不是回退到默认项目。将它按未设置处理，
        # 这正是调用者的本意。
        if resolved_project_name is not None and not resolved_project_name.strip():
            resolved_project_name = None

        bulk_converters.validate_records(items, project_name=resolved_project_name)

        rest_items = [bulk_converters.to_rest_record(item) for item in items]

        _raise_on_oversized_items(rest_items)

        batches = sequence_splitter.split_into_batches(
            rest_items,
            max_payload_size_MB=constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE_MB,
            max_length=constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE,
        )

        LOGGER.debug(
            "正在上传 %d 个实验项目，分为 %d 个批次，使用 %d 个线程",
            len(rest_items),
            len(batches),
            num_threads,
        )

        if num_threads == 1:
            for batch in batches:
                self._bulk_upload_batch_with_retry(
                    batch, project_name=resolved_project_name
                )
            return

        # 故意不用 `with` 块：ThreadPoolExecutor.__exit__ 总会调用
        # shutdown(wait=True)，这会重新 join 我们刚刚选择不等待的批次，
        # 并把调用者阻塞在一个陷入限流重试循环的批次后面。
        # 工作线程多于批次纯粹是浪费，而调用者提供无界的值会为每个批次
        # 派生一个线程。
        worker_count = min(
            num_threads, len(batches), constants.EXPERIMENT_ITEMS_BULK_MAX_THREADS
        )
        pool = futures.ThreadPoolExecutor(
            max_workers=worker_count, thread_name_prefix="opik_experiment_items_bulk"
        )
        submitted = [
            pool.submit(
                self._bulk_upload_batch_with_retry,
                batch,
                project_name=resolved_project_name,
            )
            for batch in batches
        ]
        try:
            for future in futures.as_completed(submitted):
                future.result()
        except BaseException:
            # 快速失败：丢弃尚未开始的批次，并在不 join 已在进行中的批次
            # 的情况下返回。
            pool.shutdown(wait=False, cancel_futures=True)
            raise
        else:
            pool.shutdown(wait=True)

    def get_items(
        self,
        max_results: Optional[int] = 10000,
        truncate: bool = False,
    ) -> List[experiment_item.ExperimentItemContent]:
        """
        检索并返回此实验的实验项目列表。

        Args:
            max_results: 要检索的最大实验项目数。如果未指定，默认为 10000。
            truncate: 是否截断后端返回的项目。默认为 False。

        Returns:
            此实验的 ExperimentItemContent 对象列表。
        """
        if max_results is None:
            max_results = 10000  # TODO: 一旦我们有了获取所有实验项目的正确方法就移除此处

        return self._experiments_client.find_experiment_items_for_dataset(
            dataset_name=self.dataset_name,
            experiment_ids=[self.id],
            truncate=truncate,
            max_results=max_results,
            project_name=self._project_name,
        )

    def log_experiment_scores(
        self,
        score_results: List["score_result.ScoreResult"],
    ) -> None:
        """向后端记录实验级别的分数。"""
        experiment_scores: List[rest_api_types.ExperimentScore] = []

        for score_result_ in score_results:
            if score_result_.scoring_failed:
                continue

            experiment_score = rest_api_types.ExperimentScore(
                name=score_result_.name,
                value=score_result_.value,
            )
            experiment_scores.append(experiment_score)

        if experiment_scores:
            self._rest_client.experiments.update_experiment(
                id=self.id,
                experiment_scores=experiment_scores,
            )
