import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Trash } from "lucide-react";

import { Button } from "@/ui/button";
import { Trace, Thread } from "@/types/traces";
import useAnnotationQueueDeleteItemsMutation from "@/api/annotation-queues/useAnnotationQueueDeleteItemsMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";

type QueueItemActionsPanelProps = {
  items: (Trace | Thread)[];
  annotationQueueId?: string;
};

const QueueItemActionsPanel: React.FunctionComponent<
  QueueItemActionsPanelProps
> = ({ items, annotationQueueId }) => {
  const { t } = useTranslation("pages/annotation-queue");
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !items?.length || !annotationQueueId;

  const { mutate } = useAnnotationQueueDeleteItemsMutation();

  const deleteItemsHandler = useCallback(() => {
    if (annotationQueueId) {
      mutate({
        annotationQueueId,
        ids: items.map(getAnnotationQueueItemId),
      });
    }
  }, [items, annotationQueueId, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteItemsHandler}
        title={t("annotationQueue.queueItemActions.removeFromQueue")}
        description={t("annotationQueue.queueItemActions.removeDescription")}
        confirmText={t("annotationQueue.queueItemActions.removeItems")}
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content={t("annotationQueue.queueItemActions.removeSelectedTooltip")}>
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default QueueItemActionsPanel;
