import { useMutation } from "@tanstack/react-query";
import i18next from "i18next";
import { useToast } from "@/ui/use-toast";
import usePluginsStore from "@/store/PluginsStore";

const useSendOnboardingEmailMutation = () => {
  const sendOnboardingEmail = usePluginsStore((s) => s.sendOnboardingEmail);
  const { toast } = useToast();

  const mutation = useMutation({
    mutationFn: (email: string) => sendOnboardingEmail!(email),
    onError: () => {
      toast({
        title: i18next.t(
          "common.hooks.useSendOnboardingEmailMutation.failedToSendEmail",
        ),
        description: i18next.t(
          "common.hooks.useSendOnboardingEmailMutation.tryAgain",
        ),
        variant: "destructive",
      });
    },
  });

  return { ...mutation, isAvailable: sendOnboardingEmail !== null };
};

export default useSendOnboardingEmailMutation;
