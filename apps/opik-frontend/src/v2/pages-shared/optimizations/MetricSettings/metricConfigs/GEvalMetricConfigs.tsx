import { useRef } from "react";
import { useTranslation } from "react-i18next";
import { EditorView } from "@codemirror/view";

import GEvalField from "./GEvalField";
import DatasetVariablesHint from "../DatasetVariablesHint";

import {
  GEvalMetricParameters,
  MetricParamErrors,
} from "@/types/optimizations";

interface GEvalMetricConfigsProps {
  configs: Partial<GEvalMetricParameters>;
  onChange: (configs: Partial<GEvalMetricParameters>) => void;
  datasetVariables?: string[];
  errors?: MetricParamErrors;
}

const GEvalMetricConfigs = ({
  configs,
  onChange,
  datasetVariables = [],
  errors,
}: GEvalMetricConfigsProps) => {
  const { t } = useTranslation("optimizations");
  const taskIntroEditorRef = useRef<EditorView | null>(null);
  const evalCriteriaEditorRef = useRef<EditorView | null>(null);
  const lastFocusedEditorRef = useRef<EditorView | null>(null);

  const handleVariableSelect = (variable: string) => {
    const view = lastFocusedEditorRef.current ?? evalCriteriaEditorRef.current;
    const variableText = `{{${variable}}}`;

    if (view) {
      const cursorPos = view.state.selection.main.head;
      view.dispatch({
        changes: { from: cursorPos, insert: variableText },
        selection: { anchor: cursorPos + variableText.length },
      });
      view.focus();
    }
  };

  return (
    <div className="flex w-72 flex-col gap-3">
      <GEvalField
        id="task_introduction"
        label={t("optimizations.metricConfigs.taskIntroduction")}
        value={configs.task_introduction ?? ""}
        onChange={(value) => onChange({ ...configs, task_introduction: value })}
        placeholder={t(
          "optimizations.metricConfigs.taskIntroductionPlaceholder",
        )}
        editorRef={taskIntroEditorRef}
        onFocus={() => {
          lastFocusedEditorRef.current = taskIntroEditorRef.current;
        }}
        error={errors?.task_introduction?.message}
      />

      <div className="space-y-1">
        <GEvalField
          id="evaluation_criteria"
          label={t("optimizations.metricConfigs.evaluationCriteria")}
          value={configs.evaluation_criteria ?? ""}
          onChange={(value) =>
            onChange({ ...configs, evaluation_criteria: value })
          }
          placeholder={t(
            "optimizations.metricConfigs.evaluationCriteriaPlaceholder",
          )}
          editorRef={evalCriteriaEditorRef}
          onFocus={() => {
            lastFocusedEditorRef.current = evalCriteriaEditorRef.current;
          }}
          error={errors?.evaluation_criteria?.message}
        />

        <DatasetVariablesHint
          datasetVariables={datasetVariables}
          onSelect={handleVariableSelect}
        />
      </div>
    </div>
  );
};

export default GEvalMetricConfigs;
