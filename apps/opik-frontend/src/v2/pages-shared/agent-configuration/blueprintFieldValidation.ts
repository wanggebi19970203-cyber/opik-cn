import i18next from "i18next";
import { z } from "zod";

import { BlueprintValueType } from "@/types/agent-configs";

export const BLUEPRINT_FIELD_NAME_PATTERN = /^[a-zA-Z_][a-zA-Z0-9_]*$/;

const nonEmptyString = z.string().min(1, i18next.t("common:blueprint.mustNotBeEmpty"));

const FIELD_SCHEMAS: Partial<Record<BlueprintValueType, z.ZodType>> = {
  [BlueprintValueType.INT]: nonEmptyString.pipe(
    z.coerce.number().int(i18next.t("common:blueprint.mustBeAnInteger")),
  ),
  [BlueprintValueType.FLOAT]: nonEmptyString.pipe(
    z.coerce.number({ message: i18next.t("common:blueprint.mustBeAValidNumber") }),
  ),
  [BlueprintValueType.STRING]: nonEmptyString,
};

export const validateBlueprintFieldValue = (
  type: BlueprintValueType,
  value: string,
): string => {
  const schema = FIELD_SCHEMAS[type];
  if (!schema) return "";
  const result = schema.safeParse(value.trim());
  return result.success ? "" : result.error.issues[0]?.message ?? i18next.t("common:blueprint.invalid");
};
