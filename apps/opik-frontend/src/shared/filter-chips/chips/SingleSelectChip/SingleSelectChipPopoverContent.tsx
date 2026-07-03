import React, { useMemo, useState } from "react";
import { X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { DropdownMenuItem } from "@/ui/dropdown-menu";
import { Separator } from "@/ui/separator";
import SearchInput from "@/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import {
  SingleSelectChipDefinition,
  SingleSelectChipValue,
} from "@/shared/filter-chips/types";

interface SingleSelectChipPopoverContentProps {
  definition: SingleSelectChipDefinition;
  value: SingleSelectChipValue | undefined;
  onSelect: (value: SingleSelectChipValue) => void;
  onClear: () => void;
}

const SEARCH_OPTION_THRESHOLD = 5;

const SingleSelectChipPopoverContent: React.FC<
  SingleSelectChipPopoverContentProps
> = ({ definition, value, onSelect, onClear }) => {
  const { t } = useTranslation("common");
  const [search, setSearch] = useState("");
  const showSearch = definition.options.length > SEARCH_OPTION_THRESHOLD;

  const filteredOptions = useMemo(() => {
    if (!showSearch || search.trim() === "") return definition.options;
    const needle = search.trim().toLowerCase();
    return definition.options.filter((option) =>
      option.label.toLowerCase().includes(needle),
    );
  }, [definition.options, search, showSearch]);

  return (
    <>
      {showSearch && (
        <div
          className="shrink-0"
          onKeyDown={(event) => event.stopPropagation()}
        >
          <SearchInput
            searchText={search}
            setSearchText={setSearch}
            placeholder={t("singleSelectChip.search")}
            dimension="sm"
            variant="ghost"
          />
          <Separator className="my-1" />
        </div>
      )}
      <div className="min-h-0 flex-1 overflow-y-auto">
        {filteredOptions.length === 0 && (
          <div className="comet-body-s flex h-32 w-full items-center justify-center text-muted-slate">
            {t("singleSelectChip.noSearchResults")}
          </div>
        )}
        {filteredOptions.map((option) => {
          const isSelected = value?.value === option.value;
          return (
            <DropdownMenuItem
              key={option.value}
              size="sm"
              selected={isSelected}
              onSelect={(event) => {
                if (isSelected) {
                  event.preventDefault();
                  return;
                }
                onSelect({ value: option.value });
              }}
              className="group flex justify-between gap-2"
            >
              <TooltipWrapper content={option.label}>
                <span className="truncate text-sm">{option.label}</span>
              </TooltipWrapper>
              {isSelected && (
                <TooltipWrapper content={t("singleSelectChip.clearFilter")}>
                  <button
                    type="button"
                    aria-label={t("singleSelectChip.clearFilter")}
                    onClick={(event) => {
                      event.stopPropagation();
                      onClear();
                    }}
                    className="hidden size-4 items-center justify-center text-primary group-hover:flex group-focus-visible:flex"
                  >
                    <X className="size-4" />
                  </button>
                </TooltipWrapper>
              )}
            </DropdownMenuItem>
          );
        })}
      </div>
    </>
  );
};

export default SingleSelectChipPopoverContent;
