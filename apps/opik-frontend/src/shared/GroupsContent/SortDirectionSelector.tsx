import React from "react";
import { useTranslation } from "react-i18next";
import SelectBox from "@/shared/SelectBox/SelectBox";
import { SORT_DIRECTION } from "@/types/sorting";
import { DropdownOption } from "@/types/shared";

export type SortDirectionSelectorProps = {
  direction: SORT_DIRECTION;
  onSelect?: (order: SORT_DIRECTION) => void;
  disabled?: boolean;
};

const SortDirectionSelector: React.FC<SortDirectionSelectorProps> = ({
  direction,
  onSelect,
  disabled,
}) => {
  const { t } = useTranslation();
  const options: DropdownOption<SORT_DIRECTION>[] = [
    { label: t("common.sorting.ascending"), value: SORT_DIRECTION.ASC },
    { label: t("common.sorting.descending"), value: SORT_DIRECTION.DESC },
  ];

  return (
    <SelectBox
      value={direction}
      options={options}
      placeholder={t("common.sorting.sortDirection")}
      onChange={onSelect as never}
      disabled={disabled}
    />
  );
};

export default SortDirectionSelector;
