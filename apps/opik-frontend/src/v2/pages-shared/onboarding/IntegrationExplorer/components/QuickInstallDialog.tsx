import React from "react";
import { useTranslation } from "react-i18next";

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogAutoScrollBody,
} from "@/ui/dialog";
import { Separator } from "@/ui/separator";
import HelpLinks from "./HelpLinks";
import InstallWithAITab from "@/v2/pages-shared/onboarding/InstallWithAITab";

type QuickInstallDialogProps = {
  open: boolean;
  onClose: () => void;
};

const QuickInstallDialog: React.FunctionComponent<QuickInstallDialogProps> = ({
  open,
  onClose,
}) => {
  const { t } = useTranslation();
  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      onClose();
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[720px] gap-2">
        <DialogHeader>
          <DialogTitle>
            {t("onboarding.integrationExplorer.quickInstallTitle")}
          </DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody className="border-0">
          <InstallWithAITab traceReceived={false} showTraceStep={false} />

          <Separator className="my-6" />
          <HelpLinks
            onCloseParentDialog={onClose}
            title={t("onboarding.integrationExplorer.needSomeHelp")}
            description={t(
              "onboarding.integrationExplorer.needSomeHelpDescription",
            )}
          >
            <HelpLinks.InviteDev />
            <HelpLinks.Slack />
            <HelpLinks.WatchTutorial />
          </HelpLinks>
        </DialogAutoScrollBody>
      </DialogContent>
    </Dialog>
  );
};

export default QuickInstallDialog;
