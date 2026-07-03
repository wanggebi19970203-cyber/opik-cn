import React from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import { buildDocsUrl } from "@/v2/lib/utils";

const DAILY_RUN_TIME_LABEL = "00:05 UTC";

// TODO: point at the dedicated Diagnostics docs page once it ships.
const DIAGNOSTICS_DOCS_URL = buildDocsUrl();

type TurnOnDiagnosticDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
  onConfirm: () => void;
  isPending: boolean;
};

const TurnOnDiagnosticDialog: React.FC<TurnOnDiagnosticDialogProps> = ({
  open,
  setOpen,
  onConfirm,
  isPending,
}) => {
  const { t } = useTranslation();
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="max-w-lg sm:max-w-[560px]">
        <DialogHeader>
          <DialogTitle>{t("signals.turnOnDiagnostic.title")}</DialogTitle>
        </DialogHeader>

        <div className="comet-body-s text-muted-slate">
          {t("signals.turnOnDiagnostic.description", { time: DAILY_RUN_TIME_LABEL })}{" "}
          <a
            href={DIAGNOSTICS_DOCS_URL}
            target="_blank"
            rel="noopener noreferrer"
            className="text-[var(--color-primary)] hover:underline"
          >
            {t("signals.turnOnDiagnostic.learnMore")}
          </a>
        </div>

        <DialogFooter>
          <Button variant="outline" onClick={() => setOpen(false)}>
            {t("signals.turnOnDiagnostic.cancel")}
          </Button>
          <Button onClick={onConfirm} disabled={isPending}>
            {t("signals.turnOnDiagnostic.runFirstDiagnostic")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
};

export default TurnOnDiagnosticDialog;
