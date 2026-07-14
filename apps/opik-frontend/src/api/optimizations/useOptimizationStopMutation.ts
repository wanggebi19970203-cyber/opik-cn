import { useMutation, useQueryClient } from "@tanstack/react-query";
import i18next from "i18next";
import api, {
  OPTIMIZATION_KEY,
  OPTIMIZATIONS_KEY,
  OPTIMIZATIONS_REST_ENDPOINT,
} from "@/api/api";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { AxiosError } from "axios";
import { useToast } from "@/ui/use-toast";
import useAppStore from "@/store/AppStore";

type UseOptimizationStopMutationParams = {
  optimizationId: string;
};

const useOptimizationStopMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const opikApiKey = useAppStore((state) => state.user.apiKey);

  return useMutation({
    mutationFn: ({ optimizationId }: UseOptimizationStopMutationParams) =>
      api.put(
        `${OPTIMIZATIONS_REST_ENDPOINT}${optimizationId}`,
        { status: OPTIMIZATION_STATUS.CANCELLED },
        {
          headers: {
            ...(opikApiKey && { opikApiKey }),
          },
        },
      ),
    onError: (error: AxiosError<{ message?: string }>) => {
      const message =
        error.response?.data?.message ??
        error.message ??
        i18next.t("common:messages.failedToStopOptimization");

      toast({
        title: i18next.t("common:labels.error"),
        description: message,
        variant: "destructive",
      });
    },
    onSuccess: async (_, variables: UseOptimizationStopMutationParams) => {
      queryClient.invalidateQueries({
        queryKey: [OPTIMIZATIONS_KEY],
      });

      await queryClient.refetchQueries({
        queryKey: [
          OPTIMIZATION_KEY,
          { optimizationId: variables.optimizationId },
        ],
      });

      toast({
        description: i18next.t(
          "common:messages.optimizationStoppedSuccessfully",
        ),
      });
    },
  });
};

export default useOptimizationStopMutation;
