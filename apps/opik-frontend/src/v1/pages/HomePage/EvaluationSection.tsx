import React, { useCallback, useMemo, useRef, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState } from "@tanstack/react-table";
import { Link, useNavigate } from "@tanstack/react-router";
import { ArrowRight } from "lucide-react";
import { useTranslation } from "react-i18next";

import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import ResourceCell from "@/shared/DataTableCells/ResourceCell";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import Loader from "@/shared/Loader/Loader";
import AddExperimentDialog from "@/v1/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import { Button } from "@/ui/button";
import useAppStore from "@/store/AppStore";
import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
} from "@/types/shared";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import { Experiment } from "@/types/datasets";
import { convertColumnDataToColumn } from "@/lib/table";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import FeedbackScoreListCell from "@/shared/DataTableCells/FeedbackScoreListCell";
import PassRateCell from "@/shared/DataTableCells/PassRateCell";
import { transformExperimentScores } from "@/lib/feedback-scores";
import { usePermissions } from "@/contexts/PermissionsContext";

const COLUMNS_WIDTH_KEY = "home-experiments-columns-width";

export const COLUMNS = convertColumnDataToColumn<Experiment, Experiment>(
  [
    {
      id: COLUMN_NAME_ID,
      label: "Experiment",
      type: COLUMN_TYPE.string,
      cell: ResourceCell as never,
      sortable: true,
      customMeta: {
        nameKey: "name",
        idKey: "dataset_id",
        resource: RESOURCE_TYPE.experiment,
        getSearch: (data: Experiment) => ({
          experiments: [data.id],
        }),
      },
    },
    {
      id: "dataset",
      label: "home.evaluation.testSuite",
      type: COLUMN_TYPE.string,
      cell: ResourceCell as never,
      customMeta: {
        nameKey: "dataset_name",
        idKey: "dataset_id",
        resource: RESOURCE_TYPE.dataset,
      },
    },
    {
      id: "trace_count",
      label: "home.evaluation.itemCount",
      type: COLUMN_TYPE.number,
    },
    {
      id: "pass_rate",
      label: "home.evaluation.passRate",
      type: COLUMN_TYPE.number,
      iconType: "pass_rate",
      accessorFn: (row) => row.pass_rate,
      cell: PassRateCell as never,
    },
    {
      id: COLUMN_FEEDBACK_SCORES_ID,
      label: "home.evaluation.feedbackScores",
      type: COLUMN_TYPE.numberDictionary,
      accessorFn: transformExperimentScores,
      cell: FeedbackScoreListCell as never,
      customMeta: {
        getHoverCardName: (row: Experiment) => row.name,
        areAggregatedScores: true,
      },
    },
    {
      id: "created_at",
      label: "home.evaluation.created",
      type: COLUMN_TYPE.time,
      cell: TimeCell as never,
      sortable: true,
    },
  ],
  {},
);

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const EvaluationSection: React.FC = () => {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const {
    permissions: { canViewExperiments, canCreateExperiments },
  } = usePermissions();

  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const { data, isPending } = useExperimentsList(
    {
      workspaceName,
      page: 1,
      size: 5,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const experiments = useMemo(() => data?.content ?? [], [data?.content]);
  const noDataText = t("home.evaluation.noExperiments");

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const handleRowClick = useCallback(
    (row: Experiment) => {
      navigate({
        to: "/$workspaceName/experiments/$datasetId/compare",
        params: {
          datasetId: row.dataset_id,
          workspaceName,
        },
        search: {
          experiments: [row.id],
        },
      });
    },
    [navigate, workspaceName],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleNewExperimentClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  if (!canViewExperiments) {
    return null;
  }

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pb-4 pt-2">
      <h2 className="comet-title-s sticky top-0 z-10 truncate break-words bg-soft-background pb-3 pt-2">
        {t("home.evaluation.title")}
      </h2>
      <DataTable
        columns={COLUMNS}
        data={experiments}
        onRowClick={handleRowClick}
        resizeConfig={resizeConfig}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            {canCreateExperiments && (
              <Button variant="link" onClick={handleNewExperimentClick}>
                {t("home.evaluation.createNewExperiment")}
              </Button>
            )}
          </DataTableNoData>
        }
      />
      <div className="flex justify-end pt-1">
        <Link to="/$workspaceName/experiments" params={{ workspaceName }}>
          <Button variant="ghost" className="flex items-center gap-1 pr-0">
            {t("home.evaluation.allExperiments")} <ArrowRight className="size-4" />
          </Button>
        </Link>
      </div>
      <AddExperimentDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default EvaluationSection;
