import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";
import i18next from "i18next";
import { useToast } from "@/ui/use-toast";
import api, { DATASETS_REST_ENDPOINT } from "@/api/api";
import { Filters } from "@/types/filters";
import {
  generateSearchByFieldFilters,
  processFiltersArray,
} from "@/lib/filters";

type UseDatasetItemBatchDeleteMutationParams = {
  datasetId: string;
  ids: string[];
  isAllItemsSelected?: boolean;
  filters?: Filters;
  search?: string;
  batchGroupId?: string;
};

const useDatasetItemBatchDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      datasetId,
      ids,
      isAllItemsSelected,
      filters = [],
      search,
      batchGroupId,
    }: UseDatasetItemBatchDeleteMutationParams) => {
      let payload;

      if (isAllItemsSelected) {
        const combinedFilters = [
          ...filters,
          ...generateSearchByFieldFilters("full_data", search),
        ];

        payload = {
          dataset_id: datasetId,
          filters: processFiltersArray(combinedFilters),
          ...(batchGroupId && { batch_group_id: batchGroupId }),
        };
      } else {
        payload = { item_ids: ids };
      }

      const { data } = await api.post(
        `${DATASETS_REST_ENDPOINT}items/delete`,
        payload,
      );
      return data;
    },
    onSuccess: (_, { ids, isAllItemsSelected }) => {
      const isSingle = !isAllItemsSelected && ids.length === 1;
      toast({
        title: isSingle
          ? i18next.t("common:messages.suiteItemRemoved")
          : i18next.t("common:messages.suiteItemsRemoved"),
        description: isSingle
          ? i18next.t("common:messages.suiteItemRemovedDescription")
          : i18next.t("common:messages.suiteItemsRemovedDescription"),
      });
    },
    onError: (error: AxiosError) => {
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
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: ["dataset-items"],
      });
      queryClient.invalidateQueries({
        queryKey: ["dataset", { datasetId: variables.datasetId }],
      });
    },
  });
};

export default useDatasetItemBatchDeleteMutation;
