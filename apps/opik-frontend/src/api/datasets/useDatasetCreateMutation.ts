import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError, HttpStatusCode } from "axios";
import get from "lodash/get";
import i18next from "i18next";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { Dataset } from "@/types/datasets";
import { useToast } from "@/ui/use-toast";
import { extractIdFromLocation } from "@/lib/utils";

type UseDatasetCreateMutationParams = {
  dataset: Partial<Dataset>;
};

const useDatasetCreateMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ dataset }: UseDatasetCreateMutationParams) => {
      const { data, headers } = await api.post(DATASETS_REST_ENDPOINT, {
        ...dataset,
      });

      if (data) {
        return data;
      }

      const extractedId = extractIdFromLocation(headers?.location);

      if (!extractedId) {
        throw new Error(
          i18next.t("common:messages.failedToCreateTestSuiteNoId"),
        );
      }

      return {
        ...dataset,
        id: extractedId,
      };
    },
    onError: (error: AxiosError) => {
      const statusCode = get(error, ["response", "status"]);
      if (statusCode === HttpStatusCode.Conflict) {
        return;
      }

      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      toast({
        title: i18next.t("common:labels.error"),
        description: message,
        variant: "destructive",
      });
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["project-datasets"] });
      return queryClient.invalidateQueries({
        queryKey: ["datasets"],
      });
    },
  });
};

export default useDatasetCreateMutation;
