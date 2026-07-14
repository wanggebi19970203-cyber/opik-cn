import React, { useEffect } from "react";
import { useTranslation } from "react-i18next";
import AIProvidersTab from "@/v2/pages/ConfigurationPage/AIProvidersTab/AIProvidersTab";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { StringParam, useQueryParam } from "use-query-params";
import usePluginsStore from "@/store/PluginsStore";
import FeedbackDefinitionsTab from "@/v2/pages/ConfigurationPage/FeedbackDefinitionsTab/FeedbackDefinitionsTab";
import EnvironmentsTab from "@/v2/pages/ConfigurationPage/EnvironmentsTab/EnvironmentsTab";
import WorkspacePreferencesTab from "./WorkspacePreferencesTab/WorkspacePreferencesTab";
import { CONFIGURATION_TABS } from "@/v2/constants/configuration";

const DEFAULT_TAB = CONFIGURATION_TABS.FEEDBACK_DEFINITIONS;

const ConfigurationPage = () => {
  const { t } = useTranslation("pages/settings");
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
    <div className="pt-5">
      <h1 className="comet-body-accented">{t("settings.title")}</h1>

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
              value={CONFIGURATION_TABS.ENVIRONMENTS}
            >
              {t("settings.sections.environments")}
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

          <TabsContent value={CONFIGURATION_TABS.ENVIRONMENTS}>
            <EnvironmentsTab />
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
