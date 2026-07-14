import i18next from "i18next";
import { modifierKey, isMac } from "@/lib/utils";

export enum SME_ACTION {
  PREVIOUS = "previous",
  NEXT = "next",
  DONE = "done",
  FOCUS_COMMENT = "focus_comment",
  BLUR_COMMENT = "blur_comment",
  FOCUS_FEEDBACK_SCORES = "focus_feedback_scores",
}

export const SME_HOTKEYS = {
  [SME_ACTION.PREVIOUS]: {
    key: "p",
    display: "P",
    description: i18next.t("common:smeFlow.hotkeys.goToPreviousItem"),
  },
  [SME_ACTION.NEXT]: {
    key: "n",
    display: "N",
    description: i18next.t("common:smeFlow.hotkeys.goToNextItem"),
  },
  [SME_ACTION.DONE]: {
    key: `${modifierKey}+enter`,
    display: isMac ? "⌘+⏎" : "Ctrl+⏎",
    description: i18next.t("common:smeFlow.hotkeys.submitAndContinue"),
  },
  [SME_ACTION.FOCUS_COMMENT]: {
    key: "c",
    display: "C",
    description: i18next.t("common:smeFlow.hotkeys.focusCommentTextarea"),
  },
  [SME_ACTION.BLUR_COMMENT]: {
    key: "escape",
    display: "Esc",
    description: i18next.t("common:smeFlow.hotkeys.blurCommentTextarea"),
  },
  [SME_ACTION.FOCUS_FEEDBACK_SCORES]: {
    key: "f",
    display: "F",
    description: i18next.t(
      "common:smeFlow.hotkeys.focusFirstFeedbackScoreInput",
    ),
  },
} as const;
