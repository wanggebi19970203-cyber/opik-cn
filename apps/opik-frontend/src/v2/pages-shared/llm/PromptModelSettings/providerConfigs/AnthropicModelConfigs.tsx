import React, { useCallback } from "react";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import {
  LLMAnthropicConfigsType,
  PROVIDER_MODEL_TYPE,
  AnthropicThinkingEffort,
} from "@/types/providers";
import { DEFAULT_ANTHROPIC_CONFIGS } from "@/constants/llm";
import PromptModelConfigsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { Button } from "@/ui/button";
import { X } from "lucide-react";
import {
  getAnthropicThinkingEffortOptions,
  supportsAnthropicThinkingEffort,
  supportsSamplingParams,
} from "@/lib/modelUtils";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { Label } from "@/ui/label";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import isNil from "lodash/isNil";
import { useTranslation } from "react-i18next";

interface AnthropicModelConfigsProps {
  configs: LLMAnthropicConfigsType;
  onChange: (configs: Partial<LLMAnthropicConfigsType>) => void;
  model?: PROVIDER_MODEL_TYPE | "";
}

const AnthropicModelConfigs = ({
  configs,
  onChange,
  model,
}: AnthropicModelConfigsProps) => {
  const { t } = useTranslation();
  const showThinkingEffort = supportsAnthropicThinkingEffort(model);
  const showSamplingParams = supportsSamplingParams(model);
  const thinkingEffortOptions = getAnthropicThinkingEffortOptions(model);
  const hasTemperatureValue = !isNil(configs.temperature);
  const hasTopPValue = !isNil(configs.topP);
  const temperatureDisabled = hasTopPValue && !hasTemperatureValue;
  const topPDisabled = hasTemperatureValue && !hasTopPValue;

  const handleTemperatureChange = useCallback(
    (v: number) => {
      onChange({ temperature: v, topP: undefined });
    },
    [onChange],
  );

  const handleTopPChange = useCallback(
    (v: number) => {
      onChange({ topP: v, temperature: undefined });
    },
    [onChange],
  );

  const handleClearTemperature = useCallback(() => {
    onChange({
      temperature: undefined,
      topP: DEFAULT_ANTHROPIC_CONFIGS.TOP_P,
    });
  }, [onChange]);

  const handleClearTopP = useCallback(() => {
    onChange({
      topP: undefined,
      temperature: DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE,
    });
  }, [onChange]);

  return (
    <div className="flex w-72 flex-col gap-6">
      {showSamplingParams && (
        <div className="space-y-2">
          <div
            className={
              temperatureDisabled ? "pointer-events-none opacity-50" : ""
            }
          >
            <SliderInputControl
              value={
                configs.temperature ??
                (temperatureDisabled
                  ? undefined
                  : DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE)
              }
              onChange={handleTemperatureChange}
              id="temperature"
              min={0}
              max={1}
              step={0.01}
              defaultValue={DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE}
              label={t("sharedModelConfigs.temperature")}
              tooltip={
                <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.anthropicTemperatureTooltip")} />
              }
            />
          </div>
          {hasTemperatureValue && (
            <div className="flex justify-end">
              <Button
                variant="ghost"
                size="2xs"
                onClick={handleClearTemperature}
                aria-label="Clear temperature to use Top P"
              >
                <X className="mr-1 size-3" />
                {t("sharedModelConfigs.clearToUseTopP")}
              </Button>
            </div>
          )}
        </div>
      )}

      <SliderInputControl
        value={
          configs.maxCompletionTokens ??
          DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS
        }
        onChange={(v) => onChange({ maxCompletionTokens: v })}
        id="maxCompletionTokens"
        min={0}
        max={64000}
        step={1}
        defaultValue={DEFAULT_ANTHROPIC_CONFIGS.MAX_COMPLETION_TOKENS}
        label={t("sharedModelConfigs.maxOutputTokens")}
        tooltip={
          <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.maxOutputTokensTooltip")} />
        }
      />

      {showSamplingParams && (
        <div className="space-y-2">
          <div className={topPDisabled ? "pointer-events-none opacity-50" : ""}>
            <SliderInputControl
              value={
                configs.topP ??
                (topPDisabled ? undefined : DEFAULT_ANTHROPIC_CONFIGS.TOP_P)
              }
              onChange={handleTopPChange}
              id="topP"
              min={0}
              max={1}
              step={0.01}
              defaultValue={DEFAULT_ANTHROPIC_CONFIGS.TOP_P}
              label={t("sharedModelConfigs.topP")}
              tooltip={
                <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.anthropicTopPTooltip")} />
              }
            />
          </div>
          {hasTopPValue && (
            <div className="flex justify-end">
              <Button
                variant="ghost"
                size="2xs"
                onClick={handleClearTopP}
                aria-label="Clear Top P to use temperature"
              >
                <X className="mr-1 size-3" />
                {t("sharedModelConfigs.clearToUseTemperature")}
              </Button>
            </div>
          )}
        </div>
      )}

      <SliderInputControl
        value={configs.throttling ?? DEFAULT_ANTHROPIC_CONFIGS.THROTTLING}
        onChange={(v) => onChange({ throttling: v })}
        id="throttling"
        min={0}
        max={10}
        step={0.1}
        defaultValue={DEFAULT_ANTHROPIC_CONFIGS.THROTTLING}
        label={t("sharedModelConfigs.throttling")}
        tooltip={
          <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.throttlingTooltip")} />
        }
      />

      <SliderInputControl
        value={
          configs.maxConcurrentRequests ??
          DEFAULT_ANTHROPIC_CONFIGS.MAX_CONCURRENT_REQUESTS
        }
        onChange={(v) => onChange({ maxConcurrentRequests: v })}
        id="maxConcurrentRequests"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_ANTHROPIC_CONFIGS.MAX_CONCURRENT_REQUESTS}
        label={t("sharedModelConfigs.maxConcurrentRequests")}
        tooltip={
          <PromptModelConfigsTooltipContent text={t("sharedModelConfigs.maxConcurrentRequestsTooltip")} />
        }
      />

      {showThinkingEffort && (
        <div className="space-y-2">
          <div className="flex items-center space-x-2">
            <Label htmlFor="thinkingEffort" className="text-sm font-medium">
              {t("anthropicModelConfigs.thinkingEffort")}
            </Label>
            <ExplainerIcon description={t("anthropicModelConfigs.thinkingEffortDescription")} />
          </div>
          <SelectBox
            id="thinkingEffort"
            value={configs.thinkingEffort || "high"}
            onChange={(value: AnthropicThinkingEffort) =>
              onChange({ thinkingEffort: value })
            }
            options={thinkingEffortOptions}
            placeholder={t("anthropicModelConfigs.selectThinkingEffort")}
          />
        </div>
      )}
    </div>
  );
};

export default AnthropicModelConfigs;
