import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import dayjs from "dayjs";
import usePluginsStore from "@/store/PluginsStore";
import { formatLocalTimeAsUtc, parseUtcTimeToLocalDate } from "@/lib/date";
import { Button } from "@/ui/button";
import { Textarea } from "@/ui/textarea";
import { Switch } from "@/ui/switch";
import { Separator } from "@/ui/separator";
import TimeInput from "@/shared/TimeInput/TimeInput";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { ReportPreferenceSettings } from "@/types/ollie-reports";

type SettingsDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  enabled: boolean;
  scheduleTime: string;
  customPrompt: string;
  onSave: (settings: ReportPreferenceSettings) => void;
};

export default function SettingsDialog({
  open,
  onOpenChange,
  enabled,
  scheduleTime,
  customPrompt,
  onSave,
}: SettingsDialogProps) {
  const { t } = useTranslation("pages/project-home");
  const [localEnabled, setLocalEnabled] = useState(enabled);
  const [timeDate, setTimeDate] = useState<Date | undefined>(() =>
    parseUtcTimeToLocalDate(scheduleTime),
  );
  const [localCustomPrompt, setLocalCustomPrompt] = useState(customPrompt);
  const BillingLink = usePluginsStore((state) => state.BillingLink);

  useEffect(() => {
    if (open) {
      setLocalEnabled(enabled);
      setTimeDate(parseUtcTimeToLocalDate(scheduleTime));
      setLocalCustomPrompt(customPrompt);
    }
  }, [open, enabled, scheduleTime, customPrompt]);

  const handleSave = () => {
    const utcTime = timeDate
      ? formatLocalTimeAsUtc(dayjs(timeDate).format("HH:mm:ss"))
      : scheduleTime;
    onSave({
      enabled: localEnabled,
      scheduleTime: utcTime,
      customPrompt: localCustomPrompt,
    });
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg sm:max-w-[580px]">
        <DialogHeader>
          <DialogTitle>{t("dailyBriefing.settingsDialog.title")}</DialogTitle>
          <DialogDescription asChild className="pt-4">
            <div className="flex flex-col gap-4">
              <div className="flex items-center gap-3">
                <Switch
                  checked={localEnabled}
                  onCheckedChange={setLocalEnabled}
                  size="sm"
                />
                <div>
                  {localEnabled ? (
                    <>
                      <p className="font-medium text-foreground">
                        {t("dailyBriefing.settingsDialog.active")}
                      </p>
                      <p className="text-light-slate">
                        {t("dailyBriefing.settingsDialog.activeDescription")}
                      </p>
                    </>
                  ) : (
                    <>
                      <p className="font-medium text-foreground">
                        {t("dailyBriefing.settingsDialog.paused")}
                      </p>
                      <p className="text-light-slate">
                        {t("dailyBriefing.settingsDialog.pausedDescription")}
                      </p>
                    </>
                  )}
                </div>
              </div>

              <Separator />

              <div className="flex flex-col gap-3">
                <div className="flex flex-col gap-1">
                  <p className="font-medium text-foreground">{t("dailyBriefing.settingsDialog.scheduledFor")}</p>
                  <TimeInput
                    date={timeDate}
                    setDate={setTimeDate}
                    disabled={!localEnabled}
                  />
                </div>

                <div className="flex flex-col gap-1">
                  <p className="font-medium text-foreground">
                    {t("dailyBriefing.settingsDialog.customInstructions")}
                  </p>
                  <Textarea
                    value={localCustomPrompt}
                    onChange={(e) => setLocalCustomPrompt(e.target.value)}
                    placeholder={t("dailyBriefing.settingsDialog.customInstructionsPlaceholder")}
                    rows={4}
                    maxLength={5000}
                    className="min-h-0"
                    disabled={!localEnabled}
                  />
                </div>

                <div className="flex flex-col gap-1 py-2">
                  <p className="font-medium text-foreground">{t("dailyBriefing.settingsDialog.billing")}</p>
                  <p className="text-foreground">
                    {t("dailyBriefing.settingsDialog.billingDescription")} {BillingLink && <BillingLink />}
                  </p>
                </div>
              </div>
            </div>
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t("dailyBriefing.settingsDialog.cancel")}
          </Button>
          <Button onClick={handleSave}>{t("dailyBriefing.settingsDialog.saveChanges")}</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
