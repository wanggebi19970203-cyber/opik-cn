import React from "react";
import { useTranslation } from "react-i18next";
import { CellContext } from "@tanstack/react-table";
import { FileText, MessagesSquare } from "lucide-react";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Tag } from "@/ui/tag";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

const PromptTypeCell = (context: CellContext<unknown, string>) => {
  const { t } = useTranslation("pages/prompts");
  const value = context.getValue() as PROMPT_TEMPLATE_STRUCTURE;

  const TYPE_CONFIG = {
    [PROMPT_TEMPLATE_STRUCTURE.TEXT]: {
      label: t("promptType.text"),
      icon: FileText,
    },
    [PROMPT_TEMPLATE_STRUCTURE.CHAT]: {
      label: t("promptType.chat"),
      icon: MessagesSquare,
    },
  };

  const config =
    TYPE_CONFIG[value] ?? TYPE_CONFIG[PROMPT_TEMPLATE_STRUCTURE.TEXT];
  const Icon = config.icon;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Tag
        size="md"
        variant="gray"
        className="flex items-center gap-1.5 text-xs"
      >
        <Icon className="size-3 shrink-0 text-light-slate" />
        <span className="truncate text-foreground">{config.label}</span>
      </Tag>
    </CellWrapper>
  );
};

export default PromptTypeCell;
