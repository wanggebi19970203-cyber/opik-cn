import React, { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useSearch } from "@tanstack/react-router";
import { flatMap, get, uniq } from "lodash";
import md5 from "md5";
import { FoldVertical, RotateCw, UnfoldVertical } from "lucide-react";

import useRulesLogsList from "@/api/automations/useRulesLogsList";
import NoData from "@/shared/NoData/NoData";
import Loader from "@/shared/Loader/Loader";
import { Button } from "@/ui/button";
import PageBodyScrollContainer from "@/v1/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/v1/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
import ExpandableTextCell from "@/shared/DataTableCells/ExpandableTextCell";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import {
  EvaluatorRuleLogItem,
  EvaluatorRuleLogItemWithId,
} from "@/types/automations";
import { convertColumnDataToColumn } from "@/lib/table";
import useLocalStorageState from "use-local-storage-state";
import TimeCell from "@/shared/DataTableCells/TimeCell";

const generateEvaluatorRuleLogItemKey = (
  item: EvaluatorRuleLogItem,
): string => {
  const messageHash = md5(item.message);
  return `${item.timestamp}-${item.level}-${messageHash}`;
};

const getBaseColumns = (t: (key: string) => string): ColumnData<EvaluatorRuleLogItemWithId>[] => [
  {
    id: "timestamp",
    label: t("automationLogs.columns.timestamp"),
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
    customMeta: {
      timeMode: "absolute",
    },
    size: 180,
  },
  {
    id: "level",
    label: t("automationLogs.columns.level"),
    type: COLUMN_TYPE.string,
    size: 80,
  },
];

const COLUMNS_WIDTH_KEY = "automation-logs-columns-width";

const AutomationLogsPage = () => {
  const { t } = useTranslation();
  const {
    rule_id,
  }: {
    rule_id?: string;
  } = useSearch({ strict: false });

  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useRulesLogsList(
      {
        ruleId: rule_id!,
      },
      {
        enabled: Boolean(rule_id),
      },
    );

  const { rows, markerKeys } = useMemo(() => {
    const rawRows =
      data?.content.sort((a, b) => b.timestamp.localeCompare(a.timestamp)) ??
      [];

    const sortedRowsWithId: EvaluatorRuleLogItemWithId[] = rawRows.map(
      (item) => ({
        ...item,
        id: generateEvaluatorRuleLogItemKey(item),
      }),
    );

    const allMarkerKeys = uniq(
      flatMap(sortedRowsWithId, (item) =>
        item.markers ? Object.keys(item.markers) : [],
      ),
    ).sort();

    return {
      rows: sortedRowsWithId,
      markerKeys: allMarkerKeys,
    };
  }, [data?.content]);

  const allExpanded = useMemo(
    () => rows.length > 0 && rows.every((row) => expanded[row.id]),
    [rows, expanded],
  );

  const toggleExpandAll = () => {
    if (allExpanded) {
      setExpanded({});
    } else {
      setExpanded((prev) => {
        const next = { ...prev } as Record<string, boolean>;
        rows.forEach((row) => {
          next[row.id] = true;
        });
        return next;
      });
    }
  };

  const columns = useMemo(() => {
    const baseColumns = getBaseColumns(t);

    const markerColumns: ColumnData<EvaluatorRuleLogItemWithId>[] =
      markerKeys.map((key) => ({
        id: `marker_${key}`,
        label: key.replace(/_/g, " ").replace(/\b\w/g, (l) => l.toUpperCase()),
        type: COLUMN_TYPE.string,
        accessorFn: (row) => get(row, ["markers", key], ""),
      }));

    const messageColumn: ColumnData<EvaluatorRuleLogItemWithId> = {
      id: "message",
      label: t("automationLogs.columns.message"),
      type: COLUMN_TYPE.string,
      cell: ExpandableTextCell as never,
      size: 400,
      customMeta: {
        expandedState: expanded,
        setExpandedState: setExpanded,
        getShortValue: (value: string) => (value || "").split("\n")[0] || "",
        getIsExpandable: (value: string) =>
          (value || "").split("\n").length > 1,
      },
    };

    const allColumns = [...baseColumns, ...markerColumns, messageColumn];
    return convertColumnDataToColumn<
      EvaluatorRuleLogItemWithId,
      EvaluatorRuleLogItemWithId
    >(allColumns, {});
  }, [t, markerKeys, expanded, setExpanded]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (!rule_id) {
    return <NoData message={t("automationLogs.noRuleParams")}></NoData>;
  }

  if (isPending) {
    return <Loader />;
  }

  if (rows.length === 0) {
    return <NoData message={t("automationLogs.noLogsForRule")}></NoData>;
  }

  return (
    <div className="mx-6 flex h-full flex-col bg-soft-background">
      <PageBodyScrollContainer>
        <PageBodyStickyContainer
          className="flex items-center justify-between pb-4 pt-6"
          direction="bidirectional"
        >
          <h1 className="comet-title-l truncate break-words">{t("automationLogs.logs")}</h1>
          <div className="flex items-center gap-2">
            <TooltipWrapper content={t("automationLogs.actions.refreshTooltip")}>
              <Button
                variant="outline"
                size="icon-sm"
                className="shrink-0"
                onClick={() => {
                  refetch();
                }}
              >
                <RotateCw />
              </Button>
            </TooltipWrapper>
            <TooltipWrapper
              content={allExpanded ? t("automationLogs.actions.collapseAll") : t("automationLogs.actions.expandAll")}
            >
              <Button
                onClick={toggleExpandAll}
                variant="outline"
                size="icon-sm"
              >
                {allExpanded ? <FoldVertical /> : <UnfoldVertical />}
              </Button>
            </TooltipWrapper>
          </div>
        </PageBodyStickyContainer>
        <DataTable
          columns={columns}
          data={rows}
          noData={<DataTableNoData title={t("automationLogs.noLogsForRule")} />}
          TableWrapper={PageBodyStickyTableWrapper}
          getRowId={(row) => row.id}
          stickyHeader
          resizeConfig={resizeConfig}
          showLoadingOverlay={isPlaceholderData && isFetching}
        />
      </PageBodyScrollContainer>
    </div>
  );
};

export default AutomationLogsPage;
