import { ColumnPinningState } from "@tanstack/react-table";
import { TFunction } from "i18next";
import i18next from "i18next";
import { COLUMN_NAME_ID, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { WorkspacePreference } from "@/constants/workspace-preferences";

export const WORKSPACE_PREFERENCES_DEFAULT_THREAD_TIMEOUT = "PT15M";
export const WORKSPACE_PREFERENCES_DEFAULT_TRUNCATION_TOGGLE = true;

export const WORKSPACE_PREFERENCES_DEFAULT_COLUMNS: ColumnData<WorkspacePreference>[] =
  [
    {
      id: COLUMN_NAME_ID,
      label: i18next.t(
        "pages/settings:settings.workspacePreferences.columns.name",
      ),
      type: COLUMN_TYPE.string,
    },
    {
      id: "value",
      label: i18next.t(
        "pages/settings:settings.workspacePreferences.columns.value",
      ),
      type: COLUMN_TYPE.string,
    },
  ];

export const createWorkspacePreferencesDefaultColumns = (
  t: TFunction,
): ColumnData<WorkspacePreference>[] => [
  {
    id: COLUMN_NAME_ID,
    label: t("settings.workspacePreferences.columns.name"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "value",
    label: t("settings.workspacePreferences.columns.value"),
    type: COLUMN_TYPE.string,
  },
];

export const WORKSPACE_PREFERENCES_DEFAULT_COLUMN_PINNING: ColumnPinningState =
  {
    left: [COLUMN_NAME_ID],
    right: [],
  };
