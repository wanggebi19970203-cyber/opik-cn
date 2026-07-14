import i18next from "i18next";
import { FilterOperator } from "@/types/filters";
import { COLUMN_TYPE, DropdownOption } from "@/types/shared";

export const NO_VALUE_OPERATORS: FilterOperator[] = [
  "is_empty",
  "is_not_empty",
];

export const getDefaultOperators = (): DropdownOption<FilterOperator>[] => [
  { label: i18next.t("common.filters.operators.contains"), value: "contains" },
];

export const DEFAULT_OPERATOR_MAP: Record<COLUMN_TYPE, FilterOperator> = {
  [COLUMN_TYPE.string]: "contains",
  [COLUMN_TYPE.number]: ">=",
  [COLUMN_TYPE.list]: "contains",
  [COLUMN_TYPE.time]: ">=",
  [COLUMN_TYPE.dictionary]: "=",
  [COLUMN_TYPE.numberDictionary]: "=",
  [COLUMN_TYPE.cost]: "<=",
  [COLUMN_TYPE.duration]: "<=",
  [COLUMN_TYPE.category]: "=",
  [COLUMN_TYPE.errors]: "is_not_empty",
};

export const getOperatorsMap = (): Record<
  COLUMN_TYPE,
  DropdownOption<FilterOperator>[]
> => ({
  [COLUMN_TYPE.string]: [
    { label: "=", value: "=" },
    {
      label: i18next.t("common.filters.operators.contains"),
      value: "contains",
    },
    {
      label: i18next.t("common.filters.operators.notContains"),
      value: "not_contains",
    },
    {
      label: i18next.t("common.filters.operators.startsWith"),
      value: "starts_with",
    },
    {
      label: i18next.t("common.filters.operators.endsWith"),
      value: "ends_with",
    },
  ],
  [COLUMN_TYPE.number]: [
    { label: "=", value: "=" },
    { label: ">", value: ">" },
    { label: ">=", value: ">=" },
    { label: "<", value: "<" },
    { label: "<=", value: "<=" },
  ],
  [COLUMN_TYPE.cost]: [
    { label: "=", value: "=" },
    { label: ">", value: ">" },
    { label: ">=", value: ">=" },
    { label: "<", value: "<" },
    { label: "<=", value: "<=" },
  ],
  [COLUMN_TYPE.duration]: [
    { label: "=", value: "=" },
    { label: ">", value: ">" },
    { label: ">=", value: ">=" },
    { label: "<", value: "<" },
    { label: "<=", value: "<=" },
  ],
  [COLUMN_TYPE.list]: [
    {
      label: i18next.t("common.filters.operators.contains"),
      value: "contains",
    },
  ],
  [COLUMN_TYPE.time]: [
    { label: "=", value: "=" },
    { label: ">", value: ">" },
    { label: ">=", value: ">=" },
    { label: "<", value: "<" },
    { label: "<=", value: "<=" },
  ],
  [COLUMN_TYPE.dictionary]: [
    { label: "=", value: "=" },
    {
      label: i18next.t("common.filters.operators.contains"),
      value: "contains",
    },
    {
      label: i18next.t("common.filters.operators.notContains"),
      value: "not_contains",
    },
    {
      label: i18next.t("common.filters.operators.startsWith"),
      value: "starts_with",
    },
    {
      label: i18next.t("common.filters.operators.endsWith"),
      value: "ends_with",
    },
    { label: ">", value: ">" },
    { label: "<", value: "<" },
  ],
  [COLUMN_TYPE.numberDictionary]: [
    { label: "=", value: "=" },
    { label: ">", value: ">" },
    { label: ">=", value: ">=" },
    { label: "<", value: "<" },
    { label: "<=", value: "<=" },
    { label: i18next.t("common.filters.operators.isEmpty"), value: "is_empty" },
    {
      label: i18next.t("common.filters.operators.isNotEmpty"),
      value: "is_not_empty",
    },
  ],
  [COLUMN_TYPE.category]: [{ label: "=", value: "=" }],
  [COLUMN_TYPE.errors]: [
    { label: i18next.t("common.filters.operators.isEmpty"), value: "is_empty" },
    {
      label: i18next.t("common.filters.operators.isNotEmpty"),
      value: "is_not_empty",
    },
  ],
});

export const CUSTOM_FILTER_VALIDATION_REGEXP =
  /^((\$\.)?input|\$?input\[\d+\]|(\$\.)?output|\$?output\[\d+\])(\.[^.]+)*$/;
