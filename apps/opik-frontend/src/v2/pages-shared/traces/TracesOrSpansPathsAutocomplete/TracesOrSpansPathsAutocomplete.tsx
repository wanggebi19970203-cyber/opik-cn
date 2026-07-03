import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";

import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import Autocomplete from "@/shared/Autocomplete/Autocomplete";
import {
  TRACE_AUTOCOMPLETE_ROOT_KEY,
  usePathsOptions,
} from "./usePathsOptions";

type TracesOrSpansPathsAutocompleteProps = {
  projectId: string | "";
  rootKeys: TRACE_AUTOCOMPLETE_ROOT_KEY[];
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  type?: TRACE_DATA_TYPE;
  placeholder?: string;
  excludeRoot?: boolean;
  datasetColumnNames?: string[];
  includeIntermediateNodes?: boolean;
};

const TracesOrSpansPathsAutocomplete: React.FC<
  TracesOrSpansPathsAutocompleteProps
> = ({
  projectId,
  rootKeys,
  hasError,
  value,
  onValueChange,
  type = TRACE_DATA_TYPE.traces,
  placeholder,
  excludeRoot = false,
  datasetColumnNames,
  includeIntermediateNodes = false,
}) => {
  const { t } = useTranslation("tracing");
  const defaultPlaceholder =
    type === TRACE_DATA_TYPE.spans
      ? t("tracesOrSpansPaths.selectKeyFromRecentSpan")
      : t("tracesOrSpansPaths.selectKeyFromRecentTrace");
  const finalPlaceholder = placeholder ?? defaultPlaceholder;

  const { items: allItems, isLoading } = usePathsOptions({
    projectId,
    type,
    rootKeys,
    excludeRoot,
    includeIntermediateNodes,
    datasetColumnNames,
  });

  const items = useMemo(
    () =>
      allItems.filter((p) =>
        value ? p.toLowerCase().includes(value.toLowerCase()) : true,
      ),
    [allItems, value],
  );

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      hasError={hasError}
      isLoading={isLoading}
      placeholder={finalPlaceholder}
    />
  );
};

export default TracesOrSpansPathsAutocomplete;
