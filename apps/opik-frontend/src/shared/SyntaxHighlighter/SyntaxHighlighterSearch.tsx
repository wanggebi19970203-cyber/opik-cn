import React from "react";
import { useTranslation } from "react-i18next";
import ExpandableSearchInput from "@/shared/ExpandableSearchInput/ExpandableSearchInput";

export interface SyntaxHighlighterSearchProps {
  searchValue?: string;
  onSearch: (value: string) => void;
  onPrev?: () => void;
  onNext?: () => void;
}

const SyntaxHighlighterSearch: React.FC<SyntaxHighlighterSearchProps> = ({
  searchValue,
  onSearch,
  onPrev = () => {},
  onNext = () => {},
}) => {
  const { t } = useTranslation();

  return (
    <div className="flex min-w-[200px] max-w-[60%] flex-auto justify-end overflow-hidden">
      <ExpandableSearchInput
        value={searchValue}
        placeholder={t("common:placeholders.search")}
        buttonVariant="ghost"
        size="sm"
        onChange={onSearch}
        onPrev={onPrev}
        onNext={onNext}
      />
    </div>
  );
};

export default SyntaxHighlighterSearch;
