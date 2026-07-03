import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { WELCOME_WIZARD_REST_ENDPOINT } from "@/api/api";
import {
  WelcomeWizardSubmission,
  WelcomeWizardTracking,
} from "@/types/welcome-wizard";
import { WELCOME_WIZARD_QUERY_KEY } from "./useWelcomeWizardStatus";
import { useToast } from "@/ui/use-toast";
import i18next from "i18next";

const submitWelcomeWizard = async (submission: WelcomeWizardSubmission) => {
  const { data } = await api.post<WelcomeWizardTracking>(
    WELCOME_WIZARD_REST_ENDPOINT,
    submission,
  );
  return data;
};

export default function useWelcomeWizardSubmitMutation() {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: submitWelcomeWizard,
    onSuccess: () => {
      queryClient.invalidateQueries({
        queryKey: [WELCOME_WIZARD_QUERY_KEY],
      });
      // Don't show toast here - let the component decide
    },
    onError: (error) => {
      toast({
        description: i18next.t("common:welcomeWizard.failedToSubmit"),
        variant: "destructive",
      });
      console.error("Failed to submit welcome wizard:", error);
    },
  });
}
