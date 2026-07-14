import React from "react";
import { useTranslation } from "react-i18next";
import { Filter, FilterRowConfig } from "@/types/filters";
import OperatorSelector from "@/shared/FiltersContent/OperatorSelector";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { COLUMN_TYPE } from "@/types/shared";
import { getDefaultOperators, getOperatorsMap } from "@/constants/filters";

type EqualsRowProps = {
  filter: Filter;
  onChange: (filter: Filter) => void;
  config?: FilterRowConfig;
};

export const CategoryRow: React.FunctionComponent<EqualsRowProps> = ({
  filter,
  onChange,
  config,
}) => {
  const { t } = useTranslation();
  const value = `${filter.value}`;
  return (
    <>
      <td className="p-1">
        <OperatorSelector
          operator={filter.operator}
          operators={
            config?.operators ??
            getOperatorsMap()[filter.type as COLUMN_TYPE] ??
            getDefaultOperators()
          }
          disabled
        />
      </td>
      <td className="p-1">
        <SelectBox
          value={value}
          options={[]}
          placeholder={t("common.selectBox.selectValue")}
          onChange={(value) => onChange({ ...filter, value })}
          {...(config?.keyComponentProps ?? {})}
        />
      </td>
    </>
  );
};

export default CategoryRow;
