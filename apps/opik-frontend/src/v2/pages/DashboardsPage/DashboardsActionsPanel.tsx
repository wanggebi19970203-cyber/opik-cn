import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/ui/button";
import { Dashboard } from "@/types/dashboard";
import useDashboardBatchDeleteMutation from "@/api/dashboards/useDashboardBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type DashboardsActionsPanelsProps = {
  dashboards: Dashboard[];
};

const DashboardsActionsPanel: React.FunctionComponent<
  DashboardsActionsPanelsProps
> = ({ dashboards }) => {
  const { t } = useTranslation("pages/dashboards");
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !dashboards?.length;

  const { mutate } = useDashboardBatchDeleteMutation();

  const deleteDashboardsHandler = useCallback(() => {
    mutate({
      ids: dashboards.map((d) => d.id),
    });
  }, [dashboards, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteDashboardsHandler}
        title={t("dashboards.confirmDialog.deleteBatch.title")}
        description={t("dashboards.confirmDialog.deleteBatch.description")}
        confirmText={t("dashboards.confirmDialog.deleteBatch.confirmText")}
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content={t("dashboards.actions.delete")}>
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default DashboardsActionsPanel;
