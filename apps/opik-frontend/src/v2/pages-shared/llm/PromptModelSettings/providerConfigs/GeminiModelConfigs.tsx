import React from "react";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import { LLMGeminiConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import {
  DEFAULT_GEMINI_CONFIGS,
  THINKING_LEVEL_OPTIONS_PRO,
  THINKING_LEVEL_OPTIONS_FLASH,
} from "@/constants/llm";
import { GeminiThinkingLevel } from "@/types/providers";
import PromptModelConfigsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import isUndefined from "lodash/isUndefined";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { supportsGeminiThinkingLevel } from "@/lib/modelUtils";
import { useTranslation } from "react-i18next";

interface geminiModelConfigsProps {
  configs: LLMGeminiConfigsType;
  model?: PROVIDER_MODEL_TYPE | "";
  onChange: (configs: Partial<LLMGeminiConfigsType>) => void;
}

const GeminiModelConfigs = ({
  configs,
  model,
  onChange,
}: geminiModelConfigsProps) => {
  const { t } = useTranslation();
  const hasThinkingLevel = supportsGeminiThinkingLevel(model);
  const isGemini3Flash = model === PROVIDER_MODEL_TYPE.GEMINI_3_FLASH;

  // Get appropriate options based on model
  // Flash supports all 4 levels (minimal, low, medium, high)
  // Pro supports only 2 levels (low, high)
  // Both default to "high" (dynamic reasoning)
  const thinkingLevelOptions = isGemini3Flash
    ? THINKING_LEVEL_OPTIONS_FLASH
    : THINKING_LEVEL_OPTIONS_PRO;
  const defaultThinkingLevel = "high";

  return (
    <div className="flex w-72 flex-col gap-6">
      {!isUndefined(configs.temperature) && (
        <SliderInputControl
          value={configs.temperature}
          onChange={(v) => onChange({ temperature: v })}
          id="temperature"
          min={0}
          max={2}
          step={0.01}
          defaultValue={DEFAULT_GEMINI_CONFIGS.TEMPERATURE}
          label={t("sharedModelConfigs.temperature")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.temperatureTooltip")} />
          }
        />
      )}

      {!isUndefined(configs.maxCompletionTokens) && (
        <SliderInputControl
          value={configs.maxCompletionTokens}
          onChange={(v) => onChange({ maxCompletionTokens: v })}
          id="maxOutputTokens"
          min={0}
          max={65535}
          step={1}
          defaultValue={DEFAULT_GEMINI_CONFIGS.MAX_COMPLETION_TOKENS}
          label={t("sharedModelConfigs.maxOutputTokens")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.maxOutputTokensTooltip")} />
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
          defaultValue={DEFAULT_GEMINI_CONFIGS.TOP_P}
          label={t("sharedModelConfigs.topP")}
          tooltip={
            <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.topPTooltip")} />
          }
        />
      )}

      {hasThinkingLevel && (
        <div className="space-y-2">
          <div className="flex items-center space-x-2">
            <Label htmlFor="thinkingLevel" className="text-sm font-medium">
              {t("geminiModelConfigs.thinkingLevel")}
            </Label>
            <ExplainerIcon description={t("geminiModelConfigs.thinkingLevelDescription")} />
          </div>
          <SelectBox
            id="thinkingLevel"
            value={configs.thinkingLevel || defaultThinkingLevel}
            onChange={(value: GeminiThinkingLevel) =>
              onChange({ thinkingLevel: value })
            }
            options={thinkingLevelOptions}
            placeholder={t("geminiModelConfigs.selectThinkingLevel")}
          />
        </div>
      )}

      <SliderInputControl
        value={configs.throttling ?? DEFAULT_GEMINI_CONFIGS.THROTTLING}
        onChange={(v) => onChange({ throttling: v })}
        id="throttling"
        min={0}
        max={10}
        step={0.1}
        defaultValue={DEFAULT_GEMINI_CONFIGS.THROTTLING}
        label={t("sharedModelConfigs.throttling")}
        tooltip={
          <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.throttlingTooltip")} />
        }
      />

      <SliderInputControl
        value={
          configs.maxConcurrentRequests ??
          DEFAULT_GEMINI_CONFIGS.MAX_CONCURRENT_REQUESTS
        }
        onChange={(v) => onChange({ maxConcurrentRequests: v })}
        id="maxConcurrentRequests"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_GEMINI_CONFIGS.MAX_CONCURRENT_REQUESTS}
        label={t("sharedModelConfigs.maxConcurrentRequests")}
        tooltip={
          <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.maxConcurrentRequestsTooltip")} />
        }
      />
    </div>
  );
};

export default GeminiModelConfigs;
