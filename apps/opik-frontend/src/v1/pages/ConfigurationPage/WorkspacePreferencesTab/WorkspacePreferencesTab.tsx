import { useMemo, useCallback } from "react";
import { useTranslation } from "react-i18next";
import { useQueryParam } from "use-query-params";

import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/shared/DataTable/DataTable";
import { generateActionsColumDef } from "@/shared/DataTable/utils";
import Loader from "@/shared/Loader/Loader";
import useWorkspaceConfig from "@/api/workspaces/useWorkspaceConfig";
import useWorkspaceConfigMutation from "@/api/workspaces/useWorkspaceConfigMutation";
import { formatIso8601Duration } from "@/lib/date";
import useAppStore from "@/store/AppStore";
import { usePermissions } from "@/contexts/PermissionsContext";
import {
  WorkspacePreference,
  WORKSPACE_PREFERENCE_TYPE,
  WorkspacePreferenceParam,
  WORKSPACE_PREFERENCES_QUERY_PARAMS,
} from "@/constants/workspace-preferences";
import {
  getWorkspacePreferencesDefaultColumns,
  WORKSPACE_PREFERENCES_DEFAULT_COLUMN_PINNING,
  WORKSPACE_PREFERENCES_DEFAULT_THREAD_TIMEOUT,
  WORKSPACE_PREFERENCES_DEFAULT_TRUNCATION_TOGGLE,
} from "./constants";
import WorkspacePreferencesActionsCell from "./WorkspacePreferencesActionsCell";
import EditThreadTimeoutDialog from "./EditThreadTimeoutDialog";
import { EditThreadTimeoutFormValues } from "./EditThreadTimeoutForm";
import EditTruncationToggleDialog from "./EditTruncationToggleDialog";

const WorkspacePreferencesTab: React.FC = () => {
  const { t } = useTranslation();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const { data: workspaceConfig, isPending } = useWorkspaceConfig({
    workspaceName: workspaceName,
  });
  const { mutate: updateWorkspaceConfig } = useWorkspaceConfigMutation();

  const [editPreferenceOpen, setEditPreferenceOpen] = useQueryParam(
    WORKSPACE_PREFERENCES_QUERY_PARAMS.EDIT_PREFERENCE,
    WorkspacePreferenceParam,
    {
      updateType: "replaceIn",
    },
  );

  const {
    permissions: { canConfigureWorkspaceSettings },
  } = usePermissions();

  const threadTimeoutValue =
    workspaceConfig?.timeout_to_mark_thread_as_inactive ??
    WORKSPACE_PREFERENCES_DEFAULT_THREAD_TIMEOUT;

  const truncationToggleValue =
    workspaceConfig?.truncation_on_tables ??
    WORKSPACE_PREFERENCES_DEFAULT_TRUNCATION_TOGGLE;

  const data = useMemo(
    () => [
      {
        name: t("settings.workspacePreferences.threadTimeout.name"),
        value:
          formatIso8601Duration(threadTimeoutValue) ??
          t("settings.workspacePreferences.threadTimeout.notSet"),
        type: WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
      },
      {
        name: t("settings.workspacePreferences.truncationToggle.name"),
        value: truncationToggleValue
          ? t("settings.workspacePreferences.truncationToggle.enabled")
          : t("settings.workspacePreferences.truncationToggle.disabled"),
        type: WORKSPACE_PREFERENCE_TYPE.TRUNCATION_TOGGLE,
      },
    ],
    [threadTimeoutValue, truncationToggleValue, t],
  );

  const getPreferencesDialogConfig = useCallback(
    (type: WORKSPACE_PREFERENCE_TYPE) => {
      const isOpen = editPreferenceOpen === type;
      const setOpen = (v: boolean) => setEditPreferenceOpen(v ? type : null);

      return {
        open: isOpen,
        setOpen,
      };
    },
    [editPreferenceOpen, setEditPreferenceOpen],
  );

  const handleEdit = useCallback(
    (row: WorkspacePreference) => {
      setEditPreferenceOpen(row.type);
    },
    [setEditPreferenceOpen],
  );

  const mergeConfigUpdate = useCallback(
    (
      updates: Partial<{
        timeout_to_mark_thread_as_inactive: string | null;
        truncation_on_tables: boolean;
      }>,
    ) => {
      updateWorkspaceConfig({
        config: {
          timeout_to_mark_thread_as_inactive:
            updates.timeout_to_mark_thread_as_inactive !== undefined
              ? updates.timeout_to_mark_thread_as_inactive
              : workspaceConfig?.timeout_to_mark_thread_as_inactive ?? null,
          truncation_on_tables:
            updates.truncation_on_tables !== undefined
              ? updates.truncation_on_tables
              : workspaceConfig?.truncation_on_tables ?? null,
          color_map: workspaceConfig?.color_map ?? null,
        },
      });
    },
    [
      updateWorkspaceConfig,
      workspaceConfig?.timeout_to_mark_thread_as_inactive,
      workspaceConfig?.truncation_on_tables,
      workspaceConfig?.color_map,
    ],
  );

  const handleThreadTimeoutSubmit = useCallback(
    (values: EditThreadTimeoutFormValues) => {
      mergeConfigUpdate({
        timeout_to_mark_thread_as_inactive:
          values.timeout_to_mark_thread_as_inactive,
      });
    },
    [mergeConfigUpdate],
  );

  const handleTruncationToggleSubmit = useCallback(
    (enabled: boolean) => {
      mergeConfigUpdate({
        truncation_on_tables: enabled,
      });
    },
    [mergeConfigUpdate],
  );

  const columns = useMemo(() => {
    const defaultColumns = getWorkspacePreferencesDefaultColumns(t);
    const baseColumns = convertColumnDataToColumn<
      WorkspacePreference,
      WorkspacePreference
    >(defaultColumns, {});

    if (canConfigureWorkspaceSettings) {
      return [
        ...baseColumns,
        generateActionsColumDef({
          cell: WorkspacePreferencesActionsCell,
          customMeta: {
            onEdit: handleEdit,
          },
        }),
      ];
    }
    return baseColumns;
  }, [canConfigureWorkspaceSettings, handleEdit, t]);

  return (
    <>
      {isPending ? (
        <Loader />
      ) : (
        <DataTable
          columns={columns}
          data={data}
          columnPinning={WORKSPACE_PREFERENCES_DEFAULT_COLUMN_PINNING}
        />
      )}
      {canConfigureWorkspaceSettings && (
        <>
          <EditThreadTimeoutDialog
            {...getPreferencesDialogConfig(
              WORKSPACE_PREFERENCE_TYPE.THREAD_TIMEOUT,
            )}
            defaultValue={threadTimeoutValue}
            onSubmit={handleThreadTimeoutSubmit}
          />
          <EditTruncationToggleDialog
            {...getPreferencesDialogConfig(
              WORKSPACE_PREFERENCE_TYPE.TRUNCATION_TOGGLE,
            )}
            currentValue={truncationToggleValue}
            onConfirm={handleTruncationToggleSubmit}
          />
        </>
      )}
    </>
  );
};

export default WorkspacePreferencesTab;
