import React, { useCallback, useRef, useState } from "react";
import { Trash } from "lucide-react";
import { useTranslation } from "react-i18next";

import { Button } from "@/ui/button";
import useRulesBatchDeleteMutation from "@/api/automations/useRulesBatchDeleteMutation";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { EvaluatorsRule } from "@/types/automations";

type RulesActionsPanelsProps = {
  rules: EvaluatorsRule[];
};

const RulesActionsPanel: React.FunctionComponent<RulesActionsPanelsProps> = ({
  rules,
}) => {
  const { t } = useTranslation("online-evaluation");
  const resetKeyRef = useRef(0);
  const [open, setOpen] = useState<boolean>(false);
  const disabled = !rules?.length;

  const { mutate } = useRulesBatchDeleteMutation();

  const deleteRulesHandler = useCallback(() => {
    mutate({
      ids: rules.map((p) => p.id),
    });
  }, [rules, mutate]);

  return (
    <div className="flex items-center gap-2">
      <ConfirmDialog
        key={`delete-${resetKeyRef.current}`}
        open={open}
        setOpen={setOpen}
        onConfirm={deleteRulesHandler}
        title={t("rulesActionsPanel.deleteTitle")}
        description={t("rulesActionsPanel.deleteDescription")}
        confirmText={t("rulesActionsPanel.deleteConfirmText")}
        confirmButtonVariant="destructive"
      />
      <TooltipWrapper content={t("rulesActionsPanel.deleteTooltip")}>
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

export default RulesActionsPanel;
