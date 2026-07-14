import React, { useCallback, useMemo, useRef, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import {
  ColumnDef,
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";
import { useTranslation } from "react-i18next";

import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";

import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import Loader from "@/shared/Loader/Loader";
import SearchInput from "@/shared/SearchInput/SearchInput";
import FeedbackScoreListCell from "@/shared/DataTableCells/FeedbackScoreListCell";
import IdCell from "@/shared/DataTableCells/IdCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import TextCell from "@/shared/DataTableCells/TextCell";
import ResourceCell from "@/shared/DataTableCells/ResourceCell";
import TagCell from "@/shared/DataTableCells/TagCell";
import AnnotateQueueCell from "@/v1/pages-shared/annotation-queues/AnnotateQueueCell";
import AnnotationQueueProgressCell from "@/v1/pages-shared/annotation-queues/AnnotationQueueProgressCell";
import AnnotationQueueRowActionsCell from "@/v1/pages-shared/annotation-queues/AnnotationQueueRowActionsCell";
import AnnotationQueuesActionsPanel from "@/v1/pages-shared/annotation-queues/AnnotationQueuesActionsPanel";
import AddEditAnnotationQueueDialog from "@/v1/pages-shared/annotation-queues/AddEditAnnotationQueueDialog";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v1/constants/explainers";
import NoDataPage from "@/shared/NoDataPage/NoDataPage";
import NoAnnotationQueuesPage from "@/v1/pages-shared/annotation-queues/NoAnnotationQueuesPage";
import ProjectsSelectBox from "@/v1/pages-shared/automations/ProjectsSelectBox";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";

import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import {
  generateActionsColumDef,
  generateSelectColumDef,
  getRowId,
} from "@/shared/DataTable/utils";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import useAppStore from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_PROJECT_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  ROW_HEIGHT,
} from "@/types/shared";
import {
  AnnotationQueue,
  ANNOTATION_QUEUE_SCOPE,
} from "@/types/annotation-queues";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { capitalizeFirstLetter } from "@/lib/utils";

const SHARED_COLUMNS: ColumnData<AnnotationQueue>[] = [
  {
    id: COLUMN_ID_ID,
    label: "annotationQueues.columns.id",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: COLUMN_PROJECT_ID,
    label: "annotationQueues.columns.project",
    type: COLUMN_TYPE.string,
    cell: ResourceCell as never,
    accessorFn: (row) => row.project_id,
    customMeta: {
      nameKey: "project_name",
      idKey: "project_id",
      resource: RESOURCE_TYPE.project,
    },
  },
  {
    id: "instructions",
    label: "annotationQueues.columns.instructions",
    type: COLUMN_TYPE.string,
    size: 400,
  },
  {
    id: "scope",
    label: "annotationQueues.columns.scope",
    type: COLUMN_TYPE.category,
    cell: TagCell as never,
    accessorFn: (row) => capitalizeFirstLetter(row.scope),
    customMeta: {
      colored: false,
    },
  },
  {
    id: "created_at",
    label: "annotationQueues.columns.createdAt",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: "annotationQueues.columns.createdBy",
    type: COLUMN_TYPE.string,
  },
  {
    id: "last_updated_at",
    label: "annotationQueues.columns.lastUpdated",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
    sortable: true,
  },
];

const DEFAULT_COLUMNS: ColumnData<AnnotationQueue>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "annotationQueues.columns.name",
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
    sortable: true,
  },
  ...SHARED_COLUMNS,
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "annotationQueues.columns.avgFeedbackScores",
    type: COLUMN_TYPE.numberDictionary,
    accessorFn: (row) => row.feedback_scores ?? [],
    cell: FeedbackScoreListCell as never,
    customMeta: {
      getHoverCardName: (row: AnnotationQueue) => row.name,
      areAggregatedScores: true,
    },
  },
  {
    id: "items_count",
    label: "annotationQueues.columns.itemCount",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => (row.items_count ? `${row.items_count}` : "-"),
  },
  {
    id: "reviewers",
    label: "annotationQueues.columns.reviewedBy",
    type: COLUMN_TYPE.list,
    cell: ListCell as never,
    accessorFn: (row) => row.reviewers?.map((r) => r.username) ?? [],
  },
  {
    id: "progress",
    label: "annotationQueues.columns.progress",
    type: COLUMN_TYPE.string,
    cell: AnnotationQueueProgressCell as never,
  },
];

const FILTER_COLUMNS: ColumnData<AnnotationQueue>[] = [
  {
    id: COLUMN_NAME_ID,
    label: "annotationQueues.columns.name",
    type: COLUMN_TYPE.string,
  },
  ...SHARED_COLUMNS,
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  COLUMN_PROJECT_ID,
  "instructions",
  "items_count",
  "progress",
  COLUMN_FEEDBACK_SCORES_ID,
  "scope",
  "last_updated_at",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_PROJECT_ID,
  "instructions",
  "items_count",
  "progress",
  COLUMN_FEEDBACK_SCORES_ID,
  "scope",
  "last_updated_at",
  "created_at",
  "created_by",
  "reviewers",
];

const SELECTED_COLUMNS_KEY = "workspace-annotation-queues-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "workspace-annotation-queues-columns-width";
const COLUMNS_ORDER_KEY = "workspace-annotation-queues-columns-order";
const COLUMNS_SORT_KEY = "workspace-annotation-queues-columns-sort";
const PAGINATION_SIZE_KEY = "workspace-annotation-queues-pagination-size";
const ROW_HEIGHT_KEY = "workspace-annotation-queues-row-height";

const getFiltersConfig = (t: (key: string) => string) => ({
  rowsMap: {
    scope: {
      keyComponentProps: {
        options: [
          {
            value: ANNOTATION_QUEUE_SCOPE.TRACE,
            label: t("annotationQueues.filters.trace"),
          },
          {
            value: ANNOTATION_QUEUE_SCOPE.THREAD,
            label: t("annotationQueues.filters.thread"),
          },
        ],
        placeholder: t("annotationQueues.filters.selectScope"),
      },
    },
    [COLUMN_PROJECT_ID]: {
      keyComponent: ProjectsSelectBox,
      keyComponentProps: {
        className: "w-full min-w-72",
      },
      defaultOperator: "=",
      operators: [{ label: "=", value: "=" }],
    },
  },
});

export const AnnotationQueuesPage: React.FC = () => {
  const { t } = useTranslation();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);
  const {
    permissions: { canCreateAnnotationQueues, canDeleteAnnotationQueues },
  } = usePermissions();

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 10,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [height, setHeight] = useLocalStorageState<ROW_HEIGHT>(ROW_HEIGHT_KEY, {
    defaultValue: ROW_HEIGHT.small,
  });
  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY_V2,
    {
      defaultValue: migrateSelectedColumns(
        SELECTED_COLUMNS_KEY,
        DEFAULT_SELECTED_COLUMNS,
        [COLUMN_NAME_ID],
      ),
    },
  );
  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: DEFAULT_COLUMNS_ORDER,
    },
  );
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });
  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: [
        {
          id: "last_updated_at",
          desc: true,
        },
      ],
    },
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const {
    data,
    isPending: isLoading,
    isPlaceholderData,
    isFetching,
  } = useAnnotationQueuesList(
    {
      workspaceName,
      search: search as string,
      page: page as number,
      size: size as number,
      filters,
      sorting: sortedColumns,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const sortableBy = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const selectedRows = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const noData = !search && filters.length === 0;
  const noDataText = noData
    ? t("annotationQueues.noData.noQueuesYet")
    : t("annotationQueues.noData.noSearchResults");

  const handleNewQueue = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const handleRowClick = useCallback(
    (queue: AnnotationQueue) => {
      navigate({
        to: "/$workspaceName/annotation-queues/$annotationQueueId",
        params: {
          workspaceName,
          annotationQueueId: queue.id,
        },
      });
    },
    [navigate, workspaceName],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<AnnotationQueue>(),
      ...convertColumnDataToColumn<AnnotationQueue, AnnotationQueue>(
        DEFAULT_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
      {
        accessorKey: "annotate_queue",
        header: "",
        cell: AnnotateQueueCell,
        size: 140,
        enableResizing: false,
        enableHiding: false,
        enableSorting: false,
      } as ColumnDef<AnnotationQueue>,
      generateActionsColumDef({
        cell: AnnotationQueueRowActionsCell,
      }),
    ];
  }, [sortableBy, columnsOrder, selectedColumns]);

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [setSortedColumns, sortedColumns],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (isLoading) {
    return <Loader />;
  }

  if (noData && rows.length === 0 && page === 1) {
    return (
      <>
        <NoAnnotationQueuesPage
          openModal={handleNewQueue}
          Wrapper={NoDataPage}
        />
        <AddEditAnnotationQueueDialog
          key={resetDialogKeyRef.current}
          open={openDialog}
          setOpen={setOpenDialog}
        />
      </>
    );
  }

  return (
    <div className="pt-6">
      <div className="mb-1 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">
          {t("annotationQueues.title")}
        </h1>
      </div>
      <ExplainerDescription
        className="mb-4"
        {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_annotation_queues]}
      />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search as string}
            setSearchText={setSearch}
            placeholder={t("annotationQueues.search.byName")}
            className="w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={FILTER_COLUMNS}
            config={getFiltersConfig(t) as never}
            filters={filters}
            onChange={setFilters}
            layout="icon"
          />
        </div>
        <div className="flex items-center gap-2">
          {canDeleteAnnotationQueues && (
            <>
              <AnnotationQueuesActionsPanel queues={selectedRows} />
              <Separator orientation="vertical" className="mx-2 h-4" />
            </>
          )}
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
          {canCreateAnnotationQueues && (
            <Button size="sm" onClick={handleNewQueue}>
              {t("annotationQueues.page.createNewQueue")}
            </Button>
          )}
        </div>
      </div>
      <DataTable
        columns={columns}
        data={rows}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        onRowClick={handleRowClick}
        stickyHeader
        showLoadingOverlay={isPlaceholderData && isFetching}
      />
      <div className="py-4">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={data?.total ?? 0}
        />
      </div>

      <AddEditAnnotationQueueDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default AnnotationQueuesPage;
