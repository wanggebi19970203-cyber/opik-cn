import React from "react";
import { useTranslation } from "react-i18next";
import DashboardWidget from "./DashboardWidget";
import { DashboardWidget as DashboardWidgetType } from "@/types/dashboard";
import { useDashboardStore, selectReadOnly } from "@/store/DashboardStore";

interface DashboardWidgetDisabledProps {
  widget: DashboardWidgetType;
  disabledMessage?: string;
}

const DashboardWidgetDisabled: React.FunctionComponent<
  DashboardWidgetDisabledProps
> = ({ widget, disabledMessage }) => {
  const { t } = useTranslation();
  const readOnly = useDashboardStore(selectReadOnly);

  return (
    <DashboardWidget className="opacity-60">
      <DashboardWidget.Header
        title={widget.title || widget.generatedTitle || ""}
        subtitle={widget.subtitle}
        readOnly={readOnly}
        dragHandle={<DashboardWidget.DragHandle />}
      />
      <DashboardWidget.Content>
        <DashboardWidget.DisabledState
          title={t("common:dashboard.widgetNotAvailable")}
          message={disabledMessage}
        />
      </DashboardWidget.Content>
    </DashboardWidget>
  );
};

export default DashboardWidgetDisabled;
