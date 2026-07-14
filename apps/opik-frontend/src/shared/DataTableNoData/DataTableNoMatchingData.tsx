import React from "react";
import { useTranslation } from "react-i18next";
import { useTheme } from "@/contexts/theme-provider";
import { THEME_MODE } from "@/constants/theme";
import lightImageUrl from "/images/empty-search-light.svg";
import darkImageUrl from "/images/empty-search-dark.svg";

type DataTableNoMatchingDataProps = {
  onClearFilters?: () => void;
};

const DataTableNoMatchingData: React.FC<DataTableNoMatchingDataProps> = ({
  onClearFilters,
}) => {
  const { t } = useTranslation("common");
  const { themeMode } = useTheme();
  const imageUrl = themeMode === THEME_MODE.DARK ? darkImageUrl : lightImageUrl;

  return (
    <div className="sticky left-0 flex min-h-[50vh] w-[var(--scroll-body-client-width,100%)] items-center justify-center bg-background">
      <div className="flex flex-col items-center gap-2">
        <img
          src={imageUrl}
          alt={t("dataTableNoMatchingData.noMatchingResults")}
          className="mb-1"
        />
        <h3 className="comet-body">
          {t("dataTableNoMatchingData.noMatchingResults")}
        </h3>
        <p className="comet-body-s text-muted-slate">
          {t("dataTableNoMatchingData.tryAdjustingFilters")}
        </p>
        {onClearFilters && (
          <button
            onClick={onClearFilters}
            className="comet-body-s underline underline-offset-4 hover:text-primary"
          >
            {t("dataTableNoMatchingData.clearFilters")}
          </button>
        )}
      </div>
    </div>
  );
};

export default DataTableNoMatchingData;
