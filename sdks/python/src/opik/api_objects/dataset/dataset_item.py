from typing import Optional, Dict, Any, List
import pydantic
import json
import hashlib
from .. import constants, helpers


class EvaluatorItem(pydantic.BaseModel):
    """
    数据集项的评估器配置。
    """

    model_config = pydantic.ConfigDict(extra="allow", strict=False)

    name: str
    """评估器的名称。"""

    type: str
    """评估器类型（如 'llm_judge'、'code_metric'）。"""

    config: Dict[str, Any]
    """评估器配置。"""


class ExecutionPolicyItem(pydantic.BaseModel):
    """
    数据集项的执行策略。
    """

    model_config = pydantic.ConfigDict(extra="allow", strict=False)

    runs_per_item: Optional[int] = None
    """该数据集项的任务执行次数。"""

    pass_threshold: Optional[int] = None
    """该数据集项通过所需的最少通过次数。"""


class DatasetItem(pydantic.BaseModel):
    """
    表示数据集中一个数据项的 DatasetItem 对象。
    格式是灵活的。
    """

    model_config = pydantic.ConfigDict(extra="allow", strict=False)

    id: pydantic.SkipValidation[str] = pydantic.Field(
        default_factory=helpers.generate_id
    )
    """此数据集项的唯一标识符。"""

    trace_id: Optional[str] = None
    """与此数据集项关联的 trace ID。"""

    span_id: Optional[str] = None
    """与此数据集项关联的 span ID。"""

    source: str = constants.DATASET_SOURCE_SDK
    """数据集项的来源。默认为 DATASET_SOURCE_SDK。"""

    description: Optional[str] = None
    """数据集项的可选描述。"""

    evaluators: Optional[List[EvaluatorItem]] = None
    """为此数据集项配置的评估器列表。"""

    execution_policy: Optional[ExecutionPolicyItem] = None
    """此数据集项的执行策略。"""

    def get_content(
        self,
        include_id: bool = False,
    ) -> Dict[str, Any]:
        """
        获取数据集项的数据内容（额外字段）。

        注意：evaluators 和 execution_policy 不包含在数据内容中。

        Args:
            include_id: 是否在内容中包含数据项 ID。

        Returns:
            包含数据项额外字段的字典。
        """
        content = {**self.model_extra}
        if include_id:
            content["id"] = self.id

        return content

    def content_hash(self) -> str:
        content = self.get_content()

        if self.description is not None:
            content["description"] = self.description

        if self.evaluators is not None:
            content["evaluators"] = [e.model_dump() for e in self.evaluators]

        if self.execution_policy is not None:
            content["execution_policy"] = self.execution_policy.model_dump()

        json_string = json.dumps(content, sort_keys=True)
        hash_object = hashlib.sha256(json_string.encode())

        return hash_object.hexdigest()
