import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Switch } from "@/ui/switch";
import { Separator } from "@/ui/separator";
import { AGENT_INSIGHTS_JOB_STATUS } from "@/types/signals";
import useUpdateAgentInsightsJobMutation from "@/api/signals/useUpdateAgentInsightsJobMutation";

type DiagnosticsSettingsDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  projectId: string;
  enabled: boolean;
};

const DiagnosticsSettingsDialog: React.FC<DiagnosticsSettingsDialogProps> = ({
  open,
  setOpen,
  projectId,
  enabled,
}) => {
  const { t } = useTranslation();
  const [on, setOn] = useState(enabled);
  const updateMutation = useUpdateAgentInsightsJobMutation();

  useEffect(() => {
    if (open) setOn(enabled);
  }, [open, enabled]);

  const handleSave = () => {
    updateMutation.mutate(
      {
        projectId,
        status: on
          ? AGENT_INSIGHTS_JOB_STATUS.enabled
          : AGENT_INSIGHTS_JOB_STATUS.disabled,
      },
      { onSuccess: () => setOpen(false) },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[600px]">
        <DialogHeader>
          <DialogTitle>{t("signals.diagnosticsSettings.title")}</DialogTitle>
        </DialogHeader>

        <div className="flex items-center gap-3 py-2">
          <Switch checked={on} onCheckedChange={setOn} />
          <div className="flex flex-col gap-0.5">
            <span className="comet-body-s text-foreground">
              {t("signals.diagnosticsSettings.dailyDiagnosticsOn")}
            </span>
            <span className="comet-body-s text-light-slate">
              {t("signals.diagnosticsSettings.dailyDiagnosticsDescription")}
            </span>
          </div>
        </div>

        <Separator />

        <div className="flex flex-col gap-1 py-2">
          <span className="comet-body-s-accented text-foreground">{t("signals.diagnosticsSettings.billing")}</span>
          <span className="comet-body-s text-muted-slate">
            {t("signals.diagnosticsSettings.billingDescription")}{" "}
            {/* TODO: wire to the Ollie/Comet billing page */}
            <button
              type="button"
              className="text-foreground underline underline-offset-2 hover:text-primary"
            >
              {t("signals.diagnosticsSettings.viewBilling")}
            </button>
          </span>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            {t("signals.diagnosticsSettings.cancel")}
          </Button>
          <Button onClick={handleSave} disabled={updateMutation.isPending}>
            {t("signals.diagnosticsSettings.saveChanges")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default DiagnosticsSettingsDialog;
