import React from "react";
import { useTranslation } from "react-i18next";
import { Copy, MoreHorizontal, Share, Trash } from "lucide-react";

import { Button } from "@/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type DatasetItemActionsDropdownProps = {
  datasetItemId: string;
  itemName: string;
  onShare: () => void;
  onCopyId: () => void;
  onDelete: () => void;
};

const DatasetItemActionsDropdown: React.FC<DatasetItemActionsDropdownProps> = ({
  datasetItemId,
  itemName,
  onShare,
  onCopyId,
  onDelete,
}) => {
  const { t } = useTranslation("datasets");
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline" size="icon-2xs">
          <span className="sr-only">{t("itemActions.actionsMenu")}</span>
          <MoreHorizontal />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-52">
        <DropdownMenuItem onClick={onShare}>
          <Share className="mr-2 size-4" />
          {t("itemActions.shareItem", { itemName })}
        </DropdownMenuItem>
        <TooltipWrapper content={datasetItemId} side="left">
          <DropdownMenuItem onClick={onCopyId}>
            <Copy className="mr-2 size-4" />
            {t("itemActions.copyItemId", { itemName })}
          </DropdownMenuItem>
        </TooltipWrapper>
        <DropdownMenuSeparator />
        <DropdownMenuItem onClick={onDelete}>
          <Trash className="mr-2 size-4" />
          {t("itemActions.deleteItem", { itemName })}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
};

export default DatasetItemActionsDropdown;
