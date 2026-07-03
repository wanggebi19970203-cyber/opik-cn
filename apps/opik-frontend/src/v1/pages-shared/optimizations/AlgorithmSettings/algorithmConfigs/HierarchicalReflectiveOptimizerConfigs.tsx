import React from "react";
import { useTranslation } from "react-i18next";
import { Label } from "@/ui/label";
import { Checkbox } from "@/ui/checkbox";
import SliderInputControl from "@/shared/SliderInputControl/SliderInputControl";
import { HierarchicalReflectiveOptimizerParameters } from "@/types/optimizations";
import { DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS } from "@/constants/optimizations";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v1/constants/explainers";

interface HierarchicalReflectiveOptimizerConfigsProps {
  configs: Partial<HierarchicalReflectiveOptimizerParameters>;
  onChange: (
    configs: Partial<HierarchicalReflectiveOptimizerParameters>,
  ) => void;
}

const HierarchicalReflectiveOptimizerConfigs = ({
  configs,
  onChange,
}: HierarchicalReflectiveOptimizerConfigsProps) => {
  const { t } = useTranslation();
  return (
    <div className="flex w-72 flex-col gap-6">
      <SliderInputControl
        value={
          configs.convergence_threshold ??
          DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.CONVERGENCE_THRESHOLD
        }
        onChange={(v) => onChange({ ...configs, convergence_threshold: v })}
        id="convergence_threshold"
        min={0}
        max={1}
        step={0.001}
        defaultValue={
          DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.CONVERGENCE_THRESHOLD
        }
        label={t('optimizations.algorithmConfigs.convergenceThreshold')}
        tooltip={t('optimizations.algorithmConfigs.convergenceThresholdTooltip')}
      />

      <div className="space-y-2">
        <div className="flex items-center space-x-2">
          <Checkbox
            id="verbose"
            checked={
              configs.verbose ??
              DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.VERBOSE
            }
            onCheckedChange={(checked) =>
              onChange({ ...configs, verbose: checked === true })
            }
          />
          <Label htmlFor="verbose" className="cursor-pointer text-sm">
            {t('optimizations.algorithmConfigs.verbose')}
          </Label>
          <ExplainerIcon {...EXPLAINERS_MAP[EXPLAINER_ID.optimizer_verbose]} />
        </div>
      </div>

      <SliderInputControl
        value={
          configs.seed ?? DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.SEED
        }
        onChange={(v) => onChange({ ...configs, seed: v })}
        id="seed"
        min={0}
        max={1000}
        step={1}
        defaultValue={DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.SEED}
        label={t('optimizations.algorithmConfigs.seed')}
        tooltip={t('optimizations.algorithmConfigs.seedTooltip')}
      />
    </div>
  );
};

export default HierarchicalReflectiveOptimizerConfigs;
