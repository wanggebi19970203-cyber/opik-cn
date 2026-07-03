import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { Textarea } from "@/ui/textarea";
import { Label } from "@/ui/label";
import { Input } from "@/ui/input";
import { Button } from "@/ui/button";
import useProvideFeedbackMutation from "@/api/feedback/useProvideFeedbackMutation";

type ProvideFeedbackDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const ProvideFeedbackDialog: React.FunctionComponent<
  ProvideFeedbackDialogProps
> = ({ open, setOpen }) => {
  const { t } = useTranslation();
  const [feedback, setFeedback] = useState("");
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");

  const provideFeedbackMutation = useProvideFeedbackMutation();

  const isValid = Boolean(feedback.length);

  useEffect(() => {
    if (!open) {
      setFeedback("");
      setEmail("");
      setName("");
    }
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{t("common.feedback.provideFeedback")}</DialogTitle>
        </DialogHeader>

        <div className="size-full overflow-y-auto pb-4">
          <Label htmlFor="provideFeedback text-foreground-secondary">
            {t("common.feedback.shareYourThoughts")}
          </Label>
          <Textarea
            id="provideFeedback"
            value={feedback}
            onChange={(event) => setFeedback(event.target.value)}
          />
        </div>

        <div className="comet-body-s-accented">
          {t("common.feedback.chatWithUs")}
        </div>
        <div className="comet-body-s max-w-[570px] text-muted-slate">
          {t("common.feedback.chatDescription")}
        </div>

        <div className="flex flex-row items-center gap-4 pb-4">
          <div className="w-full">
            <Label htmlFor="name">{t("common.feedback.yourName")}</Label>
            <Input
              id="name"
              placeholder={t("common.labels.name")}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          <div className="w-full">
            <Label htmlFor="email">{t("common.feedback.yourEmail")}</Label>
            <Input
              id="email"
              placeholder={t("common.labels.email")}
              value={email}
              onChange={(e) => setEmail(e.target.value)}
            />
          </div>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline">{t("common.buttons.cancel")}</Button>
          </DialogClose>
          <DialogClose asChild>
            <Button
              type="submit"
              disabled={!isValid}
              onClick={() => {
                provideFeedbackMutation.mutate({
                  feedback,
                  email,
                  name,
                });
              }}
            >
              {t("common.feedback.sendFeedback")}
            </Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default ProvideFeedbackDialog;
