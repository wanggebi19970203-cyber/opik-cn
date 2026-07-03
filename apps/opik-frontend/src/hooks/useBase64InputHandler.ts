import { useState } from "react";
import i18next from "i18next";
import { useToast } from "@/ui/use-toast";
import { isBase64DataUrl, getBase64SizeInMB } from "@/lib/base64";

interface UseBase64InputHandlerProps {
  currentItemsCount: number;
  maxItems: number;
  maxSizeMB: number;
  type: "image" | "video";
  existingItems: string[];
  onBase64Converted: (base64: string) => void;
}

export const useBase64InputHandler = ({
  currentItemsCount,
  maxItems,
  maxSizeMB,
  type,
  existingItems,
  onBase64Converted,
}: UseBase64InputHandlerProps) => {
  const { toast } = useToast();
  const [isProcessing, setIsProcessing] = useState(false);

  const handleBase64Input = (
    value: string,
    callbacks?: {
      onSuccess?: () => void;
      onError?: () => void;
    },
  ) => {
    // Check if the input contains base64 data URL
    if (!isBase64DataUrl(value)) {
      return false; // Not base64, let the caller handle it normally
    }

    // Process base64 asynchronously to avoid blocking the UI
    setIsProcessing(true);

    // Use queueMicrotask to process off the main thread
    queueMicrotask(() => {
      try {
        const trimmed = value.trim();

        // Check if already at max capacity
        if (currentItemsCount >= maxItems) {
          toast({
            title: i18next.t("common.hooks.useBase64InputHandler.maximumLimitReached"),
            description: i18next.t("common.hooks.useBase64InputHandler.maxItemsError", {
              maxItems,
              mediaType: type === "image"
                ? i18next.t("common.media.images")
                : i18next.t("common.media.videos"),
            }),
            variant: "destructive",
          });
          callbacks?.onError?.();
          return;
        }

        // Check for duplicates
        if (existingItems.includes(trimmed)) {
          toast({
            title: i18next.t("common.hooks.useBase64InputHandler.error"),
            description: i18next.t("common.hooks.useBase64InputHandler.duplicateMedia", { type }),
            variant: "destructive",
          });
          callbacks?.onError?.();
          return;
        }

        // Validate size
        const sizeInMB = getBase64SizeInMB(trimmed);

        if (sizeInMB > maxSizeMB) {
          toast({
            title: i18next.t("common.hooks.useBase64InputHandler.fileTooLarge"),
            description: i18next.t("common.hooks.useBase64InputHandler.fileTooLargeDescription", {
              type,
              sizeMB: sizeInMB.toFixed(2),
              maxSizeMB,
            }),
            variant: "destructive",
          });
          callbacks?.onError?.();
          return;
        }

        // Call the callback to add the base64 (same as file upload)
        onBase64Converted(trimmed);
        callbacks?.onSuccess?.();
      } catch (error) {
        toast({
          title: i18next.t("common.hooks.useBase64InputHandler.error"),
          description: i18next.t("common.hooks.useBase64InputHandler.failedToProcessBase64"),
          variant: "destructive",
        });
        callbacks?.onError?.();
      } finally {
        setIsProcessing(false);
      }
    });

    return true; // Base64 detected and being processed
  };

  return {
    isProcessing,
    handleBase64Input,
  };
};
