import React from "react";
import { Brain } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button, ButtonProps } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { usePermissions } from "@/contexts/PermissionsContext";

const DEFAULT_ICON_SIZE = "size-3.5";

const ICON_SIZE_BY_BUTTON_SIZE: Partial<
  Record<NonNullable<ButtonProps["size"]>, string>
> = {
  "2xs": "size-3",
};

type EvaluateButtonProps = {
  isNoRules: boolean;
  disabled: boolean;
  onClick: () => void;
  buttonVariant?: "outline" | "ghost" | "ghostInverted";
  buttonSize?: ButtonProps["size"];
  label?: string;
};

const EvaluateButton: React.FunctionComponent<EvaluateButtonProps> = ({
  disabled,
  isNoRules,
  onClick,
  buttonVariant = "outline",
  buttonSize,
  label,
}) => {
  const { t } = useTranslation("online-evaluation");
  const {
    permissions: { canUpdateOnlineEvaluationRules },
  } = usePermissions();

  const noEvaluateOptions = !canUpdateOnlineEvaluationRules && isNoRules;

  const getTooltip = () => {
    if (disabled) return "";

    if (noEvaluateOptions) {
      return t("evaluateButton.noRulesTooltip");
    }

    return t("evaluateButton.evaluate");
  };

  return (
    <TooltipWrapper content={getTooltip()}>
      <div>
        <Button
          variant={buttonVariant}
          size={buttonSize ?? (label ? "sm" : "icon-sm")}
          onClick={onClick}
          disabled={disabled || noEvaluateOptions}
        >
          <Brain
            className={
              (buttonSize && ICON_SIZE_BY_BUTTON_SIZE[buttonSize]) ??
              DEFAULT_ICON_SIZE
            }
          />
          {label && <span className="ml-2">{label}</span>}
        </Button>
      </div>
    </TooltipWrapper>
  );
};

export default EvaluateButton;
