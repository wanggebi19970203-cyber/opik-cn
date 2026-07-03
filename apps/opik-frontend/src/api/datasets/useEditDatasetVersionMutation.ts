import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import i18next from "i18next";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { DatasetVersion } from "@/types/datasets";
import { useToast } from "@/ui/use-toast";

type UseEditDatasetVersionMutationParams = {
  datasetId: string;
  versionHash: string;
  changeDescription?: string;
  tagsToAdd?: string[];
};

const useEditDatasetVersionMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      datasetId,
      versionHash,
      changeDescription,
      tagsToAdd,
    }: UseEditDatasetVersionMutationParams) => {
      const { data } = await api.patch<DatasetVersion>(
        `${DATASETS_REST_ENDPOINT}${datasetId}/versions/hash/${versionHash}`,
        {
          change_description: changeDescription,
          tags_to_add: tagsToAdd,
        },
      );

      return data;
    },
    onError: (error: AxiosError) => {
      const errors = get(error, ["response", "data", "errors"], []);
      const message =
        Array.isArray(errors) && errors.length > 0
          ? errors.join("; ")
          : get(error, ["response", "data", "message"], error.message) ||
            i18next.t("common:messages.failedToUpdateVersion");

      toast({
        title: i18next.t("common:labels.error"),
        description: message,
        variant: "destructive",
      });
    },
    onSuccess: (_, { datasetId }) => {
      toast({
        title: i18next.t("common:messages.versionUpdated"),
        description: i18next.t("common:messages.versionUpdatedDescription"),
      });

      queryClient.invalidateQueries({
        queryKey: ["dataset", { datasetId }],
      });

      queryClient.invalidateQueries({
        queryKey: ["dataset-versions", { datasetId }],
        exact: false,
      });
    },
  });
};

export default useEditDatasetVersionMutation;
