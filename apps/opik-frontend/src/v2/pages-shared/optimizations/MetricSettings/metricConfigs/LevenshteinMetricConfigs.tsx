import React from "react";
import { useTranslation } from "react-i18next";
import { Label } from "@/ui/label";
import { Checkbox } from "@/ui/checkbox";
import {
  LevenshteinMetricParameters,
  MetricParamErrors,
} from "@/types/optimizations";
import ReferenceKeyField from "../ReferenceKeyField";

interface LevenshteinMetricConfigsProps {
  configs: Partial<LevenshteinMetricParameters>;
  onChange: (configs: Partial<LevenshteinMetricParameters>) => void;
  datasetVariables?: string[];
  errors?: MetricParamErrors;
}

const LevenshteinMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
  errors,
}: LevenshteinMetricConfigsProps) => {
  const { t } = useTranslation("optimizations");
  return (
    <div className="flex w-72 flex-col gap-6">
      <div className="space-y-4">
        <ReferenceKeyField
          value={configs.reference_key ?? ""}
          onChange={(value) => onChange({ ...configs, reference_key: value })}
          datasetVariables={datasetVariables}
          error={errors?.reference_key?.message}
        />

        <div className="flex items-center space-x-2">
          <Checkbox
            id="case_sensitive"
            checked={configs.case_sensitive}
            onCheckedChange={(checked) =>
              onChange({ ...configs, case_sensitive: checked === true })
            }
          />
          <Label htmlFor="case_sensitive" className="cursor-pointer text-sm">
            {t("optimizations.metricConfigs.caseSensitiveComparison")}
          </Label>
        </div>
      </div>
    </div>
  );
};

export default LevenshteinMetricConfigs;
