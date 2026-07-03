import React from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { HelpCircle } from "lucide-react";
import HelpGuideDialog from "@/v1/pages-shared/onboarding/IntegrationExplorer/components/HelpGuideDialog";
import { useIntegrationExplorer } from "@/v1/pages-shared/onboarding/IntegrationExplorer/IntegrationExplorerContext";

type IntegrationGetHelpProps = {
  className?: string;
  label?: string;
};

const IntegrationGetHelp: React.FunctionComponent<IntegrationGetHelpProps> = ({
  className,
  label,
}) => {
  const { t } = useTranslation();
  const displayLabel = label ?? t('integrationExplorer.getHelp');
  const { helpGuideDialogOpen, setHelpGuideDialogOpen } =
    useIntegrationExplorer();

  const handleOpenChange = (newOpen: boolean) => {
    setHelpGuideDialogOpen(newOpen ? true : undefined);
  };

  return (
    <>
      <Button
        variant="outline"
        size="sm"
        onClick={() => handleOpenChange(true)}
        className={className}
        id="integration-get-help-button"
        data-fs-element="IntegrationGetHelpButton"
      >
        <HelpCircle className="mr-1.5 size-3.5" />
        {displayLabel}
      </Button>

      <HelpGuideDialog
        open={!!helpGuideDialogOpen}
        setOpen={handleOpenChange}
      />
    </>
  );
};

export default IntegrationGetHelp;
