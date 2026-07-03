import abc
import datetime
import logging
import functools
import sys
from typing import (
    Optional,
    Any,
    List,
    Dict,
    Sequence,
    Set,
    TYPE_CHECKING,
    Iterator,
)

from opik.api_objects import rest_helpers
from opik.rest_api import client as rest_api_client
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types import (
    dataset_item_write as rest_dataset_item,
    dataset_public as rest_dataset_public,
    dataset_version_public,
    evaluator_item_write as rest_evaluator_item,
    execution_policy_write as rest_execution_policy,
)
from opik.message_processing.batching import sequence_splitter
from opik import id_helpers
import opik.exceptions as exceptions
import opik.config as config
from .. import constants
from . import dataset_item, converters, rest_operations, execution_policy

if sys.version_info >= (3, 12):
    from typing import override
else:
    from typing_extensions import override

if TYPE_CHECKING:
    import pandas as pd

LOGGER = logging.getLogger(__name__)


class DatasetExportOperations(abc.ABC):
    """
    提供数据集项目导出操作的抽象基类。
    该类定义了导出数据集项目的通用接口，由Dataset（当前状态）和DatasetVersion（特定版本）共享。
    """

    @abc.abstractmethod
    def __internal_api__stream_items_as_dataclasses__(
        self,
        nb_samples: Optional[int] = None,
        batch_size: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        filter_string: Optional[str] = None,
    ) -> Iterator[dataset_item.DatasetItem]:
        """
        以DatasetItem对象的形式流式传输数据集项目。

        Args:
            nb_samples: 要检索的最大项目数。
            batch_size: 每批次要获取的最大项目数。
            dataset_item_ids: 可选的特定项目ID列表，用于检索。
            filter_string: 可选的OQL过滤字符串，用于过滤数据集项目。

        Yields:
            每次生成一个DatasetItem对象。
        """
        raise NotImplementedError

    def to_pandas(self) -> "pd.DataFrame":
        """
        将数据集项目转换为pandas DataFrame。

        需要安装 `pandas` 库。

        Returns:
            包含所有项目的pandas DataFrame。
        """
        dataset_items = list(self.__internal_api__stream_items_as_dataclasses__())
        return converters.to_pandas(dataset_items, keys_mapping={})

    def to_json(self) -> str:
        """
        将数据集项目转换为JSON字符串。

        Returns:
            所有项目的JSON字符串表示。
        """
        dataset_items = list(self.__internal_api__stream_items_as_dataclasses__())
        return converters.to_json(dataset_items, keys_mapping={})

    def get_items(
        self,
        nb_samples: Optional[int] = None,
        filter_string: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """
        以字典列表的形式检索数据集项目。

        Args:
            nb_samples: 要检索的最大项目数。如果未设置，则返回所有项目。
            filter_string: 可选的OQL过滤字符串，用于过滤数据集项目。
                支持按标签、数据字段、元数据等进行过滤。

                支持的列包括：
                - `id`, `source`, `trace_id`, `span_id`: 字符串字段
                - `data`: 字典字段（使用点表示法，例如 "data.category"）
                - `tags`: 列表字段（使用 "contains" 运算符）
                - `created_at`, `last_updated_at`: 日期时间字段（ISO 8601格式）
                - `created_by`, `last_updated_by`: 字符串字段

                示例：
                - `tags contains "failed"` - 包含 'failed' 标签的项目
                - `data.category = "test"` - 具有特定数据字段值的项目
                - `created_at >= "2024-01-01T00:00:00Z"` - 在指定日期之后创建的项目

        Returns:
            表示数据集项目的字典列表。
        """
        dataset_items_as_dicts = [
            {"id": item.id, **item.get_content()}
            for item in self.__internal_api__stream_items_as_dataclasses__(
                nb_samples=nb_samples, filter_string=filter_string
            )
        ]
        return dataset_items_as_dicts

    @abc.abstractmethod
    def get_version_info(
        self,
    ) -> Optional[dataset_version_public.DatasetVersionPublic]:
        """
        获取用于实验关联的版本信息。

        Returns:
            包含版本元数据（id, version_name等）的DatasetVersionPublic。
            对于Dataset，返回当前/最新版本的信息，如果不存在版本则返回None。
            对于DatasetVersion，返回此特定版本的信息。
        """
        raise NotImplementedError


class DatasetVersion(DatasetExportOperations):
    """
    特定数据集版本的只读视图。

    该类提供对特定时间点数据集项目的访问。
    它支持读取版本元数据和检索项目，但不允许对数据集进行修改。

    不应直接创建此对象。请使用 :meth:`Dataset.get_dataset_version` 获取实例。
    """

    def __init__(
        self,
        dataset_name: str,
        dataset_id: str,
        rest_client: rest_api_client.OpikApi,
        version_info: dataset_version_public.DatasetVersionPublic,
        project_name: Optional[str],
        client: Optional[Any] = None,
    ) -> None:
        self._dataset_name = dataset_name
        self._dataset_id = dataset_id
        self._rest_client = rest_client
        self._version_info = version_info
        self._project_name = project_name
        self.client = client

    @property
    def dataset_name(self) -> str:
        """此版本所属数据集的名称。"""
        return self._dataset_name

    @property
    def project_name(self) -> Optional[str]:
        """此数据集所属项目的名称。"""
        return self._project_name

    @property
    def name(self) -> str:
        """此版本所属数据集的名称（dataset_name的别名）。"""
        return self._dataset_name

    @property
    def dataset_id(self) -> str:
        """此版本所属数据集的唯一标识符。"""
        return self._dataset_id

    @property
    def id(self) -> str:
        """此版本所属数据集的唯一标识符（dataset_id的别名）。"""
        return self._dataset_id

    @property
    def version_id(self) -> Optional[str]:
        """此特定版本的唯一标识符。"""
        return self._version_info.id

    @property
    def dataset_items_count(self) -> Optional[int]:
        """此版本中的项目总数（items_total的别名）。"""
        return self._version_info.items_total

    @property
    def version_hash(self) -> Optional[str]:
        """此版本的唯一哈希标识符。"""
        return self._version_info.version_hash

    @property
    def version_name(self) -> Optional[str]:
        """顺序版本名称（例如 'v1', 'v2'）。"""
        return self._version_info.version_name

    @property
    def tags(self) -> Optional[List[str]]:
        """与此版本关联的标签。"""
        return self._version_info.tags

    @property
    def is_latest(self) -> Optional[bool]:
        """这是否是数据集的最新版本。"""
        return self._version_info.is_latest

    @property
    def items_total(self) -> Optional[int]:
        """此版本中的项目总数。"""
        return self._version_info.items_total

    @property
    def items_added(self) -> Optional[int]:
        """自上一版本以来添加的项目数。"""
        return self._version_info.items_added

    @property
    def items_modified(self) -> Optional[int]:
        """自上一版本以来修改的项目数。"""
        return self._version_info.items_modified

    @property
    def items_deleted(self) -> Optional[int]:
        """自上一版本以来删除的项目数。"""
        return self._version_info.items_deleted

    @property
    def change_description(self) -> Optional[str]:
        """此版本更改的描述。"""
        return self._version_info.change_description

    @property
    def created_at(self) -> Optional[datetime.datetime]:
        """创建此版本的时间戳。"""
        return self._version_info.created_at

    @property
    def created_by(self) -> Optional[str]:
        """创建此版本的用户。"""
        return self._version_info.created_by

    @override
    def __internal_api__stream_items_as_dataclasses__(
        self,
        nb_samples: Optional[int] = None,
        batch_size: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        filter_string: Optional[str] = None,
    ) -> Iterator[dataset_item.DatasetItem]:
        return rest_operations.stream_dataset_items(
            rest_client=self._rest_client,
            dataset_name=self._dataset_name,
            project_name=self._project_name,
            nb_samples=nb_samples,
            batch_size=batch_size,
            dataset_item_ids=dataset_item_ids,
            filter_string=filter_string,
            dataset_version=self._version_info.version_hash,
        )

    @override
    def get_version_info(
        self,
    ) -> Optional[dataset_version_public.DatasetVersionPublic]:
        """
        获取此特定数据集版本的版本信息。

        Returns:
            包含此版本元数据的DatasetVersionPublic。
        """
        return self._version_info

    def get_evaluators(
        self,
        evaluator_model: Optional[str] = None,
    ) -> List[Any]:
        """
        获取此数据集版本的套件级评估器。

        DatasetVersion不支持套件级评估器，因此始终返回空列表。

        Returns:
            空列表。
        """
        return []

    def get_execution_policy(self) -> execution_policy.ExecutionPolicy:
        """
        获取此数据集版本的执行策略。

        DatasetVersion不支持套件级执行策略，因此返回默认执行策略。

        Returns:
            默认执行策略。
        """
        return execution_policy.DEFAULT_EXECUTION_POLICY.copy()


class Dataset(DatasetExportOperations):
    def __init__(
        self,
        name: str,
        description: Optional[str],
        project_name: Optional[str],
        rest_client: rest_api_client.OpikApi,
        dataset_items_count: Optional[int] = None,
        client: Optional[Any] = None,
    ) -> None:
        """
        Dataset对象。不应直接创建此对象，请使用 :meth:`opik.Opik.create_dataset` 或 :meth:`opik.Opik.get_dataset`。
        """
        self._name = name
        self._description = description
        self._rest_client = rest_client
        self._dataset_items_count = dataset_items_count
        self._project_name = project_name
        self.client = client

        self._id_to_hash: Dict[str, str] = {}
        self._hashes: Set[str] = set()
        # 当本地哈希缓存与后端一致时为True。
        # 直接构建的Dataset（create_dataset, test-suite helpers, unit tests）
        # 开始时已同步——后端没有本地未见过的内容。后端获取工厂（`from_public`、
        # `rest_operations.get_datasets`）将其设为False，以便去重在第一次`insert()`
        # 时进行一次性同步，而不是在列表时支付N+1同步。
        self._hashes_synced: bool = True

    @classmethod
    def from_public(
        cls,
        dataset_fern: rest_dataset_public.DatasetPublic,
        project_name: str,
        rest_client: rest_api_client.OpikApi,
        client: Optional[Any] = None,
    ) -> "Dataset":
        """从后端响应构建Dataset，解析实际项目。

        即使调用者的project_name与数据集的实际项目不匹配，后端也可能通过工作区范围的回退找到数据集。
        此方法使用响应中的project_id来解析真实的项目名称，以便下游调用指向正确的项目。
        """
        actual_project_name: Optional[str] = None
        if dataset_fern.project_id is not None:
            actual_project_name = rest_client.projects.get_project_by_id(
                dataset_fern.project_id
            ).name

        dataset_ = cls(
            name=dataset_fern.name,
            description=dataset_fern.description,
            project_name=actual_project_name or project_name,
            rest_client=rest_client,
            dataset_items_count=dataset_fern.dataset_items_count,
            client=client,
        )
        # 后端可能已持有我们未见过的项目；在首次插入时延迟同步，
        # 以便内容哈希去重仍然有效，而无需立即支付同步开销。
        dataset_.__internal_api__hashes_synced__ = False
        return dataset_

    @functools.cached_property
    def id(self) -> str:
        """数据集的ID"""
        return self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=self._name, project_name=self._project_name
        ).id

    @property
    def name(self) -> str:
        """数据集的名称。"""
        return self._name

    @property
    def project_name(self) -> Optional[str]:
        """此数据集所属项目的名称。"""
        return self._project_name

    @property
    def description(self) -> Optional[str]:
        """数据集的描述。"""
        return self._description

    @property
    def dataset_items_count(self) -> Optional[int]:
        """
        数据集中的项目总数。

        如果计数未在本地缓存，则将从后端获取。
        """
        if self._dataset_items_count is None:
            dataset_info = self._rest_client.datasets.get_dataset_by_id(id=self.id)
            self._dataset_items_count = dataset_info.dataset_items_count

        return self._dataset_items_count

    def get_current_version_name(self) -> Optional[str]:
        """
        获取数据集的当前版本名称。

        版本名称从后端获取，反映任何修改操作（插入、更新、删除）后最新的已提交版本。

        Returns:
            当前版本名称（例如 'v1', 'v2'），如果不存在版本则返回None。
        """
        version_info = self.get_version_info()
        return version_info.version_name if version_info else None

    @override
    def get_version_info(
        self,
    ) -> Optional[dataset_version_public.DatasetVersionPublic]:
        """
        获取当前（最新）数据集版本的版本信息。

        Returns:
            包含当前版本元数据的DatasetVersionPublic，
            如果尚不存在版本则返回None。
        """
        versions_response = None
        try:
            versions_response = self._rest_client.datasets.list_dataset_versions(
                id=self.id,
                page=1,
                size=1,
            )
        except ApiError as e:
            if e.status_code == 403:
                LOGGER.debug(
                    "Versioning is not enabled for datasets get version info returning None"
                )
            else:
                raise
        if not versions_response or not versions_response.content:
            return None
        return versions_response.content[0]

    def get_evaluators(
        self,
        evaluator_model: Optional[str] = None,
    ) -> List[Any]:
        """
        从当前数据集版本获取套件级评估器。

        将来自后端的EvaluatorItemPublic对象转换为LLMJudge实例。

        Args:
            evaluator_model: 可选的模型名称，用于LLMJudge评估器。

        Returns:
            从版本中提取的LLMJudge实例列表。
        """
        from opik.evaluation.suite_evaluators import llm_judge
        from opik.evaluation.suite_evaluators.llm_judge import (
            config as llm_judge_config,
        )

        version_info = self.get_version_info()
        if version_info is None or not version_info.evaluators:
            return []

        evaluators: List[Any] = []
        for evaluator_item in version_info.evaluators:
            try:
                if evaluator_item.type == "llm_judge":
                    cfg = llm_judge_config.LLMJudgeConfig(**evaluator_item.config)
                    evaluator = llm_judge.LLMJudge.from_config(
                        cfg, init_kwargs={"model": evaluator_model}
                    )
                    evaluators.append(evaluator)
                else:
                    LOGGER.warning(
                        "Unsupported evaluator type in version: %s. Only 'llm_judge' is supported.",
                        evaluator_item.type,
                    )
            except Exception:
                LOGGER.error(
                    "Failed to instantiate evaluator from version config: %s",
                    evaluator_item.config,
                    exc_info=True,
                )
                raise

        return evaluators

    def get_execution_policy(
        self,
    ) -> execution_policy.ExecutionPolicy:
        """
        从当前数据集版本获取套件级执行策略。

        Returns:
            包含runs_per_item和pass_threshold的ExecutionPolicy字典。
        """
        version_info = self.get_version_info()
        if version_info is not None and version_info.execution_policy is not None:
            ep = version_info.execution_policy
            return {
                "runs_per_item": ep.runs_per_item
                if ep.runs_per_item is not None
                else 1,
                "pass_threshold": ep.pass_threshold
                if ep.pass_threshold is not None
                else 1,
            }

        return execution_policy.DEFAULT_EXECUTION_POLICY.copy()

    def get_tags(self) -> List[str]:
        """
        获取此数据集的标签。

        Returns:
            标签字符串列表。
        """
        dataset_fern = self._rest_client.datasets.get_dataset_by_identifier(
            dataset_name=self._name, project_name=self._project_name
        )
        return dataset_fern.tags or []

    def _convert_to_rest_item(
        self, item: dataset_item.DatasetItem
    ) -> rest_dataset_item.DatasetItemWrite:
        """将DatasetItem转换为REST API格式。

        Args:
            item: 要转换的DatasetItem。

        Returns:
            准备好用于REST API的DatasetItemWrite对象。
        """
        evaluators = None
        if item.evaluators:
            evaluators = [
                rest_evaluator_item.EvaluatorItemWrite(
                    name=e.name,
                    type=e.type,  # type: ignore
                    config=e.config,
                )
                for e in item.evaluators
            ]

        execution_policy = None
        if item.execution_policy:
            execution_policy = rest_execution_policy.ExecutionPolicyWrite(
                runs_per_item=item.execution_policy.runs_per_item,
                pass_threshold=item.execution_policy.pass_threshold,
            )

        return rest_dataset_item.DatasetItemWrite(
            id=item.id,  # type: ignore
            trace_id=item.trace_id,  # type: ignore
            span_id=item.span_id,  # type: ignore
            source=item.source,  # type: ignore
            data=item.get_content(),
            description=item.description,
            evaluators=evaluators,
            execution_policy=execution_policy,
        )

    def _insert_batch_with_retry(
        self,
        batch: List[rest_dataset_item.DatasetItemWrite],
        batch_group_id: str,
    ) -> None:
        """插入一批数据集项目，在遇到速率限制错误时自动重试。

        Args:
            batch: 要插入的数据集项目列表。
            batch_group_id: UUIDv7标识符，将来自单个用户操作的所有批次组合在一起。
                作为一次插入/更新调用的一部分发送的所有批次共享相同的batch_group_id。
        """
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: self._rest_client.datasets.create_or_update_dataset_items(
                dataset_name=self._name,
                items=batch,
                batch_group_id=batch_group_id,
                project_name=self._project_name,
            )
        )
        LOGGER.debug("Successfully sent dataset items batch of size %d", len(batch))

    def __internal_api__insert_items_as_dataclasses__(
        self, items: List[dataset_item.DatasetItem]
    ) -> None:
        # 第一次插入从后端获取的数据集时（list或get-by-name工厂）
        # 进行延迟同步，以便内容哈希去重仍然有效，而无需在列表时支付N+1同步。
        if not self._hashes_synced:
            self.__internal_api__sync_hashes__()

        # 如果已存在重复项则删除
        deduplicated_items: List[dataset_item.DatasetItem] = []
        for item in items:
            item_hash = item.content_hash()

            if item_hash in self._hashes:
                LOGGER.debug(
                    "Duplicate item found with hash: %s - ignored the event",
                    item_hash,
                )
                continue

            deduplicated_items.append(item)
            self._hashes.add(item_hash)
            self._id_to_hash[item.id] = item_hash

        rest_items = [self._convert_to_rest_item(item) for item in deduplicated_items]

        batches = sequence_splitter.split_into_batches(
            rest_items,
            max_payload_size_MB=config.MAX_BATCH_SIZE_MB,
            max_length=constants.DATASET_ITEMS_MAX_BATCH_SIZE,
        )

        batch_group_id = id_helpers.generate_id()

        for batch in batches:
            LOGGER.debug("Sending dataset items batch of size %d", len(batch))
            self._insert_batch_with_retry(batch, batch_group_id=batch_group_id)

        # 使缓存的计数失效，以便下次访问时从后端获取
        # 使缓存的计数失效，以便下次访问时从后端获取
        self._dataset_items_count = None

    def delete(self, items_ids: List[str]) -> None:
        """
        从数据集中删除项目。将创建一个新的数据集版本。

        Args:
            items_ids: 要删除的项目ID列表。
        """
        batches = sequence_splitter.split_into_batches(
            items_ids, max_length=constants.DATASET_ITEMS_MAX_BATCH_SIZE
        )

        batch_group_id = id_helpers.generate_id()

        for batch in batches:
            LOGGER.debug("Deleting dataset items batch: %s", batch)
            self._delete_batch_with_retry(batch, batch_group_id=batch_group_id)

            for item_id in batch:
                if item_id in self._id_to_hash:
                    hash = self._id_to_hash[item_id]
                    self._hashes.discard(hash)
                    del self._id_to_hash[item_id]

        # 使缓存的计数失效，以便下次访问时从后端获取
        self._dataset_items_count = None

    def clear(self) -> None:
        """
        删除给定数据集中的所有项目。将创建一个新的数据集版本。
        """
        item_ids = [
            item.id
            for item in self.__internal_api__stream_items_as_dataclasses__()
            if item.id is not None
        ]

        self.delete(item_ids)

    @override
    def __internal_api__stream_items_as_dataclasses__(
        self,
        nb_samples: Optional[int] = None,
        batch_size: Optional[int] = None,
        dataset_item_ids: Optional[List[str]] = None,
        filter_string: Optional[str] = None,
    ) -> Iterator[dataset_item.DatasetItem]:
        """
        以生成器的形式流式传输数据集项目，而不是一次性加载所有项目。

        此方法每次生成一个数据集项目，使评估能够在整个数据集下载之前开始处理项目。
        这对于具有大型负载（图像、视频、音频）的大型数据集特别有用。

        Args:
            nb_samples: 要检索的最大项目数。如果为None，则流式传输所有项目。
            batch_size: 每批次从后端获取的最大项目数。
                        如果为None，则使用constants.DATASET_STREAM_BATCH_SIZE的默认值。
            dataset_item_ids: 可选的特定项目ID列表，用于检索。如果提供，
                            只有具有匹配ID的项目将被生成。
            filter_string: 可选的OQL过滤字符串，用于过滤数据集项目。

        Yields:
            每次生成一个DatasetItem对象
        """
        return rest_operations.stream_dataset_items(
            rest_client=self._rest_client,
            dataset_name=self._name,
            project_name=self._project_name,
            nb_samples=nb_samples,
            batch_size=batch_size,
            dataset_item_ids=dataset_item_ids,
            filter_string=filter_string,
            dataset_version=None,
        )

    def insert_from_json(
        self,
        json_array: str,
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
        Args:
            json_array: 格式为 "[{...}, {...}, {...}]" 的json字符串，其中每个字典
                将被转换为数据集项目。
            keys_mapping: 将json键映射到项目字段名称的字典。
                示例: {'Expected output': 'expected_output'}
            ignore_keys: 如果您的json字典包含DatasetItem构造不需要的键，
                请将它们作为ignore_keys参数传递。
        """
        keys_mapping = {} if keys_mapping is None else keys_mapping
        ignore_keys = [] if ignore_keys is None else ignore_keys

        new_items = converters.from_json(
            json_array, keys_mapping=keys_mapping, ignore_keys=ignore_keys
        )

        self.insert(new_items)

    def read_jsonl_from_file(
        self,
        file_path: str,
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
        从文件读取JSONL并将其插入数据集。

        Args:
            file_path: JSONL文件的路径。
            keys_mapping: 将json键映射到项目字段名称的字典。
                示例: {'Expected output': 'expected_output'}
            ignore_keys: 如果您的json字典包含DatasetItem构造不需要的键，
                请将它们作为ignore_keys参数传递。
        """
        keys_mapping = {} if keys_mapping is None else keys_mapping
        ignore_keys = [] if ignore_keys is None else ignore_keys
        new_items = converters.from_jsonl_file(file_path, keys_mapping, ignore_keys)
        self.insert(new_items)

    def insert_from_pandas(
        self,
        dataframe: "pd.DataFrame",
        keys_mapping: Optional[Dict[str, str]] = None,
        ignore_keys: Optional[List[str]] = None,
    ) -> None:
        """
        需要安装 `pandas` 库。

        Args:
            dataframe: pandas dataframe。
            keys_mapping: 将dataframe列名映射到数据集项目字段名称的字典。
                示例: {'Expected output': 'expected_output'}
            ignore_keys: 如果您的dataframe包含DatasetItem构造不需要的列，
                请将它们作为ignore_keys参数传递。
        """
        keys_mapping = {} if keys_mapping is None else keys_mapping
        ignore_keys = [] if ignore_keys is None else ignore_keys

        new_items = converters.from_pandas(dataframe, keys_mapping, ignore_keys)

        self.insert(new_items)

    def get_version_view(self, version_name: str) -> DatasetVersion:
        """
        获取特定数据集版本的只读视图。

        返回的DatasetVersion对象允许读取版本元数据并通过 :meth:`DatasetVersion.get_items` 检索项目，
        但不支持修改。

        Args:
            version_name: 版本名称（例如 'v1', 'v2'）。

        Returns:
            用于访问指定版本的只读DatasetVersion对象。

        Raises:
            opik.exceptions.DatasetVersionNotFound: 如果指定的版本不存在。

        Example:
            >>> dataset = client.get_dataset("my_dataset")
            >>> version = dataset.get_version_view("v1")
            >>> items = version.get_items()
        """
        version_info = rest_operations.find_version_by_name(
            rest_client=self._rest_client,
            dataset_id=self.id,
            version_name=version_name,
        )

        if version_info is None:
            raise exceptions.DatasetVersionNotFound(
                f"Dataset version '{version_name}' not found in dataset '{self._name}'"
            )

        return DatasetVersion(
            dataset_name=self._name,
            dataset_id=self.id,
            rest_client=self._rest_client,
            version_info=version_info,
            project_name=self._project_name,
            client=self.client,
        )
