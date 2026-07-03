import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/ui/button";
import { Dataset } from "@/types/datasets";
import useDatasetBatchDeleteMutation from "@/api/datasets/useDatasetBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type DatasetActionsPanelProps = {
  datasets: Dataset[];
  entityName: string;
};

const DatasetActionsPanel: React.FunctionComponent<
  DatasetActionsPanelProps
> = ({ datasets, entityName }) => {
  const { t } = useTranslation("datasets");
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !datasets?.length;

  const { mutate } = useDatasetBatchDeleteMutation();

  const deleteDatasetsHandler = useCallback(() => {
    mutate({
      ids: datasets.map((d) => d.id),
    });
  }, [datasets, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteDatasetsHandler}
        title={t("rowActions.deleteTitle", { entityName })}
        description={t("rowActions.deleteDescription", { entityName })}
        confirmText={t("rowActions.deleteConfirmText", { entityName })}
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content={t("rowActions.delete")}>
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

export default DatasetActionsPanel;
