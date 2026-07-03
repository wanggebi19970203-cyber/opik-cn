import i18next from "i18next";
import {
  COLUMN_CUSTOM_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { BaseTraceData, Thread, Span } from "@/types/traces";

export const TRACE_FILTER_COLUMNS: ColumnData<BaseTraceData>[] = [
  { id: COLUMN_ID_ID, label: i18next.t("common:labels.id"), type: COLUMN_TYPE.string },
  { id: "name", label: i18next.t("common:labels.name"), type: COLUMN_TYPE.string },
  { id: "start_time", label: i18next.t("common:widgetFilters.startTime"), type: COLUMN_TYPE.time },
  { id: "end_time", label: i18next.t("common:widgetFilters.endTime"), type: COLUMN_TYPE.time },
  { id: "input", label: i18next.t("common:widgetFilters.input"), type: COLUMN_TYPE.string },
  { id: "output", label: i18next.t("common:widgetFilters.output"), type: COLUMN_TYPE.string },
  { id: "duration", label: i18next.t("common:widgetFilters.duration"), type: COLUMN_TYPE.duration },
  {
    id: COLUMN_METADATA_ID,
    label: i18next.t("common:labels.metadata"),
    type: COLUMN_TYPE.dictionary,
  },
  { id: "tags", label: i18next.t("common:labels.tags"), type: COLUMN_TYPE.list, iconType: "tags" },
  { id: "thread_id", label: i18next.t("common:widgetFilters.threadId"), type: COLUMN_TYPE.string },
  { id: "error_info", label: i18next.t("common:widgetFilters.errors"), type: COLUMN_TYPE.errors },
  { id: "error_type", label: i18next.t("common:widgetFilters.errorType"), type: COLUMN_TYPE.string },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: i18next.t("common:widgetFilters.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: i18next.t("common:widgetFilters.customFilter"),
    type: COLUMN_TYPE.dictionary,
  },
];

export const THREAD_FILTER_COLUMNS: ColumnData<Thread>[] = [
  { id: COLUMN_ID_ID, label: i18next.t("common:labels.id"), type: COLUMN_TYPE.string },
  {
    id: "first_message",
    label: i18next.t("common:widgetFilters.firstMessage"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "last_message",
    label: i18next.t("common:widgetFilters.lastMessage"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "number_of_messages",
    label: i18next.t("common:widgetFilters.messageCount"),
    type: COLUMN_TYPE.number,
  },
  {
    id: "created_at",
    label: i18next.t("common:widgetFilters.createdAt"),
    type: COLUMN_TYPE.time,
  },
  {
    id: "last_updated_at",
    label: i18next.t("common:testSuiteColumns.lastUpdated"),
    type: COLUMN_TYPE.time,
  },
  {
    id: "duration",
    label: i18next.t("common:widgetFilters.duration"),
    type: COLUMN_TYPE.duration,
  },
  { id: "tags", label: i18next.t("common:labels.tags"), type: COLUMN_TYPE.list, iconType: "tags" },
  {
    id: "start_time",
    label: i18next.t("common:widgetFilters.startTime"),
    type: COLUMN_TYPE.time,
  },
  {
    id: "end_time",
    label: i18next.t("common:widgetFilters.endTime"),
    type: COLUMN_TYPE.time,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: i18next.t("common:widgetFilters.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
  },
];

export const SPAN_FILTER_COLUMNS: ColumnData<Span>[] = [
  { id: COLUMN_ID_ID, label: i18next.t("common:labels.id"), type: COLUMN_TYPE.string },
  { id: "name", label: i18next.t("common:labels.name"), type: COLUMN_TYPE.string },
  { id: "start_time", label: i18next.t("common:widgetFilters.startTime"), type: COLUMN_TYPE.time },
  { id: "end_time", label: i18next.t("common:widgetFilters.endTime"), type: COLUMN_TYPE.time },
  { id: "type", label: i18next.t("common:labels.type"), type: COLUMN_TYPE.category },
  { id: "input", label: i18next.t("common:widgetFilters.input"), type: COLUMN_TYPE.string },
  { id: "output", label: i18next.t("common:widgetFilters.output"), type: COLUMN_TYPE.string },
  { id: "duration", label: i18next.t("common:widgetFilters.duration"), type: COLUMN_TYPE.duration },
  {
    id: COLUMN_METADATA_ID,
    label: i18next.t("common:labels.metadata"),
    type: COLUMN_TYPE.dictionary,
  },
  { id: "tags", label: i18next.t("common:labels.tags"), type: COLUMN_TYPE.list, iconType: "tags" },
  { id: "error_info", label: i18next.t("common:widgetFilters.errors"), type: COLUMN_TYPE.errors },
  { id: "error_type", label: i18next.t("common:widgetFilters.errorType"), type: COLUMN_TYPE.string },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: i18next.t("common:widgetFilters.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: i18next.t("common:widgetFilters.customFilter"),
    type: COLUMN_TYPE.dictionary,
  },
];
