import React from "react";
import { useTranslation } from "react-i18next";

type IntegrationExplorerComponent = React.FC<{
  source?: string;
  children: React.ReactNode;
}> & {
  Search: React.ComponentType;
  QuickInstall: React.ComponentType;
  TypeScriptSDK: React.ComponentType;
  GetHelp: React.ComponentType;
  CopyApiKey: React.ComponentType;
  Skip: React.ComponentType<{ onSkip?: () => void }>;
  Tabs: React.FC<{ children: React.ReactNode }>;
  Grid: React.ComponentType;
};

type OnboardingIntegrationsPageProps = {
  IntegrationExplorer: IntegrationExplorerComponent;
  source: string;
  banner?: React.ReactNode;
  onSkip?: () => void;
};

const OnboardingIntegrationsPage: React.FC<OnboardingIntegrationsPageProps> = ({
  IntegrationExplorer,
  source,
  banner,
  onSkip,
}) => {
  const { t } = useTranslation();

  return (
    <div className="mx-auto w-full max-w-[1040px] pb-10">
      <div className="mb-3 mt-6 flex items-center justify-between md:mt-10">
        <h1 className="md:comet-title-xl comet-title-l">
          {t("common.shared.getStartedWithOpik")}
        </h1>
        {banner}
      </div>
      <p className="comet-body-s mb-10 text-muted-slate">
        {t("common.shared.opikDescription")}
      </p>

      <IntegrationExplorer source={source}>
        <div className="mb-8 flex flex-col justify-between gap-6 md:flex-row md:items-center">
          <IntegrationExplorer.Search />
          <div className="flex flex-wrap items-center gap-6 md:gap-3">
            <IntegrationExplorer.CopyApiKey />
            <IntegrationExplorer.GetHelp />
            <IntegrationExplorer.Skip onSkip={onSkip} />
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
  );
};

export default OnboardingIntegrationsPage;
