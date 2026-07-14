import React from "react";
import { CircleCheck, Eye } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Card } from "@/ui/card";
import { Button } from "@/ui/button";
import SMEFlowLayout from "../SMEFlowLayout";
import ReturnToAnnotationQueueButton from "../ReturnToAnnotationQueueButton";
import { useSMEFlow } from "../SMEFlowContext";

interface CompletionViewProps {
  header: React.ReactNode;
}

const CompletionView: React.FunctionComponent<CompletionViewProps> = ({
  header,
}) => {
  const { t } = useTranslation("sme");
  const { handleReviewAnnotations } = useSMEFlow();

  return (
    <SMEFlowLayout header={header} footer={<ReturnToAnnotationQueueButton />}>
      <Card className="h-full p-6 pt-14 text-center">
        <CircleCheck
          className="mx-auto mb-5 size-8 text-success"
          data-testid="completion-icon"
          aria-hidden="true"
        />
        <h3 className="comet-title-l">
          {t("completionView.allItemsCompleted")}
        </h3>
        <div className="comet-body-s mt-3 text-center text-muted-slate">
          <p>{t("completionView.allAnnotationsComplete")}</p>
          <p className="mt-2">{t("completionView.canCloseTab")}</p>
        </div>
        <div className="mt-6">
          <Button
            variant="outline"
            onClick={handleReviewAnnotations}
            aria-label={t("completionView.reviewAnnotationsAriaLabel")}
          >
            <Eye className="mr-2 size-4" />
            {t("completionView.reviewAnnotations")}
          </Button>
        </div>
      </Card>
    </SMEFlowLayout>
  );
};

export default CompletionView;
