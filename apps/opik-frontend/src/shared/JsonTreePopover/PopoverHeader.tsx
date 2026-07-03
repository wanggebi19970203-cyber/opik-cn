import React from "react";
import { Trans, useTranslation } from "react-i18next";

interface PopoverHeaderProps {
  searchQuery: string;
  pathToExpand: string | null;
  searchTerm: string;
  isArrayAccess: boolean;
}

const PopoverHeader: React.FC<PopoverHeaderProps> = ({
  searchQuery,
  pathToExpand,
  searchTerm,
  isArrayAccess,
}) => {
  const { t } = useTranslation();

  if (!searchQuery.trim()) {
    return (
      <div className="border-b px-4 py-3">
        <h4 className="comet-body-xs-accented">{t("common:jsonTree.selectVariable")}</h4>
        <p className="comet-body-xs mt-1 text-light-slate">
          <Trans
            i18nKey="common:jsonTree.startTypingToFilter"
            components={{
              1: <span className="font-mono">.</span>,
              3: <span className="font-mono">[</span>,
            }}
          />
        </p>
      </div>
    );
  }

  const renderTitle = () => {
    if (!pathToExpand) {
      return (
        <>
          {t("common:jsonTree.filtering")} <span className="font-mono">{searchQuery}</span>
        </>
      );
    }

    return (
      <>
        {isArrayAccess ? t("common:jsonTree.array") : t("common:jsonTree.path")}{" "}
        <span className="font-mono">{pathToExpand}</span>
        {isArrayAccess && !searchTerm && (
          <span className="text-light-slate"> → {t("common:jsonTree.selectAnIndex")}</span>
        )}
        {searchTerm && (
          <span className="text-light-slate">
            {" "}
            → {isArrayAccess ? t("common:jsonTree.index") : t("common:jsonTree.filteringBy")} &quot;{searchTerm}
            &quot;
          </span>
        )}
      </>
    );
  };

  const renderHint = () => {
    if (isArrayAccess) {
      return t("common:jsonTree.typeIndexToFilter");
    }

    return (
      <Trans
        i18nKey="common:jsonTree.typeToExpand"
        components={{
          1: <span className="font-mono">.</span>,
          3: <span className="font-mono">[</span>,
        }}
      />
    );
  };

  return (
    <div className="border-b px-4 py-3">
      <h4 className="comet-body-xs-accented">{renderTitle()}</h4>
      <p className="comet-body-xs mt-1 text-light-slate">{renderHint()}</p>
    </div>
  );
};

export default PopoverHeader;
