import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import useProjectById from "@/api/projects/useProjectById";
import PageBodyScrollContainer from "@/v1/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import LogsTab from "@/v1/pages/TracesPage/LogsTab/LogsTab";
import InsightsTab from "@/v1/pages/TracesPage/InsightsTab/InsightsTab";
import RulesTab from "@/v1/pages/TracesPage/RulesTab/RulesTab";
import AnnotationQueuesTab from "@/v1/pages/TracesPage/AnnotationQueuesTab/AnnotationQueuesTab";
import Loader from "@/shared/Loader/Loader";
import { Button } from "@/ui/button";
import { Construction } from "lucide-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import SetGuardrailDialog from "../HomePageShared/SetGuardrailDialog";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import useProjectTabs from "@/v1/pages/TracesPage/useProjectTabs";
import { PROJECT_TAB } from "@/constants/traces";

const TracesPage = () => {
  const { t } = useTranslation();
  const projectId = useProjectIdFromURL();
  const [isGuardrailsDialogOpened, setIsGuardrailsDialogOpened] =
    useState<boolean>(false);
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );
  const { data: project } = useProjectById(
    {
      projectId,
    },
    {
      refetchOnMount: false,
    },
  );

  const projectName = project?.name || projectId;

  const {
    activeTab,
    logsType,
    needsDefaultResolution,
    setLogsType,
    handleTabChange,
  } = useProjectTabs({
    projectId,
  });

  const openGuardrailsDialog = () => setIsGuardrailsDialogOpened(true);

  const renderContent = () => {
    return (
      <Tabs
        defaultValue={PROJECT_TAB.logs}
        value={activeTab}
        onValueChange={handleTabChange}
        className="min-w-min"
      >
        <PageBodyStickyContainer direction="horizontal" limitWidth>
          <TabsList variant="underline">
            <TabsTrigger variant="underline" value={PROJECT_TAB.logs}>
              {t("tracing.tabs.logs")}
            </TabsTrigger>
            <TabsTrigger variant="underline" value={PROJECT_TAB.insights}>
              {t("tracing.tabs.insights")}
            </TabsTrigger>
            <TabsTrigger variant="underline" value={PROJECT_TAB.evaluators}>
              {t("tracing.tabs.onlineEvaluation")}
            </TabsTrigger>
            <TabsTrigger
              variant="underline"
              value={PROJECT_TAB.annotationQueues}
            >
              {t("tracing.tabs.annotationQueues")}
            </TabsTrigger>
          </TabsList>
        </PageBodyStickyContainer>
        <TabsContent value={PROJECT_TAB.logs}>
          <LogsTab
            projectId={projectId}
            projectName={projectName}
            logsType={logsType}
            onLogsTypeChange={setLogsType}
          />
        </TabsContent>
        <TabsContent value={PROJECT_TAB.insights}>
          <InsightsTab projectId={projectId} />
        </TabsContent>
        <TabsContent value={PROJECT_TAB.evaluators}>
          <RulesTab projectId={projectId} />
        </TabsContent>
        <TabsContent value={PROJECT_TAB.annotationQueues}>
          <AnnotationQueuesTab projectId={projectId} />
        </TabsContent>
      </Tabs>
    );
  };

  return (
    <>
      <PageBodyScrollContainer>
        <PageBodyStickyContainer
          className="mb-4 mt-6 flex items-center justify-between"
          direction="horizontal"
        >
          <h1
            data-testid="traces-page-title"
            className="comet-title-l truncate break-words"
          >
            {projectName}
          </h1>
          {isGuardrailsEnabled && (
            <div className="flex shrink-0 items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                onClick={openGuardrailsDialog}
              >
                <Construction className="mr-1.5 size-3.5" />
                {t("tracing.tabs.setGuardrail")}
              </Button>
            </div>
          )}
        </PageBodyStickyContainer>
        {project?.description && (
          <PageBodyStickyContainer
            className="-mt-3 mb-4 flex min-h-8 items-center justify-between"
            direction="horizontal"
          >
            <div className="text-muted-slate">{project.description}</div>
          </PageBodyStickyContainer>
        )}
        {needsDefaultResolution ? <Loader /> : renderContent()}
      </PageBodyScrollContainer>
      {isGuardrailsEnabled && (
        <SetGuardrailDialog
          open={isGuardrailsDialogOpened}
          setOpen={setIsGuardrailsDialogOpened}
          projectName={projectName}
        />
      )}
    </>
  );
};

export default TracesPage;
