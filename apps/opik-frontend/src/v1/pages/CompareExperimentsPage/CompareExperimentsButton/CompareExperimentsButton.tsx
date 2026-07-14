import React, { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Split } from "lucide-react";

import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import CompareExperimentsDialog from "@/v1/pages/CompareExperimentsPage/CompareExperimentsDialog";
import ExplainerIcon from "@/shared/ExplainerIcon/ExplainerIcon";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v1/constants/explainers";

interface CompareExperimentsButtonProps {
  size?: "default" | "sm" | "lg" | "icon";
  variant?:
    | "default"
    | "outline"
    | "ghost"
    | "link"
    | "destructive"
    | "secondary";
  className?: string;
  showIcon?: boolean;
  tooltipContent?: string;
}

const CompareExperimentsButton: React.FunctionComponent<
  CompareExperimentsButtonProps
> = ({
  size = "sm",
  variant = "default",
  className,
  showIcon = true,
  tooltipContent,
}) => {
  const { t } = useTranslation();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);

  return (
    <>
      <CompareExperimentsDialog
        key={resetKeyRef.current}
        open={open}
        setOpen={setOpen}
      />
      <div className="inline-flex items-center gap-2">
        <TooltipWrapper
          content={tooltipContent ?? t("compareExperiments.title")}
        >
          <Button
            size={size}
            variant={variant}
            className={className}
            onClick={() => {
              setOpen(true);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
          >
            {showIcon && <Split className="mr-1.5 size-3.5" />}
            {t("compareExperiments.title")}
          </Button>
        </TooltipWrapper>
        <ExplainerIcon
          className="-ml-0.5"
          {...EXPLAINERS_MAP[
            EXPLAINER_ID.what_does_it_mean_to_compare_my_experiments
          ]}
        />
      </div>
    </>
  );
};

export default CompareExperimentsButton;
