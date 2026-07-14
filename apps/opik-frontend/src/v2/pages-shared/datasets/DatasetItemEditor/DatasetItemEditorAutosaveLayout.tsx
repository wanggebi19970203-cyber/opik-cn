import React, { useCallback, useMemo } from "react";
import { useTranslation } from "react-i18next";
import copy from "clipboard-copy";
import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import ResizableSidePanelTopBar from "@/shared/ResizableSidePanel/ResizableSidePanelTopBar";
import ResizableSidePanelArrowNavigation from "@/shared/ResizableSidePanel/ResizableSidePanelArrowNavigation";
import { useToast } from "@/ui/use-toast";
import Loader from "@/shared/Loader/Loader";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import { processInputData } from "@/lib/images";
import ImagesListWrapper from "@/shared/attachments/ImagesListWrapper/ImagesListWrapper";
import { useDatasetItemEditorAutosaveContext } from "./DatasetItemEditorAutosaveContext";
import DatasetItemEditorForm from "./DatasetItemEditorForm";
import DatasetItemActionsDropdown from "@/v2/pages-shared/datasets/DatasetItemActionsDropdown/DatasetItemActionsDropdown";

interface DatasetItemEditorAutosaveLayoutProps {
  datasetItemId: string;
  isOpen: boolean;
  onClose: () => void;
}

const truncateId = (id: string): string => {
  if (id.length <= 12) return id;
  return `${id.slice(0, 4)}...${id.slice(-4)}`;
};

const DatasetItemEditorAutosaveLayout: React.FC<
  DatasetItemEditorAutosaveLayoutProps
> = ({ datasetItemId, isOpen, onClose }) => {
  const { t } = useTranslation("datasets");
  const {
    isPending,
    handleFieldChange,
    handleDelete,
    tags,
    handleAddTag,
    handleDeleteTag,
    fields,
    formId,
    horizontalNavigation,
    datasetItem,
  } = useDatasetItemEditorAutosaveContext();

  const { toast } = useToast();

  const { media } = useMemo(
    () => processInputData(datasetItem?.data),
    [datasetItem?.data],
  );

  const hasMedia = media.length > 0;

  const handleShare = useCallback(() => {
    toast({
      description: t("itemEditor.urlCopiedToClipboard"),
    });
    copy(window.location.href);
  }, [toast, t]);

  const handleCopyId = useCallback(() => {
    toast({
      description: t("itemEditor.recordIdCopiedToClipboard"),
    });
    copy(datasetItemId);
  }, [datasetItemId, toast, t]);

  const handleDeleteItemConfirm = useCallback(() => {
    handleDelete(onClose);
  }, [handleDelete, onClose]);

  const handleClose = useCallback(() => {
    onClose();
  }, [onClose]);

  return (
    <ResizableSidePanel
      panelId="dataset-item-editor"
      open={isOpen}
      header={
        <ResizableSidePanelTopBar
          variant="info"
          title={
            <TooltipWrapper content={datasetItemId}>
              <span>
                {t("itemEditor.record")}{" "}
                <span className="comet-body-s text-muted-slate">
                  {truncateId(datasetItemId)}
                </span>
              </span>
            </TooltipWrapper>
          }
          onClose={handleClose}
        >
          <DatasetItemActionsDropdown
            datasetItemId={datasetItemId}
            itemName={t("itemEditor.recordLowercase")}
            onShare={handleShare}
            onCopyId={handleCopyId}
            onDelete={handleDeleteItemConfirm}
          />
          <ResizableSidePanelArrowNavigation
            horizontalNavigation={horizontalNavigation}
          />
        </ResizableSidePanelTopBar>
      }
      onClose={handleClose}
      horizontalNavigation={horizontalNavigation}
    >
      {isPending ? (
        <div className="flex size-full items-center justify-center">
          <Loader />
        </div>
      ) : (
        <div className="relative size-full overflow-y-auto">
          <div className="sticky top-0 z-10 border-b bg-background px-6 py-4">
            <TagListRenderer
              tags={tags}
              onAddTag={handleAddTag}
              onDeleteTag={handleDeleteTag}
              size="sm"
              align="start"
            />
          </div>
          {hasMedia && (
            <div className="border-b px-6 py-4">
              <div className="mb-2 text-sm font-medium">
                {t("itemEditor.media")}
              </div>
              <ImagesListWrapper media={media} />
            </div>
          )}
          <div className="p-6 pt-4">
            <DatasetItemEditorForm
              key={datasetItemId}
              formId={formId}
              fields={fields}
              onFieldChange={handleFieldChange}
            />
          </div>
        </div>
      )}
    </ResizableSidePanel>
  );
};

export default DatasetItemEditorAutosaveLayout;
