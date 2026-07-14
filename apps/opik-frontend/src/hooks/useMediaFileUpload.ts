import { useRef } from "react";
import i18next from "i18next";
import { useToast } from "@/ui/use-toast";
import {
  convertFileToBase64,
  validateFileCount,
  validateFileSize,
} from "@/lib/file";

interface UseMediaFileUploadProps {
  currentItemsCount: number;
  maxItems: number;
  maxSizeMB: number;
  onFilesConverted: (base64Items: string[]) => void;
}

export const useMediaFileUpload = ({
  currentItemsCount,
  maxItems,
  maxSizeMB,
  onFilesConverted,
}: UseMediaFileUploadProps) => {
  const { toast } = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleFileSelect = async (
    event: React.ChangeEvent<HTMLInputElement>,
  ) => {
    const files = event.target.files;
    if (!files || files.length === 0) return;

    const fileList = Array.from(files);

    // Validate file count
    const countValidation = validateFileCount(
      currentItemsCount,
      fileList.length,
      maxItems,
    );

    if (!countValidation.valid) {
      toast({
        title: i18next.t("common.messages.maxLimitReached"),
        description: countValidation.error,
        variant: "destructive",
      });
      return;
    }

    // Validate file size
    const sizeValidation = validateFileSize(fileList, maxSizeMB);

    if (!sizeValidation.valid) {
      toast({
        title: i18next.t("common.hooks.useMediaFileUpload.fileTooLarge"),
        description: sizeValidation.error,
        variant: "destructive",
      });
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
      return;
    }

    // Convert files to base64
    try {
      const base64Items = await Promise.all(
        fileList.map((file) => convertFileToBase64(file)),
      );

      onFilesConverted(base64Items);

      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    } catch (error) {
      toast({
        title: i18next.t("common.hooks.useMediaFileUpload.error"),
        description: i18next.t(
          "common.hooks.useMediaFileUpload.failedToProcessFiles",
        ),
        variant: "destructive",
      });
      console.error("File conversion error:", error);
      if (fileInputRef.current) {
        fileInputRef.current.value = "";
      }
    }
  };

  return {
    fileInputRef,
    handleFileSelect,
  };
};
