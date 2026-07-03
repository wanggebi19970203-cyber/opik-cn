import { CellContext } from "@tanstack/react-table";
import { useTranslation } from "react-i18next";
import FeedbackScoreNameCell from "@/shared/DataTableCells/FeedbackScoreNameCell";
import FeedbackOptionCommentCell from "./FeedbackOptionCommentCell";

const FeedbackOptionCell = (context: CellContext<unknown, string>) => {
  const { t } = useTranslation("annotation-queues");
  const value = context.getValue();

  if (value === t("annotationQueues.scores.comments")) {
    return FeedbackOptionCommentCell(context);
  }

  return FeedbackScoreNameCell(context);
};

export default FeedbackOptionCell;
