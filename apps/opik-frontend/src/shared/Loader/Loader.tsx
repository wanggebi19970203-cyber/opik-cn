import React from "react";
import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";
import { Spinner } from "@/ui/spinner";

type LoaderProps = {
  message?: React.ReactNode;
  className?: string;
};

const Loader: React.FunctionComponent<LoaderProps> = ({
  message,
  className = "min-h-96",
}) => {
  const { t } = useTranslation("common");
  const resolvedMessage = message ?? t("buttons.loading");

  return (
    <div
      className={cn(
        "flex h-full flex-col items-center justify-center",
        className,
      )}
    >
      <Spinner className={cn(resolvedMessage && "mb-2")} />
      {resolvedMessage}
    </div>
  );
};

export default Loader;
