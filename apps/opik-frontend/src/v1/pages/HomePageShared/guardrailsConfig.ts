import i18next from "i18next";
import { GuardrailTypes, PiiSupportedEntities } from "@/types/guardrails";

export interface GuardrailConfig {
  id: string;
  title: string;
  hintText: string;
  enabled: boolean;
  threshold: number;
  entities: string[];
  codeImportName: string;
  codeBuilder: (entities: string[], threshold: number) => string;
}

export const guardrailsMap: Record<GuardrailTypes, GuardrailConfig> = {
  [GuardrailTypes.TOPIC]: {
    id: "topic-guardrail",
    title: i18next.t("common:guardrails.topicGuardrail"),
    hintText: i18next.t("common:guardrails.topicGuardrailHint"),
    enabled: true,
    threshold: 0.8,
    entities: [],
    codeImportName: "Topic",
    codeBuilder(entities, threshold) {
      return `Topic(restricted_topics=${JSON.stringify(
        entities,
      )}, threshold=${threshold})`;
    },
  },
  [GuardrailTypes.PII]: {
    id: "pii-guardrail",
    title: i18next.t("common:guardrails.piiGuardrail"),
    hintText: i18next.t("common:guardrails.piiGuardrailHint"),
    enabled: true,
    threshold: 0.5,
    entities: [
      PiiSupportedEntities.CREDIT_CARD,
      PiiSupportedEntities.PHONE_NUMBER,
    ],
    codeImportName: "PII",
    codeBuilder(entities, threshold) {
      return `PII(blocked_entities=${JSON.stringify(
        entities,
      )}, threshold=${threshold})`;
    },
  },
};

export type GuardrailsState = Record<
  GuardrailTypes,
  Pick<GuardrailConfig, "threshold" | "entities" | "enabled">
>;
export const guardrailsDefaultState: GuardrailsState = (
  Object.keys(guardrailsMap) as GuardrailTypes[]
).reduce<GuardrailsState>((acc, key) => {
  acc[key] = {
    threshold: guardrailsMap[key].threshold,
    entities: guardrailsMap[key].entities,
    enabled: guardrailsMap[key].enabled,
  };
  return acc;
}, {} as GuardrailsState);
