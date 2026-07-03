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
import CodeHighlighter from "@/shared/CodeHighlighter/CodeHighlighter";
import {
  INSTALL_OPIK_DEFAULT_DESCRIPTION,
  INSTALL_OPIK_DEFAULT_TITLE,
} from "@/constants/shared";
import useAppStore from "@/store/AppStore";
import { useUserApiKey } from "@/store/AppStore";
import useActiveProjectName from "@/hooks/useActiveProjectName";
import { putConfigInCode } from "@/lib/formatCodeSnippets";
import { Integration } from "@/constants/integrations";
import HelpLinks from "./HelpLinks";
import { ExternalLink } from "lucide-react";
import { IntegrationStep } from "./IntegrationStep";
import AdditionalIntegrationSteps from "@/shared/OnboardingIntegrationsPage/AdditionalIntegrationSteps";
import { useFeatureFlagVariantKey } from "posthog-js/react";
import { CODE_EXECUTOR_SERVICE_URL } from "@/api/api";
import CodeExecutor from "@/v2/pages-shared/onboarding/CodeExecutor/CodeExecutor";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";

type IntegrationDetailsDialogProps = {
  selectedIntegration?: Integration;
  onClose: () => void;
};

const IntegrationDetailsDialog: React.FunctionComponent<
  IntegrationDetailsDialogProps
> = ({ selectedIntegration, onClose }) => {
  const { t } = useTranslation();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const apiKey = useUserApiKey();
  const variant = useFeatureFlagVariantKey("run-button-activation-test");
  const projectName = useActiveProjectName();
  const { themeMode } = useTheme();

  if (!selectedIntegration) {
    return null;
  }

  const iconSrc =
    themeMode === THEME_MODE.DARK && selectedIntegration.whiteIcon
      ? selectedIntegration.whiteIcon
      : selectedIntegration.icon;

  const { code: codeWithConfig, lines } = putConfigInCode({
    code: selectedIntegration.code,
    workspaceName,
    apiKey,
    shouldMaskApiKey: true,
    withHighlight: true,
    projectName,
  });

  const { code: codeWithConfigToCopy } = putConfigInCode({
    code: selectedIntegration.code,
    workspaceName,
    apiKey,
    withHighlight: true,
    projectName,
  });

  const canExecuteCode =
    selectedIntegration.executionUrl &&
    selectedIntegration.executionLogs?.length &&
    apiKey &&
    Boolean(CODE_EXECUTOR_SERVICE_URL);

  const shouldShowCodeExecutor = canExecuteCode && variant === "test";

  const handleOpenChange = (newOpen: boolean) => {
    if (!newOpen) {
      onClose();
    }
  };

  return (
    <Dialog open={!!selectedIntegration} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-[920px] gap-2">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-1.5">
            <img
              alt={selectedIntegration.title}
              src={iconSrc}
              className="size-7 shrink-0"
            />
            {selectedIntegration.title} {t("onboarding.integrationExplorer.integration")}
          </DialogTitle>
        </DialogHeader>

        <DialogAutoScrollBody className="border-0">
          <div className="comet-body-s mb-6 text-muted-slate">
            {t("onboarding.integrationExplorer.detailsDescription")}{" "}
            <a
              href={selectedIntegration.docsLink}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 text-primary hover:underline dark:text-primary-hover"
            >
              {t("onboarding.integrationExplorer.readFullGuide")}
              <ExternalLink className="size-3" />
            </a>{" "}
            {t("onboarding.integrationExplorer.inOurDocs")}
          </div>

          <IntegrationStep
            title={
              selectedIntegration.installTitle ?? INSTALL_OPIK_DEFAULT_TITLE
            }
            description={
              selectedIntegration.installDescription ??
              INSTALL_OPIK_DEFAULT_DESCRIPTION
            }
            className="mb-6"
          >
            <div className="min-h-7">
              <CodeHighlighter data={selectedIntegration.installCommand} />
            </div>
          </IntegrationStep>
          {selectedIntegration.additionalSteps && (
            <AdditionalIntegrationSteps
              steps={selectedIntegration.additionalSteps}
              workspaceName={workspaceName}
              apiKey={apiKey}
              projectName={projectName}
              IntegrationStep={IntegrationStep}
              stepClassName="mb-6"
            />
          )}
          {selectedIntegration.code && (
            <IntegrationStep
              title={t("onboarding.integrationExplorer.runCodeWithIntegration", { title: selectedIntegration.title })}
              className="mb-6"
            >
              {shouldShowCodeExecutor ? (
                <CodeExecutor
                  executionUrl={selectedIntegration.executionUrl!}
                  executionLogs={selectedIntegration.executionLogs!}
                  data={codeWithConfig}
                  copyData={codeWithConfigToCopy}
                  apiKey={apiKey}
                  workspaceName={workspaceName}
                  highlightedLines={lines}
                />
              ) : (
                <CodeHighlighter
                  data={codeWithConfig}
                  copyData={codeWithConfigToCopy}
                  highlightedLines={lines}
                  language={selectedIntegration.codeLanguage}
                />
              )}
            </IntegrationStep>
          )}

          <Separator className="my-6" />

          <HelpLinks
            onCloseParentDialog={onClose}
            title={t("onboarding.integrationExplorer.needSomeHelp")}
            description={t("onboarding.integrationExplorer.needSomeHelpDescription")}
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

export default IntegrationDetailsDialog;
