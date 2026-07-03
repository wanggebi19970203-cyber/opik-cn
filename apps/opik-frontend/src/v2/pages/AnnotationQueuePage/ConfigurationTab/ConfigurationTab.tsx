import React from "react";
import { useTranslation } from "react-i18next";
import { AnnotationQueue } from "@/types/annotation-queues";
import Loader from "@/shared/Loader/Loader";
import InstructionsSection from "@/v2/pages/AnnotationQueuePage/ConfigurationTab/InstructionsSection";
import ScoresSection from "@/v2/pages/AnnotationQueuePage/ConfigurationTab/ScoresSection";
import ReviewersSection from "@/v2/pages/AnnotationQueuePage/ConfigurationTab/ReviewersSection";

interface ConfigurationTabProps {
  annotationQueue?: AnnotationQueue;
}

const ConfigurationTab: React.FunctionComponent<ConfigurationTabProps> = ({
  annotationQueue,
}) => {
  const { t } = useTranslation("pages/annotation-queue");

  if (!annotationQueue) {
    return <Loader message={t("annotationQueue.configuration.loadingMessage")} />;
  }

  return (
    <div className="px-6">
      <InstructionsSection annotationQueue={annotationQueue} />
      <ScoresSection annotationQueue={annotationQueue} />
      <ReviewersSection annotationQueue={annotationQueue} />
    </div>
  );
};

export default ConfigurationTab;
