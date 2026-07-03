import React, { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { UseFormReturn } from "react-hook-form";
import { Label } from "@/ui/label";
import EyeInput from "@/shared/EyeInput/EyeInput";
import { AIProviderFormType } from "@/v1/pages-shared/llm/ManageAIProviderDialog/schema";
import get from "lodash/get";
import { FormControl, FormField, FormItem, FormMessage } from "@/ui/form";
import { cn } from "@/lib/utils";
import { Input } from "@/ui/input";
import { Description } from "@/ui/description";
import { Button } from "@/ui/button";
import { CheckCircle2, XCircle, Loader2 } from "lucide-react";
import { PROVIDERS } from "@/constants/providers";
import { PROVIDER_TYPE } from "@/types/providers";
import useOllamaTestConnectionMutation from "@/api/provider-keys/useOllamaTestConnectionMutation";
import useOllamaListModelsMutation from "@/api/provider-keys/useOllamaListModelsMutation";
import { useToast } from "@/ui/use-toast";
import CustomHeadersField from "./CustomHeadersField";

type OllamaProviderDetailsProps = {
  form: UseFormReturn<AIProviderFormType>;
  isEdit?: boolean;
};

const OllamaProviderDetails: React.FC<OllamaProviderDetailsProps> = ({
  form,
  isEdit = false,
}) => {
  const { t } = useTranslation("prompt");
  const { toast } = useToast();
  const [connectionTested, setConnectionTested] = useState(false);
  const [connectionSuccess, setConnectionSuccess] = useState(false);
  const [modelsFetchFailed, setModelsFetchFailed] = useState(false);

  const testConnectionMutation = useOllamaTestConnectionMutation();
  const listModelsMutation = useOllamaListModelsMutation();

  const url = form.watch("url");
  const apiKey = form.watch("apiKey");
  const headers = form.watch("headers");

  // Reset connection status when any credential-affecting field changes
  useEffect(() => {
    setConnectionTested(false);
    setConnectionSuccess(false);
    setModelsFetchFailed(false);
  }, [url, apiKey, headers]);

  const handleTestConnection = async () => {
    if (!url) {
      toast({
        title: t("ollamaProviderDetails.urlRequired"),
        description: t("ollamaProviderDetails.urlRequiredDescription"),
        variant: "destructive",
      });
      return;
    }

    try {
      const response = await testConnectionMutation.mutateAsync({
        base_url: url,
        api_key: apiKey || undefined,
      });

      setConnectionTested(true);
      setConnectionSuccess(response.connected);

      if (response.connected) {
        toast({
          title: t("ollamaProviderDetails.connectionSuccessful"),
          description: t("ollamaProviderDetails.connectedToOllama", { version: response.version || "" }),
        });

        // Auto-fetch models if connection successful
        handleFetchModels();
      } else {
        toast({
          title: t("ollamaProviderDetails.connectionFailed"),
          description: response.error_message || t("ollamaProviderDetails.unableToConnect"),
          variant: "destructive",
        });
      }
    } catch (error) {
      setConnectionTested(true);
      setConnectionSuccess(false);
      toast({
        title: t("ollamaProviderDetails.connectionFailed"),
        description: t("ollamaProviderDetails.unableToConnectInstance"),
        variant: "destructive",
      });
    }
  };

  const handleFetchModels = async () => {
    if (!url) {
      return;
    }

    try {
      const models = await listModelsMutation.mutateAsync({
        base_url: url,
        api_key: apiKey || undefined,
      });

      if (models.length > 0) {
        const modelNames = models.map((m) => m.name).join(", ");
        form.setValue("models", modelNames);
        setModelsFetchFailed(false);

        toast({
          title: t("ollamaProviderDetails.modelsDiscovered"),
          description: t("ollamaProviderDetails.foundModels", { count: models.length }),
        });
      } else {
        setModelsFetchFailed(false);
        toast({
          title: t("ollamaProviderDetails.noModelsFound"),
          description: t("ollamaProviderDetails.noModelsDescription"),
          variant: "default",
        });
      }
    } catch (error) {
      setModelsFetchFailed(true);
      toast({
        title: t("ollamaProviderDetails.failedToFetchModels"),
        description: t("ollamaProviderDetails.unableToRetrieveModels"),
        variant: "destructive",
      });
    }
  };

  const getDefaultUrl = () => {
    return PROVIDERS[PROVIDER_TYPE.OLLAMA].defaultUrl;
  };

  return (
    <div className="flex flex-col gap-4 pb-4">
      <p className="comet-body-s text-muted-slate">
        {PROVIDERS[PROVIDER_TYPE.OLLAMA].description}
      </p>
      {!isEdit && (
        <FormField
          control={form.control}
          name="providerName"
          render={({ field, formState }) => {
            const validationErrors = get(formState.errors, ["providerName"]);

            return (
              <FormItem>
                <Label htmlFor="providerName">{t("ollamaProviderDetails.providerName")}</Label>
                <FormControl>
                  <Input
                    id="providerName"
                    placeholder="my-ollama-instance"
                    value={field.value}
                    onChange={(e) => field.onChange(e.target.value)}
                    disabled={isEdit}
                    className={cn({
                      "border-destructive": Boolean(validationErrors?.message),
                    })}
                  />
                </FormControl>
                <FormMessage />
                <Description>
                  {t("ollamaProviderDetails.providerNameDescription")}
                </Description>
              </FormItem>
            );
          }}
        />
      )}
      <FormField
        control={form.control}
        name="url"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["url"]);

          return (
            <FormItem>
              <div className="flex items-center justify-between">
                <Label htmlFor="url">{t("ollamaProviderDetails.ollamaUrl")}</Label>
                <Button
                  type="button"
                  variant="link"
                  size="sm"
                  onClick={() => field.onChange(getDefaultUrl())}
                  className="h-auto p-0 text-xs"
                >
                  {t("ollamaProviderDetails.useDefaultUrl")}
                </Button>
              </div>
              <FormControl>
                <Input
                  id="url"
                  placeholder={getDefaultUrl()}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                <strong>{t("ollamaProviderDetails.urlMustEndWith")}</strong> {t("ollamaProviderDetails.urlMustEndWithDescription")}
              </Description>
            </FormItem>
          );
        }}
      />

      <Button
        type="button"
        variant="outline"
        onClick={handleTestConnection}
        disabled={
          !url ||
          testConnectionMutation.isPending ||
          listModelsMutation.isPending
        }
        className="w-full"
      >
        {testConnectionMutation.isPending ? (
          <>
            <Loader2 className="mr-2 size-4 animate-spin" />
            {t("ollamaProviderDetails.testingConnection")}
          </>
        ) : listModelsMutation.isPending ? (
          <>
            <Loader2 className="mr-2 size-4 animate-spin" />
            {t("ollamaProviderDetails.discoveringModels")}
          </>
        ) : connectionTested ? (
          connectionSuccess ? (
            modelsFetchFailed ? (
              <>
                <CheckCircle2 className="mr-2 size-4 text-warning" />
                {t("ollamaProviderDetails.connectedModelsFailed")}
              </>
            ) : (
              <>
                <CheckCircle2 className="mr-2 size-4 text-green-600" />
                {t("ollamaProviderDetails.connected")}
              </>
            )
          ) : (
            <>
              <XCircle className="mr-2 size-4 text-destructive" />
              {t("ollamaProviderDetails.connectionFailedRetry")}
            </>
          )
        ) : (
          t("ollamaProviderDetails.connect")
        )}
      </Button>

      <FormField
        control={form.control}
        name="apiKey"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["apiKey"]);

          return (
            <FormItem>
              <Label htmlFor="apiKey">{t("ollamaProviderDetails.apiKeyOptional")}</Label>
              <FormControl>
                <EyeInput
                  id="apiKey"
                  placeholder={t("ollamaProviderDetails.apiKeyPlaceholder")}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                {t("ollamaProviderDetails.apiKeyDescription")}
              </Description>
            </FormItem>
          );
        }}
      />

      <FormField
        control={form.control}
        name="models"
        render={({ field, formState }) => {
          const validationErrors = get(formState.errors, ["models"]);

          return (
            <FormItem>
              <Label htmlFor="models">{t("ollamaProviderDetails.modelsList")}</Label>
              <FormControl>
                <Input
                  id="models"
                  placeholder={t("ollamaProviderDetails.modelsListPlaceholder")}
                  value={field.value}
                  onChange={(e) => field.onChange(e.target.value)}
                  className={cn({
                    "border-destructive": Boolean(validationErrors?.message),
                  })}
                />
              </FormControl>
              <FormMessage />
              <Description>
                {t("ollamaProviderDetails.modelsListDescription")}
              </Description>
            </FormItem>
          );
        }}
      />

      <CustomHeadersField
        form={form}
        description={t("ollamaProviderDetails.customHeadersDescription")}
      />
    </div>
  );
};

export default OllamaProviderDetails;
