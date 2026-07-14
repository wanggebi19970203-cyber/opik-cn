import React from "react";
import { useTranslation } from "react-i18next";
import {
  useProgressCompleted,
  useProgressTotal,
} from "@/store/PlaygroundStore";

const PlaygroundProgressIndicator: React.FC = () => {
  const { t } = useTranslation("playground");
  const progressTotal = useProgressTotal();
  const progressCompleted = useProgressCompleted();

  if (progressTotal === 0) {
    return null;
  }

  const progressPercentage = Math.round(
    (progressCompleted / progressTotal) * 100,
  );

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <span className="comet-body-s-accented text-foreground">
          {t("playground.progress.progress")}
        </span>
        <span className="comet-body-s text-light-slate">
          {progressCompleted}/{progressTotal}{" "}
          {t("playground.progress.completed")} ({progressPercentage}%)
        </span>
      </div>
      <div className="flex flex-1 items-center">
        <div className="h-2 w-full rounded-full bg-secondary">
          <div
            className="h-2 rounded-full bg-primary transition-all duration-300"
            style={{ width: `${progressPercentage}%` }}
          />
        </div>
      </div>
    </div>
  );
};

export default PlaygroundProgressIndicator;
