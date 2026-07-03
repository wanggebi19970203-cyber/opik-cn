import React from "react";
import { useTranslation } from "react-i18next";
import SearchInput from "@/shared/SearchInput/SearchInput";
import { useIntegrationExplorer } from "./IntegrationExplorerContext";
import { cn } from "@/lib/utils";

type IntegrationSearchProps = {
  placeholder?: string;
  className?: string;
};

const IntegrationSearch: React.FunctionComponent<IntegrationSearchProps> = ({
  placeholder,
  className,
}) => {
  const { t } = useTranslation();
  const displayPlaceholder = placeholder ?? t('integrationExplorer.searchPlaceholder');
  const { searchText, setSearchText } = useIntegrationExplorer();

  return (
    <SearchInput
      searchText={searchText}
      setSearchText={setSearchText}
      placeholder={displayPlaceholder}
      className={cn("max-w-[240px]", className)}
      dimension="sm"
    />
  );
};

export default IntegrationSearch;
