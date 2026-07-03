import React from "react";
import { useTranslation } from "react-i18next";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Button } from "@/ui/button";
import { PromptVersion } from "@/types/prompts";
import useRestorePromptVersionMutation from "@/api/prompts/useRestorePromptVersionMutation";

type RestoreVersionDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  versionToRestore: PromptVersion | null;
  onSetActiveVersionId: (versionId: string) => void;
};

const RestoreVersionDialog: React.FunctionComponent<
  RestoreVersionDialogProps
> = ({ open, setOpen, versionToRestore, onSetActiveVersionId }) => {
  const { t } = useTranslation();
  const restorePromptVersionMutation = useRestorePromptVersionMutation();
  const isLoading = restorePromptVersionMutation.isPending;

  const handleConfirm = () => {
    if (!versionToRestore) {
      return;
    }

    restorePromptVersionMutation.mutate(
      {
        promptId: versionToRestore.prompt_id,
        versionId: versionToRestore.id,
      },
      {
        onSuccess(data) {
          setOpen(false);
          onSetActiveVersionId(data.id);
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{t("prompt:restoreVersion.title")}</DialogTitle>
          <DialogDescription>
            {t("prompt:restoreVersion.description", {
              commit: versionToRestore?.commit,
            })}
          </DialogDescription>
        </DialogHeader>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">{t("prompt:editPrompt.cancel")}</Button>
          </DialogClose>
          <Button
            onClick={handleConfirm}
            disabled={isLoading || !versionToRestore}
          >
            {isLoading
              ? t("prompt:restoreVersion.restoring")
              : t("prompt:restoreVersion.restore")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default RestoreVersionDialog;
