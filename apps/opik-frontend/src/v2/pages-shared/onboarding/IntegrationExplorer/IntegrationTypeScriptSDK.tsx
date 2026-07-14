import React from "react";
import { useTranslation } from "react-i18next";
import IntegrationCard from "@/v2/pages-shared/onboarding/IntegrationExplorer/components/IntegrationCard";
import tsLogo from "@/icons/ts-logo.svg";
import { buildDocsUrl } from "@/v2/lib/utils";
import { useIntegrationExplorer } from "@/v2/pages-shared/onboarding/IntegrationExplorer/IntegrationExplorerContext";

const IntegrationTypeScriptSDK: React.FC = () => {
  const { t } = useTranslation();
  const { source } = useIntegrationExplorer();

  return (
    <a
      href={buildDocsUrl("/reference/typescript-sdk/overview")}
      target="_blank"
      rel="noopener noreferrer"
    >
      <IntegrationCard
        title={t("onboarding.integrationExplorer.typeScriptSdk")}
        description={t(
          "onboarding.integrationExplorer.typeScriptSdkDescription",
        )}
        size="lg"
        icon={
          <img alt="TypeScript" src={tsLogo} className="size-[32px] shrink-0" />
        }
        tag={t("onboarding.integrationExplorer.newTag")}
        id={`integration-typescript-sdk-card${source ? `-${source}` : ""}`}
        data-fs-element={`IntegrationTypeScriptSDKCard${
          source ? `-${source}` : ""
        }`}
      />
    </a>
  );
};

export default IntegrationTypeScriptSDK;
