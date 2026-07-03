import React, { useCallback, useRef, useState } from "react";
import { Play } from "lucide-react";

import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import { Button } from "@/ui/button";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useLoadPromptIntoPlayground from "@/v2/pages-shared/playground/useLoadPromptIntoPlayground";
import { useTranslation } from "react-i18next";

type TryInPlaygroundButtonProps = {
  prompt?: PromptWithLatestVersion;
  activeVersion?: PromptVersion;
  ButtonComponent?: React.ComponentType<{
    variant?: string;
    size?: string;
    disabled?: boolean;
    onClick?: () => void;
    children: React.ReactNode;
    className?: string;
  }>;
};

const TryInPlaygroundButton: React.FC<TryInPlaygroundButtonProps> = ({
  prompt,
  activeVersion,
  ButtonComponent = Button,
}) => {
  const { t } = useTranslation("pages/prompt");
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  const { loadPrompt, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPromptIntoPlayground();

  const handleLoadPlayground = useCallback(() => {
    if (!prompt) return;
    loadPrompt({ prompt, version: activeVersion });
  }, [loadPrompt, prompt, activeVersion]);

  return (
    <>
      <ButtonComponent
        variant="outline"
        size="sm"
        disabled={!prompt || isPendingProviderKeys}
        onClick={() => {
          if (isPlaygroundEmpty) {
            handleLoadPlayground();
          } else {
            resetKeyRef.current = resetKeyRef.current + 1;
            setOpen(true);
          }
        }}
      >
        <Play className="mr-1.5 size-3.5" />
        {t("tryInPlayground")}
      </ButtonComponent>
      <ConfirmDialog
        key={resetKeyRef.current}
        open={Boolean(open)}
        setOpen={setOpen}
        onConfirm={handleLoadPlayground}
        title={t("loadPrompt")}
        description={t("loadPromptDescription")}
        confirmText={t("loadPrompt")}
      />
    </>
  );
};

export default TryInPlaygroundButton;
