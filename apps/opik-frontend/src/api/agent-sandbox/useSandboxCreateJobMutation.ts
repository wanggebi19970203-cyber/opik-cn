import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import { AxiosError } from "axios";
import i18next from "i18next";

import api, { AGENT_SANDBOX_KEY, LOCAL_RUNNERS_REST_ENDPOINT } from "@/api/api";
import { CreateLocalRunnerJobRequest } from "@/types/agent-sandbox";
import { extractIdFromLocation } from "@/lib/utils";
import { useToast } from "@/ui/use-toast";

const useSandboxCreateJobMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async (request: CreateLocalRunnerJobRequest) => {
      const { data, headers } = await api.post(
        `${LOCAL_RUNNERS_REST_ENDPOINT}jobs`,
        request,
      );
      const id = data?.id ?? extractIdFromLocation(headers?.location);
      return { id };
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message || i18next.t("common:messages.unableToCreateSandboxJob"),
      );

      toast({
        title: i18next.t("common:labels.error"),
        description: message,
        variant: "destructive",
      });
    },
    onSettled: () => {
      queryClient.invalidateQueries({
        queryKey: [AGENT_SANDBOX_KEY, "jobs"],
      });
    },
  });
};

export default useSandboxCreateJobMutation;
