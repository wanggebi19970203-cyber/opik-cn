import React from "react";
import { useTranslation } from "react-i18next";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import PromptModelSettingsTooltipContent from "@/v1/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import {
  LLMOpenAIConfigsType,
  PROVIDER_MODEL_TYPE,
  ReasoningEffort,
} from "@/types/providers";
import { DEFAULT_OPEN_AI_CONFIGS } from "@/constants/llm";
import {
  getOpenAIReasoningEffortOptions,
  isReasoningModel,
  supportsOpenAIReasoningEffort,
} from "@/lib/modelUtils";
import isUndefined from "lodash/isUndefined";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";

interface OpenAIModelSettingsProps {
  configs: Partial<LLMOpenAIConfigsType>;
  model?: PROVIDER_MODEL_TYPE | "";
  onChange: (configs: Partial<LLMOpenAIConfigsType>) => void;
}

const OpenAIModelConfigs = ({
  configs,
  model,
  onChange,
}: OpenAIModelSettingsProps) => {
  const { t } = useTranslation("prompt");
  // Reasoning models (GPT-5.2, GPT-5.1, GPT-5, O1, O3, O4-mini) require temperature = 1.0
  const isReasoning = isReasoningModel(model);

  return (
    <div className="flex w-72 flex-col gap-6">
      {!isUndefined(configs.temperature) && (
        <SliderInputControl
          value={configs.temperature}
          onChange={(v) => onChange({ temperature: v })}
          id="temperature"
          min={isReasoning ? 1 : 0}
          max={1}
          step={0.01}
          defaultValue={isReasoning ? 1 : DEFAULT_OPEN_AI_CONFIGS.TEMPERATURE}
          label={t("sharedModelConfigs.temperature")}
          tooltip={
            <PromptModelSettingsTooltipContent
              text={
                isReasoning
                  ? t("sharedModelConfigs.temperatureReasoningTooltip")
                  : t("sharedModelConfigs.temperatureTooltip")
              }
            />
          }
        />
      )}

      {!isUndefined(configs.maxCompletionTokens) && (
        <SliderInputControl
          value={configs.maxCompletionTokens}
          onChange={(v) => onChange({ maxCompletionTokens: v })}
          id="maxCompletionTokens"
          min={0}
          max={128000}
          step={1}
          defaultValue={DEFAULT_OPEN_AI_CONFIGS.MAX_COMPLETION_TOKENS}
          label={t("sharedModelConfigs.maxOutputTokens")}
          tooltip={
            <PromptModelSettingsTooltipContent text={t("sharedModelConfigs.maxOutputTokensTooltip")} />
          }
        />
      )}

      {!isUndefined(configs.topP) && (
        <SliderInputControl
          value={configs.topP}
          onChange={(v) => onChange({ topP: v })}
          id="topP"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_AI_CONFIGS.TOP_P}
          label={t("sharedModelConfigs.topP")}
          tooltip={
            <PromptModelSettingsTooltipContent text={t("sharedModelConfigs.topPTooltip")} />
          }
        />
      )}

      {!isUndefined(configs.frequencyPenalty) && (
        <SliderInputControl
          value={configs.frequencyPenalty}
          onChange={(v) => onChange({ frequencyPenalty: v })}
          id="frequencyPenalty"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_AI_CONFIGS.FREQUENCY_PENALTY}
          label={t("openAIModelConfigs.frequencyPenalty")}
          tooltip={
            <PromptModelSettingsTooltipContent text={t("openAIModelConfigs.frequencyPenaltyTooltip")} />
          }
        />
      )}

      {!isUndefined(configs.presencePenalty) && (
        <SliderInputControl
          value={configs.presencePenalty}
          onChange={(v) => onChange({ presencePenalty: v })}
          id="presencePenalty"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_AI_CONFIGS.PRESENCE_PENALTY}
          label={t("openAIModelConfigs.presencePenalty")}
          tooltip={
            <PromptModelSettingsTooltipContent text={t("openAIModelConfigs.presencePenaltyTooltip")} />
          }
        />
      )}

      {supportsOpenAIReasoningEffort(model) && (
        <div className="space-y-2">
          <div className="flex items-center space-x-2">
            <Label htmlFor="reasoningEffort" className="text-sm font-medium">
              {t("openAIModelConfigs.reasoningEffort")}
            </Label>
            <ExplainerIcon description={t("openAIModelConfigs.reasoningEffortDescription")} />
          </div>
          <Select
            value={configs.reasoningEffort ?? "high"}
            onValueChange={(value: ReasoningEffort) =>
              onChange({ reasoningEffort: value })
            }
          >
            <SelectTrigger>
              <SelectValue placeholder={t("openAIModelConfigs.selectReasoningEffort")} />
            </SelectTrigger>
            <SelectContent>
              {getOpenAIReasoningEffortOptions(model).map((opt) => (
                <SelectItem key={opt.value} value={opt.value}>
                  {opt.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}

      <SliderInputControl
        value={configs.throttling ?? DEFAULT_OPEN_AI_CONFIGS.THROTTLING}
        onChange={(v) => onChange({ throttling: v })}
        id="throttling"
        min={0}
        max={10}
        step={0.1}
        defaultValue={DEFAULT_OPEN_AI_CONFIGS.THROTTLING}
        label={t("sharedModelConfigs.throttling")}
        tooltip={
          <PromptModelSettingsTooltipContent text={t("sharedModelConfigs.throttlingTooltip")} />
        }
      />

      <SliderInputControl
        value={
          configs.maxConcurrentRequests ??
          DEFAULT_OPEN_AI_CONFIGS.MAX_CONCURRENT_REQUESTS
        }
        onChange={(v) => onChange({ maxConcurrentRequests: v })}
        id="maxConcurrentRequests"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_OPEN_AI_CONFIGS.MAX_CONCURRENT_REQUESTS}
        label={t("sharedModelConfigs.maxConcurrentRequests")}
        tooltip={
          <PromptModelSettingsTooltipContent text={t("sharedModelConfigs.maxConcurrentRequestsTooltip")} />
        }
      />
    </div>
  );
};

export default OpenAIModelConfigs;
