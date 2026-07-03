import React from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink, Book, MonitorPlay } from "lucide-react";

type HelpResourcesProps = {
  title: string;
};

const HelpResources: React.FC<HelpResourcesProps> = ({ title }) => {
  const { t } = useTranslation();

  return (
    <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
      <div className="rounded-lg border bg-background p-4">
        <div className="mb-4 flex flex-col items-start gap-1.5">
          <div className="flex items-center gap-2">
            <MonitorPlay className="size-4 text-muted-slate" />
            <div className="comet-body-s-accented">
              {t("helpResources.watchTutorial")}
            </div>
          </div>

          <div className="comet-body-s text-muted-slate">
            {t("helpResources.watchTutorialDescription", { title })}
          </div>
        </div>

        <div className="relative flex aspect-video items-center justify-center rounded-lg border border-muted-foreground/25 bg-muted">
          <div className="text-center">
            <MonitorPlay className="mx-auto mb-2 size-16 text-muted-foreground/50" />
            <p className="comet-body-s text-muted-foreground">
              {t("helpResources.tutorialComingSoon", { title })}
            </p>
          </div>
        </div>
      </div>

      <div className="rounded-lg border bg-background p-4">
        <div className="mb-2 flex flex-col items-start gap-1.5">
          <div className="flex items-center gap-2">
            <Book className="size-4 text-muted-slate" />
            <div className="comet-body-s-accented">
              {t("helpResources.exploreDocumentation")}
            </div>
          </div>
          <div className="comet-body-s text-muted-slate">
            {t("helpResources.exploreDocumentationDescription", { title })}
          </div>
        </div>

        <div className="space-y-2">
          <a
            href="https://placeholder-getting-started.com"
            target="_blank"
            rel="noopener noreferrer"
            className="comet-body-s inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
          >
            {t("helpResources.gettingStartedWith", { title })}
            <ExternalLink className="size-4" />
          </a>
          <a
            href="https://placeholder-integration-guide.com"
            target="_blank"
            rel="noopener noreferrer"
            className="comet-body-s inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
          >
            {t("helpResources.integrationGuide", { title })}
            <ExternalLink className="size-4" />
          </a>
          <a
            href="https://placeholder-best-practices.com"
            target="_blank"
            rel="noopener noreferrer"
            className="comet-body-s inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
          >
            {t("helpResources.bestPracticesWith", { title })}
            <ExternalLink className="size-4" />
          </a>
          <a
            href="https://placeholder-troubleshooting.com"
            target="_blank"
            rel="noopener noreferrer"
            className="comet-body-s inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
          >
            {t("helpResources.troubleshooting", { title })}
            <ExternalLink className="size-4" />
          </a>
        </div>
      </div>
    </div>
  );
};

export default HelpResources;
