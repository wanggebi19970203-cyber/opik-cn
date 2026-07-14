import React, { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Trash } from "lucide-react";

import { Button } from "@/ui/button";
import { FeedbackDefinition } from "@/types/feedback-definitions";
import useFeedbackDefinitionBatchDeleteMutation from "@/api/feedback-definitions/useFeedbackDefinitionBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

type FeedbackDefinitionsActionsPanelsProps = {
  feedbackDefinitions: FeedbackDefinition[];
};

const FeedbackDefinitionsActionsPanel: React.FunctionComponent<
  FeedbackDefinitionsActionsPanelsProps
> = ({ feedbackDefinitions }) => {
  const { t } = useTranslation();
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !feedbackDefinitions?.length;

  const { mutate } = useFeedbackDefinitionBatchDeleteMutation();

  const deleteFeedbackDefinitionsHandler = useCallback(() => {
    mutate({
      ids: feedbackDefinitions.map((f) => f.id),
    });
  }, [feedbackDefinitions, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteFeedbackDefinitionsHandler}
        title={t("settings.feedback.confirmDialog.deleteBatch.title")}
        description={t(
          "settings.feedback.confirmDialog.deleteBatch.description",
        )}
        confirmText={t(
          "settings.feedback.confirmDialog.deleteBatch.confirmText",
        )}
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

export default FeedbackDefinitionsActionsPanel;
