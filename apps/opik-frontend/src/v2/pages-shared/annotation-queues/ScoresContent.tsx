import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { AnnotationQueue } from "@/types/annotation-queues";
import {
  FeedbackDefinition,
  FEEDBACK_DEFINITION_TYPE,
} from "@/types/feedback-definitions";
import { COLUMN_TYPE, ColumnData, ROW_HEIGHT } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/shared/DataTable/DataTable";
import FeedbackDefinitionsValueCell from "@/shared/DataTableCells/FeedbackDefinitionsValueCell";
import useFeedbackDefinitionsList from "@/api/feedback-definitions/useFeedbackDefinitionsList";
import useAppStore from "@/store/AppStore";
import FeedbackOptionCell from "./FeedbackOptionCell";

interface ScoresContentProps {
  annotationQueue: AnnotationQueue;
}

const useColumns = (hasOnlyComments: boolean) => {
  const { t } = useTranslation("annotation-queues");

  return useMemo(() => {
    const baseColumns: ColumnData<FeedbackDefinition>[] = [
      {
        id: "name",
        label: t("annotationQueues.scores.columns.feedbackOption"),
        type: COLUMN_TYPE.numberDictionary,
        cell: FeedbackOptionCell as never,
      },
      {
        id: "description",
        label: t("annotationQueues.scores.columns.description"),
        type: COLUMN_TYPE.string,
      },
      {
        id: "values",
        label: t("annotationQueues.scores.columns.availableValues"),
        type: COLUMN_TYPE.string,
        cell: FeedbackDefinitionsValueCell as never,
      },
    ];

    const columnsToShow = hasOnlyComments
      ? baseColumns.filter((col) => col.id !== "values")
      : baseColumns;

    return convertColumnDataToColumn<FeedbackDefinition, FeedbackDefinition>(
      columnsToShow,
      {},
    );
  }, [t, hasOnlyComments]);
};

const ScoresContent: React.FunctionComponent<ScoresContentProps> = ({
  annotationQueue,
}) => {
  const { t } = useTranslation("annotation-queues");
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Extract repeated checks into well-named variables
  const hasComments = annotationQueue.comments_enabled;
  const hasFeedbackDefinitions =
    annotationQueue.feedback_definition_names.length > 0;
  const hasOnlyComments = hasComments && !hasFeedbackDefinitions;

  const { data } = useFeedbackDefinitionsList(
    {
      workspaceName,
      page: 1,
      size: 1000,
    },
    {
      enabled: hasFeedbackDefinitions,
    },
  );

  const feedbackDefinitions = useMemo(() => {
    const definitions: FeedbackDefinition[] = [];

    // Add selected feedback definitions
    if (data?.content && hasFeedbackDefinitions) {
      const selectedDefinitions = data.content.filter((def) =>
        annotationQueue.feedback_definition_names.includes(def.name),
      );
      definitions.push(...selectedDefinitions);
    }

    // Add Comments as the last row when comments are enabled
    if (hasComments) {
      definitions.push({
        id: "comments",
        name: t("annotationQueues.scores.comments"),
        description: t("annotationQueues.scores.commentsDescription"),
        type: FEEDBACK_DEFINITION_TYPE.categorical,
        created_at: "",
        last_updated_at: "",
        details: {
          categories: {},
        },
      });
    }

    return definitions;
  }, [data?.content, hasFeedbackDefinitions, hasComments, annotationQueue, t]);

  const columns = useColumns(hasOnlyComments);

  // Only hide the table if comments are disabled AND there are no feedback definitions
  if (!hasComments && !hasFeedbackDefinitions) {
    return null;
  }

  return (
    <DataTable
      columns={columns}
      data={feedbackDefinitions}
      rowHeight={ROW_HEIGHT.small}
      getRowId={(row) => row.id}
    />
  );
};

export default ScoresContent;
