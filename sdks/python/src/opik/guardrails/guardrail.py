import logging
from typing import (
    List,
    Optional,
    Sequence,
)

import httpx

import opik.exceptions as exceptions
import opik.config as config
from opik.rest_api import core as rest_api_core
from opik.api_objects import opik_client
from opik.message_processing.messages import (
    GuardrailBatchItemMessage,
    GuardrailBatchMessage,
)
from opik.opik_context import get_current_span_data, get_current_trace_data

from . import guards, rest_api_client, schemas, stored_policies, tracing


LOGGER = logging.getLogger(__name__)

GUARDRAIL_DECORATOR = tracing.GuardrailsTrackDecorator()


class Guardrail:
    """
    Opik Guardrails API 的客户端。

    该类提供了一种依据一组 guardrail 校验文本的方式。
    """

    def __init__(
        self,
        guards: List[guards.Guard],
        guardrail_timeout: Optional[int] = None,
    ) -> None:
        """
        初始化一个 Guardrail 客户端。

        Args:
            guards: 用于校验文本的 Guard 对象列表

        Example:

        ```python
        from opik.guardrails import Guardrail, PII, Topic
        from opik import exceptions
        guard = Guardrail(
            guards=[
                Topic(restricted_topics=["finance"], threshold=0.8),
                PII(blocked_entities=["CREDIT_CARD", "PERSON"], threshold=0.4),
            ]
        )

        result = guard.validate("How can I start with evaluation in Opik platform?")
        # Guardrail passes

        try:
            result = guard.validate("Where should I invest my money?")
        except exceptions.GuardrailValidationFailed as e:
            print("Guardrail failed:", e)

        try:
            result = guard.validate("John Doe, here is my card number 4111111111111111 how can I use it in Opik platform?.")
        except exceptions.GuardrailValidationFailed as e:
            print("Guardrail failed:", e)
        ```

        """
        self.guards = guards
        self._client = opik_client.get_global_client()

        self.config_ = config.get_from_user_inputs(
            guardrail_timeout=guardrail_timeout,
        )

        self._initialize_api_client(
            host_url=self._client.config.guardrails_backend_host,
        )

    @classmethod
    def from_stored_policies(
        cls,
        names: Optional[Sequence[str]] = None,
        guardrail_timeout: Optional[int] = None,
    ) -> "Guardrail":
        """
        基于存储在 Opik 工作区中的 guardrail 策略构建一个 Guardrail。

        策略是存储在 Opik 中的一组具名 guard。按名称引用策略，可使应用程序运行的检查在无需修改代码
        的情况下保持可配置。工作区无条件应用的策略始终会被包含，无论其是否在此处被显式指定。

        所有检索到的策略中的 guard 会被合并为一个扁平集合一起检查：不保留每个 guard 来自哪个策略。

        Args:
            names: 用于构建 guardrail 的策略名称。每个名称都必须存在，
                不存在的名称会报错，而不是静默地不运行该项检查。
            guardrail_timeout: guardrails 后端调用的超时秒数。

        Returns:
            Guardrail: 运行所检索策略中 guard 的 Guardrail。

        Raises:
            opik.rest_api.core.ApiError: 若无法检索策略，例如
                当 ``names`` 中的某个名称在工作区中不存在时。
            opik.exceptions.GuardrailPolicyError: 若某个策略包含此 SDK
                版本无法运行的 guard 类型。

        Example:

        ```python
        from opik.guardrails import Guardrail
        from opik import exceptions

        guardrail = Guardrail.from_stored_policies(names=["no_contact_information"])

        try:
            guardrail.validate("Call me at 555-0123")
        except exceptions.GuardrailValidationFailed as e:
            print("Guardrail failed:", e)
        ```
        """
        client = opik_client.get_global_client()
        policies = stored_policies.retrieve_policies(client=client, names=names)
        policy_guards = stored_policies.build_guards(policies)

        if len(policy_guards) == 0:
            LOGGER.warning(
                "在存储的 guardrail 策略中未找到任何 guard，因此此 guardrail "
                "将放行所有输入。请求的策略名称：%s",
                list(names) if names is not None else [],
            )

        LOGGER.debug(
            "已基于存储的策略构建 guardrail：%s",
            [policy.name for policy in policies],
        )

        return cls(guards=policy_guards, guardrail_timeout=guardrail_timeout)

    def _initialize_api_client(self, host_url: str) -> None:
        self._api_client = rest_api_client.GuardrailsApiClient(
            httpx_client=rest_api_client.build_httpx_client(
                config=self._client.config,
                timeout_seconds=self.config_.guardrail_timeout,
            ),
            host_url=host_url,
        )

    def validate(self, text: str) -> schemas.ValidationResponse:
        """
        依据所有已配置的 guardrail 校验文本。

        Args:
            text: 要校验的文本

        Returns:
            ValidationResponse: 包含校验结果的 API 响应

        Raises:
            opik.exceptions.GuardrailValidationFailed: 若校验失败
            opik.exceptions.GuardrailValidationError: 若某条 guardrail 无法
                被评估（guardrails 后端不可达、超时，或 LLM 判定器
                提供商故障）。guardrail 采用故障关闭（fail closed）策略，因此这会阻断
                受保护的代码路径。
        """
        result = self._validate(generation=text)

        if result.error is not None:
            raise exceptions.GuardrailValidationError(result.error)

        return self._parse_result(result)

    @GUARDRAIL_DECORATOR.track
    def _validate(self, generation: str) -> schemas.ValidationResponse:
        result = schemas.ValidationResponse(validation_passed=True, validations=[])

        # 故障关闭（fail-closed）的错误被记录到结果上而不是直接抛出，以便
        # 被装饰的 span 仍能记录 guardrail 的输出；validate() 会在
        # span 完成后重新抛出这些错误。
        try:
            remote_validations = []
            for guard in self.guards:
                remote_validations.extend(guard.get_validation_configs())

            if remote_validations:
                result = self._api_client.validate(generation, remote_validations)

            for guard in self.guards:
                if guard.local:
                    result.validations.extend(
                        guard.validate_local(generation, self._client)
                    )
        except (httpx.HTTPError, rest_api_core.ApiError) as e:
            result.error = f"Guardrail 无法被评估，采用故障关闭（fail closed）：{e}"
        except exceptions.GuardrailValidationError as e:
            result.error = str(e)

        if result.error is not None:
            result.validation_passed = False
        else:
            result.validation_passed = all(
                validation.validation_passed for validation in result.validations
            )

        if not result.validation_passed:
            result.guardrail_result = "failed"
        else:
            result.guardrail_result = "passed"

        batch = []

        # 使 mypy 满意，确保当前 span 和 trace 存在
        current_span = get_current_span_data()
        current_trace = get_current_trace_data()
        assert current_span is not None
        assert current_trace is not None

        for validation in result.validations:
            guardrail_batch_item_message = GuardrailBatchItemMessage(
                # guardrail 结果必须与其所关联的 trace/span 位于同一项目中；
                # 否则按项目范围的读取（例如 trace 列表）无法匹配到它们。
                # 当 trace 没有显式项目时（罕见情况），回退到客户端项目。
                project_name=current_trace.project_name or self._client._project_name,
                entity_id=current_trace.id,
                secondary_id=current_span.id,
                name=validation.type,
                result="passed" if validation.validation_passed else "failed",
                config=validation.validation_config,
                details=validation.validation_details,
            )
            batch.append(guardrail_batch_item_message)

        # 不包含任何 guard 的 guardrail 不会产生结果，而后端会拒绝空的
        # guardrail 批次（422）。强行发送只会记录一条错误，并上报从未发生过的数据丢失。
        if batch:
            self._client._streamer.put(GuardrailBatchMessage(batch=batch))

        return result

    def _parse_result(
        self, result: schemas.ValidationResponse
    ) -> schemas.ValidationResponse:
        if not result.validation_passed:
            failed_validations = []
            for validation in result.validations:
                if not validation.validation_passed:
                    failed_validations.append(validation)

            raise exceptions.GuardrailValidationFailed(
                "Guardrail 校验失败",
                validation_results=result,
                failed_validations=failed_validations,
            )

        return result
