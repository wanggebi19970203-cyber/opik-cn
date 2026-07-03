import React, { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import uniqBy from "lodash/uniqBy";
import { UnifiedMediaItem } from "@/hooks/useUnifiedMedia";
import {
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/ui/accordion";
import AttachmentThumbnail from "@/shared/attachments/AttachmentThumbnail/AttachmentThumbnail";
import AttachmentPreviewDialog from "@/shared/attachments/AttachmentPreviewDialog/AttachmentPreviewDialog";
import { ATTACHMENT_TYPE, AttachmentPreviewData } from "@/types/attachments";
import { ATTACHMENT_ORDER_MAP } from "@/constants/attachments";

type AttachmentsListProps = {
  media: UnifiedMediaItem[];
};

const AttachmentsList: React.FC<AttachmentsListProps> = ({ media }) => {
  const { t } = useTranslation("tracing");
  const [previewData, setPreviewData] = useState<AttachmentPreviewData | null>(
    null,
  );

  // Deduplicate by URL and sort media by type for consistent display
  const previewDataArray = useMemo(() => {
    return uniqBy(media, "url")
      .map((item) => ({
        name: item.name,
        url: item.url,
        type: item.type,
      }))
      .sort(
        (a, b) => ATTACHMENT_ORDER_MAP[a.type] - ATTACHMENT_ORDER_MAP[b.type],
      );
  }, [media]);

  const hasAttachments = previewDataArray.length > 0;
  return hasAttachments ? (
    <AccordionItem value="attachments">
      <AccordionTrigger>{t("detailsTab.attachments")}</AccordionTrigger>
      <AccordionContent>
        <div className="flex flex-wrap gap-2">
          {previewDataArray.map((data) => (
            <AttachmentThumbnail
              key={data.url}
              previewData={data}
              onExpand={setPreviewData}
            />
          ))}
        </div>
        <AttachmentPreviewDialog
          open={Boolean(previewData)}
          setOpen={() => setPreviewData(null)}
          type={previewData?.type ?? ATTACHMENT_TYPE.IMAGE}
          name={previewData?.name ?? ""}
          url={previewData?.url ?? ""}
        ></AttachmentPreviewDialog>
      </AccordionContent>
    </AccordionItem>
  ) : null;
};

export default AttachmentsList;
