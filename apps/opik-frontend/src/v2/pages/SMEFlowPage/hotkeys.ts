import type { TFunction } from "i18next";
import { modifierKey, isMac } from "@/lib/utils";

export enum SME_ACTION {
  NEXT_DEFAULT = "next_default",
  FOCUS_COMMENT = "focus_comment",
  BLUR_COMMENT = "blur_comment",
  FOCUS_FEEDBACK_SCORES = "focus_feedback_scores",
}

export const getSMEHotkeys = (t: TFunction) =>
  ({
    [SME_ACTION.NEXT_DEFAULT]: {
      key: `${modifierKey}+enter`,
      display: isMac ? "⌘+⏎" : "Ctrl+⏎",
      description: t("common:smeFlow.hotkeys.goToNextItemToReview"),
    },
    [SME_ACTION.FOCUS_COMMENT]: {
      key: "c",
      display: "C",
      description: t("common:smeFlow.hotkeys.focusCommentTextarea"),
    },
    [SME_ACTION.BLUR_COMMENT]: {
      key: "escape",
      display: "Esc",
      description: t("common:smeFlow.hotkeys.blurCommentTextarea"),
    },
    [SME_ACTION.FOCUS_FEEDBACK_SCORES]: {
      key: "f",
      display: "F",
      description: t("common:smeFlow.hotkeys.focusFirstFeedbackScoreInput"),
    },
  }) as const;
