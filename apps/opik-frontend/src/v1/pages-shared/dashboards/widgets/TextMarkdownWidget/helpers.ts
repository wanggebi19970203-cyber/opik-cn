import i18next from "i18next";

const DEFAULT_TITLE = i18next.t("dashboard.textWidget", "Text");

const calculateTextMarkdownTitle = (): string => {
  return DEFAULT_TITLE;
};

export const widgetHelpers = {
  getDefaultConfig: () => ({}),
  calculateTitle: calculateTextMarkdownTitle,
};
