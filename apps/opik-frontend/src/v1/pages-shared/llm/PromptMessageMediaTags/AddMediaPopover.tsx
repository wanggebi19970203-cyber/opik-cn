import React, { useMemo, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { Input } from "@/ui/input";
import { useToast } from "@/ui/use-toast";
import PromptVariablesList from "@/v1/pages-shared/llm/PromptVariablesList/PromptVariablesList";

export type MediaType = "image" | "video" | "audio";

export interface AddMediaPopoverProps {
  type: MediaType;
  items: string[];
  setItems: (newItems: string[]) => void;
  maxItems?: number;
  align?: "start" | "end";
  onOpenChange?: (open: boolean) => void;
  promptVariables?: string[];
  children: React.ReactNode;
}

const DEFAULT_MAX_ITEMS: Record<MediaType, number> = {
  image: Infinity,
  video: Infinity,
  audio: Infinity,
};

const isHttpUrl = (value: string): boolean => {
  try {
    const url = new URL(value);
    return url.protocol === "http:" || url.protocol === "https:";
  } catch {
    return false;
  }
};

const validateMediaUrl = (url: string): { valid: boolean; errorKey?: string } => {
  if (!isHttpUrl(url)) {
    // Allow template variables like {{image}} or {{video}}, {{audio}}
    if (url.match(/^\{\{.+\}\}$/)) {
      return { valid: true };
    }
    return { valid: false, errorKey: "addMediaPopover.invalidUrlError" };
  }
  return { valid: true };
};

const AddMediaPopover: React.FC<AddMediaPopoverProps> = ({
  type,
  items,
  setItems,
  maxItems,
  align = "start",
  onOpenChange,
  promptVariables = [],
  children,
}) => {
  const { t } = useTranslation("prompt");
  const { t: tCommon } = useTranslation("common");
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [newItem, setNewItem] = useState<string>("");
  const inputRef = useRef<HTMLInputElement>(null);

  const resolvedMaxItems = maxItems ?? DEFAULT_MAX_ITEMS[type];

  const title = useMemo(() => {
    if (type === "image") {
      return t("addMediaPopover.addImage");
    }
    if (type === "video") {
      return t("addMediaPopover.addVideo");
    }
    return t("addMediaPopover.addAudio");
  }, [type, t]);

  const placeholder = useMemo(() => {
    if (type === "image") {
      return t("addMediaPopover.enterImageUrl");
    }
    if (type === "video") {
      return t("addMediaPopover.enterVideoUrl");
    }
    return t("addMediaPopover.enterAudioUrl");
  }, [type, t]);

  const handleOpenChange = (isOpen: boolean) => {
    setOpen(isOpen);
    onOpenChange?.(isOpen);

    if (!isOpen) {
      setNewItem("");
    }
  };

  const handleVariableClick = (variable: string) => {
    const variableText = `{{${variable}}}`;
    setNewItem(variableText);
    inputRef.current?.focus();
  };

  const handleAddItem = () => {
    const trimmed = newItem.trim();
    if (!trimmed) return;

    if (items.length >= resolvedMaxItems) {
      const typeLabel =
        type === "image" ? tCommon("media.images") : type === "video" ? tCommon("media.videos") : tCommon("media.audios");
      toast({
        title: t("addMediaPopover.maximumLimitReached"),
        description: t("addMediaPopover.maximumLimitDescription", { count: resolvedMaxItems, type: typeLabel }),
        variant: "destructive",
      });
      return;
    }

    if (items.includes(trimmed)) {
      toast({
        title: tCommon("providers.error"),
        description: t("addMediaPopover.alreadyExists", { type }),
        variant: "destructive",
      });
      return;
    }

    const validation = validateMediaUrl(trimmed);
    if (!validation.valid) {
      toast({
        title: tCommon("providers.invalidUrl"),
        description: validation.errorKey ? t(validation.errorKey) : undefined,
        variant: "destructive",
      });
      return;
    }

    setItems([...items, trimmed]);
    setNewItem("");
    handleOpenChange(false);
  };

  return (
    <Popover onOpenChange={handleOpenChange} open={open}>
      <PopoverTrigger asChild>
        <div>{children}</div>
      </PopoverTrigger>
      <PopoverContent className="w-[460px] p-6" align={align}>
        <div className="space-y-3">
          <h3 className="comet-body-s-accented">{title}</h3>
          <div className="flex gap-2">
            <div className="relative flex-1">
              <Input
                ref={inputRef}
                placeholder={placeholder}
                value={newItem}
                onChange={(e) => setNewItem(e.target.value)}
                onClick={(e) => e.stopPropagation()}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    handleAddItem();
                  }
                }}
              />
            </div>
            <Button type="button" variant="default" onClick={handleAddItem}>
              {tCommon("media.add")}
            </Button>
          </div>
          {promptVariables.length > 0 && (
            <p className="comet-body-xs text-light-slate">
              {t("addMediaPopover.availableVariables")}{" "}
              <PromptVariablesList
                variables={promptVariables}
                onVariableClick={handleVariableClick}
              />
            </p>
          )}
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default AddMediaPopover;
