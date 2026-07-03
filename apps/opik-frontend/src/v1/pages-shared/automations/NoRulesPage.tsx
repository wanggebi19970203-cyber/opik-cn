import React from "react";
import { useTranslation } from "react-i18next";
import { Book, Plus } from "lucide-react";
import noDataRulesImageUrl from "/images/no-data-rules.png";
import { Button } from "@/ui/button";
import { buildDocsUrl } from "@/v1/lib/utils";
type NoDataWrapperProps = {
  title: string;
  description: string;
  imageUrl: string;
  buttons: React.ReactNode;
  className?: string;
  height?: number;
};

type NoRulesPageProps = {
  openModal?: () => void;
  height?: number;
  Wrapper: React.FC<NoDataWrapperProps>;
  className?: string;
};

const NoRulesPage: React.FC<NoRulesPageProps> = ({
  openModal,
  Wrapper,
  height,
  className,
}) => {
  const { t } = useTranslation();

  return (
    <Wrapper
      title={t("onlineEvaluation.empty.title")}
      description={t("onlineEvaluation.empty.description")}
      imageUrl={noDataRulesImageUrl}
      height={height}
      className={className}
      buttons={
        <>
          <Button variant="secondary" asChild>
            <a
              href={buildDocsUrl("/production/rules")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4"></Book>
              {t("common.table.readDocs")}
            </a>
          </Button>
          {openModal && (
            <Button onClick={openModal}>
              <Plus className="mr-2 size-4" />
              {t("onlineEvaluation.empty.action")}
            </Button>
          )}
        </>
      }
    ></Wrapper>
  );
};

export default NoRulesPage;
