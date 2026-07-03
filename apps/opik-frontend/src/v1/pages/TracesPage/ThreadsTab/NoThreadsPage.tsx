import React from "react";
import { useTranslation } from "react-i18next";
import { Book, GraduationCap } from "lucide-react";
import noDataThreadsImageUrl from "/images/no-data-threads.png";
import { Button } from "@/ui/button";
import { buildDocsUrl } from "@/v1/lib/utils";
import { useOpenQuickStartDialog } from "@/v1/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import NoDataPage from "@/shared/NoDataPage/NoDataPage";

const NoThreadsPage = () => {
  const { t } = useTranslation();
  const { open: openQuickstart } = useOpenQuickStartDialog();

  return (
    <NoDataPage
      title={t("tracing.noThreadsPage.title")}
      description={t("tracing.noThreadsPage.description")}
      imageUrl={noDataThreadsImageUrl}
      height={188}
      className="px-6"
      buttons={
        <>
          <Button variant="secondary" asChild>
            <a
              href={buildDocsUrl("/tracing/log_chat_conversations")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4"></Book>
              {t("tracing.noThreadsPage.readDocumentation")}
            </a>
          </Button>
          <Button onClick={openQuickstart}>
            <GraduationCap className="mr-2 size-4" />
            {t("tracing.noThreadsPage.exploreQuickstart")}
          </Button>
        </>
      }
    ></NoDataPage>
  );
};

export default NoThreadsPage;
