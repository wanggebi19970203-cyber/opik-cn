import React from "react";
import { CornerDownRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import { IssuesIllustration } from "./illustrations";

import GaugeHigh from "@/icons/gauge-high.svg?react";
import GaugeMed from "@/icons/gauge-medium.svg?react";
import GaugeLow from "@/icons/gauge-low.svg?react";

interface ImpactCardProps {
  level: "High" | "Medium" | "Low";
  title: string;
  boldPart: string;
}

const BADGE_CONFIG: Record<
  ImpactCardProps["level"],
  {
    bgClass: string;
    textClass: string;
    Icon: React.FC<React.SVGProps<SVGSVGElement>>;
  }
> = {
  High: {
    bgClass: "bg-red-500",
    textClass: "text-white",
    Icon: GaugeHigh,
  },
  Medium: {
    bgClass: "bg-amber-400",
    textClass: "text-slate-900",
    Icon: GaugeMed,
  },
  Low: {
    bgClass: "bg-sky-300",
    textClass: "text-slate-900",
    Icon: GaugeLow,
  },
};

const ImpactCard: React.FC<ImpactCardProps> = ({ level, title, boldPart }) => {
  const { t } = useTranslation("pages/get-started");
  const { Icon, bgClass, textClass } = BADGE_CONFIG[level];

  const levelLabel = {
    High: t("getStarted.mobileOnboarding.issues.impactHigh"),
    Medium: t("getStarted.mobileOnboarding.issues.impactMedium"),
    Low: t("getStarted.mobileOnboarding.issues.impactLow"),
  }[level];

  return (
    <div className="flex flex-col rounded-md border border-border bg-soft-background p-3 dark:bg-accent-background">
      <div className="pb-1.5">
        <span
          className={`inline-flex h-5 items-center gap-1 rounded pb-px pl-1 pr-1.5 text-[10px] font-medium ${bgClass} ${textClass}`}
        >
          <Icon className="size-3" />
          {levelLabel}
        </span>
      </div>
      <p className="text-xs text-foreground">{title}</p>
      <div className="flex items-center gap-1 rounded py-px pl-0.5">
        <CornerDownRight className="size-2.5 shrink-0 text-light-slate" />
        <p className="text-xs leading-[14px] text-muted-slate">
          {t("getStarted.mobileOnboarding.issues.opikSuggests")}{" "}
          <span className="font-medium">{boldPart}</span>
        </p>
      </div>
    </div>
  );
};

const IssuesStep: React.FC = () => {
  const { t } = useTranslation("pages/get-started");

  return (
    <>
      <div className="slide-fade-right">
        <IssuesIllustration />
      </div>

      <div className="flex flex-col gap-1.5 px-0.5">
        <h1 className="slide-fade-right text-lg font-medium text-foreground [animation-delay:75ms]">
          {t("getStarted.mobileOnboarding.issues.title")}
        </h1>
        <p className="slide-fade-right pb-2 text-sm text-muted-slate [animation-delay:150ms]">
          {t("getStarted.mobileOnboarding.issues.description")}
        </p>
      </div>

      <div className="flex flex-col gap-2">
        <div className="slide-fade-right [animation-delay:225ms]">
          <ImpactCard
            level="High"
            title={t("getStarted.mobileOnboarding.issues.slowKnowledgeSearch")}
            boldPart={t(
              "getStarted.mobileOnboarding.issues.cachingCommonSearches",
            )}
          />
        </div>
        <div className="slide-fade-right [animation-delay:325ms]">
          <ImpactCard
            level="Medium"
            title={t(
              "getStarted.mobileOnboarding.issues.largeAnswerGeneration",
            )}
            boldPart={t(
              "getStarted.mobileOnboarding.issues.cachingTheSystemPrompt",
            )}
          />
        </div>
        <div className="slide-fade-right [animation-delay:425ms]">
          <ImpactCard
            level="Low"
            title={t("getStarted.mobileOnboarding.issues.tooManyDocsRetrieved")}
            boldPart={t(
              "getStarted.mobileOnboarding.issues.limitingRetrievedPages",
            )}
          />
        </div>
      </div>
    </>
  );
};

export default IssuesStep;
