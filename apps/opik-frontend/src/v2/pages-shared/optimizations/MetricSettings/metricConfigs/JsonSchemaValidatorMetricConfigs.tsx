import React from "react";
import { useTranslation } from "react-i18next";
import {
  JsonSchemaValidatorMetricParameters,
  MetricParamErrors,
} from "@/types/optimizations";
import { DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS } from "@/constants/optimizations";
import ReferenceKeyField from "../ReferenceKeyField";

interface JsonSchemaValidatorMetricConfigsProps {
  configs: Partial<JsonSchemaValidatorMetricParameters>;
  onChange: (configs: Partial<JsonSchemaValidatorMetricParameters>) => void;
  datasetVariables?: string[];
  errors?: MetricParamErrors;
}

const JsonSchemaValidatorMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
  errors,
}: JsonSchemaValidatorMetricConfigsProps) => {
  const { t } = useTranslation("optimizations");

  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-4">
        <ReferenceKeyField
          value={
            configs.reference_key ??
            DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.REFERENCE_KEY
          }
          onChange={(value) => onChange({ ...configs, reference_key: value })}
          datasetVariables={datasetVariables}
          placeholder={t("optimizations.metricConfigs.referenceKeyPlaceholder")}
          error={errors?.reference_key?.message}
        />
      </div>
    </div>
  );
};

export default JsonSchemaValidatorMetricConfigs;
