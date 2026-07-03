import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";

import { Sorting } from "@/types/sorting";
import { getJSONPaths } from "@/lib/utils";
import Autocomplete from "@/shared/Autocomplete/Autocomplete";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { Filters } from "@/types/filters";

type ExperimentsPathsAutocompleteProps = {
  hasError?: boolean;
  value: string;
  onValueChange: (value: string) => void;
  promptId?: string;
  sorting?: Sorting;
  filters?: Filters;
  placeholder?: string;
  className?: string;
  excludeRoot?: boolean;
};

const ExperimentsPathsAutocomplete: React.FC<
  ExperimentsPathsAutocompleteProps
> = ({
  hasError,
  value,
  onValueChange,
  promptId,
  sorting,
  filters,
  placeholder,
  excludeRoot = false,
  className,
}) => {
  const { t } = useTranslation("experiments");
  const resolvedPlaceholder = placeholder ?? t("selectKeyFromRecentExperiments");
  const { data, isPending } = useExperimentsList({
    promptId,
    sorting,
    filters,
    page: 1,
    size: 100,
  });

  const items = useMemo(() => {
    const key = "metadata";

    return uniq(
      (data?.content || []).reduce<string[]>((acc, d) => {
        return acc.concat(
          isObject(d[key]) || isArray(d[key])
            ? getJSONPaths(d[key], key).map((path) =>
                excludeRoot ? path.substring(path.indexOf(".") + 1) : path,
              )
            : [],
        );
      }, []),
    )
      .filter((p) =>
        value ? p.toLowerCase().includes(value.toLowerCase()) : true,
      )
      .sort();
  }, [data, value, excludeRoot]);

  return (
    <Autocomplete
      value={value}
      onValueChange={onValueChange}
      items={items}
      hasError={hasError}
      isLoading={isPending}
      placeholder={resolvedPlaceholder}
      className={className}
    />
  );
};

export default ExperimentsPathsAutocomplete;
