import React from "react";
import isUndefined from "lodash/isUndefined";
import { useTranslation } from "react-i18next";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import { LLMOpenRouterConfigsType } from "@/types/providers";
import { DEFAULT_OPEN_ROUTER_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/v1/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";

interface OpenRouterModelConfigsProps {
  configs: LLMOpenRouterConfigsType;
  onChange: (configs: Partial<LLMOpenRouterConfigsType>) => void;
}

const OpenRouterModelConfigs = ({
  configs,
  onChange,
}: OpenRouterModelConfigsProps) => {
  const { t } = useTranslation();
  return (
    <div className="flex w-72 flex-col gap-4">
      {!isUndefined(configs.temperature) && (
        <SliderInputControl
          value={configs.temperature}
          onChange={(v) => onChange({ temperature: v })}
          id="temperature"
          min={-1}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.TEMPERATURE}
          label={t("promptEngineering:modelConfigs.temperature")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.temperatureTooltip")} />
          }
        />
      )}
      {!isUndefined(configs.maxTokens) && (
        <SliderInputControl
          value={configs.maxTokens}
          onChange={(v) => onChange({ maxTokens: v })}
          id="maxTokens"
          min={0}
          max={10000}
          step={1}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.MAX_TOKENS}
          label={t("promptEngineering:modelConfigs.maxTokens")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.maxTokensTooltip")} />
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
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.TOP_P}
          label={t("promptEngineering:modelConfigs.topP")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.topPTooltip")} />
          }
        />
      )}
      {!isUndefined(configs.topK) && (
        <SliderInputControl
          value={configs.topK}
          onChange={(v) => onChange({ topK: v })}
          id="topK"
          min={0}
          max={100}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.TOP_K}
          label={t("promptEngineering:modelConfigs.topK")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.topKTooltip")} />
          }
        />
      )}
      {!isUndefined(configs.frequencyPenalty) && (
        <SliderInputControl
          value={configs.frequencyPenalty}
          onChange={(v) => onChange({ frequencyPenalty: v })}
          id="topK"
          min={-2}
          max={2}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.FREQUENCY_PENALTY}
          label={t("promptEngineering:modelConfigs.frequencyPenalty")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.openRouterFrequencyPenaltyTooltip")} />
          }
        />
      )}
      {!isUndefined(configs.presencePenalty) && (
        <SliderInputControl
          value={configs.presencePenalty}
          onChange={(v) => onChange({ presencePenalty: v })}
          id="presencePenalty"
          min={-2}
          max={2}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.PRESENCE_PENALTY}
          label={t("promptEngineering:modelConfigs.presencePenalty")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.openRouterPresencePenaltyTooltip")} />
          }
        />
      )}
      {!isUndefined(configs.repetitionPenalty) && (
        <SliderInputControl
          value={configs.repetitionPenalty}
          onChange={(v) => onChange({ repetitionPenalty: v })}
          id="repetitionPenalty"
          min={0}
          max={2}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.REPETITION_PENALTY}
          label={t("promptEngineering:modelConfigs.repetitionPenalty")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.repetitionPenaltyTooltip")} />
          }
        />
      )}
      {!isUndefined(configs.minP) && (
        <SliderInputControl
          value={configs.minP}
          onChange={(v) => onChange({ minP: v })}
          id="minP"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.MIN_P}
          label={t("promptEngineering:modelConfigs.minP")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.minPTooltip")} />
          }
        />
      )}
      {!isUndefined(configs.topA) && (
        <SliderInputControl
          value={configs.topA}
          onChange={(v) => onChange({ topA: v })}
          id="topA"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.TOP_A}
          label={t("promptEngineering:modelConfigs.topA")}
          tooltip={
            <PromptModelConfigsTooltipContent
              text={
                t("promptEngineering:modelConfigs.topATooltip")
              }
            />
          }
        />
      )}
      <SliderInputControl
        value={configs.throttling ?? DEFAULT_OPEN_ROUTER_CONFIGS.THROTTLING}
        onChange={(v) => onChange({ throttling: v })}
        id="throttling"
        min={0}
        max={10}
        step={0.1}
        defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.THROTTLING}
        label={t("promptEngineering:modelConfigs.throttling")}
        tooltip={
          <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.throttlingTooltip")} />
        }
      />
      <SliderInputControl
        value={
          configs.maxConcurrentRequests ??
          DEFAULT_OPEN_ROUTER_CONFIGS.MAX_CONCURRENT_REQUESTS
        }
        onChange={(v) => onChange({ maxConcurrentRequests: v })}
        id="maxConcurrentRequests"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_OPEN_ROUTER_CONFIGS.MAX_CONCURRENT_REQUESTS}
        label={t("promptEngineering:modelConfigs.maxConcurrentRequests")}
        tooltip={
          <PromptModelConfigsTooltipContent text={t("promptEngineering:modelConfigs.maxConcurrentRequestsTooltip")} />
        }
      />
    </div>
  );
};

export default OpenRouterModelConfigs;
