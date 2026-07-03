import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { AnnotationQueue } from "@/types/annotation-queues";
import { ROW_HEIGHT, COLUMN_TYPE, ColumnData } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import DataTable from "@/shared/DataTable/DataTable";

interface ReviewerRowData {
  username: string;
  progress: string;
}

interface ReviewersSectionProps {
  annotationQueue: AnnotationQueue;
}

export const getDefaultColumns = (t: (key: string) => string): ColumnData<ReviewerRowData>[] => [
  {
    id: "username",
    label: t("annotationQueue.configuration.reviewers.columns.reviewer"),
    type: COLUMN_TYPE.string,
  },
  {
    id: "progress",
    label: t("annotationQueue.configuration.reviewers.columns.progress"),
    type: COLUMN_TYPE.string,
  },
];

const ReviewersSection: React.FunctionComponent<ReviewersSectionProps> = ({
  annotationQueue,
}) => {
  const { t } = useTranslation("pages/annotation-queue");
  const reviewerColumns = useMemo(
    () =>
      convertColumnDataToColumn<ReviewerRowData, ReviewerRowData>(
        getDefaultColumns(t),
        {},
      ),
    [t],
  );

  const rows = useMemo(() => {
    if (!annotationQueue?.reviewers) return [];

    return annotationQueue.reviewers.map<ReviewerRowData>((reviewer) => ({
      username: reviewer.username,
      progress: `${reviewer.status}/${annotationQueue.items_count}`,
    }));
  }, [annotationQueue?.reviewers, annotationQueue?.items_count]);

  if (!annotationQueue.reviewers || annotationQueue.reviewers.length === 0) {
    return null;
  }

  return (
    <div className="pt-4">
      <h2 className="comet-title-s truncate break-words bg-soft-background pb-3 pt-2">
        {t("annotationQueue.configuration.reviewers.title", { count: annotationQueue.reviewers.length })}
      </h2>
      <DataTable
        columns={reviewerColumns}
        data={rows}
        rowHeight={ROW_HEIGHT.small}
        getRowId={(row) => row.username}
      />
    </div>
  );
};

export default ReviewersSection;
