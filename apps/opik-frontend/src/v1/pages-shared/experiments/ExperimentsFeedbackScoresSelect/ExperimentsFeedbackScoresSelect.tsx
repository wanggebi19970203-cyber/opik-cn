import React, { useMemo } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import sortBy from "lodash/sortBy";
import { useTranslation } from "react-i18next";

import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";
import SelectBox from "@/shared/SelectBox/SelectBox";

type ExperimentsFeedbackScoresSelectProps = {
  experimentsIds?: string[];
  value: string;
  onValueChange: (value: string) => void;
  placeholder?: string;
  className?: string;
};

const ExperimentsFeedbackScoresSelect: React.FC<
  ExperimentsFeedbackScoresSelectProps
> = ({ experimentsIds, value, onValueChange, placeholder, className }) => {
  const { t } = useTranslation("experiments");
  const resolvedPlaceholder = placeholder ?? t("selectScore");
  const { data } = useExperimentsFeedbackScoresNames(
    {
      experimentsIds,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const options = useMemo(() => {
    return sortBy(
      (data?.scores || []).map((f) => ({ label: f.name, value: f.name })),
      "label",
    );
  }, [data]);

  return (
    <SelectBox
      value={value}
      onChange={onValueChange}
      options={options}
      placeholder={resolvedPlaceholder}
      className={className}
    />
  );
};

export default ExperimentsFeedbackScoresSelect;
