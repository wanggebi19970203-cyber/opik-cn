import React, { useState } from "react";
import { Plus, Tag } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button, ButtonProps } from "@/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Input } from "@/ui/input";
import { useToast } from "@/ui/use-toast";
import RemovableTag from "@/shared/RemovableTag/RemovableTag";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";
import { TagProps } from "@/ui/tag";

type TagListSize = "md" | "sm";

type TagSizeConfig = {
  iconClassName: string;
  tagSize: TagProps["size"];
  addButtonSize: ButtonProps["size"];
  addButtonClassName?: string;
  rowMinHeight: string;
};

const TAG_SIZE_CONFIG: Record<TagListSize, TagSizeConfig> = {
  md: {
    iconClassName: "mx-1 size-3.5",
    tagSize: "md",
    addButtonSize: "icon-2xs",
    rowMinHeight: "min-h-6",
  },
  sm: {
    iconClassName: "mx-1 size-3",
    tagSize: "default",
    addButtonSize: "icon-2xs",
    addButtonClassName: "size-5",
    rowMinHeight: "min-h-7",
  },
};

export type TagListRendererProps = {
  tags: string[];
  immutableTags?: string[];
  onAddTag: (tag: string) => void;
  onDeleteTag: (tag: string) => void;
  align?: "start" | "end";
  size?: TagListSize;
  className?: string;
  tooltipText?: string;
  placeholderText?: string;
  addButtonText?: string;
  tagType?: string; // For error messages (e.g., "tag", "version tag")
  readOnly?: boolean;
  tagVariant?: TagProps["variant"];
};

const TagListRenderer: React.FC<TagListRendererProps> = ({
  tags = [],
  immutableTags = [],
  onAddTag,
  onDeleteTag,
  align = "end",
  size = "md",
  className,
  tooltipText,
  placeholderText,
  addButtonText,
  readOnly = false,
  tagVariant,
}) => {
  const { t } = useTranslation();
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [newTag, setNewTag] = useState<string>("");

  const hasTags = tags.length > 0 || immutableTags.length > 0;

  const tagSizeConfig = TAG_SIZE_CONFIG[size];

  const effectiveTooltipText = tooltipText ?? t("common.tags.tagsList");
  const effectivePlaceholderText = placeholderText ?? t("common.tags.newTag");
  const effectiveAddButtonText = addButtonText ?? t("common.tags.addTag");

  const isImmutableTag = (tag: string): boolean =>
    immutableTags.some((t) => t.toLowerCase() === tag.toLowerCase());

  const handleAddTag = () => {
    if (!newTag) return;

    if (tags.includes(newTag) || isImmutableTag(newTag)) {
      toast({
        title: t("common.labels.error"),
        description: t("common.tags.tagAlreadyExistsDescription", {
          tag: newTag,
        }),
        variant: "destructive",
      });
      return;
    }

    onAddTag(newTag);
    setNewTag("");
    setOpen(false);
  };

  if (readOnly && !hasTags) {
    return null;
  }

  return (
    <div
      className={cn(
        "flex w-full flex-wrap items-center gap-2 overflow-x-hidden",
        tagSizeConfig.rowMinHeight,
        className,
      )}
    >
      <TooltipWrapper content={effectiveTooltipText}>
        <Tag className={`${tagSizeConfig.iconClassName} text-muted-slate`} />
      </TooltipWrapper>
      {[...immutableTags].sort().map((tag) => (
        <RemovableTag
          label={tag}
          key={`immutable-${tag}`}
          size={tagSizeConfig.tagSize}
          variant={tagVariant}
        />
      ))}
      {[...tags].sort().map((tag) => (
        <RemovableTag
          label={tag}
          key={tag}
          size={tagSizeConfig.tagSize}
          variant={tagVariant}
          onDelete={readOnly ? undefined : () => onDeleteTag(tag)}
        />
      ))}
      {!readOnly && (
        <Popover onOpenChange={setOpen} open={open}>
          <PopoverTrigger asChild>
            <Button
              data-testid="add-tag-button"
              variant="outline"
              size={tagSizeConfig.addButtonSize}
              className={cn(tagSizeConfig.addButtonClassName)}
            >
              <Plus />
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-[420px] p-6" align={align}>
            <div className="flex gap-2">
              <Input
                placeholder={effectivePlaceholderText}
                value={newTag}
                onChange={(event) => setNewTag(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    handleAddTag();
                  }
                }}
              />
              <Button variant="default" onClick={handleAddTag}>
                {effectiveAddButtonText}
              </Button>
            </div>
          </PopoverContent>
        </Popover>
      )}
    </div>
  );
};

export default TagListRenderer;
