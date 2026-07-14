import React, { useCallback, useMemo, useRef, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { Plus } from "lucide-react";
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
import SearchInput from "@/shared/SearchInput/SearchInput";
import FeedbackScoreListCell from "@/shared/DataTableCells/FeedbackScoreListCell";
import IdCell from "@/shared/DataTableCells/IdCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import TextCell from "@/shared/DataTableCells/TextCell";
import TagCell from "@/shared/DataTableCells/TagCell";
import AnnotateQueueCell from "@/v2/pages-shared/annotation-queues/AnnotateQueueCell";
import AnnotationQueueProgressCell from "@/v2/pages-shared/annotation-queues/AnnotationQueueProgressCell";
import AnnotationQueueRowActionsCell from "@/v2/pages-shared/annotation-queues/AnnotationQueueRowActionsCell";
import AnnotationQueuesActionsPanel from "@/v2/pages-shared/annotation-queues/AnnotationQueuesActionsPanel";
import AddEditAnnotationQueueDialog from "@/v2/pages-shared/annotation-queues/AddEditAnnotationQueueDialog";
import PageEmptyState from "@/shared/PageEmptyState/PageEmptyState";
import { buildDocsUrl } from "@/v2/lib/utils";
import emptyAnnotationQueuesLightUrl from "/images/empty-annotation-queues-light.svg";
import emptyAnnotationQueuesDarkUrl from "/images/empty-annotation-queues-dark.svg";

import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import {
  generateActionsColumDef,
  generateSelectColumDef,
  getRowId,
} from "@/shared/DataTable/utils";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import useAppStore from "@/store/AppStore";
import { useActiveProjectId } from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
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

const getSharedColumns = (
  t: (key: string) => string,
): ColumnData<AnnotationQueue>[] => [
  {
    id: COLUMN_ID_ID,
    label: t("annotationQueues.columns.id"),
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "instructions",
    label: t("annotationQueues.columns.instructions"),
    type: COLUMN_TYPE.string,
    size: 400,
  },
  {
    id: "scope",
    label: t("annotationQueues.columns.scope"),
    type: COLUMN_TYPE.category,
    cell: TagCell as never,
    accessorFn: (row) => capitalizeFirstLetter(row.scope),
    customMeta: {
      colored: false,
    },
  },
  {
    id: "created_at",
    label: t("annotationQueues.columns.createdAt"),
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: t("annotationQueues.columns.createdBy"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "last_updated_at",
    label: t("annotationQueues.columns.lastUpdated"),
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
    sortable: true,
  },
];

const getDefaultColumns = (
  t: (key: string) => string,
): ColumnData<AnnotationQueue>[] => [
  {
    id: COLUMN_NAME_ID,
    label: t("annotationQueues.columns.name"),
    type: COLUMN_TYPE.string,
    cell: TextCell as never,
    sortable: true,
  },
  ...getSharedColumns(t),
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: t("annotationQueues.columns.avgFeedbackScores"),
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
    label: t("annotationQueues.columns.itemCount"),
    type: COLUMN_TYPE.number,
    accessorFn: (row) => (row.items_count ? `${row.items_count}` : "-"),
  },
  {
    id: "reviewers",
    label: t("annotationQueues.columns.reviewedBy"),
    type: COLUMN_TYPE.list,
    cell: ListCell as never,
    accessorFn: (row) => row.reviewers?.map((r) => r.username) ?? [],
  },
  {
    id: "progress",
    label: t("annotationQueues.columns.progress"),
    type: COLUMN_TYPE.string,
    cell: AnnotationQueueProgressCell as never,
  },
];

const getFilterColumns = (
  t: (key: string) => string,
): ColumnData<AnnotationQueue>[] => [
  {
    id: COLUMN_NAME_ID,
    label: t("annotationQueues.columns.name"),
    type: COLUMN_TYPE.string,
  },
  ...getSharedColumns(t),
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
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
  },
});

export const AnnotationQueuesPage: React.FC = () => {
  const { t } = useTranslation("pages/annotation-queues");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const projectId = useActiveProjectId()!;
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
      projectId,
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
        to: "/$workspaceName/projects/$projectId/annotation-queues/$annotationQueueId",
        params: {
          workspaceName,
          projectId,
          annotationQueueId: queue.id,
        },
      });
    },
    [navigate, workspaceName, projectId],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<AnnotationQueue>(),
      ...convertColumnDataToColumn<AnnotationQueue, AnnotationQueue>(
        getDefaultColumns(t),
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
  }, [sortableBy, columnsOrder, selectedColumns, t]);

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

  const isTableLoading = isLoading || (isPlaceholderData && rows.length === 0);
  const isEmpty = !isTableLoading && noData && rows.length === 0 && page === 1;

  return (
    <div className="flex min-h-full flex-col pt-4">
      <div className="mb-4 flex min-h-7 items-center justify-between">
        <h1 className="comet-body-accented truncate break-words">
          {t("annotationQueues.title")}
        </h1>
        {canCreateAnnotationQueues && (
          <Button size="xs" onClick={handleNewQueue}>
            <Plus className="mr-1 size-4" />
            {t("annotationQueues.actions.createQueue")}
          </Button>
        )}
      </div>
      {isEmpty ? (
        <PageEmptyState
          lightImageUrl={emptyAnnotationQueuesLightUrl}
          darkImageUrl={emptyAnnotationQueuesDarkUrl}
          title={t("annotationQueues.empty.title")}
          description={t("annotationQueues.empty.description")}
          primaryActionLabel={
            canCreateAnnotationQueues
              ? t("annotationQueues.empty.action")
              : undefined
          }
          onPrimaryAction={
            canCreateAnnotationQueues ? handleNewQueue : undefined
          }
          docsUrl={buildDocsUrl("/evaluation/advanced/annotation_queues")}
        />
      ) : (
        <>
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
                columns={getFilterColumns(t)}
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
                columns={getDefaultColumns(t)}
                selectedColumns={selectedColumns}
                onSelectionChange={setSelectedColumns}
                order={columnsOrder}
                onOrderChange={setColumnsOrder}
              />
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
            showSkeleton={isTableLoading}
            showLoadingOverlay={
              !isTableLoading && isPlaceholderData && isFetching
            }
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
        </>
      )}
      <AddEditAnnotationQueueDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
        projectId={projectId}
      />
    </div>
  );
};

export default AnnotationQueuesPage;
