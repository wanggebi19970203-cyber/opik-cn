import React from "react";
import { useTranslation } from "react-i18next";
import { Code2, FileTerminal, LucideIcon } from "lucide-react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Tag } from "@/ui/tag";
import { CreateDatasetMode } from "@/v2/pages-shared/datasets/CreateDatasetSidebar/CreateDatasetSidebar";

export type CreateEntityOption = {
  mode: CreateDatasetMode;
  Icon: LucideIcon;
  iconClassName: string;
  title: string;
  description: string;
};

export const getCreateEntityOptions = (
  t: (key: string) => string,
): CreateEntityOption[] => [
  {
    mode: "upload",
    Icon: FileTerminal,
    iconClassName: "text-chart-burgundy",
    title: t("createMenu.uploadFile"),
    description: t("createMenu.importFromCsvJson"),
  },
  {
    mode: "sdk",
    Icon: Code2,
    iconClassName: "text-chart-green",
    title: t("createMenu.useSdk"),
    description: t("createMenu.defineInCode"),
  },
];

type CreateEntityMenuProps = {
  children: React.ReactNode;
  onSelect: (mode: CreateDatasetMode) => void;
  align?: "start" | "center" | "end";
};

const CreateEntityMenu: React.FC<CreateEntityMenuProps> = ({
  children,
  onSelect,
  align = "end",
}) => {
  const { t } = useTranslation("datasets");
  const options = getCreateEntityOptions(t);
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>{children}</DropdownMenuTrigger>
      <DropdownMenuContent align={align} className="w-80">
        {options.map(({ mode, Icon, iconClassName, title, description }) => (
          <DropdownMenuItem
            key={mode}
            className="items-start gap-2.5 py-2.5"
            onSelect={() => onSelect(mode)}
          >
            <Icon className={`mt-0.5 size-4 shrink-0 ${iconClassName}`} />
            <div className="flex flex-1 flex-col gap-0.5">
              <div className="flex items-center justify-between gap-2">
                <span className="comet-body-s-accented">{title}</span>
                {mode === "upload" && (
                  <div className="flex shrink-0 items-center gap-1.5">
                    <Tag variant="pink" size="sm">
                      CSV
                    </Tag>
                    <Tag variant="pink" size="sm">
                      JSON
                    </Tag>
                  </div>
                )}
              </div>
              <span className="comet-body-xs text-light-slate">
                {description}
              </span>
            </div>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default CreateEntityMenu;
