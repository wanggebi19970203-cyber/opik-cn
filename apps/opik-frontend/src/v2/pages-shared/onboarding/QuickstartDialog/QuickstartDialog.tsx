import SideDialog from "@/shared/SideDialog/SideDialog";
import React from "react";
import { useTranslation } from "react-i18next";
import { SheetTopBar } from "@/ui/sheet";
import { IntegrationExplorer } from "@/v2/pages-shared/onboarding/IntegrationExplorer";
import { useLayoutDialog } from "@/hooks/useLayoutDialog";

const useOpenQuickStartDialog = () => useLayoutDialog("quickstart");

const QuickstartDialog: React.FC = () => {
  const { t } = useTranslation();
  const { isOpen, setOpen } = useOpenQuickStartDialog();

  return (
    <SideDialog
      open={isOpen}
      setOpen={setOpen}
      header={<SheetTopBar variant="info" title={t("common.table.quickstartGuide")} />}
    >
      <div className="flex max-h-full w-full min-w-fit flex-col overflow-y-auto px-20 pb-20 pt-4">
        <div className="comet-body-s mb-10 text-muted-slate">
          {t("common.shared.opikDescription")}
        </div>

        <IntegrationExplorer source="quickstart-dialog">
          <div className="mb-8 flex items-center justify-between gap-6">
            <IntegrationExplorer.Search />

            <div className="flex items-center gap-3">
              <IntegrationExplorer.CopyApiKey />
              <IntegrationExplorer.GetHelp />
            </div>
          </div>

          <div className="mb-4 grid grid-cols-1 gap-4 lg:grid-cols-2">
            <IntegrationExplorer.QuickInstall />
            <IntegrationExplorer.TypeScriptSDK />
          </div>

          <IntegrationExplorer.Tabs>
            <IntegrationExplorer.Grid />
          </IntegrationExplorer.Tabs>
        </IntegrationExplorer>
      </div>
    </SideDialog>
  );
};

export { useOpenQuickStartDialog };
export default QuickstartDialog;
