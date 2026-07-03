import React, { useCallback, useMemo } from "react";
import isUndefined from "lodash/isUndefined";
import get from "lodash/get";
import sortBy from "lodash/sortBy";
import {
  ColumnDef,
  ColumnPinningState,
  createColumnHelper,
} from "@tanstack/react-table";
import { useTranslation } from "react-i18next";

import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_COMMENTS_ID,
  COLUMN_DURATION_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  COLUMN_USAGE_ID,
  ColumnData,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableVirtualBody from "@/shared/DataTable/DataTableVirtualBody";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import SearchInput from "@/shared/SearchInput/SearchInput";
import IdCell from "@/shared/DataTableCells/IdCell";
import AutodetectCell from "@/shared/DataTableCells/AutodetectCell";
import CompareExperimentsOutputCell from "@/v1/pages-shared/experiments/CompareExperimentsOutputCell/CompareExperimentsOutputCell";
import CompareExperimentsFeedbackScoreCell from "@/v1/pages-shared/experiments/CompareExperimentsFeedbackScoreCell/CompareExperimentsFeedbackScoreCell";
import TraceDetailsPanel from "@/v1/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import CompareExperimentsPanel from "@/v1/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsPanel";
import CompareExperimentsActionsPanel from "@/v1/pages/CompareExperimentsPage/CompareExperimentsActionsPanel";
import CompareExperimentsNameCell from "@/v1/pages-shared/experiments/CompareExperimentsNameCell/CompareExperimentsNameCell";
import CompareExperimentsNameHeader from "@/v1/pages-shared/experiments/CompareExperimentsNameHeader/CompareExperimentsNameHeader";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import Loader from "@/shared/Loader/Loader";
import ExplainerCallout from "@/shared/ExplainerCallout/ExplainerCallout";
import useAppStore from "@/store/AppStore";
import { Experiment, ExperimentsCompare } from "@/types/datasets";
import { useDatasetIdFromCompareExperimentsURL } from "@/hooks/useDatasetIdFromCompareExperimentsURL";
import { useTruncationEnabled } from "@/contexts/server-sync-provider";
import {
  convertColumnDataToColumn,
  hasAnyVisibleColumns,
  isColumnSortable,
  mapColumnDataFields,
} from "@/lib/table";
import { Separator } from "@/ui/separator";
import FeedbackScoreHeader from "@/shared/DataTableHeaders/FeedbackScoreHeader";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import ExperimentsFeedbackScoresSelect from "@/v1/pages-shared/experiments/ExperimentsFeedbackScoresSelect/ExperimentsFeedbackScoresSelect";
import {
  calculateHeightStyle,
  generateSelectColumDef,
} from "@/shared/DataTable/utils";
import { calculateLineHeight } from "@/lib/experiments";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import SectionHeader from "@/shared/DataTableHeaders/SectionHeader";
import CommentsCell from "@/shared/DataTableCells/CommentsCell";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/v1/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v1/constants/explainers";
import DurationCell from "@/shared/DataTableCells/DurationCell";
import CostCell from "@/shared/DataTableCells/CostCell";
import useExperimentItemsState from "@/v1/pages-shared/experiments/useExperimentItemsState";
import useExperimentItemsData from "@/v1/pages-shared/experiments/useExperimentItemsData";
import useExperimentItemsSidebar from "@/v1/pages-shared/experiments/useExperimentItemsSidebar";
import PassedCell from "@/v1/pages-shared/experiments/TestSuiteExperiment/PassedCell";
import TestSuiteExperimentPanel from "@/v1/pages-shared/experiments/TestSuiteExperiment/ExperimentItemSidebar/TestSuiteExperimentPanel";

const getRowId = (d: ExperimentsCompare) => d.id;

const calculateVerticalAlignment = (count: number) =>
  count === 1 ? undefined : CELL_VERTICAL_ALIGNMENT.start;

const columnHelper = createColumnHelper<ExperimentsCompare>();

const COLUMN_EXPERIMENT_NAME_ID = "experiment_name";
const COLUMN_PASSED_ID = "passed";
const STORAGE_PREFIX = "compare-experiments";
const DYNAMIC_COLUMNS_KEY = "compare-experiments-dynamic-columns";

function getFilterColumns(
  isTestSuite: boolean,
  t: (key: string) => string,
): ColumnData<ExperimentsCompare>[] {
  return [
    {
      id: COLUMN_ID_ID,
      label: isTestSuite ? t("compareExperiments.items.testSuiteItemId") : t("compareExperiments.items.datasetItemId"),
      type: COLUMN_TYPE.string,
    },
    {
      id: COLUMN_DURATION_ID,
      label: t("compareExperiments.items.duration"),
      type: COLUMN_TYPE.duration,
    },
    {
      id: COLUMN_COMMENTS_ID,
      label: t("compareExperiments.items.comments"),
      type: COLUMN_TYPE.string,
    },
    {
      id: "output",
      label: t("compareExperiments.items.evaluationTask"),
      type: COLUMN_TYPE.string,
    },
    {
      id: COLUMN_FEEDBACK_SCORES_ID,
      label: t("compareExperiments.items.feedbackScores"),
      type: COLUMN_TYPE.numberDictionary,
    },
  ];
}

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_DURATION_ID,
  `${COLUMN_USAGE_ID}.total_tokens`,
  "total_estimated_cost",
];

export type ExperimentItemsTabProps = {
  experimentsIds: string[];
  experiments?: Experiment[];
  isTestSuite?: boolean;
  datasetId?: string;
};

const ExperimentItemsTab: React.FunctionComponent<ExperimentItemsTabProps> = ({
  experimentsIds = [],
  experiments,
  isTestSuite,
  datasetId: datasetIdProp,
}) => {
  const { t } = useTranslation("compare-experiments");
  const datasetIdFromURL = useDatasetIdFromCompareExperimentsURL();
  const datasetId = datasetIdProp ?? datasetIdFromURL;
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const truncationEnabled = useTruncationEnabled();

  const {
    page,
    setPage,
    size,
    setSize,
    height,
    setHeight,
    search,
    setSearch,
    filters,
    setFilters,
    columnsWidth,
    setColumnsWidth,
    selectedColumns,
    setSelectedColumns,
    columnsOrder,
    setColumnsOrder,
    scoresColumnsOrder,
    setScoresColumnsOrder,
    outputColumnsOrder,
    setOutputColumnsOrder,
    sorting,
    setSorting,
    rowSelection,
    setRowSelection,
  } = useExperimentItemsState({
    storagePrefix: STORAGE_PREFIX,
    defaultSelectedColumns: DEFAULT_SELECTED_COLUMNS,
  });

  const {
    rows,
    total,
    sortableColumns,
    columnsStatistic,
    dynamicDatasetColumns,
    dynamicOutputColumns,
    dynamicScoresColumns,
    isPending,
    isFetching,
    isPlaceholderData,
    refetchExportData,
  } = useExperimentItemsData({
    workspaceName,
    datasetId,
    experimentsIds,
    filters,
    sorting,
    search: search as string,
    page: page as number,
    size: size as number,
    truncate: truncationEnabled,
    setSelectedColumns,
    dynamicColumnsKey: DYNAMIC_COLUMNS_KEY,
  });

  const {
    activeRowId,
    activeRow,
    traceId,
    setTraceId,
    spanId,
    setSpanId,
    handleRowClick,
    handleRowChange,
    handleClose,
    hasNext,
    hasPrevious,
    setExpandedCommentSections,
  } = useExperimentItemsSidebar(rows);

  const experimentsCount = experimentsIds.length;
  const noDataText = t("compareExperiments.items.noData");

  const columnPinning = useMemo<ColumnPinningState>(
    () => ({
      left: [COLUMN_SELECT_ID],
      right: isTestSuite ? [COLUMN_PASSED_ID] : [],
    }),
    [isTestSuite],
  );

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: ExperimentsFeedbackScoresSelect,
          keyComponentProps: {
            experimentsIds,
            placeholder: t("compareExperiments.items.selectScore"),
          },
        },
      },
    }),
    [experimentsIds],
  );

  const datasetColumnsData = useMemo(() => {
    return [
      {
        id: COLUMN_ID_ID,
        label: t("compareExperiments.items.datasetItemId"),
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
        verticalAlignment: calculateVerticalAlignment(experimentsCount),
        size: 180,
        sortable: isColumnSortable(COLUMN_ID_ID, sortableColumns),
        explainer: EXPLAINERS_MAP[EXPLAINER_ID.whats_the_test_suite_item],
      } as ColumnData<ExperimentsCompare>,
      ...dynamicDatasetColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            accessorFn: (row) => get(row, ["data", label], ""),
            cell: AutodetectCell as never,
            verticalAlignment: calculateVerticalAlignment(experimentsCount),
            overrideRowHeight:
              experimentsCount === 1 ? undefined : ROW_HEIGHT.large,
            ...(columnType === COLUMN_TYPE.dictionary && { size: 400 }),
          }) as ColumnData<ExperimentsCompare>,
      ),
    ];
  }, [dynamicDatasetColumns, experimentsCount, sortableColumns, t]);

  const outputColumnsData = useMemo(() => {
    return [
      ...dynamicOutputColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            cell: CompareExperimentsOutputCell as never,
            customMeta: {
              experiments,
              experimentsIds,
              outputKey: label,
              openTrace: setTraceId,
            },
            ...(columnType === COLUMN_TYPE.dictionary && { size: 400 }),
          }) as ColumnData<ExperimentsCompare>,
      ),
      {
        id: COLUMN_DURATION_ID,
        label: t("compareExperiments.items.duration"),
        type: COLUMN_TYPE.duration,
        cell: DurationCell.Compare as never,
        statisticKey: "duration",
        statisticDataFormater: formatDuration,
        statisticTooltipFormater: formatDuration,
        customMeta: {
          experimentsIds,
        },
      },
      {
        id: `${COLUMN_USAGE_ID}.total_tokens`,
        label: t("compareExperiments.items.totalTokens"),
        type: COLUMN_TYPE.number,
        cell: CostCell.Compare as never,
        statisticKey: `${COLUMN_USAGE_ID}.total_tokens`,
        supportsPercentiles: true,
        customMeta: {
          experimentsIds,
          accessor: `${COLUMN_USAGE_ID}.total_tokens`,
          formatter: (value: number) => value.toLocaleString(),
        },
      },
      {
        id: "total_estimated_cost",
        label: t("compareExperiments.items.estimatedCost"),
        type: COLUMN_TYPE.cost,
        cell: CostCell.Compare as never,
        statisticKey: "total_estimated_cost",
        statisticDataFormater: formatCost,
        statisticTooltipFormater: (value: number) =>
          formatCost(value, { modifier: "full" }),
        supportsPercentiles: true,
        customMeta: {
          experimentsIds,
          accessor: "total_estimated_cost",
        },
      },
      {
        id: COLUMN_COMMENTS_ID,
        label: t("compareExperiments.items.comments"),
        type: COLUMN_TYPE.string,
        cell: CommentsCell.Compare as never,
        sortable: isColumnSortable(COLUMN_COMMENTS_ID, sortableColumns),
        customMeta: {
          experimentsIds,
        },
      } as ColumnData<ExperimentsCompare>,
    ];
  }, [
    dynamicOutputColumns,
    experiments,
    experimentsIds,
    setTraceId,
    sortableColumns,
    t,
  ]);

  const scoresColumnsData = useMemo(() => {
    return dynamicScoresColumns.map(
      ({ label, id, columnType }) =>
        ({
          id,
          label,
          type: columnType,
          header: FeedbackScoreHeader as never,
          cell: CompareExperimentsFeedbackScoreCell as never,
          statisticKey: `${COLUMN_FEEDBACK_SCORES_ID}.${label}`,
          statisticDataFormater: formatScoreDisplay,
          supportsPercentiles: true,
          customMeta: {
            experimentsIds,
            scoreName: label,
          },
        }) as ColumnData<ExperimentsCompare>,
    );
  }, [dynamicScoresColumns, experimentsIds]);

  const selectedRows: Array<ExperimentsCompare> = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const getDataForExport = useCallback(async (): Promise<
    ExperimentsCompare[]
  > => {
    const result = await refetchExportData();

    if (result.error) {
      throw result.error;
    }

    if (!result.data?.content) {
      throw new Error("Failed to fetch data");
    }

    const allRows = result.data.content;
    const selectedIds = Object.keys(rowSelection);

    return allRows.filter((row) => selectedIds.includes(row.id));
  }, [refetchExportData, rowSelection]);

  const columns = useMemo(() => {
    const retVal = [
      generateSelectColumDef<ExperimentsCompare>({
        verticalAlignment: calculateVerticalAlignment(experimentsCount),
      }),
    ];

    if (hasAnyVisibleColumns(datasetColumnsData, selectedColumns)) {
      retVal.push(
        columnHelper.group({
          id: "dataset",
          meta: {
            header: isTestSuite ? t("compareExperiments.items.testSuite") : t("compareExperiments.items.dataset"),
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            ExperimentsCompare,
            ExperimentsCompare
          >(datasetColumnsData, {
            selectedColumns,
            columnsOrder,
            sortableColumns,
          }),
        }),
      );
    }

    if (experimentsCount > 1) {
      retVal.push(
        columnHelper.group({
          id: "experiments",
          meta: {
            header: t("compareExperiments.items.experiments"),
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            ExperimentsCompare,
            ExperimentsCompare
          >(
            [
              {
                id: COLUMN_EXPERIMENT_NAME_ID,
                label: t("compareExperiments.items.name"),
                header: CompareExperimentsNameHeader as never,
                cell: CompareExperimentsNameCell as never,
                customMeta: {
                  experiments,
                  experimentsIds,
                },
              },
            ],
            {},
          ),
        }),
      );
    }

    if (hasAnyVisibleColumns(outputColumnsData, selectedColumns)) {
      retVal.push(
        columnHelper.group({
          id: "evaluation",
          meta: {
            header: t("compareExperiments.items.evaluationTaskLastTrial"),
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            ExperimentsCompare,
            ExperimentsCompare
          >(outputColumnsData, {
            selectedColumns,
            columnsOrder: outputColumnsOrder,
            sortableColumns,
          }),
        }),
      );
    }

    if (hasAnyVisibleColumns(scoresColumnsData, selectedColumns)) {
      retVal.push(
        columnHelper.group({
          id: "scores",
          meta: {
            header: t("compareExperiments.items.feedbackScores"),
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            ExperimentsCompare,
            ExperimentsCompare
          >(scoresColumnsData, {
            selectedColumns,
            columnsOrder: scoresColumnsOrder,
            sortableColumns,
          }),
        }),
      );
    }

    if (isTestSuite) {
      retVal.push(
        mapColumnDataFields<ExperimentsCompare, ExperimentsCompare>({
          id: COLUMN_PASSED_ID,
          label: t("compareExperiments.items.result"),
          iconType: "result" as const,
          type: COLUMN_TYPE.string,
          cell: PassedCell as never,
          size: 140,
          customMeta: {
            experimentsIds,
          },
        }),
      );
    }

    return retVal;
  }, [
    experimentsCount,
    isTestSuite,
    datasetColumnsData,
    selectedColumns,
    outputColumnsData,
    scoresColumnsData,
    columnsOrder,
    experiments,
    experimentsIds,
    outputColumnsOrder,
    scoresColumnsOrder,
    sortableColumns,
    t,
  ]);

  const columnsToExport = useMemo(() => {
    return columns
      .reduce<Array<ColumnDef<ExperimentsCompare>>>((acc, c) => {
        const subColumns = get(c, "columns");
        return acc.concat(subColumns ? subColumns : [c]);
      }, [])
      .map((c) => get(c, "accessorKey", ""))
      .filter((c) =>
        c === COLUMN_SELECT_ID
          ? false
          : selectedColumns.includes(c) ||
            (DEFAULT_COLUMN_PINNING.left || []).includes(c),
      );
  }, [columns, selectedColumns]);

  const filterColumns = useMemo(() => {
    return [
      ...sortBy(dynamicDatasetColumns, "label").map(
        ({ id, label, columnType }) => ({
          id,
          label: `${label} (${isTestSuite ? t("compareExperiments.items.testSuite") : t("compareExperiments.items.dataset")})`,
          type: columnType,
        }),
      ),
      ...sortBy(dynamicOutputColumns, "label").map(({ id, label }) => ({
        id,
        label: `${label} (${t("compareExperiments.items.output")})`,
        type: COLUMN_TYPE.string,
      })),
      ...getFilterColumns(!!isTestSuite, t),
    ];
  }, [dynamicDatasetColumns, dynamicOutputColumns, isTestSuite, t]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const getRowHeightStyle = useCallback(
    (height: ROW_HEIGHT) =>
      experimentsCount > 1
        ? calculateLineHeight(height, experimentsCount)
        : calculateHeightStyle(height),
    [experimentsCount],
  );

  const columnSections = useMemo(() => {
    return [
      {
        title: t("compareExperiments.items.evaluationTask"),
        columns: outputColumnsData,
        order: outputColumnsOrder,
        onOrderChange: setOutputColumnsOrder,
      },
      {
        title: t("compareExperiments.items.feedbackScores"),
        columns: scoresColumnsData,
        order: scoresColumnsOrder,
        onOrderChange: setScoresColumnsOrder,
      },
    ];
  }, [
    outputColumnsData,
    outputColumnsOrder,
    setOutputColumnsOrder,
    scoresColumnsData,
    scoresColumnsOrder,
    setScoresColumnsOrder,
    t,
  ]);

  const meta = useMemo(
    () => ({
      onCommentsReply: (row: ExperimentsCompare, idx?: number) => {
        handleRowClick(row);

        if (isUndefined(idx)) return;

        setExpandedCommentSections([String(idx)]);
      },
      columnsStatistic,
      enableUserFeedbackEditing: true,
    }),
    [handleRowClick, setExpandedCommentSections, columnsStatistic],
  );

  const sharedPanelProps = useMemo(
    () => ({
      experimentsCompareId: activeRowId,
      experimentsCompare: activeRow,
      experimentsIds,
      experiments,
      hasPreviousRow: hasPrevious,
      hasNextRow: hasNext,
      openTrace: setTraceId as OnChangeFn<string>,
      onClose: handleClose,
      onRowChange: handleRowChange,
      isTraceDetailsOpened: Boolean(traceId),
    }),
    [
      activeRowId,
      activeRow,
      experimentsIds,
      experiments,
      hasPrevious,
      hasNext,
      setTraceId,
      handleClose,
      handleRowChange,
      traceId,
    ],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <ExplainerCallout
          className="mb-4"
          {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_experiment_items]}
        />
      </PageBodyStickyContainer>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search!}
            setSearchText={setSearch}
            placeholder={
              isTestSuite ? t("compareExperiments.items.searchTestSuiteItems") : t("compareExperiments.items.searchDatasetItems")
            }
            className="w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={filterColumns}
            config={filtersConfig as never}
            filters={filters}
            onChange={setFilters}
            layout="icon"
          />
        </div>
        <div className="flex items-center gap-2">
          <CompareExperimentsActionsPanel
            getDataForExport={getDataForExport}
            selectedRows={selectedRows}
            columnsToExport={columnsToExport}
            experiments={experiments}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={datasetColumnsData}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
            sections={columnSections}
          ></ColumnsButton>
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        sortConfig={{
          enabled: true,
          sorting,
          setSorting,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        getRowHeightStyle={getRowHeightStyle}
        columnPinning={columnPinning}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        TableBody={DataTableVirtualBody}
        stickyHeader
        meta={meta}
        showLoadingOverlay={isPlaceholderData && isFetching}
      />
      <PageBodyStickyContainer
        className="py-4"
        direction="horizontal"
        limitWidth
      >
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={total}
          supportsTruncation
          truncationEnabled={truncationEnabled}
        />
      </PageBodyStickyContainer>
      <TestSuiteExperimentPanel
        {...sharedPanelProps}
        experimentsCompareId={
          isTestSuite ? sharedPanelProps.experimentsCompareId : undefined
        }
        datasetId={datasetId ?? ""}
      />
      <CompareExperimentsPanel
        {...sharedPanelProps}
        experimentsCompareId={
          !isTestSuite ? sharedPanelProps.experimentsCompareId : undefined
        }
      />
      <TraceDetailsPanel
        traceId={traceId!}
        spanId={spanId!}
        setSpanId={setSpanId}
        open={Boolean(traceId)}
        onClose={() => {
          setTraceId("");
          setSpanId("");
        }}
      />
    </>
  );
};

export default ExperimentItemsTab;
