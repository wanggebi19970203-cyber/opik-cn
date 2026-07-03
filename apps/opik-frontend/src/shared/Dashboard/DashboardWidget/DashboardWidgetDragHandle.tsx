import React from "react";
import { GripHorizontal } from "lucide-react";
import { useTranslation } from "react-i18next";

import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

const DashboardWidgetDragHandle: React.FunctionComponent = () => {
  const { t } = useTranslation();
  return (
    <TooltipWrapper content={t("common:dashboard.dragToReposition")}>
      <div className="comet-drag-handle flex w-full cursor-grab items-center justify-center text-light-slate hover:text-foreground active:cursor-grabbing">
        <GripHorizontal className="size-3" />
      </div>
    </TooltipWrapper>
  );
};

export default DashboardWidgetDragHandle;
