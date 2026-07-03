import React from "react";
import { Brain } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { usePermissions } from "@/contexts/PermissionsContext";

type EvaluateButtonProps = {
  isNoRules: boolean;
  disabled: boolean;
  onClick: () => void;
};

const EvaluateButton: React.FunctionComponent<EvaluateButtonProps> = ({
  disabled,
  isNoRules,
  onClick,
}) => {
  const { t } = useTranslation();
  const {
    permissions: { canUpdateOnlineEvaluationRules },
  } = usePermissions();

  const noEvaluateOptions = !canUpdateOnlineEvaluationRules && isNoRules;

  const getTooltip = () => {
    if (disabled) return "";

    if (noEvaluateOptions) {
      return t("common.automations.noOnlineEvaluationRules");
    }

    return t("common.automations.evaluate");
  };

  return (
    <TooltipWrapper content={getTooltip()}>
      <div>
        <Button
          variant="outline"
          size="icon-sm"
          onClick={onClick}
          disabled={disabled || noEvaluateOptions}
        >
          <Brain />
        </Button>
      </div>
    </TooltipWrapper>
  );
};

export default EvaluateButton;
