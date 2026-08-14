from typing import Set, List, TYPE_CHECKING, Dict, Any, Optional, Sequence

if TYPE_CHECKING:
    from opik.guardrails import schemas


class OpikException(Exception):
    pass


class DatasetItemUpdateOperationRequiresItemId(OpikException):
    pass


class ContextExtractorNotSet(OpikException):
    pass


class LocalRecordingAlreadyActive(OpikException):
    """当在同一连接上已存在活动录制时再次进入 record_traces_locally()
    （嵌套/重叠录制）时抛出。"""

    pass


class ConfigurationError(OpikException):
    pass


class ScoreMethodMissingArguments(OpikException):
    def __init__(
        self,
        score_name: str,
        missing_required_arguments: Sequence[str],
        available_keys: Sequence[str],
        unused_mapping_arguments: Optional[Sequence[str]] = None,
    ):
        self.score_name = score_name
        self.missing_required_arguments = missing_required_arguments
        self.available_keys = available_keys
        self.unused_mapping_arguments = unused_mapping_arguments
        super().__init__(self._get_error_message())

    def _get_error_message(self) -> str:
        message = (
            f"评分方法 {self.score_name} 缺少参数：{self.missing_required_arguments}。"
            f"这些键既不存在于数据集项中，也不存在于评估任务返回的字典中。"
            f"你可以更新数据集或评估任务以返回该键，或使用 `scoring_key_mapping` 将现有项映射到期望的参数。"
            f"在数据集项和评估任务输出中找到的可用键为：{self.available_keys}。"
        )
        if self.unused_mapping_arguments:
            message += f" `scoring_key_mapping` 中的某些键未匹配到任何内容：{self.unused_mapping_arguments}"
        return message


class MetricComputationError(OpikException):
    """当指标无法计算时抛出的异常。"""

    pass


class EvaluationError(OpikException):
    """当评估失败时抛出的异常。"""

    pass


class JSONParsingError(OpikException):
    """当无法将 LLM 响应解析为字典时抛出的异常"""

    pass


class PromptPlaceholdersDontMatchFormatArguments(OpikException):
    def __init__(self, prompt_placeholders: Set[str], format_arguments: Set[str]):
        self.prompt_placeholders = prompt_placeholders
        self.format_arguments = format_arguments
        self.symmetric_difference = prompt_placeholders.symmetric_difference(
            format_arguments
        )

    def __str__(self) -> str:
        return (
            f"`prompt.format(**kwargs)` 的参数必须与提示词占位符完全匹配。"
            f"提示词占位符：{list(self.prompt_placeholders)}。"
            f"格式化参数：{list(self.format_arguments)}。"
            f"差异：{list(self.symmetric_difference)}。"
        )


class PromptTemplateStructureMismatch(OpikException):
    """当尝试使用与现有提示词不同的模板结构创建提示词版本时抛出的异常。"""

    def __init__(
        self, prompt_name: str, existing_structure: str, attempted_structure: str
    ):
        self.prompt_name = prompt_name
        self.existing_structure = existing_structure
        self.attempted_structure = attempted_structure

    def __str__(self) -> str:
        return (
            f"名称为 '{self.prompt_name}' 的提示词已存在，且具有不可变的 "
            f"'{self.existing_structure}' 模板结构，而非 '{self.attempted_structure}'。"
        )


class ExperimentNotFound(OpikException):
    pass


class EmptyExperiment(OpikException):
    """当实验评估需要测试用例但一个都没有时抛出的异常"""

    pass


class ExperimentNotResumable(OpikException):
    """当实验无法通过 ``evaluate_resume`` 安全恢复时抛出。"""


class LocalCheckpointMissing(ExperimentNotResumable):
    """
    当实验以非确定性迭代配置（自定义采样器或显式的 ``dataset_item_ids``）
    创建，且包含已解析项 id 的本地检查点文件无法找到时抛出。

    检查点在评估时写入调用机器 opik 状态旁
    （``~/.opik/resume/<experiment_id>.json``），因此恢复默认是同机操作。
    从不同机器触发此错误的用户应显式重新提供原始的 ``dataset_item_ids``。
    """


class DatasetNotFound(OpikException):
    pass


class DashboardValidationError(OpikException):
    """当仪表盘配置违反结构或语义不变量时抛出。"""

    pass


class DatasetVersionNotFound(OpikException):
    """当数据集版本未找到时抛出的异常。"""

    pass


class GuardrailValidationFailed(OpikException):
    """当护栏校验失败时抛出的异常。"""

    def __init__(
        self,
        message: str,
        validation_results: List["schemas.ValidationResult"],
        failed_validations: List["schemas.ValidationResult"],
    ):
        self.message = message
        self.validation_results = validation_results
        self.failed_validations = failed_validations
        super().__init__(message)

    def __str__(self) -> str:
        return f"{self.message}。失败的校验：{self.failed_validations}\n"


class GuardrailValidationError(GuardrailValidationFailed):
    """当护栏无法被评估时抛出，例如护栏后端不可达、超时，或 LLM 评判器提供
    方的调用失败。

    Opik 护栏采用故障关闭策略：如果某项检查无法完成，校验将被视为失败，
    从而不会继续执行受保护的代码路径。此类继承自
    ``GuardrailValidationFailed``，因此现有的 ``except GuardrailValidationFailed``
    处理器也会对评估错误进行阻断。
    """

    def __init__(self, message: str):
        super().__init__(message, validation_results=[], failed_validations=[])

    def __str__(self) -> str:
        return self.message


class GuardrailPolicyError(OpikException):
    """当工作空间中存储的护栏策略无法转换为运行时护栏时抛出，例如它包含
    本 SDK 版本不认识的护栏类型。"""


class GuardrailTrainingError(OpikException):
    """当训练自定义护栏失败或未能及时完成时抛出。"""


class OpikCloudRequestsRateLimited(OpikException):
    """当 Opik Cloud 限制请求速率时抛出的异常。"""

    def __init__(self, headers: Dict[str, Any], retry_after: float):
        self.headers = headers
        self.retry_after = retry_after

    def __str__(self) -> str:
        return f"请求被限速。响应头：{self.headers}，{self.retry_after} 秒后重试"


class ValidationError(OpikException):
    """当校验失败时抛出的异常。"""

    def __init__(self, prefix: str, failure_reasons: List[str]):
        self._prefix = prefix
        self._failure_reasons = failure_reasons

    def __str__(self) -> str:
        return f"{self._prefix}() 中的校验失败：{self._failure_reasons}"

    def __repr__(self) -> str:
        return f"ValidationError(prefix={self._prefix}, failure_reasons={self._failure_reasons})"


class BaseLLMError(OpikException):
    """评估期间所有 LLM 错误的基类。"""

    def __init__(self, message: str) -> None:
        self.message = message

    def __str__(self) -> str:
        return f"LLM 基础设施错误：{self.message}"


class SearchTimeoutError(OpikException):
    """当搜索超时时抛出的异常。"""

    pass


class ConfigNotFound(OpikException):
    """当未找到所请求 env/版本对应的配置时抛出的异常。"""

    pass


class ConfigMismatch(OpikException):
    """当后端配置蓝本模式与期望的配置类不匹配时抛出的异常。

    这通常发生在后端蓝本缺少所请求 ``Config`` 子类中声明的一个或多个字段时。
    """

    pass


class EnvironmentAlreadyExists(OpikException):
    """当创建的环境名称在工作空间中已被占用时抛出。"""

    pass


class EnvironmentConfigurationError(OpikException):
    """当环境配置操作不被允许时抛出。"""

    pass


class PromptNotFoundError(OpikException):
    """当项目中不存在具有给定名称（或提交）的提示词时抛出。"""

    pass


class EnvironmentNotFoundError(OpikException):
    """当引用未在工作空间中注册的环境时抛出。"""

    pass


class LLMJudgeParseError(OpikException):
    """当 LLMJudge 输出未通过校验时抛出。

    携带部分的 ``ScoreResult`` 列表，以便调用方可以检查在抛出错误之前解析
    出的内容。
    """

    def __init__(self, results: list, message: str) -> None:
        self.results = results
        self.message = message

    def __str__(self) -> str:
        return self.message
