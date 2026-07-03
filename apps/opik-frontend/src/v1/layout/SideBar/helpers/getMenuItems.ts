import {
  Bell,
  FlaskConical,
  LayoutGrid,
  FileTerminal,
  ListChecks,
  LucideHome,
  Blocks,
  Brain,
  ChartLine,
  SparklesIcon,
  UserPen,
} from "lucide-react";
import { TFunction } from "i18next";
import {
  MENU_ITEM_TYPE,
  MenuItemGroup,
} from "@/v1/layout/SideBar/MenuItem/SidebarMenuItem";

const getMenuItems = ({
  canViewExperiments,
  canViewDashboards,
  canViewDatasets,
  canUsePlayground,
  canViewOptimizationRuns,
  t,
}: {
  canViewExperiments: boolean;
  canViewDashboards: boolean;
  canViewDatasets: boolean;
  canUsePlayground: boolean;
  canViewOptimizationRuns: boolean;
  t: TFunction;
}): MenuItemGroup[] => {
  return [
    {
      id: "home",
      items: [
        {
          id: "home",
          path: "/$workspaceName/home",
          type: MENU_ITEM_TYPE.router,
          icon: LucideHome,
          label: t("menu.home"),
        },
        ...(canViewDashboards
          ? [
              {
                id: "dashboards",
                path: "/$workspaceName/dashboards",
                type: MENU_ITEM_TYPE.router,
                icon: ChartLine,
                label: t("menu.dashboards"),
                count: "dashboards",
              },
            ]
          : []),
      ],
    },
    {
      id: "observability",
      label: t("groups.observability"),
      items: [
        {
          id: "projects",
          path: "/$workspaceName/projects",
          type: MENU_ITEM_TYPE.router,
          icon: LayoutGrid,
          label: t("menu.projects"),
          count: "projects",
        },
      ],
    },
    {
      id: "evaluation",
      label: t("groups.evaluation"),
      items: [
        ...(canViewExperiments
          ? [
              {
                id: "experiments" as const,
                path: "/$workspaceName/experiments" as const,
                type: MENU_ITEM_TYPE.router,
                icon: FlaskConical,
                label: t("menu.experiments"),
                count: "experiments" as const,
              },
            ]
          : []),
        ...(canViewDatasets
          ? [
              {
                id: "test_suites",
                path: "/$workspaceName/test-suites",
                type: MENU_ITEM_TYPE.router,
                icon: ListChecks,
                label: t("menu.datasets"),
                count: "test_suites",
              },
            ]
          : []),
        {
          id: "annotation_queues",
          path: "/$workspaceName/annotation-queues",
          type: MENU_ITEM_TYPE.router,
          icon: UserPen,
          label: t("menu.annotation_queues"),
          count: "annotation_queues",
        },
      ],
    },
    {
      id: "prompt_engineering",
      label: t("groups.promptEngineering"),
      items: [
        {
          id: "prompts",
          path: "/$workspaceName/prompts",
          type: MENU_ITEM_TYPE.router,
          icon: FileTerminal,
          label: t("menu.prompts"),
          count: "prompts",
        },
        ...(canUsePlayground
          ? [
              {
                id: "playground",
                path: "/$workspaceName/playground",
                type: MENU_ITEM_TYPE.router,
                icon: Blocks,
                label: t("menu.playground"),
              },
            ]
          : []),
      ],
    },
    ...(canViewOptimizationRuns
      ? [
          {
            id: "optimization",
            label: t("groups.optimization"),
            items: [
              {
                id: "optimizations",
                path: "/$workspaceName/optimizations",
                type: MENU_ITEM_TYPE.router,
                icon: SparklesIcon,
                label: t("menu.optimizationStudio"),
                count: "optimizations",
                showIndicator: "optimizations_running",
              },
            ],
          },
        ]
      : []),
    {
      id: "production",
      label: t("groups.production"),
      items: [
        {
          id: "online_evaluation",
          path: "/$workspaceName/online-evaluation",
          type: MENU_ITEM_TYPE.router,
          icon: Brain,
          label: t("menu.online_evaluation"),
          count: "rules",
        },
        {
          id: "alerts",
          path: "/$workspaceName/alerts",
          type: MENU_ITEM_TYPE.router,
          icon: Bell,
          label: t("menu.alerts"),
          count: "alerts",
        },
      ],
    },
  ];
};

export default getMenuItems;
