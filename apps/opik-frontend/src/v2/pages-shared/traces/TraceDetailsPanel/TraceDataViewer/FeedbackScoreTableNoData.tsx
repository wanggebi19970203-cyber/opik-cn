import { Button } from "@/ui/button";
import { Book, PenLine } from "lucide-react";
import React from "react";
import { useTranslation } from "react-i18next";
import { usePermissions } from "@/contexts/PermissionsContext";
import { buildDocsUrl } from "@/v2/lib/utils";

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

  const entityCopy = {
    thread: t("feedbackScoreTable.threads"),
    trace: t("feedbackScoreTable.tracesLLMCalls"),
    experiment: t("feedbackScoreTable.experiments"),
    span: t("feedbackScoreTable.tracesLLMCalls"),
  };

  const evaluationDocsLink = buildDocsUrl(
    entityType === "experiment"
      ? "/evaluation/overview"
      : "/production/online-evaluation/rules",
  );

  const evaluationDocsLabel =
    entityType === "experiment"
      ? t("feedbackScoreTable.learnAboutExperimentScoring")
      : t("feedbackScoreTable.learnAboutOnlineEvaluation");

  const getDescription = () => {
    if (entityType === "experiment") {
      if (canAnnotateTraceSpanThread) {
        return t("feedbackScoreTable.descExperimentWithAnnotate");
      }
      return t("feedbackScoreTable.descExperimentSdkOnly");
    }

    const entity = entityCopy[entityType];

    if (canUpdateOnlineEvaluationRules && canAnnotateTraceSpanThread) {
      return t("feedbackScoreTable.descOnlineEvalAndAnnotate", { entity });
    }

    if (canAnnotateTraceSpanThread) {
      return t("feedbackScoreTable.descSdkOnly", { entity });
    }

    if (canUpdateOnlineEvaluationRules) {
      return t("feedbackScoreTable.descOnlineEvalOnly", { entity });
    }

    return "";
  };

  return (
    <div className="flex min-h-48 flex-col items-center justify-center gap-2 bg-background p-6">
      <div>{t("feedbackScoreTable.noFeedbackScoresYet")}</div>
      {(canAnnotateTraceSpanThread ||
        canUpdateOnlineEvaluationRules ||
        entityType === "experiment") && (
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
            {(entityType === "experiment" ||
              canUpdateOnlineEvaluationRules) && (
              <Button variant="secondary" size="sm" asChild>
                <a
                  href={evaluationDocsLink}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <Book className="mr-2 size-4" />
                  {evaluationDocsLabel}
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
