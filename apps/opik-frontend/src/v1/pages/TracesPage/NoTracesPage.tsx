import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Book, GraduationCap } from "lucide-react";
import noDataTracesImageUrl from "/images/no-data-traces.png";
import noDataSpansImageUrl from "/images/no-data-spans.png";
import { Button } from "@/ui/button";
import { buildDocsUrl } from "@/v1/lib/utils";
import { useOpenQuickStartDialog } from "@/v1/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import NoDataPage from "@/shared/NoDataPage/NoDataPage";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";

type NoTracesPageProps = {
  type?: TRACE_DATA_TYPE;
};

const NoTracesPage: React.FC<NoTracesPageProps> = ({
  type = TRACE_DATA_TYPE.traces,
}) => {
  const { t } = useTranslation();
  const { open: openQuickstart } = useOpenQuickStartDialog();

  const imageUrl = useMemo(() => {
    switch (type) {
      case TRACE_DATA_TYPE.traces:
        return noDataTracesImageUrl;
      case TRACE_DATA_TYPE.spans:
        return noDataSpansImageUrl;
      default:
        return noDataTracesImageUrl;
    }
  }, [type]);

  return (
    <NoDataPage
      title={t("tracing.noTracesPage.title")}
      description={t("tracing.noTracesPage.description")}
      imageUrl={imageUrl}
      height={188}
      className="px-6"
      buttons={
        <>
          <Button variant="secondary" asChild>
            <a
              href={buildDocsUrl("/tracing/log_traces")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4"></Book>
              {t("tracing.noTracesPage.readDocumentation")}
            </a>
          </Button>
          <Button onClick={openQuickstart}>
            <GraduationCap className="mr-2 size-4" />
            {t("tracing.noTracesPage.exploreQuickstart")}
          </Button>
        </>
      }
    ></NoDataPage>
  );
};

export default NoTracesPage;
