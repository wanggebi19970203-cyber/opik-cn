import React, { useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { Eye, EyeOff, ScanText } from "lucide-react";

import { DropdownOption, OnChangeFn } from "@/types/shared";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuCustomCheckboxItem,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import {
  TREE_DATABLOCK_TYPE,
  TreeNodeConfig,
} from "@/v1/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

const OPTIONS: DropdownOption<TREE_DATABLOCK_TYPE>[] = [
  { label: "treeToolbar.duration", value: TREE_DATABLOCK_TYPE.DURATION },
  { label: "treeToolbar.numberOfTokens", value: TREE_DATABLOCK_TYPE.NUMBERS_OF_TOKENS },
  { label: "treeToolbar.tokensBreakdown", value: TREE_DATABLOCK_TYPE.TOKENS_BREAKDOWN },
  { label: "treeToolbar.cost", value: TREE_DATABLOCK_TYPE.ESTIMATED_COST },
  { label: "treeToolbar.numberOfScores", value: TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES },
  {
    label: "treeToolbar.numberOfComments",
    value: TREE_DATABLOCK_TYPE.NUMBER_OF_COMMENTS,
  },
  { label: "treeToolbar.numberOfTags", value: TREE_DATABLOCK_TYPE.NUMBER_OF_TAGS },
  { label: "treeToolbar.model", value: TREE_DATABLOCK_TYPE.MODEL },
];

type SpanDetailsButtonProps = {
  config: TreeNodeConfig;
  onConfigChange: OnChangeFn<TreeNodeConfig>;
};

const SpanDetailsButton: React.FC<SpanDetailsButtonProps> = ({
  config,
  onConfigChange,
}) => {
  const { t } = useTranslation("tracing");
  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const options = useMemo(() => {
    return isGuardrailsEnabled
      ? [
          { label: t("treeToolbar.guardrails"), value: TREE_DATABLOCK_TYPE.GUARDRAILS },
          ...OPTIONS,
        ]
      : OPTIONS;
  }, [isGuardrailsEnabled, t]);

  const toggleColumns = useCallback(
    (value: boolean) => {
      const newConfig: Partial<TreeNodeConfig> = {
        [TREE_DATABLOCK_TYPE.DURATION_TIMELINE]: value,
      };
      options.forEach(({ value: key }) => {
        newConfig[key] = value;
      });
      onConfigChange(newConfig as TreeNodeConfig);
    },
    [onConfigChange, options],
  );

  return (
    <DropdownMenu>
      <TooltipWrapper content={t("detailsPanel.spanDetails")}>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon-2xs">
            <ScanText />
          </Button>
        </DropdownMenuTrigger>
      </TooltipWrapper>
      <DropdownMenuContent className="relative max-w-72 p-0" align="end">
        <div className="max-h-[calc(var(--radix-popper-available-height)-60px)] overflow-y-auto overflow-x-hidden pb-1">
          {options.map(({ label, value }) => (
            <DropdownMenuCustomCheckboxItem
              key={value}
              checked={config[value]}
              onSelect={(event) => event.preventDefault()}
              onCheckedChange={() =>
                onConfigChange((config) => ({
                  ...config,
                  [value]: !config[value],
                }))
              }
            >
              {t(label)}
            </DropdownMenuCustomCheckboxItem>
          ))}
          <DropdownMenuSeparator />
          <DropdownMenuCustomCheckboxItem
            checked={config[TREE_DATABLOCK_TYPE.DURATION_TIMELINE]}
            onSelect={(event) => event.preventDefault()}
            onCheckedChange={() =>
              onConfigChange((config) => ({
                ...config,
                [TREE_DATABLOCK_TYPE.DURATION_TIMELINE]:
                  !config[TREE_DATABLOCK_TYPE.DURATION_TIMELINE],
              }))
            }
          >
            {t("treeToolbar.durationTimeline")}
          </DropdownMenuCustomCheckboxItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => toggleColumns(true)}>
            <Eye className="mr-2 size-4" />
            {t("treeToolbar.showAll")}
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => toggleColumns(false)}>
            <EyeOff className="mr-2 size-4" />
            {t("treeToolbar.hideAll")}
          </DropdownMenuItem>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default SpanDetailsButton;
