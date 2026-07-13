import React from "react";
import { useTranslation } from "react-i18next";
import {
  NumericalSimilarityMetricParameters,
  MetricParamErrors,
} from "@/types/optimizations";
import ReferenceKeyField from "../ReferenceKeyField";

interface NumericalSimilarityMetricConfigsProps {
  configs: Partial<NumericalSimilarityMetricParameters>;
  onChange: (configs: Partial<NumericalSimilarityMetricParameters>) => void;
  datasetVariables?: string[];
  errors?: MetricParamErrors;
}

const NumericalSimilarityMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
  errors,
}: NumericalSimilarityMetricConfigsProps) => {
  const { t } = useTranslation("optimizations");
  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-4">
        <ReferenceKeyField
          value={configs.reference_key ?? ""}
          onChange={(value) => onChange({ ...configs, reference_key: value })}
          datasetVariables={datasetVariables}
          placeholder={t(
            "optimizations.metricConfigs.referenceKeyScorePlaceholder",
          )}
          error={errors?.reference_key?.message}
        />
      </div>
    </div>
  );
};

export default NumericalSimilarityMetricConfigs;
