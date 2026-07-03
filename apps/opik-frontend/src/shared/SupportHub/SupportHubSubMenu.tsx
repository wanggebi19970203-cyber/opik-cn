import {
  Book,
  GraduationCap,
  LifeBuoy,
  MessageCircleQuestion,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import SlackIcon from "@/icons/slack.svg?react";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuPortal,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { buildDocsUrl } from "@/lib/utils";
import { useLayoutDialog } from "@/hooks/useLayoutDialog";

export const SLACK_LINK = "http://chat.comet.com";

interface SupportHubSubMenuProps {
  variant?: "submenu" | "dropdown";
  expanded?: boolean;
}

const SupportHubSubMenu: React.FC<SupportHubSubMenuProps> = ({
  variant = "submenu",
  expanded = true,
}) => {
  const { t } = useTranslation();
  const { open: openQuickstart } = useLayoutDialog("quickstart");
  const { open: openFeedback } = useLayoutDialog("feedback");

  const menuItems = (
    <>
      <DropdownMenuItem onClick={openQuickstart} className="cursor-pointer">
        <GraduationCap className="mr-2 size-4" />
        <span>{t("common.table.quickstartGuide")}</span>
      </DropdownMenuItem>
      <DropdownMenuItem asChild>
        <a
          href={buildDocsUrl()}
          target="_blank"
          rel="noreferrer"
          className="flex cursor-pointer items-center gap-2"
        >
          <Book className="size-4" />
          <span>{t("common.supportHub.docs")}</span>
        </a>
      </DropdownMenuItem>
      <DropdownMenuSeparator />
      <DropdownMenuItem asChild>
        <a
          href={SLACK_LINK}
          target="_blank"
          rel="noreferrer"
          className="flex cursor-pointer items-center gap-2"
        >
          <SlackIcon className="size-4" />
          <span>{t("common.supportHub.getHelpOnSlack")}</span>
        </a>
      </DropdownMenuItem>
      <DropdownMenuItem onClick={openFeedback} className="cursor-pointer">
        <MessageCircleQuestion className="mr-2 size-4" />
        <span>{t("common.feedback.provideFeedback")}</span>
      </DropdownMenuItem>
    </>
  );

  if (variant === "submenu") {
    return (
      <DropdownMenuSub>
        <DropdownMenuSubTrigger className="cursor-pointer">
          <LifeBuoy className="mr-2 size-4" />
          <span>{t("common.supportHub.supportHub")}</span>
        </DropdownMenuSubTrigger>
        <DropdownMenuPortal>
          <DropdownMenuSubContent className="w-56">
            {menuItems}
          </DropdownMenuSubContent>
        </DropdownMenuPortal>
      </DropdownMenuSub>
    );
  }

  const trigger = expanded ? (
    <button className="comet-body-s flex h-8 w-full items-center gap-2 rounded-md px-2.5 text-muted-slate hover:bg-primary-foreground">
      <LifeBuoy className="size-4 shrink-0" />
      <span className="grow truncate text-left">{t("common.supportHub.supportHub")}</span>
    </button>
  ) : (
    <button className="comet-body-s flex size-8 items-center justify-center rounded-md text-muted-slate hover:bg-primary-foreground">
      <LifeBuoy className="size-4 shrink-0" />
    </button>
  );

  return (
    <li className="flex">
      <DropdownMenu>
        {expanded ? (
          <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
        ) : (
          <TooltipWrapper content={t("common.supportHub.supportHub")} side="right">
            <DropdownMenuTrigger asChild>{trigger}</DropdownMenuTrigger>
          </TooltipWrapper>
        )}
        <DropdownMenuContent side="right" align="end" className="w-56">
          {menuItems}
        </DropdownMenuContent>
      </DropdownMenu>
    </li>
  );
};

export default SupportHubSubMenu;
