import { useCallback } from "react";
import { Monitor, Moon, Sun } from "lucide-react";
import i18next from "i18next";

import { useTheme } from "@/contexts/theme-provider";
import { DropdownOption } from "@/types/shared";
import { SYSTEM_THEME_MODE, THEME_MODE, ThemeMode } from "@/constants/theme";

export type ThemeOption = DropdownOption<ThemeMode> & {
  icon: React.ComponentType<{ className?: string }>;
};

export const THEME_OPTIONS: ThemeOption[] = [
  {
    value: THEME_MODE.LIGHT,
    label: i18next.t("common.hooks.useThemeOptions.light"),
    icon: Sun,
  },
  {
    value: THEME_MODE.DARK,
    label: i18next.t("common.hooks.useThemeOptions.dark"),
    icon: Moon,
  },
  {
    value: SYSTEM_THEME_MODE.SYSTEM,
    label: i18next.t("common.hooks.useThemeOptions.system"),
    icon: Monitor,
  },
];

export const useThemeOptions = () => {
  const { theme, setTheme } = useTheme();

  const handleThemeSelect = useCallback(
    (selectedTheme: ThemeMode) => {
      setTheme(selectedTheme);
    },
    [setTheme],
  );

  const currentOption = THEME_OPTIONS.find((option) => option.value === theme);
  const CurrentIcon = currentOption?.icon || Sun;

  return {
    theme,
    themeOptions: THEME_OPTIONS,
    currentOption,
    CurrentIcon,
    handleThemeSelect,
  };
};
