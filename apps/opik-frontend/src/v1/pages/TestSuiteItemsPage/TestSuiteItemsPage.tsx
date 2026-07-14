import React, { useCallback, useEffect, useState } from "react";
import { StringParam, useQueryParam } from "use-query-params";
import { useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import {
  Blocks,
  Check,
  CheckCheck,
  Code2,
  GitCommitVertical,
  Settings2,
  X,
} from "lucide-react";
import { useTranslation } from "react-i18next";

import useDatasetById from "@/api/datasets/useDatasetById";
import useDatasetItemChangesMutation from "@/api/datasets/useDatasetItemChangesMutation";
import useDatasetUpdateMutation from "@/api/datasets/useDatasetUpdateMutation";
import useDatasetVersionsList from "@/api/datasets/useDatasetVersionsList";
import TestSuiteItemsTab from "@/v1/pages/TestSuiteItemsPage/TestSuiteItemsTab/TestSuiteItemsTab";
import EditTestSuiteSettingsDialog from "@/v1/pages/TestSuiteItemsPage/EditTestSuiteSettingsDialog";
import AddVersionDialog from "@/v1/pages-shared/datasets/VersionHistoryTab/AddVersionDialog";
import VersionHistoryTab from "@/v1/pages-shared/datasets/VersionHistoryTab/VersionHistoryTab";
import OverrideVersionDialog from "@/v1/pages-shared/datasets/OverrideVersionDialog";
import ColoredTag from "@/shared/ColoredTag/ColoredTag";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import DateTag from "@/shared/DateTag/DateTag";
import Loader from "@/shared/Loader/Loader";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import { usePermissions } from "@/contexts/PermissionsContext";
import { Button } from "@/ui/button";
import { Separator } from "@/ui/separator";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/ui/tabs";
import { Tag } from "@/ui/tag";
import {
  Tooltip,
  TooltipContent,
  TooltipPortal,
  TooltipTrigger,
} from "@/ui/tooltip";
import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import useLoadPlayground from "@/hooks/useLoadPlayground";
import useNavigationBlocker from "@/hooks/useNavigationBlocker";
import { useNavigateToExperiment } from "@/hooks/useNavigateToExperiment";
import { useTestSuiteSavePayload } from "@/hooks/useTestSuiteSavePayload";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";
import { useClearDraft, useHasDraft } from "@/store/TestSuiteDraftStore";
import { DATASET_STATUS, DATASET_TYPE } from "@/types/datasets";
import { useEffectiveSuiteAssertions } from "@/hooks/useEffectiveSuiteAssertions";
import { AssertionsListTooltipContent } from "@/v1/pages-shared/experiments/TestSuiteExperiment/AssertionsListTooltipContent";
import UseTestSuiteDropdown from "./UseTestSuiteDropdown";

const POLLING_INTERVAL_MS = 3000;

function TestSuiteItemsPage(): React.ReactElement {
  const { t } = useTranslation("test-suite-items");
  const suiteId = useSuiteIdFromURL();

  const [tab, setTab] = useQueryParam("tab", StringParam);
  const [addVersionDialogOpen, setAddVersionDialogOpen] = useState(false);
  const [discardDialogOpen, setDiscardDialogOpen] = useState(false);
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const [settingsDialogOpen, setSettingsDialogOpen] = useState(false);
  const [pendingVersionData, setPendingVersionData] = useState<{
    tags?: string[];
    changeDescription?: string;
  } | null>(null);

  const queryClient = useQueryClient();
  const hasDraft = useHasDraft();
  const clearDraft = useClearDraft();
  const { toast } = useToast();
  const { navigate: navigateToExperiment } = useNavigateToExperiment();
  const { loadPlayground } = useLoadPlayground();

  const {
    permissions: { canEditDatasets },
  } = usePermissions();

  const { mutate: updateSuite } = useDatasetUpdateMutation();

  const { data: suite, isPending } = useDatasetById(
    { datasetId: suiteId },
    {
      refetchInterval: (query) => {
        const status = query.state.data?.status;
        return status === DATASET_STATUS.processing
          ? POLLING_INTERVAL_MS
          : false;
      },
    },
  );

  const datasetType = suite?.type;
  const isTestSuite = datasetType === DATASET_TYPE.TEST_SUITE;
  const latestVersion = suite?.latest_version;

  const suiteTags = suite?.tags ?? [];
  const showTags = canEditDatasets || suiteTags.length > 0;
  const tagListProps = canEditDatasets
    ? { tags: suiteTags }
    : { tags: [] as string[], immutableTags: suiteTags };

  const { data: versionsData } = useDatasetVersionsList(
    { datasetId: suiteId, page: 1, size: 1 },
    { enabled: isTestSuite },
  );
  const latestVersionData = versionsData?.content?.[0];
  const versionEvaluators = latestVersionData?.evaluators ?? [];
  const { buildPayload } = useTestSuiteSavePayload({
    suiteId,
    suite,
    versionEvaluators,
  });

  const effectiveAssertions = useEffectiveSuiteAssertions(suiteId);

  useEffect(() => {
    return clearDraft;
  }, [suiteId, clearDraft]);

  const { DialogComponent } = useNavigationBlocker({
    condition: hasDraft,
    title: t("page.unsavedChanges"),
    description: t("page.unsavedDraftDescription"),
    confirmText: t("page.leaveWithoutSaving"),
    cancelText: t("page.stay"),
  });

  const showSuccessToast = useCallback(
    (versionId?: string) => {
      toast({
        title: t("page.newVersionCreated"),
        description: t("page.newVersionDescription"),
        actions: [
          <ToastAction
            variant="link"
            size="sm"
            className="comet-body-s-accented gap-1.5 px-0"
            altText={t("page.runExperimentSdk")}
            key="sdk"
            onClick={() =>
              navigateToExperiment({
                newExperiment: true,
                datasetName: suite?.name,
              })
            }
          >
            <Code2 className="size-4" />
            {t("page.runExperimentSdk")}
          </ToastAction>,
          <ToastAction
            variant="link"
            size="sm"
            className="comet-body-s-accented gap-1.5 px-0"
            altText={t("page.runExperimentPlayground")}
            key="playground"
            onClick={() =>
              loadPlayground({
                datasetId: suiteId,
                datasetVersionId: versionId,
                datasetType: DATASET_TYPE.TEST_SUITE,
              })
            }
          >
            <Blocks className="size-4" />
            {t("page.runExperimentPlayground")}
          </ToastAction>,
        ],
      });
    },
    [toast, navigateToExperiment, loadPlayground, suite?.name, suiteId, t],
  );

  const changesMutation = useDatasetItemChangesMutation({
    onConflict: () => {
      setOverrideDialogOpen(true);
    },
  });

  const handleSaveChanges = (tags?: string[], changeDescription?: string) => {
    if (changesMutation.isPending) return;

    changesMutation.mutate(buildPayload({ tags, changeDescription }), {
      onSuccess: async (version) => {
        setAddVersionDialogOpen(false);
        showSuccessToast(version?.id);
        await Promise.all([
          queryClient.invalidateQueries({
            queryKey: ["dataset-items", { datasetId: suiteId }],
          }),
          queryClient.invalidateQueries({
            queryKey: ["dataset-versions"],
          }),
        ]);
        clearDraft();
      },
      onError: (error) => {
        if ((error as AxiosError).response?.status === 409) {
          setPendingVersionData({ tags, changeDescription });
        }
      },
    });
  };

  const handleOverrideConfirm = () => {
    if (!pendingVersionData) return;

    changesMutation.mutate(
      buildPayload({ ...pendingVersionData, override: true }),
      {
        onSuccess: async (version) => {
          setAddVersionDialogOpen(false);
          setOverrideDialogOpen(false);
          setPendingVersionData(null);
          showSuccessToast(version?.id);
          await Promise.all([
            queryClient.invalidateQueries({
              queryKey: ["dataset-items", { datasetId: suiteId }],
            }),
            queryClient.invalidateQueries({
              queryKey: ["dataset-versions"],
            }),
          ]);
          clearDraft();
        },
      },
    );
  };

  const handleDiscardChanges = () => {
    clearDraft();
    setDiscardDialogOpen(false);
  };

  const handleAddTag = (newTag: string) => {
    updateSuite({
      dataset: {
        ...suite,
        id: suiteId,
        tags: [...(suite?.tags ?? []), newTag],
      },
    });
  };

  const handleDeleteTag = (tag: string) => {
    updateSuite({
      dataset: {
        ...suite,
        id: suiteId,
        tags: (suite?.tags ?? []).filter((t) => t !== tag),
      },
    });
  };

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <AddVersionDialog
        open={addVersionDialogOpen}
        setOpen={setAddVersionDialogOpen}
        onConfirm={handleSaveChanges}
        isSubmitting={changesMutation.isPending}
      />
      <ConfirmDialog
        open={discardDialogOpen}
        setOpen={setDiscardDialogOpen}
        onConfirm={handleDiscardChanges}
        title={t("page.discardChanges")}
        description={t("page.discardChangesDescription", {
          type: isTestSuite
            ? t("page.testSuite").toLowerCase()
            : t("page.dataset").toLowerCase(),
        })}
        confirmText={t("page.discardChanges")}
        confirmButtonVariant="destructive"
      />
      <OverrideVersionDialog
        open={overrideDialogOpen}
        setOpen={setOverrideDialogOpen}
        onConfirm={handleOverrideConfirm}
      />
      <EditTestSuiteSettingsDialog
        open={settingsDialogOpen}
        setOpen={setSettingsDialogOpen}
      />
      {DialogComponent}
      <div className="mb-4">
        <div className="mb-4 flex items-center justify-between gap-2">
          <div className="flex items-center gap-2">
            {hasDraft && (
              <Tag variant="orange" size="md">
                {t("page.draft")}
              </Tag>
            )}
            <h1 className="comet-title-l truncate break-words">
              {suite?.name ??
                (isTestSuite ? t("page.testSuite") : t("page.dataset"))}
            </h1>
          </div>
          <div className="flex items-center gap-2">
            {hasDraft && (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setDiscardDialogOpen(true)}
                >
                  <X className="mr-1 size-4" />
                  {t("page.discardChanges")}
                </Button>
                <Button
                  variant="default"
                  size="sm"
                  onClick={() => setAddVersionDialogOpen(true)}
                >
                  <Check className="mr-1 size-4" />
                  {t("page.saveChanges")}
                </Button>
              </>
            )}
            <UseTestSuiteDropdown
              datasetName={suite?.name}
              datasetId={suiteId}
              datasetVersionId={latestVersion?.id}
              isTestSuite={isTestSuite}
            />
            {isTestSuite && (
              <Button
                variant="outline"
                size="sm"
                className="gap-1.5"
                onClick={() => setSettingsDialogOpen(true)}
              >
                <Settings2 className="size-3.5 shrink-0" />
                {t("page.settings")}
              </Button>
            )}
          </div>
        </div>
        {suite?.description && (
          <div className="-mt-3 mb-4 text-muted-slate">{suite.description}</div>
        )}
        <div className="flex gap-2 overflow-x-auto">
          {suite?.created_at && (
            <DateTag
              date={suite.created_at}
              resource={RESOURCE_TYPE.testSuite}
            />
          )}
          {latestVersion && (
            <>
              <Tag
                size="md"
                variant="transparent"
                className="flex shrink-0 items-center gap-1"
              >
                <GitCommitVertical className="size-3 text-green-500" />
                {latestVersion.version_name}
              </Tag>
              {latestVersion.tags?.map((tag) => (
                <ColoredTag
                  key={tag}
                  label={tag}
                  size="md"
                  IconComponent={GitCommitVertical}
                />
              ))}
            </>
          )}
          {isTestSuite && effectiveAssertions.length > 0 && (
            <Tooltip>
              <TooltipTrigger asChild>
                <div
                  className="flex shrink-0 cursor-pointer items-center gap-1 rounded bg-thread-active px-1.5 py-0.5"
                  onClick={() => setSettingsDialogOpen(true)}
                >
                  <CheckCheck className="size-3 text-muted-foreground" />
                  <span className="comet-body-s-accented text-muted-foreground">
                    {effectiveAssertions.length}{" "}
                    {effectiveAssertions.length !== 1
                      ? t("page.globalAssertions")
                      : t("page.globalAssertion")}
                  </span>
                </div>
              </TooltipTrigger>
              <TooltipPortal>
                <TooltipContent
                  side="bottom"
                  collisionPadding={16}
                  className="max-w-fit p-0"
                >
                  <AssertionsListTooltipContent
                    assertions={effectiveAssertions}
                  />
                </TooltipContent>
              </TooltipPortal>
            </Tooltip>
          )}
          {showTags && (
            <>
              <Separator orientation="vertical" className="ml-1.5 mt-1 h-4" />
              <TagListRenderer
                {...tagListProps}
                onAddTag={handleAddTag}
                onDeleteTag={handleDeleteTag}
                readOnly={!canEditDatasets}
                align="start"
                className="min-h-0 w-auto"
              />
            </>
          )}
        </div>
      </div>
      <Tabs value={tab || "items"} onValueChange={setTab}>
        <TabsList variant="underline">
          <TabsTrigger variant="underline" value="items">
            {t("page.items")}
          </TabsTrigger>
          <TabsTrigger variant="underline" value="version-history">
            {t("page.versionHistory")}
          </TabsTrigger>
        </TabsList>
        <TabsContent value="items">
          <TestSuiteItemsTab
            datasetId={suiteId}
            datasetName={suite?.name}
            datasetStatus={suite?.status}
            datasetType={datasetType}
            suiteAssertions={effectiveAssertions}
            onOpenSettings={() => setSettingsDialogOpen(true)}
          />
        </TabsContent>
        <TabsContent value="version-history">
          <VersionHistoryTab datasetId={suiteId} />
        </TabsContent>
      </Tabs>
    </div>
  );
}

export default TestSuiteItemsPage;
