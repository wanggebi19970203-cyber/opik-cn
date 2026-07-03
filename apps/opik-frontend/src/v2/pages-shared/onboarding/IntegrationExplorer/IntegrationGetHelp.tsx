import React from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { BookOpen } from "lucide-react";
import HelpGuideDialog from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/HelpGuideDialog";
import { useIntegrationExplorer } from "@/v2/pages-shared/onboarding/IntegrationExplorer/IntegrationExplorerContext";
import { buildDocsUrl } from "@/v2/lib/utils";

type IntegrationGetHelpProps = {
  className?: string;
  label?: string;
};

const IntegrationGetHelp: React.FunctionComponent<IntegrationGetHelpProps> = ({
  className,
  label,
}) => {
  const { t } = useTranslation();
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
        className={className}
        id="integration-get-help-button"
        data-fs-element="IntegrationGetHelpButton"
        asChild
      >
        <a href={buildDocsUrl()} target="_blank" rel="noopener noreferrer">
          <BookOpen className="mr-1.5 size-3.5" />
          {label ?? t("common.buttons.viewDocs")}
        </a>
      </Button>

      <HelpGuideDialog
        open={!!helpGuideDialogOpen}
        setOpen={handleOpenChange}
      />
    </>
  );
};

export default IntegrationGetHelp;
