import ApiKeyInput from "@/shared/ApiKeyInput/ApiKeyInput";
import { useTranslation } from "react-i18next";
import { useUserApiKey } from "@/store/AppStore";

const ApiKeyCard = () => {
  const { t } = useTranslation();
  const apiKey = useUserApiKey();

  if (!apiKey) {
    return null;
  }

  return (
    <div className="flex flex-1 flex-col justify-between gap-4 rounded-md border bg-background p-6">
      <div className="comet-title-xs text-foreground-secondary">{t("onboarding.apiKey")}</div>
      <ApiKeyInput apiKey={apiKey} />
    </div>
  );
};

export default ApiKeyCard;
