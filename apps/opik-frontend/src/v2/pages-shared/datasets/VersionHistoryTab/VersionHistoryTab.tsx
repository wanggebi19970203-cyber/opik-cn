import React, { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { ColumnPinningState } from "@tanstack/react-table";
import { keepPreviousData } from "@tanstack/react-query";

import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import { DatasetVersion } from "@/types/datasets";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import { convertColumnDataToColumn } from "@/lib/table";
import { generateActionsColumDef } from "@/shared/DataTable/utils";
import { usePermissions } from "@/contexts/PermissionsContext";
import VersionChangeSummaryCell from "./VersionChangeSummaryCell";
import VersionNoteCell from "./VersionNoteCell";
import VersionRowActionsCell from "./VersionRowActionsCell";

interface VersionHistoryTabProps {
  datasetId: string;
}

const getRowId = (v: DatasetVersion) => v.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: ["version_name"],
};

const getColumns = (
  t: (key: string) => string,
): ColumnData<DatasetVersion>[] => [
  {
    id: "version_name",
    label: t("versionHistoryTab.version"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "change_summary",
    label: t("versionHistoryTab.changesSummary"),
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.list,
    cell: VersionChangeSummaryCell as never,
  },
  {
    id: "change_description",
    label: t("versionHistoryTab.versionNote"),
    type: COLUMN_TYPE.string,
    cell: VersionNoteCell as never,
  },
  {
    id: "tags",
    label: t("versionHistoryTab.tags"),
    type: COLUMN_TYPE.list,
    iconType: "tags",
    cell: ListCell as never,
  },
  {
    id: "items_total",
    label: t("versionHistoryTab.itemCount"),
    type: COLUMN_TYPE.number,
    accessorFn: (row) => row.items_total.toLocaleString(),
  },
  {
    id: "created_at",
    label: t("versionHistoryTab.createdAt"),
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: t("versionHistoryTab.createdBy"),
    type: COLUMN_TYPE.string,
  },
];

const VersionHistoryTab: React.FC<VersionHistoryTabProps> = ({ datasetId }) => {
  const { t } = useTranslation("datasets");
  const {
    permissions: { canEditDatasets },
  } = usePermissions();

  const COLUMNS = useMemo(() => getColumns(t), [t]);

  const [page, setPage] = useState(1);
  const [size, setSize] = useState(10);

  const {
    data: versionsData,
    isLoading,
    isPlaceholderData,
    isFetching,
  } = useDatasetVersionsList(
    {
      datasetId,
      page,
      size,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const columns = useMemo(() => {
    const baseColumns = convertColumnDataToColumn<
      DatasetVersion,
      DatasetVersion
    >(COLUMNS, {});

    if (canEditDatasets) {
      baseColumns.push(
        generateActionsColumDef<DatasetVersion>({
          cell: VersionRowActionsCell,
          customMeta: { datasetId },
        }),
      );
    }

    return baseColumns;
  }, [datasetId, canEditDatasets, COLUMNS]);

  const data = versionsData?.content || [];
  const total = versionsData?.total ?? 0;

  const isTableLoading = isLoading || (isPlaceholderData && data.length === 0);

  return (
    <div className="flex flex-col gap-4 pt-4">
      <DataTable
        columns={columns}
        data={data}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={t("versionHistory.noData.title")}>
            <div className="text-sm text-muted-foreground">
              {t("versionHistory.noData.description")}
            </div>
          </DataTableNoData>
        }
        showSkeleton={isTableLoading}
        showLoadingOverlay={!isTableLoading && isPlaceholderData && isFetching}
      />
      <DataTablePagination
        page={page}
        pageChange={setPage}
        size={size}
        sizeChange={setSize}
        total={total}
      />
    </div>
  );
};

export default VersionHistoryTab;
