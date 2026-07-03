import React, { useEffect } from "react";
import { useTranslation } from "react-i18next";
import AIProvidersTab from "@/v1/pages/ConfigurationPage/AIProvidersTab/AIProvidersTab";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { StringParam, useQueryParam } from "use-query-params";
import usePluginsStore from "@/store/PluginsStore";
import FeedbackDefinitionsTab from "@/v1/pages/ConfigurationPage/FeedbackDefinitionsTab/FeedbackDefinitionsTab";
import WorkspacePreferencesTab from "./WorkspacePreferencesTab/WorkspacePreferencesTab";

enum CONFIGURATION_TABS {
  FEEDBACK_DEFINITIONS = "feedback-definitions",
  AI_PROVIDER = "ai-provider",
  WORKSPACE_PREFERENCES = "workspace-preferences",
  MEMBERS = "members",
}

const DEFAULT_TAB = CONFIGURATION_TABS.FEEDBACK_DEFINITIONS;

const ConfigurationPage = () => {
  const { t } = useTranslation();
  const [tab, setTab] = useQueryParam("tab", StringParam);

  const CollaboratorsTabTrigger = usePluginsStore(
    (state) => state.CollaboratorsTabTrigger,
  );
  const CollaboratorsTab = usePluginsStore((state) => state.CollaboratorsTab);

  useEffect(() => {
    if (!tab) {
      setTab(DEFAULT_TAB, "replaceIn");
    }
  }, [tab, setTab]);

  return (
    <div className="pt-6">
      <h1 className="comet-title-l">{t("settings.title")}</h1>

      <div className="mt-6">
        <Tabs
          defaultValue="feedback-definitions"
          value={tab as string}
          onValueChange={setTab}
        >
          <TabsList variant="underline">
            <TabsTrigger
              variant="underline"
              value={CONFIGURATION_TABS.FEEDBACK_DEFINITIONS}
            >
              {t("settings.sections.feedback")}
            </TabsTrigger>
            <TabsTrigger
              variant="underline"
              value={CONFIGURATION_TABS.AI_PROVIDER}
            >
              {t("settings.sections.providers")}
            </TabsTrigger>
            <TabsTrigger
              variant="underline"
              value={CONFIGURATION_TABS.WORKSPACE_PREFERENCES}
            >
              {t("settings.sections.preferences")}
            </TabsTrigger>
            {CollaboratorsTabTrigger && CollaboratorsTab && (
              <CollaboratorsTabTrigger value={CONFIGURATION_TABS.MEMBERS} />
            )}
          </TabsList>

          <TabsContent value={CONFIGURATION_TABS.FEEDBACK_DEFINITIONS}>
            <FeedbackDefinitionsTab />
          </TabsContent>

          <TabsContent value={CONFIGURATION_TABS.AI_PROVIDER}>
            <AIProvidersTab />
          </TabsContent>

          <TabsContent value={CONFIGURATION_TABS.WORKSPACE_PREFERENCES}>
            <WorkspacePreferencesTab />
          </TabsContent>

          {CollaboratorsTabTrigger && CollaboratorsTab && (
            <TabsContent value={CONFIGURATION_TABS.MEMBERS}>
              <CollaboratorsTab />
            </TabsContent>
          )}
        </Tabs>
      </div>
    </div>
  );
};

export default ConfigurationPage;
