import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import i18next from "i18next";

import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import {
  DatasetExpansionRequest,
  DatasetExpansionResponse,
} from "@/types/datasets";
import { useToast } from "@/ui/use-toast";

type UseDatasetExpansionMutationParams = {
  datasetId: string;
  entityName?: string;
} & DatasetExpansionRequest;

const useDatasetExpansionMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation<
    DatasetExpansionResponse,
    AxiosError,
    UseDatasetExpansionMutationParams
  >({
    mutationFn: async ({ datasetId, entityName, ...reqData }) => {
      void entityName;
      const { data: response } = await api.post(
        `${DATASETS_REST_ENDPOINT}${datasetId}/expansions`,
        reqData,
      );
      return response;
    },
    onSuccess: (_, { entityName = "dataset" }) => {
      const label = entityName.charAt(0).toUpperCase() + entityName.slice(1);
      toast({
        title: i18next.t("common:messages.expansionSuccessful", { label }),
        description: i18next.t("common:messages.syntheticSamplesGenerated"),
      });
    },
    onError: (error, { entityName = "dataset" }) => {
      const errorData = error?.response?.data as {
        message?: string;
        detail?: string;
      };

      let message =
        errorData?.message ||
        errorData?.detail ||
        i18next.t("common:messages.failedToExpand", { entityName });

      // Handle specific model not supported error
      if (message.includes("model not supported")) {
        const modelMatch = message.match(/model not supported (.+)/);
        const modelName = modelMatch
          ? modelMatch[1]
          : i18next.t("common:messages.selectedModel");
        message = i18next.t("common:messages.modelNotSupported", { modelName });
      }

      const label = entityName.charAt(0).toUpperCase() + entityName.slice(1);
      toast({
        title: i18next.t("common:messages.expansionFailed", { label }),
        description: message,
        variant: "destructive",
      });
    },
    onSettled: (data, error, variables) => {
      return queryClient.invalidateQueries({
        queryKey: ["dataset-items", { datasetId: variables.datasetId }],
      });
    },
  });
};

export default useDatasetExpansionMutation;
