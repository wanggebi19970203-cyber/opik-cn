import React from "react";
import { useTranslation } from "react-i18next";
import { useAgentOnboarding } from "./AgentOnboardingContext";
import { useUserApiKey, useActiveWorkspaceName } from "@/store/AppStore";
import { buildDocsUrl } from "@/v2/lib/utils";
import { BASE_API_URL } from "@/api/api";
import TimelineStep from "@/shared/TimelineStep/TimelineStep";
import CodeSnippet from "@/shared/CodeSnippet/CodeSnippet";

const INSTALL_COMMAND = "pip install opik";

interface ConnectToOllieTabProps {
  connected: boolean;
}

const ConnectToOllieTab: React.FC<ConnectToOllieTabProps> = ({ connected }) => {
  const { t } = useTranslation("pages/get-started");
  const { agentName } = useAgentOnboarding();
  const apiKey = useUserApiKey();
  const workspaceName = useActiveWorkspaceName();

  const buildConnectCommand = () => {
    if (apiKey) {
      return `opik connect --project "${agentName}" --workspace "${workspaceName}" --api-key "${apiKey}"`;
    }
    const url = new URL(BASE_API_URL, window.location.origin).toString();
    return `OPIK_URL_OVERRIDE="${url}" opik connect --project "${agentName}"`;
  };

  const connectCommandText = buildConnectCommand();

  return (
    <div className="flex flex-col gap-4 px-1">
      <p className="comet-body-s text-muted-slate">
        {t("getStarted.connectToOllie.description")}
      </p>
      <div className="flex flex-col">
        <TimelineStep number={1}>
          <div className="flex flex-col gap-2.5">
            <h4 className="comet-body-s-accented">
              {t("getStarted.connectToOllie.installOpik")}
            </h4>
            <CodeSnippet title="Terminal" code={INSTALL_COMMAND} />
          </div>
        </TimelineStep>

        <TimelineStep number={2}>
          <div className="flex flex-col gap-2.5">
            <h4 className="comet-body-s-accented">
              {t("getStarted.connectToOllie.connectRepo")}
            </h4>
            <CodeSnippet title="Terminal" code={connectCommandText} />
          </div>
        </TimelineStep>

        <TimelineStep isLast completed={connected}>
          <div className="flex flex-col gap-1">
            <h4 className="comet-body-s-accented text-primary">
              {connected
                ? t("getStarted.connectToOllie.repoConnected")
                : t("getStarted.connectToOllie.waitingForRepo")}
            </h4>
            <p className="comet-body-xs text-muted-slate">
              {connected ? (
                t("getStarted.connectToOllie.connectedDescription")
              ) : (
                <>
                  {t("getStarted.connectToOllie.notConnectedDescription")}{" "}
                  <a
                    href={buildDocsUrl(
                      "/agents/local-runner",
                      "#troubleshooting",
                    )}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="underline hover:text-foreground"
                  >
                    {t("getStarted.connectToOllie.connectTroubleshooting")}
                  </a>
                </>
              )}
            </p>
          </div>
        </TimelineStep>
      </div>
    </div>
  );
};

export default ConnectToOllieTab;
