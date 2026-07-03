import React from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { useToast } from "@/ui/use-toast";
import { TRUNCATION_DISABLED_MAX_PAGE_SIZE } from "@/constants/shared";
import { AlertTriangle } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/ui/alert";

type EditTruncationToggleDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  currentValue: boolean;
  onConfirm: (enabled: boolean) => void;
};

const EditTruncationToggleDialog: React.FC<EditTruncationToggleDialogProps> = ({
  open,
  setOpen,
  currentValue,
  onConfirm,
}) => {
  const { t } = useTranslation("pages/settings");
  const { toast } = useToast();

  const handleConfirm = () => {
    const newValue = !currentValue;
    onConfirm(newValue);
    setOpen(false);

    if (!newValue) {
      toast({
        title: t("settings.workspacePreferences.truncationToggle.toastDisabledTitle"),
        description: t("settings.workspacePreferences.truncationToggle.toastDisabledDescription"),
      });
    } else {
      toast({
        title: t("settings.workspacePreferences.truncationToggle.toastEnabledTitle"),
        description: t("settings.workspacePreferences.truncationToggle.toastEnabledDescription"),
      });
    }
  };

  const isDisabling = currentValue === true;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>
            {isDisabling
              ? t("settings.workspacePreferences.truncationToggle.disableTitle")
              : t("settings.workspacePreferences.truncationToggle.enableTitle")}
          </DialogTitle>
          <DialogDescription>
            {isDisabling
              ? t("settings.workspacePreferences.truncationToggle.disableDescription", { limit: TRUNCATION_DISABLED_MAX_PAGE_SIZE })
              : t("settings.workspacePreferences.truncationToggle.enableDescription")}
          </DialogDescription>
        </DialogHeader>

        {isDisabling && (
          <Alert variant="destructive" size="sm">
            <AlertTriangle />
            <AlertTitle size="sm" className="text-destructive">
              {t("settings.workspacePreferences.truncationToggle.performanceImpactTitle")}
            </AlertTitle>
            <AlertDescription size="sm" className="text-foreground">
              {t("settings.workspacePreferences.truncationToggle.performanceImpactDescription")}
            </AlertDescription>
          </Alert>
        )}

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">{t("settings.workspacePreferences.cancel")}</Button>
          </DialogClose>
          <Button variant="default" onClick={handleConfirm}>
            {isDisabling
              ? t("settings.workspacePreferences.truncationToggle.disableButton")
              : t("settings.workspacePreferences.truncationToggle.enableButton")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default EditTruncationToggleDialog;
