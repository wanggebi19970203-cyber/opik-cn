import React from "react";
import { useTranslation } from "react-i18next";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";

interface OverrideVersionDialogProps {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm: () => void;
}

const OverrideVersionDialog: React.FC<OverrideVersionDialogProps> = ({
  open,
  setOpen,
  onConfirm,
}) => {
  const { t } = useTranslation("datasets");

  return (
    <ConfirmDialog
      open={open}
      setOpen={setOpen}
      onConfirm={onConfirm}
      title={t("overrideVersion.title")}
      description={t("overrideVersion.description")}
      confirmText={t("overrideVersion.confirmText")}
      cancelText={t("overrideVersion.cancelText")}
      confirmButtonVariant="destructive"
    />
  );
};

export default OverrideVersionDialog;
