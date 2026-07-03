import { z } from "zod";
import i18next from "i18next";
import { COLUMN_TYPE } from "@/types/shared";

const FilterSchema = z.object({
  id: z.string(),
  field: z.string(),
  type: z.nativeEnum(COLUMN_TYPE).or(z.literal("")),
  operator: z.string(),
  value: z.union([z.string(), z.number()]),
  key: z.string().optional(),
  error: z.string().optional(),
});

export const FiltersArraySchema = z
  .array(FilterSchema)
  .superRefine((filters, ctx) => {
    filters.forEach((filter, index) => {
      if (!filter.field || filter.field.trim().length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: i18next.t("common:validation.fieldRequired"),
          path: [index, "field"],
        });
      }

      if (!filter.operator || filter.operator.trim().length === 0) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: i18next.t("common:validation.operatorRequired"),
          path: [index, "operator"],
        });
      }

      if (
        filter.operator &&
        filter.operator !== "is_empty" &&
        filter.operator !== "is_not_empty"
      ) {
        const valueString = String(filter.value || "").trim();
        if (valueString.length === 0) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: i18next.t("common:validation.valueRequiredForOperator"),
            path: [index, "value"],
          });
        }
      }

      if (
        (filter.type === COLUMN_TYPE.dictionary ||
          filter.type === COLUMN_TYPE.numberDictionary) &&
        (!filter.key || filter.key.trim().length === 0)
      ) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: i18next.t("common:validation.keyRequiredForDictionaryFields"),
          path: [index, "key"],
        });
      }

      if (filter.error) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: filter.error,
          path: [index, "value"],
        });
      }
    });
  });
