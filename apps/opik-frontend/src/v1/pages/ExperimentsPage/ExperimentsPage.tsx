import React from "react";
import { useTranslation } from "react-i18next";

import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v1/constants/explainers";
import ExplainerDescription from "@/shared/ExplainerDescription/ExplainerDescription";
import PageBodyScrollContainer from "@/v1/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import GeneralDatasetsTab from "./GeneralDatasetsTab/GeneralDatasetsTab";

const ExperimentsPage: React.FC = () => {
  const { t } = useTranslation();

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="pb-1 pt-6"
        direction="horizontal"
        limitWidth
      >
        <h1 className="comet-title-l truncate break-words">
          {t("experiments.title")}
        </h1>
      </PageBodyStickyContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <ExplainerDescription
          {...EXPLAINERS_MAP[EXPLAINER_ID.whats_an_experiment]}
        />
      </PageBodyStickyContainer>
      <GeneralDatasetsTab />
    </PageBodyScrollContainer>
  );
};

export default ExperimentsPage;
