import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/ui/button";
import { DetailsActionSectionValue, DetailsActionSection } from "./types";
import { MessageSquareMore, PenLine, Sparkles } from "lucide-react";
import { cn } from "@/lib/utils";
import { useTranslation } from "react-i18next";

export enum ButtonLayoutSize {
  Large = "lg",
  Small = "sm",
}

const isLargeLayout = (layoutSize: ButtonLayoutSize) =>
  layoutSize === ButtonLayoutSize.Large;
const formatCounter = (
  layoutSize: ButtonLayoutSize,
  count?: number | string,
) => {
  if (!count) return;
  return isLargeLayout(layoutSize) ? `(${count})` : String(count);
};

const useConfigMap = () => {
  const { t } = useTranslation("tracing");
  return {
    [DetailsActionSection.Annotations]: {
      icon: <PenLine className="size-3.5" />,
      tooltip: t("annotate.feedbackScores"),
    },
    [DetailsActionSection.Comments]: {
      icon: <MessageSquareMore className="size-3.5" />,
      tooltip: t("detailsPanel.comments"),
    },
    [DetailsActionSection.AIAssistants]: {
      icon: <Sparkles className="size-3.5" />,
      tooltip: t("aiAssistant.debugWithOpikAssist"),
    },
  };
};

type DetailsActionSectionToggleProps = {
  activeSection: DetailsActionSectionValue | null;
  setActiveSection: (v: DetailsActionSectionValue) => void;
  layoutSize: ButtonLayoutSize;
  count?: number | string;
  type: DetailsActionSectionValue;
  disabled?: boolean;
  tooltipContent?: string;
};
const DetailsActionSectionToggle: React.FC<DetailsActionSectionToggleProps> = ({
  activeSection,
  setActiveSection,
  layoutSize,
  count,
  type,
  disabled,
  tooltipContent,
}) => {
  const configMap = useConfigMap();
  const showFullActionLabel = isLargeLayout(layoutSize);

  return (
    <TooltipWrapper content={tooltipContent || configMap[type].tooltip}>
      <div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => setActiveSection(type)}
          className={cn(
            "gap-1",
            activeSection === type && "bg-primary-100 hover:bg-primary-100",
          )}
          disabled={disabled}
        >
          {configMap[type].icon}
          {showFullActionLabel && (
            <div className="pl-1">{configMap[type].tooltip}</div>
          )}
          {Boolean(count) && <div>{formatCounter(layoutSize, count)}</div>}
        </Button>
      </div>
    </TooltipWrapper>
  );
};

export default DetailsActionSectionToggle;
