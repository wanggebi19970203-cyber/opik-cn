import type { TFunction } from "i18next";
import {
  BotMessageSquare,
  FileSliders,
  SquareDashedMousePointer,
  type LucideIcon,
} from "lucide-react";

export type StudioCardId = "demo" | "studio" | "sdk";

export type StudioCardConfig = {
  id: StudioCardId;
  icon: LucideIcon;
  title: string;
  description: string;
  onClick: () => void;
};

type StudioCardHandlers = {
  navigateToStudio: (templateId?: string) => void;
  onOptimizeViaSdkClick: () => void;
  t: TFunction<"optimizations">;
};

// Shared definition of the onboarding cards; each view styles them by `id`.
export const getStudioCardConfigs = ({
  navigateToStudio,
  onOptimizeViaSdkClick,
  t,
}: StudioCardHandlers): StudioCardConfig[] => [
  {
    id: "demo",
    icon: BotMessageSquare,
    title: t("optimizations.emptyState.runDemoExample"),
    description: t("optimizations.emptyState.runDemoExampleDesc"),
    onClick: () => navigateToStudio("opik-chatbot"),
  },
  {
    id: "studio",
    icon: SquareDashedMousePointer,
    title: t("optimizations.studioTemplates.useOptimizationStudio"),
    description: t("optimizations.emptyState.startOptimizationRunDesc"),
    onClick: () => navigateToStudio(),
  },
  {
    id: "sdk",
    icon: FileSliders,
    title: t("optimizations.emptyState.optimizeViaSdk"),
    description: t("optimizations.emptyState.optimizeViaSdkDesc"),
    onClick: onOptimizeViaSdkClick,
  },
];
