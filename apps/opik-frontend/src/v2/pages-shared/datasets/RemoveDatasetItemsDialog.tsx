import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { Checkbox } from "@/ui/checkbox";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Label } from "@/ui/label";
import { useDatasetItemDeletePreference } from "./hooks/useDatasetItemDeletePreference";

type RemoveDatasetItemsDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm: () => void;
  title?: string;
  description?: string;
  confirmText?: string;
};

const RemoveDatasetItemsDialog = ({
  open,
  setOpen,
  onConfirm,
  title,
  description,
  confirmText,
}: RemoveDatasetItemsDialogProps) => {
  const { t } = useTranslation("datasets");
  const [dontAskAgain, setDontAskAgain] = useDatasetItemDeletePreference();

  const resolvedTitle = title ?? t("removeItems.title");
  const resolvedDescription = description ?? t("removeItems.description");
  const resolvedConfirmText = confirmText ?? t("removeItems.confirmText");

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{resolvedTitle}</DialogTitle>
        </DialogHeader>

        <div className="flex flex-col gap-3">
          <div className="comet-body-s text-muted-slate">
            {resolvedDescription}
          </div>
          <Label className="flex cursor-pointer items-center gap-2">
            <Checkbox
              id="dont-show-again"
              checked={dontAskAgain}
              onCheckedChange={(v) => setDontAskAgain(v === true)}
            />
            <div className="comet-body-s text-muted-slate">
              {t("removeItems.dontShowAgain")}
            </div>
          </Label>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">{t("removeItems.cancel")}</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button type="submit" variant="destructive" onClick={onConfirm}>
              {resolvedConfirmText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default RemoveDatasetItemsDialog;
