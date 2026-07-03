import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";

import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import Autocomplete from "@/shared/Autocomplete/Autocomplete";
import { useErrorTypeOptions } from "./useErrorTypeOptions";

type ErrorTypeAutocompleteProps = {
  projectId: string | "";
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  type?: TRACE_DATA_TYPE;
};

const ErrorTypeAutocomplete: React.FC<ErrorTypeAutocompleteProps> = ({
  projectId,
  hasError,
  value,
  onValueChange,
  type = TRACE_DATA_TYPE.traces,
}) => {
  const { t } = useTranslation();
  const { items: allItems, isLoading } = useErrorTypeOptions({
    projectId,
    type,
  });

  const items = useMemo(
    () =>
      allItems.filter((t) =>
        value ? t.toLowerCase().includes(value.toLowerCase()) : true,
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
      placeholder={t("tracing.errorTypeAutocomplete.selectErrorType")}
    />
  );
};

export default ErrorTypeAutocomplete;
