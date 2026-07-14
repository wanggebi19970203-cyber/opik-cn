import React from "react";
import { Alert, AlertDescription } from "@/ui/alert";
import { AlertCircle } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import InstructionsContent from "@/v1/pages-shared/annotation-queues/InstructionsContent";
import ScoresContent from "@/v1/pages-shared/annotation-queues/ScoresContent";
import SMEFlowLayout from "../SMEFlowLayout";
import ReturnToAnnotationQueueButton from "../ReturnToAnnotationQueueButton";
import { useSMEFlow } from "../SMEFlowContext";

const GetStartedView: React.FC = () => {
  const { t } = useTranslation("sme");
  const {
    annotationQueue,
    canStartAnnotation,
    handleStartAnnotating,
    handleReviewAnnotations,
    processedCount,
    totalCount,
  } = useSMEFlow();

  if (!annotationQueue) {
    return null;
  }

  const allItemsCompleted = processedCount === totalCount && totalCount > 0;

  return (
    <SMEFlowLayout
      header={
        <>
          <h1 className="comet-title-xl mb-1">
            {t("getStartedView.welcomeTo", {
              name: annotationQueue?.name ?? "opik annotation",
            })}
          </h1>
          <div className="comet-body-s mt-2 text-muted-slate">
            {t("getStartedView.invitedToReview")}
          </div>
        </>
      }
      footer={
        <>
          <ReturnToAnnotationQueueButton />
          <div className="flex gap-2">
            {canStartAnnotation ? (
              <Button onClick={handleStartAnnotating}>
                {processedCount > 0
                  ? t("getStartedView.resumeAnnotating")
                  : t("getStartedView.startAnnotating")}
              </Button>
            ) : allItemsCompleted ? (
              <Button onClick={handleReviewAnnotations}>
                {t("getStartedView.reviewAnnotations")}
              </Button>
            ) : null}
          </div>
        </>
      }
    >
      <div className="flex flex-col gap-8">
        {!canStartAnnotation && (
          <Alert variant="destructive">
            <AlertCircle className="size-4" />
            <AlertDescription>
              {t("getStartedView.allItemsProcessed")}
            </AlertDescription>
          </Alert>
        )}
        <div>
          <h2 className="comet-title-l mb-4">
            {t("getStartedView.instructions")}
          </h2>
          <InstructionsContent annotationQueue={annotationQueue} />
        </div>
        <div>
          <h2 className="comet-title-l mb-1">
            {t("getStartedView.feedbackOptions")}
          </h2>
          <div className="comet-body-s mb-4 text-muted-slate">
            {t("getStartedView.feedbackOptionsDescription")}
          </div>
          <ScoresContent annotationQueue={annotationQueue} />
        </div>
      </div>
    </SMEFlowLayout>
  );
};

export default GetStartedView;
