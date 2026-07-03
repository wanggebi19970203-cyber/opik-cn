import { useMutation } from "@tanstack/react-query";
import get from "lodash/get";

import axios, { AxiosError } from "axios";
import i18next from "i18next";

import { useToast } from "@/ui/use-toast";
import { APP_VERSION } from "@/constants/app";
import { STATS_ANONYMOUS_ID, STATS_COMET_ENDPOINT } from "@/api/api";

type UseRequestIntegrationMutationParams = {
  feedback: string;
};

const EVENT_TYPE = "opik_request_integration_fe";

const useRequestIntegrationMutation = () => {
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({ feedback }: UseRequestIntegrationMutationParams) => {
      return axios.post(STATS_COMET_ENDPOINT, {
        anonymous_id: STATS_ANONYMOUS_ID,
        event_type: EVENT_TYPE,
        event_properties: {
          feedback,
          version: APP_VERSION || null,
        },
      });
    },
    onSuccess: () => {
      toast({
        title: i18next.t("common:messages.feedbackSent"),
        description: i18next.t("common:messages.feedbackSentDescription"),
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
  });
};

export default useRequestIntegrationMutation;
