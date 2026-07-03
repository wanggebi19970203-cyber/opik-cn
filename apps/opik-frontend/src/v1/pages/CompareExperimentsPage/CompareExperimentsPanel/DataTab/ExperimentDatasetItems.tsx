import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import SyntaxHighlighter from "@/shared/SyntaxHighlighter/SyntaxHighlighter";
import ImagesListWrapper from "@/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import NoData from "@/shared/NoData/NoData";
import React, { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { DatasetItem } from "@/types/datasets";
import { pick } from "lodash";
import { useProcessedInputData } from "@/hooks/useProcessedInputData";

interface ExperimentDatasetItemsProps {
  data: DatasetItem["data"] | undefined;
  selectedKeys: string[];
}

const ExperimentDatasetItems = ({
  data,
  selectedKeys,
}: ExperimentDatasetItemsProps) => {
  const { t } = useTranslation();
  const selectedData: DatasetItem["data"] = useMemo(() => {
    if (!selectedKeys.length || !data) {
      return {};
    }

    return pick(data, selectedKeys);
  }, [selectedKeys, data]);

  const { media, formattedData } = useProcessedInputData(selectedData);

  const showMedia = media?.length > 0;

  if (!showMedia) {
    return data ? (
      <SyntaxHighlighter
        data={selectedData}
        prettifyConfig={{ fieldType: "input" }}
        preserveKey="syntax-highlighter-compare-experiment-input"
      />
    ) : (
      <NoData />
    );
  }

  return (
    <Accordion
      type="multiple"
      className="w-full"
      defaultValue={["media", "data"]}
    >
      {showMedia ? (
        <AccordionItem value="media" className="border-t">
          <AccordionTrigger>{t("compareExperiments.dataset.media")}</AccordionTrigger>
          <AccordionContent>
            <ImagesListWrapper media={media} />
          </AccordionContent>
        </AccordionItem>
      ) : null}

      <AccordionItem value="data">
        <AccordionTrigger>{t("compareExperiments.dataset.selectedData")}</AccordionTrigger>
        <AccordionContent>
          {formattedData ? (
            <SyntaxHighlighter
              data={formattedData ?? {}}
              prettifyConfig={{ fieldType: "input" }}
              preserveKey="syntax-highlighter-compare-experiment-input"
            />
          ) : (
            <NoData />
          )}
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
};

export default ExperimentDatasetItems;
