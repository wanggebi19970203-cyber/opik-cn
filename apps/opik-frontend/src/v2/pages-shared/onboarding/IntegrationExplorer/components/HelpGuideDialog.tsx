import React from "react";
import { useTranslation } from "react-i18next";
import { ExternalLink, Book, PlayIcon } from "lucide-react";
import imageTutorialUrl from "/images/tutorial-placeholder.png";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/ui/dialog";
import { Separator } from "@/ui/separator";
import usePluginsStore from "@/store/PluginsStore";
import PlayButton from "@/icons/play-button.svg?react";
import HelpLinks, { VIDEO_TUTORIAL_LINK } from "./HelpLinks";
import { buildDocsUrl } from "@/v2/lib/utils";

type HelpGuideDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const HelpGuideDialog: React.FunctionComponent<HelpGuideDialogProps> = ({
  open,
  setOpen,
}) => {
  const { t } = useTranslation();
  const InviteUsersForm = usePluginsStore((state) => state.InviteUsersForm);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-[790px] gap-2">
        <DialogHeader>
          <DialogTitle>{t("onboarding.integrationExplorer.helpGuide")}</DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody>
          <div className="comet-body-s mb-3 pb-2 text-muted-slate">
            {t("onboarding.integrationExplorer.needHelpGettingStarted")}{" "}
            <a
              href={buildDocsUrl("/quickstart")}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
            >
              {t("onboarding.integrationExplorer.checkOurDocs")}
              <ExternalLink className="size-3" />
            </a>
            .
          </div>

          <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <div className="rounded-lg border bg-background p-4">
              <div className="mb-4 flex flex-col items-start gap-1.5">
                <div className="flex items-center gap-2">
                  <PlayIcon className="size-4 text-muted-slate" />
                  <div className="comet-body-s-accented">
                    {t("onboarding.integrationExplorer.watchGuidedTutorial")}
                  </div>
                </div>

                <div className="comet-body-s text-muted-slate">
                  {t("onboarding.integrationExplorer.watchShortVideo")}
                </div>
              </div>

              <a
                href={VIDEO_TUTORIAL_LINK}
                target="_blank"
                rel="noopener noreferrer"
                className="group relative flex aspect-video cursor-pointer items-center justify-center after:absolute after:inset-0 after:rounded-lg after:bg-black/20 after:opacity-0 after:transition-opacity after:hover:opacity-50"
              >
                <img
                  src={imageTutorialUrl}
                  alt="Comet tutorial"
                  className="size-full object-cover"
                />
                <PlayButton className="absolute left-1/2 top-1/2 size-10 -translate-x-1/2 -translate-y-1/2 opacity-25 transition-opacity group-hover:opacity-80" />
              </a>
            </div>

            <div className="rounded-lg border bg-background p-4">
              <div className="mb-2 flex flex-col items-start gap-1.5">
                <div className="flex items-center gap-2">
                  <Book className="size-4 text-muted-slate" />
                  <div className="comet-body-s-accented">
                    {t("onboarding.integrationExplorer.exploreOurDocumentation")}
                  </div>
                </div>
                <div className="comet-body-s text-muted-slate">
                  {t("onboarding.integrationExplorer.checkOutDocs")}
                </div>
              </div>

              <div className="space-y-2">
                <a
                  href={buildDocsUrl("/quickstart")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  {t("onboarding.integrationExplorer.gettingStartedWithOpik")}
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href={buildDocsUrl("/integrations/overview")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  {t("onboarding.integrationExplorer.opikCookbooks")}
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href={buildDocsUrl("/integrations/overview")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  {t("onboarding.integrationExplorer.integrateOpikWithLlm")}
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href={buildDocsUrl("/tracing/advanced/log_agent_graphs")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  {t("onboarding.integrationExplorer.trackAgentExecution")}
                  <ExternalLink className="size-4" />
                </a>
                <a
                  href={buildDocsUrl("/faq")}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="comet-body-s flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
                >
                  {t("onboarding.integrationExplorer.readFaq")}
                  <ExternalLink className="size-4" />
                </a>
              </div>
            </div>
          </div>

          {InviteUsersForm ? <InviteUsersForm /> : null}

          <Separator className="my-6" />

          <HelpLinks
            onCloseParentDialog={() => setOpen(false)}
            title={t("onboarding.integrationExplorer.notReadyToIntegrate")}
            description={t("onboarding.integrationExplorer.exploreOpikDescription")}
          >
            <HelpLinks.Playground />
            <HelpLinks.DemoProject />
            <HelpLinks.Slack />
          </HelpLinks>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default HelpGuideDialog;
