import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { SheetClose } from "@/ui/sheet";
import useAppStore from "@/store/AppStore";
import { Link } from "@tanstack/react-router";
import evaluationGifUrl from "/images/playground_evaluation.gif";
import { buildDocsUrl } from "@/v1/lib/utils";
const UsingPlaygroundTab = () => {
  const { t } = useTranslation();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  return (
    <div className="flex flex-col gap-6 rounded-md border bg-background p-6">
      <div className="comet-body-s">
        <div className="pt-1">
          {t('integrationExplorer.playgroundDescription')}
        </div>

        <img className="my-5 block" src={evaluationGifUrl} />

        <div className="pb-1">
          {t('integrationExplorer.learnMorePlayground')}{" "}
          <Button
            size="sm"
            variant="link"
            className="inline-flex h-auto px-0"
            asChild
          >
            <a
              href={buildDocsUrl(
                "/prompt_engineering/playground#running-experiments-in-the-playground",
              )}
              target="_blank"
              rel="noreferrer"
            >
              {t('integrationExplorer.documentationGuide')}
            </a>
          </Button>
        </div>
        <div className="pt-2">
          {t('integrationExplorer.getStartedPlayground')}{" "}
          <SheetClose asChild>
            <Button
              size="sm"
              variant="link"
              className="inline-flex h-auto px-0"
              asChild
            >
              <Link to="/$workspaceName/playground" params={{ workspaceName }}>
                {t('integrationExplorer.playgroundLink')}
              </Link>
            </Button>
          </SheetClose>{" "}
          {t('integrationExplorer.playgroundUsage')}{" "}
          <span className="text-emerald-500">{`{{ variable_name }}`}</span>{" "}
          {t('integrationExplorer.evaluateAgainstSuites')}
        </div>
      </div>
    </div>
  );
};

export default UsingPlaygroundTab;
