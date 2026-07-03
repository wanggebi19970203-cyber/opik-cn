import React from "react";
import { useTranslation } from "react-i18next";
import { KeyRound } from "lucide-react";
import { Input } from "@/ui/input";
import CopyButton from "@/shared/CopyButton/CopyButton";
import { maskAPIKey } from "@/lib/utils";

type ApiKeyInputProps = {
  apiKey: string;
};

const ApiKeyInput: React.FunctionComponent<ApiKeyInputProps> = ({ apiKey }) => {
  const { t } = useTranslation();
  return (
    <div className="flex flex-row items-center">
      <KeyRound className="mr-3 size-6" />
      <Input
        className="mr-2 truncate"
        readOnly
        value={maskAPIKey(apiKey)}
        onFocus={(e) => {
          e.target.blur();
        }}
      />
      <CopyButton
        message={t("common.messages.apiKeyCopied")}
        text={apiKey}
        tooltipText={t("common.buttons.copyApiKey")}
      />
    </div>
  );
};

export default ApiKeyInput;
