import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { Switch } from "@/ui/switch";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";

type TurnOnDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirm: (runImmediately: boolean) => void;
  scheduleTimeLocal: string;
};

export default function TurnOnDialog({
  open,
  onOpenChange,
  onConfirm,
  scheduleTimeLocal,
}: TurnOnDialogProps) {
  const { t } = useTranslation();
  const [runImmediately, setRunImmediately] = useState(true);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{t("dailyBriefing.turnOnDialogTitle")}</DialogTitle>
          <DialogDescription asChild className="pt-6">
            <div className="flex flex-col gap-6">
              <p>
                {t("dailyBriefing.turnOnDialogDescription", { time: scheduleTimeLocal })}
              </p>
              <p>
                {t("dailyBriefing.turnOnDialogTokenInfo")}{" "}
                <button className="underline underline-offset-4 hover:text-primary">
                  {t("dailyBriefing.turnOnDialogLearnMore")}
                </button>
              </p>
              <div className="flex items-center gap-3">
                <Switch
                  checked={runImmediately}
                  onCheckedChange={setRunImmediately}
                  size="sm"
                />
                <div>
                  <p className="font-medium text-foreground">
                    {t("dailyBriefing.turnOnDialogRunImmediately")}
                  </p>
                  <p className="text-light-slate">
                    {t("dailyBriefing.turnOnDialogRunImmediatelyDescription")}
                  </p>
                </div>
              </div>
            </div>
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t("dailyBriefing.turnOnDialogCancel")}
          </Button>
          <Button onClick={() => onConfirm(runImmediately)}>{t("dailyBriefing.turnOnDialogConfirm")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
