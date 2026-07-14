import React, { useState } from "react";
import { useTranslation } from "react-i18next";

import ResizableSidePanel from "@/shared/ResizableSidePanel/ResizableSidePanel";
import { Button } from "@/ui/button";
import TagListRenderer from "@/shared/TagListRenderer/TagListRenderer";
import { useConfirmAction } from "@/shared/ConfirmDialog/useConfirmAction";
import ConfirmDialog from "@/shared/ConfirmDialog/ConfirmDialog";
import { DATASET_ITEM_SOURCE, DatasetItemColumn } from "@/types/datasets";
import useTestSuiteDraftStore, {
  useAddItem,
} from "@/store/TestSuiteDraftStore";
import { useEffectiveSuiteAssertions } from "@/hooks/useEffectiveSuiteAssertions";
import { useEffectiveExecutionPolicy } from "@/hooks/useEffectiveExecutionPolicy";
import { useSuiteIdFromURL } from "@/hooks/useSuiteIdFromURL";
import TestSuiteItemFormContainer from "./TestSuiteItemFormContainer";
import {
  TestSuiteItemFormValues,
  fromFormValues,
} from "./testSuiteItemFormSchema";

const ADD_SUITE_ITEM_FORM_ID = "add-test-suite-item-form";

const DATA_PREFILLED_CONTENT = `{
  "input": "<user question>",
  "expected_output": "<expected response>",
  "<any additional fields>": "<any value>"
}`;

interface AddTestSuiteItemPanelProps {
  open: boolean;
  onClose: () => void;
  columns: DatasetItemColumn[];
  onOpenSettings: () => void;
}

const AddTestSuiteItemPanelContent: React.FC<{
  columns: DatasetItemColumn[];
  onClose: () => void;
  onOpenSettings: () => void;
}> = ({ columns, onClose, onOpenSettings }) => {
  const { t } = useTranslation("test-suite-items");
  const [tags, setTags] = useState<string[]>([]);

  const addItem = useAddItem();
  const setItemAssertionsInStore = useTestSuiteDraftStore(
    (s) => s.setItemAssertions,
  );

  const suiteId = useSuiteIdFromURL();
  const suiteAssertions = useEffectiveSuiteAssertions(suiteId);
  const suitePolicy = useEffectiveExecutionPolicy(suiteId);
  const isEmptyDataset = columns.length === 0;
  const initialData = Object.fromEntries(columns.map((col) => [col.name, ""]));

  const initialValues: TestSuiteItemFormValues = {
    description: "",
    data: isEmptyDataset
      ? DATA_PREFILLED_CONTENT
      : JSON.stringify(initialData, null, 2),
    assertions: [],
    runsPerItem: suitePolicy.runs_per_item,
    passThreshold: suitePolicy.pass_threshold,
  };

  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const isDirty = hasUnsavedChanges || tags.length > 0;

  const {
    isOpen: showConfirmDialog,
    requestConfirm,
    confirm,
    cancel,
  } = useConfirmAction();

  const onValidSubmit = (values: TestSuiteItemFormValues) => {
    const { description, data, assertions, policy } = fromFormValues(values);

    const now = new Date().toISOString();
    const saveData = data ?? initialData;

    const policyChanged =
      policy.runs_per_item !== suitePolicy.runs_per_item ||
      policy.pass_threshold !== suitePolicy.pass_threshold;

    const tempId = addItem({
      data: saveData,
      description: description || undefined,
      source: DATASET_ITEM_SOURCE.manual,
      tags,
      created_at: now,
      last_updated_at: now,
      ...(policyChanged ? { execution_policy: policy } : {}),
    });
    if (assertions.length > 0) {
      setItemAssertionsInStore(tempId, assertions);
    }
    onClose();
  };

  return (
    <>
      <div className="flex size-full flex-col">
        <div className="shrink-0 border-b bg-background p-6 pb-4">
          <div className="comet-body-accented">
            {t("addItemPanel.addSuiteItem")}
          </div>
          <TagListRenderer
            tags={tags}
            onAddTag={(tag) => setTags((prev) => [...prev, tag])}
            onDeleteTag={(tag) =>
              setTags((prev) => prev.filter((t) => t !== tag))
            }
            size="sm"
            align="start"
          />
        </div>

        <div className="flex-1 overflow-y-auto">
          <TestSuiteItemFormContainer
            formId={ADD_SUITE_ITEM_FORM_ID}
            initialValues={initialValues}
            suiteAssertions={suiteAssertions}
            suitePolicy={suitePolicy}
            onOpenSettings={onOpenSettings}
            onSubmit={onValidSubmit}
            setHasUnsavedChanges={setHasUnsavedChanges}
            showDataDescription={isEmptyDataset}
          />
        </div>

        <div className="flex shrink-0 items-center justify-end gap-2 border-t px-6 py-4">
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              if (isDirty) {
                requestConfirm(onClose);
              } else {
                onClose();
              }
            }}
          >
            {t("addItemPanel.cancel")}
          </Button>
          <Button
            variant="default"
            size="sm"
            type="submit"
            form={ADD_SUITE_ITEM_FORM_ID}
          >
            {t("addItemPanel.saveChanges")}
          </Button>
        </div>
      </div>

      <ConfirmDialog
        open={showConfirmDialog}
        setOpen={() => cancel()}
        onConfirm={cancel}
        onCancel={confirm}
        title={t("addItemPanel.discardChangesTitle")}
        description={t("addItemPanel.discardChangesDescription")}
        confirmText={t("addItemPanel.keepEditing")}
        cancelText={t("addItemPanel.discardChanges")}
        confirmButtonVariant="default"
      />
    </>
  );
};

const AddTestSuiteItemPanel: React.FC<AddTestSuiteItemPanelProps> = ({
  open,
  onClose,
  columns,
  onOpenSettings,
}) => {
  return (
    <ResizableSidePanel
      panelId="test-suite-item-panel"
      entity="item"
      open={open}
      onClose={onClose}
    >
      {open && (
        <AddTestSuiteItemPanelContent
          columns={columns}
          onClose={onClose}
          onOpenSettings={onOpenSettings}
        />
      )}
    </ResizableSidePanel>
  );
};

export default AddTestSuiteItemPanel;
