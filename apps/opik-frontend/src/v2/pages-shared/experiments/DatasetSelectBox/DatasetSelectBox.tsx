import React, { useCallback, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { keepPreviousData } from "@tanstack/react-query";

import useProjectDatasetsList from "@/api/datasets/useProjectDatasetsList";
import LoadableSelectBox from "@/shared/LoadableSelectBox/LoadableSelectBox";
import { DropdownOption } from "@/types/shared";
import { usePermissions } from "@/contexts/PermissionsContext";

const DEFAULT_LOADED_DATASET_ITEMS = 1000;

type DatasetSelectBoxProps = {
  value: string;
  onValueChange: (value: string) => void;
  projectId?: string | null;
  placeholder?: string;
  className?: string;
};

const DatasetSelectBox: React.FC<DatasetSelectBoxProps> = ({
  value,
  onValueChange,
  projectId,
  placeholder,
  className,
}) => {
  const { t } = useTranslation("experiments");
  const {
    permissions: { canViewDatasets },
  } = usePermissions();
  const resolvedPlaceholder = placeholder ?? t("selectTestSuite");
  const [isLoadedMore, setIsLoadedMore] = useState(false);
  const { data, isLoading } = useProjectDatasetsList(
    {
      projectId: projectId!,
      page: 1,
      size: isLoadedMore ? 10000 : DEFAULT_LOADED_DATASET_ITEMS,
    },
    {
      placeholderData: keepPreviousData,
      enabled: canViewDatasets && !!projectId,
    },
  );

  const total = data?.total ?? 0;

  const loadMoreHandler = useCallback(() => setIsLoadedMore(true), []);

  const options: DropdownOption<string>[] = useMemo(() => {
    return (data?.content || []).map((dataset) => ({
      value: dataset.id,
      label: dataset.name,
    }));
  }, [data?.content]);

  return (
    <LoadableSelectBox
      options={options}
      value={value}
      placeholder={resolvedPlaceholder}
      onChange={onValueChange}
      onLoadMore={
        total > DEFAULT_LOADED_DATASET_ITEMS && !isLoadedMore
          ? loadMoreHandler
          : undefined
      }
      buttonClassName={className}
      isLoading={isLoading}
      optionsCount={DEFAULT_LOADED_DATASET_ITEMS}
    />
  );
};

export default DatasetSelectBox;
