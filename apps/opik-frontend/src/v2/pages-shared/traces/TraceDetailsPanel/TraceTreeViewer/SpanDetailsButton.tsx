import React, { useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import { MoreVertical } from "lucide-react";

import { DropdownOption, OnChangeFn } from "@/types/shared";
import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuCustomCheckboxItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import {
  TREE_DATABLOCK_TYPE,
  TreeNodeConfig,
} from "@/v2/pages-shared/traces/TraceDetailsPanel/TreeDetailsStore";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { getSelectAllCheckedState } from "@/lib/utils";

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

  const options: DropdownOption<TREE_DATABLOCK_TYPE>[] = useMemo(() => {
    const base: DropdownOption<TREE_DATABLOCK_TYPE>[] = [
      { label: t("treeToolbar.duration"), value: TREE_DATABLOCK_TYPE.DURATION },
      { label: t("treeToolbar.cost"), value: TREE_DATABLOCK_TYPE.ESTIMATED_COST },
      { label: t("treeToolbar.model"), value: TREE_DATABLOCK_TYPE.MODEL },
      { label: t("treeToolbar.numberOfTokens"), value: TREE_DATABLOCK_TYPE.NUMBERS_OF_TOKENS },
      { label: t("treeToolbar.tokensBreakdown"), value: TREE_DATABLOCK_TYPE.TOKENS_BREAKDOWN },
      { label: t("treeToolbar.numberOfScores"), value: TREE_DATABLOCK_TYPE.NUMBER_OF_SCORES },
      { label: t("treeToolbar.numberOfComments"), value: TREE_DATABLOCK_TYPE.NUMBER_OF_COMMENTS },
      { label: t("treeToolbar.numberOfTags"), value: TREE_DATABLOCK_TYPE.NUMBER_OF_TAGS },
    ];

    return isGuardrailsEnabled
      ? [
          { label: t("treeToolbar.guardrails"), value: TREE_DATABLOCK_TYPE.GUARDRAILS },
          ...base,
        ]
      : base;
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

  const totalCount = options.length + 1;
  const selectedCount =
    options.filter(({ value }) => config[value]).length +
    (config[TREE_DATABLOCK_TYPE.DURATION_TIMELINE] ? 1 : 0);
  const allSelected = selectedCount === totalCount;
  const checkedState = getSelectAllCheckedState(selectedCount, totalCount);

  return (
    <DropdownMenu>
      <TooltipWrapper content={t("treeToolbar.moreOptions")}>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon-2xs">
            <MoreVertical className="size-3" />
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
              {label}
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
            {t("treeToolbar.timeline")}
          </DropdownMenuCustomCheckboxItem>
        </div>
        <DropdownMenuSeparator />
        <DropdownMenuCustomCheckboxItem
          checked={checkedState}
          onSelect={(event) => event.preventDefault()}
          onCheckedChange={() => toggleColumns(!allSelected)}
        >
          <div className="w-full break-words py-2">
            {t("treeToolbar.selectedOfTotal", { selected: selectedCount, total: totalCount })}
          </div>
        </DropdownMenuCustomCheckboxItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default SpanDetailsButton;
