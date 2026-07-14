import React from "react";
import { useTranslation } from "react-i18next";
import { AnnotationQueue } from "@/types/annotation-queues";
import InstructionsContent from "@/v1/pages-shared/annotation-queues/InstructionsContent";

interface InstructionsSectionProps {
  annotationQueue: AnnotationQueue;
}

const InstructionsSection: React.FunctionComponent<
  InstructionsSectionProps
> = ({ annotationQueue }) => {
  const { t } = useTranslation("annotation-queues");

  return (
    <div>
      <h2 className="comet-title-s truncate break-words pb-3 pt-2">
        {t("annotationQueues.dialog.instructionsLabel")}
      </h2>
      <InstructionsContent annotationQueue={annotationQueue} />
    </div>
  );
};

export default InstructionsSection;
