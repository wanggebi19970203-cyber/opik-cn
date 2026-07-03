import React, { useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Search, X } from "lucide-react";
import { cn } from "@/lib/utils";
import DebounceInput from "@/shared/DebounceInput/DebounceInput";

type CodeBlockSearchProps = {
  searchValue?: string;
  onSearch: (value: string) => void;
};

const CodeBlockSearch: React.FC<CodeBlockSearchProps> = ({
  searchValue,
  onSearch,
}) => {
  const { t } = useTranslation();
  const [isExpanded, setIsExpanded] = useState(Boolean(searchValue));
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isExpanded) {
      inputRef.current?.focus();
    }
  }, [isExpanded]);

  const handleCollapse = () => {
    onSearch("");
    setIsExpanded(false);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Escape") handleCollapse();
  };

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setIsExpanded(true)}
        aria-label={t("common.codeBlock.search")}
        className={cn(
          "flex size-3.5 shrink-0 items-center justify-center text-muted-slate transition-colors hover:text-foreground",
          isExpanded && "invisible",
        )}
      >
        <Search className="size-2.5" />
      </button>
      {isExpanded && (
        <div className="absolute right-0 top-1/2 z-10 flex h-6 w-40 -translate-y-1/2 items-center rounded border border-border bg-background">
          <Search className="ml-1.5 size-3 shrink-0 text-muted-slate" />
          <DebounceInput
            ref={inputRef}
            value={searchValue ?? ""}
            placeholder={t("common.codeBlock.search") + "..."}
            onValueChange={(v) => onSearch(v as string)}
            onKeyDown={handleKeyDown}
            className="comet-body-xs h-6 flex-1 border-0 bg-transparent px-1.5 focus-visible:ring-0"
          />
          <button
            type="button"
            onClick={handleCollapse}
            aria-label={t("common.codeBlock.closeSearch")}
            className="mr-1 flex size-4 shrink-0 items-center justify-center text-muted-slate transition-colors hover:text-foreground"
          >
            <X className="size-3" />
          </button>
        </div>
      )}
    </div>
  );
};

export default CodeBlockSearch;
