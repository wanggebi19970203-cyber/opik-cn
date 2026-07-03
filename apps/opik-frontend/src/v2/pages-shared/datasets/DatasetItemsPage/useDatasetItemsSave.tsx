import React, { useCallback, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { Blocks, Code2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import useDatasetItemChangesMutation from "@/api/datasets/useDatasetItemChangesMutation";
import { ToastAction } from "@/ui/toast";
import { useToast } from "@/ui/use-toast";
import useLoadPlayground from "@/v2/pages-shared/playground/useLoadPlayground";
import { useNavigateToExperiment } from "@/v2/pages-shared/experiments/useNavigateToExperiment";
import { useTestSuiteSavePayload } from "@/hooks/useTestSuiteSavePayload";
import { DATASET_TYPE } from "@/types/datasets";

type SavePayload = ReturnType<typeof useTestSuiteSavePayload>;

interface UseDatasetItemsSaveParams {
  datasetId: string;
  datasetName: string | undefined;
  datasetType: DATASET_TYPE | undefined;
  buildPayload: SavePayload["buildPayload"];
  buildInitialVersionPayload: SavePayload["buildInitialVersionPayload"];
  hasNoVersion: boolean;
  clearDraft: () => void;
}

const useDatasetItemsSave = ({
  datasetId,
  datasetName,
  datasetType,
  buildPayload,
  buildInitialVersionPayload,
  hasNoVersion,
  clearDraft,
}: UseDatasetItemsSaveParams) => {
  const { t } = useTranslation("datasets");
  const [addVersionDialogOpen, setAddVersionDialogOpen] = useState(false);
  const [discardDialogOpen, setDiscardDialogOpen] = useState(false);
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const [pendingVersionData, setPendingVersionData] = useState<{
    tags?: string[];
    changeDescription?: string;
  } | null>(null);

  const queryClient = useQueryClient();
  const { toast } = useToast();
  const { navigate: navigateToExperiment } = useNavigateToExperiment();
  const { loadPlayground } = useLoadPlayground();

  const showSuccessToast = useCallback(
    (versionId?: string) => {
      toast({
        title: t("datasetItemsSave.newVersionCreated"),
        description: t("datasetItemsSave.newVersionCreatedDescription"),
        actions: [
          <ToastAction
            variant="link"
            size="sm"
            className="comet-body-s-accented gap-1.5 px-0"
            altText={t("datasetItemsSave.runExperimentInSdk")}
            key="sdk"
            onClick={() =>
              navigateToExperiment({
                newExperiment: true,
                datasetName,
              })
            }
          >
            <Code2 className="size-4" />
            {t("datasetItemsSave.runExperimentInSdk")}
          </ToastAction>,
          <ToastAction
            variant="link"
            size="sm"
            className="comet-body-s-accented gap-1.5 px-0"
            altText={t("datasetItemsSave.runExperimentInPlayground")}
            key="playground"
            onClick={() =>
              loadPlayground({
                datasetId,
                datasetVersionId: versionId,
                datasetType,
              })
            }
          >
            <Blocks className="size-4" />
            {t("datasetItemsSave.runExperimentInPlayground")}
          </ToastAction>,
        ],
      });
    },
    [
      toast,
      navigateToExperiment,
      loadPlayground,
      datasetName,
      datasetId,
      datasetType,
    ],
  );

  const changesMutation = useDatasetItemChangesMutation({
    onConflict: () => {
      setOverrideDialogOpen(true);
    },
  });

  const onSaveSuccess = async (version?: { id?: string }) => {
    setAddVersionDialogOpen(false);
    showSuccessToast(version?.id);
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: ["dataset-items", { datasetId }],
      }),
      queryClient.invalidateQueries({
        queryKey: ["dataset-versions"],
      }),
      queryClient.invalidateQueries({
        queryKey: ["dataset", { datasetId }],
      }),
    ]);
    clearDraft();
  };

  const handleSaveChanges = (tags?: string[], changeDescription?: string) => {
    if (changesMutation.isPending) return;

    if (hasNoVersion) {
      changesMutation.mutate(
        buildInitialVersionPayload({ tags, changeDescription }),
        {
          onError: (error) => {
            if ((error as AxiosError).response?.status === 409) {
              setPendingVersionData({ tags, changeDescription });
            }
          },
          onSuccess: (initialVersion) => {
            const itemPayload = buildPayload({
              baseVersionOverride: initialVersion?.id,
              tags,
              changeDescription,
            });

            const hasItemChanges =
              itemPayload.payload.added_items.length > 0 ||
              itemPayload.payload.edited_items.length > 0 ||
              itemPayload.payload.deleted_ids.length > 0;

            if (!hasItemChanges) {
              onSaveSuccess(initialVersion);
              return;
            }

            changesMutation.mutate(itemPayload, {
              onSuccess: onSaveSuccess,
              onError: (error) => {
                if ((error as AxiosError).response?.status === 409) {
                  setPendingVersionData({ tags, changeDescription });
                }
              },
            });
          },
        },
      );
      return;
    }

    changesMutation.mutate(buildPayload({ tags, changeDescription }), {
      onSuccess: onSaveSuccess,
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
          setOverrideDialogOpen(false);
          setPendingVersionData(null);
          await onSaveSuccess(version);
        },
      },
    );
  };

  const handleDiscardChanges = () => {
    clearDraft();
    setDiscardDialogOpen(false);
  };

  return {
    addVersionDialogOpen,
    setAddVersionDialogOpen,
    discardDialogOpen,
    setDiscardDialogOpen,
    overrideDialogOpen,
    setOverrideDialogOpen,
    changesMutation,
    handleSaveChanges,
    handleOverrideConfirm,
    handleDiscardChanges,
  };
};

export default useDatasetItemsSave;
