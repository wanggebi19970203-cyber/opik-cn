import { TFunction } from "i18next";
import { DropdownOption } from "@/types/shared";

const WINDOW_VALUES = [
  "300",
  "900",
  "1800",
  "3600",
  "21600",
  "43200",
  "86400",
  "604800",
  "1296000",
  "2592000",
] as const;

export const getWindowOptions = (
  t: TFunction<"pages/alerts", undefined>,
): DropdownOption<string>[] =>
  WINDOW_VALUES.map((value) => ({
    label: t(`alerts.windowOptions.${value}`),
    value,
  }));

export const getWindowLabelByValue = (
  t: TFunction<"pages/alerts", undefined>,
): Record<string, string> =>
  Object.fromEntries(
    WINDOW_VALUES.map((value) => [value, t(`alerts.windowOptions.${value}`)]),
  );

export const OPERATOR_VALUES = [">", "<"] as const;
export type OperatorValue = (typeof OPERATOR_VALUES)[number];
