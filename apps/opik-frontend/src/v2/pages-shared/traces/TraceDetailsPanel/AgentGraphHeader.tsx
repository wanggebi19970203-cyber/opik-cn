import React from "react";
import { useTranslation } from "react-i18next";
import { ChevronDown, ChevronUp, Expand } from "lucide-react";

import { cn } from "@/lib/utils";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type AgentGraphHeaderProps = {
  isCollapsed: boolean;
  onToggleCollapse: () => void;
  onFullscreen: () => void;
  border: "top" | "bottom";
};

const AgentGraphHeader: React.FC<AgentGraphHeaderProps> = ({
  isCollapsed,
  onToggleCollapse,
  onFullscreen,
  border,
}) => {
  const { t } = useTranslation("tracing");
  return (
    <div
      className={cn(
        "flex h-10 shrink-0 items-center justify-between bg-muted/50 px-4",
        border === "top" ? "border-t" : "border-b",
      )}
    >
      <span className="comet-body-xs-accented">{t("detailsPanel.agentGraph")}</span>
      <div className="flex items-center gap-1">
        <TooltipWrapper content={t("agentGraph.fullSize")}>
          <Button variant="ghost" size="icon-2xs" onClick={onFullscreen}>
            <Expand className="size-3.5" />
          </Button>
        </TooltipWrapper>
        <TooltipWrapper content={isCollapsed ? t("agentGraph.expandGraph") : t("agentGraph.collapseGraph")}>
          <Button variant="ghost" size="2xs" onClick={onToggleCollapse}>
            {isCollapsed ? (
              <ChevronUp className="size-4" />
            ) : (
              <ChevronDown className="size-4" />
            )}
          </Button>
        </TooltipWrapper>
      </div>
    </div>
  );
};

export default AgentGraphHeader;
