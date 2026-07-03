import React, { useRef, useState } from "react";
import { ArrowRight, GitCompare, Pencil, Undo2 } from "lucide-react";
import { useTranslation } from "react-i18next";

import { ConfigHistoryItem } from "@/types/agent-configs";
import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Tag } from "@/ui/tag";
import { Textarea } from "@/ui/textarea";
import AgentConfigurationEditView, {
  AgentConfigurationEditViewHandle,
  AgentConfigurationEditViewState,
} from "./AgentConfigurationEditView";
import ExpandAllToggle from "./fields/ExpandAllToggle";
import { useFieldsCollapse } from "./fields/useFieldsCollapse";

type AgentConfigurationEditPanelProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  item: ConfigHistoryItem;
  projectId: string;
  onSaved: (savedVersionName?: string) => void;
};

const AgentConfigurationEditPanel: React.FC<
  AgentConfigurationEditPanelProps
> = ({ open, onOpenChange, item, projectId, onSaved }) => {
  const { t } = useTranslation("agent-optimization");
  const viewRef = useRef<AgentConfigurationEditViewHandle>(null);
  const [view, setView] = useState<"edit" | "diff">("edit");
  const [description, setDescription] = useState("");
  const [state, setState] = useState<AgentConfigurationEditViewState>({
    isDirty: false,
    isSaving: false,
    hasErrors: false,
    isEmpty: false,
    collapsibleKeys: [],
    hasExpandableFields: false,
  });

  const controller = useFieldsCollapse({
    collapsibleKeys: state.collapsibleKeys,
  });

  const handleSavedInternal = (savedName?: string) => {
    onSaved(savedName);
    onOpenChange(false);
  };

  const handleSave = async () => {
    await viewRef.current?.save();
  };

  const handleClose = () => {
    if (state.isSaving) return;
    onOpenChange(false);
    setView("edit");
  };

  const title = t("agentOptimization.editPanel.newConfiguration");

  return (
    <Sheet open={open} onOpenChange={(o) => !o && handleClose()}>
      <SheetContent
        side="right"
        className="flex w-full max-w-none flex-col p-0 sm:max-w-[872px]"
        blockOverlayClose={state.isDirty}
        header={
          <SheetTopBar variant="form" title={title}>
            <Tag variant="gray" className="flex items-center gap-1 px-1.5 py-1">
              <Pencil className="size-3" />
              {t("agentOptimization.editPanel.fromVersion", { name: item.name })}
            </Tag>
          </SheetTopBar>
        }
      >
        <div className="min-h-0 flex-1 overflow-y-auto px-6">
          {view === "edit" && (
            <Textarea
              placeholder={t("agentOptimization.editPanel.addVersionNotes")}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              className="mb-4 min-h-[55px]"
            />
          )}
          <div className="mb-4 flex items-center justify-between">
            <div className="flex items-center gap-2">
              <h3 className="comet-body-accented flex items-center gap-1">
                {view === "diff" ? (
                  <>
                    {t("agentOptimization.editPanel.compareWith", { name: item.name })}
                    <ArrowRight className="size-3.5" />
                    {t("agentOptimization.editPanel.currentChanges")}
                  </>
                ) : (
                  t("agentOptimization.editPanel.editFields")
                )}
              </h3>
              <Button
                variant="outline"
                size="2xs"
                onClick={() => setView((v) => (v === "edit" ? "diff" : "edit"))}
                disabled={!state.isDirty && view === "edit"}
              >
                {view === "edit" ? (
                  <>
                    <GitCompare className="mr-1 size-3" />
                    {t("agentOptimization.editPanel.showDiff")}
                  </>
                ) : (
                  <>
                    <Undo2 className="mr-1 size-3" />
                    {t("agentOptimization.editPanel.backToEdit")}
                  </>
                )}
              </Button>
            </div>
            {view === "edit" && state.hasExpandableFields && (
              <ExpandAllToggle controller={controller} />
            )}
          </div>

          <AgentConfigurationEditView
            ref={viewRef}
            item={item}
            projectId={projectId}
            onSaved={handleSavedInternal}
            view={view}
            description={description}
            onDescriptionChange={setDescription}
            controller={controller}
            onStateChange={setState}
            blockNavigation
          />
        </div>

        <div className="flex items-center justify-end gap-2 border-t px-6 py-3">
          <Button
            variant="outline"
            size="sm"
            onClick={handleClose}
            disabled={state.isSaving}
          >
            {t("agentOptimization.editPanel.cancel")}
          </Button>
          <Button
            size="sm"
            onClick={handleSave}
            disabled={
              state.isSaving ||
              state.hasErrors ||
              !state.isDirty ||
              state.isEmpty
            }
          >
            {state.isSaving ? t("agentOptimization.editPanel.saving") : t("agentOptimization.editPanel.saveNewVersion")}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  );
};

export default AgentConfigurationEditPanel;
