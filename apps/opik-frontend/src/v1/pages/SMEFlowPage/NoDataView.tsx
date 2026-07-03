import React from "react";
import { useTranslation } from "react-i18next";
import { AlertCircle, FileText, RefreshCw } from "lucide-react";
import { Card } from "@/ui/card";
import { Alert, AlertDescription } from "@/ui/alert";
import { Button } from "@/ui/button";
import SMEFlowLayout from "./SMEFlowLayout";

type NoDataViewVariant = "no-queue" | "queue-error" | "items-error";

interface NoDataViewProps {
  variant: NoDataViewVariant;
  onRetry?: () => void;
}

const NoDataView: React.FunctionComponent<NoDataViewProps> = ({
  variant,
  onRetry,
}) => {
  const { t } = useTranslation();

  return (
    <SMEFlowLayout header={<h1 className="comet-title-xl">{t("sme.noDataView.error")}</h1>}>
      {variant === "queue-error" && (
        <div className="space-y-6">
          <Alert variant="destructive">
            <AlertCircle className="size-4" />
            <AlertDescription>
              {t("sme.noDataView.unableToLoadAnnotationQueue")}
            </AlertDescription>
          </Alert>

          <Card className="p-8 text-center">
            <FileText className="mx-auto mb-4 size-12 text-muted-slate" />
            <h3 className="comet-title-m mb-2">{t("sme.noDataView.queueNotAvailable")}</h3>
            <p className="comet-body-s mb-6 text-muted-slate">
              {t("sme.noDataView.checkQueueLinkOrContactAdmin")}
            </p>
          </Card>
        </div>
      )}

      {variant === "items-error" && (
        <div className="space-y-6">
          <Alert variant="destructive">
            <AlertCircle className="size-4" />
            <AlertDescription>
              {t("sme.noDataView.unableToLoadItems")}
            </AlertDescription>
          </Alert>

          <Card className="p-8 text-center">
            <FileText className="mx-auto mb-4 size-12 text-muted-slate" />
            <h3 className="comet-title-m mb-2">{t("sme.noDataView.itemsFailedToLoad")}</h3>
            <p className="comet-body-s mb-6 text-muted-slate">
              {t("sme.noDataView.tryAgainOrContactAdmin")}
            </p>
            {onRetry && (
              <Button onClick={onRetry} variant="outline">
                <RefreshCw className="mr-2 size-4" />
                {t("common.buttons.retry")}
              </Button>
            )}
          </Card>
        </div>
      )}

      {variant === "no-queue" && (
        <Card className="p-8 text-center">
          <FileText className="mx-auto mb-4 size-12 text-muted-slate" />
          <h3 className="comet-title-m mb-2">{t("sme.noDataView.noAnnotationQueueSelected")}</h3>
          <p className="comet-body-s mb-6 text-muted-slate">
            {t("sme.noDataView.toBeginSmeEvaluation")}
          </p>
        </Card>
      )}
    </SMEFlowLayout>
  );
};

export default NoDataView;
