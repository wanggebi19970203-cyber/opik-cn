import React from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v1/constants/explainers";

type OptimizationsNewHeaderProps = {
  isSubmitting: boolean;
  isFormValid: boolean;
  onSubmit: () => void;
  onCancel: () => void;
};

const OptimizationsNewHeader: React.FC<OptimizationsNewHeaderProps> = ({
  isSubmitting,
  isFormValid,
  onSubmit,
  onCancel,
}) => {
  const { t } = useTranslation("pages/optimizations");

  return (
    <>
      <div className="mb-2 flex items-center justify-between">
        <h1 className="comet-title-l">{t("optimizations.newPage.title")}</h1>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" onClick={onCancel}>
            {t("optimizations.newPage.cancel")}
          </Button>
          <Button
            size="sm"
            onClick={onSubmit}
            disabled={isSubmitting || !isFormValid}
          >
            {isSubmitting
              ? t("optimizations.newPage.starting")
              : t("optimizations.newPage.optimizePrompt")}
          </Button>
        </div>
      </div>
      <ExplainerDescription
        {...EXPLAINERS_MAP[EXPLAINER_ID.whats_the_optimization_config]}
        className="mb-6"
      />
    </>
  );
};

export default OptimizationsNewHeader;
