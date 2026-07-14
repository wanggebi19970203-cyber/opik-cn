import React from "react";
import { useTranslation } from "react-i18next";
import CustomSuccess from "@/icons/custom-success.svg?react";

type LoggedDataStatusProps = {
  status: "waiting" | "logged";
};

const LoggedDataStatus: React.FC<LoggedDataStatusProps> = ({ status }) => {
  const { t } = useTranslation();
  if (status === "logged") {
    return (
      <div className="flex shrink-0 items-center gap-1.5 rounded border border-primary bg-background px-3 py-1.5">
        <CustomSuccess />
        <span className="comet-body-s-accented text-primary">
          {t("integrationExplorer.receivingData")}
        </span>
      </div>
    );
  }
  return (
    <div className="flex shrink-0 items-center gap-2 rounded border border-primary bg-background px-3 py-1.5">
      <div className="relative">
        <div className="size-2 rounded-full bg-primary"></div>
        <div className="absolute inset-0 size-2 animate-ping rounded-full bg-primary opacity-75"></div>
      </div>
      <span className="comet-body-s-accented text-primary">
        {t("integrationExplorer.waitingForData")}
      </span>
    </div>
  );
};

export default LoggedDataStatus;
