import { z } from "zod";
import i18next from "i18next";

export const TextMarkdownWidgetSchema = z.object({
  content: z
    .string({
      required_error: i18next.t("common:validation.markdownContentRequired"),
    })
    .min(1, { message: i18next.t("common:validation.markdownContentRequired") }),
});

export type TextMarkdownWidgetFormData = z.infer<
  typeof TextMarkdownWidgetSchema
>;
