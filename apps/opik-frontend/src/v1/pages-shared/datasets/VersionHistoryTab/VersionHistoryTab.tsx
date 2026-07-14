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
import Loader from "@/shared/Loader/Loader";
import { convertColumnDataToColumn } from "@/lib/table";
import { generateActionsColumDef } from "@/shared/DataTable/utils";
import { usePermissions } from "@/contexts/PermissionsContext";
import VersionChangeSummaryCell from "./VersionChangeSummaryCell";
import VersionRowActionsCell from "./VersionRowActionsCell";

interface VersionHistoryTabProps {
  datasetId: string;
}

const getRowId = (v: DatasetVersion) => v.id;

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: ["version_name"],
};

const VersionHistoryTab: React.FC<VersionHistoryTabProps> = ({ datasetId }) => {
  const { t } = useTranslation("datasets");

  const COLUMNS: ColumnData<DatasetVersion>[] = useMemo(
    () => [
      {
        id: "version_name",
        label: t("datasets.versionHistoryTab.version"),
        type: COLUMN_TYPE.string,
      },
      {
        id: "change_summary",
        label: t("datasets.versionHistoryTab.changesSummary"),
        type: COLUMN_TYPE.string,
        iconType: COLUMN_TYPE.list,
        cell: VersionChangeSummaryCell as never,
      },
      {
        id: "change_description",
        label: t("datasets.versionHistoryTab.versionNote"),
        type: COLUMN_TYPE.string,
      },
      {
        id: "tags",
        label: t("datasets.versionHistoryTab.tags"),
        type: COLUMN_TYPE.list,
        iconType: "tags",
        cell: ListCell as never,
      },
      {
        id: "items_total",
        label: t("datasets.versionHistoryTab.itemCount"),
        type: COLUMN_TYPE.number,
        accessorFn: (row) => row.items_total.toLocaleString(),
      },
      {
        id: "created_at",
        label: t("datasets.versionHistoryTab.createdAt"),
        type: COLUMN_TYPE.time,
        cell: TimeCell as never,
      },
      {
        id: "created_by",
        label: t("datasets.versionHistoryTab.createdBy"),
        type: COLUMN_TYPE.string,
      },
    ],
    [t],
  );
  const {
    permissions: { canEditDatasets },
  } = usePermissions();

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
  }, [COLUMNS, datasetId, canEditDatasets]);

  const data = versionsData?.content || [];
  const total = versionsData?.total ?? 0;

  if (isLoading) {
    return (
      <div className="flex items-center justify-center pt-12">
        <Loader />
      </div>
    );
  }

  return (
    <div className="flex flex-col gap-4 pt-4">
      <DataTable
        columns={columns}
        data={data}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={t("datasets.versionHistory.noData.title")}>
            <div className="text-sm text-muted-foreground">
              {t("datasets.versionHistory.noData.description")}
            </div>
          </DataTableNoData>
        }
        showLoadingOverlay={isPlaceholderData && isFetching}
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
