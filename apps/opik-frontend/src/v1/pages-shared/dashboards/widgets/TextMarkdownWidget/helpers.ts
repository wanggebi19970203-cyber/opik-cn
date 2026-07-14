import i18next from "i18next";

const calculateTextMarkdownTitle = (): string => {
  return i18next.t("dashboard.textWidget");
};

export const widgetHelpers = {
  getDefaultConfig: () => ({}),
  calculateTitle: calculateTextMarkdownTitle,
};
