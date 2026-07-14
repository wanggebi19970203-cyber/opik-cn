import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { Plus } from "lucide-react";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";
import i18next from "i18next";

import useEnvironmentsList from "@/api/environments/useEnvironmentsList";
import AddEditEnvironmentDialog from "@/v2/pages-shared/environments/AddEditEnvironmentDialog/AddEditEnvironmentDialog";
import EnvironmentsRowActionsCell from "@/v2/pages/ConfigurationPage/EnvironmentsTab/EnvironmentsRowActionsCell";
import EnvironmentsActionsPanel from "@/v2/pages/ConfigurationPage/EnvironmentsTab/EnvironmentsActionsPanel";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import DataTableNoMatchingData from "@/shared/DataTableNoData/DataTableNoMatchingData";
import IdCell from "@/shared/DataTableCells/IdCell";
import EnvironmentNameCell from "@/v2/pages/ConfigurationPage/EnvironmentsTab/EnvironmentNameCell";
import emptyEnvironmentsLightImage from "/images/empty-environments-light.svg";
import emptyEnvironmentsDarkImage from "/images/empty-environments-dark.svg";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import SearchInput from "@/shared/SearchInput/SearchInput";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { Environment, ENVIRONMENT_WORKSPACE_LIMIT } from "@/types/environments";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import {
  generateActionsColumDef,
  generateSelectColumDef,
} from "@/shared/DataTable/utils";
export const getRowId = (e: Environment) => e.id;

const SELECTED_COLUMNS_KEY = "environments-selected-columns";
const COLUMNS_WIDTH_KEY = "environments-columns-width";
const COLUMNS_ORDER_KEY = "environments-columns-order";

export const DEFAULT_COLUMNS: ColumnData<Environment>[] = [
  {
    id: COLUMN_NAME_ID,
    label: i18next.t("pages/settings:settings.environments.columns.name"),
    type: COLUMN_TYPE.string,
    cell: EnvironmentNameCell as never,
    sortable: true,
  },
  {
    id: COLUMN_ID_ID,
    label: i18next.t("pages/settings:settings.environments.columns.id"),
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "description",
    label: i18next.t(
      "pages/settings:settings.environments.columns.description",
    ),
    type: COLUMN_TYPE.string,
  },
  {
    id: "created_at",
    label: i18next.t("pages/settings:settings.environments.columns.created_at"),
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "created_by",
    label: i18next.t("pages/settings:settings.environments.columns.created_by"),
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_NAME_ID,
  "description",
  "created_at",
  "created_by",
];

const DEFAULT_COLUMNS_ORDER: string[] = [
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  "description",
  "created_at",
  "created_by",
];

const EnvironmentsTab: React.FunctionComponent = () => {
  const { t } = useTranslation("pages/settings");
  const newEnvironmentDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search, setSearch] = useState("");
  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const {
    permissions: { canConfigureWorkspaceSettings },
  } = usePermissions();

  const { data, isPending, isPlaceholderData, isFetching } =
    useEnvironmentsList({
      placeholderData: keepPreviousData,
      refetchInterval: 30000,
    });

  const allEnvironments = useMemo(() => data?.content ?? [], [data?.content]);

  const environments = useMemo(() => {
    if (!search) return allEnvironments;
    const needle = search.toLowerCase();
    return allEnvironments.filter((env) =>
      env.name.toLowerCase().includes(needle),
    );
  }, [allEnvironments, search]);

  const total = allEnvironments.length;
  const showCreate = !search;
  const atLimit = total >= ENVIRONMENT_WORKSPACE_LIMIT;

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
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

  const selectedRows: Environment[] = useMemo(
    () => environments.filter((row) => rowSelection[row.id]),
    [rowSelection, environments],
  );

  const translatedColumns: ColumnData<Environment>[] = useMemo(
    () =>
      DEFAULT_COLUMNS.map((col) => ({
        ...col,
        label: t(`settings.environments.columns.${col.id}`),
      })),
    [t],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Environment>(),
      ...convertColumnDataToColumn<Environment, Environment>(
        translatedColumns,
        {
          columnsOrder,
          selectedColumns,
        },
      ),
      ...(canConfigureWorkspaceSettings
        ? [generateActionsColumDef({ cell: EnvironmentsRowActionsCell })]
        : []),
    ];
  }, [
    columnsOrder,
    selectedColumns,
    canConfigureWorkspaceSettings,
    translatedColumns,
  ]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleNewEnvironmentClick = useCallback(() => {
    setOpenDialog(true);
    newEnvironmentDialogKeyRef.current = newEnvironmentDialogKeyRef.current + 1;
  }, []);

  const isTableLoading =
    isPending || (isPlaceholderData && environments.length === 0);

  const createButton = canConfigureWorkspaceSettings ? (
    <Button
      variant="default"
      size="xs"
      onClick={handleNewEnvironmentClick}
      disabled={atLimit}
    >
      <Plus className="mr-1 size-4" />
      {t("settings.environments.create")}
    </Button>
  ) : null;

  const headerAction =
    createButton && atLimit ? (
      <TooltipWrapper
        content={t("settings.environments.atLimitTooltip", {
          limit: ENVIRONMENT_WORKSPACE_LIMIT,
        })}
      >
        <span>{createButton}</span>
      </TooltipWrapper>
    ) : (
      createButton
    );

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="comet-title-xs">{t("settings.environments.title")}</h2>
        {headerAction}
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <SearchInput
          searchText={search}
          setSearchText={setSearch}
          placeholder={t("settings.searchPlaceholder")}
          className="w-[320px]"
          dimension="sm"
        />

        <div className="flex items-center gap-2">
          {canConfigureWorkspaceSettings && (
            <EnvironmentsActionsPanel environments={selectedRows} />
          )}
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
        </div>
      </div>
      <DataTable
        columns={columns}
        data={environments}
        resizeConfig={resizeConfig}
        selectionConfig={{ rowSelection, setRowSelection }}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          showCreate ? (
            <DataTableEmptyContent
              lightImageUrl={emptyEnvironmentsLightImage}
              darkImageUrl={emptyEnvironmentsDarkImage}
              title={t("settings.environments.noEnvironmentsTitle")}
              description={t("settings.environments.noEnvironmentsDescription")}
            >
              {canConfigureWorkspaceSettings && (
                <button
                  onClick={handleNewEnvironmentClick}
                  className="comet-body-s underline underline-offset-4 hover:text-primary"
                >
                  {t("settings.environments.addEnvironment")}
                </button>
              )}
            </DataTableEmptyContent>
          ) : (
            <DataTableNoMatchingData />
          )
        }
        showSkeleton={isTableLoading}
        showLoadingOverlay={!isTableLoading && isPlaceholderData && isFetching}
      />
      <AddEditEnvironmentDialog
        key={newEnvironmentDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default EnvironmentsTab;
