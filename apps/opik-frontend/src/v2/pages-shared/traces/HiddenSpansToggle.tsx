import React, { useMemo } from "react";
import { Eye, EyeOff } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Span, Trace } from "@/types/traces";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  hasHiddenSpans,
  useHideSpansPreference,
} from "@/v2/pages-shared/traces/hiddenSpans";

type HiddenSpansToggleProps = {
  spans: Array<Span | Trace>;
};

// Self-contained toggle for collapsing spans the SDK marked as hidden-by-default. Renders
// nothing when the tree has no such spans. Shares its state with the rendering panel via
// the persisted preference, so no state needs to be threaded through the toolbar.
const HiddenSpansToggle: React.FC<HiddenSpansToggleProps> = ({ spans }) => {
  const { t } = useTranslation("tracing");
  const [hidden, setHidden] = useHideSpansPreference();
  const canHide = useMemo(() => hasHiddenSpans(spans), [spans]);

  if (!canHide) return null;

  return (
    <TooltipWrapper
      content={
        hidden ? t("hiddenSpans.someHidden") : t("hiddenSpans.allVisible")
      }
    >
      <Button
        onClick={() => setHidden(!hidden)}
        variant="ghost"
        size="icon-2xs"
      >
        {hidden ? <EyeOff className="size-3" /> : <Eye className="size-3" />}
      </Button>
    </TooltipWrapper>
  );
};

export default HiddenSpansToggle;
