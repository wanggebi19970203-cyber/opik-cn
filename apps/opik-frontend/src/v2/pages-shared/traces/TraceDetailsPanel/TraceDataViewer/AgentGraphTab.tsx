import React from "react";
import { useTranslation } from "react-i18next";
import { AgentGraphData } from "@/types/traces";
import MermaidDiagram from "@/shared/MermaidDiagram/MermaidDiagram";
import ZoomPanContainer from "@/shared/ZoomPanContainer/ZoomPanContainer";

type AgentGraphTabProps = {
  data: AgentGraphData;
};

const AgentGraphTab: React.FC<AgentGraphTabProps> = ({ data }) => {
  const { t } = useTranslation("tracing");
  return (
    <ZoomPanContainer
      dialogTitle={t("detailsPanel.agentGraph")}
      expandButton={false}
    >
      <MermaidDiagram chart={data.data} />
    </ZoomPanContainer>
  );
};

export default AgentGraphTab;
