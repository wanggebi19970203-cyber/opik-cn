import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { Button } from "@/ui/button";
import { MoreHorizontal, Trash } from "lucide-react";
import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import useAnnotationQueueDeleteItemsMutation from "@/api/annotation-queues/useAnnotationQueueDeleteItemsMutation";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
import { CellContext } from "@tanstack/react-table";
import { Thread, Trace } from "@/types/traces";

type CustomMeta = {
  annotationQueueId: string;
};

const QueueItemRowActionsCell: React.FC<
  CellContext<Thread | Trace, unknown>
> = (context) => {
  const { t } = useTranslation("pages/annotation-queue");
  const { custom } = context.column.columnDef.meta ?? {};
  const { annotationQueueId } = (custom ?? {}) as CustomMeta;

  const resetKeyRef = useRef(0);
  const item = context.row.original;
  const [open, setOpen] = useState<boolean | number>(false);

  const { mutate } = useAnnotationQueueDeleteItemsMutation();

  const deleteItemHandler = useCallback(() => {
    if (annotationQueueId) {
      mutate({
        annotationQueueId,
        ids: [getAnnotationQueueItemId(item)],
      });
    }
  }, [annotationQueueId, mutate, item]);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="justify-end p-0"
      stopClickPropagation
    >
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open === 1}
        setOpen={setOpen}
        onConfirm={deleteItemHandler}
        title={t("annotationQueue.queueItemActions.removeFromQueue")}
        description={t("annotationQueue.queueItemActions.removeDescription")}
        confirmText={t("annotationQueue.queueItemActions.removeItem")}
        confirmButtonVariant="destructive"
      />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="minimal" size="icon" className="-mr-2.5 ">
            <span className="sr-only">{t("annotationQueue.queueItemActions.actionsMenu")}</span>
            <MoreHorizontal className="size-4" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-52">
          <DropdownMenuItem
            onClick={() => {
              setOpen(1);
              resetKeyRef.current = resetKeyRef.current + 1;
            }}
            variant="destructive"
          >
            <Trash className="mr-2 size-4" />
            {t("annotationQueue.queueItemActions.removeFromQueue")}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </CellWrapper>
  );
};

export default QueueItemRowActionsCell;
