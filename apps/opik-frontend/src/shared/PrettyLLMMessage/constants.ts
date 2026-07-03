import {
  Settings,
  User,
  Bot,
  Wrench,
  LucideIcon,
  CodeIcon,
} from "lucide-react";
import { MessageRole } from "./types";

export type RoleConfig = {
  icon: LucideIcon;
  label: string;
  iconColor: string;
  iconBgColor: string;
};

export const ROLE_CONFIG: Record<MessageRole, RoleConfig> = {
  system: {
    icon: Settings,
    label: "common:llmMessages.roles.system",
    iconColor: "text-[var(--tag-blue-text)]",
    iconBgColor: "bg-[var(--tag-blue-bg)]",
  },
  user: {
    icon: User,
    label: "common:llmMessages.roles.user",
    iconColor: "text-[var(--tag-turquoise-text)]",
    iconBgColor: "bg-[var(--tag-turquoise-bg)]",
  },
  assistant: {
    icon: Bot,
    label: "common:llmMessages.roles.assistant",
    iconColor: "text-[var(--tag-yellow-text)]",
    iconBgColor: "bg-[var(--tag-yellow-bg)]",
  },
  tool: {
    icon: Wrench,
    label: "common:llmMessages.roles.tool",
    iconColor: "text-[var(--tag-burgundy-text)]",
    iconBgColor: "bg-[var(--tag-burgundy-bg)]",
  },
  function: {
    icon: CodeIcon,
    label: "common:llmMessages.roles.function",
    iconColor: "text-[var(--tag-green-text)]",
    iconBgColor: "bg-[var(--tag-green-bg)]",
  },
  human: {
    icon: User,
    label: "common:llmMessages.roles.human",
    iconColor: "text-[var(--tag-turquoise-text)]",
    iconBgColor: "bg-[var(--tag-turquoise-bg)]",
  },
  ai: {
    icon: Bot,
    label: "common:llmMessages.roles.ai",
    iconColor: "text-[var(--tag-yellow-text)]",
    iconBgColor: "bg-[var(--tag-yellow-bg)]",
  },
};
