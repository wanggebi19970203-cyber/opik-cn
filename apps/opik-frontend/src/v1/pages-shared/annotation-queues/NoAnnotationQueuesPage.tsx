import React from "react";
import { Book, Plus } from "lucide-react";
import { useTranslation } from "react-i18next";
import noDataQueuesImageUrl from "/images/no-data-annotation-queues.png";
import { Button } from "@/ui/button";
import { buildDocsUrl } from "@/v1/lib/utils";
import { usePermissions } from "@/contexts/PermissionsContext";

type NoDataWrapperProps = {
  title: string;
  description: string;
  imageUrl: string;
  buttons: React.ReactNode;
  className?: string;
  height?: number;
};

type NoAnnotationQueuesPageProps = {
  openModal?: () => void;
  height?: number;
  Wrapper: React.FC<NoDataWrapperProps>;
  className?: string;
};

const NoAnnotationQueuesPage: React.FC<NoAnnotationQueuesPageProps> = ({
  openModal,
  Wrapper,
  height,
  className,
}) => {
  const { t } = useTranslation();
  const {
    permissions: { canCreateAnnotationQueues },
  } = usePermissions();

  return (
    <Wrapper
      title={t("common.annotationQueues.organizeYourAnnotations")}
      description={t(
        "common.annotationQueues.organizeYourAnnotationsDescription",
      )}
      imageUrl={noDataQueuesImageUrl}
      height={height}
      className={className}
      buttons={
        <>
          <Button variant="secondary" asChild>
            <a
              href={buildDocsUrl("/evaluation/annotation_queues")}
              target="_blank"
              rel="noreferrer"
            >
              <Book className="mr-2 size-4"></Book>
              {t("common.annotationQueues.readDocumentation")}
            </a>
          </Button>
          {canCreateAnnotationQueues && openModal && (
            <Button onClick={openModal}>
              <Plus className="mr-2 size-4" />
              {t("common.annotationQueues.createYourFirstQueue")}
            </Button>
          )}
        </>
      }
    ></Wrapper>
  );
};

export default NoAnnotationQueuesPage;
