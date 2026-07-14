import {
  Bell,
  Blocks,
  Bot,
  ChartLine,
  Database,
  FileTerminal,
  FlaskConical,
  House,
  LayoutDashboard,
  ListChecks,
  Radar,
  Rows3,
  Settings2,
  Sparkles,
  UserPen,
  Brain,
  GitBranch,
} from "lucide-react";
import { TFunction } from "i18next";
import OllieOwl from "@/icons/ollie-owl.svg?react";
import {
  MENU_ITEM_TYPE,
  MenuItemGroup,
} from "@/v2/layout/SideBar/MenuItem/SidebarMenuItem";
import DiagnosticsNavBadge from "@/v2/layout/SideBar/MenuItem/DiagnosticsNavBadge";
const getMenuItems = ({
  projectId,
  canViewExperiments,
  canViewDatasets,
  canViewDashboards,
  canViewPrompts,
  canUsePlayground,
  canViewAgentPlayground,
  canViewOptimizationRuns,
  canViewOnlineEvaluationRules,
  canViewAlerts,
  showHomePage,
  showOlliePage,
  showDiagnostics,
  t,
}: {
  projectId: string | null;
  canViewExperiments: boolean;
  canViewDatasets: boolean;
  canViewDashboards: boolean;
  canViewPrompts: boolean;
  canUsePlayground: boolean;
  canViewAgentPlayground: boolean;
  canViewOptimizationRuns: boolean;
  canViewOnlineEvaluationRules: boolean;
  canViewAlerts: boolean;
  showHomePage: boolean;
  showOlliePage: boolean;
  showDiagnostics: boolean;
  t: TFunction;
}): MenuItemGroup[] => {
  const projectPrefix = projectId
    ? "/$workspaceName/projects/$projectId"
    : null;

  const projectPath = (suffix: string) =>
    projectPrefix ? `${projectPrefix}${suffix}` : undefined;

  return [
    {
      id: "home_group",
      items: [
        ...(showHomePage
          ? [
              {
                id: "home",
                path: projectPath("/home"),
                type: MENU_ITEM_TYPE.router as const,
                icon: House,
                label: t("navigation.menu.home"),
                disabled: !projectPrefix,
              },
            ]
          : []),
        ...(showOlliePage
          ? [
              {
                id: "ollie",
                path: projectPath("/ollie"),
                type: MENU_ITEM_TYPE.router as const,
                icon: OllieOwl,
                label: t("navigation.menu.ollie"),
                disabled: !projectPrefix,
              },
            ]
          : []),
      ],
    },
    {
      id: "observability",
      label: t("navigation.groups.observability"),
      items: [
        {
          id: "logs",
          path: projectPath("/logs"),
          type: MENU_ITEM_TYPE.router,
          icon: Rows3,
          label: t("navigation.menu.logs"),
          disabled: !projectPrefix,
        },
        ...(showDiagnostics
          ? [
              {
                id: "diagnostics",
                path: projectPath("/diagnostics"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Radar,
                label: t("navigation.menu.diagnostics"),
                disabled: !projectPrefix,
                badge: DiagnosticsNavBadge,
              },
            ]
          : []),
        ...(canViewDashboards
          ? [
              {
                id: "dashboards",
                path: projectPath("/dashboards"),
                type: MENU_ITEM_TYPE.router as const,
                icon: ChartLine,
                label: t("navigation.menu.dashboards"),
                disabled: !projectPrefix,
              },
            ]
          : []),
      ],
    },
    {
      id: "development",
      label: t("navigation.groups.development"),
      items: [
        ...(canViewPrompts
          ? [
              {
                id: "prompts",
                path: projectPath("/prompts"),
                type: MENU_ITEM_TYPE.router as const,
                icon: FileTerminal,
                label: t("navigation.menu.prompts"),
                disabled: !projectPrefix,
              },
            ]
          : []),
        ...(canViewAgentPlayground
          ? [
              {
                id: "agent_runner",
                path: projectPath("/agent-playground"),
                type: MENU_ITEM_TYPE.router as const,
                icon: GitBranch,
                label: t("navigation.menu.agent_runner"),
                disabled: !projectPrefix,
              },
            ]
          : []),
        ...(canUsePlayground
          ? [
              {
                id: "playground",
                path: projectPath("/playground"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Blocks,
                label: t("navigation.menu.playground"),
                disabled: !projectPrefix,
              },
            ]
          : []),
        ...(canViewOptimizationRuns
          ? [
              {
                id: "optimizations",
                path: projectPath("/optimizations"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Sparkles,
                label: t("navigation.menu.optimizations"),
                disabled: !projectPrefix,
              },
            ]
          : []),
      ],
    },
    {
      id: "evaluation",
      label: t("navigation.groups.evaluation"),
      items: [
        ...(canViewDatasets
          ? [
              {
                id: "test_suites",
                path: projectPath("/test-suites"),
                type: MENU_ITEM_TYPE.router as const,
                icon: ListChecks,
                label: t("navigation.menu.test_suites"),
                disabled: !projectPrefix,
              },
              {
                id: "datasets",
                path: projectPath("/datasets"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Database,
                label: t("navigation.menu.datasets"),
                disabled: !projectPrefix,
              },
            ]
          : []),
        ...(canViewExperiments
          ? [
              {
                id: "experiments",
                path: projectPath("/experiments"),
                type: MENU_ITEM_TYPE.router as const,
                icon: FlaskConical,
                label: t("navigation.menu.experiments"),
                disabled: !projectPrefix,
              },
            ]
          : []),
        {
          id: "annotation_queues",
          path: projectPath("/annotation-queues"),
          type: MENU_ITEM_TYPE.router,
          icon: UserPen,
          label: t("navigation.menu.annotation_queues"),
          disabled: !projectPrefix,
        },
      ],
    },
    {
      id: "production",
      label: t("navigation.groups.production"),
      items: [
        ...(canViewOnlineEvaluationRules
          ? [
              {
                id: "online_evaluation",
                path: projectPath("/online-evaluation"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Brain,
                label: t("navigation.menu.online_evaluation"),
                disabled: !projectPrefix,
              },
            ]
          : []),
        ...(canViewAlerts
          ? [
              {
                id: "alerts",
                path: projectPath("/alerts"),
                type: MENU_ITEM_TYPE.router as const,
                icon: Bell,
                label: t("navigation.menu.alerts"),
                disabled: !projectPrefix,
              },
            ]
          : []),
      ],
    },
  ].filter((group) => group.items.length > 0);
};

export const getWorkspaceMenuItems = (t: TFunction): MenuItemGroup[] => {
  return [
    {
      id: "workspace-nav",
      items: [
        {
          id: "workspace",
          path: "/$workspaceName/projects",
          type: MENU_ITEM_TYPE.router,
          icon: LayoutDashboard,
          label: t("navigation.menu.workspace"),
          muted: true,
          exact: true,
        },
        {
          id: "configuration",
          path: "/$workspaceName/configuration",
          type: MENU_ITEM_TYPE.router,
          icon: Settings2,
          label: t("navigation.menu.configuration"),
          muted: true,
        },
      ],
    },
  ];
};

export const getWorkspaceSidebarMenuItems = ({
  canViewDashboards,
  t,
}: {
  canViewDashboards: boolean;
  t: TFunction;
}): MenuItemGroup[] => {
  return [
    {
      id: "workspace-sidebar",
      items: [
        {
          id: "projects",
          path: "/$workspaceName/projects",
          type: MENU_ITEM_TYPE.router,
          icon: Bot,
          label: t("navigation.menu.projects"),
        },
        ...(canViewDashboards
          ? [
              {
                id: "dashboards",
                path: "/$workspaceName/dashboards",
                type: MENU_ITEM_TYPE.router as const,
                icon: ChartLine,
                label: t("navigation.menu.dashboards"),
              },
            ]
          : []),
        {
          id: "configuration",
          path: "/$workspaceName/configuration",
          type: MENU_ITEM_TYPE.router,
          icon: Settings2,
          label: t("navigation.menu.configuration"),
        },
      ],
    },
  ];
};

export default getMenuItems;
