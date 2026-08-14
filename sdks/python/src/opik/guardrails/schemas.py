import pydantic
import enum
from typing import Dict, Any, List, Optional

from opik.rest_api.types.check_public_result import CheckPublicResult


class ValidationType(str, enum.Enum):
    PII = "PII"
    TOPIC = "TOPIC"
    LLM_JUDGE = "LLM_JUDGE"
    PROMPT_INJECTION = "PROMPT_INJECTION"
    CUSTOM_CLASSIFIER = "CUSTOM_CLASSIFIER"


class ValidationResult(pydantic.BaseModel):
    validation_passed: bool
    type: ValidationType
    validation_config: Dict[str, Any]
    validation_details: Dict[str, Any]


class ValidationResponse(pydantic.BaseModel):
    validation_passed: bool
    validations: List[ValidationResult]
    # 这些由客户端注入
    guardrail_result: Optional[CheckPublicResult] = None
    # 当护栏无法评估（后端不可达、超时或本地 guard 失败）时设置。
    # 保留在响应上，使护栏 span 在失败关闭时仍然携带输出，而不仅仅是错误。
    error: Optional[str] = None
