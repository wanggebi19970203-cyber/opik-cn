"""
此文件包含 OQL 解析器和验证器。目前范围有限，仅支持不带 "or" 运算符的简单过滤器。
"""

import json
from abc import ABC, abstractmethod
from typing import Any, Dict, Optional, Tuple, List

STRING_OPERATORS = [
    "=",
    "!=",
    "contains",
    "not_contains",
    "starts_with",
    "ends_with",
    ">",
    "<",
]
# 后端 FieldType.STRING_STATE_DB 支持的运算符。对应
# FilterQueryBuilder.java 中的 ANALYTICS_DB_OPERATOR_MAP：STRING_STATE_DB 仅包含
# CONTAINS、NOT_CONTAINS、STARTS_WITH、ENDS_WITH、EQUAL 和 NOT_EQUAL 的条目
# — > 和 < 会解析为空运算符并产生 400 错误。
STRING_STATE_DB_OPERATORS = [
    "=",
    "!=",
    "contains",
    "not_contains",
    "starts_with",
    "ends_with",
]
DATE_TIME_OPERATORS = ["=", "!=", ">", ">=", "<", "<="]
NUMBER_OPERATORS = ["=", "!=", ">", ">=", "<", "<="]
FEEDBACK_SCORES_OPERATORS = [
    "=",
    "!=",
    ">",
    ">=",
    "<",
    "<=",
    "is_empty",
    "is_not_empty",
]
LIST_OPERATORS = [
    "=",
    "!=",
    "contains",
    "not_contains",
    "is_empty",
    "is_not_empty",
]
DICTIONARY_OPERATORS = [
    "=",
    "!=",
    "contains",
    "not_contains",
    "starts_with",
    "ends_with",
    ">",
    ">=",
    "<",
    "<=",
]
ENUM_OPERATORS = ["=", "!=", "in", "not_in"]


class OQLConfig(ABC):
    """OQL 配置的抽象基类。"""

    @property
    @abstractmethod
    def columns(self) -> Dict[str, str]:
        """返回支持的列及其类型。"""
        pass

    @property
    @abstractmethod
    def supported_operators(self) -> Dict[str, List[str]]:
        """返回每列支持的运算符。"""
        pass

    @property
    def dictionary_fields(self) -> List[str]:
        """返回支持通过点表示法进行嵌套键访问的字段。"""
        return ["usage", "feedback_scores", "metadata"]


class TraceOQLConfig(OQLConfig):
    """用于 trace 过滤的 OQL 配置。

    基于后端的 TraceField 枚举。
    参见：apps/opik-backend/src/main/java/com/comet/opik/api/filter/TraceField.java
    """

    @property
    def columns(self) -> Dict[str, str]:
        return {
            "id": "string",
            "name": "string",
            "start_time": "date_time",
            "end_time": "date_time",
            "input": "string",
            "output": "string",
            "input_json": "dictionary",
            "output_json": "dictionary",
            "metadata": "dictionary",
            "total_estimated_cost": "number",
            "llm_span_count": "number",
            "tags": "list",
            "usage.total_tokens": "number",
            "usage.prompt_tokens": "number",
            "usage.completion_tokens": "number",
            "feedback_scores": "feedback_scores_number",
            "span_feedback_scores": "feedback_scores_number",
            "duration": "number",
            "thread_id": "string",
            "guardrails": "string",
            "error_info": "error_container",
            "created_at": "date_time",
            "last_updated_at": "date_time",
            "annotation_queue_ids": "list",
            "experiment_id": "string",
            "environment": "enum",
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        return {
            "id": STRING_OPERATORS,
            "name": STRING_OPERATORS,
            "input": STRING_OPERATORS,
            "output": STRING_OPERATORS,
            "thread_id": STRING_OPERATORS,
            "guardrails": STRING_OPERATORS,
            "experiment_id": STRING_OPERATORS,
            "environment": ENUM_OPERATORS,
            "start_time": DATE_TIME_OPERATORS,
            "end_time": DATE_TIME_OPERATORS,
            "created_at": DATE_TIME_OPERATORS,
            "last_updated_at": DATE_TIME_OPERATORS,
            "total_estimated_cost": NUMBER_OPERATORS,
            "llm_span_count": NUMBER_OPERATORS,
            "usage.total_tokens": NUMBER_OPERATORS,
            "usage.prompt_tokens": NUMBER_OPERATORS,
            "usage.completion_tokens": NUMBER_OPERATORS,
            "duration": NUMBER_OPERATORS,
            "input_json": DICTIONARY_OPERATORS,
            "output_json": DICTIONARY_OPERATORS,
            "metadata": DICTIONARY_OPERATORS,
            "feedback_scores": FEEDBACK_SCORES_OPERATORS,
            "span_feedback_scores": FEEDBACK_SCORES_OPERATORS,
            "tags": LIST_OPERATORS,
            "annotation_queue_ids": LIST_OPERATORS,
            "error_info": ["is_empty", "is_not_empty"],
            "default": STRING_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        return [
            "metadata",
            "input_json",
            "output_json",
            "feedback_scores",
            "span_feedback_scores",
        ]


class SpanOQLConfig(OQLConfig):
    """用于 span 过滤的 OQL 配置。

    基于后端的 SpanField 枚举。
    参见：apps/opik-backend/src/main/java/com/comet/opik/api/filter/SpanField.java
    """

    @property
    def columns(self) -> Dict[str, str]:
        return {
            "id": "string",
            "name": "string",
            "start_time": "date_time",
            "end_time": "date_time",
            "input": "string",
            "output": "string",
            "input_json": "dictionary",
            "output_json": "dictionary",
            "metadata": "dictionary",
            "model": "string",
            "provider": "string",
            "total_estimated_cost": "number",
            "tags": "list",
            "usage.total_tokens": "number",
            "usage.prompt_tokens": "number",
            "usage.completion_tokens": "number",
            "feedback_scores": "feedback_scores_number",
            "duration": "number",
            "error_info": "error_container",
            "type": "enum",
            "trace_id": "string",
            "environment": "enum",
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        return {
            "id": STRING_OPERATORS,
            "name": STRING_OPERATORS,
            "input": STRING_OPERATORS,
            "output": STRING_OPERATORS,
            "model": STRING_OPERATORS,
            "provider": STRING_OPERATORS,
            "trace_id": STRING_OPERATORS,
            "type": ENUM_OPERATORS,
            "environment": ENUM_OPERATORS,
            "start_time": DATE_TIME_OPERATORS,
            "end_time": DATE_TIME_OPERATORS,
            "total_estimated_cost": NUMBER_OPERATORS,
            "usage.total_tokens": NUMBER_OPERATORS,
            "usage.prompt_tokens": NUMBER_OPERATORS,
            "usage.completion_tokens": NUMBER_OPERATORS,
            "duration": NUMBER_OPERATORS,
            "input_json": DICTIONARY_OPERATORS,
            "output_json": DICTIONARY_OPERATORS,
            "metadata": DICTIONARY_OPERATORS,
            "feedback_scores": FEEDBACK_SCORES_OPERATORS,
            "tags": LIST_OPERATORS,
            "error_info": ["is_empty", "is_not_empty"],
            "default": STRING_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        return ["metadata", "input_json", "output_json", "feedback_scores"]


class ThreadOQLConfig(OQLConfig):
    """用于线程过滤的 OQL 配置。

    基于后端的 TraceThreadField 枚举。
    参见：apps/opik-backend/src/main/java/com/comet/opik/api/filter/TraceThreadField.java
    """

    @property
    def columns(self) -> Dict[str, str]:
        return {
            "id": "string",
            "first_message": "string",
            "last_message": "string",
            "number_of_messages": "number",
            "duration": "number",
            "created_at": "date_time",
            "last_updated_at": "date_time",
            "start_time": "date_time",
            "end_time": "date_time",
            "feedback_scores": "feedback_scores_number",
            "status": "enum",
            "tags": "list",
            "annotation_queue_ids": "list",
            "environment": "enum",
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        return {
            "id": STRING_OPERATORS,
            "first_message": STRING_OPERATORS,
            "last_message": STRING_OPERATORS,
            "number_of_messages": NUMBER_OPERATORS,
            "duration": NUMBER_OPERATORS,
            "created_at": DATE_TIME_OPERATORS,
            "last_updated_at": DATE_TIME_OPERATORS,
            "start_time": DATE_TIME_OPERATORS,
            "end_time": DATE_TIME_OPERATORS,
            "feedback_scores": FEEDBACK_SCORES_OPERATORS,
            "status": ENUM_OPERATORS,
            "tags": LIST_OPERATORS,
            "annotation_queue_ids": LIST_OPERATORS,
            "environment": ENUM_OPERATORS,
            "default": STRING_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        return ["feedback_scores"]


class DatasetItemOQLConfig(OQLConfig):
    """用于数据集项目过滤的 OQL 配置。

    基于后端的 DatasetItemField 枚举和 FilterQueryBuilder。
    参见：apps/opik-backend/src/main/java/com/comet/opik/api/filter/DatasetItemField.java
    """

    @property
    def columns(self) -> Dict[str, str]:
        # 映射到 DatasetItemField 枚举值及其 FieldType
        return {
            "id": "string",  # FieldType.STRING
            "data": "map",  # FieldType.MAP - 支持嵌套键访问
            "full_data": "string",  # FieldType.STRING - toString(data)
            "source": "string",  # FieldType.STRING
            "trace_id": "string",  # FieldType.STRING
            "span_id": "string",  # FieldType.STRING
            "tags": "list",  # FieldType.LIST
            "created_at": "date_time",  # FieldType.DATE_TIME
            "last_updated_at": "date_time",  # FieldType.DATE_TIME
            "created_by": "string",  # FieldType.STRING
            "last_updated_by": "string",  # FieldType.STRING
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        return {
            "id": STRING_OPERATORS,
            "full_data": STRING_OPERATORS,
            "source": STRING_OPERATORS,
            "trace_id": STRING_OPERATORS,
            "span_id": STRING_OPERATORS,
            "created_by": STRING_OPERATORS,
            "last_updated_by": STRING_OPERATORS,
            "data": ["=", "!=", "contains", "not_contains", "starts_with", "ends_with"],
            "tags": LIST_OPERATORS,
            "created_at": DATE_TIME_OPERATORS,
            "last_updated_at": DATE_TIME_OPERATORS,
            "default": STRING_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        # 支持通过点表示法进行嵌套键访问的字段（data.key_name）
        return ["data"]


class PromptVersionOQLConfig(OQLConfig):
    """用于提示词版本过滤的 OQL 配置。"""

    @property
    def columns(self) -> Dict[str, str]:
        return {
            "id": "string",
            "commit": "string",
            "version_number": "string",
            "template": "string",
            "change_description": "string",
            "metadata": "dictionary",
            "type": "string",
            "tags": "list",
            "created_at": "date_time",
            "created_by": "string",
        }

    @property
    def supported_operators(self) -> Dict[str, List[str]]:
        # 这里所有字符串类型字段都是后端 FieldType.STRING_STATE_DB，
        # 不支持 > / < — 参见 STRING_STATE_DB_OPERATORS。
        return {
            "id": STRING_STATE_DB_OPERATORS,
            "commit": STRING_STATE_DB_OPERATORS,
            "version_number": STRING_STATE_DB_OPERATORS,
            "template": STRING_STATE_DB_OPERATORS,
            "change_description": STRING_STATE_DB_OPERATORS,
            "metadata": DICTIONARY_OPERATORS,
            "type": ["=", "!="],
            "tags": LIST_OPERATORS,
            "created_at": DATE_TIME_OPERATORS,
            "created_by": STRING_STATE_DB_OPERATORS,
            "default": STRING_STATE_DB_OPERATORS,
        }

    @property
    def dictionary_fields(self) -> List[str]:
        return ["metadata"]


OPERATORS_WITHOUT_VALUES = {"is_empty", "is_not_empty"}
ARRAY_VALUE_OPERATORS = {"in", "not_in"}

_DEFAULT_CONFIG = TraceOQLConfig()


class OpikQueryLanguage:
    """
    此方法实现了一个解析器，可用于将过滤字符串转换为后端期望的过滤器列表。

    例如，此类允许您将查询字符串 `input contains "hello"` 转换为
    后端期望的 `[{'field': 'input', 'operator': 'contains', 'value': 'hello'}]`。

    将查询字符串转换为另一种格式的常见方法是：
    1. 首先使用分词器将字符串转换为一系列标记
    2. 使用解析器将标记列表转换为抽象语法树（AST）
    3. 遍历 AST 并使用格式化器将其转换为所需格式

    由于我们当前支持的查询性质简单（不支持 and/or 运算符等），
    我们已将分词器和格式化器步骤合并为单个 parse 方法。

    parse 方法通过逐字符迭代字符串并提取/验证标记来工作。
    """

    def __init__(self, query_string: Optional[str], config: Optional[OQLConfig] = None):
        self.query_string = query_string or ""
        self._config = config or _DEFAULT_CONFIG

        self._cursor = 0
        self._filter_expressions = self._parse_expressions()
        self.parsed_filters = None
        if self._filter_expressions is not None:
            self.parsed_filters = json.dumps(self._filter_expressions)

    @classmethod
    def for_traces(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        创建一个使用 OQL 语法过滤 trace 的解析器。返回一个预配置了 TraceOQLConfig 的
        OpikQueryLanguage 实例，用于验证 trace 特定字段。空或 None 的 query_string 不产生过滤器；
        格式错误的查询在解析期间引发 ValueError。
        """
        return cls(query_string, TraceOQLConfig())

    @classmethod
    def for_spans(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        创建一个使用 OQL 语法过滤 span 的解析器。返回一个预配置了 SpanOQLConfig 的
        OpikQueryLanguage 实例，用于验证 span 特定字段。空或 None 的 query_string 不产生过滤器；
        格式错误的查询在解析期间引发 ValueError。
        """
        return cls(query_string, SpanOQLConfig())

    @classmethod
    def for_threads(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        创建一个使用 OQL 语法过滤 trace 线程的解析器。返回一个预配置了 ThreadOQLConfig 的
        OpikQueryLanguage 实例，用于验证线程特定字段。空或 None 的 query_string 不产生过滤器；
        格式错误的查询在解析期间引发 ValueError。
        """
        return cls(query_string, ThreadOQLConfig())

    @classmethod
    def for_dataset_items(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        创建一个使用 OQL 语法过滤数据集项目的解析器。在处理数据集视图或过滤数据集中的项目时使用。
        返回一个预配置了 DatasetItemOQLConfig 的 OpikQueryLanguage 实例，
        用于验证数据集特定字段（如 input、expected_output 和项目元数据）。
        空或 None 的 query_string 不产生过滤器；格式错误的查询在解析期间引发 ValueError。
        """
        return cls(query_string, DatasetItemOQLConfig())

    @classmethod
    def for_prompt_versions(cls, query_string: Optional[str]) -> "OpikQueryLanguage":
        """
        创建一个使用 OQL 语法过滤提示词版本的解析器。在搜索或过滤提示词版本历史时使用。
        返回一个预配置了 PromptVersionOQLConfig 的 OpikQueryLanguage 实例，
        用于验证提示词版本字段（如 tags、template、commit、metadata 和 created_at）。
        空或 None 的 query_string 不产生过滤器；格式错误的查询在解析期间引发 ValueError。
        """
        return cls(query_string, PromptVersionOQLConfig())

    def get_filter_expressions(self) -> Optional[List[Dict[str, Any]]]:
        return self._filter_expressions

    def _is_valid_field_char(self, char: str) -> bool:
        return char.isalnum() or char == "_"

    def _is_valid_connector_char(self, char: str) -> bool:
        return char.isalpha()

    def _skip_whitespace(self) -> None:
        while (
            self._cursor < len(self.query_string)
            and self.query_string[self._cursor].isspace()
        ):
            self._cursor += 1

    def _check_escaped_key(self) -> Tuple[bool, str]:
        if self.query_string[self._cursor] in ('"', "'"):
            is_quoted_key = True
            quote_type = self.query_string[self._cursor]
            self._cursor += 1
        else:
            is_quoted_key = False
            quote_type = ""

        return is_quoted_key, quote_type

    def _is_valid_escaped_key_char(self, quote_type: str, start: int) -> bool:
        if self.query_string[self._cursor] != quote_type:
            # 检查这不是字符串的结尾（意味着我们漏掉了关闭引号）
            if self._cursor + 2 >= len(self.query_string):
                raise ValueError(
                    "Missing closing quote for: " + self.query_string[start - 1 :]
                )

            return True

        # 检查是否是转义引号（双引号）
        if (
            self._cursor + 1 < len(self.query_string)
            and self.query_string[self._cursor + 1] == quote_type
        ):
            # 跳过第二个引号
            self._cursor += 1
            return True

        return False

    def _parse_connector(self) -> str:
        start = self._cursor
        while self._cursor < len(self.query_string) and self._is_valid_connector_char(
            self.query_string[self._cursor]
        ):
            self._cursor += 1
        connector = self.query_string[start : self._cursor]
        return connector

    def _parse_field(self) -> Dict[str, Any]:
        # 跳过空白字符
        self._skip_whitespace()

        columns = self._config.columns
        dictionary_fields = self._config.dictionary_fields

        # 解析字段名称
        start = self._cursor
        while self._cursor < len(self.query_string) and self._is_valid_field_char(
            self.query_string[self._cursor]
        ):
            self._cursor += 1
        field = self.query_string[start : self._cursor]

        # 如果存在则解析键
        if (
            self._cursor < len(self.query_string)
            and self.query_string[self._cursor] == "."
        ):
            # 跳过 "."
            self._cursor += 1

            # 检查键是否被引号包围
            is_quoted_key, quote_type = self._check_escaped_key()

            start = self._cursor
            while self._cursor < len(self.query_string) and (
                self._is_valid_field_char(self.query_string[self._cursor])
                or (
                    is_quoted_key and self._is_valid_escaped_key_char(quote_type, start)
                )
            ):
                self._cursor += 1

            key = self.query_string[start : self._cursor]

            # 如果是转义键，跳过关闭引号
            if is_quoted_key:
                key = key.replace(
                    quote_type * 2, quote_type
                )  # 将双引号替换为单引号
                self._cursor += 1

            # 对 usage.X 字段的特殊处理（trace/span 特定）
            # 这些被视为扁平字段，而不是字典访问
            if field == "usage":
                composite_field = f"usage.{key}"
                if composite_field in columns:
                    return {
                        "field": composite_field,
                        "key": "",
                        "type": columns[composite_field],
                    }
                else:
                    raise ValueError(
                        f"When querying usage, {key} is not supported, only usage.total_tokens, usage.prompt_tokens and usage.completion_tokens are supported."
                    )

            # 键仅支持字典字段
            if field not in dictionary_fields:
                raise ValueError(
                    f"Field {field}.{key} is not supported, only the fields {list(columns.keys())} are supported."
                )
            elif field in columns:
                return {"field": field, "key": key, "type": columns[field]}
            else:
                # 默认为字符串
                return {"field": field, "key": key, "type": "string"}

        elif field in columns:
            return {"field": field, "key": "", "type": columns[field]}
        else:
            # 默认为字符串
            return {"field": field, "key": "", "type": "string"}

    def _parse_operator(self, parsed_field: str) -> Dict[str, Any]:
        # 跳过空白字符
        self._skip_whitespace()

        supported_operators = self._config.supported_operators

        # 解析运算符
        if self.query_string[self._cursor] == "=":
            operator = "="
            self._cursor += 1
            if operator not in supported_operators[parsed_field]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {supported_operators[parsed_field]} are supported."
                )
            return {"operator": operator}

        elif self.query_string[self._cursor] in ["<", ">"]:
            if self.query_string[self._cursor + 1] == "=":
                operator = f"{self.query_string[self._cursor]}="
                self._cursor += 2
            else:
                operator = self.query_string[self._cursor]
                self._cursor += 1

            if operator not in supported_operators[parsed_field]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {supported_operators[parsed_field]} are supported."
                )
            return {"operator": operator}
        else:
            start = self._cursor
            while (
                self._cursor < len(self.query_string)
                and not self.query_string[self._cursor].isspace()
            ):
                self._cursor += 1

            operator = self.query_string[start : self._cursor]
            if operator not in supported_operators[parsed_field]:
                raise ValueError(
                    f"Operator {operator} is not supported for field {parsed_field}, only the operators {supported_operators[parsed_field]} are supported."
                )
            return {"operator": operator}

    def _get_number(self) -> str:
        start = self._cursor
        while (
            self._cursor < len(self.query_string)
            and self.query_string[self._cursor].isdigit()
        ):
            self._cursor += 1
        return self.query_string[start : self._cursor]

    def _parse_value(self) -> Dict[str, Any]:
        self._skip_whitespace()

        start = self._cursor
        if self.query_string[self._cursor] == '"':
            self._cursor += 1
            start = self._cursor

            # TODO: 替换为字段解析器中使用的新引号解析器
            while (
                self._cursor < len(self.query_string)
                and self.query_string[self._cursor] != '"'
            ):
                self._cursor += 1

            value = self.query_string[start : self._cursor]

            # 加 1 以跳过关闭引号并返回值
            self._cursor += 1
            return {"value": value}
        elif (
            self.query_string[self._cursor].isdigit()
            or self.query_string[self._cursor] == "-"
        ):
            value = self._get_number()
            if (
                self._cursor < len(self.query_string)
                and self.query_string[self._cursor] == "."
            ):
                self._cursor += 1
                value += "." + self._get_number()

            return {"value": value}
        else:
            raise ValueError(
                f'Invalid value {self.query_string[start : self._cursor]}, expected an string in double quotes("value") or a number'
            )

    def _parse_array_value(self) -> Dict[str, Any]:
        self._skip_whitespace()

        if (
            self._cursor >= len(self.query_string)
            or self.query_string[self._cursor] != "("
        ):
            raise ValueError(
                f"Expected array value starting with '(' for in/not_in operator, got: {self.query_string[self._cursor :]!r}"
            )
        self._cursor += 1  # 跳过 '('

        items: List[str] = []
        while True:
            self._skip_whitespace()
            if self._cursor >= len(self.query_string):
                raise ValueError("Unterminated array value, missing ')'")
            if self.query_string[self._cursor] == ")":
                if not items:
                    raise ValueError(
                        "Expected at least one item inside (...) for in/not_in operator"
                    )
                self._cursor += 1
                break
            if items:
                if self.query_string[self._cursor] != ",":
                    raise ValueError(
                        f"Expected ',' between array elements, got: {self.query_string[self._cursor :]!r}"
                    )
                self._cursor += 1
                self._skip_whitespace()

            if (
                self._cursor >= len(self.query_string)
                or self.query_string[self._cursor] != '"'
            ):
                raise ValueError(
                    f"Array elements must be quoted strings, got: {self.query_string[self._cursor :]!r}"
                )
            parsed = self._parse_value()
            items.append(parsed["value"])

        return {"value": ",".join(items)}

    def _parse_expressions(self) -> Optional[List[Dict[str, Any]]]:
        if len(self.query_string) == 0:
            return None

        expressions = []

        while True:
            # 解析字段
            parsed_field = self._parse_field()

            # 解析运算符
            parsed_operator = self._parse_operator(parsed_field["field"])

            operator_name = parsed_operator.get("operator", "")
            if operator_name in OPERATORS_WITHOUT_VALUES:
                parsed_value = {"value": ""}
            elif operator_name in ARRAY_VALUE_OPERATORS:
                parsed_value = self._parse_array_value()
            else:
                parsed_value = self._parse_value()

            expressions.append({**parsed_field, **parsed_operator, **parsed_value})

            self._skip_whitespace()

            if self._cursor < len(self.query_string):
                position = self._cursor
                connector = self._parse_connector()

                if connector.lower() == "and":
                    continue
                elif connector.lower() == "or":
                    raise ValueError(
                        "Invalid filter string, OR is not currently supported"
                    )
                else:
                    raise ValueError(
                        f"Invalid filter string, trailing characters {self.query_string[position:]}"
                    )
            else:
                break

        return expressions
