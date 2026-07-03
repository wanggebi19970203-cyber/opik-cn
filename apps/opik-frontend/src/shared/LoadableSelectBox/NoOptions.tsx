import React from "react";
import { useTranslation } from "react-i18next";
import isFunction from "lodash/isFunction";
import { Button } from "@/ui/button";

export type SelectBoxProps = {
  text?: string;
  onLoadMore?: () => void;
};

export const NoOptions = ({ text = "", onLoadMore }: SelectBoxProps) => {
  const { t } = useTranslation("common");

  return (
    <div className="flex min-h-24 flex-col items-center justify-center px-6 py-4">
      <div className="comet-body-s text-center text-muted-slate">{text}</div>
      {isFunction(onLoadMore) && (
        <Button onClick={onLoadMore} variant="link">
          {t("selectBox.loadMoreItems")}
        </Button>
      )}
    </div>
  );
};
export default NoOptions;
