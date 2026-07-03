import React from "react";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import { LLMVertexAIConfigsType, PROVIDER_MODEL_TYPE } from "@/types/providers";
import {
  DEFAULT_VERTEX_AI_CONFIGS,
  THINKING_LEVEL_OPTIONS,
} from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import isUndefined from "lodash/isUndefined";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { supportsVertexAIThinkingLevel } from "@/lib/modelUtils";
import { useTranslation } from "react-i18next";

interface VertexAIModelConfigsProps {
  configs: LLMVertexAIConfigsType;
  model?: PROVIDER_MODEL_TYPE | "";
  onChange: (configs: Partial<LLMVertexAIConfigsType>) => void;
}

const VertexAIModelConfigs = ({
  configs,
  model,
  onChange,
}: VertexAIModelConfigsProps) => {
  const { t } = useTranslation();
  const hasThinkingLevel = supportsVertexAIThinkingLevel(model);

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
          defaultValue={DEFAULT_VERTEX_AI_CONFIGS.TEMPERATURE}
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
          defaultValue={DEFAULT_VERTEX_AI_CONFIGS.MAX_COMPLETION_TOKENS}
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
          defaultValue={DEFAULT_VERTEX_AI_CONFIGS.TOP_P}
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
              {t("vertexAIModelConfigs.thinkingLevel")}
            </Label>
            <ExplainerIcon description={t("vertexAIModelConfigs.thinkingLevelDescription")} />
          </div>
          <SelectBox
            id="thinkingLevel"
            value={configs.thinkingLevel || "low"}
            onChange={(value: "low" | "high") =>
              onChange({ thinkingLevel: value })
            }
            options={THINKING_LEVEL_OPTIONS}
            placeholder={t("vertexAIModelConfigs.selectThinkingLevel")}
          />
        </div>
      )}

      <SliderInputControl
        value={configs.throttling ?? DEFAULT_VERTEX_AI_CONFIGS.THROTTLING}
        onChange={(v) => onChange({ throttling: v })}
        id="throttling"
        min={0}
        max={10}
        step={0.1}
        defaultValue={DEFAULT_VERTEX_AI_CONFIGS.THROTTLING}
        label={t("sharedModelConfigs.throttling")}
        tooltip={
          <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.throttlingTooltip")} />
        }
      />

      <SliderInputControl
        value={
          configs.maxConcurrentRequests ??
          DEFAULT_VERTEX_AI_CONFIGS.MAX_CONCURRENT_REQUESTS
        }
        onChange={(v) => onChange({ maxConcurrentRequests: v })}
        id="maxConcurrentRequests"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_VERTEX_AI_CONFIGS.MAX_CONCURRENT_REQUESTS}
        label={t("sharedModelConfigs.maxConcurrentRequests")}
        tooltip={
          <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.maxConcurrentRequestsTooltip")} />
        }
      />
    </div>
  );
};

export default VertexAIModelConfigs;
