import i18next from "i18next";
import { GuardrailTypes, PiiSupportedEntities } from "@/types/guardrails";

export type GuardrailFields = {
  threshold: number;
  entities: string[];
  modelName: string;
  model: string;
  instructions: string;
  name: string;
};

export interface GuardrailConfig extends GuardrailFields {
  id: string;
  title: string;
  hintText: string;
  enabled: boolean;
  codeImportName: string;
  codeBuilder: (fields: GuardrailFields) => string;
}

const EMPTY_FIELDS: GuardrailFields = {
  threshold: 0.5,
  entities: [],
  modelName: "",
  model: "",
  instructions: "",
  name: "",
};

export const getGuardrailsMap = (): Record<GuardrailTypes, GuardrailConfig> => {
  const t = i18next.getFixedT(null, "tracing");
  return {
    [GuardrailTypes.TOPIC]: {
      ...EMPTY_FIELDS,
      id: "topic-guardrail",
      title: t("guardrail.topicTitle"),
      hintText: t("guardrail.topicHintText"),
      enabled: true,
      threshold: 0.8,
      codeImportName: "Topic",
      codeBuilder({ entities, threshold }) {
        return `Topic(restricted_topics=${JSON.stringify(
          entities,
        )}, threshold=${threshold})`;
      },
    },
    [GuardrailTypes.PII]: {
      ...EMPTY_FIELDS,
      id: "pii-guardrail",
      title: t("guardrail.piiTitle"),
      hintText: t("guardrail.piiHintText"),
      enabled: true,
      threshold: 0.5,
      entities: [
        PiiSupportedEntities.CREDIT_CARD,
        PiiSupportedEntities.PHONE_NUMBER,
      ],
      codeImportName: "PII",
      codeBuilder({ entities, threshold }) {
        return `PII(blocked_entities=${JSON.stringify(
          entities,
        )}, threshold=${threshold})`;
      },
    },
    [GuardrailTypes.PROMPT_INJECTION]: {
      ...EMPTY_FIELDS,
      id: "prompt-injection-guardrail",
      title: t("guardrail.promptInjectionTitle"),
      hintText: t("guardrail.promptInjectionHintText"),
      enabled: false,
      threshold: 0.5,
      codeImportName: "PromptInjection",
      codeBuilder({ threshold }) {
        return `PromptInjection(threshold=${threshold})`;
      },
    },
    [GuardrailTypes.CUSTOM_CLASSIFIER]: {
      ...EMPTY_FIELDS,
      id: "custom-classifier-guardrail",
      title: t("guardrail.customClassifierTitle"),
      hintText: t("guardrail.customClassifierHintText"),
      enabled: false,
      threshold: 0.5,
      codeImportName: "CustomGuardrail",
      codeBuilder({ modelName, threshold }) {
        return `CustomGuardrail(model_name=${JSON.stringify(
          modelName,
        )}, threshold=${threshold})`;
      },
    },
    [GuardrailTypes.LLM_JUDGE]: {
      ...EMPTY_FIELDS,
      id: "llm-judge-guardrail",
      title: t("guardrail.llmJudgeTitle"),
      hintText: t("guardrail.llmJudgeHintText"),
      enabled: false,
      codeImportName: "LLMJudge",
      codeBuilder({ name, instructions, model }) {
        return `LLMJudge(name=${JSON.stringify(
          name,
        )}, instructions=${JSON.stringify(instructions)}, model=${JSON.stringify(
          model,
        )})`;
      },
    },
  };
};

/** @deprecated Use getGuardrailsMap() instead */
export const guardrailsMap: Record<GuardrailTypes, GuardrailConfig> =
  getGuardrailsMap();

export type GuardrailsState = Record<
  GuardrailTypes,
  GuardrailFields & { enabled: boolean }
>;

export const guardrailsDefaultState: GuardrailsState = (
  Object.keys(guardrailsMap) as GuardrailTypes[]
).reduce<GuardrailsState>((acc, key) => {
  const { threshold, entities, modelName, model, instructions, name, enabled } =
    guardrailsMap[key];
  acc[key] = {
    threshold,
    entities,
    modelName,
    model,
    instructions,
    name,
    enabled,
  };
  return acc;
}, {} as GuardrailsState);
