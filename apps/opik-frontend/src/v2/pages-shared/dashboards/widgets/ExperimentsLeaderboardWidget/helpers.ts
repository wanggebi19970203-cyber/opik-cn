import uniq from "lodash/uniq";
import get from "lodash/get";
import i18next from "i18next";

import { Experiment } from "@/types/datasets";
import { formatPromptVersionLabel } from "@/lib/experiments";
import {
  COLUMN_TYPE,
  ColumnData,
  COLUMN_ID_ID,
  COLUMN_DATASET_ID,
} from "@/types/shared";
import { Filters } from "@/types/filters";
import { createFilter, isFilterValid } from "@/lib/filters";
import IdCell from "@/shared/DataTableCells/IdCell";
import DurationCell from "@/shared/DataTableCells/DurationCell";
import CostCell from "@/shared/DataTableCells/CostCell";
import PassRateCell from "@/shared/DataTableCells/PassRateCell";
import ItemSourceCell, {
  ITEM_SOURCE_LABEL,
} from "@/v2/pages-shared/experiments/ItemSourceCell";
import MultiResourceCell from "@/shared/DataTableCells/MultiResourceCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import { getJSONPaths } from "@/lib/utils";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import { parseScoreColumnId } from "@/lib/feedback-scores";
import { DEFAULT_MAX_EXPERIMENTS } from "@/lib/dashboard/utils";

export {
  parseScoreColumnId,
  getExperimentScore,
  buildScoreLabel,
} from "@/lib/feedback-scores";

interface ExperimentListParamsInput {
  experimentIds: string[];
  filters: Filters;
}

export const getExperimentListParams = ({
  experimentIds,
  filters,
}: ExperimentListParamsInput) => {
  const hasExperimentIds = experimentIds.length > 0;
  const validFilters = filters.filter(isFilterValid);
  return {
    experimentIds: hasExperimentIds ? experimentIds : undefined,
    filters: validFilters.length > 0 ? validFilters : undefined,
  };
};

export const PREDEFINED_COLUMNS: ColumnData<Experiment>[] = [
  {
    id: COLUMN_ID_ID,
    label: i18next.t("common:labels.id"),
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: COLUMN_DATASET_ID,
    label: ITEM_SOURCE_LABEL,
    type: COLUMN_TYPE.string,
    cell: ItemSourceCell as never,
    customMeta: {
      nameKey: "dataset_name",
      idKey: "dataset_id",
    },
  },
  {
    id: "tags",
    label: i18next.t("common:labels.tags"),
    type: COLUMN_TYPE.list,
    iconType: "tags",
    accessorFn: (row) => row.tags || [],
    cell: ListCell as never,
  },
  {
    id: "created_at",
    label: i18next.t("common:experimentLabels.created"),
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: i18next.t("common:experimentLabels.createdBy"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "duration.p50",
    label: i18next.t("common:experimentLabels.avgDuration"),
    type: COLUMN_TYPE.duration,
    accessorFn: (row) => row.duration?.p50,
    cell: DurationCell as never,
  },
  {
    id: "duration.p90",
    label: i18next.t("common:experimentLabels.durationP90"),
    type: COLUMN_TYPE.duration,
    accessorFn: (row) => row.duration?.p90,
    cell: DurationCell as never,
  },
  {
    id: "duration.p99",
    label: i18next.t("common:experimentLabels.durationP99"),
    type: COLUMN_TYPE.duration,
    accessorFn: (row) => row.duration?.p99,
    cell: DurationCell as never,
  },
  {
    id: "prompt",
    label: i18next.t("common:experimentLabels.prompt"),
    type: COLUMN_TYPE.list,
    // Show the prompt name plus its version (OPIK-6838).
    accessorFn: (row) =>
      (row.prompt_versions ?? []).map((v) => ({
        ...v,
        version_label: formatPromptVersionLabel(v),
      })),
    cell: MultiResourceCell as never,
    customMeta: {
      nameKey: "version_label",
      idKey: "prompt_id",
      resource: RESOURCE_TYPE.prompt,
      getSearch: (data: Experiment) => ({
        activeVersionId: get(data, "id", null),
      }),
    },
  },
  {
    id: "trace_count",
    label: i18next.t("common:experimentLabels.traceCount"),
    type: COLUMN_TYPE.number,
  },
  {
    id: "total_estimated_cost",
    label: i18next.t("common:experimentLabels.totalEstimatedCost"),
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
  },
  {
    id: "total_estimated_cost_avg",
    label: i18next.t("common:experimentLabels.avgCost"),
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
  },
  {
    id: "pass_rate",
    label: i18next.t("common:experimentLabels.passRate"),
    type: COLUMN_TYPE.number,
    iconType: "pass_rate",
    accessorFn: (row) => row.pass_rate,
    cell: PassRateCell as never,
  },
];

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_DATASET_ID,
  "created_at",
  "duration.p50",
  "pass_rate",
];

export const getDefaultConfig = () => ({
  filters: [],
  selectedColumns: DEFAULT_SELECTED_COLUMNS,
  enableRanking: false,
  columnsOrder: DEFAULT_SELECTED_COLUMNS,
  scoresColumnsOrder: [],
  metadataColumnsOrder: [],
  columnsWidth: {},
  maxRows: DEFAULT_MAX_EXPERIMENTS,
  sorting: [],
});

export const parseMetadataKeys = (experiments: Experiment[]): string[] => {
  const allKeys = experiments.reduce<string[]>((acc, exp) => {
    if (exp.metadata) {
      const paths = getJSONPaths(exp.metadata, "", [], true);
      acc.push(...paths);
    }
    return acc;
  }, []);

  return uniq(allKeys).sort();
};

export const formatConfigColumnName = (key: string): string => {
  return `config.${key}`;
};

interface GetRankingSortingParams {
  rankingMetric?: string;
  rankingDirection?: boolean;
}

export const getRankingSorting = ({
  rankingMetric,
  rankingDirection = true,
}: GetRankingSortingParams) => {
  if (!rankingMetric) return undefined;
  return [{ id: rankingMetric, desc: rankingDirection }];
};

export const getRankingFilters = (
  rankingMetric: string | undefined,
  existingFilters: Filters = [],
): Filters => {
  if (!rankingMetric) return existingFilters;

  const parsedScore = parseScoreColumnId(rankingMetric);

  return parsedScore
    ? [
        ...existingFilters,
        createFilter({
          id: `ranking-filter-${rankingMetric}`,
          field: parsedScore.scoreType,
          operator: "is_not_empty",
          key: parsedScore.scoreName,
        }),
      ]
    : existingFilters;
};

export const widgetHelpers = {
  getDefaultConfig,
  calculateTitle: () =>
    i18next.t("common:experimentLabels.experimentLeaderboard"),
};
