import React from "react";
import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";

import { Button } from "@/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/ui/dialog";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/ui/form";
import { Textarea } from "@/ui/textarea";
import useRequestIntegrationMutation from "@/api/feedback/useRequestIntegrationMutation";

const createRequestIntegrationSchema = (t: TFunction) =>
  z.object({
    integrationRequest: z
      .string()
      .min(5, t("onboarding.integrationExplorer.requestMinLength"))
      .max(1000, t("onboarding.integrationExplorer.requestMaxLength")),
  });

type RequestIntegrationFormData = z.infer<
  ReturnType<typeof createRequestIntegrationSchema>
>;

type RequestIntegrationDialogProps = {
  open: boolean;
  setOpen: (open: boolean) => void;
};

const RequestIntegrationDialog: React.FunctionComponent<
  RequestIntegrationDialogProps
> = ({ open, setOpen }) => {
  const { t } = useTranslation();
  const { mutate: requestIntegration, isPending: isRequestingIntegration } =
    useRequestIntegrationMutation();
  const requestIntegrationSchema = React.useMemo(
    () => createRequestIntegrationSchema(t),
    [t],
  );

  const form = useForm<RequestIntegrationFormData>({
    resolver: zodResolver(requestIntegrationSchema),
    defaultValues: {
      integrationRequest: "",
    },
  });

  const onSubmit = (data: RequestIntegrationFormData) => {
    requestIntegration(
      { feedback: data.integrationRequest },
      {
        onSuccess: () => {
          form.reset();
          setOpen(false);
        },
      },
    );
  };

  const handleCancel = () => {
    form.reset();
    setOpen(false);
  };

  const handleOpenChange = (newOpen: boolean) => {
    setOpen(newOpen);
    if (!newOpen) {
      form.reset();
    }
  };

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>
            {t("onboarding.integrationExplorer.requestAnIntegration")}
          </DialogTitle>
          <DialogDescription>
            {t("onboarding.integrationExplorer.requestIntegrationDescription")}
          </DialogDescription>
        </DialogHeader>

        <Form {...form}>
          <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
            <FormField
              control={form.control}
              name="integrationRequest"
              render={({ field }) => (
                <FormItem>
                  <FormLabel className="sr-only">
                    {t(
                      "onboarding.integrationExplorer.integrationDescriptionLabel",
                    )}
                  </FormLabel>
                  <FormControl>
                    <Textarea
                      {...field}
                      placeholder={t(
                        "onboarding.integrationExplorer.describeIntegrationPlaceholder",
                      )}
                      className="min-h-32 resize-none"
                    />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <DialogFooter className="gap-3 md:gap-0">
              <Button type="button" variant="outline" onClick={handleCancel}>
                {t("onboarding.integrationExplorer.cancel")}
              </Button>
              <Button
                type="submit"
                disabled={
                  !form.formState.isValid ||
                  form.formState.isSubmitting ||
                  isRequestingIntegration
                }
              >
                {t("onboarding.integrationExplorer.submitRequest")}
              </Button>
            </DialogFooter>
          </form>
        </Form>
      </DialogContent>
    </Dialog>
  );
};

export default RequestIntegrationDialog;
