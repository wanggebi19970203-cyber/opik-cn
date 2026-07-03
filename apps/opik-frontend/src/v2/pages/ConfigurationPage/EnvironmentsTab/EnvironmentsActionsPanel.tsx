import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/ui/button";
import { Environment } from "@/types/environments";
import useEnvironmentBatchDeleteMutation from "@/api/environments/useEnvironmentBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type EnvironmentsActionsPanelProps = {
  environments: Environment[];
};

const EnvironmentsActionsPanel: React.FunctionComponent<
  EnvironmentsActionsPanelProps
> = ({ environments }) => {
  const { t } = useTranslation("pages/settings");
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !environments?.length;

  const { mutate } = useEnvironmentBatchDeleteMutation();

  const deleteEnvironmentsHandler = useCallback(() => {
    mutate({ ids: environments.map((e) => e.id) });
  }, [environments, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteEnvironmentsHandler}
        title={t("settings.environments.confirmDialog.deleteBatch.title")}
        description={t("settings.environments.confirmDialog.deleteBatch.description")}
        confirmText={t("settings.environments.confirmDialog.deleteBatch.confirmText")}
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content={t("settings.actions.delete")}>
        <Button
          variant="outline"
          size="icon-sm"
          onClick={() => {
            setOpen(true);
            resetKeyRef.current = resetKeyRef.current + 1;
          }}
          disabled={disabled}
        >
          <Trash className="size-4" />
        </Button>
      </TooltipWrapper>
    </div>
  );
};

export default EnvironmentsActionsPanel;
