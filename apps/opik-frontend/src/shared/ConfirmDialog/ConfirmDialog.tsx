import React from "react";
import { useTranslation } from "react-i18next";
import { Button, ButtonProps } from "@/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";

type ConfirmDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm?: () => void;
  onCancel?: () => void;
  title: string;
  description: React.ReactNode;
  confirmText: string;
  cancelText?: string;
  confirmButtonVariant?: ButtonProps["variant"];
};

const ConfirmDialog: React.FunctionComponent<ConfirmDialogProps> = ({
  open,
  setOpen,
  onConfirm,
  onCancel,
  title,
  description,
  confirmText,
  cancelText,
  confirmButtonVariant = "default",
}) => {
  const { t } = useTranslation("common");

  const resolvedConfirmText = confirmText ?? t("dialogs.confirmText");
  const resolvedCancelText = cancelText ?? t("dialogs.cancelText");

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>{description}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" onClick={onCancel}>
              {resolvedCancelText}
            </Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              variant={confirmButtonVariant}
              onClick={onConfirm}
            >
              {resolvedConfirmText}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default ConfirmDialog;
