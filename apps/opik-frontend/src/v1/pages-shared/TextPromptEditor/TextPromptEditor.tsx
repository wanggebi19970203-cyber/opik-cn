import React, { useState } from "react";
import { useTranslation } from "react-i18next";
import { Eye, FileText } from "lucide-react";
import { Button } from "@/ui/button";
import { Label } from "@/ui/label";
import { Textarea } from "@/ui/textarea";
import { Description } from "@/ui/description";
import MarkdownPreview from "@/shared/MarkdownPreview/MarkdownPreview";
import MediaTagsList from "@/v1/pages-shared/llm/PromptMessageMediaTags/MediaTagsList";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v1/constants/explainers";

interface TextPromptEditorProps {
  value: string;
  onChange: (value: string) => void;
  label?: string;
  labelClassName?: string;
  placeholder?: string;
  showDescription?: boolean;
  currentImages?: string[];
  currentVideos?: string[];
  currentAudios?: string[];
}

const TextPromptEditor: React.FC<TextPromptEditorProps> = ({
  value,
  onChange,
  label,
  labelClassName,
  placeholder,
  showDescription = true,
  currentImages = [],
  currentVideos = [],
  currentAudios = [],
}) => {
  const { t } = useTranslation();
  const [showPrettyView, setShowPrettyView] = useState(false);

  const effectiveLabel = label ?? t("textPromptEditor.prompt");
  const effectivePlaceholder = placeholder ?? t("textPromptEditor.prompt");

  return (
    <div className="flex flex-col gap-2 pb-4">
      <div className="flex items-center justify-between gap-0.5">
        <Label htmlFor="template" className={labelClassName}>
          {effectiveLabel}
        </Label>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => setShowPrettyView(!showPrettyView)}
        >
          {showPrettyView ? (
            <>
              <FileText className="mr-1.5 size-3.5" />
              {t("textPromptEditor.editView")}
            </>
          ) : (
            <>
              <Eye className="mr-1.5 size-3.5" />
              {t("textPromptEditor.prettyView")}
            </>
          )}
        </Button>
      </div>
      {showPrettyView ? (
        <div className="min-h-44 rounded-md border border-border bg-primary-foreground p-3">
          <MarkdownPreview>{value}</MarkdownPreview>
        </div>
      ) : (
        <>
          <Textarea
            id="template"
            className="comet-code"
            placeholder={effectivePlaceholder}
            value={value}
            onChange={(event) => onChange(event.target.value)}
          />
          {showDescription && (
            <Description>
              {
                EXPLAINERS_MAP[EXPLAINER_ID.what_format_should_the_prompt_be]
                  .description
              }
            </Description>
          )}
        </>
      )}
      {!showPrettyView && currentImages.length > 0 && (
        <div className="flex flex-col gap-2">
          <Label>{t("textPromptEditor.images")}</Label>
          <MediaTagsList
            type="image"
            items={currentImages}
            editable={false}
            preview={true}
          />
        </div>
      )}
      {!showPrettyView && currentVideos.length > 0 && (
        <div className="flex flex-col gap-2">
          <Label>{t("textPromptEditor.videos")}</Label>
          <MediaTagsList
            type="video"
            items={currentVideos}
            editable={false}
            preview={true}
          />
        </div>
      )}
      {!showPrettyView && currentAudios.length > 0 && (
        <div className="flex flex-col gap-2">
          <Label>{t("textPromptEditor.audios")}</Label>
          <MediaTagsList
            type="audio"
            items={currentAudios}
            editable={false}
            preview={true}
          />
        </div>
      )}
    </div>
  );
};

export default TextPromptEditor;
