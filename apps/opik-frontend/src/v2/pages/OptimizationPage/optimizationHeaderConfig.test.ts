import { describe, it, expect, vi } from "vitest";

import {
  getMetricLabel,
  getOptimizationConfigItems,
  formatMetricParameterValue,
} from "./optimizationHeaderConfig";
import {
  METRIC_TYPE,
  OPTIMIZER_TYPE,
  Optimization,
} from "@/types/optimizations";

vi.mock("i18next", () => ({
  default: {
    t: (key: string) => key,
  },
}));

const studioOptimization = {
  id: "opt-1",
  objective_name: "geval",
  dataset_name: "geography_questions",
  studio_config: {
    dataset_name: "geography_questions",
    prompt: { messages: [] },
    llm_model: { model: "claude-sonnet-4-6" },
    evaluation: {
      metrics: [
        {
          type: METRIC_TYPE.G_EVAL,
          parameters: {
            task_introduction: "Judge the answer",
            evaluation_criteria: "Score 0-1",
          },
        },
      ],
    },
    optimizer: { type: OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE },
  },
} as unknown as Optimization;

describe("getMetricLabel", () => {
  it("maps a known metric type to its display label", () => {
    expect(getMetricLabel(METRIC_TYPE.G_EVAL)).toBe("common.constants.optimizations.metricOptions.gEval.label");
    expect(getMetricLabel(METRIC_TYPE.EQUALS)).toBe("common.constants.optimizations.metricOptions.equals.label");
  });

  it("falls back to the raw value for an unknown type", () => {
    expect(getMetricLabel("custom_thing")).toBe("custom_thing");
  });

  it("returns an em dash when nothing is provided", () => {
    expect(getMetricLabel(undefined)).toBe("—");
  });
});

describe("getOptimizationConfigItems", () => {
  it("reads model, algorithm and metric from studio_config", () => {
    const items = getOptimizationConfigItems(studioOptimization);

    expect(items.model).toBe("claude-sonnet-4-6");
    expect(items.algorithmLabel).toBe("common.constants.optimizations.optimizerOptions.hierarchicalReflective.label");
    expect(items.metric?.type).toBe(METRIC_TYPE.G_EVAL);
    expect(items.metric?.label).toBe("common.constants.optimizations.metricOptions.gEval.label");
    expect(items.metric?.parameters).toMatchObject({
      evaluation_criteria: "Score 0-1",
    });
  });

  it("falls back to objective_name when there is no studio_config", () => {
    const items = getOptimizationConfigItems({
      id: "opt-2",
      objective_name: "levenshtein_ratio",
    } as unknown as Optimization);

    expect(items.model).toBeUndefined();
    expect(items.algorithmLabel).toBeUndefined();
    expect(items.metric?.label).toBe("common.constants.optimizations.metricOptions.levenshtein.label");
    expect(items.metric?.parameters).toBeUndefined();
  });

  it("resolves model and algorithm from metadata for SDK runs", () => {
    const items = getOptimizationConfigItems({
      id: "opt-3",
      objective_name: "levenshtein_ratio",
      metadata: {
        optimizer: "HierarchicalReflectiveOptimizer",
        model: "gpt-4o-mini",
      },
    } as unknown as Optimization);

    expect(items.model).toBe("gpt-4o-mini");
    expect(items.algorithmLabel).toBe("common.constants.optimizations.optimizerOptions.hierarchicalReflective.label");
    expect(items.metric?.label).toBe("common.constants.optimizations.metricOptions.levenshtein.label");
  });

  it("returns empty fields for a missing optimization", () => {
    const items = getOptimizationConfigItems(undefined);
    expect(items.model).toBeUndefined();
    expect(items.algorithmLabel).toBeUndefined();
    expect(items.metric).toBeUndefined();
  });
});

describe("formatMetricParameterValue", () => {
  it("renders booleans as Yes/No", () => {
    expect(formatMetricParameterValue(true)).toBe("common:buttons.yes");
    expect(formatMetricParameterValue(false)).toBe("common:buttons.no");
  });

  it("renders empty values as an em dash", () => {
    expect(formatMetricParameterValue(null)).toBe("—");
    expect(formatMetricParameterValue(undefined)).toBe("—");
    expect(formatMetricParameterValue("")).toBe("—");
  });

  it("stringifies other values", () => {
    expect(formatMetricParameterValue("answer")).toBe("answer");
    expect(formatMetricParameterValue(0)).toBe("0");
  });
});
