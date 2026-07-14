import React from "react";
import { useTranslation } from "react-i18next";
import { Filter } from "@/types/filters";
import OperatorSelector from "@/shared/FiltersContent/OperatorSelector";
import { getDefaultOperators } from "@/constants/filters";
import { Input } from "@/ui/input";

type DefaultRowProps = {
  filter: Filter;
};

export const DefaultRow: React.FunctionComponent<DefaultRowProps> = ({
  filter,
}) => {
  const { t } = useTranslation("common");
  return (
    <>
      <td className="p-1">
        <OperatorSelector
          operator={filter.operator}
          operators={getDefaultOperators()}
          disabled
        />
      </td>
      <td className="p-1">
        <Input
          className="w-full min-w-40"
          placeholder={t("labels.value")}
          disabled
        />
      </td>
    </>
  );
};

export default DefaultRow;
