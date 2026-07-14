import React, { useCallback } from "react";
import { ListTree } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Tag } from "@/ui/tag";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import TraceLogsSidebar, { TraceLogsViewConfig } from "./TraceLogsSidebar";
import { useTraceLogsSidebarControls } from "./useTraceLogsSidebarControls";
import { LOGS_SOURCE } from "@/types/traces";
import { Filter } from "@/types/filters";

type TraceLogsSidebarButtonProps = {
  projectId: string;
  logsSource?: LOGS_SOURCE;
  sourceFilters?: Filter[];
  lockScope?: boolean;
  scopeLabel?: string;
  variant?: "tag" | "icon";
  title?: string;
  label?: string;
  viewConfig?: TraceLogsViewConfig;
  renderSidebar?: boolean;
};

const TraceLogsSidebarButton: React.FunctionComponent<
  TraceLogsSidebarButtonProps
> = ({
  projectId,
  logsSource,
  sourceFilters,
  lockScope = false,
  scopeLabel,
  variant = "tag",
  title,
  label,
  viewConfig,
  renderSidebar = true,
}) => {
  const { t } = useTranslation("tracing");
  const { open, openSidebar, closeSidebar } = useTraceLogsSidebarControls();
  const resolvedLabel = label ?? t("traceLogs.goToLogs");

  const handleOpen = useCallback(
    () =>
      openSidebar(
        sourceFilters,
        lockScope ? { locked: true, label: scopeLabel } : undefined,
      ),
    [openSidebar, sourceFilters, lockScope, scopeLabel],
  );

  const trigger =
    variant === "icon" ? (
      <TooltipWrapper content={resolvedLabel}>
        <Button
          data-testid="playground-logs-sidebar-button"
          variant="outline"
          size="icon-2xs"
          onClick={handleOpen}
        >
          <ListTree />
        </Button>
      </TooltipWrapper>
    ) : (
      <TooltipWrapper content={resolvedLabel}>
        <Tag
          size="md"
          variant="transparent"
          className="flex shrink-0 cursor-pointer items-center gap-1 hover:bg-primary-foreground hover:text-foreground active:bg-primary-100 active:text-foreground"
          onClick={handleOpen}
        >
          <ListTree
            className="size-3 shrink-0"
            style={{ color: "var(--color-green)" }}
          />
          <div className="comet-body-s-accented truncate text-muted-slate">
            {resolvedLabel}
          </div>
        </Tag>
      </TooltipWrapper>
    );

  return (
    <>
      {trigger}
      {renderSidebar && (
        <TraceLogsSidebar
          open={open}
          onClose={closeSidebar}
          projectId={projectId}
          logsSource={logsSource}
          title={title}
          viewConfig={viewConfig}
        />
      )}
    </>
  );
};

export default TraceLogsSidebarButton;
