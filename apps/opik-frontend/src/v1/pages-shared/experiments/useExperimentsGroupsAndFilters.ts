import i18next from "i18next";
import { useMemo } from "react";
import { JsonParam } from "use-query-params";
import { ColumnSort } from "@tanstack/react-table";

import { Groups } from "@/types/groups";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_PROJECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import DatasetSelectBox from "@/v1/pages-shared/experiments/DatasetSelectBox/DatasetSelectBox";
import ProjectsSelectBox from "@/v1/pages-shared/automations/ProjectsSelectBox";
import ExperimentsPathsAutocomplete from "@/v1/pages-shared/experiments/ExperimentsPathsAutocomplete/ExperimentsPathsAutocomplete";
import { Filters } from "@/types/filters";
import { GroupedExperiment } from "@/hooks/useGroupedExperimentsList";

export const FILTER_AND_GROUP_COLUMNS: ColumnData<GroupedExperiment>[] = [
  {
    id: COLUMN_PROJECT_ID,
    label: i18next.t("experiments.experiments.filterColumns.project"),
    type: COLUMN_TYPE.string,
    disposable: true,
  },
  {
    id: COLUMN_DATASET_ID,
    label: i18next.t("experiments.experiments.filterColumns.testSuite"),
    type: COLUMN_TYPE.string,
    disposable: true,
  },
  {
    id: "tags",
    label: i18next.t("experiments.experiments.filterColumns.tags"),
    type: COLUMN_TYPE.list,
    iconType: "tags",
  },
  {
    id: COLUMN_METADATA_ID,
    label: i18next.t("experiments.experiments.filterColumns.configuration"),
    type: COLUMN_TYPE.dictionary,
  },
];

const DEFAULT_GROUPS: Groups = [];

export type UseExperimentsGroupsAndFiltersProps = {
  storageKeyPrefix: string;
  sortedColumns: ColumnSort[];
  filters: Filters;
  promptId?: string;
};

export const useExperimentsGroupsAndFilters = ({
  storageKeyPrefix,
  sortedColumns,
  filters,
  promptId,
}: UseExperimentsGroupsAndFiltersProps) => {
  const [groups, setGroups] = useQueryParamAndLocalStorageState<Groups>({
    localStorageKey: `${storageKeyPrefix}-columns-groups`,
    queryKey: `groups`,
    defaultValue: DEFAULT_GROUPS,
    queryParamConfig: JsonParam,
  });

  const filtersAndGroupsConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_PROJECT_ID]: {
          keyComponent: ProjectsSelectBox,
          keyComponentProps: {
            className: "w-full min-w-72",
          },
          defaultOperator: "=",
          operators: [{ label: "=", value: "=" }],
          sortingMessage: i18next.t("experiments.experiments.filterColumns.lastUpdatedAt"),
        },
        [COLUMN_DATASET_ID]: {
          keyComponent: DatasetSelectBox,
          keyComponentProps: {
            className: "w-full min-w-72",
          },
          defaultOperator: "=",
          operators: [{ label: "=", value: "=" }],
          sortingMessage: i18next.t("experiments.experiments.filterColumns.lastExperimentCreated"),
        },
        [COLUMN_METADATA_ID]: {
          keyComponent: ExperimentsPathsAutocomplete,
          keyComponentProps: {
            placeholder: "key",
            excludeRoot: true,
            ...(promptId && { promptId }),
            sorting: sortedColumns,
            filters,
          },
        },
      },
    }),
    [filters, sortedColumns, promptId],
  );

  return {
    groups,
    setGroups,
    filtersAndGroupsConfig,
  };
};
