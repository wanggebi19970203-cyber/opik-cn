import React from "react";
import { useTranslation } from "react-i18next";
import { AnnotationQueue } from "@/types/annotation-queues";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";

interface InstructionsContentProps {
  annotationQueue: AnnotationQueue;
}

const InstructionsContent: React.FunctionComponent<
  InstructionsContentProps
> = ({ annotationQueue }) => {
  const { t } = useTranslation();
  return (
    <div className="rounded-lg border bg-background">
      <div className="p-6">
        <MarkdownPreview>
          {annotationQueue?.instructions ||
            t("common.annotationQueues.noInstructionsProvided")}
        </MarkdownPreview>
      </div>
    </div>
  );
};

export default InstructionsContent;
