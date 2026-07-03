import React from "react";
import { Loader2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { LATEST_VERSION_TAG } from "@/constants/datasets";
import VersionForm, { VersionFormData } from "./VersionForm";

const ADD_VERSION_FORM_ID = "add-version-form";

type AddVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm?: (tags?: string[], changeDescription?: string) => void;
  isSubmitting?: boolean;
};

const AddVersionDialog: React.FC<AddVersionDialogProps> = ({
  open,
  setOpen,
  onConfirm,
  isSubmitting,
}) => {
  const { t } = useTranslation("datasets");
  const handleSubmit = (data: VersionFormData) => {
    onConfirm?.(data.tags, data.versionNote);
  };

  const handleCancel = () => {
    setOpen(false);
  };

  const handleOpenChange = (newOpen: boolean) => {
    setOpen(newOpen);
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{t('addVersion.title')}</DialogTitle>
        </DialogHeader>

        <p className="text-sm text-muted-foreground">
          {t('addVersion.description')}
        </p>

        <VersionForm
          id={ADD_VERSION_FORM_ID}
          onSubmit={handleSubmit}
          immutableTags={[LATEST_VERSION_TAG]}
        />

        <DialogFooter className="gap-3 border-t pt-6 md:gap-0">
          <Button
            type="button"
            variant="outline"
            onClick={handleCancel}
            disabled={isSubmitting}
          >
            {t('addVersion.cancel')}
          </Button>
          <Button
            type="submit"
            form={ADD_VERSION_FORM_ID}
            disabled={isSubmitting}
          >
            {isSubmitting && <Loader2 className="mr-2 size-4 animate-spin" />}
            {t('addVersion.saveChanges')}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default AddVersionDialog;
