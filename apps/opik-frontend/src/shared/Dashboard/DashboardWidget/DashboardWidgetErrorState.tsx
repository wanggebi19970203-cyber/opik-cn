import React from "react";
import { AlertCircle } from "lucide-react";
import i18n from "@/i18n";
import { Button } from "@/ui/button";
import { cn } from "@/lib/utils";

type DashboardWidgetErrorStateProps = {
  title?: string;
  message?: string;
  error?: Error | string;
  onRetry?: () => void;
  className?: string;
};

const DashboardWidgetErrorState: React.FunctionComponent<
  DashboardWidgetErrorStateProps
> = ({
  title = i18n.t("common:dashboard.failedToLoadData"),
  message = i18n.t("common:dashboard.widgetLoadError"),
  error,
  onRetry,
  className,
}) => {
  const errorMessage = typeof error === "string" ? error : error?.message;

  return (
    <div
      className={cn(
        "flex h-full flex-col items-center justify-center p-8 text-center",
        className,
      )}
    >
      <div className="mb-3 text-destructive">
        <AlertCircle className="size-12" />
      </div>
      <h3 className="comet-body-s-accented mb-1 text-foreground">{title}</h3>
      <p className="comet-body-s mb-2 text-muted-slate">{message}</p>
      {errorMessage && (
        <p className="comet-body-xs mb-4 max-w-md text-muted-slate">
          {errorMessage}
        </p>
      )}
      {onRetry && (
        <Button variant="outline" size="sm" onClick={onRetry}>
          {i18n.t("common:dashboard.tryAgain")}
        </Button>
      )}
    </div>
  );
};

export default DashboardWidgetErrorState;
