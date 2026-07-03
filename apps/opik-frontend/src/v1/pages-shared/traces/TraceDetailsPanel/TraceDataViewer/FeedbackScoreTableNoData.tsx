import { Button } from "@/ui/button";
import { Book, PenLine } from "lucide-react";
import React from "react";
import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import { buildDocsUrl } from "@/v1/lib/utils";
const entityCopy = {
  thread: "threads",
  trace: "traces/LLM calls",
  experiment: "experiments",
  span: "traces/LLM calls",
};

type FeedbackScoreTableNoDataProps = {
  onAddHumanReview: () => void;
  entityType: "trace" | "thread" | "span" | "experiment";
};
const FeedbackScoreTableNoData: React.FC<FeedbackScoreTableNoDataProps> = ({
  onAddHumanReview,
  entityType,
}) => {
  const { t } = useTranslation("tracing");
  const {
    permissions: { canUpdateOnlineEvaluationRules, canAnnotateTraceSpanThread },
  } = usePermissions();

  const evaluationDocsLink = buildDocsUrl("/production/rules");

  const getDescription = () => {
    if (canUpdateOnlineEvaluationRules && canAnnotateTraceSpanThread) {
      return t("feedbackScoreTable.descOnlineEvalAndAnnotate", { entity: entityCopy[entityType] });
    }

    if (canAnnotateTraceSpanThread) {
      return t("feedbackScoreTable.descSdkOnly", { entity: entityCopy[entityType] });
    }

    if (canUpdateOnlineEvaluationRules) {
      return t("feedbackScoreTable.descOnlineEvalOnly", { entity: entityCopy[entityType] });
    }

    return "";
  };

  return (
    <div className="flex min-h-48 flex-col items-center justify-center gap-2 bg-background p-6">
      <div>{t("feedbackScoreTable.noFeedbackScoresYet")}</div>
      {(canAnnotateTraceSpanThread || canUpdateOnlineEvaluationRules) && (
        <>
          <span className="max-w-[500px] whitespace-pre-wrap break-words text-center text-muted-slate">
            {getDescription()}
          </span>
          <div className="flex flex-wrap justify-center gap-2 pt-3">
            {canAnnotateTraceSpanThread && (
              <Button variant="outline" size="sm" onClick={onAddHumanReview}>
                <PenLine className="mr-2 size-4" />
                {t("feedbackScoreTable.addHumanReview")}
              </Button>
            )}
            {canUpdateOnlineEvaluationRules && (
              <Button variant="secondary" size="sm" asChild>
                <a
                  href={evaluationDocsLink}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <Book className="mr-2 size-4" />
                  {t("feedbackScoreTable.learnAboutOnlineEvaluation")}
                </a>
              </Button>
            )}
          </div>
        </>
      )}
    </div>
  );
};

export default FeedbackScoreTableNoData;
