import React from "react";
import { useTranslation } from "react-i18next";
import { Tag } from "@/ui/tag";
import { AnnotationQueue } from "@/types/annotation-queues";
import AnnotationQueueProgress from "@/v2/pages-shared/annotation-queues/AnnotationQueueProgress";
import { SquareCheck } from "lucide-react";

interface AnnotationQueueProgressTagProps {
  annotationQueue: AnnotationQueue;
}

const AnnotationQueueProgressTag: React.FunctionComponent<
  AnnotationQueueProgressTagProps
> = ({ annotationQueue }) => {
  const { t } = useTranslation("pages/annotation-queue");

  return (
    <AnnotationQueueProgress annotationQueue={annotationQueue}>
      {({ averageProgress, progressPercentage, itemsCount }) => (
        <Tag
          variant="transparent"
          size="md"
          className="comet-body-s-accented flex cursor-pointer items-center gap-1 text-muted-slate"
        >
          <SquareCheck className="size-3 shrink-0 text-[var(--color-red)]" />
          {t("annotationQueue.progress.label", {
            averageProgress,
            itemsCount,
            progressPercentage,
          })}
        </Tag>
      )}
    </AnnotationQueueProgress>
  );
};

export default AnnotationQueueProgressTag;
