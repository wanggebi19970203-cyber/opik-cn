import React, { useCallback } from "react";
import CodeMirror from "@uiw/react-codemirror";
import { EditorView } from "@codemirror/view";
import { jsonLanguage } from "@codemirror/lang-json";

import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import PromptModelSettingsTooltipContent from "@/v2/pages-shared/llm/PromptModelSettings/providerConfigs/PromptModelConfigsTooltipContent";
import { LLMCustomConfigsType } from "@/types/providers";
import { DEFAULT_CUSTOM_CONFIGS } from "@/constants/llm";
import { useCodemirrorTheme } from "@/hooks/useCodemirrorTheme";
import useJsonInput from "@/hooks/useJsonInput";
import { Label } from "@/ui/label";
import { FormErrorSkeleton } from "@/ui/form";
import isUndefined from "lodash/isUndefined";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Info } from "lucide-react";
import { useTranslation } from "react-i18next";

interface CustomModelConfigProps {
  configs: Partial<LLMCustomConfigsType>;
  onChange: (configs: Partial<LLMCustomConfigsType>) => void;
}

const CustomModelConfig = ({ configs, onChange }: CustomModelConfigProps) => {
  const { t } = useTranslation();
  const theme = useCodemirrorTheme({ editable: true });

  const handleExtraBodyParametersChange = useCallback(
    (value: Record<string, unknown> | null) => {
      onChange({ custom_parameters: value });
    },
    [onChange],
  );

  const { jsonString, showInvalidJSON, handleJsonChange, handleJsonBlur } =
    useJsonInput({
      value: configs.custom_parameters,
      onChange: handleExtraBodyParametersChange,
    });

  return (
    <div className="flex w-72 flex-col gap-6">
      {!isUndefined(configs.temperature) && (
        <SliderInputControl
          value={configs.temperature}
          onChange={(v) => onChange({ temperature: v })}
          id="temperature"
          min={0}
          max={1}
          step={0.01}
          defaultValue={DEFAULT_CUSTOM_CONFIGS.TEMPERATURE}
          label={t("sharedModelConfigs.temperature")}
          tooltip={
            <PromptModelSettingsTooltipContent
              text={t("customModelConfigs.temperatureTooltip")}
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
          max={10000}
          step={1}
          defaultValue={DEFAULT_CUSTOM_CONFIGS.MAX_COMPLETION_TOKENS}
          label={t("sharedModelConfigs.maxOutputTokens")}
          tooltip={
            <PromptModelSettingsTooltipContent
              text={t("customModelConfigs.maxOutputTokensTooltip")}
            />
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
          defaultValue={DEFAULT_CUSTOM_CONFIGS.TOP_P}
          label={t("sharedModelConfigs.topP")}
          tooltip={
            <PromptModelSettingsTooltipContent
              text={t("customModelConfigs.topPTooltip")}
            />
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
          defaultValue={DEFAULT_CUSTOM_CONFIGS.FREQUENCY_PENALTY}
          label={t("openAIModelConfigs.frequencyPenalty")}
          tooltip={
            <PromptModelSettingsTooltipContent
              text={t("customModelConfigs.frequencyPenaltyTooltip")}
            />
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
          defaultValue={DEFAULT_CUSTOM_CONFIGS.PRESENCE_PENALTY}
          label={t("openAIModelConfigs.presencePenalty")}
          tooltip={
            <PromptModelSettingsTooltipContent
              text={t("customModelConfigs.presencePenaltyTooltip")}
            />
          }
        />
      )}

      <SliderInputControl
        value={configs.throttling ?? DEFAULT_CUSTOM_CONFIGS.THROTTLING}
        onChange={(v) => onChange({ throttling: v })}
        id="throttling"
        min={0}
        max={10}
        step={0.1}
        defaultValue={DEFAULT_CUSTOM_CONFIGS.THROTTLING}
        label={t("sharedModelConfigs.throttling")}
        tooltip={
          <PromptModelSettingsTooltipContent
            text={t("sharedModelConfigs.throttlingTooltip")}
          />
        }
      />

      <SliderInputControl
        value={
          configs.maxConcurrentRequests ??
          DEFAULT_CUSTOM_CONFIGS.MAX_CONCURRENT_REQUESTS
        }
        onChange={(v) => onChange({ maxConcurrentRequests: v })}
        id="maxConcurrentRequests"
        min={1}
        max={20}
        step={1}
        defaultValue={DEFAULT_CUSTOM_CONFIGS.MAX_CONCURRENT_REQUESTS}
        label={t("sharedModelConfigs.maxConcurrentRequests")}
        tooltip={
          <PromptModelSettingsTooltipContent
            text={t("sharedModelConfigs.maxConcurrentRequestsTooltip")}
          />
        }
      />

      <div className="flex flex-col gap-2">
        <Label htmlFor="custom_parameters" className="flex items-center gap-1">
          {t("sharedModelConfigs.extraBodyParameters")}
          <TooltipWrapper
            content={
              <PromptModelSettingsTooltipContent
                text={t("sharedModelConfigs.extraBodyParametersTooltip")}
              />
            }
          >
            <Info className="ml-1 size-4 text-light-slate" />
          </TooltipWrapper>
        </Label>
        <div className="max-h-52 overflow-y-auto rounded-md border">
          <CodeMirror
            id="custom_parameters"
            theme={theme}
            value={jsonString}
            onChange={handleJsonChange}
            onBlur={handleJsonBlur}
            extensions={[jsonLanguage, EditorView.lineWrapping]}
            placeholder='{"key": "value"}'
            basicSetup={{
              lineNumbers: false,
              foldGutter: false,
              highlightActiveLine: false,
              highlightSelectionMatches: false,
            }}
          />
        </div>
        {showInvalidJSON && (
          <FormErrorSkeleton>
            {t("sharedModelConfigs.invalidJson")}
          </FormErrorSkeleton>
        )}
      </div>
    </div>
  );
};

export default CustomModelConfig;
