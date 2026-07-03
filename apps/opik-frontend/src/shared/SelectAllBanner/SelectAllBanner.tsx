import React from "react";
import { Trans, useTranslation } from "react-i18next";
import { CheckCircle2, Info } from "lucide-react";
import { Button } from "@/ui/button";

export interface SelectAllBannerProps {
  selectedCount: number;
  totalCount: number;
  onSelectAll: () => void;
  onClearSelection: () => void;
}

const SelectAllBanner: React.FC<SelectAllBannerProps> = ({
  selectedCount,
  totalCount,
  onSelectAll,
  onClearSelection,
}) => {
  const { t } = useTranslation("common");
  const isAllSelected = selectedCount === totalCount;

  return (
    <div className="mb-4 flex items-center justify-between rounded-md border border-blue-200 bg-blue-50 p-4">
      <div className="flex items-center gap-2">
        {isAllSelected ? (
          <CheckCircle2 className="size-4 text-[var(--color-blue)]" />
        ) : (
          <Info className="size-4 text-[var(--color-blue)]" />
        )}
        <span className="comet-body-s text-foreground">
          {isAllSelected ? (
            <Trans
              i18nKey="emptyStates.allItemsSelected"
              ns="common"
              values={{ count: totalCount }}
            />
          ) : (
            <>
              <Trans
                i18nKey="emptyStates.pageItemsSelected"
                ns="common"
                values={{ count: selectedCount }}
              />{" "}
              <Button
                variant="link"
                size="sm"
                onClick={onSelectAll}
                className="h-auto p-0"
              >
                {t("emptyStates.selectAllItems", { count: totalCount })}
              </Button>
            </>
          )}
        </span>
      </div>
      <Button
        variant="ghost"
        size="sm"
        onClick={onClearSelection}
        className="h-auto p-0"
      >
        {t("emptyStates.clearSelection")}
      </Button>
    </div>
  );
};

export default SelectAllBanner;
