import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Trash } from "lucide-react";

import { Button } from "@/ui/button";
import { Alert } from "@/types/alerts";
import useAlertsBatchDeleteMutation from "@/api/alerts/useAlertsBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type AlertsActionsPanelsProps = {
  alerts: Alert[];
};

const AlertsActionsPanel: React.FunctionComponent<AlertsActionsPanelsProps> = ({
  alerts,
}) => {
  const { t } = useTranslation();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !alerts?.length;

  const { mutate } = useAlertsBatchDeleteMutation();

  const deleteAlertsHandler = useCallback(() => {
    mutate({
      ids: alerts.map((a) => a.id!),
    });
  }, [alerts, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteAlertsHandler}
        title={t("alerts.confirmDialog.deleteBatch.title")}
        description={t("alerts.confirmDialog.deleteBatch.description")}
        confirmText={t("alerts.confirmDialog.deleteBatch.confirmText")}
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content={t("alerts.actions.delete")}>
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash className="size-4" />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default AlertsActionsPanel;
